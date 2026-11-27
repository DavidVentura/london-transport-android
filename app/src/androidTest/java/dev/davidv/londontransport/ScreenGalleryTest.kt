package dev.davidv.londontransport

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.davidv.londontransport.data.StoredPayment
import dev.davidv.londontransport.domain.*
import dev.davidv.londontransport.layers.pinFeature
import dev.davidv.londontransport.layers.stationFeature
import dev.davidv.londontransport.layers.busStopFeature
import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.ui.*
import java.io.File
import java.time.Instant
import java.util.Currency
import java.time.Clock
import java.time.ZoneOffset
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import org.junit.Rule
import org.junit.Test

private typealias GalleryRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

private class Scenario(
  val name: String,
  val interact: (GalleryRule) -> Unit = {},
  val content: @Composable () -> Unit,
)

private enum class Tab(val label: String) { MAP("Map"), FAVOURITES("Favourites"), LINES("Lines") }

/** Renders every screen with representative API data and saves a full-device capture of each. */
class ScreenGalleryTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val gbp = Currency.getInstance("GBP")
  private fun money(pence: Long) = Money(pence, gbp, 2)

  private fun station(id: String, name: String, standard: Int, electric: Int, docks: Int, state: StationState = StationState.OPERATIONAL) =
    stationFeature(Station(StationId(id), name, LatLon(51.5, -0.12), StationStatus.Live(BikeCounts(standard, electric), docks, state)))

  private val craven = station("1", "Craven Street, Strand", 12, 1, 7)
  private val empty = station("2", "Southampton Place, Holborn", 0, 0, 24)
  private val closed = station("3", "Belgrove Street, King's Cross", 0, 0, 0, StationState.LOCKED)
  private val low = station("4", "Long Acre, Covent Garden", 2, 0, 18)
  private val unknown = stationFeature(Station(StationId("5"), "Waterloo Station 3, Waterloo", LatLon(51.5, -0.11), StationStatus.Unknown))

  private val tutorial = RideTutorial(listOf(
    TutorialPage("Be safe, always", "Helmets are always a good idea. Also, be sure to follow your rules of the road."),
    TutorialPage("Pay as you ride", "Riding costs are based on the pass you select."),
    TutorialPage("Dock at a station to end your ride", "End your ride by pushing your bike firmly into any available dock."),
    TutorialPage("Check for a green indicator after docking", "Look for the green indicator and listen for the chime once your bike is docked. Check the app to confirm the trip has closed."),
  ))

  private val extra60 = "For rides longer than 60 minutes pay £1.65 for each additional 60 minutes \n\nAdd £1 per journey for e-bikes. £3 for each additional 60 minutes"
  private val single = PurchasePlan(PlanId("10017"), "Single Ride", PaymentSchedule.Once, 4, "30-minutes of breeze on your face",
    "For rides longer than 30 minutes pay £1.65 for each additional 30 minutes \n\nE-bikes cost £3 for up to 30 minutes. £3 for each additional 30 minutes",
    "Make sure the green light comes on after docking the bike.")
  private val day = PurchasePlan(PlanId("10024"), "Day Pass", PaymentSchedule.Once, 4, "24 hours of unlimited 60-minute rides", extra60,
    "Make sure the green light comes on after docking the bike.")
  private val monthly = PurchasePlan(PlanId("10016"), "Monthly Pass", PaymentSchedule.Once, 1, "Unlimited 60-minute rides for 30 days", extra60,
    "Membership valid for 30-day period and starts after your first rental.")
  private val annual = PurchasePlan(PlanId("10015"), "Annual Pass", PaymentSchedule.Once, 1, "A year of unlimited 60-minute rides", extra60)
  private val annual12 = annual.copy(schedule = PaymentSchedule.TwelveMonths)
  private fun catalogue() = listOf(
    QuotedPlan(single, PurchaseQuote(money(165), money(165))),
    QuotedPlan(day, PurchaseQuote(money(350), money(350))),
    QuotedPlan(monthly, PurchaseQuote(money(2000), money(2000))),
    QuotedPlan(annual, PurchaseQuote(money(12000), money(12000))),
    QuotedPlan(annual12, PurchaseQuote(money(1000), money(12000))),
  )

  private fun tutorialChallenge(): UnlockResult.TutorialRequired =
    UnlockResult.TutorialRequired(tutorial, UnlockContinuation(MemberId("member"), UnlockAttempt(checkNotNull(QrCode.parse("ABDRDO")), MutationSessionId("s"))))
  private val dayQuote = PurchaseQuote(money(350), money(350))
  private val attempt = PurchaseAttempt(PurchaseId("p1"), MemberId("member"), day, dayQuote)

  private val card = PaymentMethod("Rider Name", CardType("Visa Debit", "visa"), "4111111111111111", "12", "28",
    BillingAddress("10", "Downing Street", "London", "", "GB", "SW1A 2AA"), false)
  private val savedCards = listOf(
    SavedPaymentMethod(PaymentMethodId("a"), "Personal Visa", card.summary, true, Sealed("w", "i", "c")),
    SavedPaymentMethod(PaymentMethodId("b"), "Work Amex", "Amex ••1005", false, Sealed("w", "i", "c")),
  )
  private val fakeUnlocker = object : PaymentUnlocker {
    override fun available() = true
    override suspend fun open(saved: SavedPaymentMethod): Unlocked<StoredPayment> = Unlocked.Done(StoredPayment(card, null))
  }

  private val rides = listOf(
    CompletedRide(RentalId("r1"), checkNotNull(BikeId.parse("23456")), Instant.parse("2026-11-21T08:02:11Z"), Instant.parse("2026-11-21T08:24:40Z"),
      "Craven Street, Strand", "Hop Exchange, The Borough", money(165)),
    CompletedRide(RentalId("r2"), checkNotNull(BikeId.parse("19876")), Instant.parse("2026-11-20T18:40:00Z"), Instant.parse("2026-11-20T19:31:05Z"),
      "Waterloo Station 3, Waterloo", "Belgrove Street, King's Cross", money(330)),
    CompletedRide(RentalId("r3"), checkNotNull(BikeId.parse("30012")), Instant.parse("2026-11-18T12:00:00Z"), Instant.parse("2026-11-18T12:06:00Z"),
      "Long Acre, Covent Garden", "Long Acre, Covent Garden", money(0)),
  )

  private fun route(id: String, kind: TransportKind, towards: String, visible: Boolean = true, moved: Boolean = false, unavailable: String? = null) =
    SavedRoute(SavedLine(LineId(id), kind, RouteDirection.INBOUND, visible),
      if (unavailable != null) null else RoutePath(LineId(id), RouteDirection.INBOUND, towards, emptyList(), emptyList()), moved, unavailable)

  private val routes = RoutesState(routes = listOf(
    route("24", TransportKind.BUS, "Pimlico"),
    route("victoria", TransportKind.TUBE, "Brixton"),
    route("N29", TransportKind.BUS, "Trafalgar Square", moved = true),
    route("390", TransportKind.BUS, "Victoria", visible = false),
    route("73", TransportKind.BUS, "", unavailable = "Could not load the route from TfL"),
  ))

  private val catalogue = RoutesState(catalogue = listOf(
    TransitLine(LineId("1"), "1", TransportKind.BUS),
    TransitLine(LineId("10"), "10", TransportKind.BUS),
    TransitLine(LineId("24"), "24", TransportKind.BUS),
    TransitLine(LineId("bakerloo"), "Bakerloo", TransportKind.TUBE),
    TransitLine(LineId("central"), "Central", TransportKind.TUBE),
    TransitLine(LineId("victoria"), "Victoria", TransportKind.TUBE),
  ))

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  private fun AppFrame(tab: Tab = Tab.MAP, ride: CurrentRide? = null, rideError: String? = null, snack: String? = null, body: @Composable () -> Unit = {}) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(snack) { if (snack != null) snackbar.showSnackbar(snack) }
    Scaffold(
      topBar = {
        Column {
          TopAppBar(
            title = {
              Column {
                Text("London Transport", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Updated 11:22", style = MaterialTheme.typography.labelSmall)
              }
            },
            actions = {
              IconButton(onClick = {}) { Icon(Icons.Outlined.Tune, null) }
              IconButton(onClick = {}) { Icon(Icons.Outlined.PersonOutline, null) }
              IconButton(onClick = {}) { Icon(Icons.Filled.Refresh, null) }
              IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, null) }
            },
          )
          ride?.let { ActiveRideBar(it, {}, Clock.fixed(Instant.parse("2026-11-22T11:12:00Z"), ZoneOffset.UTC)) }
          rideError?.let { ErrorBanner(it, UiAction("Retry") {}) }
        }
      },
      snackbarHost = { SnackbarHost(snackbar) },
      bottomBar = {
        BottomAppBar(
          actions = {
            Tab.entries.forEach { entry ->
              val icon = when (entry) { Tab.MAP -> Icons.Outlined.Map; Tab.FAVOURITES -> Icons.Filled.Star; Tab.LINES -> Icons.Outlined.DirectionsBus }
              if (entry == tab) FilledTonalIconButton(onClick = {}, modifier = Modifier.padding(horizontal = 4.dp)) { Icon(icon, entry.label) }
              else IconButton(onClick = {}, modifier = Modifier.padding(horizontal = 4.dp)) { Icon(icon, entry.label) }
            }
          },
          floatingActionButton = if (ride != null) null else {
            {
              ExtendedFloatingActionButton(onClick = {}, icon = { Icon(Icons.Outlined.QrCodeScanner, null) }, text = { Text("Scan") },
                containerColor = BottomAppBarDefaults.bottomAppBarFabColor, elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation())
            }
          },
        )
      },
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding)) {
        if (tab == Tab.MAP) Box(Modifier.fillMaxSize().padding(0.dp)) { Surface(Modifier.fillMaxSize(), color = Color(0xFFB8C4B0)) {} }
        body()
      }
    }
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  private fun Sheet(skipPartial: Boolean = false, content: @Composable () -> Unit) {
    AppFrame()
    ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartial)) { content() }
  }

  private fun feature(name: String, feature: MapFeature, favourite: Boolean = false) =
    Scenario(name) { Sheet { FeatureSheet(feature, favourite, {}) } }

  private val noHire = object : HireActions {
    override fun scan() = Unit
    override fun submitLogin(email: String, password: String) = Unit
    override fun submitVerification(code: String) = Unit
    override fun resendCode() = Unit
    override fun unlock(code: QrCode) = Unit
    override fun continueTutorial() = Unit
    override fun dismiss() = Unit
  }

  private fun hire(name: String, flow: HireFlow, interact: (GalleryRule) -> Unit = {}) = Scenario(name, interact) {
    AppFrame()
    HireFlowHost(flow, noHire) {}
  }

  private val purchaseModel by lazy {
    val graph = (compose.activity.application as CyclesApplication).graph
    PurchaseViewModel(graph.purchaseApi, graph.account, graph.paymentMethods, graph.paymentVault, graph.pendingPurchases)
  }
  private val accountModel by lazy {
    val graph = (compose.activity.application as CyclesApplication).graph
    AccountViewModel(graph.accountApi, graph.purchaseApi, graph.account)
  }

  private fun purchase(name: String, flow: PurchaseFlow, interact: (GalleryRule) -> Unit = {}) = Scenario(name, interact) {
    val graph = (compose.activity.application as CyclesApplication).graph
    ApiPurchaseScreen(PurchaseState(flow), purchaseModel, graph.paymentVault, {}, {}, {})
  }

  private fun accountScreen(name: String, flow: AccountFlow, signingOut: Boolean = false, interact: (GalleryRule) -> Unit = {}) = Scenario(name, interact) {
    AccountScreen(AccountUiState("rider@example.com", flow, signingOut), accountModel) {}
  }

  private fun profile(plan: AccountPlan?, planName: String?, autoRenew: Boolean?, canBuy: Boolean) =
    AccountFlow.Profile(AccountOverview(AccountProfile("rider@example.com", plan, autoRenew, canBuy), planName))

  private fun scenarios(): List<Scenario> = listOf(
    Scenario("10_favourites_tab") {
      AppFrame(Tab.FAVOURITES) {
        FavouritesTab(CyclesState("Santander Cycles", listOf(craven, empty, closed, low, unknown), favourites = setOf(craven.key, empty.key, closed.key, low.key, unknown.key)), {}, {})
      }
    },
    Scenario("11_favourites_waiting") {
      AppFrame(Tab.FAVOURITES) { FavouritesTab(CyclesState("Santander Cycles", favourites = setOf(craven.key)), {}, {}) }
    },
    Scenario("12_lines_tab") { AppFrame(Tab.LINES) { LinesTab(routes, { _, _ -> }, { _, _ -> }, {}, {}) } },
    Scenario("13_lines_tab_error") { AppFrame(Tab.LINES) { LinesTab(routes.copy(error = "Could not reach TfL. Showing saved routes."), { _, _ -> }, { _, _ -> }, {}, {}) } },
    Scenario("14_add_line_sheet") { Sheet { AddLineSheet(catalogue, setOf(LineId("24")), {}) } },
    Scenario("15_add_line_loading") { Sheet { AddLineSheet(RoutesState(loadingCatalogue = true), emptySet(), {}) } },

    Scenario("16_map_frame") { AppFrame() },
    feature("20_station", craven),
    feature("21_station_favourite", craven, favourite = true),
    feature("22_station_empty", empty),
    feature("23_station_closed", closed),
    feature("24_station_unknown_offline", unknown),
    Scenario("28_bus_stop_sheet") {
      val path = RoutePath(LineId("24"), RouteDirection.INBOUND, "Pimlico", emptyList(), emptyList())
      Sheet { StopSheet(busStopFeature(RouteStop(StopId("s"), "Trafalgar Square / Charing Cross Stn", LatLon(51.5, -0.12), "S"), path, "24", TransportKind.BUS)) }
    },
    Scenario("29_pin_sheet") { Sheet { PinSheet(pinFeature(Pin(PinId("p"), "Home", LatLon(51.4975, -0.1357))), {}) } },

    Scenario("30_active_ride_bar") { AppFrame(ride = CurrentRide(RideId("r"), RideStatus("active"), Instant.parse("2026-11-22T10:58:00Z"), 1)) },
    Scenario("31_active_ride_bar_error") {
      AppFrame(ride = CurrentRide(RideId("r"), RideStatus("active"), Instant.parse("2026-11-22T10:58:00Z"), 1), rideError = "Could not reach Santander Cycles. Try again when connected.")
    },
    Scenario("32_ride_status_error_no_ride") { AppFrame(rideError = "Could not reach Santander Cycles. Try again when connected.") },
    Scenario("33_ride_awaiting_release") { AppFrame(ride = CurrentRide(RideId("r"), RideStatus("active"), null, 1)) },
    Scenario("34_unlock_requested_snackbar") { AppFrame(snack = "Unlock requested. Pull the bike from the dock once it releases.") },

    hire("40_login_unlock", HireFlow.Login(LoginPurpose.Unlock, "", false, null)),
    hire("41_login_account", HireFlow.Login(LoginPurpose.Account, "rider@example.com", false, null)),
    hire("43_login_busy", HireFlow.Login(LoginPurpose.Unlock, "rider@example.com", true, null)),
    hire("44_login_error", HireFlow.Login(LoginPurpose.Unlock, "rider@example.com", false, "Incorrect email or password.")),
    hire("45_verify", HireFlow.Verify(LoginPurpose.Unlock, "rider@example.com", Password("x"), LoginChallenge("c", Instant.now(), "Enter your code"), false, null)),
    hire("46_verify_error", HireFlow.Verify(LoginPurpose.Unlock, "rider@example.com", Password("x"), LoginChallenge("c", Instant.now(), "Enter your code"), false, "That code is incorrect.")),

    hire("50_qr_scanner", HireFlow.Scanning),
    hire("51_unlocking", HireFlow.Unlocking(checkNotNull(QrCode.parse("ABDRDO")))),
    hire("52_unlock_failed", HireFlow.UnlockFailed(checkNotNull(QrCode.parse("ABDRDO")), "This bike can't be unlocked right now. Try another bike.")),
    hire("53_unlock_precondition", HireFlow.UnlockFailed(null, "Couldn't check whether you already have a ride in progress. Refresh and try again.")),
    hire("56_tutorial_page1", HireFlow.Tutorial(tutorialChallenge())),
    hire("57_tutorial_page2", HireFlow.Tutorial(tutorialChallenge()), interact = { it.onNodeWithText("Next").performScrollTo().performClick() }),
    hire("58_tutorial_last", HireFlow.Tutorial(tutorialChallenge()), interact = { r -> repeat(3) { r.onNodeWithText("Next").performScrollTo().performClick(); r.waitForIdle() } }),
    hire("59_tutorial_error", HireFlow.Tutorial(tutorialChallenge(), error = "Couldn't check whether you already have a ride in progress. Refresh and try again."),
      interact = { r -> repeat(3) { r.onNodeWithText("Next").performScrollTo().performClick(); r.waitForIdle() } }),
    hire("61_access_needed", HireFlow.NeedsAccess("Your membership has expired", "Please renew your membership or buy a pass from the menu")),

    accountScreen("70_account_loading", AccountFlow.LoadingProfile),
    accountScreen("71_account_day_pass", profile(AccountPlan(day.id, Instant.parse("2026-11-22T09:00:00Z"), Instant.parse("2026-11-23T09:00:00Z")), "Day Pass", false, false)),
    accountScreen("72_account_no_plan", profile(null, null, null, true)),
    accountScreen("73_account_failed", AccountFlow.Failed("Could not reach Santander Cycles. Try again when connected.")),
    accountScreen("74_history", AccountFlow.History(rides, HistoryOffset(20), false)),
    accountScreen("75_history_empty", AccountFlow.History(emptyList(), null, false)),
    accountScreen("76_history_error", AccountFlow.History(rides, HistoryOffset(20), false, "Ride history is temporarily unavailable")),
    accountScreen("77_ride_details", AccountFlow.History(rides, null, false), interact = { it.onAllNodesWithText("Craven Street, Strand → Hop Exchange, The Borough").onFirst().performClick() }),
    accountScreen("79_history_loading", AccountFlow.History(emptyList(), HistoryOffset(0), true)),
    accountScreen("78_signing_out", profile(null, null, null, true), signingOut = true),

    purchase("80_purchase_loading", PurchaseFlow.Loading),
    purchase("81_purchase_plans", PurchaseFlow.Plans(catalogue())),
    purchase("82_purchase_plans_empty", PurchaseFlow.Plans(emptyList())),
    purchase("83_review_day", PurchaseFlow.Review(day, dayQuote, catalogue())),
    purchase("84_review_annual_12", PurchaseFlow.Review(annual12, PurchaseQuote(money(1000), money(12000)), catalogue(), renewable = true)),
    purchase("85_card_new", PurchaseFlow.Card(PurchaseFlow.Review(day, dayQuote, catalogue()), PaymentForm("Day Pass · £3.50", ""))),
    purchase("87_card_errors", PurchaseFlow.Card(PurchaseFlow.Review(day, dayQuote, catalogue()), PaymentForm("Day Pass · £3.50", ""),
      errors = listOf("Card declined by the issuer."))),
    Scenario("88_card_saved_methods") {
      Surface(Modifier.fillMaxSize()) {
        Column { Text("(PaymentSheet alone, saved cards)", Modifier.padding(24.dp)); PaymentSheet(PaymentForm("Day Pass · £3.50", ""), false, emptyList(), null, null, null, savedCards, fakeUnlocker, { _, _ -> }, { _, _ -> }, {}, {}, {}) }
      }
    },
    Scenario("89_card_save_dialog", interact = { it.onNodeWithText("Pay").performScrollTo().performClick() }) {
      Surface(Modifier.fillMaxSize()) {
        PaymentSheet(PaymentForm("Day Pass · £3.50", ""), false, emptyList(), null, null, StoredPayment(card, Cvv("123")), emptyList(), fakeUnlocker, { _, _ -> }, { _, _ -> }, {}, {}, {})
      }
    },
    purchase("90_preparing", PurchaseFlow.Preparing),
    purchase("91_completing", PurchaseFlow.Completing(attempt)),
    purchase("92_completed", PurchaseFlow.Completed(attempt)),
    purchase("93_completed_notice", PurchaseFlow.Completed(attempt, "Payment confirmed, but the pending-checkout record could not be cleared.")),
    purchase("94_confirmation_failed", PurchaseFlow.ConfirmationFailed(attempt, PaymentCompletion(PaymentToken("t"), null), "Your session expired. Log in again, then retry confirmation.")),
    purchase("95_unavailable", PurchaseFlow.Unavailable("Santander Cycles is not allowing another pass purchase for this account. Current pass: Day Pass. Check your current pass and rental status before paying again.")),
    purchase("96_uncertain", PurchaseFlow.Uncertain("The card purchase response was lost. Check your account before paying again. The payment was not retried.")),
    purchase("97_failed", PurchaseFlow.Failed("Your session expired. Log in again before paying.")),
    purchase("98_cancelled", PurchaseFlow.Cancelled),
  )

  @Test fun renderGallery() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val mode = InstrumentationRegistry.getArguments().getString("galleryMode") ?: "light"
    val dir = File(compose.activity.getExternalFilesDir(null), "gallery/$mode").apply { deleteRecursively(); mkdirs() }
    val all = scenarios()
    val only = InstrumentationRegistry.getArguments().getString("galleryOnly")
    var current by mutableStateOf<Scenario?>(null)
    // MainActivity already set its own content, so replace it rather than going through the rule.
    compose.runOnUiThread {
      compose.activity.setContent {
        LondonTransportTheme {
          current?.let { scenario -> key(scenario.name) { scenario.content() } }
        }
      }
    }
    for (scenario in all.filter { only == null || it.name.startsWith(only) }) {
      current = null
      compose.waitForIdle()
      current = scenario
      compose.waitForIdle()
      runCatching { scenario.interact(compose) }.onFailure { File(dir, "${scenario.name}.error.txt").writeText(it.stackTraceToString()) }
      compose.waitForIdle()
      compose.runOnUiThread { compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
      Thread.sleep(1200)
      val shot: Bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
      File(dir, "${scenario.name}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    current = null
    compose.waitForIdle()
  }
}
