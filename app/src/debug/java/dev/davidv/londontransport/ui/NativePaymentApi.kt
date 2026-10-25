package dev.davidv.londontransport.ui

import android.content.Context
import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.threeds.*
import java.io.File
import java.time.Clock
import java.time.YearMonth

sealed interface NativeFormResult {
  data class Ready(val form: PaymentForm, val errors: List<String>) : NativeFormResult
  data class Failed(val message: String) : NativeFormResult
}

sealed interface NativePaymentResult {
  data class Rejected(val form: PaymentForm, val errors: List<String>) : NativePaymentResult
  data class Challenge(val session: NativeChallenge, val prompt: ChallengeOutcome.OtpRequired) : NativePaymentResult
  data class Finished(val result: HireResult, val loggingFault: NativePaymentLoggingFault? = null) : NativePaymentResult
  data class Stopped(val message: String) : NativePaymentResult
}

enum class NativePaymentLoggingFault { OutcomeRecordingFailed }

interface NativeChallenge {
  suspend fun send(action: ChallengeAction): NativePaymentResult
}

interface NativePaymentApi {
  suspend fun open(option: AccessOption): NativeFormResult
  suspend fun pay(form: PaymentForm, card: CardDetails): NativePaymentResult
}

fun cardDirectoryServer(number: String): ProtocolResult<DirectoryServer> = protocolBoundary {
  require(number.length in 13..19 && number.all { it in '0'..'9' })
  val prefix = number.take(4).toInt()
  when {
    number.startsWith("4") -> DirectoryServer.Visa
    number.startsWith("34") || number.startsWith("37") -> DirectoryServer.Amex
    prefix / 100 in 51..55 || prefix in 2221..2720 -> DirectoryServer.Mastercard
    else -> throw ProtocolException(ProtocolFault.UnsupportedProfile)
  }
}

fun cardInputErrors(card: CardDetails, form: PaymentForm, month: YearMonth): List<String> {
  val method = card.method
  if (method.type !in form.cardTypes) return listOf("Choose a card type offered by this form.")
  if (method.cardholder.isBlank()) return listOf("Enter the cardholder name.")
  if (cardDirectoryServer(method.number) is ProtocolResult.Failure) return listOf("Only Visa, Mastercard and Amex are supported by this prototype.")
  val checksum = method.number.reversed().mapIndexed { index, char ->
    val digit = char.digitToInt()
    val weighted = if (index % 2 == 1) digit * 2 else digit
    if (weighted > 9) weighted - 9 else weighted
  }.sum()
  if (checksum % 10 != 0) return listOf("Check the card number.")
  val expiryMonth = method.expiryMonth.toIntOrNull()
  val expiryYear = method.expiryYear.toIntOrNull()
  if (method.expiryMonth.length != 2 || method.expiryYear.length != 2 ||
    (method.expiryMonth + method.expiryYear).any { it !in '0'..'9' } || expiryMonth !in 1..12 || expiryYear == null) {
    return listOf("Enter an expiry date in MM/YY format.")
  }
  if (YearMonth.of(2000 + expiryYear, checkNotNull(expiryMonth)) < month) return listOf("The card has expired.")
  val cvvLength = if (method.number.startsWith("34") || method.number.startsWith("37")) 4 else 3
  if (card.cvv.value.length != cvvLength || card.cvv.value.any { it !in '0'..'9' }) return listOf("Check the CVV.")
  if (listOf(method.address.nameOrNumber, method.address.street, method.address.city, method.address.country, method.address.postcode).any(String::isBlank)) {
    return listOf("Complete the billing address.")
  }
  return emptyList()
}

