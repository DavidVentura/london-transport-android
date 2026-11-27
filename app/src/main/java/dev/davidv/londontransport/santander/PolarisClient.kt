package dev.davidv.londontransport.santander

import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

const val POLARIS_USER_AGENT = "com.lyft.android.londonapp:android:15:2026.36.38.1793020363"

private val polarisJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

@JvmInline value class MemberId(val value: String)

@JvmInline
value class PolarisToken(val value: String) {
  override fun toString(): String = "PolarisToken(hidden)"
}

data class PolarisSession(val member: MemberId, val token: PolarisToken)

data class LoginChallenge(val id: String, val expiresAt: Instant, val message: String)

sealed interface PolarisLoginResult {
  data class Authenticated(val session: PolarisSession) : PolarisLoginResult
  data class VerificationRequired(val challenge: LoginChallenge) : PolarisLoginResult
  data class Rejected(val message: String) : PolarisLoginResult
  data class Unavailable(val reason: String) : PolarisLoginResult
}

private enum class Endpoint(val path: String, val message: String) {
  Login("polaris/api/login/2fa", "ReadPolarisLogin2FARequest"),
  Verify("polaris/api/login/2fa/validate", "ReadPolarisLogin2FAValidateRequest"),
  Unlock("ride/mutation", "ReadRideMutationRequest"),
  Ride("polaris/active-ride", "ReadPolarisRideRequest"),
}

class PolarisClient(
  http: OkHttpClient,
  private val device: DeviceId,
  private val base: HttpUrl = "https://london.lyftbikes.com/".toHttpUrl(),
  private val diagnostics: UnlockDiagnostics = UnlockDiagnostics.Disabled,
) : PolarisApi {
  private val http = http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).build()

  override suspend fun login(email: String, password: Password): PolarisLoginResult =
    authenticate(Endpoint.Login, polarisJson.encodeToString(PolarisLoginRequest(email, password.value, device.value)))

  override suspend fun verifyLogin(challenge: LoginChallenge, code: String): PolarisLoginResult {
    if (code.isBlank() || code.any { it !in '0'..'9' }) {
      return PolarisLoginResult.Rejected("Enter the verification code from your email.")
    }
    return authenticate(
      Endpoint.Verify,
      polarisJson.encodeToString(PolarisVerificationRequest(VerificationCode(challenge.id, code), device.value)),
    )
  }

  override suspend fun unlock(session: PolarisSession, bike: QrCode): UnlockResult =
    mutate(session, UnlockAttempt(bike, MutationSessionId(UUID.randomUUID().toString())))

  override suspend fun continueUnlock(session: PolarisSession, continuation: UnlockContinuation): UnlockResult {
    if (session.member != continuation.member) return UnlockResult.Unavailable("The account changed. Scan the bike again to start a new unlock.")
    return mutate(session, continuation.attempt)
  }

  private suspend fun mutate(session: PolarisSession, attempt: UnlockAttempt): UnlockResult {
    val nonce = UUID.randomUUID().toString()
    val body = polarisJson.encodeToString(QrMutationRequest(attempt.bike.value, nonce, session = attempt.session.value, reasons = attempt.reasons))
    return when (val reply = post<MutationResponse>(Endpoint.Unlock, body, session.token)) {
      is Reply.Success -> when (val outcome = mutationResult(reply.body)) {
        MutationOutcome.Accepted -> UnlockResult.Accepted
        is MutationOutcome.AccessNeeded -> UnlockResult.AccessNeeded(outcome.title, outcome.message)
        is MutationOutcome.Refused -> UnlockResult.Refused(outcome.message)
        is MutationOutcome.Unavailable -> UnlockResult.Unavailable(outcome.reason)
        is MutationOutcome.Tutorial -> UnlockResult.TutorialRequired(outcome.tutorial,
          UnlockContinuation(session.member, attempt.copy(reasons = (attempt.reasons + TutorialRetryReason("INTERSTITIAL_POLARIS_PRE_RIDE_TUTORIAL", "frt-completed")).distinct())))
      }
      is Reply.Unauthorized -> UnlockResult.LoggedOut
      is Reply.Error -> UnlockResult.Refused(reply.message)
      is Reply.Failed -> UnlockResult.Unavailable(
        "The unlock outcome is unknown. Check the bike and refresh ride status before trying again. ${reply.reason}"
      )
    }
  }

  override suspend fun activeRide(session: PolarisSession): RideResult =
    when (val reply = post<RideResponse>(Endpoint.Ride, "{}", session.token)) {
      is Reply.Success -> parseRide(reply.body)
      is Reply.Unauthorized -> RideResult.LoggedOut
      is Reply.Error -> RideResult.Unavailable(reply.message)
      is Reply.Failed -> RideResult.Unavailable(reply.reason)
    }

  private suspend fun authenticate(endpoint: Endpoint, body: String): PolarisLoginResult =
    when (val reply = post<PolarisLoginResponse>(endpoint, body, null)) {
      is Reply.Success -> loginResult(reply.body)
      is Reply.Unauthorized -> PolarisLoginResult.Rejected(reply.message ?: "Incorrect email or password.")
      is Reply.Error -> PolarisLoginResult.Rejected(reply.message)
      is Reply.Failed -> PolarisLoginResult.Unavailable(reply.reason)
    }

  private sealed interface Reply<out T> {
    data class Success<T>(val body: T) : Reply<T>
    data class Unauthorized(val message: String?) : Reply<Nothing>
    data class Error(val status: Int, val message: String) : Reply<Nothing>
    data class Failed(val reason: String) : Reply<Nothing>
  }

  private suspend inline fun <reified T> post(endpoint: Endpoint, body: String, token: PolarisToken?): Reply<T> =
    withContext(Dispatchers.IO) {
      val url = base.newBuilder().addPathSegments("v1/lbsbff/${endpoint.path}").build()
      val request = Request.Builder().url(url)
        .header("Accept", "application/json")
        .header("User-Agent", POLARIS_USER_AGENT)
        .apply { if (token != null) header("x-polaris-Authorization", token.value) }
        .post(body.toRequestBody("application/json;messageType=pb.api.endpoints.v1.lbs_bff.${endpoint.message}".toMediaType()))
        .build()
      try {
        http.newCall(request).execute().use { response ->
          val payload = response.body.string()
          if (endpoint == Endpoint.Unlock) diagnostics.response(response.code, payload)
          if (response.isSuccessful) return@withContext Reply.Success(polarisJson.decodeFromString<T>(payload))
          if (response.code !in 400..499) return@withContext Reply.Failed("HTTP ${response.code}")
          val message = runCatching { polarisJson.decodeFromString<PolarisError>(payload).message() }.getOrNull()
          if (response.code == 401) return@withContext Reply.Unauthorized(message)
          message?.let { Reply.Error(response.code, it) } ?: Reply.Failed("unrecognised error (HTTP ${response.code})")
        }
      } catch (e: IOException) {
        if (endpoint == Endpoint.Unlock) diagnostics.failure("Unlock network failure: ${e.javaClass.simpleName}")
        Reply.Failed(e.message ?: "network unavailable")
      } catch (e: SerializationException) {
        if (endpoint == Endpoint.Unlock) diagnostics.failure("Unlock response could not be decoded")
        Reply.Failed("unrecognised response from Santander Cycles")
      }
    }
}

