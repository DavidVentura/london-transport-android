package dev.davidv.londontransport

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.davidv.londontransport.data.AccountStore
import dev.davidv.londontransport.data.ActiveHireStore
import dev.davidv.londontransport.data.PaymentMethodStore
import dev.davidv.londontransport.data.Sealing
import dev.davidv.londontransport.data.StoredPayment
import dev.davidv.londontransport.domain.ActiveHire
import dev.davidv.londontransport.domain.FeatureId
import dev.davidv.londontransport.domain.FeatureKey
import dev.davidv.londontransport.domain.HireTarget
import dev.davidv.londontransport.domain.LayerId
import dev.davidv.londontransport.domain.PaymentMethodId
import dev.davidv.londontransport.domain.SaveRequest
import dev.davidv.londontransport.domain.SavedPaymentMethod
import dev.davidv.londontransport.domain.Sealed
import dev.davidv.londontransport.domain.PointName
import dev.davidv.londontransport.domain.ReleaseCode
import dev.davidv.londontransport.domain.TerminalName
import dev.davidv.londontransport.domain.WorkoutSignal
import dev.davidv.londontransport.santander.AccessOption
import dev.davidv.londontransport.santander.AuthToken
import dev.davidv.londontransport.santander.BillingAddress
import dev.davidv.londontransport.santander.CardType
import dev.davidv.londontransport.santander.Cvv
import dev.davidv.londontransport.santander.PaymentMethod
import dev.davidv.londontransport.santander.Credentials
import dev.davidv.londontransport.santander.HireCode
import dev.davidv.londontransport.santander.HireResult
import dev.davidv.londontransport.santander.LoginResult
import dev.davidv.londontransport.santander.CardDetails
import dev.davidv.londontransport.santander.Password
import dev.davidv.londontransport.santander.PayResult
import dev.davidv.londontransport.santander.PaymentForm
import dev.davidv.londontransport.santander.PaymentResult
import dev.davidv.londontransport.santander.SantanderApi
import dev.davidv.londontransport.santander.SmsChallenge
import dev.davidv.londontransport.santander.UserNodeId
import dev.davidv.londontransport.santander.VerifyResult
import dev.davidv.londontransport.santander.WebsitePass
import dev.davidv.londontransport.ui.HireFlow
import dev.davidv.londontransport.ui.HireRequest
import dev.davidv.londontransport.ui.HireState
import dev.davidv.londontransport.ui.HireViewModel
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val STATION = FeatureKey(LayerId("santander-cycles"), FeatureId("BikePoints_1"))

private val REQUEST =
  HireRequest(
    station = STATION,
    stationName = "River Street, Clerkenwell",
    target = HireTarget(TerminalName("001023"), PointName("River Street, Clerkenwell")),
  )

private val CHALLENGE =
  SmsChallenge(
    verifyUri = "part://Clients.TfL.GenerateLCHSMembershipVerifyForm?Submit=1",
    pincodeFields = listOf("lchs_pincode_1", "lchs_pincode_2"),
    message = "We texted you a code",
  )

private val CREDENTIALS = Credentials(UserNodeId("node"), AuthToken("token"))

private val SINGLE =
  AccessOption(
    name = "Single",
    price = "£1.65 per person",
    terms = listOf("30mins access"),
    selectUri = "part://Clients.TfL.EBikePhase2.TSUF.GenerateLCHSSubscriptionPicker?action=subscribe10017",
  )

private val PAYMENT_FORM =
  PaymentForm(
    title = "Payment details - Single",
    cardholder = "Rider Name",
    cardTypes = listOf(CardType("Visa", "Visa")),
    autopay = true,
    submitUri = "part://Clients.TfL.EBikePhase2.TSUF.GenerateLCHSPaymentDetails?postback=submit",
  )

private val CARD =
  CardDetails(
    PaymentMethod(
      cardholder = "Rider Name",
      type = CardType("Visa", "Visa"),
      number = "4111111111111111",
      expiryMonth = "01",
      expiryYear = "30",
      address = BillingAddress("1", "River Street", "London", "", "United Kingdom", "EC1R 1XX"),
      autopay = true,
    ),
    Cvv("123"),
  )

