package dev.davidv.londontransport

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import dev.davidv.londontransport.data.AccountStore
import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.ui.*
import java.io.File
import java.time.Instant
import java.util.Currency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val HISTORY_SESSION = PolarisSession(MemberId("member"), PolarisToken("token"))
private val HISTORY_PROFILE = AccountProfile("rider@example.test", AccountPlan(PlanId("10017"), null, null), false, false)

private class AccountApiFake : PolarisAccountApi {
  val offsets = mutableListOf<HistoryOffset>()
  val nextPageStarted = CompletableDeferred<Unit>()
  var historyGate: CompletableDeferred<Unit>? = null
  var history: AccountResult<HistoryPage> = AccountResult.Ready(HistoryPage(emptyList(), null))
  override suspend fun profile(session: PolarisSession): AccountResult<AccountProfile> = AccountResult.Ready(HISTORY_PROFILE)
  override suspend fun history(session: PolarisSession, offset: HistoryOffset): AccountResult<HistoryPage> {
    offsets += offset
    if (offset.value > 0) nextPageStarted.complete(Unit)
    historyGate?.await()
    return history
  }
}

private class AccountCatalogueFake : PurchaseApi {
  override suspend fun plans(session: PolarisSession): PurchaseResult<List<PurchasePlan>> = PurchaseResult.Ready(listOf(PurchasePlan(PlanId("10017"), "Single Ride", PaymentSchedule.Once, 4)))
  override suspend fun eligibility(session: PolarisSession): PurchaseResult<PurchaseEligibility> = error("Unexpected eligibility")
  override suspend fun quote(session: PolarisSession, plan: PurchasePlan): PurchaseResult<PurchaseQuote> = error("Unexpected quote")
  override suspend fun checkout(session: PolarisSession, attempt: PurchaseAttempt, renewable: Boolean): PurchaseResult<Checkout> = error("Unexpected checkout")
  override suspend fun purchase(session: PolarisSession, attempt: PurchaseAttempt, renewable: Boolean, card: ValidatedCard): CardPurchaseResult = error("Unexpected purchase")
  override suspend fun complete(session: PolarisSession, completion: PaymentCompletion): PurchaseResult<Unit> = error("Unexpected completion")
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
  @get:Rule val folder = TemporaryFolder()
  private val models = ViewModelStore()
  @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
  @After fun teardown() { models.clear(); Dispatchers.resetMain() }

  private fun accountTest(block: suspend TestScope.() -> Unit) = runTest {
    try { block() } finally { models.clear() }
  }
  private suspend fun TestScope.setupModel(api: AccountApiFake): Pair<AccountViewModel, AccountStore> {
    val accounts = AccountStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") })
    accounts.save(Account("rider@example.test", Password("password"), HISTORY_SESSION))
    val model = AccountViewModel(api, AccountCatalogueFake(), accounts)
    models.put("account", model)
    return model to accounts
  }
  private fun ride(id: String): CompletedRide = CompletedRide(RentalId(id), checkNotNull(BikeId.parse("51971")), Instant.EPOCH,
    Instant.EPOCH.plusSeconds(7), "Start", "End", Money(0, Currency.getInstance("GBP"), 2))

  @Test fun `profile uses the catalogue display title and retains unknown validity dates`() = accountTest {
    val (model, _) = setupModel(AccountApiFake())
    model.openProfile()
    val ready = model.state.first { it.flow is AccountFlow.Profile }.flow as AccountFlow.Profile
    assertEquals("Single Ride", ready.overview.planName)
    assertNull(ready.overview.profile.plan?.endsAt)
  }

  @Test fun `history pages append once and refuse duplicate loading taps`() = accountTest {
    val api = AccountApiFake().apply { history = AccountResult.Ready(HistoryPage(listOf(ride("first")), HistoryOffset(50))) }
    val (model, _) = setupModel(api)
    model.openHistory()
    model.state.first { (it.flow as? AccountFlow.History)?.loading == false }
    runCurrent()
    val gate = CompletableDeferred<Unit>()
    api.historyGate = gate
    api.history = AccountResult.Ready(HistoryPage(listOf(ride("second")), null))
    model.loadMore()
    api.nextPageStarted.await()
    model.loadMore()
    assertEquals(listOf(HistoryOffset(0), HistoryOffset(50)), api.offsets)
    gate.complete(Unit)
    val ready = model.state.first { (it.flow as? AccountFlow.History)?.rides?.size == 2 }.flow as AccountFlow.History
    assertNull(ready.next)
  }

  @Test fun `failed next page keeps existing rides visible with an explicit retry error`() = accountTest {
    val api = AccountApiFake().apply { history = AccountResult.Ready(HistoryPage(listOf(ride("first")), HistoryOffset(50))) }
    val (model, _) = setupModel(api)
    model.openHistory()
    model.state.first { (it.flow as? AccountFlow.History)?.loading == false }
    runCurrent()
    api.history = AccountResult.Unavailable("Offline")
    model.loadMore()
    val failed = model.state.first { (it.flow as? AccountFlow.History)?.error != null }.flow as AccountFlow.History
    assertEquals("Offline", failed.error)
    assertEquals(listOf(ride("first")), failed.rides)
    assertEquals(HistoryOffset(50), failed.next)
  }

  @Test fun `sign out clears stored credentials and discards a pending history request`() = accountTest {
    val gate = CompletableDeferred<Unit>()
    val api = AccountApiFake().apply { historyGate = gate }
    val (model, accounts) = setupModel(api)
    model.openHistory()
    runCurrent()
    model.signOut()
    accounts.account.first { it == null }
    model.state.first { it.email == null && it.flow == AccountFlow.Closed && !it.signingOut }
    gate.complete(Unit)
    runCurrent()
    assertEquals(AccountFlow.Closed, model.state.value.flow)
    assertNull(accounts.account.first())
  }

  @Test fun `closing history cancels the load and does not reopen when its response arrives`() = accountTest {
    val gate = CompletableDeferred<Unit>()
    val api = AccountApiFake().apply { historyGate = gate }
    val (model, _) = setupModel(api)
    model.openHistory()
    runCurrent()
    model.close()
    gate.complete(Unit)
    runCurrent()
    assertEquals(AccountFlow.Closed, model.state.value.flow)
  }
}
