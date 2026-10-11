package dev.davidv.londontransport

import dev.davidv.londontransport.domain.PointName
import dev.davidv.londontransport.domain.TerminalName
import dev.davidv.londontransport.santander.AuthToken
import dev.davidv.londontransport.santander.Credentials
import dev.davidv.londontransport.santander.DeviceId
import dev.davidv.londontransport.santander.AccessOption
import dev.davidv.londontransport.santander.BillingAddress
import dev.davidv.londontransport.santander.CardDetails
import dev.davidv.londontransport.santander.CardType
import dev.davidv.londontransport.santander.Cvv
import dev.davidv.londontransport.santander.HireResult
import dev.davidv.londontransport.santander.PayResult
import dev.davidv.londontransport.santander.PaymentForm
import dev.davidv.londontransport.santander.PaymentMethod
import dev.davidv.londontransport.santander.SantanderClient
import dev.davidv.londontransport.santander.UserNodeId
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

private const val LOGGED_OUT =
  """[{"Type":"Node.Internal.Error","ID":"error","Name":"You have been logged out"}]"""

private const val NO_BIKES =
  """[{"Type":"Node.Internal.SimpleResponse","ID":"r",""" +
    """"Name":"Sorry, but there are no bikes available at this location."}]"""

private val CREDENTIALS = Credentials(UserNodeId("node"), AuthToken("token"))

private fun fixture(name: String): String =
  checkNotNull(SantanderClientTest::class.java.getResourceAsStream("/$name")) {
      "missing fixture $name"
    }
    .bufferedReader()
    .readText()

class SantanderClientTest {
  private val server = MockWebServer()

  @After fun stop() = server.shutdown()

  private fun client() =
    SantanderClient(OkHttpClient(), DeviceId("device"), { _, _ -> }, server.url("").toString())

  private suspend fun hire() =
    client().hire(CREDENTIALS, TerminalName("001023"), PointName("River Street, Clerkenwell"))

  @Test
  fun `a dead token is reported as being logged out, not as a refusal to show the rider`() =
    runTest {
      server.enqueue(MockResponse().setBody(LOGGED_OUT))

      assertEquals(HireResult.LoggedOut, hire())
    }

  @Test
  fun `an account without access is shown the periods and never sent to confirm`() = runTest {
    server.enqueue(MockResponse().setBody(fixture("begin-access-needed.json")))

    val result = hire() as HireResult.AccessNeeded

    assertEquals(listOf("Single", "Monthly", "Annual"), result.options.map(AccessOption::name))
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `an empty dock is still an ordinary refusal`() = runTest {
    server.enqueue(MockResponse().setBody(NO_BIKES))

    assertEquals(
      HireResult.Refused("Sorry, but there are no bikes available at this location."),
      hire(),
    )
  }

  @Test
  fun `a card the backend cannot register keeps its message and the form to retry on`() = runTest {
    val message =
      "Sorry, there's a problem with your registration: " +
        "Root.ConsoleV1.Bike.BikeException.InvalidFormatException"
    server.enqueue(
      MockResponse()
        .setBody("""[{"Type":"Node.Internal.SimpleResponse","ID":"r","Name":"$message"}]""")
    )
    val form =
      PaymentForm(
        title = "Payment details - Single",
        cardholder = "Rider Name",
        cardTypes = listOf(CardType("Visa", "Visa")),
        autopay = false,
        submitUri = "part://Clients.TfL.EBikePhase2.TSUF.GenerateLCHSPaymentDetails?postback=submit",
      )
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

    assertEquals(PayResult.Rejected(form, listOf(message)), client().pay(CREDENTIALS, form, card))
  }
}
