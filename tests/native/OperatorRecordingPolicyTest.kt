package __PACKAGE__

/** Runs the production policy itself; no Android device or duplicate implementation. */
fun main() {
  var checks = 0
  fun expect(name: String, condition: Boolean) { check(condition) { name }; checks++ }
  val policy = OperatorRecordingPolicy
  expect("an old recording moved into the folder stays excluded", policy.isHistorical(1_000, 2_000))
  expect("a recording made after setup is eligible", !policy.isHistorical(3_000, 2_000))
  expect("a provider without mtime relies on the initial inventory", !policy.isHistorical(0, 2_000))
  expect("empty files never upload", !policy.canQueue(0, 0, 0, 200_000, false, true))
  expect("unknown file sizes never upload", !policy.canQueue(-1, 0, 0, 200_000, false, true))
  expect("a file still within the stability window waits", !policy.canQueue(1_000, 0, 0, 59_999, false, true))
  expect("a stable completed file becomes eligible", policy.canQueue(1_000, 0, 0, 60_000, false, true))
  expect("an ongoing phone call blocks stable recording upload", !policy.canQueue(1_000, 0, 0, 600_000, true, true))
  expect("without phone state the longer settling period applies", !policy.canQueue(1_000, 0, 0, 179_999, false, false))
  expect("long-settled files work when phone permission is absent", policy.canQueue(1_000, 0, 0, 180_000, false, false))
  expect("a resumed write restarts the stability timer", !policy.canQueue(2_000, 175_000, 0, 180_000, false, true))
  expect("moving the clock backwards cannot prematurely upload", !policy.canQueue(1_000, 200_000, 0, 100_000, false, true))
  expect("the documented Telegram file limit is accepted", !policy.isTooLarge(50_000_000))
  expect("oversized files are flagged instead of retried forever", policy.isTooLarge(50_000_001))
  expect("first retry has a short delay", policy.retrySeconds(0) == 15L)
  expect("temporary failures back off", policy.retrySeconds(1) == 30L && policy.retrySeconds(4) == 240L)
  expect("retries remain bounded after many failures", policy.retrySeconds(100) == 3600L)
  val update = OperatorUpdatePolicy
  val release = "https://github.com/otabek-2702/smart-pos-operator-releases/releases/download/v2.1.1/operator-2.1.1.apk"
  val sha = "a".repeat(64)
  expect("a published GitHub release asset is accepted", update.validate(5, "2.1.1", release, sha, 50_000_000, update.DEFAULT_SOURCE) == null)
  expect("APKs from other repositories are refused", update.validate(5, "2.1.1", "https://github.com/someone/else/releases/download/v1/operator.apk", sha, 1, update.DEFAULT_SOURCE) != null)
  expect("plain http is refused for production", update.validate(5, "2.1.1", release.replace("https:", "http:"), sha, 1, update.DEFAULT_SOURCE) != null)
  expect("path tricks are refused", !update.isAllowedApkUrl(update.RELEASES + "download/../../x/operator.apk", update.DEFAULT_SOURCE))
  expect("non-APK files are refused", !update.isAllowedApkUrl(update.RELEASES + "download/v2/operator.exe", update.DEFAULT_SOURCE))
  expect("a malformed checksum is refused", update.validate(5, "2.1.1", release, "ABC", 1, update.DEFAULT_SOURCE) != null)
  expect("an oversized download is refused", update.validate(5, "2.1.1", release, sha, 200_000_001, update.DEFAULT_SOURCE) != null)
  expect("a malformed version is refused", update.validate(5, "2.1.1-beta", release, sha, 1, update.DEFAULT_SOURCE) != null)
  expect("the emulator host serves only its own APKs", update.isAllowedApkUrl("http://10.0.2.2:8000/u/operator.apk", "http://10.0.2.2:8000/u/update.json") &&
    !update.isAllowedApkUrl(release, "http://10.0.2.2:8000/u/update.json"))
  expect("other hosts are never test sources", !update.isTestSource("http://10.0.2.20:8000/update.json") && !update.isTestSource("https://example.com/update.json"))
  expect("only newer builds install", update.isNewer(5, 4) && !update.isNewer(4, 4) && !update.isNewer(3, 4))
  expect("no install during a call", !update.canInstallNow(true, 0, 1_000_000))
  expect("no install right after a call", !update.canInstallNow(false, 1_000_000, 1_059_999))
  expect("install once the phone has been quiet", update.canInstallNow(false, 1_000_000, 1_060_000))
  expect("failed updates back off up to six hours", update.retryDelayMs(1) == 1_800_000L && update.retryDelayMs(2) == 3_600_000L && update.retryDelayMs(99) == 21_600_000L)
  println("$checks native recording and update policy checks passed")
}