private val SEALED = Sealed(wrappedKey = "a2V5", iv = "aXY=", ciphertext = "c2VhbGVk")

private val SAVE = SaveRequest(name = "Everyday Visa", keepCvv = false)

private val SAVED =
  SavedPaymentMethod(
    id = PaymentMethodId("method-1"),
    name = "Everyday Visa",
    summary = "Visa ••1111",
    cvvSaved = false,
    sealed = SEALED,
  )

private val FRESH_CREDENTIALS = Credentials(UserNodeId("node"), AuthToken("second-token"))

private val NOW: Instant = Instant.parse("2026-08-18T21:28:26Z")

private class FakeApi(
  private val logins: MutableList<LoginResult> = mutableListOf(),
  private val hires: MutableList<HireResult> = mutableListOf(),
  private val verifications: MutableList<VerifyResult> = mutableListOf(),
  private val payments: MutableList<PaymentResult> = mutableListOf(),
  private val paid: MutableList<PayResult> = mutableListOf(),
) : SantanderApi {
  val calls = mutableListOf<String>()

  fun willLogin(vararg results: LoginResult) = apply { logins += results }

  fun willHire(vararg results: HireResult) = apply { hires += results }

  fun willVerify(vararg results: VerifyResult) = apply { verifications += results }

  fun willOpenPayment(vararg results: PaymentResult) = apply { payments += results }

  fun willPay(vararg results: PayResult) = apply { paid += results }

  override suspend fun login(email: String, password: Password): LoginResult {
    calls += "login($email)"
    return logins.removeFirst()
  }

  override suspend fun hire(
    credentials: Credentials,
    terminal: TerminalName,
    point: PointName,
  ): HireResult {
    calls += "hire(${terminal.value})"
    return hires.removeFirst()
  }

  override suspend fun chooseAccess(
    credentials: Credentials,
    option: AccessOption,
  ): PaymentResult {
    calls += "chooseAccess(${option.name})"
    return payments.removeFirst()
  }

  override suspend fun pay(
    credentials: Credentials,
    form: PaymentForm,
    card: CardDetails,
  ): PayResult {
    calls += "pay"
    return paid.removeFirst()
  }

  override suspend fun verifyDevice(
    credentials: Credentials,
    challenge: SmsChallenge,
    digits: String,
  ): VerifyResult {
    calls += "verify($digits)"
    return verifications.removeFirst()
  }
}

private class FakeWorkouts : WorkoutSignal {
  var starts = 0
    private set

