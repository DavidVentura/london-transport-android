package dev.davidv.londontransport.domain

import java.time.Instant

/** What the hire backend calls a dock: a padded number like "001023", not the BikePoint id. */
@JvmInline
value class TerminalName(val value: String)

@JvmInline
value class PointName(val value: String)

data class HireTarget(val terminal: TerminalName, val point: PointName)

@JvmInline
value class ReleaseCode(val value: String)

data class ActiveHire(
  val station: FeatureKey,
  val stationName: String,
  val code: ReleaseCode,
  val expiresAt: Instant,
)
