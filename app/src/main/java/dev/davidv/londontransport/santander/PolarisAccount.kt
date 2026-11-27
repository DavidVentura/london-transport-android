package dev.davidv.londontransport.santander

import java.time.Duration
import java.time.Instant

sealed interface AccountResult<out T> {
  data class Ready<T>(val value: T) : AccountResult<T>
  data object LoggedOut : AccountResult<Nothing>
  data class Unavailable(val message: String) : AccountResult<Nothing>
}

data class AccountPlan(val id: PlanId, val startsAt: Instant?, val endsAt: Instant?)
data class AccountProfile(val email: String, val plan: AccountPlan?, val autoRenew: Boolean?, val canBuyPass: Boolean)
data class AccountOverview(val profile: AccountProfile, val planName: String?) {
  init {
    require((profile.plan == null) == (planName == null))
    require(planName == null || planName.isNotBlank())
  }
}
@JvmInline value class RentalId(val value: String)
@JvmInline value class HistoryOffset(val value: Int) {
  init { require(value >= 0) }
}
data class CompletedRide(
  val id: RentalId, val bike: BikeId, val startedAt: Instant, val endedAt: Instant,
  val startStation: String, val endStation: String, val cost: Money,
) {
  init { require(endedAt >= startedAt) }
  val duration: Duration get() = Duration.between(startedAt, endedAt)
}
data class HistoryPage(val rides: List<CompletedRide>, val next: HistoryOffset?)

interface PolarisAccountApi {
  suspend fun profile(session: PolarisSession): AccountResult<AccountProfile>
  suspend fun history(session: PolarisSession, offset: HistoryOffset): AccountResult<HistoryPage>
}

fun formatRideDuration(duration: Duration): String {
  require(!duration.isNegative)
  val seconds = duration.seconds
  if (seconds < 60) return "under 1 min"
  if (seconds < 3600) return "${seconds / 60} min"
  return "${seconds / 3600} h ${(seconds % 3600) / 60} min"
}
