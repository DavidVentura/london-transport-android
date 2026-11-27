package dev.davidv.londontransport.santander

import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PolarisPurchaseClient(http: OkHttpClient, private val base: HttpUrl = "https://london.lyftbikes.com/".toHttpUrl()) : PurchaseApi {
  private val http = http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).build()
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

  override suspend fun eligibility(session: PolarisSession): PurchaseResult<PurchaseEligibility> =
    send(hubRequest(session, base.newBuilder().addPathSegments("hub/mobile/v1/ldn/account").build()).get().build()) { body, _ ->
      val member = json.decodeFromString<PurchaseAccountWire>(body).member
      if (member.canPurchaseSubscription) PurchaseEligibility.Allowed
      else PurchaseEligibility.Blocked(member.currentSubscription?.type?.let {
        require(it.id.isNotBlank()) { "Invalid current subscription" }
        PlanId(it.id)
      })
    }

  override suspend fun plans(session: PolarisSession): PurchaseResult<List<PurchasePlan>> {
    val url = base.newBuilder().addPathSegments("hub/mobile/v1/ldn/subscription/types").addQueryParameter("type", "renewal").build()
    return send(hubRequest(session, url).get().build()) { body, _ ->
      json.decodeFromString<PlanListWire>(body).types.flatMap { plan ->
        require(plan.id.isNotBlank() && plan.name.isNotBlank() && plan.maximumNumberOfConcurrentBikes > 0) { "Invalid purchase plan" }
        require(plan.configurations.isNotEmpty()) { "Purchase plan has no payment configuration" }
        val title = plan.additionalFields.filter { it.code == "polaris.title" }.singleOrNull()?.value
          ?: throw IllegalArgumentException("Purchase plan needs exactly one display title")
        require(title.isNotBlank()) { "Purchase plan has no display title" }
        fun optional(code: String): String? {
          val values = plan.additionalFields.filter { it.code == code }
          require(values.size <= 1) { "Purchase plan repeats $code" }
          return values.singleOrNull()?.value?.trim()?.takeIf(String::isNotEmpty)
        }
        val subtitle = optional("polaris.subtitle")
        val terms = optional("polaris.v2.detail.more.description")
        val footer = optional("polaris.detailsFooter")
        plan.configurations.map { config ->
          val schedule = PaymentSchedule.entries.singleOrNull { it.wire == config.paymentStrategy }
            ?: throw IllegalArgumentException("Unsupported payment schedule")
          PurchasePlan(PlanId(plan.id), title, schedule, plan.maximumNumberOfConcurrentBikes, subtitle, terms, footer)
        }
      }
    }
  }

  override suspend fun quote(session: PolarisSession, plan: PurchasePlan): PurchaseResult<PurchaseQuote> =
    send(bffRequest(session, "polaris/api/quotation/get-quotation", "GetPolarisQuotationRequest",
      json.encodeToString(QuoteRequest(plan.id.value, 1, plan.schedule.quoteValue, session.member.value)))) { body, _ ->
      val response = json.decodeFromString<QuoteWire>(body)
      val due = response.amountDueNow.money()
      val total = response.totalAmount.money()
      require(due.currency == total.currency && due.exponent == total.exponent) { "Inconsistent quote currency" }
      PurchaseQuote(due, total)
    }

  override suspend fun checkout(session: PolarisSession, attempt: PurchaseAttempt, renewable: Boolean): PurchaseResult<Checkout> =
    send(bffRequest(session, "polaris/hpp/purchase", "ReadPolarisHppPurchaseRequest",
      json.encodeToString(CheckoutRequest(attempt.plan.id.value, attempt.plan.schedule.wire, renewable, 1, attempt.returnUrl)))) { body, _ ->
      require(attempt.member == session.member) { "Checkout belongs to a different account" }
      val response = json.decodeFromString<CheckoutWire>(body)
      val url = response.url.toHttpUrl()
      require(url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
        url.encodedPath == "/v1/lbsbff/polaris/hpp-page/purchase") { "Unexpected checkout origin" }
      require(response.headers.map { it.key.lowercase(Locale.ROOT) }.distinct().size == response.headers.size) { "Duplicate checkout headers" }
      val headers = Headers.Builder().apply {
        response.headers.forEach { add(it.key, it.value) }
        set("x-user-agent", POLARIS_USER_AGENT)
        set("x-polaris-authorization", session.token.value)
      }.build()
      Checkout(attempt, url, headers.names().associateWith { checkNotNull(headers[it]) })
    }

  override suspend fun purchase(session: PolarisSession, attempt: PurchaseAttempt, renewable: Boolean, card: ValidatedCard): CardPurchaseResult {
    require(attempt.member == session.member) { "Purchase belongs to a different account" }
    val key = when (val result = send(bffRequest(session, "polaris/api/get-config", "GetPolarisConfigRequest", "{}")) { body, _ ->
      json.decodeFromString<ConfigWire>(body).securityPublicKey
    }) {
      is PurchaseResult.Ready -> when (val parsed = CardPublicKey.parse(result.value)) {
        is PurchaseResult.Ready -> parsed.value
        is PurchaseResult.Failed -> return CardPurchaseResult.Rejected(parsed.message)
        PurchaseResult.LoggedOut -> error("key parser returned an authentication error")
      }
      is PurchaseResult.Failed -> return CardPurchaseResult.Rejected(result.message)
      PurchaseResult.LoggedOut -> return CardPurchaseResult.Rejected("Your session expired. Log in again before paying.")
    }
    val body = try {
      val method = card.details.method
      val address = method.address
      val country = address.country.uppercase(Locale.ROOT).let { if (it == "UK") "GB" else it }
      val number = key.encrypt(method.number)
      json.encodeToString(SubscriptionRequest(
        memberAccessToken = session.token.value,
        lines = listOf(SubscriptionLine(session.member.value,
          SubscriptionChoice(attempt.plan.id.value, attempt.plan.schedule.wire, renewable, 1))),
        billing = SubscriptionBilling(
          BillingAddressWire(address.nameOrNumber, address.street, address.city, address.county, country, address.postcode),
          SubscriptionCard(method.cardholder, number, method.expiryMonth.toInt(), 2000 + method.expiryYear.toInt(), number.take(6), number.takeLast(4))),
        creditCardSecurityCode = key.encrypt(card.details.cvv.value),
      ))
    } catch (e: GeneralSecurityException) {
      return CardPurchaseResult.Rejected("Could not encrypt card details; nothing was submitted.")
    }
    val request = hubRequest(session, base.newBuilder().addPathSegments("hub/mobile/v1/ldn/subscription").build())
      .post(body.toRequestBody("application/json".toMediaType())).build()
    return withContext(Dispatchers.IO) {
      try {
        http.newCall(request).execute().use { response ->
          val raw = response.body.string()
          if (response.code in 400..499) return@withContext CardPurchaseResult.Rejected(apiError(raw, response.code, "Card purchase"))
          if (!response.isSuccessful) return@withContext CardPurchaseResult.Uncertain("Card purchase returned HTTP ${response.code}. Check your account before paying again.")
          val receipt = json.decodeFromString<SubscriptionReceipt>(raw)
          require(receipt.salesOrderId.isNotBlank() && receipt.salesOrderId != "0") { "Missing sales order" }
          require(receipt.nowAmount == attempt.quote.dueNow.minor) { "Unexpected payment amount" }
          CardPurchaseResult.Purchased(SalesOrderId(receipt.salesOrderId))
        }
      } catch (e: IOException) {
        CardPurchaseResult.Uncertain("The card purchase response was lost. Check your account before paying again. The payment was not retried.")
      } catch (e: SerializationException) {
        CardPurchaseResult.Uncertain("The purchase response could not be read. Check your account before paying again.")
      } catch (e: IllegalArgumentException) {
        CardPurchaseResult.Uncertain("The purchase receipt could not be verified. Check your account before paying again.")
      }
    }
  }

  private fun apiError(body: String, status: Int, operation: String): String {
    val error = try { json.decodeFromString<PurchaseErrorWire>(body) } catch (e: SerializationException) { null }
    val codes = listOfNotNull(error?.code) + error?.violations.orEmpty().map { violation ->
      listOfNotNull(violation.field, violation.code).joinToString(": ")
    }
    val detail = error?.errorDescription ?: error?.description ?: codes.takeIf { it.isNotEmpty() }?.joinToString("; ")
    return "$operation failed (HTTP $status)" + detail?.let { ": $it" }.orEmpty()
  }

  override suspend fun complete(session: PolarisSession, completion: PaymentCompletion): PurchaseResult<Unit> =
    send(bffRequest(session, "polaris/hpp/complete", "ReadPolarisHppCompleteTransactionRequest",
      json.encodeToString(CompletionRequest(completion.token.value, completion.sessionId)))) { body, _ ->
      json.decodeFromString<EmptyMessage>(body)
      Unit
    }

  private fun hubRequest(session: PolarisSession, url: HttpUrl): Request.Builder = Request.Builder().url(url)
    .header("User-Agent", POLARIS_USER_AGENT).header("Accept", "application/json").header("Authorization", session.token.value)

  private fun bffRequest(session: PolarisSession, path: String, message: String, body: String): Request = Request.Builder()
    .url(base.newBuilder().addPathSegments("v1/lbsbff/$path").build())
    .header("User-Agent", POLARIS_USER_AGENT).header("Accept", "application/json")
    .header("x-polaris-Authorization", session.token.value)
    .post(body.toRequestBody("application/json;messageType=pb.api.endpoints.v1.lbs_bff.$message".toMediaType())).build()

  private suspend fun <T> send(request: Request, decode: (String, Int) -> T): PurchaseResult<T> = withContext(Dispatchers.IO) {
    try {
      http.newCall(request).execute().use { response ->
        if (response.code == 401) return@withContext PurchaseResult.LoggedOut
        val body = response.body.string()
        if (!response.isSuccessful) {
          return@withContext PurchaseResult.Failed(apiError(body, response.code, "Purchase API"))
        }
        PurchaseResult.Ready(decode(body, response.code))
      }
    } catch (e: IOException) {
      PurchaseResult.Failed("Could not reach the purchase API. The request was not retried.")
    } catch (e: SerializationException) {
      PurchaseResult.Failed("Unrecognised response from the purchase API.")
    } catch (e: IllegalArgumentException) {
      PurchaseResult.Failed("Invalid purchase data returned by Santander Cycles.")
    }
  }
}

