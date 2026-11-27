package dev.davidv.londontransport.santander

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

interface UnlockDiagnostics {
  fun response(status: Int, payload: String)
  fun failure(reason: String)

  data object Disabled : UnlockDiagnostics {
    override fun response(status: Int, payload: String) = Unit
    override fun failure(reason: String) = Unit
  }
}

internal fun redactUnlockResponse(payload: String): JsonElement {
  val parsed = try {
    Json.parseToJsonElement(payload)
  } catch (e: SerializationException) {
    return JsonPrimitive("Non-JSON response omitted")
  }
  if (parsed !is JsonObject) return JsonPrimitive("Non-object response omitted")
  return redactUnlockElement(parsed)
}

private fun redactUnlockElement(element: JsonElement): JsonElement = when (element) {
  is JsonObject -> JsonObject(element.mapValues { (name, value) ->
    val sensitive = Regex("token|secret|password|authorization|session|nonce|email|phone|card|cvv|security.code", RegexOption.IGNORE_CASE)
    if (sensitive.containsMatchIn(name)) JsonPrimitive("[redacted]") else redactUnlockElement(value)
  })
  is JsonArray -> JsonArray(element.map(::redactUnlockElement))
  is JsonPrimitive -> {
    val value = element.content
    when {
      !element.isString -> element
      value.contains("://") || value.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) -> JsonPrimitive("[redacted]")
      else -> JsonPrimitive(value.replace(Regex("(?<!\\d)\\d{12,19}(?!\\d)"), "[redacted]"))
    }
  }
}
