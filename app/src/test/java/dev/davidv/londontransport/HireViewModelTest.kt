package dev.davidv.londontransport

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.davidv.londontransport.data.AccountStore
import dev.davidv.londontransport.data.ActiveHireStore
import dev.davidv.londontransport.domain.ActiveHire
import dev.davidv.londontransport.domain.FeatureId
import dev.davidv.londontransport.domain.FeatureKey
import dev.davidv.londontransport.domain.HireTarget
import dev.davidv.londontransport.domain.LayerId
import dev.davidv.londontransport.domain.PointName
import dev.davidv.londontransport.domain.ReleaseCode
import dev.davidv.londontransport.domain.TerminalName
import dev.davidv.londontransport.domain.WorkoutSignal
import dev.davidv.londontransport.santander.AccessOption
import dev.davidv.londontransport.santander.AuthToken
import dev.davidv.londontransport.santander.Credentials
import dev.davidv.londontransport.santander.HireCode
import dev.davidv.londontransport.santander.HireResult
import dev.davidv.londontransport.santander.LoginResult
import dev.davidv.londontransport.santander.Password
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

private val FRESH_CREDENTIALS = Credentials(UserNodeId("node"), AuthToken("second-token"))

private val NOW: Instant = Instant.parse("2026-08-18T21:28:26Z")

private class FakeApi(
  private val logins: MutableList<LoginResult> = mutableListOf(),
  private val hires: MutableList<HireResult> = mutableListOf(),
  private val verifications: MutableList<VerifyResult> = mutableListOf(),
) : SantanderApi {
  val calls = mutableListOf<String>()

  fun willLogin(vararg results: LoginResult) = apply { logins += results }

  fun willHire(vararg results: HireResult) = apply { hires += results }

  fun willVerify(vararg results: VerifyResult) = apply { verifications += results }

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
  private fun stores(scope: CoroutineScope): Pair<AccountStore, ActiveHireStore> =
    AccountStore(preferences(scope, "account")) to ActiveHireStore(preferences(scope, "hire"))

  private fun viewModel(
    api: SantanderApi,
    stores: Pair<AccountStore, ActiveHireStore>,
    workouts: WorkoutSignal = FakeWorkouts(),
    now: Instant = NOW,
  ) =
    HireViewModel(api, stores.first, stores.second, workouts, Clock.fixed(now, ZoneOffset.UTC))

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
  fun `an account without access is sent to buy some instead of being refused`() = runTest {
    val api =
      FakeApi()
        .willLogin(LoginResult.Success(CREDENTIALS))
        .willHire(HireResult.AccessNeeded(listOf(SINGLE)))
    val model = viewModel(api, stores(backgroundScope))

    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)

    val state = model.settled { it.flow is HireFlow.NeedsAccess }
    val needs = state.flow as HireFlow.NeedsAccess
    assertEquals(REQUEST, needs.request)
    assertEquals(CREDENTIALS, needs.credentials)
    assertEquals(listOf(SINGLE), needs.options)
    assertNull(state.failure(STATION))
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
  fun `native continuation preserves a release code without making another hire request and ignores an older purchase`() = runTest {
    val api = FakeApi().willLogin(LoginResult.Success(CREDENTIALS))
      .willHire(HireResult.AccessNeeded(listOf(SINGLE)))
      .willHire(HireResult.AccessNeeded(listOf(SINGLE)))
    val workouts = FakeWorkouts()
    val model = viewModel(api, stores(backgroundScope), workouts)
    model.rent(REQUEST)
    model.submitLogin("rider@example.com", "hunter2")
    model.settled { it.account != null }
    model.rent(REQUEST)
    val previous = model.settled { it.flow is HireFlow.NeedsAccess }.flow as HireFlow.NeedsAccess
    model.dismiss()
    model.rent(REQUEST)
    val current = model.settled { it.flow is HireFlow.NeedsAccess }.flow as HireFlow.NeedsAccess
    assertTrue(previous.purchaseId != current.purchaseId)
    model.nativePaymentCompleted(previous, released())
    assertNull(model.state.value.active)
    assertEquals(current, model.state.value.flow)
    model.nativePaymentCompleted(current, released())
    val completed = model.settled { it.active != null && it.flow == HireFlow.Idle }
    assertEquals(ReleaseCode("13212"), checkNotNull(completed.active).code)
    assertEquals(1, workouts.starts)
    assertEquals(listOf("login(rider@example.com)", "hire(001023)", "hire(001023)"), api.calls)
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
