package dev.davidv.londontransport.threeds

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.text.ParseException
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ProtocolFault {
  MalformedInput, UnsupportedProfile, UntrustedCertificate, InvalidSignature, KeyMismatch,
  IntegrityFailure, TransactionMismatch, CounterMismatch, InvalidState, Timeout,
  ExpiredConfiguration, ConfigurationNotYetValid, ChallengeTransport,
}

sealed interface ProtocolResult<out T> {
  data class Success<T>(val value: T) : ProtocolResult<T>
  data class Failure(val fault: ProtocolFault) : ProtocolResult<Nothing>
}

internal class ProtocolException(val fault: ProtocolFault) : RuntimeException()

internal fun <T> protocolBoundary(block: () -> T): ProtocolResult<T> =
  try {
    ProtocolResult.Success(block())
  } catch (e: ProtocolException) {
    ProtocolResult.Failure(e.fault)
  } catch (e: SerializationException) {
    ProtocolResult.Failure(ProtocolFault.MalformedInput)
  } catch (e: ParseException) {
    ProtocolResult.Failure(ProtocolFault.MalformedInput)
  } catch (e: JOSEException) {
    ProtocolResult.Failure(ProtocolFault.IntegrityFailure)
  } catch (e: GeneralSecurityException) {
    ProtocolResult.Failure(ProtocolFault.IntegrityFailure)
  } catch (e: IllegalArgumentException) {
    ProtocolResult.Failure(ProtocolFault.MalformedInput)
  }

@Serializable
internal data class AcsSignedData(
  val acsURL: String,
  val acsEphemPubKey: PublicJwk,
  val sdkEphemPubKey: PublicJwk,
)

internal data class VerifiedAcs(val url: String, val publicKey: ECPublicKey)

internal fun verifyAcs(
  signedContent: String,
  transaction: SdkTransaction,
  roots: Set<TrustAnchor>,
  now: Instant,
): VerifiedAcs {
  if (signedContent.length > 64_000) throw ProtocolException(ProtocolFault.MalformedInput)
  val jws = JWSObject.parse(signedContent)
  val algorithm = jws.header.algorithm
  if (algorithm != JWSAlgorithm.ES256 && algorithm != JWSAlgorithm.RS256) {
    throw ProtocolException(ProtocolFault.UnsupportedProfile)
  }
  if (!jws.header.criticalParams.isNullOrEmpty() || !jws.header.isBase64URLEncodePayload) {
    throw ProtocolException(ProtocolFault.UnsupportedProfile)
  }
  val chain = jws.header.x509CertChain
  if (chain.isNullOrEmpty() || chain.size > 8 || roots.isEmpty()) {
    throw ProtocolException(ProtocolFault.UntrustedCertificate)
  }
  val certificates = try {
    val factory = CertificateFactory.getInstance("X.509")
    chain.map { factory.generateCertificate(it.decode().inputStream()) as X509Certificate }
  } catch (e: GeneralSecurityException) {
    throw ProtocolException(ProtocolFault.UntrustedCertificate)
  }
  try {
    certificates.forEach { it.checkValidity(Date.from(now)) }
    val path = if (roots.any { it.trustedCert == certificates.last() }) certificates.dropLast(1) else certificates
    if (path.isEmpty() || certificates.first().basicConstraints >= 0) {
      throw ProtocolException(ProtocolFault.UntrustedCertificate)
    }
    val usage = certificates.first().keyUsage
    if (usage != null && !usage[0]) throw ProtocolException(ProtocolFault.UntrustedCertificate)
    val params = PKIXParameters(roots).apply {
      date = Date.from(now)
      isRevocationEnabled = false
    }
    CertPathValidator.getInstance("PKIX").validate(
      CertificateFactory.getInstance("X.509").generateCertPath(path), params,
    )
  } catch (e: GeneralSecurityException) {
    throw ProtocolException(ProtocolFault.UntrustedCertificate)
  }
  val key = certificates.first().publicKey
  val verifier = when {
    algorithm == JWSAlgorithm.ES256 && key is ECPublicKey -> ECDSAVerifier(key)
    algorithm == JWSAlgorithm.RS256 && key is RSAPublicKey -> RSASSAVerifier(key)
    else -> throw ProtocolException(ProtocolFault.UnsupportedProfile)
  }
  if (!jws.verify(verifier)) throw ProtocolException(ProtocolFault.InvalidSignature)
  val signed = Json.decodeFromString<AcsSignedData>(jws.payload.toString())
  if (signed.sdkEphemPubKey != transaction.publicKey) throw ProtocolException(ProtocolFault.KeyMismatch)
  val url = java.net.URI(signed.acsURL)
  if (url.scheme != "https" || url.host.isNullOrEmpty() || url.userInfo != null || url.fragment != null) {
    throw ProtocolException(ProtocolFault.MalformedInput)
  }
  return VerifiedAcs(signed.acsURL, signed.acsEphemPubKey.toKey())
}

