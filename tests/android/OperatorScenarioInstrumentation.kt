package com.alphapos.operatorlink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Emulator-only setup/inspection for testing the production app's UI and service lifecycle. */
object OperatorScenario {
  fun run(targetContext: Context, mode: String, source: String? = null): JSONObject {
      check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Emulator required" }
      if (mode == "setup") {
        val targets = JSONArray()
        targets.put(JSONObject().put("id", "test-pos-1").put("name", "Asosiy kassa").put("url", "ws://10.0.2.2:8765?token=operator-emulator-test-one").put("discoveryPort", 8766))
        targets.put(JSONObject().put("id", "test-pos-2").put("name", "Ikkinchi kassa").put("url", "ws://10.0.2.2:8767?token=operator-emulator-test-two").put("discoveryPort", 8766))
        OperatorRuntimeStore.saveConfig(targetContext, JSONObject().put("targets", targets).put("telegram", JSONObject().put("enabled", false).put("sendCallStats", false)))
      }
      if (mode == "telegram-setup") {
        // A private local test fixture is pushed through adb; secrets never enter instrumentation args/output.
        val input = File(targetContext.getExternalFilesDir(null), "telegram-test.json")
        val telegram = try { JSONObject(input.readText()) } finally { input.delete() }
        check(telegram.getString("chatId") != telegram.getString("statsChatId"))
        telegram.put("enabled", false).put("sendCallStats", true)
          .put("_statsBaseline", UUID.randomUUID().toString()).put("_statsBaselineAt", System.currentTimeMillis())
        val configuration = OperatorRuntimeStore.config(targetContext).put("telegram", telegram)
        OperatorRuntimeStore.saveConfig(targetContext, configuration)
      }
      if (mode == "update-source") {
        // Serve self-updates from the emulator host; "none" restores the production source.
        OperatorUpdater.setTestSource(targetContext, source?.takeIf { it != "none" })
      }
      val state = OperatorRuntimeStore.state(targetContext)
      state.put("update", OperatorUpdater.status(targetContext))
      state.put("targetCount", OperatorRuntimeStore.config(targetContext).optJSONArray("targets")?.length() ?: 0)
      // The instrumentation process may recreate the application; persisted state is reported too.
      state.put("serviceInThisProcess", CallBridgeForegroundService.instance != null)
      if (mode == "inspect") {
        val history = OperatorCallHistory(targetContext)
        try { state.put("history", history.snapshot()) } finally { history.close() }
        val recordings = OperatorRecordings(targetContext)
        try {
          state.put("recordings", recordings.snapshot(OperatorRuntimeStore.config(targetContext).optJSONObject("telegram") ?: JSONObject()))
          val files = JSONArray()
          recordings.readableDatabase.rawQuery("SELECT name,state,attempts,message_id FROM recordings ORDER BY first_seen", null).use { rows ->
            while (rows.moveToNext()) files.put(JSONObject().put("name", rows.getString(0)).put("state", rows.getString(1))
              .put("attempts", rows.getInt(2)).put("messageId", if (rows.isNull(3)) JSONObject.NULL else rows.getLong(3)))
          }
          state.put("testAudioResults", files)
        } finally { recordings.close() }
      }
      return state
  }
}
