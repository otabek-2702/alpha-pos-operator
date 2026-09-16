package __PACKAGE__

/** Pure self-update decisions shared by the native updater and the JVM safety tests. */
object OperatorUpdatePolicy {
  const val RELEASES = "https://github.com/otabek-2702/smart-pos-operator-releases/releases/"
  const val DEFAULT_SOURCE = RELEASES + "latest/download/update.json"
  /** Emulator host alias; only honoured on emulators, for the self-update regression test. */
  const val TEST_HOST = "http://10.0.2.2:"
  const val CHECK_INTERVAL_MS = 30 * 60_000L
  const val FIRST_CHECK_DELAY_MS = 60_000L
  const val QUIET_AFTER_CALL_MS = 60_000L
  const val INSTALL_TIMEOUT_MS = 10 * 60_000L
  const val MAX_APK_BYTES = 200_000_000L

  private val VERSION_NAME = Regex("[0-9]{1,4}(\\.[0-9]{1,4}){1,3}")
  private val SHA256 = Regex("[0-9a-f]{64}")
  private val APK_NAME = Regex("[A-Za-z0-9._-]{1,120}\\.apk")

  fun isTestSource(source: String): Boolean = source.startsWith(TEST_HOST) && !source.contains('@')

  /** The APK must come from the same place as the manifest that announced it. */
  fun isAllowedApkUrl(url: String, source: String): Boolean {
    val name = url.substringAfterLast('/')
    if (!APK_NAME.matches(name) || url.contains("..") || url.contains('?') || url.contains('#')) return false
    return if (isTestSource(source)) url.startsWith(source.substringBeforeLast('/') + "/")
    else url.startsWith(RELEASES + "download/")
  }

  /** An Uzbek error for an invalid release announcement, or null when it is well-formed. */
  fun validate(versionCode: Long, versionName: String, apkUrl: String, sha256: String, size: Long, source: String): String? = when {
    versionCode <= 0 || !VERSION_NAME.matches(versionName) -> "Yangilanish ma'lumoti noto'g'ri"
    !SHA256.matches(sha256) || size <= 0 || size > MAX_APK_BYTES -> "Yangilanish fayli ma'lumoti noto'g'ri"
    !isAllowedApkUrl(apkUrl, source) -> "Yangilanish manzili ruxsat etilmagan"
    else -> null
  }

  fun isNewer(versionCode: Long, installedCode: Long): Boolean = versionCode > installedCode

  /** Install only between calls, so a restart never interrupts a live POS popup. */
  fun canInstallNow(callActive: Boolean, lastCallEndedAt: Long, now: Long): Boolean =
    !callActive && (now - lastCallEndedAt >= QUIET_AFTER_CALL_MS || now < lastCallEndedAt)

  /** 30 min, 1 h, 2 h, 4 h, then every 6 h while an update keeps failing. */
  fun retryDelayMs(failures: Int): Long = minOf(6 * 3600_000L, CHECK_INTERVAL_MS * (1L shl (failures - 1).coerceIn(0, 4)))
}
