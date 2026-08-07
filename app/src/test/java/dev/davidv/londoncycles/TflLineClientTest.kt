package dev.davidv.londoncycles

import dev.davidv.londoncycles.data.RouteResult
import dev.davidv.londoncycles.data.TflLineClient
import dev.davidv.londoncycles.domain.LatLon
import dev.davidv.londoncycles.domain.LineId
import dev.davidv.londoncycles.domain.RouteDirection
import dev.davidv.londoncycles.domain.StopId
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shaped like the real /Line/43/Route/Sequence/inbound payload, cut down to two points. */
private const val SEQUENCE =
  """
  {
    "lineId": "43",
    "lineName": "43",
    "direction": "inbound",
    "isOutboundOnly": false,
    "lineStrings": ["[[[-0.086638,51.505129],[-0.153603,51.613049]]]"],
    "stopPointSequences": [
      {"stopPoint": [
        {"id": "490000139CZ", "name": "London Bridge Bus Station", "lat": 51.505129,
         "lon": -0.086638, "stopLetter": "CM"},
        {"id": "490009333F", "name": "Halliwick Park", "lat": 51.613049, "lon": -0.153603}
      ]}
    ]
  }
  """

class TflLineClientTest {
  private val server = MockWebServer()

  @After fun stop() = server.shutdown()

  private fun client() = TflLineClient(OkHttpClient(), server.url("").toString().trimEnd('/'))

  @Test
  fun `coordinates arrive as longitude-latitude and are read the right way round`() = runTest {
    server.enqueue(MockResponse().setBody(SEQUENCE))

    val path = (client().route(LineId("43"), RouteDirection.INBOUND) as RouteResult.Success).path

    assertEquals(LineId("43"), path.line)
    assertEquals("Halliwick Park", path.towards)
    assertEquals(listOf(LatLon(51.505129, -0.086638), LatLon(51.613049, -0.153603)),
      path.segments.single())
  }

  @Test
  fun `every stop on the line comes back, flag letter and all`() = runTest {
    server.enqueue(MockResponse().setBody(SEQUENCE))

    val path = (client().route(LineId("43"), RouteDirection.INBOUND) as RouteResult.Success).path

    assertEquals(listOf("London Bridge Bus Station", "Halliwick Park"), path.stops.map { it.name })
    assertEquals(StopId("490000139CZ"), path.stops.first().id)
    assertEquals("CM", path.stops.first().letter)
    assertEquals(null, path.stops.last().letter)
    assertEquals(LatLon(51.505129, -0.086638), path.stops.first().position)
  }

  @Test
  fun `the direction asked for reaches the url`() = runTest {
    server.enqueue(MockResponse().setBody(SEQUENCE))

    client().route(LineId("43"), RouteDirection.OUTBOUND)

    assertEquals("/Line/43/Route/Sequence/outbound", server.takeRequest().path)
  }

  @Test
  fun `a line with no geometry is not served this way`() = runTest {
    server.enqueue(MockResponse().setBody("""{"lineId":"U9","lineStrings":[]}"""))

    assertEquals(RouteResult.NotServed, client().route(LineId("U9"), RouteDirection.INBOUND))
  }

  @Test
  fun `an http error is reported, not parsed`() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))

    val result = client().route(LineId("999"), RouteDirection.INBOUND)

    assertTrue(result.toString(), result is RouteResult.Unreachable)
  }

  @Test
  fun `mangled geometry is unusable rather than a crash`() = runTest {
    server.enqueue(
      MockResponse().setBody(
        """{"lineId":"43","lineStrings":["[[[-0.08]]]"],
           "stopPointSequences":[{"stopPoint":[
             {"id":"1","name":"Somewhere","lat":51.5,"lon":-0.1}]}]}"""
      )
    )

    val result = client().route(LineId("43"), RouteDirection.INBOUND)

    assertTrue(result.toString(), result is RouteResult.Unusable)
  }
}
