package dev.davidv.londoncycles

import dev.davidv.londoncycles.domain.LineId
import dev.davidv.londoncycles.domain.RouteDirection
import dev.davidv.londoncycles.domain.SavedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedLineTest {
  @Test
  fun `a line round-trips with its direction and visibility`() {
    val hidden = SavedLine(LineId("43"), RouteDirection.OUTBOUND, visible = false)
    val shown = SavedLine(LineId("N5"), RouteDirection.INBOUND, visible = true)

    assertEquals(hidden, SavedLine.decode(hidden.encode()))
    assertEquals(shown, SavedLine.decode(shown.encode()))
  }

  @Test
  fun `the two directions of one line encode apart`() {
    val inbound = SavedLine(LineId("N5"), RouteDirection.INBOUND, visible = true)
    val outbound = SavedLine(LineId("N5"), RouteDirection.OUTBOUND, visible = true)

    assertEquals(setOf("N5/INBOUND/SHOWN", "N5/OUTBOUND/SHOWN"),
      setOf(inbound.encode(), outbound.encode()))
  }

  @Test
  fun `a line kept before directions existed is inbound and shown`() {
    val migrated = SavedLine.decode("43")

    assertEquals(LineId("43"), migrated.id)
    assertEquals(RouteDirection.INBOUND, migrated.direction)
    assertTrue(migrated.visible)
  }

  @Test
  fun `a line kept before hiding existed keeps its direction and is shown`() {
    val migrated = SavedLine.decode("43/OUTBOUND")

    assertEquals(RouteDirection.OUTBOUND, migrated.direction)
    assertTrue(migrated.visible)
  }

  @Test
  fun `a hidden line stays hidden`() {
    assertFalse(SavedLine.decode("43/INBOUND/HIDDEN").visible)
  }
}
