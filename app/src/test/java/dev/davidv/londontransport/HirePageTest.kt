package dev.davidv.londontransport

import dev.davidv.londontransport.domain.ReleaseCode
import dev.davidv.londontransport.santander.HirePage
import dev.davidv.londontransport.santander.NodeReply
import dev.davidv.londontransport.santander.deviceVerified
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
}
