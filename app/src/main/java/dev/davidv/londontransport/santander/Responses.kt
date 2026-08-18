package dev.davidv.londontransport.santander

import dev.davidv.londontransport.domain.ReleaseCode
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** The docks print London wall clock, whatever the phone's own zone says. */
val LONDON_TIME: ZoneId = ZoneId.of("Europe/London")

private val UNLOCK_BAR = Regex("""valid at (.+?) until (\d{1,2}:\d{2})""")

private const val VERIFY_BUTTON = "member_next_button_sms"
private const val PINCODE_PREFIX = "lchs_pincode_"
private const val SMS_INTRO = "member_sms_desc1"
private const val LOGIN_CONTINUE = "login_continue_button"
private const val TEXT_BOX = "Node.FormControls.TextBox"

/** How long a release code lasts when the server did not print an expiry alongside it. */
private val ASSUMED_LIFETIME: Duration = Duration.ofMinutes(10)

data class HireCode(val code: ReleaseCode, val station: String, val validUntil: LocalTime?)

/** An untrusted device: the digits texted to the account go back to [verifyUri], one per field. */
data class SmsChallenge(val verifyUri: String, val pincodeFields: List<String>, val message: String)

sealed interface HirePage {
  data class Released(val code: HireCode) : HirePage

  data class VerificationNeeded(val challenge: SmsChallenge) : HirePage

  /** The server declined in a sentence: no bikes here, hire limit reached, account on hold. */
  data class Refused(val message: String) : HirePage

  data class Malformed(val reason: String) : HirePage
}

fun parseHirePage(nodes: List<Node>): HirePage {
  val challenge = smsChallenge(nodes)
  if (challenge != null) return HirePage.VerificationNeeded(challenge)

  val refusal = simpleResponse(nodes)
  if (refusal != null) return HirePage.Refused(refusal)

  val codeNode =
    nodes.firstOrNull { it.subtitle.isNotBlank() && it.name.contains("release code", true) }
      ?: return HirePage.Malformed("no release code on the hire page")
  val bar = nodes.firstOrNull { it.id.endsWith("_unlockbar") }
  val detail = UNLOCK_BAR.find(bar?.name.orEmpty())

  return HirePage.Released(
    HireCode(
      code = ReleaseCode(codeNode.subtitle),
      station = detail?.groupValues?.get(1).orEmpty(),
      validUntil = detail?.groupValues?.get(2)?.let(::readClock),
    )
  )
}

private fun readClock(printed: String): LocalTime? =
  try {
    LocalTime.parse(if (printed.length == 4) "0$printed" else printed)
  } catch (e: DateTimeParseException) {
    null
  }

private fun smsChallenge(nodes: List<Node>): SmsChallenge? {
  val button = nodes.firstOrNull { it.id == VERIFY_BUTTON } ?: return null
  return SmsChallenge(
    verifyUri = button.targetUri,
    pincodeFields =
      nodes.filter { it.type == TEXT_BOX && it.id.startsWith(PINCODE_PREFIX) }.map { it.id }.sorted(),
    message =
      nodes.firstOrNull { it.id == SMS_INTRO }?.name ?: "This device needs verifying before you can hire.",
  )
}

/**
 * When the code stops working. The server prints an absolute wall-clock time and nothing else, so a
 * time already past today belongs to tomorrow. A code with no printed expiry is still a code the
 * rider has been charged for, so it is kept for the usual ten minutes rather than discarded.
 */
fun releaseCodeExpiry(issuedAt: Instant, validUntil: LocalTime?, zone: ZoneId = LONDON_TIME): Instant {
  if (validUntil == null) return issuedAt.plus(ASSUMED_LIFETIME)
  val issued = issuedAt.atZone(zone)
  val sameDay = issued.with(validUntil).withSecond(0).withNano(0)
  return (if (sameDay.toInstant() < issuedAt) sameDay.plusDays(1) else sameDay).toInstant()
}

/** Step one of login answers with a confirmation page; its Continue button is step two's target. */
fun loginContinuation(nodes: List<Node>): String? =
  nodes.firstOrNull { it.id == LOGIN_CONTINUE }?.targetUri?.takeIf { it.isNotBlank() }

fun userCredentials(nodes: List<Node>): Credentials? =
  nodes
    .firstOrNull { it.type == USER_NODE && it.authToken.isNotBlank() }
    ?.let { Credentials(UserNodeId(it.id), AuthToken(it.authToken)) }

fun deviceVerified(nodes: List<Node>): Boolean =
  simpleResponse(nodes)?.contains("verified", ignoreCase = true) == true
