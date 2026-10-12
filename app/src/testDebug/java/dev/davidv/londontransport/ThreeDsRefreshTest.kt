package dev.davidv.londontransport

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import dev.davidv.londontransport.threeds.*
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test

class ThreeDsRefreshTest {
  private val now = Instant.parse("2026-10-11T12:00:00Z")
  private val licenseKeys = rsaKeys()
  private val apiKey = protocolSuccess(RefreshApiKey.parse(UUID.randomUUID().toString()))
  private val identity = RefreshIdentity(SdkPackageName("test.sdk.profile"), SdkAppId(UUID.randomUUID().toString()), SdkVersion("0.0.0"), 36)
  private val validator = RefreshValidator(licenseKeys.public as RSAPublicKey, identity.packageName, apiKey, ZoneOffset.UTC)

  @Test fun `new authenticated responses are validated and atomically committed`() {
    val cache = MemoryCache()
    val transport = FakeTransport(response())
    val configuration = refreshed(refresher(cache, transport).refresh(now))
    assertEquals(now.plusSeconds(86400), configuration.nextRefresh)
    assertEquals(1, cache.writes)
    assertEquals("", transport.requests.single().contentHash)
    DirectoryServer.entries.forEach { server ->
      protocolSuccess(configuration.configuration(server).encrypt(device(), now))
    }
    val encoded = Json.decodeFromString<CachedRefreshRecord>(checkNotNull(cache.saved))
    assertEquals(now.toEpochMilli(), encoded.fetchedAt)
    assertEquals("new-hash", encoded.response.contentHash)
  }

  @Test fun `refresh schedule uses the verified hash and zero days always refreshes`() {
    val cache = MemoryCache()
    val transport = FakeTransport(response())
    val refresher = refresher(cache, transport)
    refreshed(refresher.refresh(now))
    refreshed(refresher.refresh(now.plusSeconds(86399)))
    assertEquals(1, transport.requests.size)
    refreshed(refresher.refresh(now.plusSeconds(86400)))
    assertEquals("new-hash", transport.requests.last().contentHash)
    assertEquals(2, transport.requests.size)
    val zero = FakeTransport(response(days = 0))
    val everyTime = refresher(MemoryCache(), zero)
    refreshed(everyTime.refresh(now))
    refreshed(everyTime.refresh(now))
    assertEquals(2, zero.requests.size)
  }

  @Test fun `signature package API key module version and server licence flag must all match`() {
    val claims = claims()
    val invalid = listOf(
      response().copy(license = LicenseRecord(signed(claims, rsaKeys()), true)),
      response().copy(license = LicenseRecord(signed(claims.copy(appId = SdkPackageName("another.package"))), true)),
      response().copy(license = LicenseRecord(signed(claims.copy(apiKey = UUID.randomUUID().toString())), true)),
      response().copy(license = LicenseRecord(signed(claims.copy(module = "OTHER")), true)),
      response().copy(license = LicenseRecord(signed(claims.copy(version = 1)), true)),
      response().copy(license = LicenseRecord(signed(claims), false)),
      response().copy(license = LicenseRecord("invalid-token", true)),
      response().copy(license = LicenseRecord(signed(claims.copy(validUntil = "not-a-date")), true)),
    )
    invalid.forEach { record ->
      val cache = MemoryCache()
      assertEquals(RefreshResult.Failure(RefreshFault.InvalidLicense), refresher(cache, FakeTransport(record)).refresh(now))
      assertEquals(0, cache.writes)
    }
  }