  override fun cyclingStarted() {
    starts += 1
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HireViewModelTest {
  @get:Rule val folder = TemporaryFolder()

  private val dispatcher = UnconfinedTestDispatcher()

  @Before
  fun useTestDispatcher() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun releaseDispatcher() {
    Dispatchers.resetMain()
  }

  private fun preferences(scope: CoroutineScope, name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope) {
      File(folder.root, "$name.preferences_pb")
    }

  /** One set of stores per test: two DataStores over one file refuse to run together. */
  private fun stores(scope: CoroutineScope): Triple<AccountStore, ActiveHireStore, PaymentMethodStore> =
    Triple(
      AccountStore(preferences(scope, "account")),
      ActiveHireStore(preferences(scope, "hire")),
      PaymentMethodStore(preferences(scope, "payment_methods")),
    )

  private fun viewModel(
    api: SantanderApi,
    stores: Triple<AccountStore, ActiveHireStore, PaymentMethodStore>,
    workouts: WorkoutSignal = FakeWorkouts(),
    now: Instant = NOW,
  ) =
    HireViewModel(
      api,
      stores.first,
      stores.second,
      stores.third,
      { _ -> Sealing(SEALED, previousLost = false) },
      workouts,
      Clock.fixed(now, ZoneOffset.UTC),
    )

  /**
   * The stores write to a real file off the test dispatcher, so every step that saves lands a beat
   * after the call that caused it. Tests wait for the state they are about to assert on.
   */
  private suspend fun HireViewModel.settled(step: (HireState) -> Boolean): HireState =
    state.first(step)

  private fun released(until: LocalTime? = LocalTime.of(22, 38)) =
    HireResult.Released(HireCode(ReleaseCode("13212"), "River Street, Clerkenwell", until))

  @Test
  fun `renting without an account asks for one, and the dock is not called`() = runTest {
    val api = FakeApi()
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)

    assertEquals(
      HireFlow.Login(REQUEST, email = "", busy = false, error = null),
      model.state.value.flow,
    )
    assertEquals(emptyList<String>(), api.calls)
  }

  @Test
  fun `logging in logs in and stops there, without releasing a bike`() = runTest {
    val api = FakeApi().willLogin(LoginResult.Success(CREDENTIALS))
    val stores = stores(backgroundScope)
    val model = viewModel(api, stores)

    model.rent(REQUEST)
    model.submitLogin(" rider@example.com ", "hunter2")
    val settled = model.settled { it.account != null }

    assertEquals(listOf("login(rider@example.com)"), api.calls)
    assertEquals(HireFlow.Idle, settled.flow)
    assertEquals("rider@example.com", settled.account?.email)
    assertEquals(CREDENTIALS, stores.first.account.first()?.credentials)
    assertNull(settled.active)
  }

  @Test
  fun `the tap after logging in is the one that releases a bike`() = runTest {
    val api = FakeApi().willLogin(LoginResult.Success(CREDENTIALS)).willHire(released())
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)

    assertEquals(listOf("login(rider@example.com)", "hire(001023)"), api.calls)
    assertEquals(ReleaseCode("13212"), model.settled { it.active != null }.active?.code)
  }

  @Test
  fun `a released code is kept against its dock until the server's deadline`() = runTest {
    val api = FakeApi().willLogin(LoginResult.Success(CREDENTIALS)).willHire(released())
    val stores = stores(backgroundScope)
    val model = viewModel(api, stores)

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)

