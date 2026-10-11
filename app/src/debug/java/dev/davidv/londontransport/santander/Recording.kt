package dev.davidv.londontransport.santander

import android.content.Context
import java.io.File
import java.time.Instant

/** Lands in Android/data/<package>/files, so `adb pull` reaches it without root or run-as. */
fun replyRecorder(context: Context): ReplyRecorder {
  val dir = checkNotNull(context.getExternalFilesDir("santander-replies")) { "no external files dir" }
  return ReplyRecorder { url, body ->
    val name = "${Instant.now()}-${url.pathSegments.last()}".replace(':', '-')
    File(dir, "$name.json").writeText("// $url\n$body")
  }
}
