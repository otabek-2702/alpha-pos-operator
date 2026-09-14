package com.alphapos.operatorlink

import android.content.ContentValues
import android.content.Context
import org.json.JSONObject

/** Real Android SQLite baseline/outbox tests; inventories are deterministic and no HTTP is sent. */
object OperatorRecordingChecks {
  fun run(context: Context) {
    val now = System.currentTimeMillis()
    val oldFile = OperatorAudioFile("content://test/old.m4a", "old.m4a", 100, now - 86_400_000)
    var files = listOf(oldFile)
    var database = OperatorRecordings(context) { files }
    fun state(uri: String, baseline: String): String? = database.readableDatabase.rawQuery("SELECT state FROM recordings WHERE baseline=? AND uri=?", arrayOf(baseline, uri)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun settle(baseline: String) {
      database.writableDatabase.update("recordings", ContentValues().apply { put("stable_since", System.currentTimeMillis() - 61_000) }, "baseline=?", arrayOf(baseline))
    }
    try {
      val settings = JSONObject().put("enabled", true).put("botToken", "12345:synthetic-test-only-no-network")
        .put("chatId", "-1000001").put("folderUri", "content://test/tree/recordings").put("folderName", "Test")
      database.prepareConfiguration(settings, null)
      val initialBaseline = settings.getString("_baseline")
      check(state(oldFile.uri, initialBaseline) == "ignored") { "Initial existing recordings must never queue" }
      check(database.snapshot(settings).getInt("pending") == 0)

      val same = JSONObject(settings.toString())
      database.prepareConfiguration(same, settings)
      check(same.getString("_baseline") == initialBaseline) { "Ordinary save must preserve the recording baseline" }

      val fresh = OperatorAudioFile("content://test/new.m4a", "new.m4a", 500, System.currentTimeMillis() + 1000)
      files = listOf(oldFile, fresh)
      database.scan(same, false, true)
      check(state(fresh.uri, initialBaseline) == "watching") { "New files must settle before uploading" }
      settle(initialBaseline)
      database.scan(same, true, true)
      check(state(fresh.uri, initialBaseline) == "watching") { "An ongoing call must block queueing" }
      database.scan(same, false, true)
      check(state(fresh.uri, initialBaseline) == "pending")
      val rotatedToken = JSONObject(same.toString()).put("botToken", "12345:rotated-synthetic-test-only-no-network")
      database.prepareConfiguration(rotatedToken, same)
      check(rotatedToken.getString("_baseline") == initialBaseline) { "Rotating the same bot's token must preserve queued recordings" }

      files = listOf(oldFile, fresh.copy(size = 800))
      database.scan(same, false, true)
      check(state(fresh.uri, initialBaseline) == "watching") { "A resumed write must leave the ready queue" }
      settle(initialBaseline)
      database.scan(same, false, true)
      database.close()
      database = OperatorRecordings(context) { files }
      check(state(fresh.uri, initialBaseline) == "pending") { "Pending files must survive native process restart" }

      val offlineFile = OperatorAudioFile("content://test/offline.m4a", "offline.m4a", 250, System.currentTimeMillis() + 2000)
      val movedOld = oldFile.copy(uri = "content://test/renamed-old.m4a", name = "renamed-old.m4a")
      val tooLarge = OperatorAudioFile("content://test/large.m4a", "large.m4a", 50_000_001, System.currentTimeMillis() + 2000)
      files = files + offlineFile + movedOld + tooLarge
      database.scan(same, false, true)
      check(state(offlineFile.uri, initialBaseline) == "watching") { "New files created while offline must be found after restart" }
      check(state(movedOld.uri, initialBaseline) == "ignored") { "An old recording renamed after setup must stay excluded" }
      settle(initialBaseline)
      database.scan(same, false, true)
      check(state(offlineFile.uri, initialBaseline) == "pending")
      check(state(tooLarge.uri, initialBaseline) == "failed") { "Telegram oversize files must expose a permanent size error" }
      OperatorRuntimeStore.update(context) { it.remove("telegramError") }
      check(database.snapshot(same).optString("lastError").contains("50 MB")) { "A later successful upload must not hide an oversized-file error" }

      val disabled = JSONObject(same.toString()).put("enabled", false)
      val reenabled = JSONObject(same.toString())
      database.prepareConfiguration(reenabled, disabled)
      check(reenabled.getString("_baseline") != initialBaseline)
      check(database.snapshot(reenabled).getInt("pending") == 0) { "Reenabling starts with a new inventory, not historical uploads" }
      val changedGroup = JSONObject(reenabled.toString()).put("chatId", "-1000002")
      database.prepareConfiguration(changedGroup, reenabled)
      check(changedGroup.getString("_baseline") != reenabled.getString("_baseline"))
      check(database.snapshot(changedGroup).getInt("pending") == 0) { "A new Telegram destination must not receive old recordings" }
    } finally { database.close() }
  }
}
