package autopoll

object RecoverySchedule {
  fun intervalMillis(seconds: Int): Long = seconds.coerceIn(90,120)*1000L
}
