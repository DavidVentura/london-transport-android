package dev.davidv.londontransport.santander

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JvmInline value class DeviceId(val value: String)

@JvmInline
value class Password(val value: String) {
  override fun toString(): String = "Password(hidden)"
}

data class Account(val email: String, val password: Password, val session: PolarisSession)

@JvmInline
value class BikeId private constructor(val value: String) {
  companion object {
    fun parse(input: String): BikeId? {
      val trimmed = input.trim()
      if (trimmed.isEmpty() || trimmed.any { it !in '0'..'9' }) return null
      return BikeId(trimmed)
    }
  }
}

@JvmInline
value class QrCode private constructor(val value: String) {
  companion object {
    fun parse(input: String): QrCode? {
      if (input.isBlank()) return null
      return QrCode(input)
    }
  }
}

@JvmInline value class RideId(val value: String)

@JvmInline value class RideStatus(val value: String)

data class CurrentRide(val id: RideId, val status: RideStatus, val startedAt: Instant?, val bikes: Int?)

sealed interface UnlockResult {
  data object Accepted : UnlockResult
  data class AccessNeeded(val title: String?, val message: String) : UnlockResult
  data class Refused(val message: String) : UnlockResult
  data object LoggedOut : UnlockResult
  data class Unavailable(val reason: String) : UnlockResult
  data class TutorialRequired(val tutorial: RideTutorial, val continuation: UnlockContinuation) : UnlockResult
}

data class TutorialPage(val title: String, val body: String) {
  init { require(title.isNotBlank() && body.isNotBlank()) }
}
data class RideTutorial(val pages: List<TutorialPage>) {
  init { require(pages.isNotEmpty()) }
}
@JvmInline value class MutationSessionId(val value: String)

class UnlockContinuation internal constructor(internal val member: MemberId, internal val attempt: UnlockAttempt) {
  override fun toString(): String = "UnlockContinuation(hidden)"
}

internal data class UnlockAttempt(val bike: QrCode, val session: MutationSessionId, val reasons: List<TutorialRetryReason> = emptyList())

@Serializable
internal data class TutorialRetryReason(@SerialName("interstitial_type") val type: String, val reason: String)

internal sealed interface MutationOutcome {
  data object Accepted : MutationOutcome
  data class AccessNeeded(val title: String?, val message: String) : MutationOutcome
  data class Refused(val message: String) : MutationOutcome
  data class Unavailable(val reason: String) : MutationOutcome
  data class Tutorial(val tutorial: RideTutorial) : MutationOutcome
}

sealed interface RideResult {
  data class Success(val ride: CurrentRide?) : RideResult
  data object LoggedOut : RideResult
  data class Unavailable(val reason: String) : RideResult
}

interface PolarisApi {
  suspend fun login(email: String, password: Password): PolarisLoginResult
  suspend fun verifyLogin(challenge: LoginChallenge, code: String): PolarisLoginResult
  suspend fun unlock(session: PolarisSession, bike: QrCode): UnlockResult
  suspend fun continueUnlock(session: PolarisSession, continuation: UnlockContinuation): UnlockResult
  suspend fun activeRide(session: PolarisSession): RideResult
}

@Serializable
internal class EmptyMessage

@Serializable
internal data class QrMutationRequest(
  @SerialName("qr_code") val code: String,
  val nonce: String,
  @SerialName("mutation_type") val type: Int = 1,
  @SerialName("mutation_session") val session: String = "",
  @SerialName("retry_reasons") val reasons: List<TutorialRetryReason> = emptyList(),
)

@Serializable
internal data class MutationResponse(val success: EmptyMessage? = null, val interstitial: Interstitial? = null)

@Serializable
internal data class Interstitial(val action: List<PolarisAction>)

@Serializable
internal data class PolarisAction(
  @SerialName("alert_action") val alert: PolarisAlertAction? = null,
  @SerialName("get_a_pass_action") val getPass: EmptyMessage? = null,
  @SerialName("list_action") val list: PolarisActionList? = null,
  @SerialName("track_event_action") val track: EmptyMessage? = null,
  @SerialName("full_screen_action") val fullScreen: TutorialScreenAction? = null,
  @SerialName("ride_mutation_retry_action") val retry: TutorialRetryAction? = null,
)

@Serializable
internal data class PolarisActionList(@SerialName("sequential_actions") val actions: List<PolarisAction>)

@Serializable
internal data class PolarisAlertAction(
  val description: String? = null,
  val title: String? = null,
  val alert: PolarisAlert? = null,
  @SerialName("actions_map") val actions: Map<String, PolarisAction> = emptyMap(),
)

@Serializable
internal data class PolarisAlert(val message: String, val title: String? = null)

@Serializable
internal data class RideResponse(
  @SerialName("ride_id") val id: String? = null,
  @SerialName("ride_status") val status: String? = null,
  @SerialName("start_date") val startedAt: String? = null,
  @SerialName("concurrent_bikes_count") val bikes: Int? = null,
)

internal fun mutationResult(response: MutationResponse): MutationOutcome {
  if (response.success != null && response.interstitial == null) return MutationOutcome.Accepted
  if (response.success != null || response.interstitial == null) {
    return MutationOutcome.Unavailable("unrecognised unlock result")
  }
  val actions = flattenActions(response.interstitial.action) ?: return MutationOutcome.Unavailable("unsupported unlock action returned by Santander Cycles")
  if (actions.size == 1 && actions.single().fullScreen != null) {
    return parseRideTutorial(checkNotNull(actions.single().fullScreen))
  }
  if (actions.isEmpty() || actions.any { it.alert == null }) {
    return MutationOutcome.Unavailable("unsupported unlock action returned by Santander Cycles")
  }
  val alerts = actions.map { checkNotNull(it.alert) }
  val messages = alerts.map { it.alert?.message ?: it.description }
  if (messages.any { it.isNullOrBlank() }) return MutationOutcome.Unavailable("unlock alert has no message")
  val message = messages.joinToString("\n")
  if (alerts.any { alert -> alert.actions.values.any(::opensPassPurchase) }) {
    return MutationOutcome.AccessNeeded(alerts.firstNotNullOfOrNull { it.alert?.title ?: it.title }, message)
  }
  return MutationOutcome.Refused(message)
}

private fun flattenActions(actions: List<PolarisAction>): List<PolarisAction>? {
  return actions.fold(emptyList()) { collected, action ->
    if (listOfNotNull(action.alert, action.getPass, action.list, action.track, action.fullScreen, action.retry).size != 1) return null
    val list = action.list
    when {
      list != null -> {
        if (list.actions.isEmpty()) return null
        collected + (flattenActions(list.actions) ?: return null)
      }
      action.track != null -> collected
      else -> collected + action
    }
  }
}

private fun opensPassPurchase(action: PolarisAction): Boolean =
  flattenActions(listOf(action))?.any { it.getPass != null } == true
