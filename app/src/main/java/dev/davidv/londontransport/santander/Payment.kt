package dev.davidv.londontransport.santander

import kotlinx.serialization.Serializable

@Serializable data class CardType(val label: String, val value: String)

/** The card page Select leads to; only the cardholder comes pre-filled, the card is always typed. */
data class PaymentForm(
  val title: String,
  val cardholder: String,
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
