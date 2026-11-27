package dev.davidv.londontransport

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import dev.davidv.londontransport.data.*
import dev.davidv.londontransport.domain.*
import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.ui.*
import java.io.File
import java.security.GeneralSecurityException
import java.util.Currency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val PAY_SESSION = PolarisSession(MemberId("member"), PolarisToken("token"))
private val PAY_PLAN = PurchasePlan(PlanId("10017"), "Single ride", PaymentSchedule.Once, 4)
private val PAY_QUOTE = PurchaseQuote(Money(165, Currency.getInstance("GBP"), 2), Money(165, Currency.getInstance("GBP"), 2))

private class MemoryPending(var attempt: PurchaseAttempt? = null) : PendingPurchases {
  override suspend fun read(): PurchaseResult<PurchaseAttempt?> = PurchaseResult.Ready(attempt)
  override suspend fun save(attempt: PurchaseAttempt) { this.attempt = attempt }
  override suspend fun clear() { attempt = null }
}

private class FakePurchaseApi : PurchaseApi {
  val calls = mutableListOf<String>()
  var completion: PurchaseResult<Unit> = PurchaseResult.Ready(Unit)
  var eligibility: PurchaseEligibility = PurchaseEligibility.Allowed
  override suspend fun eligibility(session: PolarisSession): PurchaseResult<PurchaseEligibility> {
    calls += "eligibility"
    return PurchaseResult.Ready(eligibility)
  }
  val completionStarted = CompletableDeferred<Unit>()
  var completionGate: CompletableDeferred<Unit>? = null
  var updatedCard: ValidatedCard? = null
  var cardResult: CardPurchaseResult = CardPurchaseResult.Purchased(SalesOrderId("order-123"))
  override suspend fun plans(session: PolarisSession): PurchaseResult<List<PurchasePlan>> {
    calls += "plans"
    return PurchaseResult.Ready(listOf(PAY_PLAN))
  }
  override suspend fun quote(session: PolarisSession, plan: PurchasePlan): PurchaseResult<PurchaseQuote> {
    calls += "quote"
    return PurchaseResult.Ready(PAY_QUOTE)
  }
  override suspend fun checkout(session: PolarisSession, attempt: PurchaseAttempt, renewable: Boolean): PurchaseResult<Checkout> {
    calls += "checkout:$renewable"
    return PurchaseResult.Ready(Checkout(attempt, okhttp3.HttpUrl.Builder().scheme("https").host("london.lyftbikes.com").addPathSegments("v1/lbsbff/polaris/hpp-page/purchase").build(), emptyMap()))
  }
  override suspend fun purchase(session: PolarisSession, attempt: PurchaseAttempt, renewable: Boolean, card: ValidatedCard): CardPurchaseResult {
    calls += "purchase:$renewable"
    updatedCard = card
    return cardResult
  }
  override suspend fun complete(session: PolarisSession, completion: PaymentCompletion): PurchaseResult<Unit> {
    calls += "complete"
    completionStarted.complete(Unit)
    completionGate?.await()
    return this.completion
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseViewModelTest {
  @get:Rule val folder = TemporaryFolder()
  private val models = ViewModelStore()
  @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
  @After fun teardown() { models.clear(); Dispatchers.resetMain() }

  private fun paymentTest(block: suspend TestScope.() -> Unit) = runTest {
    try { block() } finally { models.clear() }
  }

  private suspend fun TestScope.model(
    api: FakePurchaseApi,
    pending: MemoryPending = MemoryPending(),
    sealer: PaymentSealer = PaymentSealer { Sealing(Sealed("key", "iv", "cipher"), false) },
  ): PurchaseViewModel {
    val accounts = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    accounts.save(Account("rider@example.test", Password("password"), PAY_SESSION))
    val methods = PaymentMethodStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "payment_methods.preferences_pb") })
    return PurchaseViewModel(api, accounts, methods, sealer, pending).also { models.put("purchase", it) }
  }

  private suspend fun TestScope.review(model: PurchaseViewModel) {
    model.open()
    model.state.first { it.flow is PurchaseFlow.Plans }
    runCurrent()
    model.choose(QuotedPlan(PAY_PLAN, PAY_QUOTE))
    model.state.first { it.flow is PurchaseFlow.Review }
    runCurrent()
  }

  @Test fun `catalogue and quote never create a checkout until the explicit payment tap`() = paymentTest {
    val api = FakePurchaseApi()
    val model = model(api)
    review(model)
    assertEquals(listOf("plans", "eligibility", "quote"), api.calls)
    model.setRenewable(true)
    model.checkout()
    model.state.first { it.flow is PurchaseFlow.Paying }
    assertEquals(listOf("plans", "eligibility", "quote", "checkout:true"), api.calls)
  }

  @Test fun `every plan is quoted before the list is shown and back returns to it without refetching`() = paymentTest {
    val api = FakePurchaseApi()
    val model = model(api)
    review(model)
    assertEquals(PurchaseFlow.Plans(listOf(QuotedPlan(PAY_PLAN, PAY_QUOTE))), (model.state.value.flow as PurchaseFlow.Review).let { PurchaseFlow.Plans(it.catalogue) })
    model.back()
    assertEquals(PurchaseFlow.Plans(listOf(QuotedPlan(PAY_PLAN, PAY_QUOTE))), model.state.value.flow)
    assertEquals(listOf("plans", "eligibility", "quote"), api.calls)
  }

  @Test fun `a cancelled checkout is its own outcome and keeps nothing to confirm`() = paymentTest {
    val api = FakePurchaseApi()
    val model = model(api)
    review(model)
    model.checkout()
    val paying = model.state.first { it.flow is PurchaseFlow.Paying }.flow as PurchaseFlow.Paying
    runCurrent()
    model.returned(paying.checkout.attempt.returnUrl + "?action=back")
    model.state.first { it.flow == PurchaseFlow.Cancelled }
    assertFalse(api.calls.contains("complete"))
  }

  @Test fun `a successful callback completes once and clears the pending attempt`() = paymentTest {
    val pending = MemoryPending()
    val api = FakePurchaseApi().apply { completionGate = CompletableDeferred() }
    val model = model(api, pending)
    review(model)
    model.checkout()
    val paying = model.state.first { it.flow is PurchaseFlow.Paying }.flow as PurchaseFlow.Paying
    runCurrent()
    assertEquals(paying.checkout.attempt, pending.attempt)
    val callback = paying.checkout.attempt.returnUrl + "?status=success&token=payment"
    model.returned(callback)
    model.state.first { it.flow is PurchaseFlow.Completing }
    api.completionStarted.await()
    model.returned(callback)
    assertEquals(1, api.calls.count { it == "complete" })
    checkNotNull(api.completionGate).complete(Unit)
    model.state.first { it.flow is PurchaseFlow.Completed }
    assertNull(pending.attempt)
    model.returned(callback)
    assertEquals(1, api.calls.count { it == "complete" })
  }

  @Test fun `an unmatched callback never submits confirmation or discards the actual attempt`() = paymentTest {
    val pending = MemoryPending()
    val api = FakePurchaseApi()
    val model = model(api, pending)
    review(model)
    model.checkout()
    model.state.first { it.flow is PurchaseFlow.Paying }
    runCurrent()
    model.returned("londontransport://hpp_purchase/unrelated?status=success&token=wrong")
    model.state.first { it.flow is PurchaseFlow.Failed }
    assertNotNull(pending.attempt)
    assertFalse(api.calls.contains("complete"))
  }

  @Test fun `a cold return can complete the persisted checkout`() = paymentTest {
    val attempt = PurchaseAttempt(PurchaseId("previous"), PAY_SESSION.member, PAY_PLAN, PAY_QUOTE)
    val api = FakePurchaseApi()
    val pending = MemoryPending(attempt)
    val model = model(api, pending)
    model.returned(attempt.returnUrl + "?status=success&token=payment")
    model.state.first { it.flow is PurchaseFlow.Completed }
    assertEquals(listOf("complete"), api.calls)
    assertNull(pending.attempt)
  }

  @Test fun `a leftover pending record does not block starting a new purchase`() = paymentTest {
    val pending = MemoryPending(PurchaseAttempt(PurchaseId("previous"), PAY_SESSION.member, PAY_PLAN, PAY_QUOTE))
    val api = FakePurchaseApi()
    val model = model(api, pending)
    model.open()
    model.state.first { it.flow is PurchaseFlow.Plans }
  }

  @Test fun `failed confirmation preserves the attempt and requires an explicit retry`() = paymentTest {
    val attempt = PurchaseAttempt(PurchaseId("previous"), PAY_SESSION.member, PAY_PLAN, PAY_QUOTE)
    val pending = MemoryPending(attempt)
    val api = FakePurchaseApi().apply { completion = PurchaseResult.Failed("offline") }
    val model = model(api, pending)
    model.returned(attempt.returnUrl + "?status=success&token=payment")
    model.state.first { it.flow is PurchaseFlow.ConfirmationFailed }
    runCurrent()
    assertEquals(1, api.calls.count { it == "complete" })
    assertNotNull(pending.attempt)
    api.completion = PurchaseResult.Ready(Unit)
    model.retryConfirmation()
    model.state.first { it.flow is PurchaseFlow.Completed }
    assertEquals(2, api.calls.count { it == "complete" })
  }

  @Test fun `a legacy saved card buys the pass without opening hosted checkout`() = paymentTest {
    val api = FakePurchaseApi()
    val model = model(api)
    review(model)
    model.enterCard()
    val card = CardDetails(PaymentMethod("Test", CardType("Visa Debit", "old-form-type-id"), "4111111111111111", "12", "99",
      BillingAddress("1", "Street", "London", "", "GB", "N1 1AA"), false), Cvv("123"))
    model.pay(card, null)
    model.state.first { it.flow is PurchaseFlow.Completed }
    assertEquals(listOf("plans", "eligibility", "quote", "purchase:false"), api.calls)
    assertEquals(card, api.updatedCard?.details)
  }

  @Test fun `native rejection stays on card input and does not open a website`() = paymentTest {
    val api = FakePurchaseApi().apply { cardResult = CardPurchaseResult.Rejected("Card purchase failed (HTTP 400): DECLINED") }
    val pending = MemoryPending()
    val model = model(api, pending)
    review(model)
    model.enterCard()
    val card = CardDetails(PaymentMethod("Test", CardType("Card", "card"), "4111111111111111", "12", "99",
      BillingAddress("1", "Street", "London", "", "GB", "N1 1AA"), false), Cvv("123"))
    model.pay(card, null)
    val entry = model.state.first { (it.flow as? PurchaseFlow.Card)?.errors?.isNotEmpty() == true }.flow as PurchaseFlow.Card
    assertTrue(entry.errors.single().contains("DECLINED"))
    assertEquals(listOf("plans", "eligibility", "quote", "purchase:false"), api.calls)
    assertNull(pending.attempt)
  }

  @Test fun `an uncertain native payment preserves its attempt without retry or hosted checkout`() = paymentTest {
    val api = FakePurchaseApi().apply { cardResult = CardPurchaseResult.Uncertain("Check your account") }
    val pending = MemoryPending()
    val model = model(api, pending)
    review(model)
    model.enterCard()
    val card = CardDetails(PaymentMethod("Test", CardType("Card", "card"), "4111111111111111", "12", "99",
      BillingAddress("1", "Street", "London", "", "GB", "N1 1AA"), false), Cvv("123"))
    model.pay(card, null)
    model.state.first { it.flow is PurchaseFlow.Uncertain }
    assertNotNull(pending.attempt)
    assertEquals(listOf("plans", "eligibility", "quote", "purchase:false"), api.calls)
  }

  @Test fun `an existing pass blocks payment choices before a card or checkout is offered`() = paymentTest {
    val api = FakePurchaseApi().apply { eligibility = PurchaseEligibility.Blocked(PAY_PLAN.id) }
    val model = model(api)
    model.open()
    val unavailable = model.state.first { it.flow is PurchaseFlow.Unavailable }.flow as PurchaseFlow.Unavailable
    assertTrue(unavailable.message.contains(PAY_PLAN.name))
    model.choose(QuotedPlan(PAY_PLAN, PAY_QUOTE))
    model.checkout()
    model.enterCard()
    assertEquals(listOf("plans", "eligibility"), api.calls)
  }

  @Test fun `vault failure prevents billing changes and payment submission`() = paymentTest {
    val api = FakePurchaseApi()
    val model = model(api, sealer = PaymentSealer { throw GeneralSecurityException("test") })
    review(model)
    model.enterCard()
    val type = CardType("Visa", "visa")
    val card = CardDetails(PaymentMethod("Test", type, "4111111111111111", "12", "99", BillingAddress("1", "Street", "London", "", "GB", "N1 1AA"), false), Cvv("123"))
    model.pay(card, SaveRequest("Card", false))
    model.state.first { (it.flow as? PurchaseFlow.Card)?.errors?.isNotEmpty() == true }
    assertEquals(listOf("plans", "eligibility", "quote"), api.calls)
    assertNull(api.updatedCard)
  }
}
