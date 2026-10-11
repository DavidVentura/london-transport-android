package dev.davidv.londontransport.santander

import kotlinx.serialization.Serializable

private const val CARD_TYPE = "member_card_type"
private const val CARD_NAME = "member_card_name"
private const val AUTOPAY = "member_autopay"
private const val SUBMIT_POSTBACK = "postback=submit"
private const val CHOICE = "Node.Data.Dictionary.KeyValuePair"
private const val ERROR_LABEL = "error_label"

@Serializable data class CardType(val label: String, val value: String)

/** The card page Select leads to; only the cardholder comes pre-filled, the card is always typed. */
data class PaymentForm(
  val title: String,
  val cardholder: String,
  val cardTypes: List<CardType>,
  val autopay: Boolean,
  val submitUri: String,
)

@Serializable
data class BillingAddress(
  val nameOrNumber: String,
  val street: String,
  val city: String,
  val county: String,
  val country: String,
  val postcode: String,
)

@JvmInline
@Serializable
value class Cvv(val value: String) {
  override fun toString(): String = "Cvv(hidden)"
}

/** Everything the card page asks for but the CVV, which is kept apart so saving it stays a choice. */
@Serializable
data class PaymentMethod(
  val cardholder: String,
  val type: CardType,
  val number: String,
  val expiryMonth: String,
  val expiryYear: String,
  val address: BillingAddress,
  val autopay: Boolean,
) {
  val summary: String
    get() = "${type.label} ••${number.takeLast(4)}"

  override fun toString(): String = "PaymentMethod(hidden)"
}

data class CardDetails(val method: PaymentMethod, val cvv: Cvv)

sealed interface PaymentPage {
  /** A refused card comes back as the same page with [errors] under the fields at fault. */
  data class Form(val form: PaymentForm, val errors: List<String>) : PaymentPage

  data class Refused(val message: String) : PaymentPage

  data class Malformed(val reason: String) : PaymentPage
}

fun parsePaymentPage(nodes: List<Node>): PaymentPage {
  val refusal = simpleResponse(nodes)
  if (refusal != null) return PaymentPage.Refused(refusal)

  val submit =
    nodes.firstOrNull { it.targetUri.contains(SUBMIT_POSTBACK) }
      ?: return PaymentPage.Malformed("no submit button on the payment page")
  val cardTypes =
    nodes
      .filter { it.type == CHOICE && it.parentId == CARD_TYPE }
      .map { CardType(label = it.name, value = it.value) }
  if (cardTypes.isEmpty()) return PaymentPage.Malformed("no card types on the payment page")
  val autopay =
    nodes.firstOrNull { it.id == AUTOPAY }
      ?: return PaymentPage.Malformed("no auto pay toggle on the payment page")

  return PaymentPage.Form(
    PaymentForm(
      title = nodes.first().name,
      cardholder = nodes.firstOrNull { it.id == CARD_NAME }?.value.orEmpty(),
      cardTypes = cardTypes,
      autopay = autopay.state == 1,
      submitUri = submit.targetUri,
    ),
    errors = nodes.filter { it.tags.styleClass == ERROR_LABEL }.map { it.name },
  )
}

/** Keyed by the form's control ids, which is what the official client posts back. */
fun cardControlVals(card: CardDetails): Map<String, String> {
  val method = card.method
  return mapOf(
    "member_promo" to "",
    CARD_NAME to method.cardholder,
    CARD_TYPE to method.type.value,
    "member_card_number" to method.number,
    "member_card_expiration_month" to method.expiryMonth,
    "member_card_expiration_year" to method.expiryYear,
    "member_card_cvv" to card.cvv.value,
    "member_addressLine1" to method.address.nameOrNumber,
    "member_addressLine2" to method.address.street,
    "member_addressLine3" to method.address.city,
    "member_addressLine4" to method.address.county,
    "member_addressLine5" to method.address.country,
    "member_addressLine6" to method.address.postcode,
    AUTOPAY to if (method.autopay) "1" else "0",
  )
}
