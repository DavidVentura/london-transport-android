package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun UnlockingSheet(flow: HireFlow.Unlocking) {
  Column(
    Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    CircularProgressIndicator()
    Text("Unlocking bike ${flow.bike.value}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
    Text(
      "Keep the app open. This usually takes a few seconds.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

@Composable
fun UnlockFailedSheet(flow: HireFlow.UnlockFailed, onScanAgain: () -> Unit, onClose: () -> Unit) {
  SheetBody(
    title = if (flow.bike == null) "Can't start a ride yet" else "Couldn't unlock bike ${flow.bike.value}",
  ) {
    InlineError(flow.message)
    Spacer(Modifier.height(24.dp))
    ActionButtons(UiAction("Scan again", onClick = onScanAgain), UiAction("Close", onClick = onClose))
  }
}
