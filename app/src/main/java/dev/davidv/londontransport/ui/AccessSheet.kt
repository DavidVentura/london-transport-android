package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Santander Cycles' own alert, with its title and message kept as sent. */
@Composable
fun AccessSheet(flow: HireFlow.NeedsAccess, onBuy: () -> Unit, onClose: () -> Unit) {
  SheetBody(title = flow.title ?: "You need a pass to ride", subtitle = flow.message) {
    Spacer(Modifier.height(4.dp))
    ActionButtons(UiAction("Buy a pass", onClick = onBuy), UiAction("Not now", onClick = onClose))
  }
}