@Serializable
private data class PolarisLoginRequest(val username: String, val password: String, @SerialName("device_id") val deviceId: String)

@Serializable
private data class PolarisVerificationRequest(val mfa: VerificationCode, @SerialName("device_id") val deviceId: String)

@Serializable
private data class VerificationCode(val id: String, @SerialName("verification_code") val code: String)

@Serializable
private data class PolarisLoginResponse(val access: PolarisAccess? = null, val mfa: PolarisMfa? = null)

@Serializable
private data class PolarisAccess(@SerialName("member_id") val member: String, @SerialName("access_token") val token: String)

@Serializable
private data class PolarisMfa(val id: String, @SerialName("expires_on_ms") val expiresOnMs: Long, val message: PolarisMfaMessage)

@Serializable
private data class PolarisMfaMessage(val title: String, val description: String)

@Serializable
private data class PolarisError(
  val error: String? = null,
  @SerialName("error_description") val description: String? = null,
  @SerialName("panel_error") val panelError: PolarisPanelError? = null,
)

@Serializable
private data class PolarisPanelError(@SerialName("panel_action") val action: PolarisAction? = null)

private fun PolarisError.message(): String? =
  panelError?.action?.alert?.let { it.alert?.message ?: it.description } ?: description ?: error

fun parsePolarisLogin(body: String): PolarisLoginResult =
  loginResult(polarisJson.decodeFromString<PolarisLoginResponse>(body))

private fun loginResult(response: PolarisLoginResponse): PolarisLoginResult {
  val access = response.access
  val mfa = response.mfa
  if (access != null && mfa == null && access.member.isNotBlank() && access.token.isNotBlank()) {
    return PolarisLoginResult.Authenticated(PolarisSession(MemberId(access.member), PolarisToken(access.token)))
  }
  if (mfa != null && access == null && mfa.id.isNotBlank() && mfa.expiresOnMs > 0) {
    return PolarisLoginResult.VerificationRequired(LoginChallenge(mfa.id, Instant.ofEpochMilli(mfa.expiresOnMs), mfa.message.description))
  }
  return PolarisLoginResult.Unavailable("login did not return one valid account or verification challenge")
}

private fun parseRide(response: RideResponse): RideResult {
  if (response.id == null && response.status == null && response.startedAt == null && response.bikes == null) {
    return RideResult.Success(null)
  }
  if (response.id.isNullOrBlank() || response.status.isNullOrBlank() || (response.bikes != null && response.bikes < 0)) {
    return RideResult.Unavailable("incomplete ride status")
  }
  val startedAt = try {
    response.startedAt?.let(Instant::parse)
  } catch (e: DateTimeParseException) {
    return RideResult.Unavailable("invalid ride start time")
  }
  return RideResult.Success(CurrentRide(RideId(response.id), RideStatus(response.status), startedAt, response.bikes))
}
