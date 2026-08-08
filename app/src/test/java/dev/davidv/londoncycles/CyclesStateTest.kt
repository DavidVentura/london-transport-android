package dev.davidv.londoncycles

import dev.davidv.londoncycles.domain.AvailabilityLevel
import dev.davidv.londoncycles.domain.FeatureId
import dev.davidv.londoncycles.domain.FeatureKey
import dev.davidv.londoncycles.domain.LatLon
import dev.davidv.londoncycles.domain.MapFeature
import dev.davidv.londoncycles.domain.MarkerStyle
import dev.davidv.londoncycles.domain.Pin
import dev.davidv.londoncycles.domain.PinId
import dev.davidv.londoncycles.layers.CYCLE_HIRE_LAYER
import dev.davidv.londoncycles.layers.pinFeature
import dev.davidv.londoncycles.layers.pinIdOf
import dev.davidv.londoncycles.domain.BikeDisplay
import dev.davidv.londoncycles.ui.CyclesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val pin = Pin(PinId("p1"), "Home", LatLon(51.5, -0.1))

private fun key(id: String) = FeatureKey(CYCLE_HIRE_LAYER, FeatureId(id))

private fun station(name: String) =
  MapFeature(
    key = key(name),
    position = LatLon(51.5, -0.1),
    title = name,
    level = AvailabilityLevel.PLENTY,
    badge = "9",
    summary = "9 bikes · 3 free docks",
    details = emptyList(),
  )

private fun state(vararg starred: String) =
  CyclesState(
    layerTitle = "Santander Cycles",
    layerFeatures = listOf(station("Alpha"), station("Bravo"), station("Charlie")),
    pinFeatures = listOf(pinFeature(pin)),
    favourites = starred.map(::key).toSet(),
  )

class CyclesStateTest {
  @Test
  fun `the favourites setting keeps hand-placed pins on the map`() {
    val filtered = state("Bravo").copy(bikeDisplay = BikeDisplay.FAVOURITES)

    assertEquals(listOf("Bravo", "Home"), filtered.visibleFeatures.map { it.title })
  }

  @Test
  fun `switching docking stations off leaves the pins`() {
    val off = state("Bravo").copy(bikeDisplay = BikeDisplay.NONE)

    assertEquals(listOf("Home"), off.visibleFeatures.map { it.title })
  }

  @Test
  fun `the bike button cycles off, favourites, all`() {
    assertEquals(BikeDisplay.FAVOURITES, BikeDisplay.NONE.next())
    assertEquals(BikeDisplay.ALL, BikeDisplay.FAVOURITES.next())
    assertEquals(BikeDisplay.NONE, BikeDisplay.ALL.next())
  }

  @Test
  fun `unfiltered, everything is visible`() {
    assertEquals(4, state("Bravo").visibleFeatures.size)
  }

  @Test
  fun `the favourites list ignores pins`() {
    val favourites = state("Bravo", "Charlie").favouriteFeatures

    assertEquals(listOf("Bravo", "Charlie"), favourites.map { it.title })
  }

  @Test
  fun `a pin feature carries its id back for deletion`() {
    val feature = pinFeature(pin)

    assertEquals(MarkerStyle.Pin, feature.marker)
    assertEquals(PinId("p1"), pinIdOf(feature.key))
    assertTrue(feature.summary.startsWith("51.50000"))
  }
}
