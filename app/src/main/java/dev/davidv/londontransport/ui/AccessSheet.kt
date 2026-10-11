package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.santander.AccessOption

private const val SANTANDER_PROFILE = "https://santandercycles.tfl.gov.uk/profile"

@Composable
fun AccessSheet(
  purchase: AccessPurchase,
  options: List<AccessOption>,
  opening: AccessOption?,
  onChoose: (AccessOption) -> Unit,
) {
  val uris = LocalUriHandler.current
  Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
    Text("No active subscription", style = MaterialTheme.typography.headlineSmall)
    if (purchase == AccessPurchase.WebsiteOnly) {
      Button(
        onClick = { uris.openUri(SANTANDER_PROFILE) },
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
      ) {
        Text("Subscribe on the website and try again")
      }
      return@Column
    }

    Text(
      "Pick an access period to pay for it by card.",
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

    TextButton(
      onClick = { uris.openUri(SANTANDER_PROFILE) },
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
      Text("Or buy one on the website")
    }
  }
}
