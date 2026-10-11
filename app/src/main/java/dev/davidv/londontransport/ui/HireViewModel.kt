package dev.davidv.londontransport.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.davidv.londontransport.data.AccountStore
import dev.davidv.londontransport.data.ActiveHireStore
import dev.davidv.londontransport.data.PaymentMethodStore
import dev.davidv.londontransport.data.PaymentSealer
import dev.davidv.londontransport.data.StoredPayment
import dev.davidv.londontransport.domain.ActiveHire
import dev.davidv.londontransport.domain.FeatureKey
import dev.davidv.londontransport.domain.HireTarget
import dev.davidv.londontransport.domain.PaymentMethodId
import dev.davidv.londontransport.domain.SaveRequest
import dev.davidv.londontransport.domain.SavedPaymentMethod
import dev.davidv.londontransport.domain.WorkoutSignal
import dev.davidv.londontransport.santander.AccessOption
import dev.davidv.londontransport.santander.Account
import dev.davidv.londontransport.santander.CardDetails
import dev.davidv.londontransport.santander.Credentials
import dev.davidv.londontransport.santander.HireResult
import dev.davidv.londontransport.santander.LoginResult
import dev.davidv.londontransport.santander.Password
import dev.davidv.londontransport.santander.PayResult
import dev.davidv.londontransport.santander.PaymentForm
import dev.davidv.londontransport.santander.PaymentResult
import dev.davidv.londontransport.santander.SantanderApi
import dev.davidv.londontransport.santander.SmsChallenge
import dev.davidv.londontransport.santander.VerifyResult
import dev.davidv.londontransport.santander.releaseCodeExpiry
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HireRequest(
  val station: FeatureKey,
  val stationName: String,
  val target: HireTarget,
)

sealed interface HireFlow {
  data object Idle : HireFlow

  data class Login(
    val request: HireRequest,
    val email: String,
    val busy: Boolean,
    val error: String?,
  ) : HireFlow

  data class Releasing(val request: HireRequest) : HireFlow

  data class Verify(
    val request: HireRequest,
    val credentials: Credentials,
    val challenge: SmsChallenge,
    val busy: Boolean,
    val error: String?,
  ) : HireFlow

  data class NeedsAccess(
    val request: HireRequest,
    val credentials: Credentials,
    val options: List<AccessOption>,
    val opening: AccessOption?,
  ) : HireFlow

  data class Paying(
    val request: HireRequest,
    val credentials: Credentials,
    val form: PaymentForm,
    val busy: Boolean,
    val errors: List<String>,
    val save: SaveRequest?,
    /** The entry this sheet already saved, so a retry replaces it rather than adding another. */
    val savedAs: PaymentMethodId?,
  ) : HireFlow

  data class Failed(val request: HireRequest, val message: String) : HireFlow
}

data class HireState(
  val account: Account? = null,
  val flow: HireFlow = HireFlow.Idle,
  val active: ActiveHire? = null,
  val savedMethods: List<SavedPaymentMethod> = emptyList(),
  /** The last card typed, kept in memory only, so a failed attempt never means typing it again. */
  val draft: StoredPayment? = null,
) {
  fun releasing(station: FeatureKey): Boolean =
    flow is HireFlow.Releasing && flow.request.station == station

  fun failure(station: FeatureKey): String? =
    (flow as? HireFlow.Failed)?.takeIf { it.request.station == station }?.message
}

