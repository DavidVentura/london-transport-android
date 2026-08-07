package dev.davidv.londoncycles.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import dev.davidv.londoncycles.domain.AvailabilityLevel
import dev.davidv.londoncycles.domain.FeatureKey
import dev.davidv.londoncycles.domain.LatLon
import dev.davidv.londoncycles.domain.LineId
import dev.davidv.londoncycles.domain.MapFeature
import dev.davidv.londoncycles.domain.MarkerStyle
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

private const val LABELLED_FROM_ZOOM = 15.0
private const val STOPS_FROM_ZOOM = 13.5
private const val MEDIUM_FROM_ZOOM = 13.0
private const val PIN_COLOUR = 0xFF7B1FA2.toInt()

/**
 * Draws a whole layer as flat shapes in one pass instead of one [org.osmdroid.views.overlay.Marker]
 * per point — the cycle hire network is ~800 stations, which markers do not survive.
 */
class FeatureOverlay(
  private val density: Float,
  private val colourOf: (AvailabilityLevel) -> Int,
  private val lineColourOf: (LineId) -> Int,
  private val onSelect: (MapFeature?) -> Unit,
  private val onPlacePin: (LatLon) -> Unit,
) : Overlay() {

  private var placed: List<Placed> = emptyList()
  private var selected: FeatureKey? = null
  private var favourites: Set<FeatureKey> = emptySet()

  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val outline =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = Color.WHITE
    }
  private val highlight =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = Color.BLACK
    }
  private val badgeText =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textAlign = Paint.Align.CENTER
      isFakeBoldText = true
    }
  private val pinLabel =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      textAlign = Paint.Align.CENTER
      isFakeBoldText = true
      textSize = 12f * density
      setShadowLayer(3f * density, 0f, 0f, Color.WHITE)
    }
  private val stopRing =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private val scratch = Point()
  private val pinPath = Path()

  private class Placed(val feature: MapFeature, val point: GeoPoint)

  fun show(features: List<MapFeature>, selected: FeatureKey?, favourites: Set<FeatureKey>) {
    placed = features.map { Placed(it, GeoPoint(it.position.latitude, it.position.longitude)) }
    this.selected = selected
    this.favourites = favourites
  }

  override fun draw(canvas: Canvas, projection: Projection) {
    val radius = dotRadiusFor(projection.zoomLevel)
    val margin = pinHeight + radius * 2f
    outline.strokeWidth = radius * 0.2f
    highlight.strokeWidth = radius * 0.35f
    badgeText.textSize = radius * 1.3f

    for (entry in placed) {
      projection.toPixels(entry.point, scratch)
      val x = scratch.x.toFloat()
      val y = scratch.y.toFloat()
      if (x < -margin || y < -margin || x > canvas.width + margin || y > canvas.height + margin) {
        continue
      }

      when (val marker = entry.feature.marker) {
        MarkerStyle.Dot -> drawDot(canvas, entry.feature, x, y, radius, projection.zoomLevel)
        MarkerStyle.Pin -> drawPin(canvas, entry.feature, x, y, projection.zoomLevel)
        is MarkerStyle.Stop ->
          if (projection.zoomLevel >= STOPS_FROM_ZOOM) {
            drawStop(canvas, entry.feature, marker.line, x, y)
          }
      }
    }
  }

  private fun drawDot(
    canvas: Canvas,
    feature: MapFeature,
    x: Float,
    y: Float,
    radius: Float,
    zoom: Double,
  ) {
    fill.color = colourOf(feature.level)
    if (feature.key in favourites) {
      // Squares read as starred at a glance, including for anyone who cannot tell the
      // red dots from the green ones.
      val half = radius * 1.1f
      canvas.drawRect(x - half, y - half, x + half, y + half, fill)
      canvas.drawRect(x - half, y - half, x + half, y + half, outline)
      if (feature.key == selected) {
        val reach = half * 1.6f
        canvas.drawRect(x - reach, y - reach, x + reach, y + reach, highlight)
      }
    } else {
      canvas.drawCircle(x, y, radius, fill)
      canvas.drawCircle(x, y, radius, outline)
      if (feature.key == selected) {
        canvas.drawCircle(x, y, radius * 1.6f, highlight)
      }
    }
    if (zoom >= LABELLED_FROM_ZOOM) {
      canvas.drawText(feature.badge, x, y + radius * 0.45f, badgeText)
    }
  }

  /** Anchored at its tip, so the point the user pressed stays under the pin. */
  private fun drawPin(canvas: Canvas, feature: MapFeature, x: Float, y: Float, zoom: Double) {
    val head = pinHeadRadius
    val centreY = y - pinHeight + head

    pinPath.reset()
    pinPath.moveTo(x, y)
    pinPath.lineTo(x - head * 0.75f, centreY + head * 0.66f)
    pinPath.lineTo(x + head * 0.75f, centreY + head * 0.66f)
    pinPath.close()

    fill.color = PIN_COLOUR
    outline.strokeWidth = 2f * density
    canvas.drawPath(pinPath, fill)
    canvas.drawCircle(x, centreY, head, fill)
    canvas.drawCircle(x, centreY, head, outline)
    fill.color = Color.WHITE
    canvas.drawCircle(x, centreY, head * 0.35f, fill)
    if (feature.key == selected) {
      highlight.strokeWidth = 2.5f * density
      canvas.drawCircle(x, centreY, head * 1.5f, highlight)
    }
    if (zoom >= MEDIUM_FROM_ZOOM) {
      canvas.drawText(feature.title, x, y + 14f * density, pinLabel)
    }
  }

  /** A ring rather than a disc, so a stop never reads as a docking station. */
  private fun drawStop(canvas: Canvas, feature: MapFeature, line: LineId, x: Float, y: Float) {
    val radius = stopRadius
    fill.color = Color.WHITE
    canvas.drawCircle(x, y, radius, fill)
    stopRing.color = lineColourOf(line)
    stopRing.strokeWidth = radius * 0.55f
    canvas.drawCircle(x, y, radius, stopRing)
    if (feature.key == selected) {
      highlight.strokeWidth = 2.5f * density
      canvas.drawCircle(x, y, radius * 1.9f, highlight)
    }
  }

  override fun onSingleTapConfirmed(event: MotionEvent, mapView: MapView): Boolean {
    val projection = mapView.projection
    val tap = projection.toPixels(projection.fromPixels(event.x.toInt(), event.y.toInt()), null)
    val slop = dotRadiusFor(projection.zoomLevel) + 12f * density

    var nearest: MapFeature? = null
    var nearestDistance = Float.MAX_VALUE
    for (entry in placed) {
      projection.toPixels(entry.point, scratch)
      val marker = entry.feature.marker
      if (marker is MarkerStyle.Stop && projection.zoomLevel < STOPS_FROM_ZOOM) continue
      val reach =
        when (marker) {
          MarkerStyle.Pin -> pinHeadRadius + 8f * density
          is MarkerStyle.Stop -> stopRadius + 10f * density
          MarkerStyle.Dot -> slop
        }
      val anchorY = if (marker == MarkerStyle.Pin) pinHeight - pinHeadRadius else 0f
      val dx = (scratch.x - tap.x).toFloat()
      val dy = (scratch.y - anchorY - tap.y).toFloat()
      val distance = dx * dx + dy * dy
      if (distance <= reach * reach && distance <= nearestDistance) {
        nearestDistance = distance
        nearest = entry.feature
      }
    }

    onSelect(nearest)
    return nearest != null
  }

  override fun onLongPress(event: MotionEvent, mapView: MapView): Boolean {
    val pressed = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt())
    onPlacePin(LatLon(pressed.latitude, pressed.longitude))
    return true
  }

  private val stopRadius: Float
    get() = 5f * density

  private val pinHeadRadius: Float
    get() = 8f * density

  private val pinHeight: Float
    get() = 26f * density

  private fun dotRadiusFor(zoom: Double): Float =
    density *
      when {
        zoom >= LABELLED_FROM_ZOOM -> 11f
        zoom >= MEDIUM_FROM_ZOOM -> 6f
        else -> 3.5f
      }
}
