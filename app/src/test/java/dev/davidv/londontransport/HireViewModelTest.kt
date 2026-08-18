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
import dev.davidv.londontransport.santander.AuthToken
import dev.davidv.londontransport.santander.Credentials
import dev.davidv.londontransport.santander.HireCode
import dev.davidv.londontransport.santander.HireResult
import dev.davidv.londontransport.santander.LoginResult
import dev.davidv.londontransport.santander.SantanderApi
import dev.davidv.londontransport.santander.SmsChallenge
import dev.davidv.londontransport.santander.UserNodeId
import dev.davidv.londontransport.santander.VerifyResult
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

private val NOW: Instant = Instant.parse("2026-08-18T21:28:26Z")

/** Answers whatever the test lines up, and remembers what it was asked. */
private class FakeApi(
  private val logins: MutableList<LoginResult> = mutableListOf(),
  private val hires: MutableList<HireResult> = mutableListOf(),
  private val verifications: MutableList<VerifyResult> = mutableListOf(),
) : SantanderApi {
  val calls = mutableListOf<String>()

  fun willLogin(vararg results: LoginResult) = apply { logins += results }

  fun willHire(vararg results: HireResult) = apply { hires += results }

  fun willVerify(vararg results: VerifyResult) = apply { verifications += results }

  override suspend fun login(email: String, password: String): LoginResult {
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
    now: Instant = NOW,
  ) = HireViewModel(api, stores.first, stores.second, Clock.fixed(now, ZoneOffset.UTC))

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

    assertEquals(HireFlow.Login(REQUEST, busy = false, error = null), model.state.value.flow)
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
}
