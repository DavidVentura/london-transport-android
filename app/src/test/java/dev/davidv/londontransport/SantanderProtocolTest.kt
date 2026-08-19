package dev.davidv.londontransport

import dev.davidv.londontransport.santander.AuthToken
import dev.davidv.londontransport.santander.Credentials
import dev.davidv.londontransport.santander.NodeReply
import dev.davidv.londontransport.santander.UserNodeId
import dev.davidv.londontransport.santander.encryptControlVals
import dev.davidv.londontransport.santander.loggedOut
import dev.davidv.londontransport.santander.partUriToUrl
import dev.davidv.londontransport.santander.parseNodeReply
import dev.davidv.londontransport.santander.userAuthHeader
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = "https://ce-a24.corethree.net/"

private val KEY: ByteArray =
  Base64.getDecoder().decode("diS1dUbsVQyWSRA5zhAbKJrxYmLa7hD5CVUWc4OZaRs=")

private fun decryptControlVals(encrypted: String): String {
  val cipher =
    Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
      init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(KEY.copyOf(16)))
    }
  return String(cipher.doFinal(Base64.getDecoder().decode(encrypted)))
}

class SantanderProtocolTest {
  @Test
  fun `a part uri becomes a url, dots turning to slashes only in the path`() {
    assertEquals(
      "${BASE}Clients/TfL/EBikePhase2/BeginMemberHire?TerminalName=001023",
      partUriToUrl("part://Clients.TfL.EBikePhase2.BeginMemberHire?TerminalName=001023", BASE),
    )
  }

  @Test
  fun `a query keeps its dots`() {
    assertEquals(
      "${BASE}Clients/TfL/TSUF/ConfirmationPageAction?email=rider%40example.com",
      partUriToUrl("part://Clients.TfL.TSUF.ConfirmationPageAction?email=rider%40example.com", BASE),
    )
  }

  @Test
  fun `the auth header is the hash of node and token, then the node again`() {
    val header = userAuthHeader(Credentials(UserNodeId("bhCQFpyCWwuq"), AuthToken("bcegHCsniBea")))

    // sha1("bhCQFpyCWwuqbcegHCsniBea")
    assertEquals("d6cfd63f6f5f3c71fb6c49fdb34e759c1bbeaca0|bhCQFpyCWwuq", header)
  }

  @Test
  fun `control values travel as an encrypted form the server can check`() {
    val encrypted = encryptControlVals(mapOf("login_email" to "rider@example.com"))

    assertEquals(
      "login_email=rider%40example.com&encryptionverificationcheck=1",
      decryptControlVals(encrypted),
    )
  }

  @Test
  fun `a password keeps its punctuation through encoding`() {
    val encrypted = encryptControlVals(mapOf("login_password" to "p a&s/s+w£d"))

    assertEquals(
      "login_password=p%20a%26s/s%2Bw%C2%A3d&encryptionverificationcheck=1",
      decryptControlVals(encrypted),
    )
  }

  @Test
  fun `a lone error node is a refusal rather than a page`() {
    val reply =
      parseNodeReply("""[{"Type":"Node.Internal.Error","ID":"x","Name":"Please log in again"}]""")

    assertEquals(NodeReply.Rejected("Please log in again"), reply)
  }

  @Test
  fun `the refusals that mean the token is dead are told apart from the ones that do not`() {
    assertTrue(loggedOut("You have been logged out"))
    assertTrue(loggedOut("Please log in again"))
    assertFalse(loggedOut("Sorry, but there are no bikes available at this location."))
    assertFalse(loggedOut("You already have a bike out."))
  }

  @Test
  fun `a null name reads as no name rather than failing the whole page`() {
    val reply = parseNodeReply("""[{"Type":"Node","ID":"x","Name":null,"Subtitle":"13212"}]""")

    assertEquals("13212", (reply as NodeReply.Nodes).nodes.single().subtitle)
    assertEquals("", reply.nodes.single().name)
  }

  @Test
  fun `something that is not a node array is reported, not thrown`() {
    assertTrue(parseNodeReply("""{"status":"0"}""") is NodeReply.Malformed)
  }
}
