package dev.davidv.londontransport.threeds

import java.security.cert.TrustAnchor
import java.security.interfaces.ECPrivateKey
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ChallengeParameters(
  val serverId: ServerTransactionId,
  val acsId: AcsTransactionId,
  val signedContent: String,
)

@JvmInline value class OtpAnswer(val value: String) {
  init { require(value.isNotBlank() && value.length <= 45) }
  override fun toString(): String = "OtpAnswer(hidden)"
}

sealed interface ChallengeAction {
  data object Start : ChallengeAction
  data class Answer(val code: OtpAnswer) : ChallengeAction
  data object Cancel : ChallengeAction
}

sealed interface ChallengeOutcome {
  data class OtpRequired(val heading: String, val information: String, val label: String) : ChallengeOutcome
  data object Authenticated : ChallengeOutcome
  data object Rejected : ChallengeOutcome
  data object Cancelled : ChallengeOutcome
}

@Serializable internal enum class CompletionIndicator {
  @SerialName("Y") Complete, @SerialName("N") Incomplete
}

@Serializable internal enum class AuthenticationStatus {
  @SerialName("Y") Authenticated, @SerialName("N") Rejected
}

@Serializable
internal data class ChallengeRequest(
  val threeDSServerTransID: ServerTransactionId,
  val acsTransID: AcsTransactionId,
  val sdkTransID: SdkTransactionId,
  val sdkCounterStoA: String,
  val messageType: String = "CReq",
  val messageVersion: String = "2.2.0",
  val challengeDataEntry: String? = null,
  val challengeNoEntry: String? = null,
  val challengeCancel: String? = null,
) {
  override fun toString(): String = "ChallengeRequest(hidden)"
}

@Serializable
internal data class ChallengeResponse(
  val threeDSServerTransID: ServerTransactionId,
  val acsTransID: AcsTransactionId,
  val sdkTransID: SdkTransactionId,
  val acsCounterAtoS: String,
  val challengeCompletionInd: CompletionIndicator,
  val messageType: String,
  val messageVersion: String,
  val transStatus: AuthenticationStatus? = null,
  val acsUiType: String? = null,
  val challengeInfoHeader: String? = null,
  val challengeInfoText: String? = null,
  val challengeInfoLabel: String? = null,
)

internal fun challengeOutcome(response: ChallengeResponse, action: ChallengeAction): ChallengeOutcome {
  if (response.challengeCompletionInd == CompletionIndicator.Complete) {
    val status = response.transStatus ?: throw ProtocolException(ProtocolFault.MalformedInput)
    if (action == ChallengeAction.Cancel) {
      if (status != AuthenticationStatus.Rejected) throw ProtocolException(ProtocolFault.MalformedInput)
      return ChallengeOutcome.Cancelled
    }
    return when (status) {
      AuthenticationStatus.Authenticated -> ChallengeOutcome.Authenticated
      AuthenticationStatus.Rejected -> ChallengeOutcome.Rejected
    }
  }
  if (response.transStatus != null || action == ChallengeAction.Cancel) {
    throw ProtocolException(ProtocolFault.MalformedInput)
  }
  if (response.acsUiType != "01") throw ProtocolException(ProtocolFault.UnsupportedProfile)
  return ChallengeOutcome.OtpRequired(
    response.challengeInfoHeader ?: throw ProtocolException(ProtocolFault.MalformedInput),
    response.challengeInfoText ?: throw ProtocolException(ProtocolFault.MalformedInput),
    response.challengeInfoLabel ?: throw ProtocolException(ProtocolFault.MalformedInput),
  )
}

class ChallengeFlow private constructor(
  val acsUrl: String,
  private val transaction: SdkTransaction,
  private val parameters: ChallengeParameters,
  private val key: ByteArray,
  private val deadline: Instant,
) {
  private sealed interface State {
    data object Initial : State
    data class Waiting(val counter: Int, val action: ChallengeAction) : State
    data class Prompt(val nextCounter: Int) : State
    data object Finished : State
    data object Failed : State
  }

  private var state: State = State.Initial
  private val json = Json { encodeDefaults = true; explicitNulls = false }

  @Synchronized
  fun request(action: ChallengeAction, now: Instant): ProtocolResult<String> {
    if (!now.isBefore(deadline)) {
      state = State.Failed
      return ProtocolResult.Failure(ProtocolFault.Timeout)
    }
    val current = state
    val counter = when (current) {
      State.Initial -> {
        if (action != ChallengeAction.Start) return ProtocolResult.Failure(ProtocolFault.InvalidState)
        0
      }
      is State.Prompt -> {
        if (action == ChallengeAction.Start) return ProtocolResult.Failure(ProtocolFault.InvalidState)
        current.nextCounter
      }
      else -> return ProtocolResult.Failure(ProtocolFault.InvalidState)
    }
    state = State.Failed
    val result = protocolBoundary {
      if (counter !in 0..255) throw ProtocolException(ProtocolFault.CounterMismatch)
      val request = ChallengeRequest(
        parameters.serverId, parameters.acsId, transaction.id, "%03d".format(java.util.Locale.ROOT, counter),
        challengeDataEntry = (action as? ChallengeAction.Answer)?.code?.value,
        challengeNoEntry = if (action == ChallengeAction.Start) "Y" else null,
        challengeCancel = if (action == ChallengeAction.Cancel) "01" else null,
      )
      encryptChallenge(json.encodeToString(request), key, parameters.acsId, ChallengeIv.generate())
    }
    if (result is ProtocolResult.Success) state = State.Waiting(counter, action)
    return result
  }

  @Synchronized
  fun receive(packet: String, now: Instant): ProtocolResult<ChallengeOutcome> {
    if (!now.isBefore(deadline)) {
      state = State.Failed
      return ProtocolResult.Failure(ProtocolFault.Timeout)
    }
    val waiting = state as? State.Waiting ?: return ProtocolResult.Failure(ProtocolFault.InvalidState)
    state = State.Failed
    val result = protocolBoundary {
      val plaintext = decryptChallenge(packet, key, parameters.acsId)
      val response = json.decodeFromString<ChallengeResponse>(plaintext)
      if (response.messageType != "CRes" || response.messageVersion != "2.2.0") {
        throw ProtocolException(ProtocolFault.UnsupportedProfile)
      }
      if (response.threeDSServerTransID != parameters.serverId || response.acsTransID != parameters.acsId ||
        response.sdkTransID != transaction.id) {
        throw ProtocolException(ProtocolFault.TransactionMismatch)
      }
      if (response.acsCounterAtoS != "%03d".format(java.util.Locale.ROOT, waiting.counter)) {
        throw ProtocolException(ProtocolFault.CounterMismatch)
      }
      challengeOutcome(response, waiting.action)
    }
    if (result is ProtocolResult.Success) {
      state = if (result.value is ChallengeOutcome.OtpRequired) State.Prompt(waiting.counter + 1) else State.Finished
    }
    return result
  }

  companion object {
    fun start(
      transaction: SdkTransaction,
      parameters: ChallengeParameters,
      roots: Set<TrustAnchor>,
      now: Instant,
    ): ProtocolResult<ChallengeFlow> = protocolBoundary {
      val acs = verifyAcs(parameters.signedContent, transaction, roots, now)
      val key = deriveChallengeKey(transaction.keyPair.private as ECPrivateKey, acs.publicKey, transaction.reference)
      ChallengeFlow(acs.url, transaction, parameters, key, now.plus(Duration.ofMinutes(5)))
    }
  }
}
