package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.santander.WebsitePass

@Composable
fun AccessSheet(onBuyOnWebsite: (WebsitePass) -> Unit, nativePurchase: @Composable () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
    Text("No active subscription", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Buy one on Santander Cycles, then tap Rent bike again.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    nativePurchase()
    WebsitePass.entries.forEach { pass ->
      OutlinedButton(
        onClick = { onBuyOnWebsite(pass) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      ) {
        Text(pass.label)
      }
    }
  }
}
