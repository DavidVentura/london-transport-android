package dev.davidv.londontransport

import dev.davidv.londontransport.domain.PointName
import dev.davidv.londontransport.domain.TerminalName
import dev.davidv.londontransport.santander.AuthToken
import dev.davidv.londontransport.santander.Credentials
import dev.davidv.londontransport.santander.DeviceId
import dev.davidv.londontransport.santander.HireResult
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

class SantanderClientTest {
  private val server = MockWebServer()

  @After fun stop() = server.shutdown()

  private fun client() =
    SantanderClient(OkHttpClient(), DeviceId("device"), server.url("").toString())

  private suspend fun hire() =
    client().hire(CREDENTIALS, TerminalName("001023"), PointName("River Street, Clerkenwell"))

  @Test
  fun `a dead token is reported as being logged out, not as a refusal to show the rider`() =
    runTest {
      server.enqueue(MockResponse().setBody(LOGGED_OUT))

      assertEquals(HireResult.LoggedOut, hire())
    }

  @Test
  fun `an empty dock is still an ordinary refusal`() = runTest {
    server.enqueue(MockResponse().setBody(NO_BIKES))

    assertEquals(
      HireResult.Refused("Sorry, but there are no bikes available at this location."),
      hire(),
    )
  }
}
