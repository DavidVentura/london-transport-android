package dev.davidv.londontransport.threeds

import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.Date
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class DirectoryServerConfiguration internal constructor(
  val encryptionCertificate: X509Certificate,
  private val encryptionKey: RSAPublicKey,
  val challengeRoots: Set<TrustAnchor>,
) {
  fun encrypt(device: DeviceInformation, now: Instant): ProtocolResult<String> = protocolBoundary {
    try {
      encryptionCertificate.checkValidity(Date.from(now))
    } catch (e: java.security.cert.CertificateNotYetValidException) {
      throw ProtocolException(ProtocolFault.ConfigurationNotYetValid)
    } catch (e: java.security.cert.CertificateExpiredException) {
      throw ProtocolException(ProtocolFault.ExpiredConfiguration)
    }
    when (val encrypted = encryptDeviceInformation(device, encryptionKey)) {
      is ProtocolResult.Success -> encrypted.value
      is ProtocolResult.Failure -> throw ProtocolException(encrypted.fault)
    }
  }
}

class RecoveredSdkProfile internal constructor(
  val reference: SdkReferenceNumber,
  private val servers: Map<DirectoryServer, DirectoryServerConfiguration>,
) {
  fun configuration(server: DirectoryServer): DirectoryServerConfiguration = servers.getValue(server)

  companion object {
    fun parse(snapshot: String, expectedPackage: SdkPackageName): ProtocolResult<RecoveredSdkProfile> = protocolBoundary {
      val record = Json.decodeFromString<SnapshotRecord>(snapshot)
      require(record.sourcePackage == expectedPackage.value && record.sourceVersionCode > 0)
      try {
        LocalDate.parse(record.capturedOn)
      } catch (e: DateTimeParseException) {
        throw ProtocolException(ProtocolFault.MalformedInput)
      }
      val servers = parseDirectoryServers(record.directoryServers)
      RecoveredSdkProfile(SdkReferenceNumber(record.sdkReferenceNumber), servers)
    }
  }
}

internal fun certificate(encoded: String): X509Certificate {
  val bytes = Base64.getDecoder().decode(encoded)
  val certificate = try {
    CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
  } catch (e: java.security.cert.CertificateException) {
    throw ProtocolException(ProtocolFault.UntrustedCertificate)
  }
  require(certificate.encoded.contentEquals(bytes))
  return certificate
}

@Serializable private data class SnapshotRecord(
  val sdkReferenceNumber: String,
  val sourcePackage: String,
  val sourceVersionCode: Int,
  val capturedOn: String,
  val directoryServers: List<DirectoryServerRecord>,
)

internal fun parseDirectoryServers(records: List<DirectoryServerRecord>): Map<DirectoryServer, DirectoryServerConfiguration> {
  require(records.size == DirectoryServer.entries.size)
  require(records.map { it.schemeName }.toSet() == DirectoryServer.entries.toSet())
  return records.associate { entry ->
    require(entry.schemeIds == listOf(entry.schemeName.id))
    require(entry.rootCertificates.isNotEmpty())
    val encryption = certificate(entry.encryptionCertificate)
    val key = encryption.publicKey
    if (key !is RSAPublicKey || key.modulus.bitLength() < 2048 || encryption.basicConstraints >= 0) {
      throw ProtocolException(ProtocolFault.UnsupportedProfile)
    }
    val usage = encryption.keyUsage
    if (usage != null && (usage.size < 3 || !usage[2])) {
      throw ProtocolException(ProtocolFault.UnsupportedProfile)
    }
    val rootCertificates = entry.rootCertificates.map { encoded ->
      val root = certificate(encoded)
      if (root.basicConstraints < 0) throw ProtocolException(ProtocolFault.UntrustedCertificate)
      root
    }
    require(rootCertificates.distinct().size == rootCertificates.size)
    val roots = rootCertificates.map { TrustAnchor(it, null) }.toSet()
    entry.schemeName to DirectoryServerConfiguration(encryption, key, roots)
  }
}

@Serializable internal data class DirectoryServerRecord(
  val schemeName: DirectoryServer,
  val schemeIds: List<String>,
  val encryptionCertificate: String,
  val rootCertificates: List<String>,
)
