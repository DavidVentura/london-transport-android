package dev.davidv.londontransport.domain

@JvmInline
value class PinId(val value: String)

data class Pin(val id: PinId, val name: String, val position: LatLon)
