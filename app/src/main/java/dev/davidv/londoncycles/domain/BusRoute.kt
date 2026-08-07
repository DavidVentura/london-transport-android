package dev.davidv.londoncycles.domain

@JvmInline
value class LineId(val value: String)

@JvmInline
value class StopId(val value: String)

data class BusLine(val id: LineId, val name: String)

enum class RouteDirection {
  INBOUND,
  OUTBOUND,
}

data class RouteStop(
  val id: StopId,
  val name: String,
  val position: LatLon,
  /** The letter on the flag at the kerb, which is how you find the stop. Not every stop has one. */
  val letter: String?,
)

/**
 * TfL publishes the route it is running today, so a long-lived diversion arrives here as an
 * ordinary path with nothing marking it as temporary. Comparing against the copy on disk is the
 * only signal that a route moved.
 */
data class RoutePath(
  val line: LineId,
  val direction: RouteDirection,
  val towards: String,
  val segments: List<List<LatLon>>,
  val stops: List<RouteStop>,
)

/** A line the user keeps: which way round they care about, and whether it is on the map. */
data class SavedLine(
  val id: LineId,
  val direction: RouteDirection,
  val visible: Boolean,
) {
  fun encode(): String =
    listOf(id.value, direction.name, if (visible) SHOWN else HIDDEN).joinToString(SEPARATOR)

  companion object {
    private const val SEPARATOR = "/"
    private const val SHOWN = "SHOWN"
    private const val HIDDEN = "HIDDEN"

    fun decode(encoded: String): SavedLine {
      val parts = encoded.split(SEPARATOR)
      check(parts[0].isNotEmpty()) { "not a saved line: $encoded" }
      // Shorter forms were written before lines carried a direction, and before they could be
      // hidden; both were drawn inbound and always shown.
      return SavedLine(
        id = LineId(parts[0]),
        direction = parts.getOrNull(1)?.let(RouteDirection::valueOf) ?: RouteDirection.INBOUND,
        visible =
          when (val kept = parts.getOrNull(2)) {
            null, SHOWN -> true
            HIDDEN -> false
            else -> error("not a saved line: $kept")
          },
      )
    }
  }
}
