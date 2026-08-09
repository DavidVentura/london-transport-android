package dev.davidv.londontransport.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import dev.davidv.londontransport.domain.LineId
import dev.davidv.londontransport.domain.RoutePath
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

data class DrawnRoute(val path: RoutePath, val colour: Int)

/**
 * Sits below the station dots so a route never hides the thing you are looking for. Points are
 * projected per frame; a bus route is a few hundred of them, which costs nothing next to the tiles.
 */
class RouteOverlay(private val density: Float) : Overlay() {
  private var placed: List<Placed> = emptyList()

  private val casing =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = Color.WHITE
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
    }
  private val stroke =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
    }
  private val scratch = Point()
  private val path = Path()

  private class Placed(val line: LineId, val colour: Int, val segments: List<List<GeoPoint>>)

  fun show(routes: List<DrawnRoute>) {
    placed =
      routes.map { drawn ->
        Placed(
          line = drawn.path.line,
          colour = drawn.colour,
          segments =
            drawn.path.segments.map { segment ->
              segment.map { GeoPoint(it.latitude, it.longitude) }
            },
        )
      }
  }

  override fun draw(canvas: Canvas, projection: Projection) {
    if (placed.isEmpty()) return
    casing.strokeWidth = 8f * density
    stroke.strokeWidth = 4.5f * density

    for (route in placed) {
      path.reset()
      for (segment in route.segments) {
        segment.forEachIndexed { index, point ->
          projection.toPixels(point, scratch)
          if (index == 0) path.moveTo(scratch.x.toFloat(), scratch.y.toFloat())
          else path.lineTo(scratch.x.toFloat(), scratch.y.toFloat())
        }
      }
      canvas.drawPath(path, casing)
      stroke.color = route.colour
      canvas.drawPath(path, stroke)
    }
  }
}
