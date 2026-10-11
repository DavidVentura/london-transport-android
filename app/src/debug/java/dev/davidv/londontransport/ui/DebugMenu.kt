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
import dev.davidv.londontransport.santander.WebsitePass

/** Reaches the website purchase without first running out of access, which takes a ride. */
@Composable
fun DebugMenu(onOpenPurchase: (WebsitePass) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }) {
      Icon(Icons.Filled.MoreVert, contentDescription = "Debug")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      WebsitePass.entries.forEach { pass ->
        DropdownMenuItem(
          text = { Text("Open ${pass.label} purchase") },
          onClick = {
            open = false
            onOpenPurchase(pass)
          },
        )
      }
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
