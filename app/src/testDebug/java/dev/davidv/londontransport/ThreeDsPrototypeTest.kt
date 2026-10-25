package dev.davidv.londontransport

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.DirectDecrypter
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.crypto.impl.AAD
import com.nimbusds.jose.crypto.impl.AESCBC
import com.nimbusds.jose.crypto.impl.ConcatKDF
import com.nimbusds.jose.crypto.impl.ECDH
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.util.Base64
import com.nimbusds.jose.util.Base64URL
import dev.davidv.londontransport.threeds.*
import dev.davidv.londontransport.local.ThreeDsLocalConstants
import dev.davidv.londontransport.santander.*
import java.io.Closeable
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

class ThreeDsPrototypeTest {
  private val now = Instant.now()
  private val reference = SdkReferenceNumber("test-sdk-reference")
  private val appId = SdkAppId("ac2d9673-ed8d-439f-a9ba-9629e203966a")
  private val device = DeviceInformation(
    DeviceDataVersion.V15, DeviceData("Android", "Example Offline test device", "Android BAKLAVA 16 API 36", "16", "en-GB", "0"),
    mapOf(DeviceParameter.Screen to UnavailableReason.Unsupported),
    listOf(SecurityWarning.Emulator),
  )

  @Test
  fun `recovered certificates encrypt with the upstream reference and profile`() {
    val profile = success(RecoveredSdkProfile.parse(recoveredSnapshot(), SdkPackageName(ThreeDsLocalConstants.SDK_PACKAGE)))
    assertEquals(SdkReferenceNumber(ThreeDsLocalConstants.SDK_REFERENCE_NUMBER), profile.reference)
    val capturedAt = Instant.parse(ThreeDsLocalConstants.SDK_CAPTURED_DATE + "T12:00:00Z")
    val expected = checkNotNull(javaClass.getResourceAsStream("/threeds/certificate-expectations.json")).bufferedReader().use {
      Json.decodeFromString<List<CertificateExpectation>>(it.readText())
    }
    val transaction = SdkTransaction.create(appId, profile.reference)
    expected.forEach { value ->
      val configuration = profile.configuration(value.schemeName)
      assertEquals(value.rootCount, configuration.challengeRoots.size)
      val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
        .digest(configuration.encryptionCertificate.encoded).joinToString("") { "%02x".format(it) }
      assertEquals(value.fingerprint, fingerprint)
      val ciphertext = success(configuration.encrypt(device, capturedAt))
      val encrypted = JWEObject.parse(ciphertext)
      assertEquals(JWEAlgorithm.RSA_OAEP_256, encrypted.header.algorithm)
      assertEquals(EncryptionMethod.A128CBC_HS256, encrypted.header.encryptionMethod)
      assertNull(encrypted.header.keyID)
      assertEquals(setOf("alg", "enc"), encrypted.header.toJSONObject().keys)
      val fields = AuthenticationParameters(
        transaction.appId, transaction.id, transaction.reference, transaction.publicKey, ciphertext,
      ).formFields()
      assertEquals(profile.reference.value, fields.getValue("sdkReferenceNumber"))
      assertEquals(7, fields.size)
      assertNotEquals(ciphertext, success(configuration.encrypt(device, capturedAt)))
    }
  }

  @Test
  fun `encryption checks certificate lifetime for every call without substituting another network`() {
    val profile = success(RecoveredSdkProfile.parse(recoveredSnapshot(), SdkPackageName(ThreeDsLocalConstants.SDK_PACKAGE)))
    DirectoryServer.entries.forEach { server ->
      val configuration = profile.configuration(server)
      val validFrom = configuration.encryptionCertificate.notBefore.toInstant()
      val validUntil = configuration.encryptionCertificate.notAfter.toInstant()
      assertFault(ProtocolFault.ConfigurationNotYetValid, configuration.encrypt(device, validFrom.minusSeconds(1)))
      success(configuration.encrypt(device, validFrom))
      success(configuration.encrypt(device, validUntil))
      assertFault(ProtocolFault.ExpiredConfiguration, configuration.encrypt(device, validUntil.plusSeconds(1)))
    }
    val afterVisaExpires = profile.configuration(DirectoryServer.Visa).encryptionCertificate.notAfter.toInstant().plusSeconds(1)
    assertFault(ProtocolFault.ExpiredConfiguration, profile.configuration(DirectoryServer.Visa).encrypt(device, afterVisaExpires))
  }

  @Test
  fun `malformed configuration invalidates the entire snapshot`() {
    val snapshot = recoveredSnapshot()
    listOf(
      snapshot.replace("A000000003", "A000000004"),
      snapshot.replace("\"visa\"", "\"mastercard\""),
      snapshot.replace(ThreeDsLocalConstants.SDK_PACKAGE, "incorrect.package"),
      snapshot.replace(ThreeDsLocalConstants.SDK_CAPTURED_DATE, "invalid-date"),
      snapshot.replace(ThreeDsLocalConstants.SDK_REFERENCE_NUMBER, ""),
      "{}",
    ).forEach { malformed ->
      assertFault(ProtocolFault.MalformedInput, RecoveredSdkProfile.parse(malformed, SdkPackageName(ThreeDsLocalConstants.SDK_PACKAGE)))
    }
    val invalidCertificate = snapshot.replaceFirst(
      Regex("\"encryptionCertificate\":\\s*\"[^\"]+\""), "\"encryptionCertificate\": \"AA==\"",
    )
    assertFault(ProtocolFault.UntrustedCertificate, RecoveredSdkProfile.parse(invalidCertificate, SdkPackageName(ThreeDsLocalConstants.SDK_PACKAGE)))
  }

