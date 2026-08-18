package dev.davidv.londontransport.santander

import dev.davidv.londontransport.domain.PointName
import dev.davidv.londontransport.domain.TerminalName
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface LoginResult {
  data class Success(val credentials: Credentials) : LoginResult

  /** The backend said no: wrong password, locked account. */
  data class Rejected(val message: String) : LoginResult

  data class Unavailable(val reason: String) : LoginResult
}

sealed interface HireResult {
  data class Released(val code: HireCode) : HireResult

  data class VerificationNeeded(val challenge: SmsChallenge) : HireResult

  data class Refused(val message: String) : HireResult

  data class Unavailable(val reason: String) : HireResult
}

sealed interface VerifyResult {
  data object Verified : VerifyResult

  data class Rejected(val message: String) : VerifyResult

  data class Unavailable(val reason: String) : VerifyResult
}

/**
 * Everything the app can do against the Santander Cycles backend. Three calls, each of which either
 * finishes the job or names what stopped it; nodes, part uris and encrypted form values stay behind
 * this interface.
 */
interface SantanderApi {
  suspend fun login(email: String, password: String): LoginResult

  /** Releases a physical bike. There is no cancel: a [HireResult.Released] is already a charge. */
  suspend fun hire(credentials: Credentials, terminal: TerminalName, point: PointName): HireResult

  suspend fun verifyDevice(
    credentials: Credentials,
    challenge: SmsChallenge,
    digits: String,
  ): VerifyResult
}

private const val USER_AGENT = "Core/642 (Android; LE2123; Android 14; uk.gov.tfl.cyclehire)"
private const val DIAGNOSTICS = """{"nodestore":"count":"0","bytelength":"0"}"""
private val RETRYABLE = setOf(502, 503, 504)
private const val ATTEMPTS = 6

private const val PROCESS_LOGIN = "Clients/TfL/TSUF/ProcessLogin"
private const val BEGIN_HIRE = "Clients/TfL/EBikePhase2/BeginMemberHire"
private const val CONFIRM_HIRE = "Clients/TfL/EBikePhase2/ConfirmMemberHire"

class SantanderClient(
  private val http: OkHttpClient,
  private val deviceId: DeviceId,
  private val base: String = SANTANDER_BASE,
) : SantanderApi {

  override suspend fun login(email: String, password: String): LoginResult {
    val page =
      post(
        url(
          PROCESS_LOGIN,
          mapOf("terminal" to "", "terminalname" to "", "navigateBackToID" to ""),
        ),
        controlVals = mapOf("login_email" to email, "login_password" to password),
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
    when (begun) {
      is Reply.Failed -> return HireResult.Unavailable(begun.reason)
      is Reply.Refused -> return HireResult.Refused(begun.message)
      is Reply.Page -> simpleResponse(begun.nodes)?.let { return HireResult.Refused(it) }
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
      is Reply.Refused -> HireResult.Refused(confirmed.message)
      is Reply.Page ->
        when (val page = parseHirePage(confirmed.nodes)) {
          is HirePage.Released -> HireResult.Released(page.code)
          is HirePage.VerificationNeeded -> HireResult.VerificationNeeded(page.challenge)
          is HirePage.Refused -> HireResult.Refused(page.message)
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
      is Reply.Refused -> VerifyResult.Rejected(reply.message)
      is Reply.Page ->
        if (deviceVerified(reply.nodes)) VerifyResult.Verified
        else VerifyResult.Rejected(simpleResponse(reply.nodes) ?: "That code was not accepted.")
    }
  }

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
      val body =
        FormBody.Builder()
          .add("c3-clienttime", (System.currentTimeMillis() / 1000).toString())
          .add("c3-deviceid", deviceId.value)
          .add("RemoteHost", REMOTE_HOST)
          .apply { controlVals?.let { add("c3-controlvals", encryptControlVals(it)) } }
          .build()

      val request =
        Request.Builder()
          .url(url)
          .post(body)
          .header("User-agent", USER_AGENT)
          .header("format", "flatjson")
          .header("c3-userauth", credentials?.let(::userAuthHeader).orEmpty())
          .header("c3-capabilities", "")
          .header("c3-language", "English")
          .header("c3-diagnostics", DIAGNOSTICS)
          .apply { if (controlVals != null) header("c3-applysensitivedatacheck", "y") }
          .build()

      when (val text = send(request)) {
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
   * The backend answers a working request in seconds and hangs a failing one to its own gateway
   * timeout, so a slow call is retried rather than waited out. Only login is habitually affected,
   * but a retried hire is safe: nothing commits until the confirm call returns.
   */
  private suspend fun send(request: Request): Fetch {
    var last = "no answer"
    repeat(ATTEMPTS) { attempt ->
      val outcome =
        try {
          http.newCall(request).execute().use { response ->
            if (response.code in RETRYABLE) Fetch.Failed("HTTP ${response.code}")
            else if (!response.isSuccessful) return Fetch.Failed("HTTP ${response.code}")
            else Fetch.Body(response.body.string())
          }
        } catch (e: IOException) {
          Fetch.Failed(e.message ?: "network unavailable")
        }
      when (outcome) {
        is Fetch.Body -> return outcome
        is Fetch.Failed -> last = outcome.reason
      }
      if (attempt < ATTEMPTS - 1) delay(RETRY_PAUSE_MS)
    }
    return Fetch.Failed(last)
  }

  private companion object {
    const val RETRY_PAUSE_MS = 1_000L
  }
}

/** The backend splits its own query string on a literal ampersand, so dock names spell theirs out. */
private fun pointParam(point: PointName): String = point.value.replace("&", "and")
