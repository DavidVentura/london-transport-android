package dev.davidv.londontransport

import dev.davidv.londontransport.santander.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RideTutorialTest {
  private val json = Json { ignoreUnknownKeys = true }
  private fun fixture(): String = checkNotNull(javaClass.getResource("/lyft-pre-ride-tutorial.json")).readText()
  @Serializable private data class Retry(val interstitial_type: String, val reason: String)
  @Serializable private data class MutationRequest(
    val nonce: String, val mutation_session: String, val mutation_type: Int,
    val qr_code: String? = null, val retry_reasons: List<Retry>,
  )

  @Test fun `captured tutorial preserves all four pages and confirms only the advertised retry`() {
    val result = mutationResult(json.decodeFromString<MutationResponse>(fixture())) as MutationOutcome.Tutorial
    assertEquals(listOf("Be safe, always", "Pay as you ride", "Dock at a station to end your ride", "Check for a green indicator after docking"), result.tutorial.pages.map { it.title })
    assertEquals("Look for the green indicator and listen for the chime once your bike is docked. Check the app to confirm the trip has closed.", result.tutorial.pages.last().body)
  }

  @Test fun `other screens and altered continuation actions cannot be acknowledged as this tutorial`() {
    val response = json.decodeFromString<MutationResponse>(fixture())
    val action = checkNotNull(checkNotNull(response.interstitial).action.single().fullScreen)
    val changedReason = action.copy(actions = mapOf("continue-unlock" to PolarisAction(retry = TutorialRetryAction(TutorialRetryReason("OTHER", "skip")))))
    val noPages = action.copy(screen = action.screen.copy(content = TutorialNode()))
    val unknownScreen = action.copy(screen = action.screen.copy(id = "payment_or_terms_screen"))
    val badNavigation = action.copy(actions = emptyMap())
    listOf(changedReason, noPages, unknownScreen, badNavigation).forEach { screen ->
      assertTrue(parseRideTutorial(screen) is MutationOutcome.Unavailable)
    }
  }

  @Test fun `confirming the tutorial preserves target and session and adds a fresh nonce and retry reason`() = runTest {
    for (bike in listOf(checkNotNull(QrCode.parse("ABDRDO")))) {
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody(fixture()))
        server.enqueue(MockResponse().setBody("""{"success":{}}"""))
        val session = PolarisSession(MemberId("member"), PolarisToken("token"))
        val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"))
        val tutorial = client.unlock(session, bike) as UnlockResult.TutorialRequired
        assertEquals(1, server.requestCount)
        assertFalse(tutorial.continuation.toString().contains(bike.value))
        assertEquals(UnlockResult.Accepted, client.continueUnlock(session, tutorial.continuation))
        val first = json.decodeFromString<MutationRequest>(server.takeRequest().body.readUtf8())
        val second = json.decodeFromString<MutationRequest>(server.takeRequest().body.readUtf8())
        UUID.fromString(first.mutation_session)
        UUID.fromString(first.nonce)
        UUID.fromString(second.nonce)
        assertEquals(first.mutation_session, second.mutation_session)
        assertNotEquals(first.nonce, second.nonce)
        assertEquals(first.qr_code, second.qr_code)
        assertEquals(1, second.mutation_type)
        assertTrue(first.retry_reasons.isEmpty())
        assertEquals(listOf(Retry("INTERSTITIAL_POLARIS_PRE_RIDE_TUTORIAL", "frt-completed")), second.retry_reasons)
        assertEquals(2, server.requestCount)
      }
    }
  }

  @Test fun `continuation refuses a different account without making another request`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(fixture()))
      val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"))
      val tutorial = client.unlock(PolarisSession(MemberId("member"), PolarisToken("token")), checkNotNull(QrCode.parse("ABDRDO"))) as UnlockResult.TutorialRequired
      assertTrue(client.continueUnlock(PolarisSession(MemberId("other"), PolarisToken("other-token")), tutorial.continuation) is UnlockResult.Unavailable)
      assertEquals(1, server.requestCount)
    }
  }
}
