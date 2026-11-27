package dev.davidv.londontransport

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.davidv.londontransport.santander.QrCode
import dev.davidv.londontransport.ui.HireActions
import dev.davidv.londontransport.ui.HireFlow
import dev.davidv.londontransport.ui.HireFlowHost
import dev.davidv.londontransport.ui.LondonTransportTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScannerCancellationTest {
  @get:Rule val compose = createComposeRule()

  @Test fun cancellingTheCameraDismissesTheFlowWithoutUnlocking() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val command = "pm grant ${instrumentation.targetContext.packageName} android.permission.CAMERA"
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    val dismissed = AtomicInteger()
    val unlocks = AtomicInteger()
    val actions = object : HireActions {
      override fun scan() = Unit
      override fun submitLogin(email: String, password: String) = Unit
      override fun submitVerification(code: String) = Unit
      override fun resendCode() = Unit
      override fun unlock(code: QrCode) { unlocks.incrementAndGet() }
      override fun continueTutorial() = Unit
      override fun dismiss() { dismissed.incrementAndGet() }
    }
    compose.setContent { LondonTransportTheme { HireFlowHost(HireFlow.Scanning, actions) {} } }
    compose.onNodeWithContentDescription("Cancel").performClick()
    compose.waitForIdle()
    assertEquals(1, dismissed.get())
    assertEquals(0, unlocks.get())
  }
}
