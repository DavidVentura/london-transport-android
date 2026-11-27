package dev.davidv.londontransport

import dev.davidv.londontransport.santander.*
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.YearMonth
import java.util.Currency
import javax.crypto.Cipher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

private val PURCHASE_SESSION = PolarisSession(MemberId("member"), PolarisToken("secret-token"))
private val SINGLE_PLAN = PurchasePlan(PlanId("10017"), "Single ride", PaymentSchedule.Once, 4)
private val GBP = Currency.getInstance("GBP")
private val SINGLE_QUOTE = PurchaseQuote(Money(165, GBP, 2), Money(165, GBP, 2))
private val ATTEMPT = PurchaseAttempt(PurchaseId("attempt"), PURCHASE_SESSION.member, SINGLE_PLAN, SINGLE_QUOTE)

class PolarisPurchaseClientTest {
  @Serializable private data class QuoteBody(val subscription_type_id: String, val number_of_concurrent_bikes: Int, val payment_strategy: Int, val member_id: String)
  @Serializable private data class CheckoutBody(val plan_id: String, val payment_strategy: String, val renewable: Boolean, val concurrent_bikes: Int, val redirect_url: String)
  @Serializable private data class CompletionBody(val payment_token: String, val completion_type: Int, val session_id: String? = null)
  @Serializable private data class CardWire(val holderName: String, val number: String, val expirationMonth: Int, val expirationYear: Int)
  @Serializable private data class AddressWire(val houseNumber: String, val street: String, val city: String, val countryRegion: String, val country: String, val postalCode: String)
  @Serializable private data class BillingWire(val creditCard: CardWire, val address: AddressWire)
  @Serializable private data class NativeLine(val memberId: String, val subscription: NativeChoice)
  @Serializable private data class NativeChoice(val typeId: String, val paymentStrategy: String, val enableRenewalOnSubscriptionExpiry: Boolean, val numberOfConcurrentBikes: Int)
  @Serializable private data class NativeRequest(val memberAccessToken: String, val endpoint: String, val billing: BillingWire, val creditCardSecurityCode: String, val lines: List<NativeLine>)

  private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/$name")).readText()