  @Test
  fun `common device fixture follows recovered formats and omits empty optional sections`() {
    val encoded = Json.encodeToString(device)
    assertTrue(encoded.contains("\"DV\":\"1.5\""))
    assertTrue(encoded.contains("\"C003\":\"Android BAKLAVA 16 API 36\""))
    assertTrue(encoded.contains("\"C006\":\"0\""))
    assertTrue(encoded.contains("\"DPNA\":{\"C008\":\"RE02\"}"))
    assertFalse(encoded.contains("\"C008\":null"))
    val noWarnings = Json.encodeToString(device.copy(unavailable = emptyMap(), warnings = emptyList()))
    assertFalse(noWarnings.contains("\"DPNA\""))
    assertFalse(noWarnings.contains("\"SW\""))
    assertFault(ProtocolFault.MalformedInput, protocolBoundary {
      Json.decodeFromString<DeviceInformation>(encoded.replace("\"1.5\"", "\"1.6\""))
    })
  }

  private fun recoveredSnapshot(): String {
    val stream = checkNotNull(javaClass.getResourceAsStream("/threeds/recovered-directory-server-config.json"))
    return stream.bufferedReader().use { it.readText() }
  }

  @Test
  fun `authentication and OTP challenge complete over local HTTPS with an independent bank`() {
    SimulatedBank(now, reference).use { bank ->
      val transaction = SdkTransaction.create(appId, reference)
      val fields = authentication(transaction, bank).formFields()
      val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
      val request = Request.Builder().url(bank.server.url("/authenticate"))
        .header("c3-capabilities", "3dsenabled").post(body).build()
      val response = bank.client.newCall(request).execute().use { reply ->
        assertEquals(200, reply.code)
        Json.decodeFromString<AuthenticationReply>(reply.body.string())
      }
      val flow = success(ChallengeFlow.start(transaction, response.parameters, bank.roots, now))
      val first = success(flow.request(ChallengeAction.Start, now))
      val prompt = success(flow.receive(bank.postChallenge(first), now)) as ChallengeOutcome.OtpRequired
      assertEquals("Verification code", prompt.label)
      val answer = success(flow.request(ChallengeAction.Answer(OtpAnswer("123456")), now.plusSeconds(1)))
      assertEquals(ChallengeOutcome.Authenticated, success(flow.receive(bank.postChallenge(answer), now.plusSeconds(2))))
      assertEquals(device, bank.receivedDevice.get())
      assertEquals(transaction.publicKey, bank.receivedPublicKey.get())
      assertEquals("opaque-server-continuation", response.transData)
      assertEquals(ProtocolResult.Failure(ProtocolFault.InvalidState), flow.request(ChallengeAction.Start, now))
      bank.failure.get()?.let { throw AssertionError("simulated bank failed", it) }
    }
  }

  @Test
  fun `device encryption uses the expected profile and never includes a private key`() {
    SimulatedBank(now, reference).use { bank ->
      val first = SdkTransaction.create(appId, reference)
      val second = SdkTransaction.create(appId, reference)
      val params = authentication(first, bank)
      val encrypted = JWEObject.parse(params.encryptedDeviceData)
      assertEquals(JWEAlgorithm.RSA_OAEP_256, encrypted.header.algorithm)
      assertEquals(EncryptionMethod.A128CBC_HS256, encrypted.header.encryptionMethod)
      encrypted.decrypt(RSADecrypter(bank.directoryKey.private))
      assertEquals(device, Json.decodeFromString<DeviceInformation>(encrypted.payload.toString()))
      assertFalse(ECKey.parse(params.formFields().getValue("sdkEphemPubKey")).isPrivate)
      assertNotEquals(first.id, second.id)
      assertNotEquals(first.publicKey, second.publicKey)
      assertEquals("05", params.formFields().getValue("sdkMaxTimeout"))
      assertEquals("01", params.formFields().getValue("sdkInterface"))
      assertEquals(7, params.formFields().size)
      assertNotEquals(params.encryptedDeviceData, authentication(first, bank).encryptedDeviceData)
    }
  }

