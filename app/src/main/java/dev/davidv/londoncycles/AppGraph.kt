package dev.davidv.londoncycles

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.davidv.londoncycles.data.FavouritesStore
import dev.davidv.londoncycles.data.PinsStore
import dev.davidv.londoncycles.data.RouteCache
import dev.davidv.londoncycles.data.SavedLinesStore
import dev.davidv.londoncycles.data.SettingsStore
import dev.davidv.londoncycles.data.TFL_API_BASE
import dev.davidv.londoncycles.data.TflLineClient
import dev.davidv.londoncycles.data.StationCache
import dev.davidv.londoncycles.data.TFL_BIKEPOINT_ENDPOINT
import dev.davidv.londoncycles.data.TflBikePointClient
import dev.davidv.londoncycles.layers.CycleHireLayer
import dev.davidv.londoncycles.layers.MapLayer
import dev.davidv.londoncycles.map.configureOsmdroid
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Everything with a lifetime longer than an Activity, wired once at startup. */
class AppGraph(context: Context) {
  private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val http =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .callTimeout(30, TimeUnit.SECONDS)
      .build()

  val layer: MapLayer =
    CycleHireLayer(
      TflBikePointClient(http, TFL_BIKEPOINT_ENDPOINT),
      StationCache(File(context.filesDir, "stations.json")),
    )

  val favourites =
    FavouritesStore(
      PreferenceDataStoreFactory.create(scope = storeScope) {
        context.preferencesDataStoreFile("favourites")
      }
    )

  val settings =
    SettingsStore(
      PreferenceDataStoreFactory.create(scope = storeScope) {
        context.preferencesDataStoreFile("settings")
      }
    )

  val lineClient = TflLineClient(http, TFL_API_BASE)

  val routeCache = RouteCache(File(context.filesDir, "routes.json"))

  val savedLines =
    SavedLinesStore(
      PreferenceDataStoreFactory.create(scope = storeScope) {
        context.preferencesDataStoreFile("lines")
      }
    )

  val pins =
    PinsStore(
      PreferenceDataStoreFactory.create(scope = storeScope) {
        context.preferencesDataStoreFile("pins")
      }
    )
}

class CyclesApplication : Application() {
  lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    configureOsmdroid(this)
    graph = AppGraph(this)
  }
}
