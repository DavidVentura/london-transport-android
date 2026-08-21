package dev.davidv.londontransport

import dev.davidv.londontransport.domain.AvailabilityLevel
import dev.davidv.londontransport.domain.BikeCounts
import dev.davidv.londontransport.domain.FeatureKey
import dev.davidv.londontransport.domain.LatLon
import dev.davidv.londontransport.domain.Station
import dev.davidv.londontransport.domain.StationId
import dev.davidv.londontransport.domain.StationState
import dev.davidv.londontransport.domain.StationStatus
import dev.davidv.londontransport.domain.TerminalName
import dev.davidv.londontransport.layers.stationFeature
import org.junit.Assert.assertEquals
import org.junit.Test

private fun station(
  standard: Int,
  electric: Int = 0,
  emptyDocks: Int = 5,
  state: StationState = StationState.OPERATIONAL,
) =
  Station(
    id = StationId("BikePoints_1"),
    terminal = TerminalName("001023"),
    name = "River Street, Clerkenwell",
    position = LatLon(51.5, -0.1),
    status =
      StationStatus.Live(
        bikes = BikeCounts(standard = standard, electric = electric),
        emptyDocks = emptyDocks,
        capacity = 20,
        state = state,
      ),
  )

class CycleFeaturesTest {
  @Test
  fun `availability buckets follow the bike count`() {
    assertEquals(AvailabilityLevel.NONE, stationFeature(station(0)).level)
    assertEquals(AvailabilityLevel.LOW, stationFeature(station(2)).level)
    assertEquals(AvailabilityLevel.MODERATE, stationFeature(station(5)).level)
    assertEquals(AvailabilityLevel.PLENTY, stationFeature(station(6)).level)
  }

  @Test
  fun `the badge counts bikes against the docks you can actually park in`() {
    assertEquals("5/5", stationFeature(station(3, 2)).badge)
    assertEquals("0/5", stationFeature(station(0)).badge)
    assertEquals("3/0", stationFeature(station(3, emptyDocks = 0)).badge)
  }

  @Test
  fun `a closed station reads as unavailable whatever it holds`() {
    val feature = stationFeature(station(standard = 12, state = StationState.LOCKED))

    assertEquals(AvailabilityLevel.UNAVAILABLE, feature.level)
    assertEquals("—", feature.badge)
    assertEquals("Closed", feature.summary)
  }

  @Test
  fun `summary counts electric bikes separately and pluralises`() {
    assertEquals("5 bikes (2 electric) · 5 free docks", stationFeature(station(3, 2)).summary)
    assertEquals("1 bike · 1 free dock", stationFeature(station(1, emptyDocks = 1)).summary)
  }

  @Test
  fun `feature keys round-trip through storage`() {
    val key = stationFeature(station(4)).key

    assertEquals(key, FeatureKey.decode(key.encode()))
  }
}
