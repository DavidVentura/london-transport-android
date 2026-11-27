package dev.davidv.londontransport.ui

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.davidv.londontransport.santander.QrCode

private const val DEBUG_QR_CODE = "ABDRDO"

/** Reaches the website purchase without first running out of access, which takes a ride. */
@Composable
fun DebugMenu(onOpenPurchase: () -> Unit, qrUnlockEnabled: Boolean, onUnlockQr: (QrCode) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }) {
      Icon(Icons.Filled.MoreVert, contentDescription = "Debug")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      DropdownMenuItem(
        text = { Text("Unlock QR $DEBUG_QR_CODE") },
        enabled = qrUnlockEnabled,
        onClick = {
          open = false
          onUnlockQr(checkNotNull(QrCode.parse(DEBUG_QR_CODE)))
        },
      )
      DropdownMenuItem(text = { Text("Open API purchases") }, onClick = {
        open = false
        onOpenPurchase()
      })
      DropdownMenuItem(
        text = { Text("Forget website login") },
        onClick = {
          open = false
          CookieManager.getInstance().removeAllCookies(null)
          WebStorage.getInstance().deleteAllData()
        },
      )
    }
  }
}