  @Test fun `licence and scheme certificate expiry bring the next refresh forward`() {
    val shortLicense = response(days = 365).copy(license = LicenseRecord(signed(claims().copy(validUntil = "2026-10-12")), true))
    val refreshed = refreshed(refresher(MemoryCache(), FakeTransport(shortLicense)).refresh(now))
    assertEquals(Instant.parse("2026-10-12T00:00:00Z"), refreshed.nextRefresh)
    val longLicense = refreshed(refresher(MemoryCache(), FakeTransport(response(days = 365))).refresh(now))
    assertEquals(fixtureExpiry(), longLicense.nextRefresh)
    val expiredLicense = response().copy(license = LicenseRecord(signed(claims().copy(validUntil = "2026-10-10")), true))
    assertEquals(RefreshResult.Failure(RefreshFault.ExpiredLicense), refresher(MemoryCache(), FakeTransport(expiredLicense)).refresh(now))
    assertEquals(RefreshResult.Failure(RefreshFault.InvalidCertificates), refresher(MemoryCache(), FakeTransport(response())).refresh(fixtureExpiry().plusSeconds(1)))
  }

  @Test fun `malformed records duplicate schemes and missing networks never partly update the cache`() {
    val valid = response()
    val invalid = listOf(valid.copy(dsConfiguration = valid.dsConfiguration.dropLast(1)),
      valid.copy(dsConfiguration = valid.dsConfiguration + valid.dsConfiguration.first()),
      valid.copy(dsConfiguration = valid.dsConfiguration.map { it.copy(schemeIds = listOf("wrong-id")) }),
      valid.copy(dsConfiguration = valid.dsConfiguration.map { it.copy(encryptionCertificate = "AA==") }),
      valid.copy(requestFrequencyDays = -1), valid.copy(contentHash = ""))
    invalid.forEach { record ->
      val cache = MemoryCache()
      assertTrue(refresher(cache, FakeTransport(record)).refresh(now) is RefreshResult.Failure)
      assertNull(cache.saved)
    }
  }

  @Test fun `network failures do not return stale configuration or overwrite the last valid cache`() {
    val cache = MemoryCache()
    val transport = FakeTransport(response())
    val refresher = refresher(cache, transport)
    refreshed(refresher.refresh(now))
    val saved = cache.saved
    transport.result = RefreshResult.Failure(RefreshFault.Transport)
    assertEquals(RefreshResult.Failure(RefreshFault.Transport), refresher.refresh(now.plusSeconds(86400)))
    assertEquals(saved, cache.saved)
    assertEquals(1, cache.writes)
    transport.result = RefreshResult.Success(ConfigurationDownload.Updated(response().copy(license = LicenseRecord("bad-token", true))))
    assertEquals(RefreshResult.Failure(RefreshFault.InvalidLicense), refresher.refresh(now.plusSeconds(86400)))
    assertEquals(saved, cache.saved)
  }

  @Test fun `cache failures corrupt cache and clock regression are explicit errors`() {
    val transport = FakeTransport(response())
    val corrupt = MemoryCache().apply { saved = "broken" }
    assertEquals(RefreshResult.Failure(RefreshFault.CacheRead), refresher(corrupt, transport).refresh(now))
    assertTrue(transport.requests.isEmpty())
    val unwritable = MemoryCache().apply { writeFailure = true }
    assertEquals(RefreshResult.Failure(RefreshFault.CacheWrite), refresher(unwritable, transport).refresh(now))
    assertNull(unwritable.saved)
    val future = MemoryCache().apply { saved = Json.encodeToString(CachedRefreshRecord(now.plusSeconds(1).toEpochMilli(), response())) }
    assertEquals(RefreshResult.Failure(RefreshFault.ClockRegression), refresher(future, transport).refresh(now))
  }

  @Test fun `recovered refresh trust is public only and malformed trust fails closed`() {
    val recovered = protocolSuccess(RefreshTrust.parse(resource("recovered-refresh-trust.json")))
    assertEquals(2048, recovered.licenseVerificationKey.modulus.bitLength())
    assertEquals(7, recovered.tlsCertificates.size)
    assertEquals(ProtocolResult.Failure(ProtocolFault.MalformedInput), RefreshTrust.parse("{}"))
    assertFalse(apiKey.toString().contains(apiKey.value))
    assertFalse(response().license.toString().contains(response().license.licenseKey))
  }

