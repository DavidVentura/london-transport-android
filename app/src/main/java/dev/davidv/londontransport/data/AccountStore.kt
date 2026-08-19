package dev.davidv.londontransport.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.davidv.londontransport.santander.Account
import dev.davidv.londontransport.santander.AuthToken
import dev.davidv.londontransport.santander.Credentials
import dev.davidv.londontransport.santander.Password
import dev.davidv.londontransport.santander.UserNodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The login, kept whole. The token is what every call runs on, and the password is what earns a new
 * one when the backend drops the old one mid-ride, which it does without warning. A login stored
 * before the password was kept reads back as no account at all, so it is asked for once more.
 */
class AccountStore(private val store: DataStore<Preferences>) {
  val account: Flow<Account?> =
    store.data.map { preferences ->
      val email = preferences[EMAIL]
      val password = preferences[PASSWORD]
      val node = preferences[NODE]
      val token = preferences[TOKEN]
      if (email == null || password == null || node == null || token == null) null
      else Account(email, Password(password), Credentials(UserNodeId(node), AuthToken(token)))
    }

  suspend fun save(account: Account) {
    store.edit { preferences ->
      preferences[EMAIL] = account.email
      preferences[PASSWORD] = account.password.value
      preferences[NODE] = account.credentials.user.value
      preferences[TOKEN] = account.credentials.token.value
    }
  }

  private companion object {
    val EMAIL = stringPreferencesKey("email")
    val PASSWORD = stringPreferencesKey("password")
    val NODE = stringPreferencesKey("user_node_id")
    val TOKEN = stringPreferencesKey("auth_token")
  }
}
