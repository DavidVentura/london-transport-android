package dev.davidv.londontransport

import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.threeds.*
import java.time.Instant
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ThreeDsPaymentContinuationTest {
  private val now = Instant.parse("2026-10-12T12:00:00Z")
  private val context = PaymentRequestContext("https://processor.example/".toHttpUrl(), DeviceId("test-device"),
    Credentials(UserNodeId("test-user"), AuthToken("test-token")))
  private val data = PaymentTransactionData("opaque +/&=? continuation")

  @Test fun `continuation retains existing query parameters and adds opaque data only to the form`() {
    val continuation = PaymentContinuation(PaymentCallback("Clients/TfL/Continue?existing=a%2Bb&empty="), data)
    val target = (PaymentContinuationTarget.resolve(continuation, context.base) as ProtocolResult.Success).value
    val normal = buildPaymentContinuationRequest(target, context, PaymentContinuationDisposition.Normal, now)
    val failed = buildPaymentContinuationRequest(target, context, PaymentContinuationDisposition.ChallengeFailed, now)
    assertEquals("/Clients/TfL/Continue", normal.url.encodedPath)
    assertEquals("a+b", normal.url.queryParameter("existing"))
    assertEquals("", normal.url.queryParameter("empty"))
    assertNull(normal.url.queryParameter("ChallengeFailed"))
    assertEquals("1", failed.url.queryParameter("ChallengeFailed"))
    assertEquals(1, failed.url.queryParameterValues("ChallengeFailed").size)
    assertEquals(normal.url.queryParameter("existing"), failed.url.queryParameter("existing"))
    assertEquals(2, normal.url.querySize)
    listOf(normal, failed).forEach { request ->
      assertEquals("POST", request.method)
      assertEquals("3dsenabled", request.header("c3-capabilities"))
      assertEquals(userAuthHeader(context.credentials), request.header("c3-userauth"))
      assertNull(request.header("c3-applysensitivedatacheck"))
      assertNull(request.url.queryParameter("transData"))
      val body = request.body as FormBody
      assertEquals(4, body.size)
      assertEquals(listOf("c3-clienttime", "c3-deviceid", "RemoteHost", "transData"), (0 until body.size).map(body::name))
      assertEquals(data.value, body.value(3))
      assertEquals(now.epochSecond.toString(), body.value(0))
      assertEquals(context.deviceId.value, body.value(1))
      assertEquals(REMOTE_HOST, body.value(2))
    }
  }

  @Test fun `cross origin credential exfiltration fragments userinfo and ambiguous failure flags are rejected`() {
    listOf("https://attacker.example/steal", "//attacker.example/steal", "http://processor.example/cleartext",
      "https://processor.example:444/other-port", "https://user:pass@processor.example/path", "Continue#fragment",
      "Continue?ChallengeFailed=0", "Continue?ChallengeFailed=1&ChallengeFailed=0", "ftp://processor.example/Continue",
    ).forEach { callback ->
      assertEquals(callback, ProtocolResult.Failure(ProtocolFault.MalformedInput),
        PaymentContinuationTarget.resolve(PaymentContinuation(PaymentCallback(callback), data), context.base))
    }
    assertTrue(PaymentContinuationTarget.resolve(PaymentContinuation(PaymentCallback("https://processor.example/Continue"), data), context.base)
      is ProtocolResult.Success)
    val part = PaymentContinuationTarget.resolve(PaymentContinuation(PaymentCallback("part://Clients.TfL.Continue?existing=a%2Bb"), data), context.base)
      as ProtocolResult.Success
    assertEquals("/Clients/TfL/Continue", part.value.url.encodedPath)
    assertEquals("a+b", part.value.url.queryParameter("existing"))
  }

  @Test fun `frictionless and declined statuses both require server continuation without a challenge failure flag`() {
    val transaction = SdkTransaction.create(SdkAppId("ac2d9673-ed8d-439f-a9ba-9629e203966a"), SdkReferenceNumber("test-reference"))
    listOf(NonChallengePaymentStatus.Authenticated, NonChallengePaymentStatus.NotAuthenticated).forEach { status ->
      val response = PaymentAuthentication.Continue(status, PaymentContinuation(PaymentCallback("Continue"), data))
      val result = (preparePaymentHandoff(response, transaction, emptySet(), context, now) as ProtocolResult.Success).value as PaymentHandoff.Continue
      assertEquals(status, result.status)
      assertNull(result.request.url.queryParameter("ChallengeFailed"))
      assertEquals(data.value, (result.request.body as FormBody).value(3))
    }
  }

  @Test fun `context rejects non TLS remote bases and ambiguous base URLs`() {
    listOf("http://processor.example/", "https://processor.example/path", "https://processor.example/?query=1",
      "https://processor.example/#fragment", "https://user:pass@processor.example/",
    ).forEach { base ->
      assertEquals(ProtocolResult.Failure(ProtocolFault.MalformedInput), protocolBoundary { context.copy(base = base.toHttpUrl()) })
    }
  }
}
