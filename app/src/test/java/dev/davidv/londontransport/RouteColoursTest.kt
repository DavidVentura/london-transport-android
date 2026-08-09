package dev.davidv.londontransport

import dev.davidv.londontransport.domain.LineId
import dev.davidv.londontransport.domain.TransportKind
import dev.davidv.londontransport.ui.lineLabel
import dev.davidv.londontransport.ui.routeColour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RouteColoursTest {
  @Test
  fun `a tube line wears its roundel colour`() {
    assertEquals(
      androidx.compose.ui.graphics.Color(0xFF0098D4),
      routeColour(LineId("victoria"), TransportKind.TUBE),
    )
  }

  @Test
  fun `a bus keeps a colour of its own even if it shares a tube line's id`() {
    val asBus = routeColour(LineId("victoria"), TransportKind.BUS)

    assertNotEquals(routeColour(LineId("victoria"), TransportKind.TUBE), asBus)
  }

  @Test
  fun `a colour follows the line, not its place in the list`() {
    assertEquals(routeColour(LineId("43"), TransportKind.BUS), routeColour(LineId("43"), TransportKind.BUS))
  }

  @Test
  fun `a tube line reads by name, a bus by its number`() {
    assertEquals("Victoria", lineLabel(LineId("victoria"), TransportKind.TUBE))
    assertEquals("43", lineLabel(LineId("43"), TransportKind.BUS))
  }
}
