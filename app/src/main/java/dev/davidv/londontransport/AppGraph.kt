package dev.davidv.londontransport

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.davidv.londontransport.data.AccountStore
import dev.davidv.londontransport.data.ActiveHireStore
import dev.davidv.londontransport.data.FavouritesStore
import dev.davidv.londontransport.data.PinsStore
import dev.davidv.londontransport.data.RouteCache
import dev.davidv.londontransport.data.SavedLinesStore
import dev.davidv.londontransport.data.SettingsStore
import dev.davidv.londontransport.data.TFL_API_BASE
import dev.davidv.londontransport.data.TflLineClient
import dev.davidv.londontransport.data.StationCache
import dev.davidv.londontransport.data.TFL_BIKEPOINT_ENDPOINT
import dev.davidv.londontransport.data.TflBikePointClient
import dev.davidv.londontransport.layers.CycleHireLayer
import dev.davidv.londontransport.ipc.WorkoutBroadcast
import dev.davidv.londontransport.layers.MapLayer
import dev.davidv.londontransport.map.configureOsmdroid
import dev.davidv.londontransport.santander.DeviceId
import dev.davidv.londontransport.santander.SantanderClient
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

  val account =
    AccountStore(
      PreferenceDataStoreFactory.create(scope = storeScope) {
        context.preferencesDataStoreFile("account")
      }
    )

  val activeHire =
    ActiveHireStore(
      PreferenceDataStoreFactory.create(scope = storeScope) {
        context.preferencesDataStoreFile("hire")
      }
    )

  val workouts = WorkoutBroadcast(context)

  /**
   * The hire backend trusts a device id once it has been verified by SMS, so this has to be the same
   * string on every run. ANDROID_ID is exactly that, and it is what the official app sends.
   */
  private val deviceId =
    DeviceId(
      checkNotNull(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
      ) {
        "the device has no ANDROID_ID"
      }
    )

  val santander =
    SantanderClient(
      // A failing call to this backend hangs until its own gateway gives up at twenty seconds, so
      // the read timeout sits under that and the client retries instead of waiting it out.
      http.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
      deviceId,
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
