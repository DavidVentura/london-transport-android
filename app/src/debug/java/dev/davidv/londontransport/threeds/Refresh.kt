package dev.davidv.londontransport.threeds

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.RSASSAVerifier
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@JvmInline @Serializable value class SdkPackageName(val value: String) {
  init { require(value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) }
}

@JvmInline @Serializable value class SdkVersion(val value: String) {
  init { require(value.matches(Regex("[0-9]+(?:\\.[0-9]+){2,3}"))) }
}

class RefreshApiKey private constructor(internal val value: String) {
  override fun toString(): String = "<redacted API key>"
  companion object {
    fun parse(value: String): ProtocolResult<RefreshApiKey> = protocolBoundary {
      require(UUID.fromString(value).toString() == value)
      RefreshApiKey(value)
    }
  }
}

data class RefreshIdentity(val packageName: SdkPackageName, val appId: SdkAppId, val sdkVersion: SdkVersion, val androidApi: Int) {
  init { require(androidApi >= 26) }
}

enum class RefreshFault { InvalidResponse, InvalidLicense, ExpiredLicense, InvalidCertificates, Transport, HttpRejected, CacheRead, CacheWrite, ClockRegression }
sealed interface RefreshResult<out T> {
  data class Success<T>(val value: T) : RefreshResult<T>
  data class Failure(val fault: RefreshFault) : RefreshResult<Nothing>
}
internal class RefreshException(val fault: RefreshFault) : RuntimeException()

@Serializable internal data class RefreshResponseRecord(
  val contentHash: String,
  val requestFrequencyDays: Int,
  val license: LicenseRecord,
  val dsConfiguration: List<RefreshSchemeRecord>,
)
@Serializable internal data class LicenseRecord(val licenseKey: String, val isValid: Boolean) {
  override fun toString(): String = "LicenseRecord(isValid=$isValid, licenseKey=<redacted>)"
}
@Serializable internal data class LicenseClaims(
  val version: Int, val module: String, val apiKey: String, val appId: SdkPackageName, val validUntil: String,
) {
  override fun toString(): String = "<redacted licence claims>"
}
@Serializable internal enum class RefreshScheme(val id: String, val server: DirectoryServer?) {
  @kotlinx.serialization.SerialName("visa") Visa("A000000003", DirectoryServer.Visa),
  @kotlinx.serialization.SerialName("mastercard") Mastercard("A000000004", DirectoryServer.Mastercard),
  @kotlinx.serialization.SerialName("amex") Amex("A000000025", DirectoryServer.Amex),
  @kotlinx.serialization.SerialName("cartesBancaires") CartesBancaires("A000000042", null),
  @kotlinx.serialization.SerialName("diners") Diners("A000000152", null),
  @kotlinx.serialization.SerialName("eftpos") Eftpos("A000000384", null),
  @kotlinx.serialization.SerialName("jcb") Jcb("A000000065", null),
  @kotlinx.serialization.SerialName("union") Union("A000000333", null),
}
@Serializable internal data class RefreshSchemeRecord(
  val schemeName: RefreshScheme, val schemeIds: List<String>,
  val encryptionCertificate: String, val rootCertificates: List<String>,
)
@Serializable internal data class CachedRefreshRecord(val fetchedAt: Long, val response: RefreshResponseRecord)

class RefreshedConfiguration internal constructor(
  private val servers: Map<DirectoryServer, DirectoryServerConfiguration>,
  val nextRefresh: Instant,
  internal val cache: CachedRefreshRecord,
) {
  fun configuration(server: DirectoryServer): DirectoryServerConfiguration = servers.getValue(server)
  override fun toString(): String = "RefreshedConfiguration(nextRefresh=$nextRefresh)"
}

