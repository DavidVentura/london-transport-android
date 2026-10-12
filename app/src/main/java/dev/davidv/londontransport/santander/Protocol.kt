package dev.davidv.londontransport.santander

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

const val SANTANDER_BASE = CoreConstants.BASE_URL

/** Sent as a body field on every call; the backend routes on it rather than on the Host header. */
const val REMOTE_HOST = CoreConstants.REMOTE_HOST

private const val VERIFICATION_FLAG = "encryptionverificationcheck"

@JvmInline value class DeviceId(val value: String)

@JvmInline value class UserNodeId(val value: String)

@JvmInline value class AuthToken(val value: String)

@JvmInline
value class Password(val value: String) {
  override fun toString(): String = "Password(hidden)"
}

data class Credentials(val user: UserNodeId, val token: AuthToken)

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

/** The server checks the injected flag to know the payload decrypted correctly. */
fun encryptControlVals(fields: Map<String, String>): String {
  val key = Base64.getDecoder().decode(CoreConstants.CONTROL_VALS_KEY_BASE64)
  val form =
    (fields + (VERIFICATION_FLAG to "1"))
      .map { (key, value) -> "${percentEncode(key)}=${percentEncode(value)}" }
      .joinToString("&")
  val cipher =
    Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
      init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(key, "AES"),
        IvParameterSpec(key.copyOf(16)),
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
