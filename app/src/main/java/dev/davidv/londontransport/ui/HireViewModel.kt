package dev.davidv.londontransport.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.davidv.londontransport.data.AccountStore
import dev.davidv.londontransport.domain.WorkoutSignal
import dev.davidv.londontransport.santander.Account
import dev.davidv.londontransport.santander.CurrentRide
import dev.davidv.londontransport.santander.LoginChallenge
import dev.davidv.londontransport.santander.Password
import dev.davidv.londontransport.santander.PolarisApi
import dev.davidv.londontransport.santander.PolarisLoginResult
import dev.davidv.londontransport.santander.PolarisSession
import dev.davidv.londontransport.santander.QrCode
import dev.davidv.londontransport.santander.RideResult
import dev.davidv.londontransport.santander.UnlockResult
import java.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a successful login leads to: the camera for a ride, or the account screen. */
enum class LoginPurpose { Unlock, Account }

private const val RIDE_UNKNOWN = "Couldn't check whether you already have a ride in progress. Refresh and try again."
private const val RIDE_ACTIVE = "You already have a ride in progress."
private const val LOG_IN_AGAIN = "Log in again to unlock a bike."

sealed interface HireFlow {
  data object Idle : HireFlow
  data object AccountReady : HireFlow
  data class Login(val purpose: LoginPurpose, val email: String, val busy: Boolean, val error: String?) : HireFlow
  data class Verify(
    val purpose: LoginPurpose,
    val email: String,
    val password: Password,
    val challenge: LoginChallenge,
    val busy: Boolean,
    val error: String?,
  ) : HireFlow
  data object Scanning : HireFlow
  data class Unlocking(val bike: QrCode) : HireFlow
  /** [bike] is absent when the unlock was refused before any code was scanned. */
  data class UnlockFailed(val bike: QrCode?, val message: String) : HireFlow
  data class Tutorial(val challenge: UnlockResult.TutorialRequired, val busy: Boolean = false, val error: String? = null) : HireFlow
  data class NeedsAccess(val title: String?, val message: String) : HireFlow
  data object UnlockAccepted : HireFlow
}

sealed interface RideState {
  data object Checking : RideState
  data class Known(val ride: CurrentRide?) : RideState
  data class Unavailable(val previous: CurrentRide?, val message: String) : RideState
  data class LoggedOut(val previous: CurrentRide?) : RideState
}

data class HireState(
  val account: Account? = null,
  val flow: HireFlow = HireFlow.Idle,
  val ride: RideState = RideState.Checking,
) {
  val active: CurrentRide?
    get() = when (ride) {
      RideState.Checking -> null
      is RideState.Known -> ride.ride
      is RideState.Unavailable -> ride.previous
      is RideState.LoggedOut -> ride.previous
    }

  val rideError: String?
    get() = when (ride) {
      is RideState.Unavailable -> ride.message
      is RideState.LoggedOut -> "Your session expired. Log in again before hiring."
      else -> null
    }

  val needsLogin: Boolean
    get() = account == null || ride is RideState.LoggedOut
}

/** The hire sheets drive the flow through this, so they can be rendered without a live backend. */
interface HireActions {
  fun scan()
  fun submitLogin(email: String, password: String)
  fun submitVerification(code: String)
  fun resendCode()
  fun unlock(code: QrCode)
  fun continueTutorial()
  fun dismiss()
}

