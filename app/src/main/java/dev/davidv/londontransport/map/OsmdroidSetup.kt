package dev.davidv.londontransport.map

import android.content.Context
import java.io.File
import org.osmdroid.config.Configuration

private const val CACHE_MAX_BYTES = 250L * 1024 * 1024
private const val CACHE_TRIM_BYTES = 200L * 1024 * 1024

/**
 * osmdroid keeps every tile it downloads in a sqlite cache and serves from it before hitting the
 * network, so panning over ground you have already seen works offline. Must run before the first
 * MapView is inflated.
 */
fun configureOsmdroid(context: Context) {
  val preferences = context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
  Configuration.getInstance().apply {
    load(context, preferences)
    // OSM's tile servers block the default user agent.
    userAgentValue = context.packageName
    osmdroidBasePath = File(context.filesDir, "osmdroid").also { it.mkdirs() }
    osmdroidTileCache = File(context.cacheDir, "tiles").also { it.mkdirs() }
    tileFileSystemCacheMaxBytes = CACHE_MAX_BYTES
    tileFileSystemCacheTrimBytes = CACHE_TRIM_BYTES
    save(context, preferences)
  }
}
