package dev.davidv.londontransport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.davidv.londontransport.data.PaymentVault
import dev.davidv.londontransport.domain.MapFeature
import dev.davidv.londontransport.layers.BUS_STOP_LAYER
import dev.davidv.londontransport.layers.PIN_LAYER
import dev.davidv.londontransport.domain.TransportKind
import dev.davidv.londontransport.map.DrawnRoute
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val STALE_AFTER: Duration = Duration.ofSeconds(60)
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private enum class Tab(val label: String, val icon: ImageVector) {
  MAP("Map", Icons.Outlined.Map),
  FAVOURITES("Favourites", Icons.Filled.Star),
  LINES("Lines", Icons.Outlined.DirectionsBus),
}

private fun subtitle(state: CyclesState): String? =
  when {
    state.offline -> "Offline · saved positions, no counts"
    state.fetchedAt != null ->
      "Updated ${CLOCK.format(state.fetchedAt.atZone(ZoneId.systemDefault()))}"
    else -> null
  }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CyclesApp(
  viewModel: CyclesViewModel,
  routesViewModel: RoutesViewModel,
  hireViewModel: HireViewModel,
  purchaseViewModel: PurchaseViewModel,
  paymentVault: PaymentVault,
  accountViewModel: AccountViewModel,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val routes by routesViewModel.state.collectAsStateWithLifecycle()
  val hire by hireViewModel.state.collectAsStateWithLifecycle()
  var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
  var picking by rememberSaveable { mutableStateOf(false) }
  val account by accountViewModel.state.collectAsStateWithLifecycle()

  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(hire.flow) {
    when (hire.flow) {
      HireFlow.AccountReady -> {
        hireViewModel.dismiss()
        accountViewModel.openProfile()
      }
      HireFlow.UnlockAccepted -> {
        hireViewModel.dismiss()
        snackbar.showSnackbar("Unlock requested. Pull the bike from the dock once it releases.")
      }
      else -> Unit
    }
  }

  if (account.flow != AccountFlow.Closed) {
    AccountScreen(account, accountViewModel) {
      accountViewModel.close()
      purchaseViewModel.open()
    }
    return
  }

  val purchase by purchaseViewModel.state.collectAsStateWithLifecycle()
  if (purchase.flow != PurchaseFlow.Closed) {
    ApiPurchaseScreen(
      state = purchase,
      model = purchaseViewModel,
      vault = paymentVault,
      onDone = purchaseViewModel::close,
      onScan = {
        purchaseViewModel.close()
        hireViewModel.scan()
      },
      onOpenAccount = {
        purchaseViewModel.close()
        accountViewModel.openProfile()
      },
    )
    return
  }

  BackHandler(enabled = tab != Tab.MAP) { tab = Tab.MAP }

  val shownKinds = TransportKind.entries.filter(state::shows).toSet()
  val drawn =
    routes.drawable(shownKinds).mapNotNull { route ->
      route.path?.let { DrawnRoute(it, routeColour(route.line, route.saved.kind).toArgb()) }
    }
  val features = state.visibleFeatures + routes.stopFeatures(shownKinds)
  val selected = features.firstOrNull { it.key == state.selected }

  LifecycleResumeEffect(Unit) {
    viewModel.refreshIfOlderThan(STALE_AFTER)
    hireViewModel.refreshRide()
    onPauseOrDispose {}
  }

  Scaffold(
    topBar = {
      Column {
        TopAppBar(
          title = {
            Column {
              Text("London Transport", maxLines = 1, overflow = TextOverflow.Ellipsis)
              subtitle(state)?.let { line ->
                Text(line, style = MaterialTheme.typography.labelSmall)
              }
            }
          },
          actions = {
            ViewPreferencesMenu(
              bikeDisplay = state.bikeDisplay,
              shownKinds = shownKinds,
              onBikeDisplay = viewModel::setBikeDisplay,
              onToggleKind = viewModel::toggleKind,
            )
            AccountMenu(
              signedIn = !hire.needsLogin,
              onProfile = { viewModel.select(null); accountViewModel.openProfile() },
              onHistory = { viewModel.select(null); accountViewModel.openHistory() },
              onSignOut = { hireViewModel.dismiss(); accountViewModel.signOut() },
              onSignIn = { viewModel.select(null); hireViewModel.signIn() },
            )
            if (state.refreshing) {
              CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp))
            } else {
              IconButton(
                onClick = {
                  viewModel.refresh()
                  routesViewModel.refreshNow()
                  hireViewModel.refreshRide()
                }
              ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
              }
            }
            DebugMenu(
              onOpenPurchase = purchaseViewModel::open,
              qrUnlockEnabled = !hire.needsLogin && hire.ride is RideState.Known && hire.active == null,
              onUnlockQr = hireViewModel::unlock,
            )
          },
        )
        hire.active?.let { active -> ActiveRideBar(ride = active, onRefresh = hireViewModel::refreshRide) }
        hire.rideError?.let { message ->
          val action =
            if (hire.ride is RideState.LoggedOut) UiAction("Log in", onClick = hireViewModel::signIn)
            else UiAction("Retry", onClick = hireViewModel::refreshRide)
          ErrorBanner(message, action)
        }
      }
    },
    snackbarHost = { SnackbarHost(snackbar) },
    bottomBar = {
      BottomAppBar(
        actions = {
          Tab.entries.forEach { entry ->
            if (tab == entry) {
              FilledTonalIconButton(onClick = {}, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(entry.icon, contentDescription = entry.label)
              }
            } else {
              IconButton(onClick = { tab = entry }, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(entry.icon, contentDescription = entry.label)
              }
            }
          }
        },
        floatingActionButton =
          if (hire.active != null) null
          else {
            {
              ExtendedFloatingActionButton(
                onClick = {
                  viewModel.select(null)
                  hireViewModel.scan()
                },
                icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
                text = { Text("Scan") },
                containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
              )
            }
          },
      )
    },
  ) { padding ->
    when (tab) {
      Tab.MAP ->
        MapTab(
          state = state,
          features = features,
          routes = drawn,
          initialCamera = viewModel.camera,
          onSelect = { feature -> viewModel.select(feature?.key) },
          onCameraMoved = { camera -> viewModel.camera = camera },
          onPlacePin = viewModel::startPin,
          onFocusHandled = viewModel::focusHandled,
          onFitApplied = viewModel::fitApplied,
          onRetry = viewModel::refresh,
          modifier = Modifier.fillMaxSize().padding(padding),
        )
      Tab.FAVOURITES ->
        FavouritesTab(
          state = state,
          onOpen = { feature ->
            viewModel.focusOn(feature)
            tab = Tab.MAP
          },
          onUnfavourite = viewModel::toggleFavourite,
          modifier = Modifier.fillMaxSize().padding(padding),
        )
      Tab.LINES ->
        LinesTab(
          state = routes,
          onDirection = routesViewModel::setDirection,
          onVisibility = routesViewModel::setVisible,
          onForget = routesViewModel::forget,
          onAdd = {
            routesViewModel.loadCatalogue()
            picking = true
          },
          modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
  }

  if (picking) {
    ModalBottomSheet(
      onDismissRequest = { picking = false },
      sheetState = rememberModalBottomSheetState(),
    ) {
      AddLineSheet(
        state = routes,
        saved = routes.routes.map { it.line }.toSet(),
        onPick = { line ->
          routesViewModel.save(line)
          picking = false
        },
      )
    }
  }

  if (state.pendingPin != null) {
    NamePinDialog(onConfirm = viewModel::confirmPin, onDismiss = viewModel::cancelPin)
  }

  if (selected != null) {
    ModalBottomSheet(
      onDismissRequest = { viewModel.select(null) },
      sheetState = rememberModalBottomSheetState(),
    ) {
      if (selected.key.layer == BUS_STOP_LAYER) {
        StopSheet(feature = selected)
      } else if (selected.key.layer == PIN_LAYER) {
        PinSheet(feature = selected, onDelete = { viewModel.deletePin(selected.key) })
      } else {
        FeatureSheet(
          feature = selected,
          favourite = selected.key in state.favourites,
          onToggleFavourite = { viewModel.toggleFavourite(selected.key) },
        )
      }
    }
  }

  HireFlowHost(hire.flow, hireViewModel) {
    hireViewModel.dismiss()
    purchaseViewModel.open()
  }
}
