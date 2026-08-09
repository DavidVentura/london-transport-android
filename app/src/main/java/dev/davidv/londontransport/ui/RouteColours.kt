package dev.davidv.londontransport.ui

import androidx.compose.ui.graphics.Color
import dev.davidv.londontransport.domain.LineId
import dev.davidv.londontransport.domain.TransportKind
import kotlin.math.absoluteValue

private val BUS_COLOURS =
  listOf(
    Color(0xFF1565C0),
    Color(0xFF6A1B9A),
    Color(0xFF00838F),
    Color(0xFFC62828),
    Color(0xFF4527A0),
    Color(0xFF2E7D32),
  )

/** The line colours off the roundel, so a drawn tube line looks like the one on the map at Bank. */
private val TUBE_COLOURS =
  mapOf(
    "bakerloo" to Color(0xFFB36305),
    "central" to Color(0xFFE32017),
    "circle" to Color(0xFFFFD300),
    "district" to Color(0xFF00782A),
    "hammersmith-city" to Color(0xFFF3A9BB),
    "jubilee" to Color(0xFFA0A5A9),
    "metropolitan" to Color(0xFF9B0056),
    "northern" to Color(0xFF000000),
    "piccadilly" to Color(0xFF003688),
    "victoria" to Color(0xFF0098D4),
    "waterloo-city" to Color(0xFF95CDBA),
  )

private val TUBE_NAMES =
  mapOf(
    "bakerloo" to "Bakerloo",
    "central" to "Central",
    "circle" to "Circle",
    "district" to "District",
    "hammersmith-city" to "H'smith & City",
    "jubilee" to "Jubilee",
    "metropolitan" to "Metropolitan",
    "northern" to "Northern",
    "piccadilly" to "Piccadilly",
    "victoria" to "Victoria",
    "waterloo-city" to "Waterloo & City",
  )

/**
 * A bus route is its number, but a tube line's id is a slug. Kept here rather than taken from the
 * catalogue so a saved line still reads properly with no network.
 */
fun lineLabel(line: LineId, kind: TransportKind): String =
  when (kind) {
    TransportKind.TUBE -> TUBE_NAMES[line.value] ?: line.value
    TransportKind.BUS, TransportKind.BIKE -> line.value
  }

/** Tied to the line rather than to its position, so removing one does not recolour the rest. */
fun routeColour(line: LineId, kind: TransportKind): Color =
  when (kind) {
    TransportKind.TUBE -> TUBE_COLOURS[line.value] ?: hashed(line)
    TransportKind.BUS, TransportKind.BIKE -> hashed(line)
  }

private fun hashed(line: LineId): Color =
  BUS_COLOURS[line.value.hashCode().absoluteValue % BUS_COLOURS.size]
