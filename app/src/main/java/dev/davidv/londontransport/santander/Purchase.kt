package dev.davidv.londontransport.santander

import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.UUID
import okhttp3.HttpUrl

sealed interface PurchaseResult<out T> {
  data class Ready<T>(val value: T) : PurchaseResult<T>
  data object LoggedOut : PurchaseResult<Nothing>
  data class Failed(val message: String) : PurchaseResult<Nothing>
}

@JvmInline value class PlanId(val value: String)

@JvmInline value class PurchaseId(val value: String)

enum class PaymentSchedule(val wire: String, val quoteValue: Int) {
  Once("o", 1), ThreePayments("t", 2), TwelveMonths("m", 3);
}

/** The catalogue's descriptive copy is absent on a plan read back from a pending purchase record. */
data class PurchasePlan(
  val id: PlanId, val name: String, val schedule: PaymentSchedule, val maximumBikes: Int,
  val subtitle: String? = null, val terms: String? = null, val footer: String? = null,
)

data class Money(val minor: Long, val currency: Currency, val exponent: Int) {
  init {
    require(minor >= 0 && exponent in 0..6)
  }
  val label: String
    get() = NumberFormat.getCurrencyInstance(Locale.UK).apply {
      currency = this@Money.currency
      minimumFractionDigits = exponent
      maximumFractionDigits = exponent
    }.format(BigDecimal.valueOf(minor, exponent))
}

data class PurchaseQuote(val dueNow: Money, val total: Money)

@JvmInline
value class PaymentToken(val value: String) {
  override fun toString(): String = "PaymentToken(hidden)"
}

data class PaymentCompletion(val token: PaymentToken, val sessionId: String?)

data class PurchaseAttempt(val id: PurchaseId, val member: MemberId, val plan: PurchasePlan, val quote: PurchaseQuote) {
  val returnUrl: String get() = "londontransport://hpp_purchase/${id.value}"
}

class Checkout(val attempt: PurchaseAttempt, val url: HttpUrl, val headers: Map<String, String>) {
  override fun toString(): String = "Checkout(hidden)"
}

sealed interface CheckoutReturn {
  data class Paid(val completion: PaymentCompletion) : CheckoutReturn
  data object Cancelled : CheckoutReturn
  data object RetryRequested : CheckoutReturn
  data class Failed(val message: String) : CheckoutReturn
  data object Unrelated : CheckoutReturn
}

fun parseCheckoutReturn(url: String, attempt: PurchaseAttempt): CheckoutReturn {
  val uri = try { URI(url) } catch (e: java.net.URISyntaxException) {
    return CheckoutReturn.Failed("Invalid payment return URL.")
  }
  if (uri.scheme != "londontransport") return CheckoutReturn.Unrelated
  if (uri.rawAuthority != "hpp_purchase" || uri.path != "/${attempt.id.value}") {
    return CheckoutReturn.Failed("This payment return does not belong to the current checkout.")
  }
  val fields = try {
    val entries = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map {
      val parts = it.split('=', limit = 2)
      URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }
    if (entries.map { it.first }.distinct().size != entries.size) {
      return CheckoutReturn.Failed("Duplicate fields in the payment return.")
    }
    val query = entries.toMap()
    ReturnFields(query["status"], query["token"], query["session_id"], query["action"])
  } catch (e: IllegalArgumentException) {
    return CheckoutReturn.Failed("Invalid payment return fields.")
  }
  if (fields.status.equals("success", ignoreCase = true)) {
    val token = fields.token
    if (token.isNullOrBlank()) return CheckoutReturn.Failed("Payment returned without a confirmation token.")
    return CheckoutReturn.Paid(PaymentCompletion(PaymentToken(token), fields.sessionId))
  }
  return when (fields.action?.lowercase(Locale.ROOT)) {
    "back", "skip" -> CheckoutReturn.Cancelled
    "retry" -> CheckoutReturn.RetryRequested
    else -> CheckoutReturn.Failed("Payment was not completed. Check your account before paying again.")
  }
}

private data class ReturnFields(val status: String?, val token: String?, val sessionId: String?, val action: String?)

sealed interface PurchaseEligibility {
  data object Allowed : PurchaseEligibility
  data class Blocked(val currentPlan: PlanId?) : PurchaseEligibility
}

sealed interface CardPurchaseResult {
  data class Purchased(val order: SalesOrderId) : CardPurchaseResult
  data class Rejected(val message: String) : CardPurchaseResult
  data class Uncertain(val message: String) : CardPurchaseResult
}

@JvmInline value class SalesOrderId(val value: String)

interface PurchaseApi {
  suspend fun eligibility(session: PolarisSession): PurchaseResult<PurchaseEligibility>
  suspend fun plans(session: PolarisSession): PurchaseResult<List<PurchasePlan>>
  suspend fun quote(session: PolarisSession, plan: PurchasePlan): PurchaseResult<PurchaseQuote>
  suspend fun checkout(session: PolarisSession, attempt: PurchaseAttempt, renewable: Boolean): PurchaseResult<Checkout>
  suspend fun purchase(session: PolarisSession, attempt: PurchaseAttempt, renewable: Boolean, card: ValidatedCard): CardPurchaseResult
  suspend fun complete(session: PolarisSession, completion: PaymentCompletion): PurchaseResult<Unit>
}
