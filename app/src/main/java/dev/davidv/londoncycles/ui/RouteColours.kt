package dev.davidv.londoncycles.ui

import androidx.compose.ui.graphics.Color
import dev.davidv.londoncycles.domain.LineId
import kotlin.math.absoluteValue

private val ROUTE_COLOURS =
  listOf(
    Color(0xFF1565C0),
    Color(0xFF6A1B9A),
    Color(0xFF00838F),
    Color(0xFFC62828),
    Color(0xFF4527A0),
    Color(0xFF2E7D32),
  )

/** Tied to the line rather than to its position, so removing one does not recolour the rest. */
fun routeColour(line: LineId): Color =
  ROUTE_COLOURS[line.value.hashCode().absoluteValue % ROUTE_COLOURS.size]
