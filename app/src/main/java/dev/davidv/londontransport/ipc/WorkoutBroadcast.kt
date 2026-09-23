package dev.davidv.londontransport.ipc

import android.content.Context
import android.content.Intent
import dev.davidv.londontransport.domain.WorkoutSignal

private const val WITHOUTINGS = "dev.davidv.withoutings"
private const val ACTION_START_WORKOUT = "dev.davidv.withoutings.action.START_WORKOUT"
private const val EXTRA_ACTIVITY = "dev.davidv.withoutings.extra.ACTIVITY"

private const val WITHOUTINGS_CYCLING_ID = 6

/**
 * The broadcast has to name the package, because an implicit one has not reached
 * a manifest receiver since Android 8. Naming a package that is not installed is
 * not an error: the send lands nowhere and the ride carries on.
 */
class WorkoutBroadcast(private val context: Context) : WorkoutSignal {
  override fun cyclingStarted() {
    context.sendBroadcast(
      Intent(ACTION_START_WORKOUT)
        .setPackage(WITHOUTINGS)
        .putExtra(EXTRA_ACTIVITY, WITHOUTINGS_CYCLING_ID)
    )
  }
}
