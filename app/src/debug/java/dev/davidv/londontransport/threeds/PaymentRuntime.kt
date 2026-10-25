package dev.davidv.londontransport.threeds

import dev.davidv.londontransport.santander.NodeReply
import dev.davidv.londontransport.santander.parseNodeReply
import dev.davidv.londontransport.santander.userAuthHeader
import dev.davidv.londontransport.santander.PaymentForm
import dev.davidv.londontransport.santander.buildCoreRequest
import dev.davidv.londontransport.santander.partUriToUrl
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl

class ProcessorPageTarget private constructor(val url: HttpUrl) {
  companion object {
    fun parse(uri: String, base: HttpUrl): ProtocolResult<ProcessorPageTarget> = protocolBoundary {
      require(uri.isNotBlank() && uri.none { it.isISOControl() })
      val relative = if (uri.startsWith("part://")) partUriToUrl(uri, base.toString()) else uri
      val url = requireNotNull(base.resolve(relative))
      require(url.scheme == base.scheme && url.host == base.host && url.port == base.port)
      require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
      ProcessorPageTarget(url)
    }
  }
}

sealed interface PaymentRuntimeFault {
  data object AlreadySent : PaymentRuntimeFault
  data object OutcomeUnknown : PaymentRuntimeFault
  data class HttpRejected(val code: Int) : PaymentRuntimeFault
  data object InvalidResponse : PaymentRuntimeFault
  data class Protocol(val fault: ProtocolFault) : PaymentRuntimeFault
}

sealed interface PaymentRuntimeResult<out T> {
  data class Success<T>(val value: T) : PaymentRuntimeResult<T>
  data class Failure(val fault: PaymentRuntimeFault) : PaymentRuntimeResult<Nothing>
}

class PendingPaymentRequest internal constructor(internal val request: Request) {
  internal val mutex = Mutex()
  internal var sent = false
  override fun toString(): String = "PendingPaymentRequest(<redacted>)"
}

class PreparedCardSubmission private constructor(
  internal val pending: PendingPaymentRequest,
  internal val authentication: PreparedAuthentication,
  internal val context: PaymentRequestContext,
) {
  companion object {
    fun create(core: Request, authentication: PreparedAuthentication, context: PaymentRequestContext): ProtocolResult<PreparedCardSubmission> = protocolBoundary {
      require(core.url.scheme == context.base.scheme && core.url.host == context.base.host && core.url.port == context.base.port)
      require(core.url.username.isEmpty() && core.url.password.isEmpty() && core.url.fragment == null)
      require(core.header("c3-userauth") == userAuthHeader(context.credentials))
      val body = core.body as? FormBody ?: throw ProtocolException(ProtocolFault.MalformedInput)
      require((0 until body.size).filter { body.name(it) == "c3-deviceid" }.map(body::value) == listOf(context.deviceId.value))
      val request = when (val result = buildCardSubmissionRequest(core, authentication.parameters)) {
        is ProtocolResult.Success -> result.value
        is ProtocolResult.Failure -> throw ProtocolException(result.fault)
      }
      PreparedCardSubmission(PendingPaymentRequest(request), authentication, context)
    }
  }
}

sealed interface RuntimePaymentStep {
  data class CardRejected(val form: PaymentForm, val errors: List<String>) : RuntimePaymentStep
  data class Refused(val message: String) : RuntimePaymentStep
  data class Continue(val status: NonChallengePaymentStatus, val pending: PendingPaymentRequest) : RuntimePaymentStep
  data class Challenge(val session: RuntimeChallengeSession) : RuntimePaymentStep
  data class Prompt(val prompt: ChallengeOutcome.OtpRequired) : RuntimePaymentStep
  data class Completed(val outcome: PaymentChallengeCompletion, val pending: PendingPaymentRequest) : RuntimePaymentStep
  data class Failed(val fault: ProtocolFault, val pending: PendingPaymentRequest) : RuntimePaymentStep
}

