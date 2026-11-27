package dev.davidv.londontransport.santander

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

fun createUnlockDiagnostics(context: Context): UnlockDiagnostics =
  PersistentUnlockDiagnostics(File(context.noBackupFilesDir, "diagnostics/unlock.jsonl"))

internal class PersistentUnlockDiagnostics(private val file: File, private val clock: Clock = Clock.systemUTC()) : UnlockDiagnostics {
  private val json = Json { explicitNulls = false }

  override fun response(status: Int, payload: String) {
    append(UnlockLogEntry(clock.instant().toString(), "unlock.response", status, redactUnlockResponse(payload)))
    Log.i("PolarisUnlock", "Unlock response HTTP $status")
  }

  override fun failure(reason: String) {
    append(UnlockLogEntry(clock.instant().toString(), "unlock.failure", message = reason))
    Log.w("PolarisUnlock", reason)
  }

  @Synchronized
  private fun append(entry: UnlockLogEntry) {
    try {
      val directory = checkNotNull(file.parentFile)
      if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create diagnostics directory")
      file.appendText(json.encodeToString(entry) + "\n")
    } catch (e: IOException) {
      Log.e("PolarisUnlock", "Could not save unlock diagnostics", e)
    }
  }
}

@Serializable
private data class UnlockLogEntry(
  val timestamp: String, val event: String, val status: Int? = null,
  val response: JsonElement? = null, val message: String? = null,
)
