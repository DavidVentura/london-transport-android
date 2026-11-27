package dev.davidv.londontransport.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import dev.davidv.londontransport.data.PaymentVault
import dev.davidv.londontransport.santander.PaymentSchedule

@Composable
fun ApiPurchaseScreen(
  state: PurchaseState,
  model: PurchaseViewModel,
  vault: PaymentVault,
  onDone: () -> Unit,
  onScan: () -> Unit,
  onOpenAccount: () -> Unit,
) {
  val activity = checkNotNull(LocalActivity.current) as FragmentActivity
  val unlocker = remember(activity, vault) { AndroidPaymentUnlocker(activity, vault) }
  DisposableEffect(activity) {
    val wasSecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    onDispose { if (!wasSecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
  }
  val flow = state.flow
  val busy = flow == PurchaseFlow.Preparing || flow is PurchaseFlow.Completing || (flow as? PurchaseFlow.Card)?.busy == true
  val title = when (flow) {
    is PurchaseFlow.Plans -> "Choose a pass"
    is PurchaseFlow.Review -> flow.plan.name
    is PurchaseFlow.Card -> "Card details"
    is PurchaseFlow.Paying -> "Secure checkout"
    else -> "Buy a pass"
  }
  FlowScreen(title = title, onBack = model::back, backEnabled = !busy) { padding ->
    val body = Modifier.fillMaxSize().padding(padding)
    when (flow) {
      PurchaseFlow.Closed -> Unit
      PurchaseFlow.Loading -> CenteredProgress("Loading passes and prices…", body)
      PurchaseFlow.Preparing -> CenteredProgress("Opening secure checkout…", body)
      is PurchaseFlow.Completing -> CenteredProgress("Confirming your ${flow.attempt.plan.name}…", body)
      is PurchaseFlow.Plans -> Plans(flow, model::choose, body)
      is PurchaseFlow.Review -> Review(flow, model, body)
      is PurchaseFlow.Card -> Box(body) {
        PaymentSheet(flow.form, flow.busy, flow.errors, flow.save, flow.savedAs, flow.draft,
          state.saved, unlocker, model::pay, model::edit, model::stopEditing, model::forget, model::forgetAll)
      }
      is PurchaseFlow.Paying -> CheckoutWebView(flow.checkout, model::returned, model::close, body)
      is PurchaseFlow.Completed -> StatusMessage(
        icon = Icons.Outlined.CheckCircle,
        tone = Tone.Positive,
        title = "You're all set",
        message = "${flow.attempt.plan.name} is active. Scan a bike at any dock to start riding.",
        primary = UiAction("Scan a bike", onClick = onScan),
        secondary = UiAction("Done", onClick = onDone),
        modifier = body,
      ) { flow.notice?.let { InlineError(it, Modifier.padding(top = 16.dp)) } }
      is PurchaseFlow.ConfirmationFailed -> StatusMessage(
        icon = Icons.Outlined.ErrorOutline,
        tone = Tone.Negative,
        title = "Payment not confirmed yet",
        message = flow.message,
        primary = UiAction("Retry confirmation", onClick = model::retryConfirmation),
        secondary = UiAction("Check my account", onClick = onOpenAccount),
        modifier = body,
      )
      is PurchaseFlow.Unavailable -> StatusMessage(
        icon = Icons.Outlined.Info,
        tone = Tone.Neutral,
        title = "You can't buy another pass right now",
        message = flow.message,
        primary = UiAction("Check my account", onClick = onOpenAccount),
        secondary = UiAction("Close", onClick = model::close),
        modifier = body,
      )
      is PurchaseFlow.Uncertain -> StatusMessage(
        icon = Icons.Outlined.ErrorOutline,
        tone = Tone.Negative,
        title = "Check your payment",
        message = flow.message,
        primary = UiAction("Check my account", onClick = onOpenAccount),
        secondary = UiAction("Close", onClick = model::close),
        modifier = body,
      )
      PurchaseFlow.Cancelled -> StatusMessage(
        icon = Icons.Outlined.Info,
        tone = Tone.Neutral,
        title = "Checkout cancelled",
        message = "Nothing was bought.",
        primary = UiAction("Back to passes", onClick = model::restart),
        secondary = UiAction("Close", onClick = model::close),
        modifier = body,
      )
      is PurchaseFlow.Failed -> StatusMessage(
        icon = Icons.Outlined.ErrorOutline,
        tone = Tone.Negative,
        title = "Purchase not completed",
        message = flow.message,
        primary = UiAction("Try again", onClick = model::restart),
        secondary = UiAction("Close", onClick = model::close),
        modifier = body,
      )
    }
  }
}

@Composable
private fun Plans(flow: PurchaseFlow.Plans, onChoose: (QuotedPlan) -> Unit, modifier: Modifier) {
  if (flow.plans.isEmpty()) {
    StatusMessage(Icons.Outlined.ConfirmationNumber, Tone.Neutral, "No passes available", "Santander Cycles isn't offering any passes on this account.", primary = null, modifier = modifier)
    return
  }
  LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    items(flow.plans, key = { "${it.plan.id.value}:${it.plan.schedule}" }) { choice ->
      OutlinedCard(onClick = { onChoose(choice) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(choice.plan.name, style = MaterialTheme.typography.titleMedium)
            scheduleLabel(choice.plan.schedule)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            choice.plan.subtitle?.let {
              Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
          }
          Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 16.dp)) {
            Text(choice.quote.dueNow.label, style = MaterialTheme.typography.titleLarge)
            if (choice.quote.total != choice.quote.dueNow) {
              Text("${choice.quote.total.label} total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
    }
  }
}

private fun scheduleLabel(schedule: PaymentSchedule): String? = when (schedule) {
  PaymentSchedule.Once -> null
  PaymentSchedule.ThreePayments -> "Paid in 3 instalments"
  PaymentSchedule.TwelveMonths -> "Paid monthly over 12 months"
}

@Composable
private fun Review(flow: PurchaseFlow.Review, model: PurchaseViewModel, modifier: Modifier) {
  Column(modifier) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
      Text(flow.quote.dueNow.label, style = MaterialTheme.typography.displaySmall)
      Text(
        if (flow.quote.total != flow.quote.dueNow) "due now · ${flow.quote.total.label} in total" else "for one bike",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      flow.plan.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp)) }
      flow.plan.terms?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
      }
      flow.plan.footer?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
      }
      Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Auto-renew", style = MaterialTheme.typography.bodyLarge)
          Text("Buy this pass again when it runs out", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = flow.renewable, onCheckedChange = model::setRenewable)
      }
    }
    Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
      ActionButtons(UiAction("Pay ${flow.quote.dueNow.label}", onClick = model::checkout))
      Text(
        "You'll enter your card on Santander Cycles' secure payment page.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        textAlign = TextAlign.Center,
      )
      DebugCardRoute(model::enterCard)
    }
  }
}