class PaymentRuntime internal constructor(private val client: OkHttpClient, private val clock: Clock,
  private val trace: PaymentTrace = PaymentTrace.Disabled) {
  init {
    require(!client.retryOnConnectionFailure && !client.followRedirects && !client.followSslRedirects)
    require(client.interceptors.isEmpty() && client.networkInterceptors.isEmpty())
    require(client.authenticator === okhttp3.Authenticator.NONE && client.proxyAuthenticator === okhttp3.Authenticator.NONE)
    require(client.cookieJar === okhttp3.CookieJar.NO_COOKIES && client.cache == null)
    require(client.callTimeoutMillis in 1..30_000)
  }

  suspend fun submit(prepared: PreparedCardSubmission): PaymentRuntimeResult<RuntimePaymentStep> {
    val response = when (val result = sendOnce(prepared.pending, 512_000, PaymentHttpStage.CardSubmission)) {
      is PaymentRuntimeResult.Success -> result.value
      is PaymentRuntimeResult.Failure -> return result
    }
    val authentication = when (val result = parseCardSubmissionReply(response)) {
      is ProtocolResult.Success -> when (val reply = result.value) {
        is CardSubmissionReply.Authentication -> reply.authentication
        is CardSubmissionReply.Rejected -> return PaymentRuntimeResult.Success(RuntimePaymentStep.CardRejected(reply.form, reply.errors))
        is CardSubmissionReply.Refused -> return PaymentRuntimeResult.Success(RuntimePaymentStep.Refused(reply.message))
      }
      is ProtocolResult.Failure -> return PaymentRuntimeResult.Failure(PaymentRuntimeFault.Protocol(result.fault))
    }
    val handoff = when (val result = preparePaymentHandoff(authentication, prepared.authentication.transaction,
      prepared.authentication.challengeRoots, prepared.context, clock.instant())) {
      is ProtocolResult.Success -> result.value
      is ProtocolResult.Failure -> return PaymentRuntimeResult.Failure(PaymentRuntimeFault.Protocol(result.fault))
    }
    val step = when (handoff) {
      is PaymentHandoff.Continue -> RuntimePaymentStep.Continue(handoff.status, PendingPaymentRequest(handoff.request))
      is PaymentHandoff.Challenge -> RuntimePaymentStep.Challenge(RuntimeChallengeSession(handoff.session, this, clock))
      is PaymentHandoff.Failed -> RuntimePaymentStep.Failed(handoff.fault, PendingPaymentRequest(handoff.request))
    }
    return PaymentRuntimeResult.Success(step)
  }

  suspend fun continuePayment(pending: PendingPaymentRequest): PaymentRuntimeResult<NodeReply> = readPage(pending, PaymentHttpStage.Continuation)

  private suspend fun readPage(pending: PendingPaymentRequest, stage: PaymentHttpStage): PaymentRuntimeResult<NodeReply> {
    val response = when (val result = sendOnce(pending, 512_000, stage)) {
      is PaymentRuntimeResult.Success -> result.value
      is PaymentRuntimeResult.Failure -> return result
    }
    val reply = parseNodeReply(response)
    if (reply is NodeReply.Malformed) return PaymentRuntimeResult.Failure(PaymentRuntimeFault.InvalidResponse)
    return PaymentRuntimeResult.Success(reply)
  }

  suspend fun openPage(target: ProcessorPageTarget, context: PaymentRequestContext, now: Instant): PaymentRuntimeResult<NodeReply> {
    require(target.url.scheme == context.base.scheme && target.url.host == context.base.host && target.url.port == context.base.port)
    return readPage(PendingPaymentRequest(buildCoreRequest(target.url, context.deviceId, context.credentials, null, now)), PaymentHttpStage.Page)
  }

  internal suspend fun exchange(session: PaymentChallengeSession, packet: String): PaymentRuntimeResult<String> {
    val request = Request.Builder().url(session.acsUrl.toHttpUrl())
      .header("Accept", "application/jose")
      .post(packet.toRequestBody("application/jose; charset=utf-8".toMediaType())).build()
    return sendOnce(PendingPaymentRequest(request), 64_000, PaymentHttpStage.Challenge)
  }

  private suspend fun sendOnce(pending: PendingPaymentRequest, limit: Int, stage: PaymentHttpStage): PaymentRuntimeResult<String> =
    pending.mutex.withLock {
      if (pending.sent) return@withLock PaymentRuntimeResult.Failure(PaymentRuntimeFault.AlreadySent)
      pending.sent = true
      withContext(Dispatchers.IO) {
        try {
          executePaymentHttp(client, pending.request, limit.toLong(), trace, stage).use { response ->
            if (!response.isSuccessful) return@withContext PaymentRuntimeResult.Failure(PaymentRuntimeFault.HttpRejected(response.code))
            val source = response.body.source()
            source.request(limit + 1L)
            if (source.buffer.size > limit) return@withContext PaymentRuntimeResult.Failure(PaymentRuntimeFault.InvalidResponse)
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source.buffer.readByteArray())).toString()
            PaymentRuntimeResult.Success(text)
          }
        } catch (e: java.nio.charset.CharacterCodingException) {
          PaymentRuntimeResult.Failure(PaymentRuntimeFault.InvalidResponse)
        } catch (e: IOException) {
          PaymentRuntimeResult.Failure(PaymentRuntimeFault.OutcomeUnknown)
        }
      }
    }

  companion object {
    fun create(clock: Clock, trace: PaymentTrace = PaymentTrace.Disabled): PaymentRuntime = PaymentRuntime(OkHttpClient.Builder()
      .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
      .callTimeout(Duration.ofSeconds(30)).build(), clock, trace)
  }
}

class RuntimeChallengeSession internal constructor(
  private val session: PaymentChallengeSession,
  private val payments: PaymentRuntime,
  private val clock: Clock,
) {
  private val mutex = Mutex()

  suspend fun send(action: ChallengeAction): PaymentRuntimeResult<RuntimePaymentStep> = mutex.withLock {
    val requested = when (val result = session.request(action, clock.instant())) {
      is ProtocolResult.Success -> result.value
      is ProtocolResult.Failure -> return@withLock PaymentRuntimeResult.Failure(PaymentRuntimeFault.Protocol(result.fault))
    }
    if (requested !is PaymentChallengeStep.SendToAcs) return@withLock convert(requested)
    val received = when (val response = payments.exchange(session, requested.packet)) {
      is PaymentRuntimeResult.Success -> session.receive(response.value, clock.instant())
      is PaymentRuntimeResult.Failure -> session.transportFailed(clock.instant())
    }
    when (received) {
      is ProtocolResult.Success -> convert(received.value)
      is ProtocolResult.Failure -> PaymentRuntimeResult.Failure(PaymentRuntimeFault.Protocol(received.fault))
    }
  }

  private fun convert(step: PaymentChallengeStep): PaymentRuntimeResult<RuntimePaymentStep> {
    val result = when (step) {
      is PaymentChallengeStep.Prompt -> RuntimePaymentStep.Prompt(step.prompt)
      is PaymentChallengeStep.Completed -> RuntimePaymentStep.Completed(step.outcome, PendingPaymentRequest(step.request))
      is PaymentChallengeStep.Failed -> RuntimePaymentStep.Failed(step.fault, PendingPaymentRequest(step.request))
      is PaymentChallengeStep.SendToAcs -> error("Undispatched challenge request")
    }
    return PaymentRuntimeResult.Success(result)
  }
}
