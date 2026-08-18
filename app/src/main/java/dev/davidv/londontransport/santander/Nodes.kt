package dev.davidv.londontransport.santander

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One entry of a flatjson response. Every screen and every action on this backend answers with a
 * flat list of these; only the handful of fields below are ever read.
 */
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

/** A one-node response that is a plain sentence: "no bikes here", "your device is verified". */
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

/** The lone sentence of a [SIMPLE_RESPONSE_NODE] reply, or null when the reply is a real page. */
fun simpleResponse(nodes: List<Node>): String? =
  nodes.singleOrNull()?.takeIf { it.type == SIMPLE_RESPONSE_NODE }?.name
