package dev.davidv.londontransport

import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.crypto.RSADecrypter
import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.threeds.*
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import dev.davidv.londontransport.santander.CoreConstants
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ThreeDsPaymentSubmissionTest {
  private val now = Instant.parse("2026-10-12T12:00:00Z")
  private val credentials = Credentials(UserNodeId("test-user"), AuthToken("test-token"))
  private val appId = SdkAppId("ac2d9673-ed8d-439f-a9ba-9629e203966a")
  private val reference = SdkReferenceNumber("test-sdk-reference")
  private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
  private val transaction = SdkTransaction.create(appId, reference)
  private val device = DeviceInformation(DeviceDataVersion.V15,
    DeviceData("Android", "Offline test device", "Android VANILLA_ICE_CREAM 15 API 35", "15", "en-US", "0",
      packageName = "dev.davidv.londontransport", sdkAppId = appId.value, sdkVersion = SdkVersion("0.0.0"), sdkReferenceNumber = reference),
    mapOf(DeviceParameter.Latitude to UnavailableReason.Restricted, DeviceParameter.Longitude to UnavailableReason.Restricted))
  private val authentication = AuthenticationParameters(appId, transaction.id, reference, transaction.publicKey,
    (encryptDeviceInformation(device, keys.public as RSAPublicKey) as ProtocolResult.Success).value)
  private val json = Json { encodeDefaults = true; explicitNulls = false }
  private val record = AuthenticationFixture("C", "opaque+continuation&value", "9781e504-3b6f-4592-97a4-3736832e42b5",
    "c0f6d9f9-f2c2-4b12-88b1-4a43842047b3", "test-acs-reference", "test-signed-content", "2.2.0")

  @Test fun `SDK form fields sit beside encrypted card controls and preserve core request metadata`() {
    val original = core("https://localhost/card-submit".toHttpUrl())
    val submission = (buildCardSubmissionRequest(original, authentication) as ProtocolResult.Success).value
    val before = original.body as FormBody
    val after = submission.body as FormBody
    assertEquals(4, before.size)
    assertEquals(11, after.size)
    (0 until before.size).forEach { index ->
      assertEquals(before.name(index), after.name(index))
      assertEquals(before.value(index), after.value(index))
    }
    assertEquals("", original.header("c3-capabilities"))
    assertEquals("3dsenabled", submission.header("c3-capabilities"))
    original.headers.names().filter { it != "c3-capabilities" }.forEach { header ->
      assertEquals(original.header(header), submission.header(header))
    }
    assertEquals(userAuthHeader(credentials), submission.header("c3-userauth"))
    assertEquals("flatjson", submission.header("format"))
    assertEquals("y", submission.header("c3-applysensitivedatacheck"))
    val form = ("https://localhost/?" + after.encoded()).toHttpUrl()
    assertEquals(now.epochSecond.toString(), form.queryParameter("c3-clienttime"))
    assertEquals("05", form.queryParameter("sdkMaxTimeout"))
    assertEquals("01", form.queryParameter("sdkInterface"))
    assertEquals(reference.value, form.queryParameter("sdkReferenceNumber"))
    assertEquals(transaction.id.value, form.queryParameter("sdkTransID"))
    val jwe = JWEObject.parse(form.queryParameter("sdkEncData"))
    jwe.decrypt(RSADecrypter(keys.private))
    assertEquals(device, Json.decodeFromString<DeviceInformation>(jwe.payload.toString()))
    val key = Base64.getDecoder().decode(CoreConstants.CONTROL_VALS_KEY_BASE64)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
      init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOf(16)))
    }
    val controls = cipher.doFinal(Base64.getDecoder().decode(form.queryParameter("c3-controlvals"))).toString(Charsets.UTF_8)
    assertEquals("member_card_number=4111111111111111&member_card_cvv=123&encryptionverificationcheck=1", controls)
    assertFalse(controls.contains("sdk"))
  }

  @Test fun `missing card controls authentication duplicate fields and double augmentation are rejected`() {
    val original = core("https://localhost/card-submit".toHttpUrl())
    val augmented = (buildCardSubmissionRequest(original, authentication) as ProtocolResult.Success).value
    val duplicate = FormBody.Builder().add("c3-controlvals", "first").add("c3-controlvals", "second").build()
    listOf(
      original.newBuilder().get().build(), original.newBuilder().header("c3-userauth", "").build(),
      original.newBuilder().post(FormBody.Builder().build()).build(), original.newBuilder().post(duplicate).build(), augmented,
    ).forEach { invalid -> assertEquals(ProtocolResult.Failure(ProtocolFault.MalformedInput), buildCardSubmissionRequest(invalid, authentication)) }
  }

  @Test fun `challenge response requires the action envelope all challenge fields and the server continuation`() {
    val result = (parsePaymentAuthentication(reply(record)) as ProtocolResult.Success).value as PaymentAuthentication.Challenge
    assertEquals(ServerTransactionId(checkNotNull(record.threeDSServerTransID)), result.parameters.serverId)
    assertEquals(AcsTransactionId(checkNotNull(record.acsTransID)), result.parameters.acsId)
    assertEquals(record.acsSignedContent, result.parameters.signedContent)
    assertEquals(record.acsReferenceNumber, result.acsReference.value)
    assertEquals("Clients/TfL/Test/Continue", result.continuation.callback.value)
    assertEquals(record.transData, result.continuation.data.value)
    assertFalse(result.continuation.data.toString().contains(record.transData))
  }

  @Test fun `non challenge statuses are retained for server continuation rather than treated as a completed payment`() {
    listOf("Y", "N", "U", "A", "R", "I").forEach { status ->
      val fixture = AuthenticationFixture(status, "continuation")
      val result = (parsePaymentAuthentication(reply(fixture)) as ProtocolResult.Success).value as PaymentAuthentication.Continue
      assertEquals(status, Json.encodeToString(result.status).trim('"'))
      assertEquals(fixture.transData, result.continuation.data.value)
    }
    assertEquals(ProtocolResult.Failure(ProtocolFault.UnsupportedProfile), parsePaymentAuthentication(reply(AuthenticationFixture("D", "continuation"))))
  }

  @Test fun `malformed records cannot contribute partially parsed authentication values`() {
    listOf(
      record.copy(threeDSServerTransID = null), record.copy(acsTransID = null), record.copy(acsReferenceNumber = null),
      record.copy(acsSignedContent = null), record.copy(acsSignedContent = " "), record.copy(transData = ""),
      record.copy(threeDSServerTransID = "invalid"), record.copy(transStatus = "unknown"),
      record.copy(transStatus = "Y", acsTransID = "invalid"),
    ).forEach { malformed -> assertEquals(ProtocolResult.Failure(ProtocolFault.MalformedInput), parsePaymentAuthentication(reply(malformed))) }
    val valid = action(record)
    val invalidMessage = valid.copy(name = json.encodeToString(MessageFixture("not JSON")))
    listOf("{}", "[]", json.encodeToString(listOf(valid, valid)),
      json.encodeToString(listOf(valid.copy(tags = valid.tags.copy(callback = null)))),
      json.encodeToString(listOf(valid.copy(tags = valid.tags.copy(deserialize = null)))),
      json.encodeToString(listOf(valid.copy(name = "{}"))),
      json.encodeToString(listOf(invalidMessage)),
    ).forEach { malformed -> assertEquals(ProtocolResult.Failure(ProtocolFault.MalformedInput), parsePaymentAuthentication(malformed)) }
    assertEquals(ProtocolResult.Failure(ProtocolFault.UnsupportedProfile), parsePaymentAuthentication(reply(record.copy(messageVersion = "2.3.1"))))
  }

  @Test fun `complete card request and both authentication branches exercise a local processor without redirects or retries`() {
    MockWebServer().use { server ->
      server.start()
      val client = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build()
      listOf(AuthenticationFixture("Y", "continuation"), record).forEach { fixture ->
        server.enqueue(MockResponse().setBody(reply(fixture)))
        val request = (buildCardSubmissionRequest(core(server.url("/card-submit")), authentication) as ProtocolResult.Success).value
        val result = client.newCall(request).execute().use { response -> parsePaymentAuthentication(response.body.string()) }
        assertTrue(result is ProtocolResult.Success)
        val parsed = (result as ProtocolResult.Success).value
        assertEquals(fixture.transStatus == "C", parsed is PaymentAuthentication.Challenge)
        val received = server.takeRequest()
        assertEquals("POST", received.method)
        assertEquals("3dsenabled", received.getHeader("c3-capabilities"))
        val form = ("https://localhost/?" + received.body.readUtf8()).toHttpUrl()
        assertEquals(11, form.querySize)
        assertEquals(authentication.encryptedDeviceData, form.queryParameter("sdkEncData"))
        assertNotNull(form.queryParameter("c3-controlvals"))
      }
      assertEquals(2, server.requestCount)
    }
  }

  private fun core(url: okhttp3.HttpUrl): Request = buildCoreRequest(url, DeviceId("offline-device"), credentials,
    mapOf("member_card_number" to "4111111111111111", "member_card_cvv" to "123"), now)
  private fun action(record: AuthenticationFixture): ActionFixture = ActionFixture(name = json.encodeToString(MessageFixture(json.encodeToString(record))))
  private fun reply(record: AuthenticationFixture): String = json.encodeToString(listOf(action(record)))
  private fun FormBody.encoded(): String = (0 until size).joinToString("&") { "${encodedName(it)}=${encodedValue(it)}" }
}

@Serializable private data class AuthenticationFixture(
  val transStatus: String, val transData: String,
  val threeDSServerTransID: String? = null, val acsTransID: String? = null,
  val acsReferenceNumber: String? = null, val acsSignedContent: String? = null, val messageVersion: String? = null,
)
@Serializable private data class MessageFixture(val message: String)
@Serializable private data class ActionFixture(
  @SerialName("Type") val type: String = "Node.Internal.SimpleResponse",
  @SerialName("Name") val name: String,
  @SerialName("Tags") val tags: ActionTagsFixture = ActionTagsFixture(),
)
@Serializable private data class ActionTagsFixture(
  @SerialName("ClientAction.ShowMessage.Deserialise") val deserialize: String? = "1",
  @SerialName("ClientAction.Controls.Callback") val callback: String? = "Clients/TfL/Test/Continue",
)
