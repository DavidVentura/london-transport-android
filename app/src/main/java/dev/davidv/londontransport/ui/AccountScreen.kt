package dev.davidv.londontransport.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.santander.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LONDON_ZONE = ZoneId.of("Europe/London")
private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(LONDON_ZONE)
private val RIDE_START = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(LONDON_ZONE)
private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(LONDON_ZONE)

@Composable
fun AccountMenu(signedIn: Boolean, onProfile: () -> Unit, onHistory: () -> Unit, onSignOut: () -> Unit, onSignIn: () -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { if (signedIn) expanded = true else onSignIn() }) {
      Icon(Icons.Outlined.PersonOutline, contentDescription = if (signedIn) "Account" else "Sign in")
    }
    DropdownMenu(expanded = expanded && signedIn, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(text = { Text("Account") }, onClick = { expanded = false; onProfile() })
      DropdownMenuItem(text = { Text("Ride history") }, onClick = { expanded = false; onHistory() })
      HorizontalDivider()
      DropdownMenuItem(text = { Text("Sign out") }, onClick = { expanded = false; onSignOut() })
    }
  }
}

@Composable
fun AccountScreen(state: AccountUiState, model: AccountViewModel, onBuyPass: () -> Unit) {
  val flow = state.flow
  FlowScreen(
    title = if (flow is AccountFlow.History) "Ride history" else "Account",
    onBack = model::close,
    backEnabled = !state.signingOut,
    actions = {
      IconButton(onClick = { if (flow is AccountFlow.History) model.openHistory() else model.openProfile() }, enabled = !state.signingOut) {
        Icon(Icons.Filled.Refresh, contentDescription = "Refresh account")
      }
    },
  ) { padding ->
    val body = Modifier.fillMaxSize().padding(padding)
    if (state.signingOut) {
      CenteredProgress("Signing out…", body)
      return@FlowScreen
    }
    when (flow) {
      AccountFlow.Closed -> Unit
      AccountFlow.LoadingProfile -> CenteredProgress("Loading your account…", body)
      is AccountFlow.Profile -> Profile(flow.overview, model, onBuyPass, body)
      is AccountFlow.History -> History(flow, model, body)
      is AccountFlow.Failed -> StatusMessage(
        icon = Icons.Outlined.ErrorOutline,
        tone = Tone.Negative,
        title = "Couldn't load your account",
        message = flow.message,
        primary = UiAction("Try again", onClick = model::openProfile),
        secondary = UiAction("Sign out", onClick = model::signOut),
        modifier = body,
      )
    }
  }
}

@Composable
private fun Profile(overview: AccountOverview, model: AccountViewModel, onBuyPass: () -> Unit, modifier: Modifier) {
  val profile = overview.profile
  Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
    Text("Current pass", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(overview.planName ?: "No pass", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    profile.plan?.startsAt?.let { LabelledRow("Starts", DATE_TIME.format(it)) }
    profile.plan?.endsAt?.let { LabelledRow("Ends", DATE_TIME.format(it)) }
    profile.autoRenew?.let { LabelledRow("Auto-renew", if (it) "On" else "Off") }
    LabelledRow("Email", profile.email)
    Spacer(Modifier.height(24.dp))
    if (profile.canBuyPass) {
      ActionButtons(UiAction("Buy a pass", onClick = onBuyPass))
    } else {
      Text(
        "Santander Cycles isn't offering another pass on this account right now.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Surface(onClick = model::openHistory, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
        Icon(Icons.Outlined.History, contentDescription = null)
        Text("Ride history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 16.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
      }
    }
    TextButton(
      onClick = model::signOut,
      colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
      modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) { Text("Sign out") }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun History(flow: AccountFlow.History, model: AccountViewModel, modifier: Modifier) {
  var selected by remember { mutableStateOf<CompletedRide?>(null) }
  if (flow.rides.isEmpty() && flow.loading) {
    CenteredProgress("Loading your rides…", modifier)
    return
  }
  if (flow.rides.isEmpty() && flow.error == null) {
    StatusMessage(Icons.Outlined.History, Tone.Neutral, "No rides yet", "Finished rides show up here.", primary = null, modifier = modifier)
    return
  }
  val list = rememberLazyListState()
  val atEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == list.layoutInfo.totalItemsCount - 1 } }
  LaunchedEffect(atEnd, flow.next, flow.error) {
    if (atEnd && flow.next != null && flow.error == null) model.loadMore()
  }
  LazyColumn(modifier, state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
    items(flow.rides, key = { it.id.value }) { ride ->
      ListItem(
        headlineContent = { Text("${ride.startStation} → ${ride.endStation}") },
        supportingContent = { Text("${RIDE_START.format(ride.startedAt)} · ${formatRideDuration(ride.duration)}") },
        trailingContent = { Text(ride.cost.label, style = MaterialTheme.typography.titleMedium) },
        modifier = Modifier.clickable { selected = ride },
      )
      HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
    item {
      Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        when {
          flow.loading -> CircularProgressIndicator()
          flow.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            InlineError(flow.error)
            TextButton(onClick = if (flow.next != null) model::loadMore else model::openHistory) { Text("Try again") }
          }
        }
      }
    }
  }
  selected?.let { ride ->
    ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
      SheetBody(title = "${ride.startStation} → ${ride.endStation}", subtitle = RIDE_START.format(ride.startedAt)) {
        LabelledRow("Duration", formatRideDuration(ride.duration))
        LabelledRow("Cost", ride.cost.label)
        LabelledRow("Started", TIME.format(ride.startedAt))
        LabelledRow("Ended", TIME.format(ride.endedAt))
        LabelledRow("Bike", ride.bike.value)
      }
    }
  }
}
