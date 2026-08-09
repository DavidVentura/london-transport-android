package dev.davidv.londontransport.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.davidv.londontransport.data.FavouritesStore
import dev.davidv.londontransport.data.PinsStore
import dev.davidv.londontransport.data.SettingsStore
import dev.davidv.londontransport.domain.BikeDisplay
import dev.davidv.londontransport.domain.FeatureKey
import dev.davidv.londontransport.domain.GeoBounds
import dev.davidv.londontransport.domain.LatLon
import dev.davidv.londontransport.domain.boundsAround
import dev.davidv.londontransport.domain.MapCamera
import dev.davidv.londontransport.domain.MapFeature
import dev.davidv.londontransport.domain.TransportKind
import dev.davidv.londontransport.layers.LayerLoad
import dev.davidv.londontransport.layers.MapLayer
import dev.davidv.londontransport.layers.pinFeature
import dev.davidv.londontransport.layers.pinIdOf
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CyclesState(
  val layerTitle: String,
  val layerFeatures: List<MapFeature> = emptyList(),
  val pinFeatures: List<MapFeature> = emptyList(),
  val favourites: Set<FeatureKey> = emptySet(),
  val bikeDisplay: BikeDisplay = BikeDisplay.ALL,
  val hiddenKinds: Set<TransportKind> = emptySet(),
  val refreshing: Boolean = false,
  val offline: Boolean = false,
  val error: String? = null,
  val fetchedAt: Instant? = null,
  val selected: FeatureKey? = null,
  val focus: LatLon? = null,
  val fitBounds: GeoBounds? = null,
  val pendingPin: LatLon? = null,
) {
  fun shows(kind: TransportKind): Boolean = kind !in hiddenKinds

  /** Hand-placed pins stay on the map through every filter; they are already deliberate. */
  val visibleFeatures: List<MapFeature>
    get() =
      when (bikeDisplay) {
        BikeDisplay.NONE -> pinFeatures
        BikeDisplay.FAVOURITES -> layerFeatures.filter { it.key in favourites } + pinFeatures
        BikeDisplay.ALL -> layerFeatures + pinFeatures
      }

  val favouriteFeatures: List<MapFeature>
    get() = layerFeatures.filter { it.key in favourites }.sortedBy { it.title }
}

val LONDON: MapCamera = MapCamera(LatLon(51.5074, -0.1278), zoom = 13.0)

private const val FIT_PADDING = 0.18

class CyclesViewModel(
  private val layer: MapLayer,
  private val favourites: FavouritesStore,
  private val pins: PinsStore,
  private val settings: SettingsStore,
) : ViewModel() {

  private val _state = MutableStateFlow(CyclesState(layerTitle = layer.title))
  val state: StateFlow<CyclesState> = _state.asStateFlow()

  /** Survives tab switches, which tear the [org.osmdroid.views.MapView] down and build it again. */
  var camera: MapCamera = LONDON

  init {
    viewModelScope.launch {
      val onDisk = layer.cached()
      // Loses to whichever live fetch got there first; the cache is only ever the head start.
      _state.update {
        if (onDisk.isEmpty() || it.layerFeatures.isNotEmpty()) it
        else it.copy(layerFeatures = onDisk, offline = true)
      }
    }
    viewModelScope.launch {
      favourites.favourites.collect { keys -> _state.update { it.copy(favourites = keys) } }
    }
    viewModelScope.launch {
      pins.pins.collect { saved ->
        _state.update { it.copy(pinFeatures = saved.map(::pinFeature)) }
      }
    }
    viewModelScope.launch {
      settings.bikeDisplay.collect { display -> _state.update { it.copy(bikeDisplay = display) } }
    }
    viewModelScope.launch {
      settings.hiddenKinds.collect { hidden -> _state.update { it.copy(hiddenKinds = hidden) } }
    }
    viewModelScope.launch { fitToSavedPlaces() }
    refresh()
  }

  /**
   * Opens on whatever the user saved rather than on central London, once, before they have touched
   * the map. Waits for both stores and for the first load to settle, otherwise the frame would
   * depend on which of them answered first.
   */
  private suspend fun fitToSavedPlaces() {
    val starred = favourites.favourites.first()
    val saved = pins.pins.first()
    if (starred.isEmpty() && saved.isEmpty()) return

    val settled =
      _state.first { it.layerFeatures.isNotEmpty() || it.fetchedAt != null || it.error != null }
    val points =
      settled.layerFeatures.filter { it.key in starred }.map { it.position } +
        saved.map { it.position }
    val bounds = boundsAround(points, FIT_PADDING) ?: return
    _state.update { it.copy(fitBounds = bounds) }
  }

  fun fitApplied() {
    _state.update { it.copy(fitBounds = null) }
  }

  fun refresh() {
    if (_state.value.refreshing) return
    _state.update { it.copy(refreshing = true) }
    viewModelScope.launch {
      val load = layer.load()
      _state.update { current ->
        when (load) {
          is LayerLoad.Failed -> current.copy(refreshing = false, error = load.message)
          is LayerLoad.Offline ->
            current.copy(
              refreshing = false,
              error = load.message,
              layerFeatures = load.features,
              fetchedAt = null,
              offline = true,
            )
          is LayerLoad.Loaded ->
            current.copy(
              refreshing = false,
              error = null,
              layerFeatures = load.features,
              fetchedAt = Instant.now(),
              offline = false,
            )
        }
      }
    }
  }

  fun refreshIfOlderThan(age: Duration) {
    val fetchedAt = _state.value.fetchedAt
    if (fetchedAt == null || Duration.between(fetchedAt, Instant.now()) > age) refresh()
  }

  fun select(key: FeatureKey?) {
    _state.update { it.copy(selected = key) }
  }

  fun toggleFavourite(key: FeatureKey) {
    viewModelScope.launch { favourites.toggle(key) }
  }

  /** Written to disk and read back through the flow above, so there is one copy of the answer. */
  fun toggleKind(kind: TransportKind) {
    val wanted = kind in _state.value.hiddenKinds
    viewModelScope.launch { settings.setKindVisible(kind, wanted) }
  }

  fun cycleBikeDisplay() {
    val wanted = _state.value.bikeDisplay.next()
    viewModelScope.launch { settings.setBikeDisplay(wanted) }
  }

  fun focusOn(feature: MapFeature) {
    _state.update { it.copy(focus = feature.position, selected = feature.key) }
  }

  fun focusHandled() {
    _state.update { it.copy(focus = null) }
  }

  fun startPin(position: LatLon) {
    _state.update { it.copy(pendingPin = position, selected = null) }
  }

  fun cancelPin() {
    _state.update { it.copy(pendingPin = null) }
  }

  fun confirmPin(name: String) {
    val position = checkNotNull(_state.value.pendingPin) { "no pin was being placed" }
    _state.update { it.copy(pendingPin = null) }
    viewModelScope.launch { pins.add(name, position) }
  }

  fun deletePin(key: FeatureKey) {
    _state.update { it.copy(selected = null) }
    viewModelScope.launch { pins.remove(pinIdOf(key)) }
  }
}