class RefreshValidator(
  private val licenseKey: RSAPublicKey,
  private val expectedPackage: SdkPackageName,
  private val apiKey: RefreshApiKey,
  private val licenseZone: ZoneId,
) {
  init { require(licenseKey.modulus.bitLength() >= 2048) }

  internal fun validate(cached: CachedRefreshRecord, now: Instant): RefreshedConfiguration {
    if (cached.fetchedAt > now.toEpochMilli()) throw RefreshException(RefreshFault.ClockRegression)
    val record = cached.response
    require(record.contentHash.isNotBlank() && record.contentHash.length <= 1024)
    require(record.requestFrequencyDays >= 0)
    if (!record.license.isValid) throw RefreshException(RefreshFault.InvalidLicense)
    val licenseDeadline = try {
      val signed = JWSObject.parse(record.license.licenseKey)
      if (signed.header.algorithm != JWSAlgorithm.RS256 || !signed.header.criticalParams.isNullOrEmpty() ||
        !signed.header.isBase64URLEncodePayload || !signed.verify(RSASSAVerifier(licenseKey))) {
        throw RefreshException(RefreshFault.InvalidLicense)
      }
      val claims = Json.decodeFromString<LicenseClaims>(signed.payload.toString())
      if (claims.version != 2 || claims.module != "3DS" || claims.appId != expectedPackage || claims.apiKey != apiKey.value) {
        throw RefreshException(RefreshFault.InvalidLicense)
      }
      val deadline = LocalDate.parse(claims.validUntil).atStartOfDay(licenseZone).toInstant()
      if (now.isAfter(deadline)) throw RefreshException(RefreshFault.ExpiredLicense)
      deadline
    } catch (e: RefreshException) {
      throw e
    } catch (e: Exception) {
      if (e !is java.text.ParseException && e !is com.nimbusds.jose.JOSEException &&
        e !is kotlinx.serialization.SerializationException && e !is java.time.format.DateTimeParseException &&
        e !is IllegalArgumentException) throw e
      throw RefreshException(RefreshFault.InvalidLicense)
    }
    require(record.dsConfiguration.isNotEmpty())
    require(record.dsConfiguration.map { it.schemeName }.distinct().size == record.dsConfiguration.size)
    require(record.dsConfiguration.flatMap { it.schemeIds }.distinct().size == record.dsConfiguration.sumOf { it.schemeIds.size })
    val selected = record.dsConfiguration.mapNotNull { entry ->
      require(entry.schemeIds == listOf(entry.schemeName.id))
      require(entry.rootCertificates.isNotEmpty())
      val leaf = certificate(entry.encryptionCertificate)
      if (leaf.basicConstraints >= 0) throw RefreshException(RefreshFault.InvalidCertificates)
      val roots = entry.rootCertificates.map { certificate(it) }
      if (roots.any { it.basicConstraints < 0 } || roots.distinct().size != roots.size) {
        throw RefreshException(RefreshFault.InvalidCertificates)
      }
      val server = entry.schemeName.server ?: return@mapNotNull null
      try {
        leaf.checkValidity(java.util.Date.from(now))
      } catch (e: java.security.cert.CertificateException) {
        throw RefreshException(RefreshFault.InvalidCertificates)
      }
      DirectoryServerRecord(server, entry.schemeIds, entry.encryptionCertificate, entry.rootCertificates)
    }
    val servers = parseDirectoryServers(selected)
    val scheduled = Instant.ofEpochMilli(cached.fetchedAt).plusSeconds(record.requestFrequencyDays.toLong() * 86400)
    val nextRefresh = listOf(scheduled, licenseDeadline, servers.values.minOf { it.encryptionCertificate.notAfter.toInstant() }).min()
    return RefreshedConfiguration(servers, nextRefresh, cached)
  }
}

class RefreshRequest internal constructor(internal val contentHash: String) {
  override fun toString(): String = "<redacted refresh request>"
}
sealed interface ConfigurationDownload {
  class Updated internal constructor(internal val record: RefreshResponseRecord) : ConfigurationDownload
  data object NotModified : ConfigurationDownload
}
interface ConfigurationTransport { fun fetch(request: RefreshRequest): RefreshResult<ConfigurationDownload> }
interface ConfigurationCache {
  fun read(): RefreshResult<String?>
  fun write(validated: RefreshedConfiguration): RefreshResult<Unit>
}

class ConfigurationRefresher(
  private val validator: RefreshValidator,
  private val transport: ConfigurationTransport,
  private val cache: ConfigurationCache,
) {
  @Synchronized fun refresh(now: Instant): RefreshResult<RefreshedConfiguration> {
    val saved = when (val read = cache.read()) {
      is RefreshResult.Success -> read.value
      is RefreshResult.Failure -> return read
    }
    val cached = if (saved == null) null else try {
      Json.decodeFromString<CachedRefreshRecord>(saved)
    } catch (e: kotlinx.serialization.SerializationException) {
      return RefreshResult.Failure(RefreshFault.CacheRead)
    } catch (e: IllegalArgumentException) {
      return RefreshResult.Failure(RefreshFault.CacheRead)
    }
    if (cached != null) {
      if (cached.fetchedAt > now.toEpochMilli()) return RefreshResult.Failure(RefreshFault.ClockRegression)
      val checked = when (val checked = validated(cached, Instant.ofEpochMilli(cached.fetchedAt))) {
        is RefreshResult.Failure -> return checked
        is RefreshResult.Success -> checked.value
      }
      if (now.isBefore(checked.nextRefresh)) {
        return validated(cached, now)
      }
    }
    val download = when (val fetched = transport.fetch(RefreshRequest(cached?.response?.contentHash ?: ""))) {
      is RefreshResult.Success -> fetched.value
      is RefreshResult.Failure -> return fetched
    }
    val record = when (download) {
      is ConfigurationDownload.Updated -> download.record
      ConfigurationDownload.NotModified -> cached?.response ?: return RefreshResult.Failure(RefreshFault.InvalidResponse)
    }
    val updated = CachedRefreshRecord(now.toEpochMilli(), record)
    return when (val checked = validated(updated, now)) {
      is RefreshResult.Failure -> checked
      is RefreshResult.Success -> when (val written = cache.write(checked.value)) {
        is RefreshResult.Failure -> written
        is RefreshResult.Success -> checked
      }
    }
  }

  private fun validated(record: CachedRefreshRecord, now: Instant): RefreshResult<RefreshedConfiguration> = try {
    RefreshResult.Success(validator.validate(record, now))
  } catch (e: RefreshException) {
    RefreshResult.Failure(e.fault)
  } catch (e: ProtocolException) {
    RefreshResult.Failure(RefreshFault.InvalidCertificates)
  } catch (e: IllegalArgumentException) {
    RefreshResult.Failure(RefreshFault.InvalidResponse)
  }
}

