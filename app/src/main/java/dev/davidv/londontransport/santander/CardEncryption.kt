package dev.davidv.londontransport.santander

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.YearMonth
import java.util.Locale
import javax.crypto.Cipher

class CardPublicKey private constructor(private val key: PublicKey) {
  fun encrypt(value: String): String {
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8)).dropWhile { it == 0.toByte() }
    check(bytes.isNotEmpty()) { "empty RSA ciphertext" }
    return "scdpv2:" + bytes.joinToString("") { "%02x".format(it) }
  }

  companion object {
    fun parse(encoded: String): PurchaseResult<CardPublicKey> {
      val match = Regex("^scpkv2:([0-9a-fA-F]{256,4096})-([0-9a-fA-F]{1,256})$").matchEntire(encoded)
        ?: return PurchaseResult.Failed("Unsupported card-encryption key returned by Santander Cycles.")
      return try {
        val spec = RSAPublicKeySpec(BigInteger(match.groupValues[1], 16), BigInteger(match.groupValues[2], 16))
        PurchaseResult.Ready(CardPublicKey(KeyFactory.getInstance("RSA").generatePublic(spec)))
      } catch (e: java.security.GeneralSecurityException) {
        PurchaseResult.Failed("Invalid card-encryption key returned by Santander Cycles.")
      }
    }
  }
}

fun cardInputErrors(card: CardDetails, month: YearMonth): List<String> {
  val method = card.method
  if (method.cardholder.isBlank()) return listOf("Enter the cardholder name.")
  if (method.number.length !in 12..19 || method.number.any { it !in '0'..'9' }) return listOf("Check the card number.")
  val checksum = method.number.reversed().mapIndexed { index, char ->
    val weighted = char.digitToInt() * if (index % 2 == 1) 2 else 1
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
  if (listOf(method.address.nameOrNumber, method.address.street, method.address.city, method.address.postcode).any(String::isBlank)) {
    return listOf("Complete the billing address.")
  }
  val country = method.address.country.uppercase(Locale.ROOT)
  if (country != "UK" && country !in Locale.getISOCountries()) return listOf("Enter a two-letter country code, such as GB.")
  return emptyList()
}


sealed interface CardValidation {
  data class Valid(val card: ValidatedCard) : CardValidation
  data class Invalid(val errors: List<String>) : CardValidation
}

class ValidatedCard private constructor(val details: CardDetails) {
  override fun toString(): String = "ValidatedCard(hidden)"

  companion object {
    fun parse(card: CardDetails, month: YearMonth): CardValidation {
      val errors = cardInputErrors(card, month)
      if (errors.isNotEmpty()) return CardValidation.Invalid(errors)
      return CardValidation.Valid(ValidatedCard(card))
    }
  }
}
