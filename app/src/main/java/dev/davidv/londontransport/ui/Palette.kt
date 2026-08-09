package dev.davidv.londontransport.ui

import androidx.compose.ui.graphics.Color
import dev.davidv.londontransport.domain.AvailabilityLevel

/** Fixed rather than theme-derived: these read the same on the map in light and dark tiles. */
fun availabilityColour(level: AvailabilityLevel): Color =
  when (level) {
    AvailabilityLevel.UNKNOWN -> Color(0xFF90A4AE)
    AvailabilityLevel.NONE -> Color(0xFFD32F2F)
    AvailabilityLevel.LOW -> Color(0xFFEF6C00)
    AvailabilityLevel.MODERATE -> Color(0xFFF9A825)
    AvailabilityLevel.PLENTY -> Color(0xFF2E7D32)
    AvailabilityLevel.UNAVAILABLE -> Color(0xFF757575)
  }
