package dev.davidv.londontransport.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.davidv.londontransport.data.PaymentMethodStore
import dev.davidv.londontransport.data.PaymentSealer
import dev.davidv.londontransport.data.StoredPayment
import dev.davidv.londontransport.domain.PaymentMethodId
import dev.davidv.londontransport.domain.SaveRequest
import dev.davidv.londontransport.domain.SavedPaymentMethod
import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.threeds.ChallengeAction
import dev.davidv.londontransport.threeds.ChallengeOutcome
import dev.davidv.londontransport.threeds.OtpAnswer
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface NativePaymentFlow {
  data object Closed : NativePaymentFlow
  data object Picking : NativePaymentFlow
  data object Loading : NativePaymentFlow
  data class Card(
    val form: PaymentForm,
    val busy: Boolean = false,
    val errors: List<String> = emptyList(),
    val save: SaveRequest? = null,
    val savedAs: PaymentMethodId? = null,
    val draft: StoredPayment? = null,
  ) : NativePaymentFlow
  data class Challenge(val session: NativeChallenge, val prompt: ChallengeOutcome.OtpRequired, val busy: Boolean) : NativePaymentFlow
  data class Finished(val result: HireResult, val loggingFault: NativePaymentLoggingFault? = null) : NativePaymentFlow
  data class Stopped(val message: String) : NativePaymentFlow
}

data class NativePaymentState(val flow: NativePaymentFlow = NativePaymentFlow.Closed, val saved: List<SavedPaymentMethod> = emptyList()) {
  val busy: Boolean get() = when (flow) {
    NativePaymentFlow.Loading -> true
    is NativePaymentFlow.Card -> flow.busy
    is NativePaymentFlow.Challenge -> flow.busy
    else -> false
  }
}

class NativePaymentViewModel(
  private val api: NativePaymentApi,
  private val methods: PaymentMethodStore,
  private val sealer: PaymentSealer,
  private val options: List<AccessOption>,
  private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
  private val mutableState = MutableStateFlow(NativePaymentState())
  val state = mutableState.asStateFlow()

  init {
    viewModelScope.launch { methods.methods.collect { saved -> mutableState.update { it.copy(saved = saved) } } }
  }

  fun open() {
    if (mutableState.value.flow != NativePaymentFlow.Closed) return
    mutableState.update { it.copy(flow = NativePaymentFlow.Picking) }
  }

  fun choose(option: AccessOption) {
    if (mutableState.value.flow != NativePaymentFlow.Picking || option !in options) return
    mutableState.update { it.copy(flow = NativePaymentFlow.Loading) }
    viewModelScope.launch {
      when (val result = api.open(option)) {
        is NativeFormResult.Ready -> mutableState.update { it.copy(flow = NativePaymentFlow.Card(result.form, errors = result.errors)) }
        is NativeFormResult.Failed -> mutableState.update { it.copy(flow = NativePaymentFlow.Stopped(result.message)) }
      }
    }
  }

  fun pay(card: CardDetails, save: SaveRequest?) {
    val entry = mutableState.value.flow as? NativePaymentFlow.Card ?: return
    if (entry.busy) return
    val errors = cardInputErrors(card, entry.form, YearMonth.now(clock))
    if (errors.isNotEmpty()) {
      mutableState.update { it.copy(flow = entry.copy(errors = errors)) }
      return
    }
    mutableState.update { it.copy(flow = entry.copy(busy = true, errors = emptyList())) }
    viewModelScope.launch {
      if (save != null) {
        try {
          val sealed = withContext(Dispatchers.IO) {
            sealer.seal(StoredPayment(card.method, card.cvv.takeIf { save.keepCvv }))
          }
          if (sealed.previousLost) methods.clear()
          methods.put(SavedPaymentMethod(entry.savedAs ?: PaymentMethodId(UUID.randomUUID().toString()),
            save.name, card.method.summary, save.keepCvv, sealed.sealed))
        } catch (e: GeneralSecurityException) {
          mutableState.update { it.copy(flow = entry.copy(errors = listOf("Could not encrypt the saved card; nothing was submitted."))) }
          return@launch
        } catch (e: IOException) {
          mutableState.update { it.copy(flow = entry.copy(errors = listOf("Could not store the saved card; nothing was submitted."))) }
          return@launch
        }
      }
      accept(api.pay(entry.form, card))
    }
  }

  fun answer(code: String) {
    if (code.isBlank() || code.length > 45) return
    challenge(ChallengeAction.Answer(OtpAnswer(code)))
  }

  fun cancelChallenge() = challenge(ChallengeAction.Cancel)

  private fun challenge(action: ChallengeAction) {
    val current = mutableState.value.flow as? NativePaymentFlow.Challenge ?: return
    if (current.busy) return
    mutableState.update { it.copy(flow = current.copy(busy = true)) }
    viewModelScope.launch { accept(current.session.send(action)) }
  }

  private fun accept(result: NativePaymentResult) {
    val flow = when (result) {
      is NativePaymentResult.Rejected -> {
        val entry = checkNotNull(mutableState.value.flow as? NativePaymentFlow.Card)
        entry.copy(form = result.form, busy = false, errors = result.errors)
      }
      is NativePaymentResult.Challenge -> NativePaymentFlow.Challenge(result.session, result.prompt, false)
      is NativePaymentResult.Finished -> NativePaymentFlow.Finished(result.result, result.loggingFault)
      is NativePaymentResult.Stopped -> NativePaymentFlow.Stopped(result.message)
    }
    mutableState.update { it.copy(flow = flow) }
  }

  fun edit(saved: SavedPaymentMethod, opened: StoredPayment) {
    val entry = mutableState.value.flow as? NativePaymentFlow.Card ?: return
    if (entry.busy) return
    mutableState.update { it.copy(flow = entry.copy(savedAs = saved.id, draft = opened, save = SaveRequest(saved.name, saved.cvvSaved))) }
  }

  fun stopEditing() {
    val entry = mutableState.value.flow as? NativePaymentFlow.Card ?: return
    if (entry.busy) return
    mutableState.update { it.copy(flow = entry.copy(savedAs = null, draft = null, save = null)) }
  }

  fun forget(id: PaymentMethodId) {
    if (mutableState.value.busy) return
    viewModelScope.launch { methods.remove(id) }
  }

  fun forgetAll() {
    if (mutableState.value.busy) return
    viewModelScope.launch { methods.clear() }
  }

  fun dismiss() {
    if (mutableState.value.busy) return
    if (mutableState.value.flow is NativePaymentFlow.Challenge) {
      cancelChallenge()
      return
    }
    mutableState.update { it.copy(flow = NativePaymentFlow.Closed) }
  }
}
