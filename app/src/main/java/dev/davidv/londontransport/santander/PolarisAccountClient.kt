package dev.davidv.londontransport.santander

import java.io.IOException
import java.time.Instant
import java.util.Currency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class PolarisAccountClient(http: OkHttpClient, private val base: HttpUrl = "https://london.lyftbikes.com/".toHttpUrl()) : PolarisAccountApi {
  private val http = http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).build()
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun profile(session: PolarisSession): AccountResult<AccountProfile> =
    get(session, base.newBuilder().addPathSegments("hub/mobile/v1/ldn/account").build()) { body ->
      val member = json.decodeFromString<ProfileWire>(body).member
      require(member.id == session.member.value && member.email.isNotBlank()) { "Account response belongs to a different member" }
      val plan = member.currentSubscription?.let { current ->
        require(current.type.id.isNotBlank()) { "Invalid current plan" }
        require(current.startDateMs == null || current.startDateMs >= 0) { "Invalid plan start date" }
        require(current.endDateMs == null || current.endDateMs >= 0) { "Invalid plan end date" }
        val start = current.startDateMs?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val end = current.endDateMs?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        require(start == null || end == null || end >= start) { "Invalid plan period" }
        AccountPlan(PlanId(current.type.id), start, end)
      }
      AccountProfile(member.email, plan, member.subscriptionRenewalConfiguration?.enabled, member.canPurchaseSubscription)
    }

  override suspend fun history(session: PolarisSession, offset: HistoryOffset): AccountResult<HistoryPage> {
    val size = 50
    val url = base.newBuilder().addPathSegments("hub/mobile/v1/ldn/rental/closed")
      .addQueryParameter("startDateMs", "1199142000")
      .addQueryParameter("memberId", session.member.value)
      .addQueryParameter("pageOffset", offset.value.toString())
      .addQueryParameter("pageSize", size.toString())
      .addQueryParameter("isBikeAngel", "false").build()
    return get(session, url) { body ->
      val response = json.decodeFromString<HistoryWire>(body)
      require(response.dataWarehouseAvailable) { "Ride history is temporarily unavailable" }
      val records = if (response.rentals.isEmpty()) emptyList() else {
        requireNotNull(response.rentals[session.member.value]) { "Ride history belongs to a different member" }
      }
      require(records.size <= size) { "History page exceeds requested size" }
      val rides = records.map { record ->
        require(record.rentalId.isNotBlank() && record.ss.isNotBlank() && record.es.isNotBlank()) { "Incomplete ride record" }
        require(record.sd > 0 && record.ed >= record.sd && record.co >= 0 && record.electricBikeSurchargeFee >= 0) { "Invalid ride record" }
        val bike = requireNotNull(BikeId.parse(record.bike.displayedNumber)) { "Invalid bike number" }
        val surcharge = if (record.electricBikeSurchargeWaivedReason == null) record.electricBikeSurchargeFee else 0
        CompletedRide(RentalId(record.rentalId), bike, Instant.ofEpochMilli(record.sd), Instant.ofEpochMilli(record.ed),
          record.ss.trim(), record.es.trim(), Money(Math.addExact(record.co, surcharge), Currency.getInstance("GBP"), 2))
      }.sortedByDescending { it.startedAt }
      require(rides.map { it.id }.distinct().size == rides.size) { "Duplicate rides in history response" }
      HistoryPage(rides, if (records.size == size) HistoryOffset(Math.addExact(offset.value, size)) else null)
    }
  }

  private suspend fun <T> get(session: PolarisSession, url: HttpUrl, decode: (String) -> T): AccountResult<T> = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).header("Authorization", session.token.value)
      .header("User-Agent", POLARIS_USER_AGENT).header("Accept", "application/json").build()
    try {
      http.newCall(request).execute().use { response ->
        if (response.code == 401) return@withContext AccountResult.LoggedOut
        if (!response.isSuccessful) return@withContext AccountResult.Unavailable("Could not load account data (HTTP ${response.code}).")
        AccountResult.Ready(decode(response.body.string()))
      }
    } catch (e: IOException) {
      AccountResult.Unavailable("Could not reach Santander Cycles. Try again when connected.")
    } catch (e: SerializationException) {
      AccountResult.Unavailable("Santander Cycles returned unreadable account data.")
    } catch (e: IllegalArgumentException) {
      AccountResult.Unavailable(e.message ?: "Santander Cycles returned invalid account data.")
    } catch (e: ArithmeticException) {
      AccountResult.Unavailable("Santander Cycles returned invalid ride amounts.")
    }
  }
}

@Serializable private data class ProfileWire(val member: ProfileMemberWire)
@Serializable private data class ProfileMemberWire(
  val id: String, val email: String, val canPurchaseSubscription: Boolean,
  val currentSubscription: AccountPlanWire? = null, val subscriptionRenewalConfiguration: RenewalWire? = null,
)
@Serializable private data class AccountPlanWire(val type: PlanTypeWire, val startDateMs: Long? = null, val endDateMs: Long? = null)
@Serializable private data class PlanTypeWire(val id: String)
@Serializable private data class RenewalWire(val enabled: Boolean)
@Serializable private data class HistoryWire(val dataWarehouseAvailable: Boolean, val rentals: Map<String, List<RentalWire>>)
@Serializable private data class RentalWire(
  val rentalId: String, val bike: RentalBikeWire, val ss: String, val es: String, val sd: Long, val ed: Long, val co: Long,
  val electricBikeSurchargeFee: Long = 0, val electricBikeSurchargeWaivedReason: String? = null,
)
@Serializable private data class RentalBikeWire(val displayedNumber: String)
