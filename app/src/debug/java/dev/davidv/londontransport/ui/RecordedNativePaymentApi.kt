package dev.davidv.londontransport.ui

import dev.davidv.londontransport.santander.AccessOption
import dev.davidv.londontransport.santander.CardDetails
import dev.davidv.londontransport.santander.HireResult
import dev.davidv.londontransport.santander.PaymentForm
import dev.davidv.londontransport.threeds.ChallengeAction
import dev.davidv.londontransport.threeds.PaymentTrace
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecordedNativePaymentApi(private val delegate: NativePaymentApi, private val trace: PaymentTrace.Local) : NativePaymentApi {
  override suspend fun open(option: AccessOption): NativeFormResult = try {
    withContext(Dispatchers.IO) { trace.event("form", "started") }
    val result = delegate.open(option)
    withContext(Dispatchers.IO) { trace.event("form", when (result) {
      is NativeFormResult.Ready -> "ready"
      is NativeFormResult.Failed -> result.message
    }) }
    result
  } catch (error: IOException) {
    NativeFormResult.Failed("Local payment logging failed; the card form was not opened successfully.")
  }

  override suspend fun pay(form: PaymentForm, card: CardDetails): NativePaymentResult = recorded("pay") { delegate.pay(form, card) }

  private suspend fun recorded(stage: String, action: suspend () -> NativePaymentResult): NativePaymentResult {
    try {
      withContext(Dispatchers.IO) { trace.event(stage, "started") }
    } catch (error: IOException) {
      return NativePaymentResult.Stopped("Local payment logging failed before this step was sent; check your account before starting another payment.")
    }
    val result = action()
    val outcome = when (result) {
      is NativePaymentResult.Rejected -> "rejected: ${result.errors.joinToString("; ")}"
      is NativePaymentResult.Stopped -> "stopped: ${result.message}"
      is NativePaymentResult.Challenge -> "OTP required"
      is NativePaymentResult.Finished -> when (result.result) {
        is HireResult.Released -> "release code returned"
        is HireResult.VerificationNeeded -> "account verification required"
        else -> error("Unexpected native payment completion")
      }
    }
    try {
      withContext(Dispatchers.IO) { trace.event(stage, outcome) }
    } catch (error: IOException) {
      if (result is NativePaymentResult.Finished) return result.copy(loggingFault = NativePaymentLoggingFault.OutcomeRecordingFailed)
      return NativePaymentResult.Stopped("Local payment outcome logging failed; check your account before paying again.")
    }
    return if (result is NativePaymentResult.Challenge) {
      NativePaymentResult.Challenge(RecordedChallenge(result.session), result.prompt)
    } else result
  }

  private inner class RecordedChallenge(private val delegate: NativeChallenge) : NativeChallenge {
    override suspend fun send(action: ChallengeAction): NativePaymentResult = recorded(when (action) {
      ChallengeAction.Start -> "challenge-start"
      ChallengeAction.Cancel -> "challenge-cancel"
      is ChallengeAction.Answer -> "challenge-answer"
    }) { delegate.send(action) }
  }
}
