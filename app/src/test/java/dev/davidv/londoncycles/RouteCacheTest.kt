package dev.davidv.londoncycles

import dev.davidv.londoncycles.data.RouteCache
import dev.davidv.londoncycles.data.RouteCacheRead
import dev.davidv.londoncycles.data.RouteChange
import dev.davidv.londoncycles.domain.LatLon
import dev.davidv.londoncycles.domain.LineId
import dev.davidv.londoncycles.domain.RouteDirection
import dev.davidv.londoncycles.domain.RoutePath
import dev.davidv.londoncycles.domain.RouteStop
import dev.davidv.londoncycles.domain.StopId
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun route(
  line: String = "43",
  direction: RouteDirection = RouteDirection.INBOUND,
  towards: String = "Halliwick Park",
  points: List<Pair<Double, Double>> = listOf(51.505 to -0.086, 51.508 to -0.089),
) =
  RoutePath(
    line = LineId(line),
    direction = direction,
    towards = towards,
    segments = listOf(points.map { LatLon(it.first, it.second) }),
    stops =
      listOf(
        RouteStop(StopId("490000254S"), "London Bridge", LatLon(51.505, -0.086), "A"),
        RouteStop(StopId("490009333F"), towards, LatLon(51.613, -0.153), null),
      ),
  )

class RouteCacheTest {
  @get:Rule val folder = TemporaryFolder()

  private fun cache(name: String = "routes.json") = RouteCache(File(folder.root, name))

  @Test
  fun `a route survives a round trip with its geometry intact`() = runTest {
    val cache = cache()
    cache.put(route())

    val held = (cache.read() as RouteCacheRead.Held).routes.single()

    assertEquals(LineId("43"), held.line)
    assertEquals(RouteDirection.INBOUND, held.direction)
    assertEquals("Halliwick Park", held.towards)
    assertEquals(LatLon(51.505, -0.086), held.segments.single().first())
    assertEquals(listOf("London Bridge", "Halliwick Park"), held.stops.map { it.name })
    assertEquals("A", held.stops.first().letter)
    assertEquals(StopId("490009333F"), held.stops.last().id)
  }

  @Test
  fun `a route that moves is reported, an identical one is not`() = runTest {
    val cache = cache()

    assertEquals(RouteChange.FIRST_SEEN, cache.put(route()))
    assertEquals(RouteChange.UNCHANGED, cache.put(route()))
    assertEquals(
      RouteChange.MOVED,
      cache.put(route(points = listOf(51.505 to -0.086, 51.600 to -0.150))),
    )
  }

  @Test
  fun `the two directions of one line are held apart`() = runTest {
    val cache = cache()
    cache.put(route(direction = RouteDirection.INBOUND, towards = "Halliwick Park"))

    assertEquals(
      RouteChange.FIRST_SEEN,
      cache.put(route(direction = RouteDirection.OUTBOUND, towards = "London Bridge")),
    )
    assertEquals(2, (cache.read() as RouteCacheRead.Held).routes.size)
  }

  @Test
  fun `forgetting a line drops both its directions and leaves the others`() = runTest {
    val cache = cache()
    cache.put(route(line = "43", direction = RouteDirection.INBOUND))
    cache.put(route(line = "43", direction = RouteDirection.OUTBOUND))
    cache.put(route(line = "N5"))

    cache.forget(LineId("43"))

    val held = (cache.read() as RouteCacheRead.Held).routes
    assertEquals(listOf(LineId("N5")), held.map { it.line })
  }

  @Test
  fun `a corrupt cache is reported rather than thrown`() = runTest {
    File(folder.root, "broken.json").writeText("[[[not json")

    assertTrue(cache("broken.json").read() is RouteCacheRead.Unreadable)
  }

  @Test
  fun `nothing on disk is not an error`() = runTest {
    assertEquals(RouteCacheRead.Empty, cache("absent.json").read())
  }
}
