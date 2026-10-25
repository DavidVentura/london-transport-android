package dev.davidv.londontransport.threeds

import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

@Serializable data class TraceHeader(val name: String, val value: String)
@Serializable data class RedactedPaymentRequest(
  val method: String, val origin: String, val path: String, val query: List<TraceHeader>,
  val headers: List<TraceHeader>, val fields: List<TraceHeader>, val body: String,
)
@Serializable private data class TraceResponse(val time: String, val status: Int, val headers: List<TraceHeader>)
@Serializable private data class TraceRequest(val time: String, val stage: PaymentHttpStage, val request: RedactedPaymentRequest)
@Serializable private data class TraceEvent(val time: String, val stage: String, val outcome: String)
@Serializable enum class PaymentHttpStage { Configuration, CardSubmission, Page, Continuation, Challenge }

fun redactPaymentRequest(request: Request): RedactedPaymentRequest {
  val safeHeaders = setOf("accept", "content-type", "c3-capabilities")
  val safeFields = setOf("sdkMaxTimeout", "sdkInterface")
  val form = request.body as? FormBody
  return RedactedPaymentRequest(request.method, "${request.url.scheme}://${request.url.host}:${request.url.port}",
    "<redacted>", request.url.queryParameterNames.map { TraceHeader(it, "<redacted>") },
    request.headers.map { (name, value) -> TraceHeader(name, if (name.lowercase() in safeHeaders) value else "<redacted>") },
    form?.let { (0 until it.size).map { index ->
      TraceHeader(it.name(index), if (it.name(index) in safeFields) it.value(index) else "<redacted>")
    } } ?: emptyList(), if (request.body == null) "none" else "<redacted>")
}

sealed interface PaymentTrace {
  data object Disabled : PaymentTrace
  class Local(val directory: File) : PaymentTrace {
    fun event(stage: String, outcome: String) {
      ensureDirectory()
      File(directory, "event-${UUID.randomUUID()}.json").writeText(Json.encodeToString(TraceEvent(Instant.now().toString(), stage, outcome)))
    }

    internal fun start(request: Request, stage: PaymentHttpStage): File {
      ensureDirectory()
      val exchange = File(directory, "exchange-${UUID.randomUUID()}")
      if (!exchange.mkdir()) throw IOException("Cannot create payment exchange log")
      File(exchange, "request.json").writeText(Json.encodeToString(TraceRequest(Instant.now().toString(), stage, redactPaymentRequest(request))))
      return exchange
    }

    private fun ensureDirectory() {
      if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create payment trace directory")
    }
  }
}

internal fun executePaymentHttp(client: OkHttpClient, request: Request, limit: Long, trace: PaymentTrace, stage: PaymentHttpStage): Response {
  if (trace is PaymentTrace.Disabled) return client.newCall(request).execute()
  val exchange = (trace as PaymentTrace.Local).start(request, stage)
  try {
    return client.newCall(request).execute().use { response ->
      File(exchange, "response.json").writeText(Json.encodeToString(TraceResponse(Instant.now().toString(),
        response.code, response.headers.map { (name, value) -> TraceHeader(name, value) })))
      val prefix = Buffer()
      File(exchange, "response.body").outputStream().use { output ->
        response.body.byteStream().use { input ->
          val chunk = ByteArray(8192)
          while (true) {
            val count = input.read(chunk)
            if (count == -1) break
            output.write(chunk, 0, count)
            val retained = minOf(count.toLong(), limit + 1 - prefix.size).toInt()
            if (retained > 0) prefix.write(chunk, 0, retained)
          }
        }
      }
      File(exchange, "complete.json").writeText(Json.encodeToString(TraceEvent(Instant.now().toString(), "http", "received")))
      response.newBuilder().body(prefix.readByteArray().toResponseBody(response.body.contentType())).build()
    }
  } catch (error: IOException) {
    try {
      File(exchange, "failure.json").writeText(Json.encodeToString(TraceEvent(Instant.now().toString(), "http", error.javaClass.simpleName)))
    } catch (loggingError: IOException) {
      error.addSuppressed(loggingError)
    }
    throw error
  }
}
