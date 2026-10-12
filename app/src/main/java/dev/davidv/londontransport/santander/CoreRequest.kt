package dev.davidv.londontransport.santander

import java.time.Instant
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request

private const val USER_AGENT = CoreConstants.USER_AGENT
private const val DIAGNOSTICS = """{"nodestore":"count":"0","bytelength":"0"}"""

fun buildCoreRequest(
  url: HttpUrl,
  deviceId: DeviceId,
  credentials: Credentials?,
  controlVals: Map<String, String>?,
  clientTime: Instant,
): Request {
  val body = FormBody.Builder()
    .add("c3-clienttime", clientTime.epochSecond.toString())
    .add("c3-deviceid", deviceId.value)
    .add("RemoteHost", REMOTE_HOST)
    .apply { controlVals?.let { add("c3-controlvals", encryptControlVals(it)) } }
    .build()
  return Request.Builder()
    .url(url)
    .post(body)
    .header("User-agent", USER_AGENT)
    .header("format", "flatjson")
    .header("c3-userauth", credentials?.let(::userAuthHeader).orEmpty())
    .header("c3-capabilities", "")
    .header("c3-language", "English")
    .header("c3-diagnostics", DIAGNOSTICS)
    .apply { if (controlVals != null) header("c3-applysensitivedatacheck", "y") }
    .build()
}
