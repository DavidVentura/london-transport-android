package dev.davidv.londontransport.domain

/**
 * Told when a ride begins, so that whatever tracks workouts on this phone can
 * start one without being asked. Nothing here depends on anyone listening.
 */
fun interface WorkoutSignal {
  fun cyclingStarted()
}
