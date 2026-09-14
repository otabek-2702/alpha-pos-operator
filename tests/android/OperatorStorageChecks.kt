package com.alphapos.operatorlink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Uses Android Keystore and isolated real SharedPreferences, without starting a service. */
object OperatorStorageChecks {
  fun run(context: Context) {
    val secret = "12345:synthetic-keystore-verification-token"
    val configuration = JSONObject().put("targets", JSONArray().put(JSONObject().put("id", "test-pos").put("name", "Test POS").put("url", "ws://127.0.0.1:8765?token=synthetic-pos-secret")))
      .put("telegram", JSONObject().put("enabled", false).put("botToken", secret))
    OperatorRuntimeStore.saveConfig(context, configuration)
    val ciphertext = context.getSharedPreferences("operator_runtime_v1", Context.MODE_PRIVATE).getString("config", "")!!
    check(!ciphertext.contains(secret) && !ciphertext.contains("synthetic-pos-secret") && !ciphertext.contains("targets")) { "Pairing and Telegram secrets must be encrypted on disk" }
    val restored = OperatorRuntimeStore.config(context)
    check(restored.getJSONObject("telegram").getString("botToken") == secret)
    check(restored.getJSONArray("targets").getJSONObject(0).getString("id") == "test-pos")
    check(OperatorRuntimeStore.shouldRun(restored))
    check(!OperatorRuntimeStore.shouldRun(JSONObject().put("targets", JSONArray())))
    check(OperatorRuntimeStore.shouldRun(JSONObject().put("telegram", JSONObject().put("sendCallStats", true)))) { "Call reports must work when audio uploads are disabled" }

    OperatorRuntimeStore.began(context)
    OperatorRuntimeStore.ended(context, "test-stop")
    var periods = OperatorRuntimeStore.state(context).getJSONArray("periods")
    check(periods.length() == 1 && !periods.getJSONObject(0).isNull("endedAt"))
    check(!periods.getJSONObject(0).optBoolean("approximate"))
    OperatorRuntimeStore.began(context)
    val heartbeat = System.currentTimeMillis()
    OperatorRuntimeStore.update(context) { it.put("lastHeartbeatAt", heartbeat) }
    // Simulate process death: Android did not run onDestroy, so the open period has no precise end.
    OperatorRuntimeStore.began(context)
    periods = OperatorRuntimeStore.state(context).getJSONArray("periods")
    check(periods.length() == 3)
    check(periods.getJSONObject(1).getBoolean("approximate"))
    check(periods.getJSONObject(1).getLong("endedAt") == heartbeat)
    check(periods.getJSONObject(2).isNull("endedAt"))
    OperatorRuntimeStore.ended(context, "test-complete")
  }
}
