package dev.davidv.londontransport.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.davidv.londontransport.santander.Account
import dev.davidv.londontransport.santander.MemberId
import dev.davidv.londontransport.santander.Password
import dev.davidv.londontransport.santander.PolarisSession
import dev.davidv.londontransport.santander.PolarisToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AccountStore(private val store: DataStore<Preferences>) {
  val account: Flow<Account?> = store.data.map { preferences ->
    val email = preferences[EMAIL]
    val password = preferences[PASSWORD]
    val member = preferences[MEMBER]
    val token = preferences[TOKEN]
    if (email == null || password == null || member == null || token == null) null
    else Account(email, Password(password), PolarisSession(MemberId(member), PolarisToken(token)))
  }

  suspend fun save(account: Account) {
    store.edit { preferences ->
      preferences.clear()
      preferences[EMAIL] = account.email
      preferences[PASSWORD] = account.password.value
      preferences[MEMBER] = account.session.member.value
      preferences[TOKEN] = account.session.token.value
    }
  }

  suspend fun clear() {
    store.edit { it.clear() }
  }

  private companion object {
    val EMAIL = stringPreferencesKey("email")
    val PASSWORD = stringPreferencesKey("password")
    val MEMBER = stringPreferencesKey("polaris_member_id")
    val TOKEN = stringPreferencesKey("polaris_access_token")
  }
}
