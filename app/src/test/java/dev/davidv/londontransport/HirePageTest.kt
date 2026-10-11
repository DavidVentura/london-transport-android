package dev.davidv.londontransport

import dev.davidv.londontransport.domain.ReleaseCode
import dev.davidv.londontransport.santander.AccessOption
import dev.davidv.londontransport.santander.BeginPage
import dev.davidv.londontransport.santander.BillingAddress
import dev.davidv.londontransport.santander.CardDetails
import dev.davidv.londontransport.santander.CardType
import dev.davidv.londontransport.santander.Cvv
import dev.davidv.londontransport.santander.PaymentMethod
import dev.davidv.londontransport.santander.HirePage
import dev.davidv.londontransport.santander.PaymentPage
import dev.davidv.londontransport.santander.cardControlVals
import dev.davidv.londontransport.santander.parsePaymentPage
import dev.davidv.londontransport.santander.NodeReply
import dev.davidv.londontransport.santander.deviceVerified
import dev.davidv.londontransport.santander.parseBeginPage
import dev.davidv.londontransport.santander.parseHirePage
import dev.davidv.londontransport.santander.parseNodeReply
import dev.davidv.londontransport.santander.releaseCodeExpiry
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val LONDON = ZoneId.of("Europe/London")

private fun nodes(fixture: String) =
  (parseNodeReply(
      checkNotNull(HirePageTest::class.java.getResourceAsStream("/$fixture")) {
          "missing fixture $fixture"
        }
        .bufferedReader()
        .readText()
    ) as NodeReply.Nodes)
    .nodes

class HirePageTest {
  @Test
  fun `a released bike gives up its code, its dock and its deadline`() {
    val page = parseHirePage(nodes("hire-released.json")) as HirePage.Released

    assertEquals(ReleaseCode("11111"), page.code.code)
    assertEquals("River Street, Clerkenwell", page.code.station)
    assertEquals(LocalTime.of(9, 15), page.code.validUntil)
  }

  @Test
  fun `an empty dock refuses in its own words`() {
    val page = parseHirePage(nodes("hire-no-bikes.json")) as HirePage.Refused

    assertTrue(page.message.startsWith("Sorry, but there are no bikes available"))
  }

  @Test
  fun `an unknown device asks for the digits it texted, in field order`() {
    val page = parseHirePage(nodes("hire-verification-needed.json")) as HirePage.VerificationNeeded

    assertEquals(
      listOf(
        "lchs_pincode_1",
        "lchs_pincode_2",
        "lchs_pincode_3",
        "lchs_pincode_4",
        "lchs_pincode_5",
        "lchs_pincode_6",
      ),
      page.challenge.pincodeFields,
    )
    assertTrue(page.challenge.verifyUri.startsWith("part://"))
    assertTrue(page.challenge.message.contains("verification code"))
  }

  @Test
  fun `a page with no code at all is reported rather than read as a hire`() {
    val page = parseHirePage(nodes("verify-accepted.json"))

    assertTrue(page is HirePage.Refused)
  }

  @Test
  fun `the verify reply says so in the one line it answers with`() {
    assertTrue(deviceVerified(nodes("verify-accepted.json")))
    assertTrue(!deviceVerified(nodes("hire-no-bikes.json")))
  }

  @Test
  fun `a deadline later today is today`() {
    val issued = Instant.parse("2026-08-18T21:28:26Z")

    assertEquals(
      Instant.parse("2026-08-18T21:38:00Z"),
      releaseCodeExpiry(issued, LocalTime.of(22, 38), LONDON),
    )
  }

  @Test
  fun `a deadline already past belongs to tomorrow`() {
    val issued = Instant.parse("2026-08-18T22:56:00Z")

    assertEquals(
      Instant.parse("2026-08-18T23:04:00Z"),
      releaseCodeExpiry(issued, LocalTime.of(0, 4), LONDON),
    )
  }

  @Test
  fun `a code the server printed no deadline for still lasts the usual ten minutes`() {
    val issued = Instant.parse("2026-08-18T21:28:26Z")

    assertEquals(issued.plusSeconds(600), releaseCodeExpiry(issued, null, LONDON))
  }

  @Test
  fun `an account without access is offered each period with its price and terms`() {
    val page = parseBeginPage(nodes("begin-access-needed.json")) as BeginPage.AccessNeeded

    assertEquals(listOf("Single", "Monthly", "Annual"), page.options.map(AccessOption::name))
    assertEquals(
      listOf("£1.65 per person", "£20.00 per person", "£120.00 per person"),
      page.options.map(AccessOption::price),
    )
    assertEquals(
      listOf(
        "30mins access begins when a chosen bike has been undocked",
        "If you choose to undock an ebike you will be charged an additional £1.35",
        "Longer journeys cost £1.65 at the start of every additional 30 minutes",
        "Ebikes cost £3.00 at the start of every additional 30 minutes",
      ),
      page.options.first().terms,
    )
  }

  @Test
  fun `an empty dock refuses at begin`() {
    val page = parseBeginPage(nodes("hire-no-bikes.json")) as BeginPage.Refused

    assertTrue(page.message.startsWith("Sorry, but there are no bikes available"))
  }

  @Test
  fun `the card page offers its card types and keeps the cardholder it already knows`() {
    val page = parsePaymentPage(nodes("payment-details.json")) as PaymentPage.Form
    val form = page.form

    assertEquals("Payment details - Single", form.title)
    assertEquals("Rider Name", form.cardholder)
    assertEquals(
      listOf("Visa", "Mastercard", "Visa_Debit", "American_Express"),
      form.cardTypes.map(CardType::value),
    )
    assertTrue(form.autopay)
    assertTrue(form.submitUri.contains("postback=submit"))
    assertEquals(emptyList<String>(), page.errors)
  }

  @Test
  fun `a refused card comes back as the card page with the reason under the field`() {
    val page = parsePaymentPage(nodes("payment-card-rejected.json")) as PaymentPage.Form

    assertEquals(listOf("Please check your card number or the card type"), page.errors)
  }

  @Test
  fun `a card is posted back under exactly the controls the card page has`() {
    val page = nodes("payment-details.json")
    val controls =
      page
        .filter {
          it.type in
            setOf(
              "Node.FormControls.TextBox",
              "Node.FormControls.ListBox",
              "Node.FormControls.Toggle",
            )
        }
        .map { it.id }
        .toSet()
    val card =
      CardDetails(
        PaymentMethod(
          cardholder = "Rider Name",
          type = CardType("Visa", "Visa"),
          number = "4111111111111111",
          expiryMonth = "01",
          expiryYear = "30",
          address = BillingAddress("1", "River Street", "London", "", "United Kingdom", "EC1R 1XX"),
          autopay = false,
        ),
        Cvv("123"),
      )

    assertEquals(controls, cardControlVals(card).keys)
  }
}
