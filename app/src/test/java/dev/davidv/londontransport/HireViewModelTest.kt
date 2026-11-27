package dev.davidv.londontransport

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import dev.davidv.londontransport.data.AccountStore
import dev.davidv.londontransport.domain.WorkoutSignal
import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.ui.HireFlow
import dev.davidv.londontransport.ui.LoginPurpose
import dev.davidv.londontransport.ui.RideState
import dev.davidv.londontransport.ui.HireViewModel
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val SESSION = PolarisSession(MemberId("member"), PolarisToken("token"))
private val ACCOUNT = Account("rider@example.test", Password("password"), SESSION)
private val BIKE = checkNotNull(QrCode.parse("ABDRDO"))

private class FakeApi : PolarisApi {
  val calls = mutableListOf<String>()
  val identifiers = mutableListOf<QrCode>()
  var login: PolarisLoginResult = PolarisLoginResult.Authenticated(SESSION)
  var verification: PolarisLoginResult = PolarisLoginResult.Authenticated(SESSION)
  var unlock: UnlockResult = UnlockResult.Accepted
  var ride: RideResult = RideResult.Success(null)
  var unlockGate: CompletableDeferred<Unit>? = null
  override suspend fun login(email: String, password: Password): PolarisLoginResult {
    calls += "login"
    return login
  }
  override suspend fun verifyLogin(challenge: LoginChallenge, code: String): PolarisLoginResult {
    calls += "verify"
    return verification
  }
  override suspend fun unlock(session: PolarisSession, bike: QrCode): UnlockResult {
    calls += "unlock:${bike.value}"
    identifiers += bike
    unlockGate?.await()
    return unlock
  }
  override suspend fun continueUnlock(session: PolarisSession, continuation: UnlockContinuation): UnlockResult {
    calls += "continue"
    unlockGate?.await()
    return unlock
  }
  override suspend fun activeRide(session: PolarisSession): RideResult {
    calls += "ride"
    return ride
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HireViewModelTest {
  @get:Rule val folder = TemporaryFolder()
  private val models = ViewModelStore()
  @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
  @After fun teardown() { models.clear(); Dispatchers.resetMain() }

  private fun model(api: PolarisApi, store: AccountStore, signal: WorkoutSignal = WorkoutSignal {}) =
    HireViewModel(api, store, signal).also { models.put("hire", it) }


  private fun hireTest(block: suspend TestScope.() -> Unit) = runTest {
    try {
      block()
    } finally {
      models.clear()
    }
  }

  @Test fun `login never initiates an unlock`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    val api = FakeApi()
    val model = model(api, store)
    model.scan()
    assertTrue(model.state.value.flow is HireFlow.Login)
    model.submitLogin(" rider@example.test ", "password")
    model.state.first { it.account != null && it.flow == HireFlow.Scanning && it.ride is RideState.Known }
    assertEquals("rider@example.test", model.state.value.account?.email)
    assertFalse(api.calls.any { it.startsWith("unlock") })
  }

  @Test fun `expired membership becomes access flow and never starts a workout`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val api = FakeApi().apply { unlock = UnlockResult.AccessNeeded("Your membership has expired", "Please renew your membership") }
    var starts = 0
    val model = model(api, store, WorkoutSignal { starts++ })
    model.state.first { it.account != null && it.ride is RideState.Known }
    model.scan()
    model.unlock(BIKE)
    model.state.first { it.flow is HireFlow.NeedsAccess }
    assertEquals(HireFlow.NeedsAccess("Your membership has expired", "Please renew your membership"), model.state.value.flow)
    assertEquals(0, starts)
  }

