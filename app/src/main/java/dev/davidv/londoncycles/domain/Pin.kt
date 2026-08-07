package dev.davidv.londoncycles.domain

@JvmInline
value class PinId(val value: String)

/** A place the user marked by hand. Nothing fetches these; they only ever come back from disk. */
data class Pin(val id: PinId, val name: String, val position: LatLon)
