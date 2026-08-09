package dev.davidv.londontransport

import dev.davidv.londontransport.domain.LineId
import dev.davidv.londontransport.domain.RouteDirection
import dev.davidv.londontransport.domain.SavedLine
import dev.davidv.londontransport.domain.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedLineTest {
  @Test
  fun `a line round-trips with its direction and visibility`() {
    val hidden = SavedLine(LineId("43"), TransportKind.BUS, RouteDirection.OUTBOUND, visible = false)
    val shown = SavedLine(LineId("N5"), TransportKind.BUS, RouteDirection.INBOUND, visible = true)

    assertEquals(hidden, SavedLine.decode(hidden.encode()))
    assertEquals(shown, SavedLine.decode(shown.encode()))
  }

  @Test
  fun `the two directions of one line encode apart`() {
    val inbound = SavedLine(LineId("N5"), TransportKind.BUS, RouteDirection.INBOUND, visible = true)
    val outbound = SavedLine(LineId("N5"), TransportKind.BUS, RouteDirection.OUTBOUND, visible = true)

    assertEquals(setOf("N5/INBOUND/SHOWN/BUS", "N5/OUTBOUND/SHOWN/BUS"),
      setOf(inbound.encode(), outbound.encode()))
  }

  @Test
  fun `a line kept before directions existed is an inbound bus, shown`() {
    val migrated = SavedLine.decode("43")

    assertEquals(LineId("43"), migrated.id)
    assertEquals(RouteDirection.INBOUND, migrated.direction)
    assertEquals(TransportKind.BUS, migrated.kind)
    assertTrue(migrated.visible)
  }

  @Test
  fun `a tube line round-trips as a tube line`() {
    val victoria = SavedLine(LineId("victoria"), TransportKind.TUBE, RouteDirection.INBOUND, true)

    assertEquals("victoria/INBOUND/SHOWN/TUBE", victoria.encode())
    assertEquals(victoria, SavedLine.decode(victoria.encode()))
  }

  @Test
  fun `a line kept before hiding existed keeps its direction and is a shown bus`() {
    val migrated = SavedLine.decode("43/OUTBOUND")

    assertEquals(RouteDirection.OUTBOUND, migrated.direction)
    assertEquals(TransportKind.BUS, migrated.kind)
    assertTrue(migrated.visible)
  }

  @Test
  fun `a line kept before the tube existed is still a bus`() {
    assertEquals(TransportKind.BUS, SavedLine.decode("43/INBOUND/HIDDEN").kind)
  }

  @Test
  fun `a hidden line stays hidden`() {
    assertFalse(SavedLine.decode("43/INBOUND/HIDDEN").visible)
  }
}