  @Test fun `runtime dispatches OTP and frictionless paths and consumes each submission and continuation once`() = runBlocking {
    listOf("/payment-authenticate", "/payment-frictionless").forEach { path ->
      SimulatedBank(now, reference).use { bank ->
        val transaction = SdkTransaction.create(appId, reference)
        val context = PaymentRequestContext(bank.server.url("/"), DeviceId("offline-device"),
          Credentials(UserNodeId("offline-user"), AuthToken("offline-token")))
        val core = buildCoreRequest(bank.server.url(path), context.deviceId, context.credentials,
          mapOf("member_card_number" to "4111111111111111", "member_card_cvv" to "123"), now)
        val prepared = success(PreparedCardSubmission.create(core,
          PreparedAuthentication(transaction, authentication(transaction, bank), bank.roots), context))
        val runtime = PaymentRuntime(bank.client, Clock.fixed(now, ZoneOffset.UTC))
        val handoff = (runtime.submit(prepared) as PaymentRuntimeResult.Success).value
        assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.AlreadySent), runtime.submit(prepared))
        val continuation = when (handoff) {
          is RuntimePaymentStep.Continue -> handoff.pending
          is RuntimePaymentStep.Challenge -> {
            val prompt = (handoff.session.send(ChallengeAction.Start) as PaymentRuntimeResult.Success).value as RuntimePaymentStep.Prompt
            assertEquals("Verification code", prompt.prompt.label)
            val completed = (handoff.session.send(ChallengeAction.Answer(OtpAnswer("123456"))) as PaymentRuntimeResult.Success).value as RuntimePaymentStep.Completed
            assertEquals(PaymentChallengeCompletion.Authenticated, completed.outcome)
            assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.Protocol(ProtocolFault.InvalidState)),
              handoff.session.send(ChallengeAction.Answer(OtpAnswer("123456"))))
            completed.pending
          }
          else -> error("Unexpected runtime handoff")
        }
        val reply = (runtime.continuePayment(continuation) as PaymentRuntimeResult.Success).value as NodeReply.Nodes
        assertEquals("offline continuation accepted", simpleResponse(reply.nodes))
        assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.AlreadySent), runtime.continuePayment(continuation))
        assertEquals(if (path == "/payment-authenticate") 4 else 2, bank.server.requestCount)
        bank.failure.get()?.let { throw AssertionError("simulated runtime flow failed", it) }
      }
    }
  }

  @Test fun `card submit parsed challenge encrypted OTP and server continuation complete over local HTTPS`() {
    SimulatedBank(now, reference).use { bank ->
      val transaction = SdkTransaction.create(appId, reference)
      val context = PaymentRequestContext(bank.server.url("/"), DeviceId("offline-device"), Credentials(UserNodeId("offline-user"), AuthToken("offline-token")))
      val core = buildCoreRequest(bank.server.url("/payment-authenticate"), context.deviceId, context.credentials,
        mapOf("member_card_number" to "4111111111111111", "member_card_cvv" to "123"), now)
      val request = success(buildCardSubmissionRequest(core, authentication(transaction, bank)))
      val response = bank.client.newCall(request).execute().use { reply ->
        assertEquals(200, reply.code)
        success(parsePaymentAuthentication(reply.body.string()))
      }
      val handoff = success(preparePaymentHandoff(response, transaction, bank.roots, context, now)) as PaymentHandoff.Challenge
      val session = handoff.session
      assertEquals(bank.server.url("/challenge").toString(), session.acsUrl)
      val start = success(session.request(ChallengeAction.Start, now)) as PaymentChallengeStep.SendToAcs
      val prompt = success(session.receive(bank.postChallenge(start.packet), now)) as PaymentChallengeStep.Prompt
      assertEquals("Verification code", prompt.prompt.label)
      val answer = success(session.request(ChallengeAction.Answer(OtpAnswer("123456")), now.plusSeconds(1))) as PaymentChallengeStep.SendToAcs
      val completed = success(session.receive(bank.postChallenge(answer.packet), now.plusSeconds(2))) as PaymentChallengeStep.Completed
      assertEquals(PaymentChallengeCompletion.Authenticated, completed.outcome)
      assertNull(completed.request.url.queryParameter("ChallengeFailed"))
      assertEquals("a+b", completed.request.url.queryParameter("token"))
      bank.client.newCall(completed.request).execute().use { reply ->
        assertEquals(200, reply.code)
        val nodes = parseNodeReply(reply.body.string()) as NodeReply.Nodes
        assertEquals("offline continuation accepted", simpleResponse(nodes.nodes))
      }
      assertEquals(ProtocolResult.Failure(ProtocolFault.InvalidState), session.receive(bank.encrypt(bank.prompt()), now.plusSeconds(3)))
      assertEquals(ProtocolResult.Failure(ProtocolFault.InvalidState), session.transportFailed(now.plusSeconds(3)))
      assertEquals(4, bank.server.requestCount)
      bank.failure.get()?.let { throw AssertionError("simulated payment flow failed", it) }
    }
  }

  @Test fun `frictionless card submission resumes on the server without any ACS request`() {
    SimulatedBank(now, reference).use { bank ->
      val transaction = SdkTransaction.create(appId, reference)
      val context = PaymentRequestContext(bank.server.url("/"), DeviceId("offline-device"), Credentials(UserNodeId("offline-user"), AuthToken("offline-token")))
      val core = buildCoreRequest(bank.server.url("/payment-frictionless"), context.deviceId, context.credentials,
        mapOf("member_card_number" to "4111111111111111", "member_card_cvv" to "123"), now)
      val request = success(buildCardSubmissionRequest(core, authentication(transaction, bank)))
      val response = bank.client.newCall(request).execute().use { reply ->
        assertEquals(200, reply.code)
        success(parsePaymentAuthentication(reply.body.string()))
      }
      val handoff = success(preparePaymentHandoff(response, transaction, emptySet(), context, now)) as PaymentHandoff.Continue
      assertEquals(NonChallengePaymentStatus.Authenticated, handoff.status)
      assertNull(handoff.request.url.queryParameter("ChallengeFailed"))
      bank.client.newCall(handoff.request).execute().use { reply ->
        assertEquals(200, reply.code)
        assertEquals("offline continuation accepted", simpleResponse((parseNodeReply(reply.body.string()) as NodeReply.Nodes).nodes))
      }
      assertEquals(2, bank.server.requestCount)
      bank.failure.get()?.let { throw AssertionError("simulated frictionless flow failed", it) }
    }
  }

  @Test fun `issuer rejection continues normally but cancellation marks challenge failure`() {
    listOf(ChallengeAction.Answer(OtpAnswer("wrong-code")) to PaymentChallengeCompletion.Rejected,
      ChallengeAction.Cancel to PaymentChallengeCompletion.Cancelled).forEach { (action, expected) ->
      SimulatedBank(now, reference).use { bank ->
        val transaction = SdkTransaction.create(appId, reference)
        val context = PaymentRequestContext(bank.server.url("/"), DeviceId("offline-device"), Credentials(UserNodeId("offline-user"), AuthToken("offline-token")))
        val response = success(parsePaymentAuthentication(paymentAuthenticationBody(bank.challenge(transaction))))
        val session = (success(preparePaymentHandoff(response, transaction, bank.roots, context, now)) as PaymentHandoff.Challenge).session
        val start = success(session.request(ChallengeAction.Start, now)) as PaymentChallengeStep.SendToAcs
        assertTrue(success(session.receive(bank.postChallenge(start.packet), now)) is PaymentChallengeStep.Prompt)
        val packet = success(session.request(action, now.plusSeconds(1))) as PaymentChallengeStep.SendToAcs
        val completed = success(session.receive(bank.postChallenge(packet.packet), now.plusSeconds(2))) as PaymentChallengeStep.Completed
        assertEquals(expected, completed.outcome)
        assertEquals(if (expected == PaymentChallengeCompletion.Cancelled) "1" else null, completed.request.url.queryParameter("ChallengeFailed"))
        val form = ("https://localhost/?" + (completed.request.body as FormBody).let { body ->
          (0 until body.size).joinToString("&") { "${body.encodedName(it)}=${body.encodedValue(it)}" }
        }).toHttpUrl()
        assertEquals("opaque-server-continuation", form.queryParameter("transData"))
        assertEquals(ProtocolResult.Failure(ProtocolFault.InvalidState), session.request(ChallengeAction.Start, now.plusSeconds(3)))
      }
    }
  }

  @Test fun `timeout malformed bank packets and transport failures produce one explicit failure continuation`() {
    SimulatedBank(now, reference).use { bank ->
      val context = PaymentRequestContext(bank.server.url("/"), DeviceId("offline-device"), Credentials(UserNodeId("offline-user"), AuthToken("offline-token")))
      listOf(ProtocolFault.Timeout, ProtocolFault.MalformedInput, ProtocolFault.ChallengeTransport).forEach { fault ->
        val transaction = SdkTransaction.create(appId, reference)
        val response = success(parsePaymentAuthentication(paymentAuthenticationBody(bank.challenge(transaction))))
        val session = (success(preparePaymentHandoff(response, transaction, bank.roots, context, now)) as PaymentHandoff.Challenge).session
        val result = when (fault) {
          ProtocolFault.Timeout -> session.request(ChallengeAction.Start, now.plusSeconds(300))
          ProtocolFault.MalformedInput -> {
            assertTrue(success(session.request(ChallengeAction.Start, now)) is PaymentChallengeStep.SendToAcs)
            session.receive("invalid-packet", now)
          }
          ProtocolFault.ChallengeTransport -> {
            assertTrue(success(session.request(ChallengeAction.Start, now)) is PaymentChallengeStep.SendToAcs)
            session.transportFailed(now)
          }
          else -> error("Unexpected failure fixture")
        }
        val failed = success(result) as PaymentChallengeStep.Failed
        assertEquals(fault, failed.fault)
        assertEquals("1", failed.request.url.queryParameter("ChallengeFailed"))
        assertEquals("a+b", failed.request.url.queryParameter("token"))
        assertEquals(ProtocolResult.Failure(ProtocolFault.InvalidState), session.transportFailed(now.plusSeconds(301)))
      }
      assertEquals(0, bank.server.requestCount)
    }
  }

  @Test fun `invalid caller sequence stays an explicit error and signed ACS key mismatch prevents challenge handoff`() {
    SimulatedBank(now, reference).use { bank ->
      val transaction = SdkTransaction.create(appId, reference)
      val context = PaymentRequestContext(bank.server.url("/"), DeviceId("offline-device"), Credentials(UserNodeId("offline-user"), AuthToken("offline-token")))
      val response = success(parsePaymentAuthentication(paymentAuthenticationBody(bank.challenge(transaction))))
      val other = SdkTransaction.create(appId, reference)
      val failed = success(preparePaymentHandoff(response, other, bank.roots, context, now)) as PaymentHandoff.Failed
      assertEquals(ProtocolFault.KeyMismatch, failed.fault)
      assertEquals("1", failed.request.url.queryParameter("ChallengeFailed"))
      val session = (success(preparePaymentHandoff(response, transaction, bank.roots, context, now)) as PaymentHandoff.Challenge).session
      assertEquals(ProtocolResult.Failure(ProtocolFault.InvalidState), session.request(ChallengeAction.Answer(OtpAnswer("123456")), now))
      assertTrue(success(session.request(ChallengeAction.Start, now)) is PaymentChallengeStep.SendToAcs)
      assertEquals(0, bank.server.requestCount)
    }
  }

  @Test
  fun `derived key agrees with independent Nimbus ECDH and Concat KDF`() {
    val transaction = SdkTransaction.create(appId, reference)
    val bankKey = ecKeyPair()
    val sdkKey = deriveChallengeKey(transaction.keyPair.private as ECPrivateKey, bankKey.public as ECPublicKey, reference)
    assertArrayEquals(bankChallengeKey(bankKey, transaction.publicKey, reference), sdkKey)
    assertFalse(sdkKey.contentEquals(deriveChallengeKey(
      transaction.keyPair.private as ECPrivateKey, bankKey.public as ECPublicKey, SdkReferenceNumber("different-reference"),
    )))
  }

  @Test
  fun `wrong OTP and cancellation are terminal outcomes`() {
    SimulatedBank(now, reference).use { bank ->
      listOf(
        ChallengeAction.Answer(OtpAnswer("000000")) to ChallengeOutcome.Rejected,
        ChallengeAction.Cancel to ChallengeOutcome.Cancelled,
      ).forEach { (action, expected) ->
        val transaction = SdkTransaction.create(appId, reference)
        val flow = success(ChallengeFlow.start(transaction, bank.challenge(transaction), bank.roots, now))
        val initial = success(flow.request(ChallengeAction.Start, now))
        success(flow.receive(bank.exchange(initial), now))
        val packet = success(flow.request(action, now))
        assertEquals(expected, success(flow.receive(bank.exchange(packet), now)))
        assertEquals(ProtocolResult.Failure(ProtocolFault.InvalidState), flow.request(action, now))
      }
    }
  }

  @Test
  fun `untrusted expired and incorrectly signed ACS content cannot start a challenge`() {
    SimulatedBank(now, reference).use { bank ->
      val transaction = SdkTransaction.create(appId, reference)
      val parameters = bank.challenge(transaction)
      assertFault(ProtocolFault.UntrustedCertificate, ChallengeFlow.start(transaction, parameters, emptySet(), now))
      assertFault(ProtocolFault.UntrustedCertificate, ChallengeFlow.start(transaction, parameters, bank.roots, now.plusSeconds(7200)))
      val jws = JWSObject.parse(parameters.signedContent)
      val wrongSignature = JWSObject(jws.header, jws.payload).apply { sign(ECDSASigner(ecKeyPair().private as ECPrivateKey)) }.serialize()
      assertFault(ProtocolFault.InvalidSignature, ChallengeFlow.start(transaction, parameters.copy(signedContent = wrongSignature), bank.roots, now))
      val another = SdkTransaction.create(appId, reference)
      assertFault(ProtocolFault.KeyMismatch, ChallengeFlow.start(another, parameters, bank.roots, now))
    }
  }

  @Test
  fun `tampered packets and transaction mismatches fail closed`() {
    SimulatedBank(now, reference).use { bank ->
      val transaction = SdkTransaction.create(appId, reference)
      val parameters = bank.challenge(transaction)
      val flow = success(ChallengeFlow.start(transaction, parameters, bank.roots, now))
      val first = success(flow.request(ChallengeAction.Start, now))
      val reply = bank.exchange(first).split('.').toMutableList()
      val tag = Base64URL(reply[4]).decode().apply { this[0] = (this[0].toInt() xor 1).toByte() }
      reply[4] = Base64URL.encode(tag).toString()
      assertFault(ProtocolFault.IntegrityFailure, flow.receive(reply.joinToString("."), now))
      assertFault(ProtocolFault.InvalidState, flow.request(ChallengeAction.Start, now))
      val wrongIds = success(ChallengeFlow.start(transaction, parameters, bank.roots, now))
      success(wrongIds.request(ChallengeAction.Start, now))
      val wrongResponse = bank.prompt().copy(threeDSServerTransID = ServerTransactionId(UUID.randomUUID().toString()))
      assertFault(ProtocolFault.TransactionMismatch, wrongIds.receive(bank.encrypt(wrongResponse), now))
    }
  }

  @Test
  fun `replayed response counters and malformed records invalidate the exchange`() {
    SimulatedBank(now, reference).use { bank ->
      val transaction = SdkTransaction.create(appId, reference)
      val parameters = bank.challenge(transaction)
      val flow = success(ChallengeFlow.start(transaction, parameters, bank.roots, now))
      val first = success(flow.request(ChallengeAction.Start, now))
      assertFault(ProtocolFault.InvalidState, flow.request(ChallengeAction.Start, now))
      val prompt = bank.exchange(first)
      success(flow.receive(prompt, now))
      success(flow.request(ChallengeAction.Answer(OtpAnswer("123456")), now))
      assertFault(ProtocolFault.CounterMismatch, flow.receive(prompt, now))
      val mismatch = success(ChallengeFlow.start(transaction, parameters, bank.roots, now))
      success(mismatch.request(ChallengeAction.Start, now))
      assertFault(ProtocolFault.CounterMismatch, mismatch.receive(bank.encrypt(bank.prompt().copy(acsCounterAtoS = "001")), now))
      val malformed = success(ChallengeFlow.start(transaction, parameters, bank.roots, now))
      success(malformed.request(ChallengeAction.Start, now))
      assertFault(ProtocolFault.MalformedInput, malformed.receive(bank.encryptPayload("""{"messageType":"CRes"}"""), now))
    }
  }

  @Test
  fun `timeout unsupported challenge types and incomplete success are rejected`() {
    SimulatedBank(now, reference).use { bank ->
      val transaction = SdkTransaction.create(appId, reference)
      val parameters = bank.challenge(transaction)
      val timeout = success(ChallengeFlow.start(transaction, parameters, bank.roots, now))
      assertFault(ProtocolFault.Timeout, timeout.request(ChallengeAction.Start, now.plusSeconds(300)))
      val awaiting = success(ChallengeFlow.start(transaction, parameters, bank.roots, now))
      val packet = success(awaiting.request(ChallengeAction.Start, now))
      assertFault(ProtocolFault.Timeout, awaiting.receive(bank.exchange(packet), now.plusSeconds(300)))
      val unsupported = success(ChallengeFlow.start(transaction, parameters, bank.roots, now))
      success(unsupported.request(ChallengeAction.Start, now))
      assertFault(ProtocolFault.UnsupportedProfile, unsupported.receive(bank.encrypt(bank.prompt().copy(acsUiType = "05")), now))
      val incomplete = success(ChallengeFlow.start(transaction, parameters, bank.roots, now))
      success(incomplete.request(ChallengeAction.Start, now))
      assertFault(ProtocolFault.MalformedInput, incomplete.receive(bank.encrypt(bank.prompt().copy(transStatus = AuthenticationStatus.Authenticated)), now))
    }
  }

  private fun authentication(transaction: SdkTransaction, bank: SimulatedBank): AuthenticationParameters =
    AuthenticationParameters(transaction.appId, transaction.id, transaction.reference, transaction.publicKey,
      success(encryptDeviceInformation(device, bank.directoryKey.public as RSAPublicKey)))

  private fun <T> success(result: ProtocolResult<T>): T = when (result) {
    is ProtocolResult.Success -> result.value
    is ProtocolResult.Failure -> throw AssertionError("unexpected protocol fault: ${result.fault}")
  }

  private fun assertFault(expected: ProtocolFault, result: ProtocolResult<*>) {
    assertEquals(ProtocolResult.Failure(expected), result)
  }
}

