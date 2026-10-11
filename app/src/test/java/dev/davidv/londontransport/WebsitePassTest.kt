package dev.davidv.londontransport

import dev.davidv.londontransport.santander.Password
import dev.davidv.londontransport.santander.WebsitePass
import dev.davidv.londontransport.santander.purchaseCompleted
import dev.davidv.londontransport.santander.signInPage
import dev.davidv.londontransport.santander.signInScript
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsitePassTest {
  @Test
  fun `each pass opens its own purchase page, the annual one having no index at all`() {
    assertEquals(
      listOf(
        "https://santandercycles.tfl.gov.uk/purchase?idx=3",
        "https://santandercycles.tfl.gov.uk/purchase?idx=2",
        "https://santandercycles.tfl.gov.uk/purchase?idx=1",
        "https://santandercycles.tfl.gov.uk/purchase",
      ),
      WebsitePass.entries.map(WebsitePass::url),
    )
  }

  @Test
  fun `only the website's own completion page counts as a finished purchase`() {
    assertTrue(purchaseCompleted("santandercycles.tfl.gov.uk", "/purchase/complete"))
    assertFalse(purchaseCompleted("santandercycles.tfl.gov.uk", "/purchase"))
    assertFalse(purchaseCompleted("payment.contactless.tfl.gov.uk", "/purchase/complete"))
  }

  @Test
  fun `only the website's own sign-in page gets the account filled in`() {
    assertTrue(signInPage("santandercycles.tfl.gov.uk", "/sign-in"))
    assertFalse(signInPage("payment.contactless.tfl.gov.uk", "/sign-in"))
    assertFalse(signInPage("santandercycles.tfl.gov.uk", "/purchase"))
  }

  @Test
  fun `a password with quotes cannot break out of the sign-in script`() {
    val password = "a\"b');alert(1)//\\"
    val script = signInScript("rider@example.com", Password(password))

    val arguments = script.substringAfterLast("})(").substringBeforeLast(");")
    assertEquals(
      listOf("rider@example.com", password),
      Json.decodeFromString<List<String>>("[$arguments]"),
    )
  }
}