class HireViewModel(
  private val api: SantanderApi,
  private val accounts: AccountStore,
  private val hires: ActiveHireStore,
  private val methods: PaymentMethodStore,
  private val sealer: PaymentSealer,
  private val workouts: WorkoutSignal,
  private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

  private val _state = MutableStateFlow(HireState())
  val state: StateFlow<HireState> = _state.asStateFlow()

  private var expiry: Job? = null

  init {
    viewModelScope.launch {
      accounts.account.collect { account -> _state.update { it.copy(account = account) } }
    }
    viewModelScope.launch { hires.hire.collect(::hold) }
    viewModelScope.launch {
      methods.methods.collect { saved -> _state.update { it.copy(savedMethods = saved) } }
    }
  }

  private suspend fun hold(stored: ActiveHire?) {
    expiry?.cancel()
    if (stored == null) {
      _state.update { it.copy(active = null) }
      return
    }
    val remaining = Duration.between(clock.instant(), stored.expiresAt)
    if (remaining.isNegative || remaining.isZero) {
      hires.clear()
      return
    }
    _state.update { it.copy(active = stored) }
    expiry =
      viewModelScope.launch {
        delay(remaining.toMillis())
        hires.clear()
      }
  }

  fun rent(request: HireRequest) {
    val account = _state.value.account
    if (account == null) {
      askLogin(request, email = "", error = null)
      return
    }
    release(request, account.credentials)
  }

  private fun release(request: HireRequest, credentials: Credentials) {
    _state.update { it.copy(flow = HireFlow.Releasing(request)) }
    viewModelScope.launch {
      val result = hire(request, credentials)
      if (result !is HireResult.LoggedOut) {
        settle(request, credentials, result)
        return@launch
      }
      relogin(request)?.let { fresh -> settle(request, fresh, hire(request, fresh)) }
    }
  }

  private suspend fun hire(request: HireRequest, credentials: Credentials): HireResult =
    api.hire(credentials, request.target.terminal, request.target.point)

  /**
   * The backend drops tokens whenever it likes, so a dead token is silently traded for a live one.
   * Null means the rider has to type the password themselves, and the form asking for it is already
   * on screen by then.
   */
  private suspend fun relogin(request: HireRequest): Credentials? {
    val account = _state.value.account
    if (account == null) {
      askLogin(request, email = "", error = null)
      return null
    }
    return when (val result = api.login(account.email, account.password)) {
      is LoginResult.Success -> {
        accounts.save(account.copy(credentials = result.credentials))
        result.credentials
      }
      is LoginResult.Rejected -> {
        askLogin(request, account.email, result.message)
        null
      }
      is LoginResult.Unavailable -> {
        askLogin(request, account.email, unreachable(result.reason))
        null
      }
    }
  }

  private suspend fun settle(request: HireRequest, credentials: Credentials, result: HireResult) {
    when (result) {
      is HireResult.Released -> {
        hires.save(
          ActiveHire(
            station = request.station,
            stationName = result.code.station.ifBlank { request.stationName },
            code = result.code.code,
            expiresAt = releaseCodeExpiry(clock.instant(), result.code.validUntil),
          )
        )
        // The bike is out of the dock a minute or two after this, not now, but this
        // is the last thing the hire tells us about: nothing reports the undocking.
        workouts.cyclingStarted()
        _state.update { it.copy(flow = HireFlow.Idle, draft = null) }
      }
      is HireResult.VerificationNeeded ->
        _state.update {
          it.copy(
            flow =
              HireFlow.Verify(
                request = request,
                credentials = credentials,
                challenge = result.challenge,
                busy = false,
                error = null,
              )
          )
        }
      is HireResult.AccessNeeded ->
        _state.update {
          it.copy(flow = HireFlow.NeedsAccess(request, credentials, result.options, opening = null))
        }
      is HireResult.Refused -> fail(request, result.message)
      HireResult.LoggedOut -> askLogin(request, storedEmail(), LOGGED_OUT)
      is HireResult.Unavailable -> fail(request, unreachable(result.reason))
    }
  }

  /**
   * Logging in only logs in. Carrying straight on to the bike would turn the login itself into the
   * tap that releases one, and the rider has not seen the button say "Rent bike" yet.
   */
  fun submitLogin(email: String, password: String) {
    val login = _state.value.flow as? HireFlow.Login ?: return
    val address = email.trim()
    val secret = Password(password)
    _state.update { it.copy(flow = login.copy(busy = true, error = null)) }
    viewModelScope.launch {
      when (val result = api.login(address, secret)) {
        is LoginResult.Success -> {
          accounts.save(Account(address, secret, result.credentials))
          _state.update { it.copy(flow = HireFlow.Idle) }
        }
        is LoginResult.Rejected -> retryLogin(login, result.message)
        is LoginResult.Unavailable -> retryLogin(login, unreachable(result.reason))
      }
    }
  }

  fun submitVerification(digits: String) {
    val verify = _state.value.flow as? HireFlow.Verify ?: return
    _state.update { it.copy(flow = verify.copy(busy = true, error = null)) }
    viewModelScope.launch {
      when (val result = api.verifyDevice(verify.credentials, verify.challenge, digits)) {
        VerifyResult.Verified -> release(verify.request, verify.credentials)
        VerifyResult.LoggedOut -> askLogin(verify.request, storedEmail(), LOGGED_OUT)
        is VerifyResult.Rejected -> retryVerification(verify, result.message)
        is VerifyResult.Unavailable -> retryVerification(verify, unreachable(result.reason))
      }
    }
  }

  fun chooseAccess(option: AccessOption) {
    val needs = _state.value.flow as? HireFlow.NeedsAccess ?: return
    if (needs.opening != null) return
    _state.update { it.copy(flow = needs.copy(opening = option)) }
    viewModelScope.launch {
      when (val result = api.chooseAccess(needs.credentials, option)) {
        is PaymentResult.Form ->
          _state.update {
            it.copy(
              flow =
                HireFlow.Paying(
                  needs.request,
                  needs.credentials,
                  result.form,
                  busy = false,
                  errors = emptyList(),
                  save = null,
                  savedAs = null,
                )
            )
          }
        // Opening the card page charges nothing, so a fresh token simply starts over from Begin.
        PaymentResult.LoggedOut ->
          relogin(needs.request)?.let { fresh ->
            settle(needs.request, fresh, hire(needs.request, fresh))
          }
        is PaymentResult.Refused -> fail(needs.request, result.message)
        is PaymentResult.Unavailable -> fail(needs.request, unreachable(result.reason))
      }
    }
  }

  /**
   * Only a card the server sent back with errors is worth another try; after any other reply a
   * second submit could be a second charge, so the sheet closes. [save] rides along with a
   * rejection so the corrected retry does not ask again.
   */
  fun pay(card: CardDetails, save: SaveRequest?) {
    val paying = _state.value.flow as? HireFlow.Paying ?: return
    if (paying.busy) return
    _state.update {
      it.copy(
        flow = paying.copy(busy = true, errors = emptyList(), save = save),
        draft = StoredPayment(card.method, card.cvv),
      )
    }
    viewModelScope.launch {
      val result = api.pay(paying.credentials, paying.form, card)
      // Saved whatever the reply while the card format the backend wants is still being worked out,
      // so a refused attempt can be retried from the saved method instead of retyped.
      val savedAs = save?.let { remember(card, it, paying.savedAs) }
      when (result) {
        is PayResult.Rejected ->
          _state.update {
            it.copy(
              flow =
                paying.copy(
                  form = result.form,
                  busy = false,
                  errors = result.errors,
                  save = save,
                  savedAs = savedAs ?: paying.savedAs,
                )
            )
          }
        is PayResult.Hire -> settle(paying.request, paying.credentials, result.result)
      }
    }
  }

  private suspend fun remember(
    card: CardDetails,
    save: SaveRequest,
    replacing: PaymentMethodId?,
  ): PaymentMethodId {
    val stored = StoredPayment(card.method, card.cvv.takeIf { save.keepCvv })
    val sealing = withContext(Dispatchers.IO) { sealer.seal(stored) }
    if (sealing.previousLost) methods.clear()
    val id = replacing ?: PaymentMethodId(UUID.randomUUID().toString())
    methods.put(
      SavedPaymentMethod(
        id = id,
        name = save.name,
        summary = card.method.summary,
        cvvSaved = save.keepCvv,
        sealed = sealing.sealed,
      )
    )
    return id
  }

  /** Editing is paying with the form filled from [opened]; the next attempt replaces [method]. */
  fun edit(method: SavedPaymentMethod, opened: StoredPayment) {
    val paying = _state.value.flow as? HireFlow.Paying ?: return
    _state.update {
      it.copy(
        flow =
          paying.copy(
            errors = emptyList(),
            save = SaveRequest(method.name, keepCvv = method.cvvSaved),
            savedAs = method.id,
          ),
        draft = opened,
      )
    }
  }

  fun stopEditing() {
    val paying = _state.value.flow as? HireFlow.Paying ?: return
    _state.update { it.copy(flow = paying.copy(errors = emptyList(), save = null, savedAs = null)) }
  }

  fun forgetMethod(id: PaymentMethodId) {
    if ((_state.value.flow as? HireFlow.Paying)?.savedAs == id) stopEditing()
    viewModelScope.launch { methods.remove(id) }
  }

  fun forgetAllMethods() {
    viewModelScope.launch { methods.clear() }
  }

  fun dismiss() {
    _state.update { it.copy(flow = HireFlow.Idle) }
  }

  fun dismissCode() {
    viewModelScope.launch { hires.clear() }
  }

  private fun askLogin(request: HireRequest, email: String, error: String?) {
    _state.update {
      it.copy(flow = HireFlow.Login(request, email = email, busy = false, error = error))
    }
  }

  private fun storedEmail(): String = _state.value.account?.email.orEmpty()

  private fun retryLogin(login: HireFlow.Login, message: String) {
    _state.update { it.copy(flow = login.copy(busy = false, error = message)) }
  }

  private fun retryVerification(verify: HireFlow.Verify, message: String) {
    _state.update { it.copy(flow = verify.copy(busy = false, error = message)) }
  }

  private fun fail(request: HireRequest, message: String) {
    _state.update { it.copy(flow = HireFlow.Failed(request, message)) }
  }

  private fun unreachable(reason: String): String = "Could not reach Santander Cycles: $reason"

  private companion object {
    const val LOGGED_OUT =
      "Santander Cycles logged this device out. Log in again to take a bike out."
  }
}
