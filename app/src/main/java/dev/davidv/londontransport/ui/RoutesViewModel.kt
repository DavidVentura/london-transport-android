package dev.davidv.londontransport.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.davidv.londontransport.data.LinesResult
import dev.davidv.londontransport.data.RouteCache
import dev.davidv.londontransport.data.HeldRoute
import dev.davidv.londontransport.data.RouteCacheRead
import dev.davidv.londontransport.data.RouteChange
import dev.davidv.londontransport.data.RouteResult
import dev.davidv.londontransport.data.SavedLinesStore
import dev.davidv.londontransport.data.TflLineClient
import dev.davidv.londontransport.domain.TransitLine
import dev.davidv.londontransport.domain.TransportKind
import dev.davidv.londontransport.domain.LineId
import dev.davidv.londontransport.domain.MapFeature
import dev.davidv.londontransport.domain.RouteDirection
import dev.davidv.londontransport.domain.RoutePath
import dev.davidv.londontransport.domain.SavedLine
import dev.davidv.londontransport.layers.busStopFeature
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SavedRoute(
  val saved: SavedLine,
  val path: RoutePath?,
  val moved: Boolean,
  val unavailable: String?,
) {
  val line: LineId
    get() = saved.id
}

data class RoutesState(
  val routes: List<SavedRoute> = emptyList(),
  val catalogue: List<TransitLine> = emptyList(),
  val loadingCatalogue: Boolean = false,
  val error: String? = null,
) {
  private fun shown(kinds: Set<TransportKind>): List<SavedRoute> =
    routes.filter { it.saved.visible && it.saved.kind in kinds }

  fun drawable(kinds: Set<TransportKind>): List<SavedRoute> = shown(kinds)

  /** Every stop of every drawn route, as map features the same overlay can hit-test. */
  fun stopFeatures(kinds: Set<TransportKind>): List<MapFeature> =
    shown(kinds).flatMap { route ->
      val path = route.path ?: return@flatMap emptyList()
      path.stops.map {
        busStopFeature(it, path, lineLabel(route.line, route.saved.kind), route.saved.kind)
      }
    }
}

class RoutesViewModel(
  private val client: TflLineClient,
  private val saved: SavedLinesStore,
  private val cache: RouteCache,
) : ViewModel() {

  private val _state = MutableStateFlow(RoutesState())
  val state: StateFlow<RoutesState> = _state.asStateFlow()

  private var savedLines: List<SavedLine> = emptyList()

  init {
    viewModelScope.launch {
      saved.lines.collect { lines ->
        savedLines = lines
        refresh(lines, Refresh.IF_STALE)
      }
    }
  }

  fun refreshNow() {
    viewModelScope.launch { refresh(savedLines, Refresh.FORCED) }
  }

  fun setDirection(line: LineId, direction: RouteDirection) {
    viewModelScope.launch { saved.setDirection(line, direction) }
  }

  fun setVisible(line: LineId, visible: Boolean) {
    viewModelScope.launch { saved.setVisible(line, visible) }
  }

  fun save(line: TransitLine) {
    viewModelScope.launch { saved.add(line.id, line.kind, RouteDirection.INBOUND) }
  }

  fun forget(line: LineId) {
    viewModelScope.launch {
      saved.remove(line)
      cache.forget(line)
    }
  }

  fun loadCatalogue() {
    if (_state.value.catalogue.isNotEmpty() || _state.value.loadingCatalogue) return
    _state.update { it.copy(loadingCatalogue = true) }
    viewModelScope.launch {
      val result = client.lines()
      _state.update { current ->
        when (result) {
          is LinesResult.Success ->
            current.copy(loadingCatalogue = false, catalogue = result.lines.sortedWith(BY_NUMBER))
          is LinesResult.Unreachable ->
            current.copy(loadingCatalogue = false, error = "Could not reach TfL: ${result.reason}")
          is LinesResult.Unusable ->
            current.copy(loadingCatalogue = false, error = "Line list unreadable: ${result.reason}")
        }
      }
    }
  }

  /**
   * Draws from disk first, then replaces each route with whatever TfL says today. A route is a
   * timetable rather than a live count, so asking a few times a day is plenty — and hiding a line
   * re-emits the saved list, which should not cost a request at all.
   */
  private suspend fun refresh(lines: List<SavedLine>, refresh: Refresh) {
    if (lines.isEmpty()) {
      _state.update { it.copy(routes = emptyList()) }
      return
    }

    val held =
      when (val read = cache.read()) {
        is RouteCacheRead.Held -> read.routes
        RouteCacheRead.Empty -> emptyList()
        is RouteCacheRead.Unreadable -> {
          Log.w(TAG, "discarding route cache: ${read.reason}")
          emptyList()
        }
      }
    val previous = _state.value.routes.associateBy { it.line }
    _state.update { current ->
      current.copy(
        routes =
          lines.map { line ->
            val carried = previous[line.id]?.takeIf { it.saved.direction == line.direction }
            SavedRoute(
              saved = line,
              path = held.firstOrNull { it.matches(line) }?.path,
              moved = carried?.moved == true,
              unavailable = carried?.unavailable,
            )
          }
      )
    }

    val now = Instant.now()
    for (line in lines) {
      val age = held.firstOrNull { it.matches(line) }?.let { Duration.between(it.fetchedAt, now) }
      if (refresh == Refresh.IF_STALE && age != null && age < MAX_AGE) continue
      val fetched =
        when (val result = client.route(line.id, line.direction)) {
          is RouteResult.Success -> {
            val change = cache.put(result.path, Instant.now())
            _state.update { it.copy(error = null) }
            SavedRoute(line, result.path, change == RouteChange.MOVED, null)
          }
          RouteResult.NotServed -> {
            val why =
              if (line.kind == TransportKind.TUBE) "Unavailable — TfL publishes no route"
              else "Does not run this way"
            SavedRoute(line, null, false, why)
          }
          is RouteResult.Unreachable -> {
            _state.update { it.copy(error = "Offline — routes as last seen (${result.reason})") }
            null
          }
          is RouteResult.Unusable -> SavedRoute(line, null, false, "Route unreadable")
        }
      if (fetched != null) {
        _state.update { current ->
          current.copy(
            routes = current.routes.map { if (it.saved == line) fetched else it }
          )
        }
      }
    }
  }

  private enum class Refresh {
    IF_STALE,
    FORCED,
  }

  private companion object {
    const val TAG = "RoutesViewModel"

    /** A published route barely moves; the docking station counts are what need chasing. */
    val MAX_AGE: Duration = Duration.ofHours(4)

    /** "5" before "43" before "N5" before "X68", the way a Londoner reads a route list. */
    val BY_NUMBER =
      compareBy<TransitLine>(
        { if (it.kind == TransportKind.TUBE) 0 else 1 },
        { it.name.takeWhile { c -> !c.isDigit() } },
        { it.name.dropWhile { c -> !c.isDigit() }.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 },
        { it.name },
      )
  }
}

private fun HeldRoute.matches(line: SavedLine): Boolean =
  path.line == line.id && path.direction == line.direction
