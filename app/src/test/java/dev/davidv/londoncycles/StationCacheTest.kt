package dev.davidv.londoncycles

import dev.davidv.londoncycles.data.CacheRead
import dev.davidv.londoncycles.data.StationCache
import dev.davidv.londoncycles.domain.AvailabilityLevel
import dev.davidv.londoncycles.domain.BikeCounts
import dev.davidv.londoncycles.domain.LatLon
import dev.davidv.londoncycles.domain.Station
import dev.davidv.londoncycles.domain.StationId
import dev.davidv.londoncycles.domain.StationState
import dev.davidv.londoncycles.domain.StationStatus
import dev.davidv.londoncycles.layers.stationFeature
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val live =
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

class StationCacheTest {
  @get:Rule val folder = TemporaryFolder()

  private fun cache(name: String = "stations.json") = StationCache(File(folder.root, name))

  @Test
  fun `a station comes back placed but with no counts`() = runTest {
    val cache = cache()
    cache.write(listOf(live))

    val stations = (cache.read() as CacheRead.Hit).stations

    assertEquals(listOf(live.id), stations.map { it.id })
    assertEquals(listOf(live.name), stations.map { it.name })
    assertEquals(live.position, stations.single().position)
    assertEquals(StationStatus.Unknown, stations.single().status)
  }

  @Test
  fun `an unknown station reads as grey with no count on its badge`() = runTest {
    val cache = cache()
    cache.write(listOf(live))

    val feature = stationFeature((cache.read() as CacheRead.Hit).stations.single())

    assertEquals(AvailabilityLevel.UNKNOWN, feature.level)
    assertEquals("?", feature.badge)
    assertEquals("River Street, Clerkenwell", feature.title)
    assertTrue(feature.summary.startsWith("Bikes unknown"))
  }

  @Test
  fun `nothing on disk is not an error`() = runTest {
    assertEquals(CacheRead.Empty, cache("absent.json").read())
  }

  @Test
  fun `a corrupt cache is reported rather than thrown`() = runTest {
    File(folder.root, "broken.json").writeText("{not json")

    assertTrue(cache("broken.json").read() is CacheRead.Unreadable)
  }

  @Test
  fun `writing twice leaves no scratch file behind`() = runTest {
    val cache = cache()
    cache.write(listOf(live))
    cache.write(listOf(live, live.copy(id = StationId("BikePoints_2"))))

    assertEquals(2, (cache.read() as CacheRead.Hit).stations.size)
    assertEquals(listOf("stations.json"), folder.root.list()!!.sorted())
  }
}