@Serializable private data class AuthenticationReply(val challenge: ChallengeParametersWire, val transData: String) {
  val parameters: ChallengeParameters
    get() = ChallengeParameters(challenge.serverId, challenge.acsId, challenge.signedContent)
}

@Serializable private data class CertificateExpectation(val schemeName: DirectoryServer, val rootCount: Int, val fingerprint: String)

@Serializable private data class ChallengeParametersWire(
  val serverId: ServerTransactionId, val acsId: AcsTransactionId, val signedContent: String,
)

@Serializable private data class PaymentActionFixture(
  @SerialName("Type") val type: String = "Node.Internal.SimpleResponse",
  @SerialName("Name") val name: String,
  @SerialName("Tags") val tags: PaymentActionTagsFixture = PaymentActionTagsFixture(),
)
@Serializable private data class PaymentActionTagsFixture(
  @SerialName("ClientAction.ShowMessage.Deserialise") val deserialize: String = "1",
  @SerialName("ClientAction.Controls.Callback") val callback: String = "payment-complete?token=a%2Bb",
)
@Serializable private data class PaymentEnvelopeFixture(val message: String)
@Serializable private data class PaymentChallengeFixture(
  val transStatus: String = "C", val transData: String = "opaque-server-continuation",
  val threeDSServerTransID: ServerTransactionId, val acsTransID: AcsTransactionId,
  val acsReferenceNumber: String = "offline-acs-reference", val acsSignedContent: String,
)

