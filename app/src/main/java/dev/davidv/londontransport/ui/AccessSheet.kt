package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.santander.AccessOption
import dev.davidv.londontransport.santander.WebsitePass

@Composable
fun AccessSheet(
  purchase: AccessPurchase,
  options: List<AccessOption>,
  opening: AccessOption?,
  onChoose: (AccessOption) -> Unit,
  onBuyOnWebsite: (WebsitePass) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
    Text("No active subscription", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Buy one on Santander Cycles, then tap Rent bike again.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    WebsitePass.entries.forEach { pass ->
      OutlinedButton(
        onClick = { onBuyOnWebsite(pass) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      ) {
        Text(pass.label)
      }
    }
    if (purchase == AccessPurchase.WebsiteOnly) return@Column

    Text(
      "Or pay by card in the app.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    options.forEach { option ->
      OutlinedButton(
        onClick = { onChoose(option) },
        enabled = opening == null,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(option.name, modifier = Modifier.weight(1f))
          if (option == opening) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
          } else {
            Text(option.price)
          }
        }
      }
    }
  }
}
