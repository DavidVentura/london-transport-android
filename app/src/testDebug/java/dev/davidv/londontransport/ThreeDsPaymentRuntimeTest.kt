package dev.davidv.londontransport

import dev.davidv.londontransport.threeds.*
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ThreeDsPaymentRuntimeTest {
  @Test fun `redirects and HTTP failures do not retry or follow another target`() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val runtime = PaymentRuntime.create(Clock.systemUTC())
      listOf(302, 401, 503).forEach { status ->
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/elsewhere")))
        val pending = PendingPaymentRequest(Request.Builder().url(server.url("/continue")).post("opaque".toRequestBody()).build())
        assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.HttpRejected(status)), runtime.continuePayment(pending))
        assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.AlreadySent), runtime.continuePayment(pending))
      }
      assertEquals(3, server.requestCount)
    }
  }

  @Test fun `lost responses leave an unknown outcome and cannot be resent`() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
      val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(1, TimeUnit.SECONDS).build()
      val runtime = PaymentRuntime(client, Clock.systemUTC())
      val pending = PendingPaymentRequest(Request.Builder().url(server.url("/continue")).post("opaque".toRequestBody()).build())
      assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.OutcomeUnknown), runtime.continuePayment(pending))
      assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.AlreadySent), runtime.continuePayment(pending))
      assertEquals(1, server.requestCount)
    }
  }

  @Test fun `concurrent sends share one attempt and malformed responses are consumed`() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setBody("not-json"))
      val runtime = PaymentRuntime.create(Clock.systemUTC())
      val pending = PendingPaymentRequest(Request.Builder().url(server.url("/continue")).post("opaque".toRequestBody()).build())
      val outcomes = listOf(async { runtime.continuePayment(pending) }, async { runtime.continuePayment(pending) }).awaitAll()
      assertEquals(setOf(PaymentRuntimeResult.Failure(PaymentRuntimeFault.InvalidResponse),
        PaymentRuntimeResult.Failure(PaymentRuntimeFault.AlreadySent)), outcomes.toSet())
      assertEquals(1, server.requestCount)
    }
  }

  @Test fun `oversized and invalid UTF8 replies fail at the network boundary`() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val runtime = PaymentRuntime.create(Clock.systemUTC())
      listOf(okio.Buffer().writeUtf8(" ".repeat(512_001)), okio.Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))).forEach { body ->
        server.enqueue(MockResponse().setBody(body))
        val pending = PendingPaymentRequest(Request.Builder().url(server.url("/continue")).post("opaque".toRequestBody()).build())
        assertEquals(PaymentRuntimeResult.Failure(PaymentRuntimeFault.InvalidResponse), runtime.continuePayment(pending))
      }
    }
  }
}