class HireViewModel(
  private val api: PolarisApi,
  private val accounts: AccountStore,
  private val workouts: WorkoutSignal,
  private val clock: Clock = Clock.systemUTC(),
) : ViewModel(), HireActions {
  private val _state = MutableStateFlow(HireState())
  val state: StateFlow<HireState> = _state.asStateFlow()
  private var operation: Job? = null
  private var refresh: Job? = null
  private var awaitingUnlock = false

  init {
    viewModelScope.launch {
      accounts.account.collectLatest { account ->
        refresh?.cancel()
        awaitingUnlock = false
        _state.update { it.copy(account = account, ride = RideState.Checking) }
        if (account == null) return@collectLatest
        while (true) {
          updateRide(account.session)
          if (_state.value.ride is RideState.LoggedOut) return@collectLatest
          delay(15_000)
        }
      }
    }
  }

  override fun scan() {
    if (operation?.isActive == true) return
    val current = _state.value
    if (current.needsLogin) {
      _state.update { it.copy(flow = HireFlow.Login(LoginPurpose.Unlock, current.account?.email.orEmpty(), false, null)) }
      return
    }
    if (current.ride !is RideState.Known) {
      _state.update { it.copy(flow = HireFlow.UnlockFailed(null, RIDE_UNKNOWN)) }
      refreshRide()
      return
    }
    if (current.active != null) {
      _state.update { it.copy(flow = HireFlow.UnlockFailed(null, RIDE_ACTIVE)) }
      return
    }
    _state.update { it.copy(flow = HireFlow.Scanning) }
  }

  fun signIn() {
    if (operation?.isActive == true) return
    val current = _state.value
    _state.update { it.copy(flow = HireFlow.Login(LoginPurpose.Account, current.account?.email.orEmpty(), false, null)) }
  }

  override fun submitLogin(email: String, password: String) {
    val login = _state.value.flow as? HireFlow.Login ?: return
    if (login.busy) return
    val address = email.trim()
    val secret = Password(password)
    _state.update { it.copy(flow = login.copy(busy = true, error = null)) }
    operation = viewModelScope.launch { finishLogin(login.purpose, address, secret, api.login(address, secret)) }
  }

  override fun resendCode() {
    val verify = _state.value.flow as? HireFlow.Verify ?: return
    if (verify.busy) return
    _state.update { it.copy(flow = verify.copy(busy = true, error = null)) }
    operation = viewModelScope.launch {
      when (val result = api.login(verify.email, verify.password)) {
        is PolarisLoginResult.Rejected -> _state.update { it.copy(flow = verify.copy(busy = false, error = result.message)) }
        is PolarisLoginResult.Unavailable -> _state.update { it.copy(flow = verify.copy(busy = false, error = result.reason)) }
        else -> finishLogin(verify.purpose, verify.email, verify.password, result)
      }
    }
  }

  private suspend fun finishLogin(purpose: LoginPurpose, email: String, password: Password, result: PolarisLoginResult) {
    when (result) {
      is PolarisLoginResult.Authenticated -> {
        accounts.save(Account(email, password, result.session))
        _state.update { it.copy(flow = when (purpose) {
          LoginPurpose.Unlock -> HireFlow.Scanning
          LoginPurpose.Account -> HireFlow.AccountReady
        }) }
      }
      is PolarisLoginResult.VerificationRequired ->
        _state.update { it.copy(flow = HireFlow.Verify(purpose, email, password, result.challenge, false, null)) }
      is PolarisLoginResult.Rejected ->
        _state.update { it.copy(flow = HireFlow.Login(purpose, email, false, result.message)) }
      is PolarisLoginResult.Unavailable ->
        _state.update { it.copy(flow = HireFlow.Login(purpose, email, false, result.reason)) }
    }
  }

  override fun submitVerification(code: String) {
    val verify = _state.value.flow as? HireFlow.Verify ?: return
    if (verify.busy) return
    if (!clock.instant().isBefore(verify.challenge.expiresAt)) {
      _state.update { it.copy(flow = HireFlow.Login(verify.purpose, verify.email, false, "That code expired. Log in again to get a new one.")) }
      return
    }
    _state.update { it.copy(flow = verify.copy(busy = true, error = null)) }
    operation = viewModelScope.launch {
      when (val result = api.verifyLogin(verify.challenge, code.trim())) {
        is PolarisLoginResult.Rejected -> _state.update { it.copy(flow = verify.copy(busy = false, error = result.message)) }
        is PolarisLoginResult.Unavailable -> _state.update { it.copy(flow = verify.copy(busy = false, error = result.reason)) }
        else -> finishLogin(verify.purpose, verify.email, verify.password, result)
      }
    }
  }

  /** Reached from the camera, and from the debug menu with a fixed code. */
  override fun unlock(code: QrCode) {
    if (operation?.isActive == true) return
    val current = _state.value
    val account = current.account
    if (account == null || current.needsLogin) {
      _state.update { it.copy(flow = HireFlow.Login(LoginPurpose.Unlock, account?.email.orEmpty(), false, LOG_IN_AGAIN)) }
      return
    }
    val ride = current.ride
    if (ride !is RideState.Known) {
      _state.update { it.copy(flow = HireFlow.UnlockFailed(code, RIDE_UNKNOWN)) }
      refreshRide()
      return
    }
    if (ride.ride != null) {
      _state.update { it.copy(flow = HireFlow.UnlockFailed(code, RIDE_ACTIVE)) }
      return
    }
    _state.update { it.copy(flow = HireFlow.Unlocking(code)) }
    operation = viewModelScope.launch { finishUnlock(account.session, code, api.unlock(account.session, code)) }
  }

  override fun continueTutorial() {
    val tutorial = _state.value.flow as? HireFlow.Tutorial ?: return
    if (tutorial.busy || operation?.isActive == true) return
    val account = _state.value.account
    if (account == null || _state.value.needsLogin || account.session.member != tutorial.challenge.continuation.member) {
      _state.update { it.copy(flow = tutorial.copy(error = "The account changed. Close this and scan the bike again.")) }
      return
    }
    if (_state.value.ride !is RideState.Known || _state.value.active != null) {
      _state.update { it.copy(flow = tutorial.copy(error = RIDE_UNKNOWN)) }
      refreshRide()
      return
    }
    _state.update { it.copy(flow = tutorial.copy(busy = true, error = null)) }
    val bike = tutorial.challenge.continuation.attempt.bike
    operation = viewModelScope.launch {
      finishUnlock(account.session, bike, api.continueUnlock(account.session, tutorial.challenge.continuation))
    }
  }

  private suspend fun finishUnlock(session: PolarisSession, bike: QrCode, result: UnlockResult) {
    when (result) {
      is UnlockResult.TutorialRequired -> _state.update { it.copy(flow = HireFlow.Tutorial(result)) }
      UnlockResult.Accepted -> {
        awaitingUnlock = true
        _state.update { it.copy(flow = HireFlow.UnlockAccepted) }
        updateRide(session)
      }
      is UnlockResult.AccessNeeded -> _state.update { it.copy(flow = HireFlow.NeedsAccess(result.title, result.message)) }
      is UnlockResult.Refused -> _state.update { it.copy(flow = HireFlow.UnlockFailed(bike, result.message)) }
      UnlockResult.LoggedOut -> {
        val email = _state.value.account?.email.orEmpty()
        accounts.clear()
        _state.update { it.copy(flow = HireFlow.Login(LoginPurpose.Unlock, email, false, LOG_IN_AGAIN)) }
      }
      is UnlockResult.Unavailable -> {
        _state.update { it.copy(flow = HireFlow.UnlockFailed(bike, result.reason)) }
        updateRide(session)
      }
    }
  }

  fun refreshRide() {
    val account = _state.value.account ?: return
    if (refresh?.isActive == true) return
    refresh = viewModelScope.launch { updateRide(account.session) }
  }

  private suspend fun updateRide(session: PolarisSession) {
    val result = api.activeRide(session)
    if (_state.value.account?.session != session) return
    when (result) {
      is RideResult.Success -> {
        _state.update { it.copy(ride = RideState.Known(result.ride)) }
        if (awaitingUnlock && result.ride?.startedAt != null) {
          awaitingUnlock = false
          workouts.cyclingStarted()
        }
      }
      RideResult.LoggedOut -> _state.update { it.copy(ride = RideState.LoggedOut(it.active)) }
      is RideResult.Unavailable -> _state.update { it.copy(ride = RideState.Unavailable(it.active, result.reason)) }
    }
  }

  override fun dismiss() {
    when (val flow = _state.value.flow) {
      is HireFlow.Unlocking -> return
      is HireFlow.Tutorial -> if (flow.busy) return
      else -> Unit
    }
    operation?.cancel()
    _state.update { it.copy(flow = HireFlow.Idle) }
  }
}
