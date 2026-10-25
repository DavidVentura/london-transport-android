package dev.davidv.londontransport

import dev.davidv.londontransport.threeds.*
import java.io.File
import java.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PaymentTraceTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun `request redaction excludes secrets in headers URL form and opaque bodies`() {
    val request = Request.Builder().url("https://example.test/private-path?transData=private-query")
      .header("c3-userauth", "private-auth").header("Cookie", "private-cookie")
      .header("c3-capabilities", "3dsenabled")
      .post(FormBody.Builder().add("c3-controlvals", "private-card")
        .add("sdkEncData", "private-device").add("c3-deviceid", "private-id")
        .add("sdkAppID", "private-app").add("sdkMaxTimeout", "05").add("sdkInterface", "01").build()).build()
    val text = Json.encodeToString(redactPaymentRequest(request))
    listOf("private-path", "private-query", "private-auth", "private-cookie", "private-card", "private-device", "private-id", "private-app")
      .forEach { assertFalse(it, text.contains(it)) }
    assertTrue(text.contains("3dsenabled"))
    assertTrue(text.contains("05"))
    assertFalse(Json.encodeToString(redactPaymentRequest(request.newBuilder().post("private-otp".toRequestBody()).build())).contains("private-otp"))
  }

  @Test fun `HTTP rejection preserves complete response headers and body without another send`() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val body = "full private response " + "x".repeat(520_000)
      server.enqueue(MockResponse().setResponseCode(403).addHeader("X-Private", "full-header").setBody(body))
      val directory = temporary.newFolder()
      val runtime = PaymentRuntime.create(Clock.systemUTC(), PaymentTrace.Local(directory))
      val pending = PendingPaymentRequest(Request.Builder().url(server.url("/callback")).post("secret".toRequestBody()).build())
      assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.HttpRejected(403)), runtime.continuePayment(pending))
      assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.AlreadySent), runtime.continuePayment(pending))
      val exchange = directory.listFiles()!!.single()
      assertEquals(body, File(exchange, "response.body").readText())
      assertTrue(File(exchange, "response.json").readText().contains("full-header"))
      assertTrue(File(exchange, "request.json").readText().contains("Continuation"))
      assertTrue(File(exchange, "complete.json").isFile)
      assertEquals(1, server.requestCount)
    }
  }

  @Test fun `malformed oversized and invalid UTF8 bodies are fully logged while parsing stays bounded`() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      listOf("not-json".toByteArray(), ByteArray(520_000) { 32 }, byteArrayOf(0xc3.toByte(), 0x28)).forEach { body ->
        server.enqueue(MockResponse().setBody(okio.Buffer().write(body)))
        val directory = temporary.newFolder()
        val runtime = PaymentRuntime.create(Clock.systemUTC(), PaymentTrace.Local(directory))
        val pending = PendingPaymentRequest(Request.Builder().url(server.url("/callback")).post("secret".toRequestBody()).build())
        assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.InvalidResponse), runtime.continuePayment(pending))
        assertArrayEquals(body, File(directory.listFiles()!!.single(), "response.body").readBytes())
      }
    }
  }

  @Test fun `lost reply is recorded as incomplete and never retried`() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
      val directory = temporary.newFolder()
      val runtime = PaymentRuntime.create(Clock.systemUTC(), PaymentTrace.Local(directory))
      val pending = PendingPaymentRequest(Request.Builder().url(server.url("/callback")).post("secret".toRequestBody()).build())
      assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.OutcomeUnknown), runtime.continuePayment(pending))
      val exchange = directory.listFiles()!!.single()
      assertTrue(File(exchange, "failure.json").isFile)
      assertFalse(File(exchange, "complete.json").exists())
      assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.AlreadySent), runtime.continuePayment(pending))
      assertEquals(1, server.requestCount)
    }
  }

  @Test fun `unwritable trace stops before network IO`() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val runtime = PaymentRuntime.create(Clock.systemUTC(), PaymentTrace.Local(temporary.newFile()))
      val pending = PendingPaymentRequest(Request.Builder().url(server.url("/callback")).post("secret".toRequestBody()).build())
      assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.OutcomeUnknown), runtime.continuePayment(pending))
      assertEquals(0, server.requestCount)
    }
  }
}
