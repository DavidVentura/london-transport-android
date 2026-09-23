package dev.davidv.londontransport.domain

@JvmInline
value class LayerId(val value: String)

@JvmInline
value class FeatureId(val value: String)

data class FeatureKey(val layer: LayerId, val feature: FeatureId) {
  fun encode(): String = "${layer.value}$SEPARATOR${feature.value}"

  companion object {
    private const val SEPARATOR = '/'

    fun decode(encoded: String): FeatureKey {
      val separator = encoded.indexOf(SEPARATOR)
      check(separator > 0) { "not a feature key: $encoded" }
      return FeatureKey(
        layer = LayerId(encoded.substring(0, separator)),
        feature = FeatureId(encoded.substring(separator + 1)),
      )
    }
  }
}

enum class AvailabilityLevel {
  UNKNOWN,
  NONE,
  LOW,
  MODERATE,
  PLENTY,
  UNAVAILABLE,
}

data class DetailRow(val label: String, val value: String)

sealed interface MarkerStyle {
  data object Dot : MarkerStyle

  data object Pin : MarkerStyle

  data class Stop(val line: LineId, val kind: TransportKind) : MarkerStyle
}

data class MapFeature(
  val key: FeatureKey,
  val position: LatLon,
  val title: String,
  val level: AvailabilityLevel,
  val badge: String,
  val summary: String,
  val details: List<DetailRow>,
  val marker: MarkerStyle = MarkerStyle.Dot,
  val hire: HireTarget? = null,
)
