package dev.davidv.londontransport

import dev.davidv.londontransport.domain.LatLon
import dev.davidv.londontransport.domain.boundsAround
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoBoundsTest {
  @Test
  fun `the box holds every point with room to spare`() {
    val bounds =
      boundsAround(listOf(LatLon(51.50, -0.20), LatLon(51.60, -0.10), LatLon(51.55, -0.15)), 0.10)!!

    assertEquals(51.49, bounds.south, 1e-9)
    assertEquals(51.61, bounds.north, 1e-9)
    assertEquals(-0.21, bounds.west, 1e-9)
    assertEquals(-0.09, bounds.east, 1e-9)
  }

  @Test
  fun `a lone point gives a box with no span, left to the map to zoom sanely`() {
    val bounds = boundsAround(listOf(LatLon(51.5, -0.1)), 0.18)!!

    assertEquals(51.5, bounds.south, 1e-9)
    assertEquals(51.5, bounds.north, 1e-9)
    assertEquals(-0.1, bounds.west, 1e-9)
    assertEquals(-0.1, bounds.east, 1e-9)
  }

  @Test
  fun `nothing to frame`() {
    assertNull(boundsAround(emptyList(), 0.18))
  }
}