@Serializable private data class PaymentContinueFixture(
  val transStatus: PaymentAuthenticationStatus = PaymentAuthenticationStatus.Authenticated,
  val transData: String = "opaque-server-continuation",
)

private fun paymentAuthenticationBody(parameters: ChallengeParameters, status: PaymentAuthenticationStatus = PaymentAuthenticationStatus.Challenge): String {
  val json = Json { encodeDefaults = true }
  val message = when (status) {
    PaymentAuthenticationStatus.Authenticated -> json.encodeToString(PaymentContinueFixture())
    PaymentAuthenticationStatus.Challenge -> json.encodeToString(PaymentChallengeFixture(threeDSServerTransID = parameters.serverId,
      acsTransID = parameters.acsId, acsSignedContent = parameters.signedContent))
    else -> error("Unexpected authentication fixture status")
  }
  return json.encodeToString(listOf(PaymentActionFixture(name = json.encodeToString(PaymentEnvelopeFixture(message)))))
}

private class SimulatedBank(private val now: Instant, private val reference: SdkReferenceNumber) : Closeable {
  val server = MockWebServer()
  val failure = AtomicReference<Throwable>()
  val receivedDevice = AtomicReference<DeviceInformation>()
  val receivedPublicKey = AtomicReference<PublicJwk>()
  val directoryKey: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
  private val signingKey = ecKeyPair()
  private val rootKey = ecKeyPair()
  private val root = certificate(rootKey, rootKey, "CN=Offline CA", "CN=Offline CA", now, CertificateRole.Root)
  private val leaf = certificate(signingKey, rootKey, "CN=localhost", "CN=Offline CA", now, CertificateRole.Acs)
  val roots = setOf(TrustAnchor(root, null))
  private val bankKey = ecKeyPair()
  private val serverId = ServerTransactionId(UUID.randomUUID().toString())
  private val acsId = AcsTransactionId(UUID.randomUUID().toString())
  private data class BankSession(val sdkId: SdkTransactionId, val secret: ByteArray)
  private lateinit var session: BankSession
  private val sdkId: SdkTransactionId get() = session.sdkId
  private val secret: ByteArray get() = session.secret
  private val json = Json { encodeDefaults = true; explicitNulls = false }
  val client: OkHttpClient