  @Test fun `live catalogue and quote fixtures use their distinct auth and payment formats`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(fixture("purchase-plans-renewal.json")))
      server.enqueue(MockResponse().setBody(fixture("purchase-quote-10017.json")))
      val client = PolarisPurchaseClient(OkHttpClient(), server.url("/"))
      val plans = (client.plans(PURCHASE_SESSION) as PurchaseResult.Ready).value
      assertEquals(listOf("10017", "10024", "10016", "10015"), plans.map { it.id.value })
      assertEquals(listOf("Single Ride", "Day Pass", "Monthly Pass", "Annual Pass"), plans.map { it.name })
      assertEquals(SINGLE_QUOTE, (client.quote(PURCHASE_SESSION, plans.first()) as PurchaseResult.Ready).value)
      val catalogue = server.takeRequest()
      assertEquals("/hub/mobile/v1/ldn/subscription/types?type=renewal", catalogue.path)
      assertEquals("secret-token", catalogue.getHeader("Authorization"))
      assertNull(catalogue.getHeader("x-polaris-Authorization"))
      val quote = server.takeRequest()
      assertEquals("secret-token", quote.getHeader("x-polaris-Authorization"))
      assertEquals(QuoteBody("10017", 1, 1, "member"), Json.decodeFromString<QuoteBody>(quote.body.readUtf8()))
    }
  }

  @Test fun `checkout carries header context and an owned per-attempt callback`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"url":"${server.url("/v1/lbsbff/polaris/hpp-page/purchase")}","headers":[{"key":"x-polaris-hpp-gateway","value":"dnaPayments"}]}"""))
      val client = PolarisPurchaseClient(OkHttpClient(), server.url("/"))
      val checkout = (client.checkout(PURCHASE_SESSION, ATTEMPT, false) as PurchaseResult.Ready).value
      assertEquals("dnaPayments", checkout.headers["x-polaris-hpp-gateway"])
      assertEquals("secret-token", checkout.headers["x-polaris-authorization"])
      assertEquals(POLARIS_USER_AGENT, checkout.headers["x-user-agent"])
      assertFalse(checkout.toString().contains("secret-token"))
      val request = server.takeRequest()
      assertEquals(CheckoutBody("10017", "o", false, 1, "londontransport://hpp_purchase/attempt"), Json.decodeFromString<CheckoutBody>(request.body.readUtf8()))
    }
  }

  @Test fun `an unexpected checkout origin is rejected before any authenticated page load`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"url":"https://other.example/checkout","headers":[]}"""))
      assertTrue(PolarisPurchaseClient(OkHttpClient(), server.url("/")).checkout(PURCHASE_SESSION, ATTEMPT, false) is PurchaseResult.Failed)
      assertEquals(1, server.requestCount)
    }
  }

  @Test fun `card number and CVV use the backend RSA format in a single native subscription request`() = runTest {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val public = pair.public as RSAPublicKey
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"security_public_key":"scpkv2:${public.modulus.toString(16)}-${public.publicExponent.toString(16)}"}"""))
      server.enqueue(MockResponse().setBody("""{"salesOrderId":"order-123","nowAmount":165}"""))
      val type = CardType("Visa", "visa")
      val card = CardDetails(PaymentMethod("Test Rider", type, "4111111111111111", "12", "99", BillingAddress("1", "Test Street", "London", "", "GB", "N1 1AA"), false), Cvv("123"))
      val valid = (ValidatedCard.parse(card, YearMonth.of(2026, 11)) as CardValidation.Valid).card
      assertEquals(CardPurchaseResult.Purchased(SalesOrderId("order-123")), PolarisPurchaseClient(OkHttpClient(), server.url("/")).purchase(PURCHASE_SESSION, ATTEMPT, false, valid))
      server.takeRequest()
      val request = server.takeRequest()
      assertEquals("POST", request.method)
      assertEquals("/hub/mobile/v1/ldn/subscription", request.path)
      assertEquals("secret-token", request.getHeader("Authorization"))
      val body = request.body.readUtf8()
      assertFalse(body.contains("4111111111111111"))
      val native = Json { ignoreUnknownKeys = true }.decodeFromString<NativeRequest>(body)
      assertEquals("secret-token", native.memberAccessToken)
      assertEquals("mobile", native.endpoint)
      assertEquals(listOf(NativeLine("member", NativeChoice("10017", "o", false, 1))), native.lines)
      assertEquals(2, server.requestCount)
      val billing = native.billing
      val decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, pair.private) }
      fun plain(encoded: String): String {
        assertTrue(encoded.startsWith("scdpv2:"))
        val number = BigInteger(encoded.removePrefix("scdpv2:"), 16).toByteArray()
        val bytes = ByteArray(256)
        val unsigned = number.takeLast(256).toByteArray()
        unsigned.copyInto(bytes, 256 - unsigned.size)
        return decrypt.doFinal(bytes).toString(Charsets.UTF_8)
      }
      assertEquals("4111111111111111", plain(billing.creditCard.number))
      assertEquals("123", plain(native.creditCardSecurityCode))
      assertEquals(2099, billing.creditCard.expirationYear)
      assertEquals("GB", billing.address.country)
    }
  }

  @Test fun `native purchase reports validation codes and never retries uncertain responses`() = runTest {
    val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
    val config = """{"security_public_key":"scpkv2:${key.modulus.toString(16)}-${key.publicExponent.toString(16)}"}"""
    val card = CardDetails(PaymentMethod("Test", CardType("Card", "card"), "4111111111111111", "12", "99",
      BillingAddress("1", "Street", "London", "", "GB", "N1 1AA"), false), Cvv("123"))
    val valid = (ValidatedCard.parse(card, YearMonth.of(2026, 11)) as CardValidation.Valid).card
    MockWebServer().use { server ->
      val client = PolarisPurchaseClient(OkHttpClient(), server.url("/"))
      server.enqueue(MockResponse().setBody(config))
      server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":"INVALID_ENCRYPTION","violations":[{"field":"billing.creditCard.number","code":"INVALID"}]}"""))
      val rejected = client.purchase(PURCHASE_SESSION, ATTEMPT, false, valid) as CardPurchaseResult.Rejected
      assertTrue(rejected.message.contains("INVALID_ENCRYPTION"))
      assertTrue(rejected.message.contains("billing.creditCard.number: INVALID"))
      assertEquals(2, server.requestCount)
      server.enqueue(MockResponse().setBody(config))
      server.enqueue(MockResponse().setResponseCode(503))
      assertTrue(client.purchase(PURCHASE_SESSION, ATTEMPT, false, valid) is CardPurchaseResult.Uncertain)
      assertEquals(4, server.requestCount)
      server.enqueue(MockResponse().setBody(config))
      server.enqueue(MockResponse().setBody("{}"))
      assertTrue(client.purchase(PURCHASE_SESSION, ATTEMPT, false, valid) is CardPurchaseResult.Uncertain)
      assertEquals(6, server.requestCount)
    }
  }

  @Test fun `account eligibility preserves the current plan and requires the availability flag`() = runTest {
    MockWebServer().use { server ->
      val client = PolarisPurchaseClient(OkHttpClient(), server.url("/"))
      server.enqueue(MockResponse().setBody("""{"member":{"canPurchaseSubscription":false,"currentSubscription":{"type":{"id":"10017"}}}}"""))
      assertEquals(PurchaseResult.Ready(PurchaseEligibility.Blocked(PlanId("10017"))), client.eligibility(PURCHASE_SESSION))
      val request = server.takeRequest()
      assertEquals("/hub/mobile/v1/ldn/account", request.path)
      assertEquals("secret-token", request.getHeader("Authorization"))
      server.enqueue(MockResponse().setBody("""{"member":{}}"""))
      assertTrue(client.eligibility(PURCHASE_SESSION) is PurchaseResult.Failed)
    }
  }

  @Test fun `completion sends the payment token once and accepts an omitted session ID`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("{}"))
      val result = PolarisPurchaseClient(OkHttpClient(), server.url("/")).complete(PURCHASE_SESSION, PaymentCompletion(PaymentToken("payment-token"), null))
      assertEquals(PurchaseResult.Ready(Unit), result)
      val request = server.takeRequest()
      assertEquals("/v1/lbsbff/polaris/hpp/complete", request.path)
      assertEquals(CompletionBody("payment-token", 2), Json.decodeFromString<CompletionBody>(request.body.readUtf8()))
      assertEquals(1, server.requestCount)
    }
  }

  @Test fun `failed completion does not retry and an expired account is a typed result`() = runTest {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(503))
      server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
      val client = PolarisPurchaseClient(OkHttpClient(), server.url("/"))
      assertTrue(client.complete(PURCHASE_SESSION, PaymentCompletion(PaymentToken("token"), null)) is PurchaseResult.Failed)
      assertEquals(1, server.requestCount)
      assertEquals(PurchaseResult.LoggedOut, client.plans(PURCHASE_SESSION))
    }
  }

  @Test fun `callback validation rejects another attempt and requires a success token`() {
    assertTrue(parseCheckoutReturn("londontransport://hpp_purchase/other?status=success&token=x", ATTEMPT) is CheckoutReturn.Failed)
    assertTrue(parseCheckoutReturn("${ATTEMPT.returnUrl}?status=success", ATTEMPT) is CheckoutReturn.Failed)
    assertTrue(parseCheckoutReturn("${ATTEMPT.returnUrl}?status=success&status=failed&token=x", ATTEMPT) is CheckoutReturn.Failed)
    assertEquals(CheckoutReturn.Unrelated, parseCheckoutReturn("https://bank.example/challenge", ATTEMPT))
    assertEquals(CheckoutReturn.Cancelled, parseCheckoutReturn("${ATTEMPT.returnUrl}?action=back", ATTEMPT))
    assertEquals(CheckoutReturn.Paid(PaymentCompletion(PaymentToken("a+b"), "session")), parseCheckoutReturn("${ATTEMPT.returnUrl}?status=success&token=a%2Bb&session_id=session", ATTEMPT))
  }
}
