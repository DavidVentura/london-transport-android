package dev.davidv.londontransport

import dev.davidv.londontransport.santander.TutorialRetryReason
import dev.davidv.londontransport.santander.QrCode
import dev.davidv.londontransport.santander.BikeId
import dev.davidv.londontransport.santander.MemberId
import dev.davidv.londontransport.santander.PolarisSession
import dev.davidv.londontransport.santander.UnlockResult
import dev.davidv.londontransport.santander.RideResult
import dev.davidv.londontransport.santander.DeviceId
import dev.davidv.londontransport.santander.LoginChallenge
import dev.davidv.londontransport.santander.Password
import dev.davidv.londontransport.santander.PolarisClient
import dev.davidv.londontransport.santander.PolarisLoginResult
import dev.davidv.londontransport.santander.PolarisToken
import dev.davidv.londontransport.santander.parsePolarisLogin
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarisClientTest {
  @Serializable private data class QrBody(val qr_code: String, val nonce: String, val mutation_type: Int, val mutation_session: String, val retry_reasons: List<TutorialRetryReason>)

  @Serializable private data class LoginBody(val username: String, val password: String, val device_id: String)

  @Test fun `account login uses the London login contract without Corethree or OAuth credentials`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"access":{"member_id":"member","access_token":"secret","firebase_token":"firebase"}}"""))
      val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"))
      val result = client.login("person@example.test", Password("password")) as PolarisLoginResult.Authenticated
      assertEquals("member", result.session.member.value)
      assertEquals("secret", result.session.token.value)
      assertFalse(result.toString().contains("secret"))
      val request = server.takeRequest()
      assertEquals("/v1/lbsbff/polaris/api/login/2fa", request.path)
      assertEquals("POST", request.method)
      assertEquals("application/json;messageType=pb.api.endpoints.v1.lbs_bff.ReadPolarisLogin2FARequest; charset=utf-8", request.getHeader("Content-Type"))
      assertEquals(LoginBody("person@example.test", "password", "device"), Json.decodeFromString<LoginBody>(request.body.readUtf8()))
      assertEquals(null, request.getHeader("c3-userauth"))
      assertEquals(null, request.getHeader("Authorization"))
    }
  }

  @Test fun `email verification remains a challenge until the code is accepted`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"mfa":{"id":"challenge","expires_on_ms":1900000000000,"message":{"title":"Check your email","description":"Enter your code"}}}"""))
      server.enqueue(MockResponse().setBody("""{"access":{"member_id":"member","access_token":"token"}}"""))
      val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"))
      val result = client.login("person@example.test", Password("password")) as PolarisLoginResult.VerificationRequired
      assertEquals("Enter your code", result.challenge.message)
      assertTrue(client.verifyLogin(result.challenge, "123456") is PolarisLoginResult.Authenticated)
      server.takeRequest()
      val verification = server.takeRequest()
      assertEquals("/v1/lbsbff/polaris/api/login/2fa/validate", verification.path)
      assertEquals("""{"mfa":{"id":"challenge","verification_code":"123456"},"device_id":"device"}""", verification.body.readUtf8())
    }
  }

  @Test fun `conflicting or incomplete login records cannot authenticate`() {
    assertTrue(parsePolarisLogin("{}") is PolarisLoginResult.Unavailable)
    assertTrue(parsePolarisLogin("""{"access":{"member_id":"member","access_token":""}}""") is PolarisLoginResult.Unavailable)
    assertEquals("PolarisToken(hidden)", PolarisToken("secret").toString())
  }

  @Test fun `an invalid verification code makes no request`() = runTest {
    MockWebServer().use { server ->
      val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"))
      assertTrue(client.verifyLogin(LoginChallenge("challenge", Instant.MAX, "Code"), "abc") is PolarisLoginResult.Rejected)
      assertEquals(0, server.requestCount)
    }
  }

  @Test fun `login rejection preserves the backend explanation`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_credentials","error_description":"Check your email and password"}"""))
      assertEquals(
        PolarisLoginResult.Rejected("Check your email and password"),
        PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/")).login("email", Password("password")),
      )
    }
  }

  @Test fun `login secrets are never forwarded to a redirect destination`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/unexpected"))
      val result = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/")).login("email", Password("password"))
      assertEquals(PolarisLoginResult.Unavailable("HTTP 307"), result)
      assertEquals(1, server.requestCount)
    }
  }

  @Test fun `captured expired membership response is access needed despite HTTP 200`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(checkNotNull(javaClass.getResource("/lyft-access-needed.json")).readText()))
      val session = PolarisSession(MemberId("member"), PolarisToken("token"))
      val result = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/")).unlock(session, checkNotNull(QrCode.parse("ABDRDO")))
      assertEquals(UnlockResult.AccessNeeded("Your membership has expired", "Please renew your membership or buy a pass from the menu"), result)
      val request = server.takeRequest()
      assertEquals("/v1/lbsbff/ride/mutation", request.path)
      assertEquals("token", request.getHeader("x-polaris-Authorization"))
      assertEquals(null, request.getHeader("Authorization"))
      assertTrue(request.body.readUtf8().contains("\"qr_code\":\"ABDRDO\""))
      assertEquals(1, server.requestCount)
    }
  }

  @Test fun `only an explicit success field accepts the unlock`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("{}"))
      server.enqueue(MockResponse().setBody("""{"success":{}}"""))
      val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"))
      val session = PolarisSession(MemberId("member"), PolarisToken("token"))
      val bike = checkNotNull(QrCode.parse("ABDRDO"))
      assertTrue(client.unlock(session, bike) is UnlockResult.Unavailable)
      assertEquals(UnlockResult.Accepted, client.unlock(session, bike))
    }
  }

  @Test fun `empty active ride response means no ride while malformed status is an error`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("{}"))
      server.enqueue(MockResponse().setBody("""{"ride_id":"ride"}"""))
      server.enqueue(MockResponse().setBody("""{"ride_id":"ride","ride_status":"active","start_date":"2026-11-17T18:00:00Z","concurrent_bikes_count":1}"""))
      val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"))
      val session = PolarisSession(MemberId("member"), PolarisToken("token"))
      assertEquals(RideResult.Success(null), client.activeRide(session))
      assertTrue(client.activeRide(session) is RideResult.Unavailable)
      val active = client.activeRide(session) as RideResult.Success
      assertEquals("ride", active.ride?.id?.value)
      assertEquals(Instant.parse("2026-11-17T18:00:00Z"), active.ride?.startedAt)
    }
  }

  @Test fun `an unauthorised session logs out even when the error body carries no message`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
      server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
      val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"))
      val session = PolarisSession(MemberId("member"), PolarisToken("token"))
      assertEquals(UnlockResult.LoggedOut, client.unlock(session, checkNotNull(QrCode.parse("ABDRDO"))))
      assertEquals(RideResult.LoggedOut, client.activeRide(session))
    }
  }

  @Test fun `a reservation without a started ride is no active ride`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"reservation_id":"res123"}"""))
      val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"))
      assertEquals(RideResult.Success(null), client.activeRide(PolarisSession(MemberId("member"), PolarisToken("token"))))
    }
  }

  @Test fun `invalid bike IDs never enter the domain`() {
    assertEquals(null, BikeId.parse(""))
    assertEquals(null, BikeId.parse("https://example.test/bike"))
    assertEquals(null, BikeId.parse("123a"))
    assertEquals("63243", BikeId.parse(" 63243 ")?.value)
  }


  @Test fun `gateway failure does not retry a potentially committed unlock`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(503))
      val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"))
      val result = client.unlock(PolarisSession(MemberId("member"), PolarisToken("token")), checkNotNull(QrCode.parse("ABDRDO")))
      assertTrue(result is UnlockResult.Unavailable)
      assertEquals(1, server.requestCount)
    }
  }


  @Test fun `QR unlock sends the scanned payload as the only mutation target without a station`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(checkNotNull(javaClass.getResource("/lyft-access-needed.json")).readText()))
      val session = PolarisSession(MemberId("member"), PolarisToken("token"))
      val result = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/")).unlock(session, checkNotNull(QrCode.parse("ABDRDO")))
      assertTrue(result is UnlockResult.AccessNeeded)
      val request = server.takeRequest()
      assertEquals("/v1/lbsbff/ride/mutation", request.path)
      assertEquals("token", request.getHeader("x-polaris-Authorization"))
      val body = Json.decodeFromString<QrBody>(request.body.readUtf8())
      assertEquals("ABDRDO", body.qr_code)
      assertEquals(1, body.mutation_type)
      java.util.UUID.fromString(body.mutation_session)
      assertTrue(body.retry_reasons.isEmpty())
      java.util.UUID.fromString(body.nonce)
      assertEquals(1, server.requestCount)
    }
  }

}
