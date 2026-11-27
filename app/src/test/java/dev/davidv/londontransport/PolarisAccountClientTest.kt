package dev.davidv.londontransport

import dev.davidv.londontransport.santander.*
import java.time.Duration
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PolarisAccountClientTest {
  private val session = PolarisSession(MemberId("member"), PolarisToken("secret-token"))
  private fun rental(id: String, start: Long = 1795283825335, end: Long = start + 7124, extra: String = ""): String =
    """{"rentalId":"$id","bike":{"displayedNumber":"51971"},"ss":"Start station","es":"End station","sd":$start,"ed":$end,"co":165$extra}"""
  private fun page(records: List<String>, member: String = "member"): String =
    """{"dataWarehouseAvailable":true,"rentals":{"$member":[${records.joinToString(",")}]}}"""

  @Test fun `profile uses raw authorization and preserves plan and renewal without inventing missing dates`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"member":{"id":"member","email":"rider@example.test","canPurchaseSubscription":false,"currentSubscription":{"type":{"id":"10017"}},"subscriptionRenewalConfiguration":{"enabled":false}}}"""))
      val result = PolarisAccountClient(OkHttpClient(), server.url("/")).profile(session) as AccountResult.Ready
      assertEquals(PlanId("10017"), result.value.plan?.id)
      assertNull(result.value.plan?.endsAt)
      assertEquals(false, result.value.autoRenew)
      val request = server.takeRequest()
      assertEquals("/hub/mobile/v1/ldn/account", request.path)
      assertEquals("secret-token", request.getHeader("Authorization"))
      assertNull(request.getHeader("x-polaris-Authorization"))
    }
  }

  @Test fun `history preserves the completed ride and follows the verified paging contract`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(page(listOf(rental("ride")))))
      val result = PolarisAccountClient(OkHttpClient(), server.url("/")).history(session, HistoryOffset(0)) as AccountResult.Ready
      val ride = result.value.rides.single()
      assertEquals(Duration.ofMillis(7124), ride.duration)
      assertEquals("51971", ride.bike.value)
      assertEquals("£1.65", ride.cost.label)
      assertNull(result.value.next)
      val url = checkNotNull(server.takeRequest().requestUrl)
      assertEquals("/hub/mobile/v1/ldn/rental/closed", url.encodedPath)
      assertEquals("member", url.queryParameter("memberId"))
      assertEquals("0", url.queryParameter("pageOffset"))
      assertEquals("50", url.queryParameter("pageSize"))
      assertEquals("1199142000", url.queryParameter("startDateMs"))
    }
  }

  @Test fun `a full page advances its offset and is sorted newest first`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(page((0 until 50).map { rental("ride-$it", 1795283825335 + it) })))
      val result = PolarisAccountClient(OkHttpClient(), server.url("/")).history(session, HistoryOffset(50)) as AccountResult.Ready
      assertEquals(HistoryOffset(100), result.value.next)
      assertEquals(RentalId("ride-49"), result.value.rides.first().id)
    }
  }

  @Test fun `history includes unwaived electric surcharge in recorded cost`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(page(listOf(rental("charged", extra = ",\"electricBikeSurchargeFee\":100"),
        rental("waived", extra = ",\"electricBikeSurchargeFee\":100,\"electricBikeSurchargeWaivedReason\":\"promotion\"")))))
      val rides = (PolarisAccountClient(OkHttpClient(), server.url("/")).history(session, HistoryOffset(0)) as AccountResult.Ready).value.rides
      assertEquals("£2.65", rides.single { it.id.value == "charged" }.cost.label)
      assertEquals("£1.65", rides.single { it.id.value == "waived" }.cost.label)
    }
  }

  @Test fun `malformed records duplicate rides and wrong-member pages fail instead of returning partial history`() = runTest {
    val cases = listOf(page(listOf(rental("valid"), rental("bad", end = 1))),
      page(listOf(rental("same"), rental("same"))), page(listOf(rental("other")), member = "someone-else"),
      """{"dataWarehouseAvailable":false,"rentals":{}}""")
    MockWebServer().use { server ->
      val client = PolarisAccountClient(OkHttpClient(), server.url("/"))
      cases.forEach { body ->
        server.enqueue(MockResponse().setBody(body))
        assertTrue(client.history(session, HistoryOffset(0)) is AccountResult.Unavailable)
      }
      server.enqueue(MockResponse().setResponseCode(401))
      assertEquals(AccountResult.LoggedOut, client.history(session, HistoryOffset(0)))
    }
  }

  @Test fun `empty history and short durations stay accurate`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"dataWarehouseAvailable":true,"rentals":{}}"""))
      assertEquals(AccountResult.Ready(HistoryPage(emptyList(), null)), PolarisAccountClient(OkHttpClient(), server.url("/")).history(session, HistoryOffset(0)))
    }
    assertEquals("under 1 min", formatRideDuration(Duration.ofMillis(7124)))
    assertEquals("8 min", formatRideDuration(Duration.ofSeconds(535)))
    assertEquals("1 h 5 min", formatRideDuration(Duration.ofMinutes(65)))
  }
}
