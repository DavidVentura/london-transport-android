package dev.davidv.londontransport

import dev.davidv.londontransport.santander.*
import dev.davidv.londontransport.threeds.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NativePaymentPageTest {
  private val nodes = listOf(
    Node(type = "Node.Screen", name = "Test card form"),
    Node(id = "member_card_name", value = "Test Rider"),
    Node(type = "Node.Data.Dictionary.KeyValuePair", parentId = "member_card_type", name = "Visa", value = "test-visa"),
    Node(id = "member_autopay", state = 0),
    Node(type = "Node.Control.Button", targetUri = "part://test.submit?postback=submit"),
    Node(type = "Node.Control.Label", name = "Test validation error", tags = NodeTags("error_label")),
  )

  @Test fun `processor field errors return an editable form without a 3DS continuation`() {
    val result = parseCardSubmissionReply(Json { encodeDefaults = true }.encodeToString(nodes)) as ProtocolResult.Success
    val rejected = result.value as CardSubmissionReply.Rejected
    assertEquals("Test Rider", rejected.form.cardholder)
    assertEquals(listOf("Test validation error"), rejected.errors)
    assertEquals("test-visa", rejected.form.cardTypes.single().value)
  }

  @Test fun `an incomplete form is malformed and cannot be mistaken for payment success`() {
    assertTrue(parsePaymentPage(nodes.filterNot { it.id == "member_autopay" }) is PaymentPage.Malformed)
    assertTrue(parsePaymentPage(nodes.filterNot { it.parentId == "member_card_type" }) is PaymentPage.Malformed)
    assertTrue(parseCardSubmissionReply("[]") is ProtocolResult.Failure)
  }

  @Test fun `page targets accept upstream part URIs but reject credential exfiltration`() {
    val base = "https://processor.example/".toHttpUrl()
    val target = (ProcessorPageTarget.parse("part://Clients.TfL.Card?postback=submit", base) as ProtocolResult.Success).value
    assertEquals("/Clients/TfL/Card", target.url.encodedPath)
    assertEquals("submit", target.url.queryParameter("postback"))
    listOf("https://attacker.example/", "//attacker.example/", "http://processor.example/card", "https://user:pass@processor.example/card", "card#fragment").forEach {
      assertTrue(ProcessorPageTarget.parse(it, base) is ProtocolResult.Failure)
    }
  }
}
