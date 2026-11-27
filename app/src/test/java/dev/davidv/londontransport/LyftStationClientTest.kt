package dev.davidv.londontransport

import dev.davidv.londontransport.data.FeedResult
import dev.davidv.londontransport.data.LyftStationClient
import dev.davidv.londontransport.data.parseLyftInventory
import dev.davidv.londontransport.domain.StationStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyftStationClientTest {
  private fun inventory(): String = checkNotNull(javaClass.getResource("/lyft-inventory.json")).readText()

  @Test fun `live inventory preserves favourite IDs and separates standard and electric bikes`() {
    val feed = parseLyftInventory(inventory())
    assertEquals(2, feed.stations.size)
    assertTrue(feed.malformed.isEmpty())
    val station = feed.stations.first()
    assertEquals("BikePoints_311", station.id.value)
    assertEquals("Foley Street, Fitzrovia", station.name)
    assertEquals(51.51918144, station.position.latitude, 0.00000001)
    assertEquals(-0.140485596, station.position.longitude, 0.00000001)
    val live = station.status as StationStatus.Live
    assertEquals(1, live.bikes.standard)
    assertEquals(1, live.bikes.electric)
    assertEquals(22, live.emptyDocks)
  }

  @Test fun `a record with impossible counts is rejected as a whole`() {
    val feed = parseLyftInventory(inventory().replace("bikes_available\\\":1", "bikes_available\\\":-1"))
    assertEquals(1, feed.stations.size)
    assertEquals(1, feed.malformed.size)
  }

  @Test fun `non-dock map items are ignored rather than counted as malformed`() {
    val body = """{"map_inventory_json":"{\"type\":\"FeatureCollection\",\"features\":[""" +
      """{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-0.14,51.5]},\"properties\":{\"bikes_available\":1,\"ebikes_available\":0,\"docks_available\":5,\"is_offline\":false,\"map_item_id\":\"311\",\"map_item_type\":1,\"search_result_representation\":{\"title\":\"A Dock\"}}},""" +
      """{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-0.14,51.5]},\"properties\":{\"bikes_available\":0,\"ebikes_available\":2,\"docks_available\":0,\"is_offline\":false,\"map_item_id\":\"float1\",\"map_item_type\":2,\"search_result_representation\":{\"title\":\"Free-floating ebike\"}}}""" +
      """]}"}"""
    val feed = parseLyftInventory(body)
    assertEquals(1, feed.stations.size)
    assertEquals("BikePoints_311", feed.stations.first().id.value)
    assertTrue(feed.malformed.isEmpty())
  }

  @Test(expected = SerializationException::class)
  fun `unexpected inventory envelopes fail at the boundary`() {
    parseLyftInventory("""{"map_inventory_json":"{\"type\":\"other\",\"features\":[]}"}""")
  }

  @Test fun `fetch follows the captured JSON protocol`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(inventory()))
      val result = LyftStationClient(OkHttpClient(), server.url("/v1/lbsbff/map/inventory").toString()).fetch()
      assertTrue(result is FeedResult.Success)
      val request = server.takeRequest()
      assertEquals("POST", request.method)
      assertEquals("com.lyft.android.londonapp:android:15:2026.36.38.1793020363", request.getHeader("User-Agent"))
      assertEquals("{}", request.body.readUtf8())
      assertEquals("application/json", request.getHeader("Accept"))
      assertEquals(
        "application/json;messageType=pb.api.endpoints.v1.lbs_bff.ReadMapInventoryRequest; charset=utf-8",
        request.getHeader("Content-Type"),
      )
    }
  }

  @Test fun `server failure cannot become an empty station map`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(503))
      assertTrue(LyftStationClient(OkHttpClient(), server.url("/").toString()).fetch() is FeedResult.Unreachable)
    }
  }
}
