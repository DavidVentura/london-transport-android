package dev.davidv.londontransport.domain

@JvmInline
value class LineId(val value: String)

@JvmInline
value class StopId(val value: String)

enum class TransportKind {
  BIKE,
  BUS,
  TUBE,
}

data class TransitLine(val id: LineId, val name: String, val kind: TransportKind)

enum class BikeDisplay {
  NONE,
  FAVOURITES,
  ALL;

  fun next(): BikeDisplay = entries[(ordinal + 1) % entries.size]
}

enum class RouteDirection {
  INBOUND,
  OUTBOUND,
}

data class RouteStop(
  val id: StopId,
  val name: String,
  val position: LatLon,
  val letter: String?,
)

/**
 * TfL publishes the route it is running today, so a long-lived diversion arrives as an ordinary
 * path with nothing marking it as temporary. Comparing against the copy on disk is the only signal
 * that a route moved.
 */
data class RoutePath(
  val line: LineId,
  val direction: RouteDirection,
  val towards: String,
  val segments: List<List<LatLon>>,
  val stops: List<RouteStop>,
)

data class SavedLine(
  val id: LineId,
  val kind: TransportKind,
  val direction: RouteDirection,
  val visible: Boolean,
) {
  fun encode(): String =
    listOf(id.value, direction.name, if (visible) SHOWN else HIDDEN, kind.name)
      .joinToString(SEPARATOR)

  companion object {
    private const val SEPARATOR = "/"
    private const val SHOWN = "SHOWN"
    private const val HIDDEN = "HIDDEN"

    fun decode(encoded: String): SavedLine {
      val parts = encoded.split(SEPARATOR)
      check(parts[0].isNotEmpty()) { "not a saved line: $encoded" }
      // Shorter forms predate directions, hiding and the tube; all of them were buses drawn
      // inbound and always shown.
      return SavedLine(
        id = LineId(parts[0]),
        direction = parts.getOrNull(1)?.let(RouteDirection::valueOf) ?: RouteDirection.INBOUND,
        visible =
          when (val kept = parts.getOrNull(2)) {
            null, SHOWN -> true
            HIDDEN -> false
            else -> error("not a saved line: $kept")
          },
        kind = parts.getOrNull(3)?.let(TransportKind::valueOf) ?: TransportKind.BUS,
      )
    }
  }
}
