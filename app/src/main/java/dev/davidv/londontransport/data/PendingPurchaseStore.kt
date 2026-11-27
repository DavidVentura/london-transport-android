package dev.davidv.londontransport.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.davidv.londontransport.santander.*
import java.io.IOException
import java.util.Currency
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface PendingPurchases {
  suspend fun read(): PurchaseResult<PurchaseAttempt?>
  suspend fun save(attempt: PurchaseAttempt)
  suspend fun clear()
}

class PendingPurchaseStore(private val store: DataStore<Preferences>) : PendingPurchases {
  override suspend fun read(): PurchaseResult<PurchaseAttempt?> = try {
    val encoded = store.data.first()[KEY]
    PurchaseResult.Ready(encoded?.let {
      val record = Json.decodeFromString<PendingRecord>(it)
      PurchaseAttempt(PurchaseId(record.id), MemberId(record.member),
        PurchasePlan(PlanId(record.plan), record.name, PaymentSchedule.valueOf(record.schedule), record.maximumBikes),
        PurchaseQuote(Money(record.due, Currency.getInstance(record.currency), record.exponent),
          Money(record.total, Currency.getInstance(record.currency), record.exponent)))
    })
  } catch (e: IOException) {
    PurchaseResult.Failed("Could not read the pending payment. Check your account before paying again.")
  } catch (e: SerializationException) {
    PurchaseResult.Failed("Pending payment data is unreadable. Check your account before paying again.")
  } catch (e: IllegalArgumentException) {
    PurchaseResult.Failed("Pending payment data is invalid. Check your account before paying again.")
  }

  override suspend fun save(attempt: PurchaseAttempt) {
    val record = PendingRecord(attempt.id.value, attempt.member.value, attempt.plan.id.value, attempt.plan.name,
      attempt.plan.schedule.name, attempt.plan.maximumBikes, attempt.quote.dueNow.minor,
      attempt.quote.total.minor, attempt.quote.dueNow.currency.currencyCode, attempt.quote.dueNow.exponent)
    store.edit { it[KEY] = Json.encodeToString(record) }
  }

  override suspend fun clear() {
    store.edit { it.remove(KEY) }
  }

  private companion object {
    val KEY = stringPreferencesKey("pending_purchase")
  }
}

@Serializable
private data class PendingRecord(
  val id: String, val member: String, val plan: String, val name: String, val schedule: String,
  val maximumBikes: Int, val due: Long, val total: Long, val currency: String, val exponent: Int,
)
