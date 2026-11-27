package dev.davidv.londontransport

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.davidv.londontransport.santander.RideTutorial
import dev.davidv.londontransport.santander.TutorialPage
import dev.davidv.londontransport.ui.LondonTransportTheme
import dev.davidv.londontransport.ui.RideTutorialSheet
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RideTutorialSheetTest {
  @get:Rule val compose = createComposeRule()
  private val tutorial = RideTutorial((1..4).map { TutorialPage("Page $it", "Instructions $it") })

  @Test fun confirmationIsOfferedOnlyAfterAllPages() {
    val confirms = AtomicInteger()
    compose.setContent { LondonTransportTheme { RideTutorialSheet(tutorial, false, null) { confirms.incrementAndGet() } } }
    compose.onNodeWithText("Continue and unlock").assertDoesNotExist()
    repeat(3) { compose.onNodeWithText("Next").performScrollTo().performClick() }
    assertEquals(0, confirms.get())
    compose.onNodeWithText("Page 4").assertExists()
    compose.onNodeWithText("Back").performScrollTo().performClick()
    compose.onNodeWithText("Continue and unlock").assertDoesNotExist()
    compose.onNodeWithText("Next").performScrollTo().performClick()
    compose.onNodeWithText("Continue and unlock").performScrollTo().performClick()
    assertEquals(1, confirms.get())
  }

  @Test fun pendingContinuationDisablesNavigation() {
    compose.setContent { LondonTransportTheme { RideTutorialSheet(tutorial, true, null) {} } }
    compose.onNodeWithText("Unlocking…").assertIsNotEnabled()
    compose.onNodeWithText("Next").assertDoesNotExist()
  }
}
