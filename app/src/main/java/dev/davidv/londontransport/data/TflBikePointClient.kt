package dev.davidv.londontransport.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface FeedResult {
  data class Success(val feed: ParsedFeed) : FeedResult

  data class Unreachable(val reason: String) : FeedResult

  data class Unusable(val reason: String) : FeedResult
}

const val TFL_BIKEPOINT_ENDPOINT = "https://api.tfl.gov.uk/BikePoint"

class TflBikePointClient(private val http: OkHttpClient, private val endpoint: String) {
  suspend fun fetch(): FeedResult =
    withContext(Dispatchers.IO) {
      val request = Request.Builder().url(endpoint).build()
      val body =
        try {
          http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
              return@withContext FeedResult.Unreachable("TfL returned HTTP ${response.code}")
            }
            response.body.string()
          }
        } catch (e: IOException) {
          return@withContext FeedResult.Unreachable(e.message ?: "network unavailable")
        }

      try {
        FeedResult.Success(parseBikePointFeed(body))
      } catch (e: SerializationException) {
        FeedResult.Unusable(e.message ?: "unrecognised feed format")
      }
    }
}
