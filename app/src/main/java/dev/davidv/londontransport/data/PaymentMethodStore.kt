package dev.davidv.londontransport.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.davidv.londontransport.domain.PaymentMethodId
import dev.davidv.londontransport.domain.SavedPaymentMethod
import dev.davidv.londontransport.domain.Sealed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SavedRecord(
  val id: String,
  val name: String,
  val summary: String,
  val cvvSaved: Boolean,
  val wrappedKey: String,
  val iv: String,
  val ciphertext: String,
)

private val json = Json

class PaymentMethodStore(private val store: DataStore<Preferences>) {
  val methods: Flow<List<SavedPaymentMethod>> =
    store.data.map { preferences -> decode(preferences[KEY]) }

  /** Replaces the method with the same id, or adds it if there is none. */
  suspend fun put(method: SavedPaymentMethod) {
    store.edit { preferences ->
      val others = decode(preferences[KEY]).filterNot { it.id == method.id }
      preferences[KEY] = encode(others + method)
    }
  }

  suspend fun remove(id: PaymentMethodId) {
    store.edit { preferences ->
      preferences[KEY] = encode(decode(preferences[KEY]).filterNot { it.id == id })
    }
  }

  /** A new Keystore key cannot open anything sealed under the old one. */
  suspend fun clear() {
    store.edit { preferences -> preferences.remove(KEY) }
  }

  private fun decode(stored: String?): List<SavedPaymentMethod> =
    if (stored == null) emptyList()
    else
      json.decodeFromString<List<SavedRecord>>(stored).map {
        SavedPaymentMethod(
          PaymentMethodId(it.id),
          it.name,
          it.summary,
          it.cvvSaved,
          Sealed(it.wrappedKey, it.iv, it.ciphertext),
        )
      }

  private fun encode(methods: List<SavedPaymentMethod>): String =
    json.encodeToString(
      methods.map {
        SavedRecord(
          it.id.value,
          it.name,
          it.summary,
          it.cvvSaved,
          it.sealed.wrappedKey,
          it.sealed.iv,
          it.sealed.ciphertext,
        )
      }
    )

  private companion object {
    val KEY = stringPreferencesKey("payment_methods")
  }
}
