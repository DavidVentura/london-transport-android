package dev.davidv.londontransport

import dev.davidv.londontransport.santander.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PolarisMutationTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test fun `captured list action reports the actual bike error and logs the response`() = runTest {
    val payload = checkNotNull(javaClass.getResource("/lyft-unrecognized-bike.json")).readText()
    val responses = mutableListOf<Pair<Int, String>>()
    val diagnostics = object : UnlockDiagnostics {
      override fun response(status: Int, payload: String) { responses += status to payload }
      override fun failure(reason: String) { fail(reason) }
    }
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(payload))
      val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"), diagnostics)
      val result = client.unlock(PolarisSession(MemberId("member"), PolarisToken("token")), checkNotNull(QrCode.parse("ABDRDO")))
      assertEquals(UnlockResult.Refused("The scanned QR code or vehicle ID you entered isn’t one we recognize."), result)
      assertEquals(listOf(200 to payload), responses)
      assertEquals(1, server.requestCount)
    }
  }

  @Test fun `nested lists preserve the pass purchase action behind telemetry`() {
    val payload = checkNotNull(javaClass.getResource("/lyft-access-needed.json")).readText()
    val original = json.decodeFromString<MutationResponse>(payload)
    val alert = checkNotNull(original.interstitial).action.single()
    val getPass = PolarisAction(getPass = EmptyMessage())
    val nestedPass = PolarisAction(list = PolarisActionList(listOf(PolarisAction(track = EmptyMessage()), getPass)))
    val nestedAlert = alert.copy(alert = checkNotNull(alert.alert).copy(actions = mapOf("buy" to nestedPass)))
    val actions = listOf(PolarisAction(track = EmptyMessage()),
      PolarisAction(list = PolarisActionList(listOf(PolarisAction(list = PolarisActionList(listOf(nestedAlert)))))))
    assertEquals(MutationOutcome.AccessNeeded("Your membership has expired", "Please renew your membership or buy a pass from the menu"), mutationResult(MutationResponse(interstitial = Interstitial(actions))))
  }

  @Test fun `unknown and conflicting actions cannot become a success or a partial alert`() {
    val alert = PolarisAction(alert = PolarisAlertAction(description = "Error"))
    val cases = listOf(
      emptyList(),
      listOf(PolarisAction(track = EmptyMessage())),
      listOf(PolarisAction(list = PolarisActionList(emptyList()))),
      listOf(PolarisAction(list = PolarisActionList(listOf(alert, PolarisAction())))),
      listOf(PolarisAction(alert = alert.alert, track = EmptyMessage())),
      listOf(PolarisAction(list = PolarisActionList(listOf(alert)), alert = alert.alert)),
    )
    cases.forEach { actions ->
      assertTrue(mutationResult(MutationResponse(interstitial = Interstitial(actions))) is MutationOutcome.Unavailable)
    }
  }

  @Test fun `an unlock decode failure is recorded after the original response`() = runTest {
    val events = mutableListOf<String>()
    val diagnostics = object : UnlockDiagnostics {
      override fun response(status: Int, payload: String) { events += "response:$status:$payload" }
      override fun failure(reason: String) { events += reason }
    }
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("{broken"))
      val client = PolarisClient(OkHttpClient(), DeviceId("device"), server.url("/"), diagnostics)
      assertTrue(client.unlock(PolarisSession(MemberId("member"), PolarisToken("token")), checkNotNull(QrCode.parse("ABDRDO"))) is UnlockResult.Unavailable)
      assertEquals(listOf("response:200:{broken", "Unlock response could not be decoded"), events)
      assertEquals(1, server.requestCount)
    }
  }
}