    val active = model.settled { it.active != null }.active!!
    assertEquals(STATION, active.station)
    assertEquals(ReleaseCode("13212"), active.code)
    assertEquals(Instant.parse("2026-08-18T21:38:00Z"), active.expiresAt)
    assertEquals(active, stores.second.hire.first())
  }

  @Test
  fun `a rejected login stays on the form with the reason`() = runTest {
    val api = FakeApi().willLogin(LoginResult.Rejected("Those details were not accepted."))
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "wrong")

    val flow = model.settled { (it.flow as? HireFlow.Login)?.error != null }.flow as HireFlow.Login
    assertEquals("Those details were not accepted.", flow.error)
    assertTrue(!flow.busy)
  }

  @Test
  fun `an unknown device verifies once, then the same request goes through`() = runTest {
    val api =
      FakeApi()
        .willLogin(LoginResult.Success(CREDENTIALS))
        .willHire(HireResult.VerificationNeeded(CHALLENGE), released())
        .willVerify(VerifyResult.Verified)
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)
    val challenged = model.settled { it.flow is HireFlow.Verify }
    assertEquals(CHALLENGE, (challenged.flow as HireFlow.Verify).challenge)

    model.submitVerification("42")
    val released = model.settled { it.active != null }

    assertEquals(
      listOf("login(rider@example.com)", "hire(001023)", "verify(42)", "hire(001023)"),
      api.calls,
    )
    assertEquals(ReleaseCode("13212"), released.active?.code)
  }

  @Test
  fun `a wrong sms code keeps the dialog open with what the server said`() = runTest {
    val api =
      FakeApi()
        .willLogin(LoginResult.Success(CREDENTIALS))
        .willHire(HireResult.VerificationNeeded(CHALLENGE))
        .willVerify(VerifyResult.Rejected("That code was not accepted."))
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)
    model.settled { it.flow is HireFlow.Verify }
    model.submitVerification("11")

    val state = model.settled { (it.flow as? HireFlow.Verify)?.error != null }
    assertEquals("That code was not accepted.", (state.flow as HireFlow.Verify).error)
    assertNull(state.active)
  }

  @Test
  fun `an account without access is shown the periods instead of a refusal`() = runTest {
    val options = listOf(SINGLE)
    val api =
      FakeApi()
        .willLogin(LoginResult.Success(CREDENTIALS))
        .willHire(HireResult.AccessNeeded(options))
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)

    val state = model.settled { it.flow is HireFlow.NeedsAccess }
    assertEquals(HireFlow.NeedsAccess(REQUEST, CREDENTIALS, options, opening = null), state.flow)
    assertNull(state.failure(STATION))
  }

  @Test
  fun `picking a period opens the card page, and paying releases the bike`() = runTest {
    val api =
      FakeApi()
        .willLogin(LoginResult.Success(CREDENTIALS))
        .willHire(HireResult.AccessNeeded(listOf(SINGLE)))
        .willOpenPayment(PaymentResult.Form(PAYMENT_FORM))
        .willPay(PayResult.Hire(released()))
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)
    model.settled { it.flow is HireFlow.NeedsAccess }
    model.chooseAccess(SINGLE)
    assertEquals(
      HireFlow.Paying(
        REQUEST,
        CREDENTIALS,
        PAYMENT_FORM,
        busy = false,
        errors = emptyList(),
        save = null,
        savedAs = null,
      ),
      model.settled { it.flow is HireFlow.Paying }.flow,
    )
    model.pay(CARD, SAVE)

    val state = model.settled { it.active != null && it.savedMethods.isNotEmpty() }
    assertEquals(
      listOf(SAVED.copy(id = state.savedMethods.single().id)),
      state.savedMethods,
    )
    assertEquals(ReleaseCode("13212"), state.active?.code)
    assertEquals(
      listOf("login(rider@example.com)", "hire(001023)", "chooseAccess(Single)", "pay"),
      api.calls,
    )
  }

  @Test
  fun `a refused card keeps the card sheet open with the reason, ready for another go`() = runTest {
    val error = "Please check your card number or the card type"
    val api =
      FakeApi()
        .willLogin(LoginResult.Success(CREDENTIALS))
        .willHire(HireResult.AccessNeeded(listOf(SINGLE)))
        .willOpenPayment(PaymentResult.Form(PAYMENT_FORM))
        .willPay(PayResult.Rejected(PAYMENT_FORM, listOf(error)))
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)
    model.settled { it.flow is HireFlow.NeedsAccess }
    model.chooseAccess(SINGLE)
    model.settled { it.flow is HireFlow.Paying }
    model.pay(CARD, SAVE)

    val state =
      model.settled {
        (it.flow as? HireFlow.Paying)?.errors?.isNotEmpty() == true && it.savedMethods.isNotEmpty()
      }
    val saved = state.savedMethods.single()
    // The save choice survives the rejection, so the corrected retry goes out without asking again,
    // and replaces the entry this attempt saved rather than adding a second one.
    assertEquals(
      HireFlow.Paying(
        REQUEST,
        CREDENTIALS,
        PAYMENT_FORM,
        busy = false,
        errors = listOf(error),
        save = SAVE,
        savedAs = saved.id,
      ),
      state.flow,
    )
    assertEquals(StoredPayment(CARD.method, CARD.cvv), state.draft)
  }

  @Test
  fun `editing a saved method fills the form and the next attempt replaces it`() = runTest {
    val stores = stores(backgroundScope)
    stores.third.put(SAVED)
    val api =
      FakeApi()
        .willLogin(LoginResult.Success(CREDENTIALS))
        .willHire(HireResult.AccessNeeded(listOf(SINGLE)))
        .willOpenPayment(PaymentResult.Form(PAYMENT_FORM))
        .willPay(PayResult.Rejected(PAYMENT_FORM, listOf("Please check your card number")))
    val model = viewModel(api, stores)
    val opened = StoredPayment(CARD.method, cvv = null)

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)
    model.settled { it.flow is HireFlow.NeedsAccess }
    model.chooseAccess(SINGLE)
    model.settled { it.flow is HireFlow.Paying && it.savedMethods.isNotEmpty() }
    model.edit(SAVED, opened)
    assertEquals(opened, model.state.value.draft)
    model.pay(CARD, (model.state.value.flow as HireFlow.Paying).save)

    val state = model.settled { (it.flow as? HireFlow.Paying)?.errors?.isNotEmpty() == true }
    assertEquals(SAVED.id, (state.flow as HireFlow.Paying).savedAs)
    assertEquals(listOf(SAVED.id), state.savedMethods.map(SavedPaymentMethod::id))
  }

  @Test
  fun `buying on the website ends back at the dock, waiting for the rider to rent`() = runTest {
    val api =
      FakeApi()
        .willLogin(LoginResult.Success(CREDENTIALS))
        .willHire(HireResult.AccessNeeded(listOf(SINGLE)))
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)
    model.settled { it.flow is HireFlow.NeedsAccess }
    model.buyOnWebsite(WebsitePass.Single)
    assertEquals(HireFlow.BuyingOnWebsite(REQUEST, WebsitePass.Single), model.state.value.flow)
    model.purchaseCompleted()

    assertEquals(HireFlow.Bought(REQUEST), model.state.value.flow)
    assertTrue(model.state.value.bought(STATION))
    assertEquals(listOf("login(rider@example.com)", "hire(001023)"), api.calls)
  }

  @Test
  fun `forgetting a payment method removes only that one`() = runTest {
    val stores = stores(backgroundScope)
    val other = SAVED.copy(id = PaymentMethodId("method-2"), name = "Work card")
    stores.third.put(SAVED)
    stores.third.put(other)
    val model = viewModel(FakeApi(), stores)

    model.forgetMethod(SAVED.id)

    assertEquals(listOf(other), model.settled { it.savedMethods == listOf(other) }.savedMethods)
  }

  @Test
  fun `a refusal is shown against the dock that refused and nowhere else`() = runTest {
    val api =
      FakeApi()
        .willLogin(LoginResult.Success(CREDENTIALS))
        .willHire(HireResult.Refused("Sorry, but there are no bikes available at this location."))
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)

    val state = model.settled { it.flow is HireFlow.Failed }
    assertEquals("Sorry, but there are no bikes available at this location.", state.failure(STATION))
    assertNull(state.failure(FeatureKey(LayerId("santander-cycles"), FeatureId("BikePoints_2"))))
  }

  @Test
  fun `a code left over from an earlier run is dropped once the dock would refuse it`() = runTest {
    val stale =
      ActiveHire(
        station = STATION,
        stationName = "River Street, Clerkenwell",
        code = ReleaseCode("13212"),
        expiresAt = NOW.minusSeconds(60),
      )
    val stores = stores(backgroundScope)
    stores.second.save(stale)

    val model = viewModel(FakeApi(), stores)

    assertNull(model.settled { true }.active)
    assertNull(stores.second.hire.first { it == null })
  }

  @Test
  fun `releasing a bike says a ride has started, once`() = runTest {
    val workouts = FakeWorkouts()
    val api = FakeApi().willLogin(LoginResult.Success(CREDENTIALS)).willHire(released())
    val model = viewModel(api, stores(backgroundScope), workouts)

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)
    model.settled { it.active != null }

    assertEquals(1, workouts.starts)
  }

  @Test
  fun `a dock that refuses starts no ride`() = runTest {
    val workouts = FakeWorkouts()
    val api =
      FakeApi()
        .willLogin(LoginResult.Success(CREDENTIALS))
        .willHire(HireResult.Refused("Sorry, but there are no bikes available at this location."))
    val model = viewModel(api, stores(backgroundScope), workouts)

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)
    model.settled { it.flow is HireFlow.Failed }

    assertEquals(0, workouts.starts)
  }

  @Test
  fun `a code still held from an earlier run does not start the ride again`() = runTest {
    val workouts = FakeWorkouts()
    val stores = stores(backgroundScope)
    stores.second.save(
      ActiveHire(
        station = STATION,
        stationName = "River Street, Clerkenwell",
        code = ReleaseCode("13212"),
        expiresAt = NOW.plusSeconds(600),
      )
    )

    val model = viewModel(FakeApi(), stores, workouts)
    model.settled { it.active != null }

    assertEquals(0, workouts.starts)
  }

  @Test
  fun `a token the backend has dropped is replaced from the saved password, in one tap`() =
    runTest {
      val api =
        FakeApi()
          .willLogin(LoginResult.Success(CREDENTIALS), LoginResult.Success(FRESH_CREDENTIALS))
          .willHire(HireResult.LoggedOut, released())
      val stores = stores(backgroundScope)
      val model = viewModel(api, stores)

      model.rent(REQUEST)
      model.submitLogin("rider@example.com", "hunter2")
      model.settled { it.account != null }
      model.rent(REQUEST)

      val state = model.settled { it.active != null }
      assertEquals(
        listOf(
          "login(rider@example.com)",
          "hire(001023)",
          "login(rider@example.com)",
          "hire(001023)",
        ),
        api.calls,
      )
      assertEquals(HireFlow.Idle, state.flow)
      assertEquals(ReleaseCode("13212"), state.active?.code)
      assertEquals(
        Password("hunter2"),
        stores.first.account.first { it?.credentials == FRESH_CREDENTIALS }?.password,
      )
    }

  @Test
  fun `a password the backend no longer accepts puts the login form back, with the email in it`() =
    runTest {
      val api =
        FakeApi()
          .willLogin(
            LoginResult.Success(CREDENTIALS),
            LoginResult.Rejected("Those details were not accepted."),
          )
          .willHire(HireResult.LoggedOut)
      val model = viewModel(api, stores(backgroundScope))

      model.rent(REQUEST)
      model.submitLogin("rider@example.com", "hunter2")
      model.settled { it.account != null }
      model.rent(REQUEST)

      val flow = model.settled { it.flow is HireFlow.Login }.flow as HireFlow.Login
      assertEquals("rider@example.com", flow.email)
      assertEquals("Those details were not accepted.", flow.error)
      assertEquals(REQUEST, flow.request)
    }

  @Test
  fun `being logged out again straight after logging in stops asking and hands over the form`() =
    runTest {
      val workouts = FakeWorkouts()
      val api =
        FakeApi()
          .willLogin(LoginResult.Success(CREDENTIALS), LoginResult.Success(FRESH_CREDENTIALS))
          .willHire(HireResult.LoggedOut, HireResult.LoggedOut)
      val model = viewModel(api, stores(backgroundScope), workouts)

      model.rent(REQUEST)
      model.submitLogin("rider@example.com", "hunter2")
      model.settled { it.account != null }
      model.rent(REQUEST)

      val flow = model.settled { it.flow is HireFlow.Login }.flow as HireFlow.Login
      assertEquals("rider@example.com", flow.email)
      assertTrue(flow.error!!.contains("logged this device out"))
      assertEquals(2, api.calls.count { it == "hire(001023)" })
      assertEquals(0, workouts.starts)
    }

  @Test
  fun `losing the session while verifying asks for the login rather than the digits`() = runTest {
    val api =
      FakeApi()
        .willLogin(LoginResult.Success(CREDENTIALS))
        .willHire(HireResult.VerificationNeeded(CHALLENGE))
        .willVerify(VerifyResult.LoggedOut)
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)
    model.settled { it.flow is HireFlow.Verify }
    model.submitVerification("42")

    val flow = model.settled { it.flow is HireFlow.Login }.flow as HireFlow.Login
    assertEquals("rider@example.com", flow.email)
    assertEquals(REQUEST, flow.request)
  }
}
