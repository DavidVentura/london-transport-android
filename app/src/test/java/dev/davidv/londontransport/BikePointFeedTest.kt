package dev.davidv.londontransport

import dev.davidv.londontransport.data.parseBikePointFeed
import dev.davidv.londontransport.data.tidyName
import dev.davidv.londontransport.domain.StationId
import dev.davidv.londontransport.domain.StationState
import dev.davidv.londontransport.domain.StationStatus
import dev.davidv.londontransport.domain.TerminalName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun station(
  id: String,
  name: String = "Somewhere, London",
  properties: String =
    """
    {"key":"TerminalName","value":"001023"},
    {"key":"NbBikes","value":"9"},
    {"key":"NbStandardBikes","value":"7"},
    {"key":"NbEBikes","value":"2"},
    {"key":"NbEmptyDocks","value":"3"},
    {"key":"NbDocks","value":"14"},
    {"key":"Installed","value":"true"},
    {"key":"Locked","value":"false"}
    """,
) =
  """
  {
    "${'$'}type": "Tfl.Api.Presentation.Entities.Place",
    "id": "$id",
    "commonName": "$name",
    "placeType": "BikePoint",
    "lat": 51.529163,
    "lon": -0.10997,
    "additionalProperties": [$properties]
  }
  """

class BikePointFeedTest {
  @Test
  fun `parses a station into domain counts`() {
    val feed = parseBikePointFeed("[${station("BikePoints_1")}]")

    assertTrue(feed.malformed.isEmpty())
    val parsed = feed.stations.single()
    assertEquals(StationId("BikePoints_1"), parsed.id)
    assertEquals(TerminalName("001023"), parsed.terminal)
    assertEquals(51.529163, parsed.position.latitude, 1e-9)
    val status = parsed.status as StationStatus.Live
    assertEquals(7, status.bikes.standard)
    assertEquals(2, status.bikes.electric)
    assertEquals(9, status.bikes.total)
    assertEquals(3, status.emptyDocks)
    assertEquals(14, status.capacity)
    assertEquals(StationState.OPERATIONAL, status.state)
  }

  @Test
  fun `a station with no terminal name cannot be hired from, so it is dropped`() {
    val feed =
      parseBikePointFeed(
        "[${station("BikePoints_9", properties = """
          {"key":"NbStandardBikes","value":"7"},
          {"key":"NbEBikes","value":"2"},
          {"key":"NbEmptyDocks","value":"3"},
          {"key":"NbDocks","value":"14"},
          {"key":"Installed","value":"true"},
          {"key":"Locked","value":"false"}
          """)}]"
      )

    assertTrue(feed.stations.isEmpty())
    assertEquals("no TerminalName", feed.malformed.single().reason)
  }

  @Test
  fun `a station missing a count is dropped whole, the rest survive`() {
    val body =
      """
      [
        ${station("BikePoints_1")},
        ${
        station(
          "BikePoints_2",
          properties =
            """
            {"key":"NbStandardBikes","value":"7"},
            {"key":"NbEmptyDocks","value":"3"},
            {"key":"NbDocks","value":"14"},
            {"key":"Installed","value":"true"},
            {"key":"Locked","value":"false"}
            """,
        )
      }
      ]
      """

    val feed = parseBikePointFeed(body)

    assertEquals(listOf("BikePoints_1"), feed.stations.map { it.id.value })
    assertEquals("BikePoints_2", feed.malformed.single().id)
  }

  @Test
  fun `locked and uninstalled stations keep their state`() {
    val flags = { installed: String, locked: String ->
      """
      {"key":"TerminalName","value":"001023"},
      {"key":"NbStandardBikes","value":"0"},
      {"key":"NbEBikes","value":"0"},
      {"key":"NbEmptyDocks","value":"0"},
      {"key":"NbDocks","value":"20"},
      {"key":"Installed","value":"$installed"},
      {"key":"Locked","value":"$locked"}
      """
    }
    val body =
      "[${station("a", properties = flags("false", "false"))}," +
        "${station("b", properties = flags("true", "true"))}]"

    val states = parseBikePointFeed(body).stations.map { (it.status as StationStatus.Live).state }

    assertEquals(listOf(StationState.NOT_INSTALLED, StationState.LOCKED), states)
  }

  @Test
  fun `names lose TfL's stray spacing`() {
    assertEquals("River Street, Clerkenwell", tidyName("River Street , Clerkenwell"))
    assertEquals("Hyde Park Corner", tidyName("  Hyde  Park Corner "))
  }
}
