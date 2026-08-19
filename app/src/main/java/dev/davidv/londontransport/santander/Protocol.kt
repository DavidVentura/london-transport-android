package dev.davidv.londontransport.santander

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

const val SANTANDER_BASE = "https://ce-a24.corethree.net/"

/** Sent as a body field on every call; the backend routes on it rather than on the Host header. */
const val REMOTE_HOST = "ce.corethree.net"

// Lifted verbatim from the shipped app (zd/a.java), where the IV is the key's first 16 bytes.
private val CONTROL_VALS_KEY: ByteArray =
  Base64.getDecoder().decode("diS1dUbsVQyWSRA5zhAbKJrxYmLa7hD5CVUWc4OZaRs=")

private const val VERIFICATION_FLAG = "encryptionverificationcheck"

@JvmInline value class DeviceId(val value: String)

@JvmInline value class UserNodeId(val value: String)

@JvmInline value class AuthToken(val value: String)

@JvmInline
value class Password(val value: String) {
  override fun toString(): String = "Password(hidden)"
}

/** What the backend accepts on a call; it carries no expiry, and dies without warning. */
data class Credentials(val user: UserNodeId, val token: AuthToken)

/**
 * Everything needed to act as the rider, including what it takes to earn a new token once the
 * stored one stops being accepted.
 */
data class Account(val email: String, val password: Password, val credentials: Credentials)

/** `part://Clients.TfL.X.Y?q=1` -> `<base>Clients/TfL/X/Y?q=1`; dots become slashes in the path only. */
fun partUriToUrl(partUri: String, base: String = SANTANDER_BASE): String {
  require(partUri.startsWith(PART_SCHEME)) { "not a part uri: $partUri" }
  val body = partUri.removePrefix(PART_SCHEME)
  val query = body.indexOf('?')
  if (query < 0) return base + body.replace('.', '/')
  return base + body.substring(0, query).replace('.', '/') + body.substring(query)
}

private const val PART_SCHEME = "part://"

fun userAuthHeader(credentials: Credentials): String {
  val digest =
    MessageDigest.getInstance("SHA-1")
      .digest((credentials.user.value + credentials.token.value).toByteArray(Charsets.US_ASCII))
  return digest.joinToString("") { "%02x".format(it) } + "|" + credentials.user.value
}

/**
 * Passwords and SMS digits travel as `c3-controlvals`: the form joined as `k=v&k=v`, AES-256-CBC
 * encrypted under the app's static key, base64'd. The server checks the injected flag to know the
 * payload decrypted correctly.
 */
fun encryptControlVals(fields: Map<String, String>): String {
  val form =
    (fields + (VERIFICATION_FLAG to "1"))
      .map { (key, value) -> "${percentEncode(key)}=${percentEncode(value)}" }
      .joinToString("&")
  val cipher =
    Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
      init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(CONTROL_VALS_KEY, "AES"),
        IvParameterSpec(CONTROL_VALS_KEY.copyOf(16)),
      )
    }
  return Base64.getEncoder().encodeToString(cipher.doFinal(form.toByteArray(Charsets.UTF_8)))
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-~/"

/** RFC 3986 with `/` left alone, matching the encoder on the other side of this cipher. */
internal fun percentEncode(raw: String): String =
  buildString {
    raw.toByteArray(Charsets.UTF_8).forEach { byte ->
      val char = byte.toInt().toChar()
      if (char in UNRESERVED) append(char) else append("%%%02X".format(byte))
    }
  }
