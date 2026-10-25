package dev.davidv.londontransport.threeds

import dev.davidv.londontransport.santander.Credentials
import dev.davidv.londontransport.santander.DeviceId
import dev.davidv.londontransport.santander.buildCoreRequest
import dev.davidv.londontransport.santander.partUriToUrl
import java.security.cert.TrustAnchor
import java.time.Instant
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request

data class PaymentRequestContext(val base: HttpUrl, val deviceId: DeviceId, val credentials: Credentials) {
  init {
    require(base.encodedPath == "/" && base.query == null && base.fragment == null)
    require(base.username.isEmpty() && base.password.isEmpty())
    require(base.isHttps || base.host in setOf("localhost", "127.0.0.1", "::1"))
  }
}

class PaymentContinuationTarget private constructor(val url: HttpUrl, val data: PaymentTransactionData) {
  companion object {
    fun resolve(continuation: PaymentContinuation, base: HttpUrl): ProtocolResult<PaymentContinuationTarget> = protocolBoundary {
      val callback = continuation.callback.value
      val target = if (callback.startsWith("part://")) partUriToUrl(callback, base.toString()) else callback
      val url = requireNotNull(base.resolve(target))
      require(url.scheme == base.scheme && url.host == base.host && url.port == base.port)
      require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
      require("ChallengeFailed" !in url.queryParameterNames)
      PaymentContinuationTarget(url, continuation.data)
    }
  }
}

enum class PaymentContinuationDisposition { Normal, ChallengeFailed }

fun buildPaymentContinuationRequest(
  target: PaymentContinuationTarget,
  context: PaymentRequestContext,
  disposition: PaymentContinuationDisposition,
  now: Instant,
): Request {
  require(target.url.scheme == context.base.scheme && target.url.host == context.base.host && target.url.port == context.base.port)
  val url = when (disposition) {
    PaymentContinuationDisposition.Normal -> target.url
    PaymentContinuationDisposition.ChallengeFailed -> target.url.newBuilder().addQueryParameter("ChallengeFailed", "1").build()
  }
  val core = buildCoreRequest(url, context.deviceId, context.credentials, null, now)
  val form = core.body as FormBody
  val body = FormBody.Builder().apply {
    (0 until form.size).forEach { add(form.name(it), form.value(it)) }
    add("transData", target.data.value)
  }.build()
  return core.newBuilder().post(body).header("c3-capabilities", "3dsenabled").build()
}

sealed interface PaymentHandoff {
  data class Continue(val status: NonChallengePaymentStatus, val request: Request) : PaymentHandoff
  data class Challenge(val session: PaymentChallengeSession) : PaymentHandoff
  data class Failed(val fault: ProtocolFault, val request: Request) : PaymentHandoff
}

sealed interface PaymentChallengeStep {
  data class SendToAcs(val packet: String) : PaymentChallengeStep
  data class Prompt(val prompt: ChallengeOutcome.OtpRequired) : PaymentChallengeStep
  data class Completed(val outcome: PaymentChallengeCompletion, val request: Request) : PaymentChallengeStep
  data class Failed(val fault: ProtocolFault, val request: Request) : PaymentChallengeStep
}

enum class PaymentChallengeCompletion { Authenticated, Rejected, Cancelled }

class PaymentChallengeSession internal constructor(
  private val flow: ChallengeFlow,
  private val target: PaymentContinuationTarget,
  private val context: PaymentRequestContext,
) {
  val acsUrl: String get() = flow.acsUrl
  private enum class State { Active, Finished }
  private var state = State.Active

  @Synchronized
  fun request(action: ChallengeAction, now: Instant): ProtocolResult<PaymentChallengeStep> {
    if (state != State.Active) return ProtocolResult.Failure(ProtocolFault.InvalidState)
    return when (val result = flow.request(action, now)) {
      is ProtocolResult.Success -> ProtocolResult.Success(PaymentChallengeStep.SendToAcs(result.value))
      is ProtocolResult.Failure -> failed(result.fault, now)
    }
  }

  @Synchronized
  fun receive(packet: String, now: Instant): ProtocolResult<PaymentChallengeStep> {
    if (state != State.Active) return ProtocolResult.Failure(ProtocolFault.InvalidState)
    val outcome = when (val result = flow.receive(packet, now)) {
      is ProtocolResult.Success -> result.value
      is ProtocolResult.Failure -> return failed(result.fault, now)
    }
    val completion = when (outcome) {
      is ChallengeOutcome.OtpRequired -> return ProtocolResult.Success(PaymentChallengeStep.Prompt(outcome))
      ChallengeOutcome.Authenticated -> PaymentChallengeCompletion.Authenticated
      ChallengeOutcome.Rejected -> PaymentChallengeCompletion.Rejected
      ChallengeOutcome.Cancelled -> PaymentChallengeCompletion.Cancelled
    }
    state = State.Finished
    val disposition = if (completion == PaymentChallengeCompletion.Cancelled) PaymentContinuationDisposition.ChallengeFailed
      else PaymentContinuationDisposition.Normal
    return ProtocolResult.Success(PaymentChallengeStep.Completed(completion, buildPaymentContinuationRequest(target, context, disposition, now)))
  }

  private fun failed(fault: ProtocolFault, now: Instant): ProtocolResult<PaymentChallengeStep> {
    if (fault == ProtocolFault.InvalidState) return ProtocolResult.Failure(fault)
    state = State.Finished
    return ProtocolResult.Success(PaymentChallengeStep.Failed(fault,
      buildPaymentContinuationRequest(target, context, PaymentContinuationDisposition.ChallengeFailed, now)))
  }

  @Synchronized
  fun transportFailed(now: Instant): ProtocolResult<PaymentChallengeStep> {
    if (state != State.Active) return ProtocolResult.Failure(ProtocolFault.InvalidState)
    return failed(ProtocolFault.ChallengeTransport, now)
  }
}

fun preparePaymentHandoff(
  authentication: PaymentAuthentication,
  transaction: SdkTransaction,
  roots: Set<TrustAnchor>,
  context: PaymentRequestContext,
  now: Instant,
): ProtocolResult<PaymentHandoff> {
  val continuation = when (authentication) {
    is PaymentAuthentication.Continue -> authentication.continuation
    is PaymentAuthentication.Challenge -> authentication.continuation
  }
  val target = when (val result = PaymentContinuationTarget.resolve(continuation, context.base)) {
    is ProtocolResult.Success -> result.value
    is ProtocolResult.Failure -> return result
  }
  if (authentication is PaymentAuthentication.Continue) {
    return ProtocolResult.Success(PaymentHandoff.Continue(authentication.status,
      buildPaymentContinuationRequest(target, context, PaymentContinuationDisposition.Normal, now)))
  }
  authentication as PaymentAuthentication.Challenge
  return when (val result = ChallengeFlow.start(transaction, authentication.parameters, roots, now)) {
    is ProtocolResult.Success -> ProtocolResult.Success(PaymentHandoff.Challenge(PaymentChallengeSession(result.value, target, context)))
    is ProtocolResult.Failure -> ProtocolResult.Success(PaymentHandoff.Failed(result.fault,
      buildPaymentContinuationRequest(target, context, PaymentContinuationDisposition.ChallengeFailed, now)))
  }
}
