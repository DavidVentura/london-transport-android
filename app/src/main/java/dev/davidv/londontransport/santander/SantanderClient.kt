package dev.davidv.londontransport.santander

import android.util.Log
import dev.davidv.londontransport.domain.PointName
import dev.davidv.londontransport.domain.TerminalName
import java.io.IOException
import java.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface LoginResult {
  data class Success(val credentials: Credentials) : LoginResult

  data class Rejected(val message: String) : LoginResult

  data class Unavailable(val reason: String) : LoginResult
}

sealed interface HireResult {
  data class Released(val code: HireCode) : HireResult

  data class VerificationNeeded(val challenge: SmsChallenge) : HireResult

  data class AccessNeeded(val options: List<AccessOption>) : HireResult

  data class Refused(val message: String) : HireResult

  data object LoggedOut : HireResult

  data class Unavailable(val reason: String) : HireResult
}

/** Keeps raw replies from authenticated calls so pages nobody has mapped yet can be studied. */
fun interface ReplyRecorder {
  fun record(url: HttpUrl, body: String)
}

sealed interface VerifyResult {
  data object Verified : VerifyResult

  data class Rejected(val message: String) : VerifyResult

  data object LoggedOut : VerifyResult

  data class Unavailable(val reason: String) : VerifyResult
}

interface SantanderApi {
  suspend fun login(email: String, password: Password): LoginResult

  /** There is no cancel: a [HireResult.Released] is already a charge. */
  suspend fun hire(credentials: Credentials, terminal: TerminalName, point: PointName): HireResult

  suspend fun verifyDevice(
    credentials: Credentials,
    challenge: SmsChallenge,
    digits: String,
  ): VerifyResult
}

private val RETRYABLE = setOf(502, 503, 504)
private const val ATTEMPTS = 6

private const val PROCESS_LOGIN = "Clients/TfL/TSUF/ProcessLogin"
private const val BEGIN_HIRE = "Clients/TfL/EBikePhase2/BeginMemberHire"
private const val CONFIRM_HIRE = "Clients/TfL/EBikePhase2/ConfirmMemberHire"

