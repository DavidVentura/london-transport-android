package dev.davidv.londontransport

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.davidv.londontransport.data.*
import dev.davidv.londontransport.domain.*
import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.threeds.*
import dev.davidv.londontransport.ui.*
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class NativePaymentViewModelTest {
  @get:Rule val folder = TemporaryFolder()
  private val dispatcher = UnconfinedTestDispatcher()
  private val clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC)
  private val option = AccessOption("Test access", "£1", emptyList(), "part://test.payment")
  private val form = PaymentForm("Test payment", "Test Rider", listOf(CardType("Visa", "test-visa")), false, "part://test.submit?postback=submit")
  private val card = CardDetails(PaymentMethod("Test Rider", form.cardTypes.single(), "4111111111111111", "12", "99",
    BillingAddress("1", "Test Street", "London", "", "UK", "TEST"), false), Cvv("123"))

  @Before fun setup() { Dispatchers.setMain(dispatcher) }
  @After fun teardown() { Dispatchers.resetMain() }

  @Test fun `chosen card and address are sealed before a failed prototype attempt and CVV follows the explicit choice`() = runTest(dispatcher) {
    val store = PaymentMethodStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("payments.preferences_pb") })
    val sealed = mutableListOf<StoredPayment>()
    val sealer = PaymentSealer { payment ->
      sealed.add(payment)
      Sealing(Sealed("wrapped", "iv", "ciphertext"), false)
    }
    val api = FakeNativeApi(form)
    val model = NativePaymentViewModel(api, store, sealer, listOf(option), clock)
    model.open()
    model.choose(option)
    model.state.first { it.flow is NativePaymentFlow.Card }
    model.pay(card, SaveRequest("My test card", false))
    model.state.first { it.flow is NativePaymentFlow.Stopped }
    val methods = store.methods.first()
    assertEquals(1, methods.size)
    assertFalse(methods.single().cvvSaved)
    assertEquals(card.method, sealed.single().method)
    assertNull(sealed.single().cvv)
    assertEquals(1, api.pays)
    assertFalse(methods.single().toString().contains(card.method.number))
  }

  @Test fun `an explicit CVV save request is passed only to the encrypted vault`() = runTest(dispatcher) {
    val store = PaymentMethodStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("payments.preferences_pb") })
    val sealed = mutableListOf<StoredPayment>()
    val api = FakeNativeApi(form)
    val model = NativePaymentViewModel(api, store, PaymentSealer { payment ->
      sealed.add(payment); Sealing(Sealed("wrapped", "iv", "ciphertext"), false)
    }, listOf(option), clock)
    model.open(); model.choose(option)
    model.state.first { it.flow is NativePaymentFlow.Card }
    model.pay(card, SaveRequest("My test card", true))
    model.state.first { it.flow is NativePaymentFlow.Stopped }
    assertEquals(card.cvv, sealed.single().cvv)
    assertTrue(store.methods.first().single().cvvSaved)
  }

  @Test fun `busy payment ignores duplicate taps and dismissal and an uncertain result cannot be paid again`() = runTest(dispatcher) {
    val store = PaymentMethodStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("payments.preferences_pb") })
    val api = FakeNativeApi(form)
    api.gate = CompletableDeferred()
    val model = NativePaymentViewModel(api, store, PaymentSealer { error("Saving was not requested") }, listOf(option), clock)
    model.open(); model.choose(option)
    model.pay(card, null); model.pay(card, null); model.dismiss()
    assertTrue(model.state.value.busy)
    assertEquals(1, api.pays)
    checkNotNull(api.gate).complete(NativePaymentResult.Stopped("Outcome unknown"))
    model.state.first { it.flow is NativePaymentFlow.Stopped }
    model.pay(card, null)
    assertEquals(1, api.pays)
  }

  @Test fun `OTP answer and cancellation are explicit and serialized`() = runTest(dispatcher) {
    val store = PaymentMethodStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("payments.preferences_pb") })
    val actions = mutableListOf<ChallengeAction>()
    val pending = CompletableDeferred<NativePaymentResult>()
    val session = object : NativeChallenge {
      override suspend fun send(action: ChallengeAction): NativePaymentResult { actions.add(action); return pending.await() }
    }
    val api = FakeNativeApi(form)
    api.reply = NativePaymentResult.Challenge(session, ChallengeOutcome.OtpRequired("Test bank", "Test information", "OTP"))
    val model = NativePaymentViewModel(api, store, PaymentSealer { error("Saving was not requested") }, listOf(option), clock)
    model.open(); model.choose(option); model.pay(card, null)
    assertTrue(model.state.value.flow is NativePaymentFlow.Challenge)
    model.answer("123456"); model.answer("654321"); model.dismiss()
    assertEquals(listOf(ChallengeAction.Answer(OtpAnswer("123456"))), actions)
    pending.complete(NativePaymentResult.Challenge(session, ChallengeOutcome.OtpRequired("Test bank", "Again", "OTP")))
    model.state.first { it.flow is NativePaymentFlow.Challenge && !it.busy }
    model.dismiss()
    assertEquals(ChallengeAction.Cancel, actions.last())
  }

  @Test fun `invalid card input never reaches storage or payment transport`() = runTest(dispatcher) {
    val store = PaymentMethodStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("payments.preferences_pb") })
    val api = FakeNativeApi(form)
    val model = NativePaymentViewModel(api, store, PaymentSealer { error("Invalid card reached storage") }, listOf(option), clock)
    model.open(); model.choose(option)
    model.pay(card.copy(method = card.method.copy(number = "invalid")), SaveRequest("Test", false))
    assertTrue((model.state.value.flow as NativePaymentFlow.Card).errors.isNotEmpty())
    assertEquals(0, api.pays)
  }

  @Test fun `directory server routing covers scheme prefix boundaries and never uses a card label`() {
    listOf("4111111111111111" to DirectoryServer.Visa, "340000000000009" to DirectoryServer.Amex,
      "370000000000002" to DirectoryServer.Amex, "5100000000000000" to DirectoryServer.Mastercard,
      "5500000000000000" to DirectoryServer.Mastercard, "2221000000000000" to DirectoryServer.Mastercard,
      "2720000000000000" to DirectoryServer.Mastercard).forEach { (number, server) ->
      assertEquals(ProtocolResult.Success(server), cardDirectoryServer(number))
    }
    listOf("2220000000000000", "2721000000000000", "5600000000000000", "6000000000000000", "invalid").forEach {
      assertTrue(cardDirectoryServer(it) is ProtocolResult.Failure)
    }
    assertTrue(cardInputErrors(card, form, YearMonth.of(2026, 10)).isEmpty())
    assertTrue(cardInputErrors(card.copy(method = card.method.copy(expiryMonth = "13")), form, YearMonth.of(2026, 10)).isNotEmpty())
    assertTrue(cardInputErrors(card.copy(method = card.method.copy(expiryYear = "20")), form, YearMonth.of(2026, 10)).isNotEmpty())
  }
}

private class FakeNativeApi(private val form: PaymentForm) : NativePaymentApi {
  var pays = 0
  var gate: CompletableDeferred<NativePaymentResult>? = null
  var reply: NativePaymentResult = NativePaymentResult.Stopped("Test failure")
  override suspend fun open(option: AccessOption): NativeFormResult = NativeFormResult.Ready(form, emptyList())
  override suspend fun pay(form: PaymentForm, card: CardDetails): NativePaymentResult { pays++; return gate?.await() ?: reply }
}
