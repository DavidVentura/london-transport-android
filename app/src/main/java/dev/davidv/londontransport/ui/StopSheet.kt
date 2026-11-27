package dev.davidv.londontransport.ui

import androidx.compose.runtime.Composable
import dev.davidv.londontransport.domain.MapFeature

@Composable
fun StopSheet(feature: MapFeature) {
  SheetBody(title = feature.title, subtitle = feature.summary) {
    feature.details.forEach { LabelledRow(it.label, it.value) }
  }
}
