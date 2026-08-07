package dev.davidv.londoncycles.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.davidv.londoncycles.data.LinesResult
import dev.davidv.londoncycles.data.RouteCache
import dev.davidv.londoncycles.data.RouteCacheRead
import dev.davidv.londoncycles.data.RouteChange
import dev.davidv.londoncycles.data.RouteResult
import dev.davidv.londoncycles.data.SavedLinesStore
import dev.davidv.londoncycles.data.TflLineClient
import dev.davidv.londoncycles.domain.BusLine
import dev.davidv.londoncycles.domain.LineId
import dev.davidv.londoncycles.domain.MapFeature
import dev.davidv.londoncycles.domain.RouteDirection
import dev.davidv.londoncycles.domain.RoutePath
import dev.davidv.londoncycles.domain.SavedLine
import dev.davidv.londoncycles.layers.busStopFeature
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
  val catalogue: List<BusLine> = emptyList(),
  val loadingCatalogue: Boolean = false,
  val error: String? = null,
) {
  private val shown: List<SavedRoute>
    get() = routes.filter { it.saved.visible }

  val drawable: List<RoutePath>
    get() = shown.mapNotNull { it.path }

  /** Every stop of every drawn route, as map features the same overlay can hit-test. */
  val stopFeatures: List<MapFeature>
    get() =
      shown.flatMap { route ->
        val path = route.path ?: return@flatMap emptyList()
        path.stops.map { busStopFeature(it, path, route.line.value) }
      }
}

class RoutesViewModel(
  private val client: TflLineClient,
  private val saved: SavedLinesStore,
  private val cache: RouteCache,
) : ViewModel() {

  private val _state = MutableStateFlow(RoutesState())
  val state: StateFlow<RoutesState> = _state.asStateFlow()

  /** Hiding a line re-emits the saved list; TfL should not be asked again for what we hold. */
  private val alreadyFetched = mutableSetOf<Pair<LineId, RouteDirection>>()

  init {
    viewModelScope.launch { saved.lines.collect { lines -> refresh(lines) } }
  }

  fun setDirection(line: LineId, direction: RouteDirection) {
    viewModelScope.launch { saved.setDirection(line, direction) }
  }

  fun setVisible(line: LineId, visible: Boolean) {
    viewModelScope.launch { saved.setVisible(line, visible) }
  }

  fun save(line: LineId) {
    viewModelScope.launch { saved.add(line, RouteDirection.INBOUND) }
  }

  fun forget(line: LineId) {
    alreadyFetched.removeAll { it.first == line }
    viewModelScope.launch {
      saved.remove(line)
      cache.forget(line)
    }
  }

  fun loadCatalogue() {
    if (_state.value.catalogue.isNotEmpty() || _state.value.loadingCatalogue) return
    _state.update { it.copy(loadingCatalogue = true) }
    viewModelScope.launch {
      val result = client.busLines()
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

  /** Draws from disk first, then replaces each route with whatever TfL says today. */
  private suspend fun refresh(lines: List<SavedLine>) {
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
              path =
                held.firstOrNull { it.line == line.id && it.direction == line.direction },
              moved = carried?.moved == true,
              unavailable = carried?.unavailable,
            )
          }
      )
    }

    for (line in lines) {
      if (line.id to line.direction in alreadyFetched) continue
      val fetched =
        when (val result = client.route(line.id, line.direction)) {
          is RouteResult.Success -> {
            val change = cache.put(result.path)
            alreadyFetched += line.id to line.direction
            _state.update { it.copy(error = null) }
            SavedRoute(line, result.path, change == RouteChange.MOVED, null)
          }
          RouteResult.NotServed -> {
            alreadyFetched += line.id to line.direction
            SavedRoute(line, null, false, "Does not run this way")
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

  private companion object {
    const val TAG = "RoutesViewModel"

    /** "5" before "43" before "N5" before "X68", the way a Londoner reads a route list. */
    val BY_NUMBER =
      compareBy<BusLine>(
        { it.name.takeWhile { c -> !c.isDigit() } },
        { it.name.dropWhile { c -> !c.isDigit() }.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 },
        { it.name },
      )
  }
}
