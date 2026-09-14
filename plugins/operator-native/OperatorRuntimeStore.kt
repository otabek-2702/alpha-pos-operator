package __PACKAGE__

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No plaintext bot/pairing secrets in JS storage, logs, backups or service intents. */
object OperatorRuntimeStore {
  private const val KEY_ALIAS = "smart_pos_operator_config_v1"
  private fun prefs(context: Context) = context.getSharedPreferences("operator_runtime_v1", Context.MODE_PRIVATE)

  @Synchronized private fun key(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
      init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
  }

  @Synchronized fun config(context: Context): JSONObject {
    val encoded = prefs(context).getString("config", null) ?: return JSONObject().put("targets", JSONArray())
    val parts = encoded.split(":", limit = 2)
    require(parts.size == 2) { "Saqlangan sozlamalarni ochib bo'lmadi" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
    return JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
  }

  @Synchronized fun saveConfig(context: Context, config: JSONObject) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val ciphertext = cipher.doFinal(config.toString().toByteArray(Charsets.UTF_8))
    check(prefs(context).edit().putString("config", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit())
  }

  fun shouldRun(config: JSONObject): Boolean = config.optBoolean("enabled", true) &&
    ((config.optJSONArray("targets")?.length() ?: 0) > 0 || config.optJSONObject("telegram")?.optBoolean("enabled", false) == true || config.optJSONObject("telegram")?.optBoolean("sendCallStats", false) == true)

  fun startIfConfigured(context: Context) {
    try {
      if (shouldRun(config(context))) ContextCompat.startForegroundService(context, Intent(context, CallBridgeForegroundService::class.java))
    } catch (_: Exception) { setError(context, "Xizmatni boshlash uchun ilovani oching va ruxsatlarni tekshiring") }
  }

  @Synchronized fun state(context: Context): JSONObject = try { JSONObject(prefs(context).getString("state", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
  @Synchronized fun update(context: Context, change: (JSONObject) -> Unit) {
    val value = state(context)
    change(value)
    prefs(context).edit().putString("state", value.toString()).commit()
  }
  fun setError(context: Context, error: String) = update(context) { it.put("lastError", error) }

  fun began(context: Context) = update(context) { state ->
    val now = System.currentTimeMillis()
    val periods = state.optJSONArray("periods") ?: JSONArray()
    if (periods.length() > 0) {
      val last = periods.getJSONObject(periods.length() - 1)
      if (last.isNull("endedAt")) {
        last.put("endedAt", state.optLong("lastHeartbeatAt", now)).put("endReason", "Tizim yoki telefon to'xtagan").put("approximate", true)
      }
    }
    periods.put(JSONObject().put("startedAt", now).put("endedAt", JSONObject.NULL))
    val kept = JSONArray()
    for (i in maxOf(0, periods.length() - 180) until periods.length()) kept.put(periods.get(i))
    state.put("periods", kept).put("running", true).put("startedAt", now).put("lastHeartbeatAt", now).remove("lastError")
  }

  fun ended(context: Context, reason: String) = update(context) { state ->
    val now = System.currentTimeMillis()
    val periods = state.optJSONArray("periods")
    if (periods != null && periods.length() > 0) {
      val last = periods.getJSONObject(periods.length() - 1)
      if (last.isNull("endedAt")) last.put("endedAt", now).put("endReason", reason).put("approximate", false)
    }
    state.put("running", false).put("startedAt", JSONObject.NULL)
  }
}
