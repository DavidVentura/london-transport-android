package dev.davidv.londontransport

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.davidv.londontransport.data.StoredPayment
import dev.davidv.londontransport.domain.*
import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.ui.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePaymentSheetTest {
  @get:Rule val compose = createComposeRule()
  private val form = PaymentForm("Offline test card form", "Test Rider", listOf(CardType("Visa", "test-visa")), false, "part://test.submit")
  private val card = CardDetails(PaymentMethod("Test Rider", form.cardTypes.single(), "4111111111111111", "12", "99",
    BillingAddress("1", "Test Street", "London", "", "UK", "TEST"), false), Cvv("123"))

  @Test fun savedCardRequiresUnlockAndUsesOnlyTheFreshlyTypedCvv() {
    val saved = SavedPaymentMethod(PaymentMethodId("test-id"), "Reusable test card", card.method.summary, false, Sealed("wrapped", "iv", "ciphertext"))
    val unlocks = AtomicInteger()
    val submitted = AtomicReference<CardDetails>()
    val unlocker = object : PaymentUnlocker {
      override fun available(): Boolean = true
      override suspend fun open(saved: SavedPaymentMethod): Unlocked<StoredPayment> {
        unlocks.incrementAndGet()
        return Unlocked.Done(StoredPayment(card.method, null))
      }
    }
    compose.setContent {
      LondonTransportTheme {
        PaymentSheet(form, false, emptyList(), null, null, null, listOf(saved), unlocker,
          onPay = { details, _ -> submitted.set(details) }, onEdit = { _, _ -> }, onStopEditing = {}, onForget = {}, onForgetAll = {})
      }
    }
    compose.onNodeWithText("CVV").performTextInput("123")
    compose.onNodeWithText("Pay with Reusable test card").performScrollTo().performClick()
    compose.waitUntil { submitted.get() != null }
    assertEquals(1, unlocks.get())
    assertEquals(card, submitted.get())
  }

  @Test fun cancellingAnUnlockNeverSubmitsTheCard() {
    val saved = SavedPaymentMethod(PaymentMethodId("test-id"), "Reusable test card", card.method.summary, true, Sealed("wrapped", "iv", "ciphertext"))
    val unlocks = AtomicInteger()
    val submits = AtomicInteger()
    val unlocker = object : PaymentUnlocker {
      override fun available(): Boolean = true
      override suspend fun open(saved: SavedPaymentMethod): Unlocked<StoredPayment> {
        unlocks.incrementAndGet()
        return Unlocked.Declined(null)
      }
    }
    compose.setContent {
      LondonTransportTheme {
        PaymentSheet(form, false, emptyList(), null, null, null, listOf(saved), unlocker,
          onPay = { _, _ -> submits.incrementAndGet() }, onEdit = { _, _ -> }, onStopEditing = {}, onForget = {}, onForgetAll = {})
      }
    }
    compose.onNodeWithText("Pay with Reusable test card").performScrollTo().performClick()
    compose.waitUntil { unlocks.get() == 1 }
    compose.waitForIdle()
    assertEquals(0, submits.get())
  }
}
