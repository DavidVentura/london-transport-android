package dev.davidv.londontransport.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.davidv.londontransport.data.*
import dev.davidv.londontransport.domain.*
import dev.davidv.londontransport.santander.*
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QuotedPlan(val plan: PurchasePlan, val quote: PurchaseQuote)

sealed interface PurchaseFlow {
  data object Closed : PurchaseFlow
  data object Loading : PurchaseFlow
  data class Plans(val plans: List<QuotedPlan>) : PurchaseFlow
  data class Unavailable(val message: String) : PurchaseFlow
  data class Uncertain(val message: String) : PurchaseFlow
  /** [catalogue] is what Back returns to. */
  data class Review(val plan: PurchasePlan, val quote: PurchaseQuote, val catalogue: List<QuotedPlan>, val renewable: Boolean = false) : PurchaseFlow
  data class Card(
    val review: Review, val form: PaymentForm, val busy: Boolean = false, val errors: List<String> = emptyList(),
    val save: SaveRequest? = null, val savedAs: PaymentMethodId? = null, val draft: StoredPayment? = null,
  ) : PurchaseFlow
  data object Preparing : PurchaseFlow
  data class Paying(val checkout: Checkout) : PurchaseFlow
  data class Completing(val attempt: PurchaseAttempt) : PurchaseFlow
  data class ConfirmationFailed(val attempt: PurchaseAttempt, val completion: PaymentCompletion, val message: String) : PurchaseFlow
  data class Completed(val attempt: PurchaseAttempt, val notice: String? = null) : PurchaseFlow
  data object Cancelled : PurchaseFlow
  data class Failed(val message: String) : PurchaseFlow
}

data class PurchaseState(val flow: PurchaseFlow = PurchaseFlow.Closed, val saved: List<SavedPaymentMethod> = emptyList())