class AndroidNativePaymentApi(
  private val context: Context,
  private val requestContext: PaymentRequestContext,
  private val payments: PaymentRuntime,
  private val clock: Clock,
  private val trace: PaymentTrace = PaymentTrace.Disabled,
) : NativePaymentApi {
  override suspend fun open(option: AccessOption): NativeFormResult {
    val target = when (val result = ProcessorPageTarget.parse(option.selectUri, requestContext.base)) {
      is ProtocolResult.Success -> result.value
      is ProtocolResult.Failure -> return NativeFormResult.Failed("Invalid payment-page target: ${result.fault}")
    }
    val response = when (val result = payments.openPage(target, requestContext, clock.instant())) {
      is PaymentRuntimeResult.Success -> result.value
      is PaymentRuntimeResult.Failure -> return NativeFormResult.Failed("Could not open the card form: ${result.fault}")
    }
    return when (response) {
      is NodeReply.Nodes -> when (val page = parsePaymentPage(response.nodes)) {
        is PaymentPage.Form -> NativeFormResult.Ready(page.form, page.errors)
        is PaymentPage.Refused -> NativeFormResult.Failed(page.message)
        is PaymentPage.Malformed -> NativeFormResult.Failed(page.reason)
      }
      is NodeReply.Rejected -> NativeFormResult.Failed(response.message)
      is NodeReply.Malformed -> NativeFormResult.Failed(response.reason)
    }
  }

  override suspend fun pay(form: PaymentForm, card: CardDetails): NativePaymentResult {
    val errors = cardInputErrors(card, form, YearMonth.now(clock))
    if (errors.isNotEmpty()) return NativePaymentResult.Rejected(form, errors)
    val server = when (val result = cardDirectoryServer(card.method.number)) {
      is ProtocolResult.Success -> result.value
      is ProtocolResult.Failure -> error("Validated card has no directory server")
    }
    val target = when (val result = ProcessorPageTarget.parse(form.submitUri, requestContext.base)) {
      is ProtocolResult.Success -> result.value
      is ProtocolResult.Failure -> return NativePaymentResult.Stopped("Invalid card-submit target: ${result.fault}")
    }
    val runtime = when (val result = AndroidThreeDsRuntime.create(context, File(context.noBackupFilesDir, "three-ds-profile"), trace)) {
      is RuntimeResult.Success -> result.value
      is RuntimeResult.Failure -> return NativePaymentResult.Rejected(form, listOf("3DS initialization failed before card submission: ${result.fault}"))
    }
    val authentication = when (val result = runtime.prepare(server, clock.instant())) {
      is PreparationResult.Success -> result.authentication
      is PreparationResult.Failure -> return NativePaymentResult.Rejected(form, listOf("3DS preparation failed before card submission: ${result.fault}"))
    }
    val core = buildCoreRequest(target.url, requestContext.deviceId, requestContext.credentials, cardControlVals(card), clock.instant())
    val prepared = when (val result = PreparedCardSubmission.create(core, authentication, requestContext)) {
      is ProtocolResult.Success -> result.value
      is ProtocolResult.Failure -> return NativePaymentResult.Stopped("Card request could not be constructed: ${result.fault}")
    }
    return when (val result = payments.submit(prepared)) {
      is PaymentRuntimeResult.Success -> settle(result.value)
      is PaymentRuntimeResult.Failure -> NativePaymentResult.Stopped(uncertain(result.fault))
    }
  }

  private suspend fun settle(step: RuntimePaymentStep): NativePaymentResult = when (step) {
    is RuntimePaymentStep.CardRejected -> NativePaymentResult.Rejected(step.form, step.errors)
    is RuntimePaymentStep.Refused -> NativePaymentResult.Stopped(step.message)
    is RuntimePaymentStep.Continue -> finish(step.pending)
    is RuntimePaymentStep.Completed -> finish(step.pending)
    is RuntimePaymentStep.Failed -> finish(step.pending, "Bank challenge failed: ${step.fault}")
    is RuntimePaymentStep.Prompt -> error("Challenge prompt without its session")
    is RuntimePaymentStep.Challenge -> ActiveChallenge(step.session).send(ChallengeAction.Start)
  }

  private suspend fun finish(pending: PendingPaymentRequest, failure: String? = null): NativePaymentResult {
    val response = when (val result = payments.continuePayment(pending)) {
      is PaymentRuntimeResult.Success -> result.value
      is PaymentRuntimeResult.Failure -> return NativePaymentResult.Stopped(uncertain(result.fault))
    }
    return when (response) {
      is NodeReply.Rejected -> NativePaymentResult.Stopped(response.message)
      is NodeReply.Malformed -> NativePaymentResult.Stopped("Unrecognized payment continuation; check your account before paying again.")
      is NodeReply.Nodes -> when (val page = parseHirePage(response.nodes)) {
        is HirePage.Released -> NativePaymentResult.Finished(HireResult.Released(page.code))
        is HirePage.VerificationNeeded -> NativePaymentResult.Finished(HireResult.VerificationNeeded(page.challenge))
        is HirePage.Refused -> NativePaymentResult.Stopped(page.message)
        is HirePage.Malformed -> NativePaymentResult.Stopped(failure ?: "Unrecognized payment continuation; check your account before paying again.")
      }
    }
  }

  private inner class ActiveChallenge(private val session: RuntimeChallengeSession) : NativeChallenge {
    override suspend fun send(action: ChallengeAction): NativePaymentResult = when (val result = session.send(action)) {
      is PaymentRuntimeResult.Failure -> NativePaymentResult.Stopped(uncertain(result.fault))
      is PaymentRuntimeResult.Success -> when (val step = result.value) {
        is RuntimePaymentStep.Prompt -> NativePaymentResult.Challenge(this, step.prompt)
        else -> settle(step)
      }
    }
  }

  private fun uncertain(fault: PaymentRuntimeFault): String =
    "Payment stopped ($fault). The outcome may be unknown; check your account before paying again."
}