@Serializable private data class PlanListWire(val types: List<PlanWire>)
@Serializable private data class PlanWire(val id: String, val name: String, val maximumNumberOfConcurrentBikes: Int, val configurations: List<PlanConfigWire>, val additionalFields: List<PlanFieldWire>)
@Serializable private data class PlanFieldWire(val code: String, val value: String)
@Serializable private data class PlanConfigWire(val paymentStrategy: String)
@Serializable private data class MoneyWire(val amount: Long, val currency: String, val exponent: Int) {
  fun money(): Money = Money(amount, Currency.getInstance(currency), exponent)
}
@Serializable private data class QuoteWire(@SerialName("amount_due_now") val amountDueNow: MoneyWire, @SerialName("total_amount") val totalAmount: MoneyWire)
@Serializable private data class QuoteRequest(
  @SerialName("subscription_type_id") val plan: String,
  @SerialName("number_of_concurrent_bikes") val bikes: Int,
  @SerialName("payment_strategy") val schedule: Int,
  @SerialName("member_id") val member: String,
)
@Serializable private data class CheckoutRequest(
  @SerialName("plan_id") val plan: String,
  @SerialName("payment_strategy") val schedule: String,
  val renewable: Boolean,
  @SerialName("concurrent_bikes") val bikes: Int,
  @SerialName("redirect_url") val redirect: String,
)
@Serializable private data class CheckoutWire(val url: String, val headers: List<HeaderWire>)
@Serializable private data class HeaderWire(val key: String, val value: String)
@Serializable private data class CompletionRequest(
  @SerialName("payment_token") val token: String,
  @SerialName("session_id") val sessionId: String?,
  @SerialName("completion_type") val type: Int = 2,
)
@Serializable private data class ConfigWire(@SerialName("security_public_key") val securityPublicKey: String)
@Serializable private data class BillingAddressWire(val houseNumber: String, val street: String, val city: String, val countryRegion: String, val country: String, val postalCode: String)
@Serializable private data class PurchaseErrorWire(@SerialName("error_description") val errorDescription: String? = null, val description: String? = null, val code: String? = null, val violations: List<PurchaseViolationWire> = emptyList())

