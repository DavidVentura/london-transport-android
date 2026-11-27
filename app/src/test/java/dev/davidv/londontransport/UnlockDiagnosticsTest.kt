package dev.davidv.londontransport

import dev.davidv.londontransport.santander.redactUnlockResponse
import org.junit.Assert.*
import org.junit.Test

class UnlockDiagnosticsTest {
  @Test fun `diagnostics retain action structure and messages while removing credentials and URLs`() {
    val payload = """{"interstitial":{"action":[{"list_action":{"sequential_actions":[{"alert_action":{"description":"Try another bike","actions_map":{"retry":{"mutation_session_id":"session-secret","url":"https://example.test/?token=url-secret"}}}}]}}]},"access_token":"access-secret","card_number":"4111111111111111","password":"password-secret","email":"rider@example.test","nested":[{"description":"Number 4111111111111111"}]}"""
    val result = redactUnlockResponse(payload).toString()
    listOf("session-secret", "url-secret", "access-secret", "4111111111111111", "password-secret", "rider@example.test").forEach { secret ->
      assertFalse(result.contains(secret))
    }
    assertTrue(result.contains("list_action"))
    assertTrue(result.contains("Try another bike"))
  }

  @Test fun `non JSON response content is never written verbatim`() {
    assertFalse(redactUnlockResponse("<html>secret-value</html>").toString().contains("secret-value"))
  }
}
