package dev.davidv.londontransport.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.davidv.londontransport.santander.Account
import dev.davidv.londontransport.santander.AuthToken
import dev.davidv.londontransport.santander.Credentials
import dev.davidv.londontransport.santander.UserNodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The login result, kept because logging in is slow and the token never expires. The password is
 * not kept: a token that stops working is rare enough to ask again for.
 */
class AccountStore(private val store: DataStore<Preferences>) {
  val account: Flow<Account?> =
    store.data.map { preferences ->
      val email = preferences[EMAIL]
      val node = preferences[NODE]
      val token = preferences[TOKEN]
      if (email == null || node == null || token == null) null
      else Account(email, Credentials(UserNodeId(node), AuthToken(token)))
    }

  suspend fun save(account: Account) {
    store.edit { preferences ->
      preferences[EMAIL] = account.email
      preferences[NODE] = account.credentials.user.value
      preferences[TOKEN] = account.credentials.token.value
    }
  }

  private companion object {
    val EMAIL = stringPreferencesKey("email")
    val NODE = stringPreferencesKey("user_node_id")
    val TOKEN = stringPreferencesKey("auth_token")
  }
}