  init {
    val serverKeys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null)
      setKeyEntry("acs", signingKey.private, "offline".toCharArray(), arrayOf(leaf, root))
    }
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
      init(serverKeys, "offline".toCharArray())
    }
    val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("ca", root) }
    val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
    val trustManager = trustManagers.trustManagers.single() as X509TrustManager
    val tls = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, arrayOf(trustManager), null) }
    server.useHttps(tls.socketFactory, false)
    client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trustManager)
      .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
      .callTimeout(5, TimeUnit.SECONDS).build()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = try {
        when (request.requestUrl?.encodedPath) {
          "/authenticate", "/payment-authenticate", "/payment-frictionless" -> {
            check(request.getHeader("c3-capabilities") == "3dsenabled")
            val form = ("https://localhost/?" + request.body.readUtf8()).toHttpUrl()
            check(form.querySize == if (request.requestUrl?.encodedPath == "/authenticate") 7 else 11)
            check(form.queryParameter("sdkMaxTimeout") == "05" && form.queryParameter("sdkInterface") == "01")
            check(SdkReferenceNumber(requireNotNull(form.queryParameter("sdkReferenceNumber"))) == reference)
            SdkAppId(requireNotNull(form.queryParameter("sdkAppID")))
            val sdkId = SdkTransactionId(requireNotNull(form.queryParameter("sdkTransID")))
            val publicKey = Json.decodeFromString<PublicJwk>(requireNotNull(form.queryParameter("sdkEphemPubKey")))
            receivedPublicKey.set(publicKey)
            val jwe = JWEObject.parse(requireNotNull(form.queryParameter("sdkEncData")))
            check(jwe.header.algorithm == JWEAlgorithm.RSA_OAEP_256 && jwe.header.encryptionMethod == EncryptionMethod.A128CBC_HS256)
            jwe.decrypt(RSADecrypter(directoryKey.private))
            receivedDevice.set(Json.decodeFromString<DeviceInformation>(jwe.payload.toString()))
            session = BankSession(sdkId, bankChallengeKey(bankKey, publicKey, reference))
            val reply = AuthenticationReply(
              ChallengeParametersWire(serverId, acsId, signedContent(publicKey)), "opaque-server-continuation",
            )
            val body = if (request.requestUrl?.encodedPath != "/authenticate") paymentAuthenticationBody(
              ChallengeParameters(serverId, acsId, reply.parameters.signedContent),
              if (request.requestUrl?.encodedPath == "/payment-frictionless") PaymentAuthenticationStatus.Authenticated else PaymentAuthenticationStatus.Challenge,
            ) else Json.encodeToString(reply)
            MockResponse().setHeader("Content-Type", "application/json").setBody(body)
          }
          "/challenge" -> {
            check(request.getHeader("c3-userauth") == null)
            MockResponse().setBody(exchange(request.body.readUtf8()))
          }
          "/payment-complete" -> {
            val form = ("https://localhost/?" + request.body.readUtf8()).toHttpUrl()
            check(form.querySize == 4 && form.queryParameter("transData") == "opaque-server-continuation")
            check(request.requestUrl?.queryParameter("token") == "a+b")
            check(request.getHeader("c3-capabilities") == "3dsenabled")
            MockResponse().setBody("""[{"Type":"Node.Internal.SimpleResponse","Name":"offline continuation accepted"}]""")
          }
          else -> MockResponse().setResponseCode(404)
        }
      } catch (e: Exception) {
        failure.set(e)
        MockResponse().setResponseCode(500)
      }
    }
    server.start()
  }

  fun challenge(transaction: SdkTransaction): ChallengeParameters {
    session = BankSession(transaction.id, bankChallengeKey(bankKey, transaction.publicKey, reference))
    return ChallengeParameters(serverId, acsId, signedContent(transaction.publicKey))
  }

  private fun signedContent(sdkPublicKey: PublicJwk): String {
    val publicKey = Json.decodeFromString<PublicJwk>(ECKey.Builder(Curve.P_256, bankKey.public as ECPublicKey).build().toJSONString())
    val data = AcsSignedData(server.url("/challenge").toString(), publicKey, sdkPublicKey)
    val jws = JWSObject(
      JWSHeader.Builder(JWSAlgorithm.ES256).x509CertChain(listOf(Base64.encode(leaf.encoded))).build(),
      Payload(Json.encodeToString(data)),
    )
    jws.sign(ECDSASigner(signingKey.private as ECPrivateKey))
    return jws.serialize()
  }

  fun postChallenge(packet: String): String {
    val request = Request.Builder().url(server.url("/challenge"))
      .post(packet.toRequestBody("application/jose".toMediaType())).build()
    return client.newCall(request).execute().use { response ->
      check(response.code == 200)
      response.body.string()
    }
  }

  fun exchange(packet: String): String {
    val jwe = JWEObject.parse(packet)
    check(jwe.header.algorithm == JWEAlgorithm.DIR && jwe.header.encryptionMethod == EncryptionMethod.A128CBC_HS256)
    check(jwe.header.keyID == acsId.value)
    jwe.decrypt(DirectDecrypter(secret))
    val request = json.decodeFromString<ChallengeRequest>(jwe.payload.toString())
    check(request.threeDSServerTransID == serverId && request.acsTransID == acsId && request.sdkTransID == sdkId)
    check(request.messageType == "CReq" && request.messageVersion == "2.2.0")
    val counter = request.sdkCounterStoA.toInt()
    check(jwe.iv.decode().size == 16)
    if (counter == 0) {
      check(request.challengeNoEntry == "Y" && request.challengeDataEntry == null)
      return encrypt(prompt())
    }
    check(counter == 1)
    val status = if (request.challengeDataEntry == "123456") AuthenticationStatus.Authenticated else AuthenticationStatus.Rejected
    val response = ChallengeResponse(serverId, acsId, sdkId, "001", CompletionIndicator.Complete, "CRes", "2.2.0", status)
    return encrypt(response)
  }

  fun prompt(): ChallengeResponse = ChallengeResponse(
    serverId, acsId, sdkId, "000", CompletionIndicator.Incomplete, "CRes", "2.2.0",
    acsUiType = "01", challengeInfoHeader = "Offline bank", challengeInfoText = "Enter your code", challengeInfoLabel = "Verification code",
  )

  fun encrypt(response: ChallengeResponse): String = encryptPayload(json.encodeToString(response))

  fun encryptPayload(payload: String): String {
    val header = JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A128CBC_HS256).keyID(acsId.value).build()
    val iv = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
    val encrypted = AESCBC.encryptAuthenticated(SecretKeySpec(secret, "AES"), iv,
      payload.toByteArray(), AAD.compute(header), null, null)
    return listOf(header.toBase64URL().toString(), "", Base64URL.encode(iv).toString(),
      Base64URL.encode(encrypted.cipherText).toString(), Base64URL.encode(encrypted.authenticationTag).toString()).joinToString(".")
  }

  override fun close() {
    server.shutdown()
    client.connectionPool.evictAll()
    client.dispatcher.executorService.shutdown()
  }
}