  @Test fun `local HTTPS requires a client certificate and validates the complete refresh path`() {
    tlsFixture().use { fixture ->
      val transport = protocolSuccess(HttpsConfigurationTransport.create(fixture.server.url("/sdk-data"), fixture.clientKeys, fixture.password, fixture.trust, identity, apiKey))
      fixture.server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Json.encodeToString(response())))
      val cache = MemoryCache()
      refreshed(refresher(cache, transport).refresh(now))
      val request = fixture.server.takeRequest()
      assertEquals("POST", request.method)
      assertEquals("/sdk-data", request.path)
      val body = Json.decodeFromString<ObservedRequest>(request.body.readUtf8())
      assertEquals(identity.packageName.value, body.appId)
      assertEquals(identity.appId.value, body.appInstanceId)
      assertEquals("0.0.0", body.sdkVersion)
      assertEquals(apiKey.value, body.apiKey)
      assertEquals("Android", body.deviceInfo.osType)
      assertEquals("36", body.deviceInfo.osVersion)
      assertEquals("", body.contentHash)
      assertNotNull(UUID.fromString(request.getHeader("X-Request-ID")))
      assertEquals("CN=Local client", (request.handshake!!.peerCertificates.first() as java.security.cert.X509Certificate).subjectX500Principal.name)
      assertEquals(1, cache.writes)
      fixture.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", fixture.server.url("/redirect")))
      assertEquals(RefreshResult.Failure(RefreshFault.HttpRejected), transport.fetch(RefreshRequest("new-hash")))
      assertEquals(2, fixture.server.requestCount)
    }
  }

  @Test fun `TLS rejects unknown server roots unauthorized client certificates and missing credentials`() {
    tlsFixture().use { fixture ->
      val keys = rsaKeys()
      val root = tlsCertificate(keys, keys, "CN=Untrusted root", "CN=Untrusted root", true)
      val untrusted = protocolSuccess(RefreshTrust.parse(Json.encodeToString(TrustFixture(
        Base64.getEncoder().encodeToString(licenseKeys.public.encoded), listOf(Base64.getEncoder().encodeToString(root.encoded)),
      ))))
      val transport = protocolSuccess(HttpsConfigurationTransport.create(fixture.server.url("/sdk-data"), fixture.clientKeys, fixture.password, untrusted, identity, apiKey))
      assertEquals(RefreshResult.Failure(RefreshFault.Transport), transport.fetch(RefreshRequest("")))
      val clientStore = KeyStore.getInstance("PKCS12").apply {
        load(null, fixture.password)
        val certificate = tlsCertificate(keys, keys, "CN=Unauthorized client", "CN=Unauthorized client", false)
        setKeyEntry("client", keys.private, fixture.password, arrayOf(certificate))
      }
      val unauthorized = protocolSuccess(HttpsConfigurationTransport.create(fixture.server.url("/sdk-data"), clientStore, fixture.password, fixture.trust, identity, apiKey))
      assertEquals(RefreshResult.Failure(RefreshFault.Transport), unauthorized.fetch(RefreshRequest("")))
      assertEquals(0, fixture.server.requestCount)
      val empty = KeyStore.getInstance("PKCS12").apply { load(null, fixture.password) }
      assertEquals(ProtocolResult.Failure(ProtocolFault.MalformedInput), HttpsConfigurationTransport.create(fixture.server.url("/sdk-data"), empty, fixture.password, fixture.trust, identity, apiKey))
    }
  }

  @Test fun `refresh transport rejects wrong content types and oversized responses`() {
    tlsFixture().use { fixture ->
      val transport = protocolSuccess(HttpsConfigurationTransport.create(fixture.server.url("/sdk-data"), fixture.clientKeys, fixture.password, fixture.trust, identity, apiKey))
      fixture.server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("bad response"))
      assertEquals(RefreshResult.Failure(RefreshFault.InvalidResponse), transport.fetch(RefreshRequest("")))
      fixture.server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setChunkedBody("x".repeat(512 * 1024 + 1), 8192))
      assertEquals(RefreshResult.Failure(RefreshFault.InvalidResponse), transport.fetch(RefreshRequest("")))
      fixture.server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
      assertEquals(RefreshResult.Failure(RefreshFault.InvalidResponse), transport.fetch(RefreshRequest("")))
    }
  }

  @Test fun `not modified responses advance the schedule only for still usable verified cached configuration`() {
    val cache = MemoryCache()
    val transport = FakeTransport(response())
    val refresher = refresher(cache, transport)
    refreshed(refresher.refresh(now))
    transport.result = RefreshResult.Success(ConfigurationDownload.NotModified)
    val updated = refreshed(refresher.refresh(now.plusSeconds(86400)))
    assertEquals(now.plusSeconds(172800), updated.nextRefresh)
    assertEquals(2, cache.writes)
    val saved = cache.saved
    assertEquals(RefreshResult.Failure(RefreshFault.InvalidCertificates), refresher.refresh(fixtureExpiry().plusSeconds(1)))
    assertEquals(saved, cache.saved)
    assertEquals(RefreshResult.Failure(RefreshFault.InvalidResponse), refresher(MemoryCache(), transport).refresh(now))
  }

  @Test fun `HTTPS 304 requires an existing hash and has a distinct parsed outcome`() {
    tlsFixture().use { fixture ->
      val transport = protocolSuccess(HttpsConfigurationTransport.create(fixture.server.url("/sdk-data"), fixture.clientKeys, fixture.password, fixture.trust, identity, apiKey))
      fixture.server.enqueue(MockResponse().setResponseCode(304))
      assertEquals(RefreshResult.Success(ConfigurationDownload.NotModified), transport.fetch(RefreshRequest("known-hash")))
      fixture.server.enqueue(MockResponse().setResponseCode(304))
      assertEquals(RefreshResult.Failure(RefreshFault.InvalidResponse), transport.fetch(RefreshRequest("")))
    }
  }

  @Test fun `encrypted cache uses fresh nonces and never stores plaintext credentials`() {
    val storage = MemoryStorage()
    val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val cache = EncryptedConfigurationCache(storage, key)
    assertEquals(RefreshResult.Success(null), cache.read())
    val configuration = refreshed(refresher(MemoryCache(), FakeTransport(response())).refresh(now))
    assertEquals(RefreshResult.Success(Unit), cache.write(configuration))
    val first = checkNotNull(storage.bytes).copyOf()
    assertFalse(String(first, Charsets.ISO_8859_1).contains(apiKey.value))
    assertFalse(String(first, Charsets.ISO_8859_1).contains(configuration.cache.response.license.licenseKey))
    val record = Json.decodeFromString<CachedRefreshRecord>(checkNotNull(refreshed(cache.read())))
    assertEquals(configuration.cache, record)
    assertEquals(RefreshResult.Success(Unit), cache.write(configuration))
    assertFalse(first.contentEquals(checkNotNull(storage.bytes)))
  }

  @Test fun `cache authentication rejects tampering wrong keys and unknown formats`() {
    val storage = MemoryStorage()
    val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val cache = EncryptedConfigurationCache(storage, key)
    val configuration = refreshed(refresher(MemoryCache(), FakeTransport(response())).refresh(now))
    refreshed(cache.write(configuration))
    val valid = checkNotNull(storage.bytes).copyOf()
    storage.bytes = valid.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
    assertEquals(RefreshResult.Failure(RefreshFault.CacheRead), cache.read())
    storage.bytes = valid.copyOf().apply { this[0] = 2 }
    assertEquals(RefreshResult.Failure(RefreshFault.CacheRead), cache.read())
    storage.bytes = byteArrayOf(1)
    assertEquals(RefreshResult.Failure(RefreshFault.CacheRead), cache.read())
    storage.bytes = valid
    val wrongKey = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    assertEquals(RefreshResult.Failure(RefreshFault.CacheRead), EncryptedConfigurationCache(storage, wrongKey).read())
    storage.failWrites = true
    assertEquals(RefreshResult.Failure(RefreshFault.CacheWrite), cache.write(configuration))
    assertArrayEquals(valid, storage.bytes)
    storage.failReads = true
    assertEquals(RefreshResult.Failure(RefreshFault.CacheRead), cache.read())
  }

  @Test fun `full offline preparation refreshes over mutual TLS collects and encrypts using the recovered Visa key`() {
    tlsFixture().use { fixture ->
      fixture.server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Json.encodeToString(response())))
      val transport = protocolSuccess(HttpsConfigurationTransport.create(fixture.server.url("/sdk-data"), fixture.clientKeys, fixture.password, fixture.trust, identity, apiKey))
      val collector = object : DeviceInformationSource {
        override fun collect(): ProtocolResult<DeviceInformation> = ProtocolResult.Success(device())
      }
      val reference = SdkReferenceNumber("test-sdk-reference")
      val prototype = AuthenticationPrototype(identity.appId, reference, identity.sdkVersion, collector, refresher(MemoryCache(), transport))
      val result = prototype.prepare(DirectoryServer.Visa, now) as PreparationResult.Success
      val fields = result.authentication.parameters.formFields()
      assertEquals(7, fields.size)
      assertEquals(identity.appId.value, fields.getValue("sdkAppID"))
      assertEquals(reference.value, fields.getValue("sdkReferenceNumber"))
      assertEquals(result.authentication.transaction.id.value, fields.getValue("sdkTransID"))
      assertEquals(result.authentication.transaction.publicKey, Json.decodeFromString<PublicJwk>(fields.getValue("sdkEphemPubKey")))
      val jwe = com.nimbusds.jose.JWEObject.parse(fields.getValue("sdkEncData"))
      assertEquals(com.nimbusds.jose.JWEAlgorithm.RSA_OAEP_256, jwe.header.algorithm)
      assertEquals(com.nimbusds.jose.EncryptionMethod.A128CBC_HS256, jwe.header.encryptionMethod)
      assertEquals(1, fixture.server.requestCount)
    }
  }

  @Test fun `preparation binds encrypted device identity for each directory server and fresh transaction`() {
    val keys = rsaKeys()
    val builder = JcaX509v3CertificateBuilder(X500Name("CN=Test DS"), BigInteger.ONE,
      Date.from(now.minusSeconds(86400)), Date.from(now.plusSeconds(86400)), X500Name("CN=Test DS"), keys.public)
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
    builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyEncipherment))
    val certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)))
    val original = response()
    val configuration = original.copy(dsConfiguration = original.dsConfiguration.map {
      it.copy(encryptionCertificate = Base64.getEncoder().encodeToString(certificate.encoded))
    })
    val stale = device().copy(data = device().data.copy(
      sdkAppId = UUID.randomUUID().toString(), sdkVersion = SdkVersion("0.0.0"), sdkReferenceNumber = SdkReferenceNumber("stale"),
    ))
    val collector = object : DeviceInformationSource {
      override fun collect(): ProtocolResult<DeviceInformation> = ProtocolResult.Success(stale)
    }
    val reference = SdkReferenceNumber("test-sdk-reference")
    val prototype = AuthenticationPrototype(identity.appId, reference, identity.sdkVersion, collector,
      refresher(MemoryCache(), FakeTransport(configuration)))
    val authentications = DirectoryServer.entries.map { server ->
      val prepared = (prototype.prepare(server, now) as PreparationResult.Success).authentication
      val jwe = com.nimbusds.jose.JWEObject.parse(prepared.parameters.encryptedDeviceData)
      jwe.decrypt(com.nimbusds.jose.crypto.RSADecrypter(keys.private))
      val decoded = Json.decodeFromString<DeviceInformation>(jwe.payload.toString())
      assertEquals(identity.appId.value, decoded.data.sdkAppId)
      assertEquals(identity.sdkVersion, decoded.data.sdkVersion)
      assertEquals(reference, decoded.data.sdkReferenceNumber)
      assertEquals(device().data.model, decoded.data.model)
      assertTrue(jwe.payload.toString().contains("\"C015\":\"${identity.sdkVersion.value}\""))
      assertTrue(jwe.payload.toString().contains("\"A234\":\"${reference.value}\""))
      assertEquals(reference.value, prepared.parameters.formFields().getValue("sdkReferenceNumber"))
      prepared
    }
    assertEquals(DirectoryServer.entries.size, authentications.map { it.transaction.id }.toSet().size)
    assertEquals(DirectoryServer.entries.size, authentications.map { it.transaction.publicKey }.toSet().size)
    assertEquals(SdkVersion("0.0.0"), stale.data.sdkVersion)
    assertEquals(SdkReferenceNumber("stale"), stale.data.sdkReferenceNumber)
  }

  private fun fixtureExpiry(): Instant {
    val factory = java.security.cert.CertificateFactory.getInstance("X.509")
    return response().dsConfiguration.minOf {
      val bytes = Base64.getDecoder().decode(it.encryptionCertificate)
      val certificate = factory.generateCertificate(bytes.inputStream()) as java.security.cert.X509Certificate
      certificate.notAfter.toInstant()
    }
  }

  private fun response(days: Int = 1): RefreshResponseRecord {
    val snapshot = Json.decodeFromString<SnapshotFixture>(resource("recovered-directory-server-config.json"))
    return RefreshResponseRecord("new-hash", days, LicenseRecord(signed(claims()), true), snapshot.directoryServers.map { entry ->
      RefreshSchemeRecord(RefreshScheme.entries.single { it.server == entry.schemeName }, entry.schemeIds, entry.encryptionCertificate, entry.rootCertificates)
    })
  }
  private fun claims(): LicenseClaims = LicenseClaims(2, "3DS", apiKey.value, identity.packageName, "2028-08-31")
  private fun signed(claims: LicenseClaims, keys: KeyPair = licenseKeys): String {
    val jws = JWSObject(JWSHeader(JWSAlgorithm.RS256), Payload(Json.encodeToString(claims)))
    jws.sign(RSASSASigner(keys.private))
    return jws.serialize()
  }
  private fun refresher(cache: ConfigurationCache, transport: ConfigurationTransport): ConfigurationRefresher =
    ConfigurationRefresher(validator, transport, cache)

  private fun resource(name: String): String {
    val stream = checkNotNull(javaClass.getResourceAsStream("/threeds/$name"))
    return stream.bufferedReader().use { it.readText() }
  }
  private fun device(): DeviceInformation = DeviceInformation(DeviceDataVersion.V15, DeviceData("Android", "test device", "Android BAKLAVA 16 API 36", "16", "en-GB", "0"))
  private fun <T> protocolSuccess(result: ProtocolResult<T>): T = when (result) {
    is ProtocolResult.Success -> result.value
    is ProtocolResult.Failure -> throw AssertionError(result.fault)
  }
  private fun <T> refreshed(result: RefreshResult<T>): T = when (result) {
    is RefreshResult.Success -> result.value
    is RefreshResult.Failure -> throw AssertionError(result.fault)
  }

  private fun tlsFixture(): TlsFixture {
    val rootKeys = rsaKeys()
    val serverKeys = rsaKeys()
    val clientKeys = rsaKeys()
    val root = tlsCertificate(rootKeys, rootKeys, "CN=Local root", "CN=Local root", true)
    val serverCertificate = tlsCertificate(serverKeys, rootKeys, "CN=localhost", "CN=Local root", false)
    val clientCertificate = tlsCertificate(clientKeys, rootKeys, "CN=Local client", "CN=Local root", false)
    val password = UUID.randomUUID().toString().toCharArray()
    val serverStore = KeyStore.getInstance("PKCS12").apply {
      load(null, password)
      setKeyEntry("server", serverKeys.private, password, arrayOf(serverCertificate, root))
    }
    val clientStore = KeyStore.getInstance("PKCS12").apply {
      load(null, password)
      setKeyEntry("client", clientKeys.private, password, arrayOf(clientCertificate, root))
    }
    val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("root", root) }
    val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(serverStore, password) }
    val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, trustManagers.trustManagers, null) }
    val server = MockWebServer().apply { useHttps(context.socketFactory, false); requireClientAuth(); start() }
    val trust = protocolSuccess(RefreshTrust.parse(Json.encodeToString(TrustFixture(
      Base64.getEncoder().encodeToString(licenseKeys.public.encoded), listOf(Base64.getEncoder().encodeToString(root.encoded)),
    ))))
    return TlsFixture(server, clientStore, password, trust)
  }

  private fun tlsCertificate(keys: KeyPair, signer: KeyPair, subject: String, issuer: String, root: Boolean): java.security.cert.X509Certificate {
    val time = Instant.now()
    val builder = JcaX509v3CertificateBuilder(X500Name(issuer), BigInteger(128, java.security.SecureRandom()),
      Date.from(time.minusSeconds(3600)), Date.from(time.plusSeconds(3600)), X500Name(subject), keys.public)
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(root))
    builder.addExtension(Extension.keyUsage, true, KeyUsage(if (root) KeyUsage.keyCertSign else KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
    if (!root) builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, "localhost")))
    return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(signer.private)))
  }
  private fun rsaKeys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}

