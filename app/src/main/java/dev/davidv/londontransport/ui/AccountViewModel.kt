package dev.davidv.londontransport.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.davidv.londontransport.data.AccountStore
import dev.davidv.londontransport.santander.*
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AccountFlow {
  data object Closed : AccountFlow
  data object LoadingProfile : AccountFlow
  data class Profile(val overview: AccountOverview) : AccountFlow
  data class History(val rides: List<CompletedRide>, val next: HistoryOffset?, val loading: Boolean, val error: String? = null) : AccountFlow
  data class Failed(val message: String) : AccountFlow
}

data class AccountUiState(val email: String? = null, val flow: AccountFlow = AccountFlow.Closed, val signingOut: Boolean = false)

class AccountViewModel(private val api: PolarisAccountApi, private val purchases: PurchaseApi, private val accounts: AccountStore) : ViewModel() {
  private val mutableState = MutableStateFlow(AccountUiState())
  val state = mutableState.asStateFlow()
  private var operation: Job? = null
  private var owner: PolarisSession? = null

  init {
    viewModelScope.launch {
      accounts.account.collectLatest { account ->
        if (owner != null && owner != account?.session) {
          operation?.cancel()
          owner = null
          mutableState.value = AccountUiState(email = account?.email)
        } else {
          mutableState.update { it.copy(email = account?.email) }
        }
      }
    }
  }

  fun openProfile() {
    operation?.cancel()
    mutableState.update { it.copy(flow = AccountFlow.LoadingProfile) }
    operation = viewModelScope.launch {
      val account = accounts.account.first()
      if (account == null) { fail("Sign in to view your account."); return@launch }
      owner = account.session
      val profile = when (val result = api.profile(account.session)) {
        is AccountResult.Ready -> result.value
        AccountResult.LoggedOut -> { fail("Your session expired. Sign out and sign in again."); return@launch }
        is AccountResult.Unavailable -> { fail(result.message); return@launch }
      }
      val name = if (profile.plan == null) null else {
        val plans = when (val result = purchases.plans(account.session)) {
          is PurchaseResult.Ready -> result.value
          PurchaseResult.LoggedOut -> { fail("Your session expired. Sign out and sign in again."); return@launch }
          is PurchaseResult.Failed -> { fail(result.message); return@launch }
        }
        val names = plans.filter { it.id == profile.plan.id }.map { it.name }.distinct()
        if (names.size != 1) { fail("The current plan could not be matched to the pass catalogue."); return@launch }
        names.single()
      }
      mutableState.update { it.copy(flow = AccountFlow.Profile(AccountOverview(profile, name))) }
    }
  }

  fun openHistory() {
    operation?.cancel()
    val initial = AccountFlow.History(emptyList(), HistoryOffset(0), true)
    mutableState.update { it.copy(flow = initial) }
    operation = viewModelScope.launch { fetchHistory(initial, HistoryOffset(0)) }
  }

  fun loadMore() {
    val history = mutableState.value.flow as? AccountFlow.History ?: return
    if (history.loading || operation?.isActive == true) return
    val offset = history.next ?: return
    mutableState.update { it.copy(flow = history.copy(loading = true, error = null)) }
    operation = viewModelScope.launch { fetchHistory(history, offset) }
  }

  private suspend fun fetchHistory(previous: AccountFlow.History, offset: HistoryOffset) {
    val account = accounts.account.first()
    if (account == null) { fail("Sign in to view your ride history."); return }
    owner = account.session
    when (val result = api.history(account.session, offset)) {
      is AccountResult.Ready -> {
        val rides = previous.rides + result.value.rides
        if (rides.map { it.id }.distinct().size != rides.size) {
          mutableState.update { it.copy(flow = previous.copy(loading = false, error = "Ride history changed while loading. Refresh to reload it.")) }
          return
        }
        mutableState.update { it.copy(flow = AccountFlow.History(rides.sortedByDescending { ride -> ride.startedAt }, result.value.next, false)) }
      }
      AccountResult.LoggedOut -> fail("Your session expired. Sign out and sign in again.")
      is AccountResult.Unavailable -> mutableState.update { it.copy(flow = previous.copy(loading = false, error = result.message)) }
    }
  }

  fun signOut() {
    if (mutableState.value.signingOut) return
    operation?.cancel()
    mutableState.update { it.copy(signingOut = true) }
    operation = viewModelScope.launch {
      try {
        accounts.clear()
        owner = null
        mutableState.value = AccountUiState()
      } catch (e: IOException) {
        mutableState.update { it.copy(signingOut = false, flow = AccountFlow.Failed("Could not sign out. Please try again.")) }
      }
    }
  }

  fun close() {
    if (mutableState.value.signingOut) return
    operation?.cancel()
    mutableState.update { it.copy(flow = AccountFlow.Closed) }
  }

  private fun fail(message: String) {
    mutableState.update { it.copy(flow = AccountFlow.Failed(message)) }
  }
}
