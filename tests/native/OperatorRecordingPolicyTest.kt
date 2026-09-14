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
  println("$checks native recording policy checks passed")
}