  @Test fun `pending unlock rejects duplicate taps and cancellation`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val gate = CompletableDeferred<Unit>()
    val api = FakeApi().apply { unlockGate = gate }
    val model = model(api, store)
    model.state.first { it.account != null && it.ride is RideState.Known }
    model.scan()
    model.unlock(BIKE)
    model.unlock(BIKE)
    model.dismiss()
    assertEquals(1, api.calls.count { it.startsWith("unlock") })
    assertEquals(HireFlow.Unlocking(BIKE), model.state.value.flow)
    gate.complete(Unit)
    model.state.first { it.flow is HireFlow.UnlockAccepted }
  }

  @Test fun `restoring an active ride prevents another hire without restarting a workout`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val api = FakeApi().apply { ride = RideResult.Success(CurrentRide(RideId("ride"), RideStatus("active"), Instant.parse("2026-11-17T18:00:00Z"), 1)) }
    var starts = 0
    val model = model(api, store, WorkoutSignal { starts++ })
    model.state.first { it.active != null }
    model.refreshRide()
    assertEquals(0, starts)
    model.scan()
    assertEquals(HireFlow.UnlockFailed(null, "You already have a ride in progress."), model.state.value.flow)
    assertFalse(api.calls.any { it.startsWith("unlock") })
  }

  @Test fun `verification is completed before persisting a session`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    val api = FakeApi().apply { login = PolarisLoginResult.VerificationRequired(LoginChallenge("challenge", Instant.parse("2099-01-01T00:00:00Z"), "Check email")) }
    val model = model(api, store)
    model.scan()
    model.submitLogin(ACCOUNT.email, ACCOUNT.password.value)
    model.state.first { it.flow is HireFlow.Verify }
    assertNull(store.account.first())
    model.submitVerification("123456")
    model.state.first { it.account != null && it.flow == HireFlow.Scanning && it.ride is RideState.Known }
    assertEquals(SESSION, store.account.first()?.session)
    assertFalse(api.calls.any { it.startsWith("unlock") })
  }

  @Test fun `Corethree tokens are never loaded as Polaris sessions`() = hireTest {
    val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") }
    preferences.edit {
      it[stringPreferencesKey("email")] = ACCOUNT.email
      it[stringPreferencesKey("password")] = ACCOUNT.password.value
      it[stringPreferencesKey("user_node_id")] = "old-node"
      it[stringPreferencesKey("auth_token")] = "old-token"
    }
    assertNull(AccountStore(preferences).account.first())
  }

  @Test fun `an uncertain unlock is never silently retried`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val api = FakeApi().apply { unlock = UnlockResult.Unavailable("Check the bike before retrying") }
    val model = model(api, store)
    model.state.first { it.account != null && it.ride is RideState.Known }
    model.scan()
    model.unlock(BIKE)
    model.state.first { it.flow == HireFlow.UnlockFailed(BIKE, "Check the bike before retrying") }
    assertEquals(1, api.calls.count { it.startsWith("unlock") })
    assertNull(model.state.value.active)
  }

  @Test fun `a lost session asks for login before any new unlock`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val api = FakeApi().apply { ride = RideResult.LoggedOut }
    val model = model(api, store)
    model.state.first { it.ride is RideState.LoggedOut }
    model.scan()
    assertTrue(model.state.value.flow is HireFlow.Login)
    assertFalse(api.calls.any { it.startsWith("unlock") })
  }

  @Test fun `failed status reads preserve the last ride and prevent another hire`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val ride = CurrentRide(RideId("ride"), RideStatus("active"), Instant.parse("2026-11-17T18:00:00Z"), 1)
    val api = FakeApi().apply { this.ride = RideResult.Success(ride) }
    val model = model(api, store)
    model.state.first { it.active != null }
    api.ride = RideResult.Unavailable("offline")
    model.refreshRide()
    model.state.first { it.ride is RideState.Unavailable }
    assertEquals(ride, model.state.value.active)
    model.scan()
    assertTrue(model.state.value.flow is HireFlow.UnlockFailed)
    assertFalse(api.calls.any { it.startsWith("unlock") })
  }

  @Test fun `workout starts once when an accepted unlock becomes an active ride`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val gate = CompletableDeferred<Unit>()
    val api = FakeApi().apply { unlockGate = gate }
    var starts = 0
    val model = model(api, store, WorkoutSignal { starts++ })
    model.state.first { it.account != null && it.ride is RideState.Known }
    model.scan()
    model.unlock(BIKE)
    assertEquals(0, starts)
    api.ride = RideResult.Success(CurrentRide(RideId("ride"), RideStatus("active"), Instant.parse("2026-11-17T18:00:00Z"), 1))
    gate.complete(Unit)
    model.state.first { it.active != null }
    model.refreshRide()
    assertEquals(1, starts)
  }


  @Test fun `debug QR unlock needs no scan and enters the usual access flow`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val api = FakeApi().apply { unlock = UnlockResult.AccessNeeded("Your membership has expired", "Please renew your membership") }
    val model = model(api, store)
    model.state.first { it.account != null && it.ride is RideState.Known }
    model.unlock(BIKE)
    model.state.first { it.flow is HireFlow.NeedsAccess }
    assertEquals(listOf(BIKE), api.identifiers)
  }

  @Test fun `debug QR unlock cannot be submitted twice or dismissed while pending`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val gate = CompletableDeferred<Unit>()
    val api = FakeApi().apply { unlockGate = gate; unlock = UnlockResult.Refused("Bike unavailable") }
    val model = model(api, store)
    model.state.first { it.account != null && it.ride is RideState.Known }
    model.unlock(BIKE)
    model.unlock(BIKE)
    model.dismiss()
    assertEquals(HireFlow.Unlocking(BIKE), model.state.value.flow)
    assertEquals(listOf(BIKE), api.identifiers)
    gate.complete(Unit)
    model.state.first { it.flow == HireFlow.UnlockFailed(BIKE, "Bike unavailable") }
  }

  private fun tutorialChallenge(): UnlockResult.TutorialRequired {
    val bike = checkNotNull(QrCode.parse("ABDRDO"))
    val attempt = UnlockAttempt(bike, MutationSessionId("session"), listOf(TutorialRetryReason("INTERSTITIAL_POLARIS_PRE_RIDE_TUTORIAL", "frt-completed")))
    return UnlockResult.TutorialRequired(RideTutorial(listOf(TutorialPage("Dock safely", "Check for a green light"))), UnlockContinuation(SESSION.member, attempt))
  }

  @Test fun `tutorial waits for confirmation and prevents duplicate continuation or dismissal while pending`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val api = FakeApi().apply { unlock = tutorialChallenge() }
    var starts = 0
    val model = model(api, store, WorkoutSignal { starts++ })
    model.state.first { it.account != null && it.ride is RideState.Known }
    model.unlock(BIKE)
    model.state.first { it.flow is HireFlow.Tutorial }
    runCurrent()
    assertFalse(api.calls.contains("continue"))
    assertEquals(0, starts)
    api.unlock = UnlockResult.Accepted
    val gate = CompletableDeferred<Unit>()
    api.unlockGate = gate
    model.continueTutorial()
    model.continueTutorial()
    model.dismiss()
    assertTrue((model.state.value.flow as HireFlow.Tutorial).busy)
    assertEquals(1, api.calls.count { it == "continue" })
    gate.complete(Unit)
    model.state.first { it.flow == HireFlow.UnlockAccepted }
    assertEquals(0, starts)
  }

  @Test fun `dismissing the tutorial never acknowledges or resumes the unlock`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val api = FakeApi().apply { unlock = tutorialChallenge() }
    val model = model(api, store)
    model.state.first { it.account != null && it.ride is RideState.Known }
    model.scan()
    model.unlock(BIKE)
    model.state.first { it.flow is HireFlow.Tutorial }
    runCurrent()
    model.dismiss()
    model.continueTutorial()
    assertEquals(HireFlow.Idle, model.state.value.flow)
    assertFalse(api.calls.contains("continue"))
  }

  @Test fun `a continuation refusal is shown against the scanned bike`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val api = FakeApi().apply { unlock = tutorialChallenge() }
    val model = model(api, store)
    model.state.first { it.account != null && it.ride is RideState.Known }
    model.unlock(BIKE)
    model.state.first { it.flow is HireFlow.Tutorial }
    runCurrent()
    api.unlock = UnlockResult.Refused("Bike unavailable")
    model.continueTutorial()
    model.state.first { it.flow == HireFlow.UnlockFailed(BIKE, "Bike unavailable") }
    assertEquals(1, api.calls.count { it == "continue" })
  }

  @Test fun `scanning does not unlock until a code is read`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    store.save(ACCOUNT)
    val api = FakeApi()
    val model = model(api, store)
    model.state.first { it.account != null && it.ride is RideState.Known }
    model.scan()
    assertEquals(HireFlow.Scanning, model.state.value.flow)
    assertFalse(api.calls.any { it.startsWith("unlock") })
    model.unlock(BIKE)
    model.state.first { it.flow == HireFlow.UnlockAccepted }
    assertEquals(listOf(BIKE), api.identifiers)
  }

  @Test fun `scanning resumes the camera after login without submitting an unlock`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    val api = FakeApi()
    val model = model(api, store)
    model.scan()
    assertTrue(model.state.value.flow is HireFlow.Login)
    model.submitLogin("rider@example.test", "password")
    model.state.first { it.account != null && it.flow == HireFlow.Scanning }
    assertFalse(api.calls.any { it.startsWith("unlock") })
  }

  @Test fun `account login never opens the scanner or submits an unlock`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    val api = FakeApi()
    val model = model(api, store)
    model.signIn()
    assertEquals(LoginPurpose.Account, (model.state.value.flow as HireFlow.Login).purpose)
    model.submitLogin("rider@example.test", "password")
    model.state.first { it.flow == HireFlow.AccountReady }
    assertFalse(api.calls.any { it.startsWith("unlock") })
  }

  @Test fun `resending a code logs in again and replaces the challenge`() = hireTest {
    val store = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    val first = LoginChallenge("first", Instant.parse("2099-01-01T00:00:00Z"), "Check email")
    val api = FakeApi().apply { login = PolarisLoginResult.VerificationRequired(first) }
    val model = model(api, store)
    model.scan()
    model.submitLogin(ACCOUNT.email, ACCOUNT.password.value)
    model.state.first { it.flow is HireFlow.Verify }
    val second = LoginChallenge("second", Instant.parse("2099-01-01T00:00:00Z"), "Check email")
    api.login = PolarisLoginResult.VerificationRequired(second)
    model.resendCode()
    model.state.first { (it.flow as? HireFlow.Verify)?.challenge == second }
    assertEquals(2, api.calls.count { it == "login" })
    assertNull(store.account.first())
  }
}
