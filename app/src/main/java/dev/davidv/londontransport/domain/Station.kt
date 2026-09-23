package dev.davidv.londontransport.domain

@JvmInline
value class StationId(val value: String)

data class BikeCounts(val standard: Int, val electric: Int) {
  val total: Int
    get() = standard + electric
}

enum class StationState {
  OPERATIONAL,
  LOCKED,
  NOT_INSTALLED,
}

/** Counts only ever come from a live fetch: a count read back from the cache would be stale. */
sealed interface StationStatus {
  data class Live(
    val bikes: BikeCounts,
    val emptyDocks: Int,
    val capacity: Int,
    val state: StationState,
  ) : StationStatus

  data object Unknown : StationStatus
}

data class Station(
  val id: StationId,
  val terminal: TerminalName,
  val name: String,
  val position: LatLon,
  val status: StationStatus,
)