class SantanderClient(
  private val http: OkHttpClient,
  private val deviceId: DeviceId,
  private val recorder: ReplyRecorder,
  private val base: String = SANTANDER_BASE,
) : SantanderApi {

  override suspend fun login(email: String, password: Password): LoginResult {
    val page =
      post(
        url(
          PROCESS_LOGIN,
          mapOf("terminal" to "", "terminalname" to "", "navigateBackToID" to ""),
        ),
        controlVals = mapOf("login_email" to email, "login_password" to password.value),
      )
    val nodes =
      when (page) {
        is Reply.Failed -> return LoginResult.Unavailable(page.reason)
        is Reply.Refused -> return LoginResult.Rejected(page.message)
        is Reply.Page -> page.nodes
      }

    // Step one only confirms the email back to the user; the token is behind its Continue button.
    val continuation =
      loginContinuation(nodes)
        ?: return LoginResult.Rejected(simpleResponse(nodes) ?: "Those details were not accepted.")

    return when (val confirmed = post(followUrl(continuation))) {
      is Reply.Failed -> LoginResult.Unavailable(confirmed.reason)
      is Reply.Refused -> LoginResult.Rejected(confirmed.message)
      is Reply.Page ->
        userCredentials(confirmed.nodes)?.let(LoginResult::Success)
          ?: LoginResult.Unavailable("login did not return an account token")
    }
  }

  override suspend fun hire(
    credentials: Credentials,
    terminal: TerminalName,
    point: PointName,
  ): HireResult {
    // Begin is non-committal — it is where an empty or closed dock says so, before anything bills.
    val begun = post(url(BEGIN_HIRE, mapOf("TerminalName" to terminal.value)), credentials)
    val nodes =
      when (begun) {
        is Reply.Failed -> return HireResult.Unavailable(begun.reason)
        is Reply.Refused -> return refusedHire(begun.message)
        is Reply.Page -> begun.nodes
      }
    when (val page = parseBeginPage(nodes)) {
      BeginPage.Ready -> Unit
      // Confirming without access answers only "problem getting a release code".
      is BeginPage.AccessNeeded -> return HireResult.AccessNeeded(page.options)
      is BeginPage.Refused -> return refusedHire(page.message)
      is BeginPage.Malformed -> return HireResult.Unavailable(page.reason)
    }

    val confirmed =
      post(
        url(
          CONFIRM_HIRE,
          mapOf(
            "TerminalName" to terminal.value,
            "PointName" to pointParam(point),
            "LCHS_Confirm" to "1",
            "nbBikes" to "1",
          ),
        ),
        credentials,
      )
    return when (confirmed) {
      is Reply.Failed -> HireResult.Unavailable(confirmed.reason)
      is Reply.Refused -> refusedHire(confirmed.message)
      is Reply.Page ->
        when (val page = parseHirePage(confirmed.nodes)) {
          is HirePage.Released -> HireResult.Released(page.code)
          is HirePage.VerificationNeeded -> HireResult.VerificationNeeded(page.challenge)
          is HirePage.Refused -> refusedHire(page.message)
          is HirePage.Malformed -> HireResult.Unavailable(page.reason)
        }
    }
  }

  override suspend fun verifyDevice(
    credentials: Credentials,
    challenge: SmsChallenge,
    digits: String,
  ): VerifyResult {
    val typed = digits.trim()
    if (typed.length != challenge.pincodeFields.size) {
      return VerifyResult.Rejected("Enter all ${challenge.pincodeFields.size} digits.")
    }
    val reply =
      post(
        followUrl(challenge.verifyUri),
        credentials,
        controlVals = challenge.pincodeFields.zip(typed.map(Char::toString)).toMap(),
      )
    return when (reply) {
      is Reply.Failed -> VerifyResult.Unavailable(reply.reason)
      is Reply.Refused -> rejectedVerification(reply.message)
      is Reply.Page ->
        if (deviceVerified(reply.nodes)) VerifyResult.Verified
        else rejectedVerification(simpleResponse(reply.nodes) ?: "That code was not accepted.")
    }
  }

  private fun refusedHire(message: String): HireResult =
    if (loggedOut(message)) HireResult.LoggedOut else HireResult.Refused(message)

  private fun rejectedVerification(message: String): VerifyResult =
    if (loggedOut(message)) VerifyResult.LoggedOut else VerifyResult.Rejected(message)

  private sealed interface Reply {
    data class Page(val nodes: List<Node>) : Reply

    data class Refused(val message: String) : Reply

    data class Failed(val reason: String) : Reply
  }

  private fun url(path: String, params: Map<String, String>): HttpUrl =
    (base + path).toHttpUrl().newBuilder().apply {
      params.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build()

  private fun followUrl(targetUri: String): HttpUrl = partUriToUrl(targetUri, base).toHttpUrl()

  private suspend fun post(
    url: HttpUrl,
    credentials: Credentials? = null,
    controlVals: Map<String, String>? = null,
  ): Reply =
    withContext(Dispatchers.IO) {
      val request = buildCoreRequest(url, deviceId, credentials, controlVals, Instant.now())

      val text = send(request)
      // Only authenticated calls are kept: the unauthenticated ones are the login, whose reply is the token.
      if (text is Fetch.Body && credentials != null) {
        recorder.record(url, text.text)
      }
      when (text) {
        is Fetch.Failed -> Reply.Failed(text.reason)
        is Fetch.Body ->
          when (val reply = parseNodeReply(text.text)) {
            is NodeReply.Nodes -> Reply.Page(reply.nodes)
            is NodeReply.Rejected -> Reply.Refused(reply.message)
            is NodeReply.Malformed -> Reply.Failed(reply.reason)
          }
      }
    }

  private sealed interface Fetch {
    data class Body(val text: String) : Fetch

    data class Failed(val reason: String) : Fetch
  }

  /**
   * The backend hangs a failing request until its gateway times out, so a slow call is retried
   * rather than waited out. A retried hire is safe: nothing commits until the confirm call returns.
   */
  private sealed interface Attempt {
    data class Done(val fetch: Fetch) : Attempt

    data class Retry(val reason: String) : Attempt
  }

  /** Every attempt is logged with its time, so a slow hire shows whether it was a retry. */
  private suspend fun send(request: Request): Fetch {
    val endpoint = request.url.pathSegments.last()
    var last = "no answer"
    repeat(ATTEMPTS) { attempt ->
      val started = TimeSource.Monotonic.markNow()
      val outcome =
        try {
          http.newCall(request).execute().use { response ->
            when {
              response.code in RETRYABLE -> Attempt.Retry("HTTP ${response.code}")
              !response.isSuccessful -> Attempt.Done(Fetch.Failed("HTTP ${response.code}"))
              else -> Attempt.Done(Fetch.Body(response.body.string()))
            }
          }
        } catch (e: IOException) {
          Attempt.Retry(e.message ?: "network unavailable")
        }
      val took = started.elapsedNow().inWholeMilliseconds
      val tried = "$endpoint attempt ${attempt + 1}/$ATTEMPTS"
      when (outcome) {
        is Attempt.Done -> {
          Log.i(TAG, "$tried finished in $took ms")
          return outcome.fetch
        }
        is Attempt.Retry -> {
          Log.w(TAG, "$tried failed after $took ms: ${outcome.reason}")
          last = outcome.reason
        }
      }
      if (attempt < ATTEMPTS - 1) delay(RETRY_PAUSE_MS)
    }
    return Fetch.Failed(last)
  }

  private companion object {
    const val RETRY_PAUSE_MS = 1_000L
    const val TAG = "SantanderClient"
  }
}

/** The backend splits its own query string on a literal ampersand, so dock names spell theirs out. */
private fun pointParam(point: PointName): String = point.value.replace("&", "and")
