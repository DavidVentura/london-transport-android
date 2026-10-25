package dev.davidv.londontransport.threeds

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import dev.davidv.londontransport.santander.NodeReply
import dev.davidv.londontransport.santander.PaymentForm
import dev.davidv.londontransport.santander.PaymentPage
import dev.davidv.londontransport.santander.parseNodeReply
import dev.davidv.londontransport.santander.parsePaymentPage

sealed interface CardSubmissionReply {
  data class Authentication(val authentication: PaymentAuthentication) : CardSubmissionReply
  data class Rejected(val form: PaymentForm, val errors: List<String>) : CardSubmissionReply
  data class Refused(val message: String) : CardSubmissionReply
}

fun parseCardSubmissionReply(body: String): ProtocolResult<CardSubmissionReply> = protocolBoundary {
  require(body.length <= 512_000)
  val nodes = Json { ignoreUnknownKeys = true }.decodeFromString<List<PaymentResponseNode>>(body)
  if (nodes.any { it.type == "Node.Internal.SimpleResponse" && (it.tags.deserialize != null || it.tags.callback != null) }) {
    return@protocolBoundary when (val result = parsePaymentAuthentication(body)) {
      is ProtocolResult.Success -> CardSubmissionReply.Authentication(result.value)
      is ProtocolResult.Failure -> throw ProtocolException(result.fault)
    }
  }
  when (val reply = parseNodeReply(body)) {
    is NodeReply.Rejected -> CardSubmissionReply.Refused(reply.message)
    is NodeReply.Malformed -> throw ProtocolException(ProtocolFault.MalformedInput)
    is NodeReply.Nodes -> when (val page = parsePaymentPage(reply.nodes)) {
      is PaymentPage.Form -> {
        require(page.errors.isNotEmpty())
        CardSubmissionReply.Rejected(page.form, page.errors)
      }
      is PaymentPage.Refused -> CardSubmissionReply.Refused(page.message)
      is PaymentPage.Malformed -> throw ProtocolException(ProtocolFault.MalformedInput)
    }
  }
}

fun buildCardSubmissionRequest(core: Request, authentication: AuthenticationParameters): ProtocolResult<Request> = protocolBoundary {
  val form = core.body as? FormBody ?: throw ProtocolException(ProtocolFault.MalformedInput)
  val names = (0 until form.size).map(form::name)
  val sdkFields = authentication.formFields()
  require(core.method == "POST" && core.header("c3-capabilities") == "")
  require(!core.header("c3-userauth").isNullOrBlank())
  require(names.size == names.toSet().size && "c3-controlvals" in names)
  require(names.none { it in sdkFields || it == "transData" })
  require((0 until form.size).none { form.name(it) == "c3-controlvals" && form.value(it).isBlank() })
  val body = FormBody.Builder().apply {
    (0 until form.size).forEach { add(form.name(it), form.value(it)) }
    sdkFields.forEach { (name, value) -> add(name, value) }
  }.build()
  core.newBuilder().post(body).header("c3-capabilities", "3dsenabled").build()
}

@JvmInline @Serializable value class PaymentTransactionData(val value: String) {
  init { require(value.isNotBlank() && value.length <= 64_000) }
  override fun toString(): String = "PaymentTransactionData(hidden)"
}

@JvmInline value class PaymentCallback(val value: String) {
  init { require(value.isNotBlank() && value.none { it.isISOControl() }) }
}

@JvmInline @Serializable value class AcsReferenceNumber(val value: String) {
  init { require(value.isNotBlank()) }
}

@Serializable enum class PaymentAuthenticationStatus {
  @SerialName("Y") Authenticated,
  @SerialName("N") NotAuthenticated,
  @SerialName("U") Unavailable,
  @SerialName("A") Attempted,
  @SerialName("R") Rejected,
  @SerialName("C") Challenge,
  @SerialName("D") Decoupled,
  @SerialName("I") Informational,
}

data class PaymentContinuation(val callback: PaymentCallback, val data: PaymentTransactionData)

@Serializable enum class NonChallengePaymentStatus(val authenticationStatus: PaymentAuthenticationStatus) {
  @SerialName("Y") Authenticated(PaymentAuthenticationStatus.Authenticated),
  @SerialName("N") NotAuthenticated(PaymentAuthenticationStatus.NotAuthenticated),
  @SerialName("U") Unavailable(PaymentAuthenticationStatus.Unavailable),
  @SerialName("A") Attempted(PaymentAuthenticationStatus.Attempted),
  @SerialName("R") Rejected(PaymentAuthenticationStatus.Rejected),
  @SerialName("I") Informational(PaymentAuthenticationStatus.Informational),
}

sealed interface PaymentAuthentication {
  data class Continue(val status: NonChallengePaymentStatus, val continuation: PaymentContinuation) : PaymentAuthentication
  data class Challenge(
    val parameters: ChallengeParameters,
    val acsReference: AcsReferenceNumber,
    val continuation: PaymentContinuation,
  ) : PaymentAuthentication
}

@Serializable private data class PaymentResponseNode(
  @SerialName("Type") val type: String,
  @SerialName("Name") val name: String = "",
  @SerialName("Tags") val tags: PaymentResponseTags = PaymentResponseTags(),
)
@Serializable private data class PaymentResponseTags(
  @SerialName("ClientAction.ShowMessage.Deserialise") val deserialize: String? = null,
  @SerialName("ClientAction.Controls.Callback") val callback: String? = null,
)
@Serializable private data class PaymentMessageEnvelope(val message: String)
@Serializable private data class PaymentAuthenticationRecord(
  val transStatus: PaymentAuthenticationStatus,
  val transData: PaymentTransactionData,
  val threeDSServerTransID: ServerTransactionId? = null,
  val acsTransID: AcsTransactionId? = null,
  val acsReferenceNumber: AcsReferenceNumber? = null,
  val acsSignedContent: String? = null,
  val messageVersion: String? = null,
)

fun parsePaymentAuthentication(body: String): ProtocolResult<PaymentAuthentication> = protocolBoundary {
  require(body.length <= 512_000)
  val json = Json { ignoreUnknownKeys = true }
  val nodes = json.decodeFromString<List<PaymentResponseNode>>(body)
  val actions = nodes.filter {
    it.type == "Node.Internal.SimpleResponse" && it.tags.deserialize != null && it.tags.callback != null
  }
  require(actions.size == 1)
  val action = actions.single()
  val envelope = json.decodeFromString<PaymentMessageEnvelope>(action.name)
  val record = json.decodeFromString<PaymentAuthenticationRecord>(envelope.message)
  if (record.messageVersion != null && record.messageVersion != "2.2.0") throw ProtocolException(ProtocolFault.UnsupportedProfile)
  val continuation = PaymentContinuation(PaymentCallback(requireNotNull(action.tags.callback)), record.transData)
  if (record.transStatus == PaymentAuthenticationStatus.Decoupled) throw ProtocolException(ProtocolFault.UnsupportedProfile)
  if (record.transStatus != PaymentAuthenticationStatus.Challenge) {
    val status = NonChallengePaymentStatus.entries.first { it.authenticationStatus == record.transStatus }
    return@protocolBoundary PaymentAuthentication.Continue(status, continuation)
  }
  val content = requireNotNull(record.acsSignedContent)
  require(content.isNotBlank() && content.length <= 64_000)
  PaymentAuthentication.Challenge(
    ChallengeParameters(requireNotNull(record.threeDSServerTransID), requireNotNull(record.acsTransID), content),
    requireNotNull(record.acsReferenceNumber), continuation,
  )
}