internal fun deriveChallengeKey(
  privateKey: ECPrivateKey,
  publicKey: ECPublicKey,
  reference: SdkReferenceNumber,
): ByteArray {
  val agreement = KeyAgreement.getInstance("ECDH")
  agreement.init(privateKey)
  agreement.doPhase(publicKey, true)
  val shared = agreement.generateSecret()
  val partyV = reference.value.toByteArray(Charsets.UTF_8)
  val kdfInput = ByteBuffer.allocate(4 + shared.size + 4 + 4 + 4 + partyV.size + 4)
    .putInt(1).put(shared)
    .putInt(0).putInt(0).putInt(partyV.size).put(partyV).putInt(256).array()
  return MessageDigest.getInstance("SHA-256").digest(kdfInput)
}

@Serializable
internal data class ChallengeHeader(val alg: String, val enc: String, val kid: AcsTransactionId)

internal class ChallengeIv private constructor(internal val bytes: ByteArray) {
  companion object {
    fun generate(): ChallengeIv {
      val bytes = ByteArray(16)
      java.security.SecureRandom().nextBytes(bytes)
      return ChallengeIv(bytes)
    }
  }
}

internal fun encryptChallenge(
  payload: String,
  key: ByteArray,
  acsId: AcsTransactionId,
  freshIv: ChallengeIv,
): String {
  require(key.size == 32)
  val encode = Base64.getUrlEncoder().withoutPadding()
  val header = encode.encodeToString(Json.encodeToString(ChallengeHeader("dir", "A128CBC-HS256", acsId)).toByteArray())
  val iv = freshIv.bytes
  val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
  cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOfRange(16, 32), "AES"), IvParameterSpec(iv))
  val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
  val tag = challengeTag(header, iv, ciphertext, key)
  return listOf(header, "", encode.encodeToString(iv), encode.encodeToString(ciphertext), encode.encodeToString(tag)).joinToString(".")
}

internal fun decryptChallenge(
  packet: String,
  key: ByteArray,
  acsId: AcsTransactionId,
): String {
  require(key.size == 32)
  if (packet.length > 64_000) throw ProtocolException(ProtocolFault.MalformedInput)
  val parts = packet.split('.')
  if (parts.size != 5 || parts[1].isNotEmpty()) throw ProtocolException(ProtocolFault.MalformedInput)
  val decoder = Base64.getUrlDecoder()
  val values = listOf(parts[0], parts[2], parts[3], parts[4]).map { part ->
    if (part.isEmpty() || !part.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
      throw ProtocolException(ProtocolFault.MalformedInput)
    }
    val bytes = decoder.decode(part)
    if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != part) {
      throw ProtocolException(ProtocolFault.MalformedInput)
    }
    bytes
  }
  val header = Json.decodeFromString<ChallengeHeader>(values[0].toString(Charsets.UTF_8))
  if (header.alg != "dir" || header.enc != "A128CBC-HS256") throw ProtocolException(ProtocolFault.UnsupportedProfile)
  if (header.kid != acsId) throw ProtocolException(ProtocolFault.TransactionMismatch)
  val (iv, ciphertext, tag) = values.drop(1)
  if (iv.size != 16 || tag.size != 16 || ciphertext.isEmpty() || ciphertext.size % 16 != 0) {
    throw ProtocolException(ProtocolFault.MalformedInput)
  }
  if (!MessageDigest.isEqual(tag, challengeTag(parts[0], iv, ciphertext, key))) {
    throw ProtocolException(ProtocolFault.IntegrityFailure)
  }
  val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
  cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(16, 32), "AES"), IvParameterSpec(iv))
  return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
}

private fun challengeTag(header: String, iv: ByteArray, ciphertext: ByteArray, key: ByteArray): ByteArray {
  val aad = header.toByteArray(Charsets.US_ASCII)
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(key.copyOfRange(0, 16), "HmacSHA256"))
  mac.update(aad)
  mac.update(iv)
  mac.update(ciphertext)
  mac.update(ByteBuffer.allocate(8).putLong(aad.size.toLong() * 8).array())
  return mac.doFinal().copyOf(16)
}
