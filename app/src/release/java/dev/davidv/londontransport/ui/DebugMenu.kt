package dev.davidv.londontransport.ui

import androidx.compose.runtime.Composable
import dev.davidv.londontransport.santander.QrCode

@Suppress("UNUSED_PARAMETER") @Composable fun DebugMenu(onOpenPurchase: () -> Unit, qrUnlockEnabled: Boolean, onUnlockQr: (QrCode) -> Unit) {}
