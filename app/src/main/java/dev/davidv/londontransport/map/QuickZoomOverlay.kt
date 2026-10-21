package dev.davidv.londontransport.map

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.log2
import org.osmdroid.api.IGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

private const val QUICK_SCALE_SPEED = 0.8

/**
 * One-finger zoom: double tap, keep the finger down and drag down to zoom in or up to zoom out. A
 * plain double tap still zooms in by one level.
 *
 * osmdroid pinches through its own MultiTouchController, so the platform's quick scale is wired in
 * here. Two-finger pinches never pass [Gesture.Pending], so they stay with osmdroid.
 *
 * Owns the whole second tap, so it must sit above every overlay that reacts to a long press:
 * [android.view.GestureDetector] arms its long-press timer on the second down and never disarms it
 * while the finger moves, so without this the drag would also drop a pin.
 */
class QuickZoomOverlay(context: Context, private val mapView: MapView) : Overlay() {

  private sealed interface Gesture {
    data object Idle : Gesture

    class Pending(val x: Float, val y: Float) : Gesture

    class Scaling(val x: Float, val y: Float, val anchor: IGeoPoint) : Gesture
  }

  private var gesture: Gesture = Gesture.Idle

  private val detector =
    ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
          override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            val pending = gesture as? Gesture.Pending ?: return false
            gesture =
              Gesture.Scaling(
                pending.x,
                pending.y,
                mapView.projection.fromPixels(pending.x.toInt(), pending.y.toInt()),
              )
            return true
          }

          override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaling = gesture as? Gesture.Scaling ?: return false
            mapView.setZoomLevel(
              mapView.zoomLevelDouble + QUICK_SCALE_SPEED * log2(detector.scaleFactor.toDouble())
            )
            val moved = mapView.projection.toPixels(scaling.anchor, null)
            mapView.scrollBy(moved.x - scaling.x.toInt(), moved.y - scaling.y.toInt())
            return true
          }
        },
      )
      .apply { isQuickScaleEnabled = true }

  override fun onDoubleTap(event: MotionEvent, mapView: MapView): Boolean {
    gesture = Gesture.Pending(event.x, event.y)
    return true
  }

  override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
    detector.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_UP -> {
        val ended = gesture
        if (ended is Gesture.Pending) {
          mapView.controller.zoomInFixing(ended.x.toInt(), ended.y.toInt())
        }
        gesture = Gesture.Idle
      }
      // A second finger turns this into a pinch, which osmdroid handles itself.
      MotionEvent.ACTION_POINTER_DOWN,
      MotionEvent.ACTION_CANCEL -> gesture = Gesture.Idle
    }
    return false
  }

  override fun onLongPress(event: MotionEvent, mapView: MapView): Boolean =
    gesture != Gesture.Idle
}
