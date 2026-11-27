package dev.davidv.londontransport.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Every step of logging in and unlocking is a bottom sheet over the map, apart from the camera.
 * [HireFlow.UnlockAccepted] is not drawn here: it is announced by the caller's snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HireFlowHost(flow: HireFlow, actions: HireActions, onBuyPass: () -> Unit) {
  val sheet: (@Composable () -> Unit)? = when (flow) {
    HireFlow.Idle, HireFlow.AccountReady, HireFlow.UnlockAccepted -> null
    HireFlow.Scanning -> {
      QrScannerDialog(onScanned = actions::unlock, onDismiss = actions::dismiss)
      null
    }
    is HireFlow.Login -> { { LoginSheet(flow, actions::submitLogin) } }
    is HireFlow.Verify -> { { VerifySheet(flow, actions::submitVerification, actions::resendCode) } }
    is HireFlow.Unlocking -> { { UnlockingSheet(flow) } }
    is HireFlow.UnlockFailed -> { { UnlockFailedSheet(flow, onScanAgain = actions::scan, onClose = actions::dismiss) } }
    is HireFlow.Tutorial -> { { RideTutorialSheet(flow.challenge.tutorial, flow.busy, flow.error, actions::continueTutorial) } }
    is HireFlow.NeedsAccess -> { { AccessSheet(flow, onBuy = onBuyPass, onClose = actions::dismiss) } }
  }
  // A swipe must not hide a sheet whose request is still in flight: the flow would stay behind it.
  val locked by rememberUpdatedState(flow is HireFlow.Unlocking || (flow as? HireFlow.Tutorial)?.busy == true)
  val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !locked })
  if (sheet == null) return
  ModalBottomSheet(onDismissRequest = actions::dismiss, sheetState = state) {
    sheet()
  }
}
