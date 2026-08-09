package dev.davidv.londontransport.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.davidv.londontransport.domain.FeatureKey
import dev.davidv.londontransport.domain.GeoBounds
import dev.davidv.londontransport.domain.LatLon
import dev.davidv.londontransport.domain.MapCamera
import dev.davidv.londontransport.domain.MapFeature
import dev.davidv.londontransport.ui.availabilityColour
import dev.davidv.londontransport.ui.routeColour
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

private const val FOCUS_ZOOM = 18.0
private const val FIT_MAX_ZOOM = 16.5
private const val FIT_PADDING_DP = 40f
private const val MIN_ZOOM = 4.0
private const val MAX_ZOOM = 21.0
private const val TILE_SCALE = 2f

@Composable
fun FeatureMap(
  features: List<MapFeature>,
  selected: FeatureKey?,
  favourites: Set<FeatureKey>,
  routes: List<DrawnRoute>,
  initialCamera: MapCamera,
  focus: LatLon?,
  fit: GeoBounds?,
  darkTiles: Boolean,
  followLocation: Boolean,
  onSelect: (MapFeature?) -> Unit,
  onPlacePin: (LatLon) -> Unit,
  onCameraMoved: (MapCamera) -> Unit,
  onFocusHandled: () -> Unit,
  onFitApplied: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val density = LocalDensity.current.density
  val currentOnSelect by rememberUpdatedState(onSelect)
  val currentOnCameraMoved by rememberUpdatedState(onCameraMoved)
  val currentOnPlacePin by rememberUpdatedState(onPlacePin)

  val routeOverlay = remember { RouteOverlay(density) }

  val overlay = remember {
    FeatureOverlay(
      density = density,
      colourOf = { level -> availabilityColour(level).toArgb() },
      lineColourOf = { line, kind -> routeColour(line, kind).toArgb() },
      onSelect = { feature -> currentOnSelect(feature) },
      onPlacePin = { position -> currentOnPlacePin(position) },
    )
  }

  val mapView = remember {
    MapView(context).apply {
      setTileSource(TileSourceFactory.MAPNIK)
      setMultiTouchControls(true)
      zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
      // Half-size tiles against a 3-4x screen: enough detail steps to keep zooming into,
      // and osmdroid upscales past Mapnik's own limit of 19.
      setTilesScaledToDpi(false)
      setTilesScaleFactor(TILE_SCALE)
      setMinZoomLevel(MIN_ZOOM)
      setMaxZoomLevel(MAX_ZOOM)
      controller.setZoom(initialCamera.zoom)
      controller.setCenter(GeoPoint(initialCamera.center.latitude, initialCamera.center.longitude))
      overlays.add(routeOverlay)
      overlays.add(overlay)
      addMapListener(
        object : MapListener {
          override fun onScroll(event: ScrollEvent?): Boolean = report()

          override fun onZoom(event: ZoomEvent?): Boolean = report()

          private fun report(): Boolean {
            currentOnCameraMoved(
              MapCamera(LatLon(mapCenter.latitude, mapCenter.longitude), zoomLevelDouble)
            )
            return false
          }
        }
      )
    }
  }

  val locationOverlay = remember {
    MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
      setPersonIcon(personMarker(density))
      setDirectionIcon(directionMarker(density))
      setPersonAnchor(0.5f, 0.5f)
      setDirectionAnchor(0.5f, 0.5f)
    }
  }

  DisposableEffect(followLocation) {
    if (followLocation) {
      locationOverlay.enableMyLocation()
      mapView.overlays.add(locationOverlay)
      mapView.invalidate()
    }
    onDispose {
      if (followLocation) {
        locationOverlay.disableMyLocation()
        mapView.overlays.remove(locationOverlay)
      }
    }
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> mapView.onResume()
        Lifecycle.Event.ON_PAUSE -> mapView.onPause()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      mapView.onDetach()
    }
  }

  LaunchedEffect(fit) {
    if (fit != null) {
      // getWidth()/getHeight() feed the zoom computation, so this is worthless before layout.
      mapView.doOnLayout {
        mapView.zoomToBoundingBox(
          BoundingBox(fit.north, fit.east, fit.south, fit.west),
          false,
          (FIT_PADDING_DP * density).toInt(),
          FIT_MAX_ZOOM,
          null,
        )
      }
      onFitApplied()
    }
  }

  LaunchedEffect(focus) {
    if (focus != null) {
      mapView.controller.animateTo(GeoPoint(focus.latitude, focus.longitude), FOCUS_ZOOM, null)
      onFocusHandled()
    }
  }

  AndroidView(
    factory = { mapView },
    modifier = modifier,
    update = { view ->
      view.overlayManager.tilesOverlay.setColorFilter(
        if (darkTiles) TilesOverlay.INVERT_COLORS else null
      )
      routeOverlay.show(routes)
      overlay.show(features, selected, favourites)
      view.invalidate()
    },
  )
}
