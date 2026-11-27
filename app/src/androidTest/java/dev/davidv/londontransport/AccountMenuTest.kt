package dev.davidv.londontransport

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.davidv.londontransport.ui.AccountMenu
import dev.davidv.londontransport.ui.LondonTransportTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountMenuTest {
  @get:Rule val compose = createComposeRule()

  @Test fun signedInMenuOffersPlanHistoryAndSignOutWithoutSigningOutOnOpen() {
    val history = AtomicInteger()
    val signOut = AtomicInteger()
    compose.setContent {
      LondonTransportTheme { AccountMenu(true, {}, { history.incrementAndGet() }, { signOut.incrementAndGet() }, {}) }
    }
    compose.onNodeWithContentDescription("Account").performClick()
    compose.onNodeWithText("Account").assertExists()
    compose.onNodeWithText("Ride history").assertExists()
    compose.onNodeWithText("Sign out").assertExists()
    assertEquals(0, signOut.get())
    compose.onNodeWithText("Ride history").performClick()
    assertEquals(1, history.get())
    assertEquals(0, signOut.get())
  }

  @Test fun signedOutButtonOffersLoginInsteadOfPrivateAccountActions() {
    val login = AtomicInteger()
    compose.setContent {
      LondonTransportTheme { AccountMenu(false, {}, {}, {}, { login.incrementAndGet() }) }
    }
    compose.onNodeWithContentDescription("Sign in").performClick()
    assertEquals(1, login.get())
    compose.onNodeWithText("Ride history").assertDoesNotExist()
    compose.onNodeWithText("Sign out").assertDoesNotExist()
  }
}