@Serializable private data class PurchaseViolationWire(val field: String? = null, val code: String)
@Serializable private data class SubscriptionRequest(
  val memberAccessToken: String, val lines: List<SubscriptionLine>, val billing: SubscriptionBilling,
  val creditCardSecurityCode: String, val endpoint: String = "mobile", val taxRegion: EmptyMessage = EmptyMessage(),
)
@Serializable private data class SubscriptionLine(val memberId: String, val subscription: SubscriptionChoice)
@Serializable private data class SubscriptionChoice(
  val typeId: String, val paymentStrategy: String, val enableRenewalOnSubscriptionExpiry: Boolean, val numberOfConcurrentBikes: Int,
)
@Serializable private data class SubscriptionBilling(val address: BillingAddressWire, val creditCard: SubscriptionCard)
@Serializable private data class SubscriptionCard(
  val holderName: String, val number: String, val expirationMonth: Int, val expirationYear: Int, val first6: String, val last4: String,
)
@Serializable private data class SubscriptionReceipt(val salesOrderId: String, val nowAmount: Long)

@Serializable private data class PurchaseAccountWire(val member: PurchaseMemberWire)
@Serializable private data class PurchaseMemberWire(val canPurchaseSubscription: Boolean, val currentSubscription: CurrentSubscriptionWire? = null)
@Serializable private data class CurrentSubscriptionWire(val type: CurrentPlanWire)
@Serializable private data class CurrentPlanWire(val id: String)
