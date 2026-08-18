package dev.davidv.londontransport.domain

import java.time.Instant

/** What the hire backend calls a dock: a padded number like "001023", not the BikePoint id. */
@JvmInline
value class TerminalName(val value: String)

@JvmInline
value class PointName(val value: String)

/** A dock addressed the way the hire calls address it. */
data class HireTarget(val terminal: TerminalName, val point: PointName)

/** The digits keyed into the dock point to let a bike out. */
@JvmInline
value class ReleaseCode(val value: String)

/**
 * A code that has been paid for and not yet used. It outlives the screen it was got on — the rider
 * walks to the dock with the phone in a pocket — so it is kept until the dock stops accepting it.
 */
data class ActiveHire(
  val station: FeatureKey,
  val stationName: String,
  val code: ReleaseCode,
  val expiresAt: Instant,
)
