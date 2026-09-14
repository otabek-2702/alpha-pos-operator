package __PACKAGE__

/** Pure decisions shared by the native scanner and the JVM safety tests. */
object OperatorRecordingPolicy {
  const val MAX_FILE_BYTES = 50_000_000L
  const val STABLE_MS = 60_000L
  const val WITHOUT_PHONE_PERMISSION_MS = 180_000L

  fun isHistorical(modified: Long, baselineAt: Long): Boolean = modified > 0 && modified < baselineAt

  fun canQueue(size: Long, stableSince: Long, firstSeen: Long, now: Long, callActive: Boolean, hasPhonePermissions: Boolean): Boolean =
    size > 0 && now - stableSince >= STABLE_MS && !callActive &&
      (hasPhonePermissions || now - firstSeen >= WITHOUT_PHONE_PERMISSION_MS)

  fun isTooLarge(size: Long): Boolean = size > MAX_FILE_BYTES

  fun retrySeconds(attempts: Int): Long = minOf(3600L, 15L * (1L shl attempts.coerceIn(0, 8)))
}