class PurchaseViewModel(
  private val api: PurchaseApi,
  private val accounts: AccountStore,
  private val methods: PaymentMethodStore,
  private val sealer: PaymentSealer,
  private val pending: PendingPurchases,
  private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
  private val mutableState = MutableStateFlow(PurchaseState())
  val state = mutableState.asStateFlow()
  private var operation: Job? = null
  private var session: PolarisSession? = null

  init {
    viewModelScope.launch { methods.methods.collect { saved -> mutableState.update { it.copy(saved = saved) } } }
  }

  fun open() {
    if (mutableState.value.flow != PurchaseFlow.Closed || operation?.isActive == true) return
    mutableState.update { it.copy(flow = PurchaseFlow.Loading) }
    operation = viewModelScope.launch {
      val account = accounts.account.first()
      if (account == null) {
        fail("Log in to Santander Cycles before buying a pass.")
        return@launch
      }
      session = account.session
      val plans = value(api.plans(account.session)) ?: return@launch
      when (val eligibility = value(api.eligibility(account.session)) ?: return@launch) {
        PurchaseEligibility.Allowed -> {
          val quotes = coroutineScope { plans.map { plan -> async { api.quote(account.session, plan) } }.awaitAll() }
          val quoted = plans.zip(quotes).map { (plan, quote) -> QuotedPlan(plan, value(quote) ?: return@launch) }
          mutableState.update { it.copy(flow = PurchaseFlow.Plans(quoted)) }
        }
        is PurchaseEligibility.Blocked -> {
          val current = plans.firstOrNull { it.id == eligibility.currentPlan }
          val message = "Santander Cycles is not allowing another pass purchase for this account." +
            current?.let { " Current pass: ${it.name}." }.orEmpty() +
            " Check your current pass and rental status before paying again."
          mutableState.update { it.copy(flow = PurchaseFlow.Unavailable(message)) }
        }
      }
    }
  }

  fun choose(choice: QuotedPlan) {
    val choices = mutableState.value.flow as? PurchaseFlow.Plans ?: return
    if (choice !in choices.plans || operation?.isActive == true) return
    mutableState.update { it.copy(flow = PurchaseFlow.Review(choice.plan, choice.quote, choices.plans)) }
  }

  fun restart() {
    close()
    open()
  }

  /** Steps back through review and card entry before leaving the purchase entirely. */
  fun back() {
    when (val flow = mutableState.value.flow) {
      is PurchaseFlow.Review -> mutableState.update { it.copy(flow = PurchaseFlow.Plans(flow.catalogue)) }
      is PurchaseFlow.Card -> if (!flow.busy) mutableState.update { it.copy(flow = flow.review) }
      else -> close()
    }
  }

  fun setRenewable(enabled: Boolean) {
    val review = mutableState.value.flow as? PurchaseFlow.Review ?: return
    mutableState.update { it.copy(flow = review.copy(renewable = enabled)) }
  }

  fun enterCard() {
    val review = mutableState.value.flow as? PurchaseFlow.Review ?: return
    val form = PaymentForm("${review.plan.name} · ${review.quote.dueNow.label}", "")
    mutableState.update { it.copy(flow = PurchaseFlow.Card(review, form)) }
  }

  fun checkout() {
    val review = mutableState.value.flow as? PurchaseFlow.Review ?: return
    if (operation?.isActive == true) return
    mutableState.update { it.copy(flow = PurchaseFlow.Preparing) }
    operation = viewModelScope.launch { prepare(review) }
  }

  fun pay(card: CardDetails, save: SaveRequest?) {
    val entry = mutableState.value.flow as? PurchaseFlow.Card ?: return
    if (entry.busy || operation?.isActive == true) return
    val validated = when (val result = ValidatedCard.parse(card, YearMonth.now(clock))) {
      is CardValidation.Valid -> result.card
      is CardValidation.Invalid -> {
        mutableState.update { it.copy(flow = entry.copy(errors = result.errors)) }
        return
      }
    }
    mutableState.update { it.copy(flow = entry.copy(busy = true, errors = emptyList())) }
    operation = viewModelScope.launch {
      if (save != null) {
        try {
          val sealed = withContext(Dispatchers.IO) { sealer.seal(StoredPayment(card.method, card.cvv.takeIf { save.keepCvv })) }
          if (sealed.previousLost) methods.clear()
          methods.put(SavedPaymentMethod(entry.savedAs ?: PaymentMethodId(UUID.randomUUID().toString()),
            save.name, card.method.summary, save.keepCvv, sealed.sealed))
        } catch (e: GeneralSecurityException) {
          mutableState.update { it.copy(flow = entry.copy(errors = listOf("Could not encrypt the saved card; nothing was submitted."))) }
          return@launch
        } catch (e: IOException) {
          mutableState.update { it.copy(flow = entry.copy(errors = listOf("Could not save the card; nothing was submitted."))) }
          return@launch
        }
      }
      val current = checkNotNull(session)
      val attempt = PurchaseAttempt(PurchaseId(UUID.randomUUID().toString()), current.member, entry.review.plan, entry.review.quote)
      try { pending.save(attempt) } catch (e: IOException) {
        mutableState.update { it.copy(flow = entry.copy(errors = listOf("Could not save the purchase attempt; nothing was submitted."))) }
        return@launch
      }
      when (val result = api.purchase(current, attempt, entry.review.renewable, validated)) {
        is CardPurchaseResult.Purchased -> {
          val notice = try { pending.clear(); null } catch (e: IOException) { "Payment confirmed, but the purchase record could not be cleared." }
          mutableState.update { it.copy(flow = PurchaseFlow.Completed(attempt, notice)) }
        }
        is CardPurchaseResult.Rejected -> {
          try { pending.clear() } catch (e: IOException) {
            fail("${result.message} The purchase record could not be cleared.")
            return@launch
          }
          mutableState.update { it.copy(flow = entry.copy(errors = listOf(result.message))) }
        }
        is CardPurchaseResult.Uncertain -> mutableState.update { it.copy(flow = PurchaseFlow.Uncertain(result.message)) }
      }
    }
  }

  private suspend fun prepare(review: PurchaseFlow.Review) {
    val current = checkNotNull(session)
    val attempt = PurchaseAttempt(PurchaseId(UUID.randomUUID().toString()), current.member, review.plan, review.quote)
    val checkout = value(api.checkout(current, attempt, review.renewable)) ?: return
    try {
      pending.save(attempt)
    } catch (e: IOException) {
      fail("Could not save the checkout session; the payment page was not opened.")
      return
    }
    mutableState.update { it.copy(flow = PurchaseFlow.Paying(checkout)) }
  }

  fun returned(url: String) {
    if (mutableState.value.flow is PurchaseFlow.Completing || mutableState.value.flow is PurchaseFlow.Completed) return
    if (operation?.isActive == true) return
    operation = viewModelScope.launch {
      val attempt = when (val stored = pending.read()) {
        is PurchaseResult.Ready -> stored.value
        is PurchaseResult.Failed -> { fail(stored.message); return@launch }
        PurchaseResult.LoggedOut -> error("pending store returned an authentication error")
      }
      if (attempt == null) { fail("There is no pending checkout for this payment return."); return@launch }
      when (val result = parseCheckoutReturn(url, attempt)) {
        is CheckoutReturn.Paid -> finish(attempt, result.completion)
        CheckoutReturn.Cancelled -> mutableState.update { it.copy(flow = PurchaseFlow.Cancelled) }
        CheckoutReturn.RetryRequested -> fail("Checkout requested a retry. Check your account before starting another payment.")
        is CheckoutReturn.Failed -> fail(result.message)
        CheckoutReturn.Unrelated -> Unit
      }
    }
  }

  fun retryConfirmation() {
    val failed = mutableState.value.flow as? PurchaseFlow.ConfirmationFailed ?: return
    if (operation?.isActive == true) return
    operation = viewModelScope.launch { finish(failed.attempt, failed.completion) }
  }

  private suspend fun finish(attempt: PurchaseAttempt, completion: PaymentCompletion) {
    mutableState.update { it.copy(flow = PurchaseFlow.Completing(attempt)) }
    val account = accounts.account.first()
    if (account == null || account.session.member != attempt.member) {
      mutableState.update { it.copy(flow = PurchaseFlow.ConfirmationFailed(attempt, completion, "Log in with the account that started this checkout, then retry confirmation.")) }
      return
    }
    when (val result = api.complete(account.session, completion)) {
      is PurchaseResult.Ready -> {
        val notice = try { pending.clear(); null } catch (e: IOException) { "Payment confirmed, but the pending-checkout record could not be cleared." }
        mutableState.update { it.copy(flow = PurchaseFlow.Completed(attempt, notice)) }
      }
      PurchaseResult.LoggedOut -> mutableState.update { it.copy(flow = PurchaseFlow.ConfirmationFailed(attempt, completion, "Your session expired. Log in again, then retry confirmation.")) }
      is PurchaseResult.Failed -> mutableState.update { it.copy(flow = PurchaseFlow.ConfirmationFailed(attempt, completion, result.message)) }
    }
  }

  fun edit(saved: SavedPaymentMethod, opened: StoredPayment) {
    val entry = mutableState.value.flow as? PurchaseFlow.Card ?: return
    if (entry.busy) return
    mutableState.update { it.copy(flow = entry.copy(savedAs = saved.id, draft = opened, save = SaveRequest(saved.name, saved.cvvSaved))) }
  }

  fun stopEditing() {
    val entry = mutableState.value.flow as? PurchaseFlow.Card ?: return
    if (entry.busy) return
    mutableState.update { it.copy(flow = entry.copy(savedAs = null, draft = null, save = null)) }
  }

  fun forget(id: PaymentMethodId) {
    if ((mutableState.value.flow as? PurchaseFlow.Card)?.busy == true) return
    viewModelScope.launch { methods.remove(id) }
  }

  fun forgetAll() {
    if ((mutableState.value.flow as? PurchaseFlow.Card)?.busy == true) return
    viewModelScope.launch { methods.clear() }
  }

  fun close() {
    val flow = mutableState.value.flow
    if (flow == PurchaseFlow.Preparing || flow is PurchaseFlow.Completing || (flow as? PurchaseFlow.Card)?.busy == true) return
    operation?.cancel()
    mutableState.update { it.copy(flow = PurchaseFlow.Closed) }
  }

  private fun fail(message: String) {
    mutableState.update { it.copy(flow = PurchaseFlow.Failed(message)) }
  }

  private fun <T> value(result: PurchaseResult<T>): T? = when (result) {
    is PurchaseResult.Ready -> result.value
    is PurchaseResult.Failed -> { fail(result.message); null }
    PurchaseResult.LoggedOut -> { fail("Your session expired. Log in again before paying."); null }
  }
}
