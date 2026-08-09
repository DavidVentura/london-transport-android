package dev.davidv.londontransport

import dev.davidv.londontransport.data.LinesResult
import dev.davidv.londontransport.data.RouteResult
import dev.davidv.londontransport.data.TflLineClient
import dev.davidv.londontransport.domain.LatLon
import dev.davidv.londontransport.domain.LineId
import dev.davidv.londontransport.domain.RouteDirection
import dev.davidv.londontransport.domain.StopId
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
  fun `a line with no ordered points is simply not served`() = runTest {
    server.enqueue(MockResponse().setBody("""{"lineId":"bakerloo","lineStrings":[]}"""))

    assertEquals(
      RouteResult.NotServed,
      client().route(LineId("bakerloo"), RouteDirection.INBOUND),
    )
  }

  @Test
  fun `a branching line lists each shared station once`() = runTest {
    server.enqueue(
      MockResponse().setBody(
        """{"lineId":"northern","lineStrings":["[[[-0.1,51.5],[-0.11,51.51]]]"],
           "stopPointSequences":[
             {"stopPoint":[{"id":"940A","name":"Camden Town","lat":51.539,"lon":-0.142},
                           {"id":"940B","name":"Euston","lat":51.528,"lon":-0.133}]},
             {"stopPoint":[{"id":"940A","name":"Camden Town","lat":51.539,"lon":-0.142},
                           {"id":"940C","name":"Bank","lat":51.513,"lon":-0.089}]}]}"""
      )
    )

    val path = (client().route(LineId("northern"), RouteDirection.INBOUND) as RouteResult.Success)
      .path

    assertEquals(listOf("Camden Town", "Euston", "Bank"), path.stops.map { it.name })
  }

  @Test
  fun `only modes the map can draw reach the catalogue`() = runTest {
    server.enqueue(
      MockResponse().setBody(
        """[{"id":"victoria","name":"Victoria","modeName":"tube"},
            {"id":"43","name":"43","modeName":"bus"},
            {"id":"dlr","name":"DLR","modeName":"dlr"}]"""
      )
    )

    val lines = (client().lines() as LinesResult.Success).lines

    assertEquals(listOf("Victoria", "43"), lines.map { it.name })
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