@Serializable private data class RefreshWireRequest(
  val apiKey: String, val appId: String, val appInstanceId: String, val sdkVersion: String,
  val contentHash: String, val deviceInfo: RefreshDeviceInfo,
)
@Serializable private data class RefreshDeviceInfo(val osType: String, val osVersion: String)

class HttpsConfigurationTransport private constructor(
  private val endpoint: HttpUrl, private val client: OkHttpClient,
  private val identity: RefreshIdentity, private val apiKey: RefreshApiKey,
) : ConfigurationTransport {
  override fun fetch(request: RefreshRequest): RefreshResult<ConfigurationDownload> {
    val body = RefreshWireRequest(apiKey.value, identity.packageName.value, identity.appId.value,
      identity.sdkVersion.value, request.contentHash, RefreshDeviceInfo("Android", identity.androidApi.toString()))
    val http = Request.Builder().url(endpoint).header("Accept", "application/json")
      .header("X-Request-ID", UUID.randomUUID().toString())
      .post(Json.encodeToString(body).toRequestBody("application/json".toMediaType())).build()
    return try {
      client.newCall(http).execute().use { response ->
        if (response.code == 304) {
          if (request.contentHash.isEmpty()) return RefreshResult.Failure(RefreshFault.InvalidResponse)
          return RefreshResult.Success(ConfigurationDownload.NotModified)
        }
        if (response.code !in 200..299) return RefreshResult.Failure(RefreshFault.HttpRejected)
        val contentType = response.body.contentType()
        if (contentType?.type != "application" || contentType.subtype != "json") {
          return RefreshResult.Failure(RefreshFault.InvalidResponse)
        }
        val limit = 512 * 1024L
        if (response.body.contentLength() > limit) return RefreshResult.Failure(RefreshFault.InvalidResponse)
        val source = response.body.source()
        source.request(limit + 1)
        if (source.buffer.size > limit) return RefreshResult.Failure(RefreshFault.InvalidResponse)
        val bytes = source.buffer.readByteArray()
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        RefreshResult.Success(ConfigurationDownload.Updated(Json.decodeFromString<RefreshResponseRecord>(text)))
      }
    } catch (e: IOException) {
      RefreshResult.Failure(RefreshFault.Transport)
    } catch (e: kotlinx.serialization.SerializationException) {
      RefreshResult.Failure(RefreshFault.InvalidResponse)
    } catch (e: IllegalArgumentException) {
      RefreshResult.Failure(RefreshFault.InvalidResponse)
    }
  }

  companion object {
    fun create(endpoint: HttpUrl, credentials: KeyStore, credentialPassword: CharArray, trust: RefreshTrust,
      identity: RefreshIdentity, apiKey: RefreshApiKey): ProtocolResult<HttpsConfigurationTransport> = protocolBoundary {
      require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.query == null && endpoint.fragment == null)
      require(credentials.aliases().toList().any { credentials.isKeyEntry(it) && credentials.getCertificateChain(it)?.isNotEmpty() == true })
      val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(credentials, credentialPassword) }
      val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        trust.tlsCertificates.forEachIndexed { index, certificate -> setCertificateEntry(index.toString(), certificate) }
      }
      val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
      val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
      val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, arrayOf(manager), null) }
      val client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, manager)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(java.time.Duration.ofSeconds(30)).build()
      HttpsConfigurationTransport(endpoint, client, identity, apiKey)
    }
  }
}

class RefreshTrust private constructor(val licenseVerificationKey: RSAPublicKey, internal val tlsCertificates: List<java.security.cert.X509Certificate>) {
  companion object {
    fun parse(json: String): ProtocolResult<RefreshTrust> = protocolBoundary {
      val record = Json.decodeFromString<RefreshTrustRecord>(json)
      val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(record.licenseVerificationKey))) as RSAPublicKey
      require(key.modulus.bitLength() >= 2048 && record.tlsTrustCertificates.isNotEmpty())
      val certificates = record.tlsTrustCertificates.map { certificate(it) }
      require(certificates.all { it.basicConstraints >= 0 } && certificates.distinct().size == certificates.size)
      RefreshTrust(key, certificates)
    }
  }
}
@Serializable private data class RefreshTrustRecord(val licenseVerificationKey: String, val tlsTrustCertificates: List<String>)
