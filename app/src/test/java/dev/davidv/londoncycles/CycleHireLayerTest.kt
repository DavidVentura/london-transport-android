package dev.davidv.londoncycles

import dev.davidv.londoncycles.data.StationCache
import dev.davidv.londoncycles.data.TflBikePointClient
import dev.davidv.londoncycles.domain.AvailabilityLevel
import dev.davidv.londoncycles.domain.BikeCounts
import dev.davidv.londoncycles.domain.LatLon
import dev.davidv.londoncycles.domain.Station
import dev.davidv.londoncycles.domain.StationId
import dev.davidv.londoncycles.domain.StationState
import dev.davidv.londoncycles.domain.StationStatus
import dev.davidv.londoncycles.layers.CycleHireLayer
import dev.davidv.londoncycles.layers.LayerLoad
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Nothing is listening here, so the fetch fails the way it does on a train. */
private const val NOWHERE = "http://127.0.0.1:1/BikePoint"

private val station =
  Station(
    id = StationId("BikePoints_1"),
    name = "River Street, Clerkenwell",
    position = LatLon(51.529163, -0.10997),
    status =
      StationStatus.Live(
        bikes = BikeCounts(standard = 7, electric = 2),
        emptyDocks = 3,
        capacity = 14,
        state = StationState.OPERATIONAL,
      ),
  )

class CycleHireLayerTest {
  @get:Rule val folder = TemporaryFolder()

  private fun layer(cache: StationCache) =
    CycleHireLayer(TflBikePointClient(OkHttpClient(), NOWHERE), cache)

  @Test
  fun `an unreachable feed serves the cached positions and says why`() = runTest {
    val cache = StationCache(File(folder.root, "stations.json"))
    cache.write(listOf(station))

    val load = layer(cache).load()

    val offline = load as LayerLoad.Offline
    assertEquals("River Street, Clerkenwell", offline.features.single().title)
    assertEquals(AvailabilityLevel.UNKNOWN, offline.features.single().level)
    assertTrue(offline.message, offline.message.startsWith("Could not reach TfL"))
  }

  @Test
  fun `with nothing cached there is nothing to show`() = runTest {
    val load = layer(StationCache(File(folder.root, "absent.json"))).load()

    assertTrue(load.toString(), load is LayerLoad.Failed)
  }
}
