package dev.davidv.londontransport

import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.domain.ReleaseCode
import dev.davidv.londontransport.threeds.*
import dev.davidv.londontransport.ui.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordedNativePaymentApiTest {
  @get:Rule val temporary = TemporaryFolder()
  private val form = PaymentForm("Test payment", "Test Rider", listOf(CardType("Visa", "test-visa")), false, "part://test.submit")
  private val card = CardDetails(PaymentMethod("Test Rider", form.cardTypes.single(), "4111111111111111", "12", "99",
    BillingAddress("1", "Test Street", "London", "", "UK", "TEST"), false), Cvv("123"))

  @Test fun `preparation failure outcome is recorded without plaintext card details`() = runBlocking {
    val directory = temporary.newFolder()
    val api = RecordedNativePaymentApi(fake { NativePaymentResult.Rejected(form, listOf("Preparation failed")) }, PaymentTrace.Local(directory))
    assertTrue(api.pay(form, card) is NativePaymentResult.Rejected)
    val text = directory.listFiles()!!.joinToString { it.readText() }
    assertTrue(text.contains("Preparation failed"))
    assertFalse(text.contains(card.method.number))
    assertFalse(text.contains("\"${card.cvv.value}\""))
  }

  @Test fun `challenge answer outcome is recorded without the OTP`() = runBlocking {
    val directory = temporary.newFolder()
    val session = object : NativeChallenge {
      override suspend fun send(action: ChallengeAction): NativePaymentResult = NativePaymentResult.Stopped("Test bank refusal")
    }
    val api = RecordedNativePaymentApi(fake {
      NativePaymentResult.Challenge(session, ChallengeOutcome.OtpRequired("Bank", "Information", "OTP"))
    }, PaymentTrace.Local(directory))
    val challenge = api.pay(form, card) as NativePaymentResult.Challenge
    challenge.session.send(ChallengeAction.Answer(OtpAnswer("987654")))
    val text = directory.listFiles()!!.joinToString { it.readText() }
    assertTrue(text.contains("challenge-answer"))
    assertTrue(text.contains("Test bank refusal"))
    assertFalse(text.contains("987654"))
  }

  @Test fun `completed hire survives an outcome logging error with an explicit fault`() = runBlocking {
    val directory = File(temporary.root, "trace")
    val result = NativePaymentResult.Finished(HireResult.Released(HireCode(ReleaseCode("12345"), "Test station", null)))
    val api = RecordedNativePaymentApi(fake {
      check(directory.renameTo(File(temporary.root, "saved-trace")))
      check(directory.createNewFile())
      result
    }, PaymentTrace.Local(directory))
    val received = api.pay(form, card) as NativePaymentResult.Finished
    assertEquals(result.result, received.result)
    assertEquals(NativePaymentLoggingFault.OutcomeRecordingFailed, received.loggingFault)
  }

  private fun fake(pay: suspend () -> NativePaymentResult): NativePaymentApi = object : NativePaymentApi {
    override suspend fun open(option: AccessOption): NativeFormResult = NativeFormResult.Ready(form, emptyList())
    override suspend fun pay(form: PaymentForm, card: CardDetails): NativePaymentResult = pay()
  }
}
