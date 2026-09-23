package dev.davidv.londontransport.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

private const val BLUE = 0xFF1A73E8.toInt()

/** osmdroid ships a grey arrow and a grey figure; people expect the blue dot. */
fun personMarker(density: Float): Bitmap {
  val radius = 9f * density
  val ring = 3f * density
  val glow = 4f * density
  val size = ((radius + ring + glow) * 2).toInt()
  val centre = size / 2f

  val bitmap = createBitmap(size)
  val canvas = Canvas(bitmap)
  val paint = Paint(Paint.ANTI_ALIAS_FLAG)

  paint.color = BLUE
  paint.alpha = 40
  canvas.drawCircle(centre, centre, radius + ring + glow, paint)

  paint.alpha = 255
  paint.color = Color.WHITE
  canvas.drawCircle(centre, centre, radius + ring, paint)

  paint.color = BLUE
  canvas.drawCircle(centre, centre, radius, paint)

  return bitmap
}

fun directionMarker(density: Float): Bitmap {
  val half = 13f * density
  val size = (half * 2).toInt()
  val centre = size / 2f

  val bitmap = createBitmap(size)
  val canvas = Canvas(bitmap)
  val chevron =
    Path().apply {
      moveTo(centre, centre - half * 0.85f)
      lineTo(centre + half * 0.68f, centre + half * 0.8f)
      lineTo(centre, centre + half * 0.4f)
      lineTo(centre - half * 0.68f, centre + half * 0.8f)
      close()
    }

  val outline =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 2.5f * density
      strokeJoin = Paint.Join.ROUND
      color = Color.WHITE
    }
  canvas.drawPath(chevron, outline)
  canvas.drawPath(chevron, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE })

  return bitmap
}

private fun createBitmap(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