private fun ecKeyPair(): KeyPair {
  val generator = KeyPairGenerator.getInstance("EC")
  generator.initialize(ECGenParameterSpec("secp256r1"))
  return generator.generateKeyPair()
}

private fun bankChallengeKey(bankKey: KeyPair, sdkKey: PublicJwk, reference: SdkReferenceNumber): ByteArray {
  val shared = ECDH.deriveSharedSecret(sdkKey.toKey(), bankKey.private, null)
  return ConcatKDF("SHA-256").deriveKey(shared, 256,
    ConcatKDF.encodeStringData(null), ConcatKDF.encodeDataWithLength(null as Base64URL?),
    ConcatKDF.encodeDataWithLength(Base64URL.encode(reference.value)),
    ConcatKDF.encodeIntData(256), ConcatKDF.encodeNoData()).encoded
}

private enum class CertificateRole { Root, Acs }

private fun certificate(
  subjectKey: KeyPair, issuerKey: KeyPair, subject: String, issuer: String, now: Instant, role: CertificateRole,
): X509Certificate {
  val builder = JcaX509v3CertificateBuilder(X500Name(issuer), BigInteger(128, java.security.SecureRandom()),
    Date.from(now.minusSeconds(3600)), Date.from(now.plusSeconds(3600)), X500Name(subject), subjectKey.public)
  builder.addExtension(Extension.basicConstraints, true, BasicConstraints(role == CertificateRole.Root))
  builder.addExtension(Extension.keyUsage, true, KeyUsage(
    if (role == CertificateRole.Root) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature,
  ))
  if (role == CertificateRole.Acs) {
    builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, "localhost")))
  }
  val signed = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey.private))
  return JcaX509CertificateConverter().getCertificate(signed)
}
