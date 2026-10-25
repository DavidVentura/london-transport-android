package dev.davidv.londontransport.domain

@JvmInline value class PaymentMethodId(val value: String)

/** All base64; [wrappedKey] opens only with the Keystore's private key, and that needs an unlock. */
data class Sealed(val wrappedKey: String, val iv: String, val ciphertext: String)

/** What the rider chose in the save prompt, held until the server has taken the card. */
data class SaveRequest(val name: String, val keepCvv: Boolean)

/** Only [name] and [summary] are readable without unlocking; the card itself sits in [sealed]. */
data class SavedPaymentMethod(
  val id: PaymentMethodId,
  val name: String,
  val summary: String,
  val cvvSaved: Boolean,
  val sealed: Sealed,
)
