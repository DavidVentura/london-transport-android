package dev.davidv.londontransport.santander

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class Node(
  @SerialName("Type") val type: String = "",
  @SerialName("ID") val id: String = "",
  @SerialName("Name") val name: String = "",
  @SerialName("Subtitle") val subtitle: String = "",
  @SerialName("TargetUri") val targetUri: String = "",
  @SerialName("AuthToken") val authToken: String = "",
)

sealed interface NodeReply {
  data class Nodes(val nodes: List<Node>) : NodeReply

  /** The backend reports failure as a lone error node, not as an HTTP status. */
  data class Rejected(val message: String) : NodeReply

  data class Malformed(val reason: String) : NodeReply
}

private const val ERROR_NODE = "Node.Internal.Error"

const val SIMPLE_RESPONSE_NODE = "Node.Internal.SimpleResponse"

const val USER_NODE = "Node.Internal.User"

private val json = Json {
  ignoreUnknownKeys = true
  coerceInputValues = true
}

fun parseNodeReply(body: String): NodeReply {
  val nodes =
    try {
      json.decodeFromString<List<Node>>(body)
    } catch (e: SerializationException) {
      return NodeReply.Malformed(e.message ?: "unreadable response")
    }
  val only = nodes.singleOrNull()
  if (only != null && only.type == ERROR_NODE) return NodeReply.Rejected(only.name)
  return NodeReply.Nodes(nodes)
}

private val SESSION_GONE = Regex("logged out|log ?in again", RegexOption.IGNORE_CASE)

/**
 * The backend reports a dead token in the same shape as every other refusal, so the wording is all
 * there is to separate "log in again" from "no bikes here".
 */
fun loggedOut(message: String): Boolean = SESSION_GONE.containsMatchIn(message)

fun simpleResponse(nodes: List<Node>): String? =
  nodes.singleOrNull()?.takeIf { it.type == SIMPLE_RESPONSE_NODE }?.name