private class MemoryCache : ConfigurationCache {
  var saved: String? = null
  var writes = 0
  var writeFailure = false
  override fun read(): RefreshResult<String?> = RefreshResult.Success(saved)
  override fun write(validated: RefreshedConfiguration): RefreshResult<Unit> {
    if (writeFailure) return RefreshResult.Failure(RefreshFault.CacheWrite)
    saved = Json.encodeToString(validated.cache)
    writes += 1
    return RefreshResult.Success(Unit)
  }
}
private class MemoryStorage : ConfigurationStorage {
  var bytes: ByteArray? = null
  var failWrites = false
  var failReads = false
  override fun read(): ByteArray? {
    if (failReads) throw java.io.IOException()
    return bytes?.copyOf()
  }
  override fun write(bytes: ByteArray) {
    if (failWrites) throw java.io.IOException()
    this.bytes = bytes.copyOf()
  }
}
private class FakeTransport(response: RefreshResponseRecord) : ConfigurationTransport {
  var result: RefreshResult<ConfigurationDownload> = RefreshResult.Success(ConfigurationDownload.Updated(response))
  val requests = mutableListOf<RefreshRequest>()
  override fun fetch(request: RefreshRequest): RefreshResult<ConfigurationDownload> {
    requests.add(request)
    return result
  }
}
private class TlsFixture(val server: MockWebServer, val clientKeys: KeyStore, val password: CharArray, val trust: RefreshTrust) : java.io.Closeable {
  override fun close() { server.shutdown(); password.fill('\u0000') }
}
@Serializable private data class SnapshotFixture(val sdkReferenceNumber: String, val sourcePackage: String, val sourceVersionCode: Int, val capturedOn: String, val directoryServers: List<DirectoryServerRecord>)
@Serializable private data class TrustFixture(val licenseVerificationKey: String, val tlsTrustCertificates: List<String>)
@Serializable private data class ObservedRequest(val apiKey: String, val appId: String, val appInstanceId: String, val sdkVersion: String, val contentHash: String, val deviceInfo: ObservedDeviceInfo)
@Serializable private data class ObservedDeviceInfo(val osType: String, val osVersion: String)
