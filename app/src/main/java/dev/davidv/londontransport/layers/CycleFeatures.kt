package dev.davidv.londontransport.layers

import dev.davidv.londontransport.domain.AvailabilityLevel
import dev.davidv.londontransport.domain.DetailRow
import dev.davidv.londontransport.domain.FeatureId
import dev.davidv.londontransport.domain.FeatureKey
import dev.davidv.londontransport.domain.HireTarget
import dev.davidv.londontransport.domain.LayerId
import dev.davidv.londontransport.domain.MapFeature
import dev.davidv.londontransport.domain.PointName
import dev.davidv.londontransport.domain.Station
import dev.davidv.londontransport.domain.StationState
import dev.davidv.londontransport.domain.StationStatus

val CYCLE_HIRE_LAYER = LayerId("santander-cycles")

fun stationFeature(station: Station): MapFeature =
  MapFeature(
    key = FeatureKey(CYCLE_HIRE_LAYER, FeatureId(station.id.value)),
    position = station.position,
    title = station.name,
    level = level(station.status),
    badge = badge(station.status),
    summary = summary(station.status),
    details = details(station.status),
    hire = hireTarget(station),
  )

/**
 * A dock that is locked or gone cannot hand out a bike, so it gets no button. A dock read back from
 * the cache still can: the counts are unknown, not the dock.
 */
private fun hireTarget(station: Station): HireTarget? {
  val status = station.status
  if (status is StationStatus.Live && status.state != StationState.OPERATIONAL) return null
  return HireTarget(terminal = station.terminal, point = PointName(station.name))
}

private fun level(status: StationStatus): AvailabilityLevel =
  when (status) {
    StationStatus.Unknown -> AvailabilityLevel.UNKNOWN
    is StationStatus.Live ->
      when {
        status.state != StationState.OPERATIONAL -> AvailabilityLevel.UNAVAILABLE
        status.bikes.total == 0 -> AvailabilityLevel.NONE
        status.bikes.total <= 2 -> AvailabilityLevel.LOW
        status.bikes.total <= 5 -> AvailabilityLevel.MODERATE
        else -> AvailabilityLevel.PLENTY
      }
  }

/**
 * Bikes over free docks, not over the whole stand: a broken dock is counted in the capacity but
 * cannot be parked at, so only the empty count answers "can I leave a bike here".
 */
private fun badge(status: StationStatus): String =
  when (status) {
    StationStatus.Unknown -> "?"
    is StationStatus.Live ->
      if (status.state == StationState.OPERATIONAL) "${status.bikes.total}/${status.emptyDocks}"
      else "—"
  }

private fun summary(status: StationStatus): String =
  when (status) {
    StationStatus.Unknown -> "Bikes unknown — saved from an earlier visit"
    is StationStatus.Live ->
      when (status.state) {
        StationState.NOT_INSTALLED -> "Not installed"
        StationState.LOCKED -> "Closed"
        StationState.OPERATIONAL ->
          buildString {
            append(plural(status.bikes.total, "bike"))
            if (status.bikes.electric > 0) append(" (${status.bikes.electric} electric)")
            append(" · ")
            append(plural(status.emptyDocks, "free dock"))
          }
      }
  }

private fun details(status: StationStatus): List<DetailRow> =
  when (status) {
    StationStatus.Unknown -> listOf(DetailRow("Status", "Not checked since you were last online"))
    is StationStatus.Live ->
      listOf(
        DetailRow("Standard bikes", status.bikes.standard.toString()),
        DetailRow("Electric bikes", status.bikes.electric.toString()),
        DetailRow("Free docks", status.emptyDocks.toString()),
        DetailRow("Total docks", status.capacity.toString()),
        DetailRow(
          "Status",
          when (status.state) {
            StationState.OPERATIONAL -> "In service"
            StationState.LOCKED -> "Closed"
            StationState.NOT_INSTALLED -> "Not installed"
          },
        ),
      )
  }

private fun plural(count: Int, noun: String): String =
  if (count == 1) "1 $noun" else "$count ${noun}s"
