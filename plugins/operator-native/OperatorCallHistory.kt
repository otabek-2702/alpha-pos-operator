package __PACKAGE__

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.CallLog
import android.telephony.TelephonyManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Native call ledger. Answer observations and Android's final call log have distinct provenance. */
class OperatorCallHistory(private val context: Context) : SQLiteOpenHelper(context, "operator_calls_v1.db", null, 2) {
  private val observationSession = UUID.randomUUID().toString()
  private val prefs = context.getSharedPreferences("operator_call_history_v1", Context.MODE_PRIVATE)
  private val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
    check(prefs.edit().putString("device_id", it).commit())
  }
  private val baseline: Long = prefs.getLong("baseline", 0L).takeIf { it > 0L } ?: System.currentTimeMillis().also {
    check(prefs.edit().putLong("baseline", it).commit())
  }
  private var primary: String? = null
  private var primaryAnswered = false
  private var primaryIncoming = false
  private var ringing: String? = null
  private var offhookActive = false
  private var currentState = TelephonyManager.CALL_STATE_IDLE
  private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE observations (id TEXT PRIMARY KEY, phone TEXT, started INTEGER, answered INTEGER, ended INTEGER, busy INTEGER, direction TEXT, session TEXT, ambiguous INTEGER NOT NULL DEFAULT 0, call_id TEXT)")
    db.execSQL("CREATE INDEX observations_match ON observations(started,phone)")
    db.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, phone TEXT, started INTEGER, revision INTEGER, json TEXT NOT NULL)")
    db.execSQL("CREATE INDEX calls_phone ON calls(phone,started)")
    db.execSQL("CREATE TABLE pos_ack (target TEXT, id TEXT, revision INTEGER, PRIMARY KEY(target,id))")
    db.execSQL("CREATE TABLE names (phone TEXT PRIMARY KEY, name TEXT NOT NULL)")
    db.execSQL("CREATE TABLE stats_queue (chat TEXT, id TEXT, revision INTEGER, due INTEGER, attempts INTEGER DEFAULT 0, message_id INTEGER, sent_revision INTEGER DEFAULT 0, error TEXT, PRIMARY KEY(chat,id))")
  }
  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE observations ADD COLUMN session TEXT")
      db.execSQL("ALTER TABLE observations ADD COLUMN ambiguous INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE observations ADD COLUMN call_id TEXT")
    }
  }

  private fun normalized(raw: String): String {
    val digits = raw.filter { it.isDigit() }.let { if (it.startsWith("00")) it.drop(2) else it }
    return if (digits.length == 9) "998$digits" else digits
  }
  private fun observationId() = UUID.randomUUID().toString()

  /** Called for every raw phone state, including a waiting caller while another call is active. */
  @Synchronized fun onPhoneState(state: Int, number: String) {
    val now = System.currentTimeMillis()
    val phone = normalized(number)
    try {
      if (state == TelephonyManager.CALL_STATE_RINGING) {
        // A number-bearing duplicate belongs to the current ringing observation,
        // which can be a waiting caller rather than the primary conversation.
        if (currentState == TelephonyManager.CALL_STATE_RINGING && ringing != null) {
          val priorPhone = readableDatabase.rawQuery("SELECT phone FROM observations WHERE id=? AND ended IS NULL AND session=?", arrayOf(ringing!!, observationSession)).use {
            if (it.moveToFirst()) it.getString(0) else null
          }
          if (priorPhone != null && (phone.isBlank() || priorPhone.isBlank() || priorPhone == phone)) {
            if (priorPhone.isBlank() && phone.isNotBlank()) {
              writableDatabase.update("observations", ContentValues().apply { put("phone", phone) }, "id=?", arrayOf(ringing!!))
            }
            currentState = state
            return
          }
        }
        run {
          val id = observationId()
          // Preserve the observed off-hook state while the aggregate state is RINGING.
          val busy = offhookActive
          if (busy) writableDatabase.update("observations", ContentValues().apply { put("ambiguous", 1) }, "session=? AND ended IS NULL", arrayOf(observationSession))
          writableDatabase.insertOrThrow("observations", null, ContentValues().apply {
            put("id", id); put("phone", phone); put("started", now); put("busy", if (busy) 1 else 0); put("direction", "in")
            put("session", observationSession); put("ambiguous", if (busy) 1 else 0)
          })
          ringing = id
          if (primary == null) { primary = id; primaryAnswered = false; primaryIncoming = true }
          Unit
        }
      } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
        if (primary != null && primaryIncoming && !primaryAnswered && ringing == primary) {
          writableDatabase.update("observations", ContentValues().apply { put("answered", now) }, "id=? AND busy=0 AND direction='in'", arrayOf(primary!!))
          primaryAnswered = true
        } else if (primary == null && currentState != TelephonyManager.CALL_STATE_OFFHOOK) {
          val id = observationId()
          writableDatabase.insertOrThrow("observations", null, ContentValues().apply {
            put("id", id); put("phone", phone); put("started", now); put("busy", 0); put("direction", "out")
            put("session", observationSession)
          })
          primary = id
          primaryIncoming = false
          // Outgoing Offhook means dialing; it does NOT prove the customer answered.
          primaryAnswered = false
        }
        offhookActive = true
        ringing = null
      } else if (state == TelephonyManager.CALL_STATE_IDLE && currentState != TelephonyManager.CALL_STATE_IDLE) {
        writableDatabase.update("observations", ContentValues().apply { put("ended", now) }, "ended IS NULL AND session=?", arrayOf(observationSession))
        primary = null; primaryAnswered = false; primaryIncoming = false; ringing = null; offhookActive = false
      }
      currentState = state
    } catch (_: Exception) { prefs.edit().putString("last_error", "Qo'ng'iroq vaqtini saqlab bo'lmadi").apply() }
  }

  private data class FinalCall(val id: String, val phone: String, val start: Long, val duration: Long, val type: Int) {
    val direction: String get() = if (type == CallLog.Calls.OUTGOING_TYPE) "out" else "in"
  }

  /** Claim once, against the whole batch: an older unobserved call must not steal a later call's observations. */
  @Synchronized private fun matchObservation(call: FinalCall, batch: List<FinalCall>): JSONObject? {
    return readableDatabase.rawQuery(
      "SELECT started,answered,ended,busy,ambiguous,id,phone,call_id FROM observations WHERE direction=? AND (call_id=? OR (call_id IS NULL AND started BETWEEN ? AND ? AND (phone=? OR phone='') AND (ended IS NULL OR ended>=?))) ORDER BY CASE WHEN call_id=? THEN 0 ELSE 1 END, ABS(started-?)",
      arrayOf(call.direction, call.id, (call.start - 2000L).toString(), (call.start + 2000L).toString(), normalized(call.phone), call.start.toString(), call.id, call.start.toString())
    ).use { observed ->
      while (observed.moveToNext()) {
        val started = observed.getLong(0)
        val ended = if (observed.isNull(2)) null else observed.getLong(2)
        val claimed = if (observed.isNull(7)) null else observed.getString(7)
        if (claimed == null) {
          // An ongoing later call may not have its own final OS row yet.
          if (ended == null && started > call.start) continue
          val phone = observed.getString(6) ?: ""
          val compatible = batch.filter {
            it.direction == call.direction && (phone.isBlank() || normalized(it.phone) == phone) &&
              kotlin.math.abs(started - it.start) <= 2000L && (ended == null || ended >= it.start)
          }
          val closest = compatible.minOfOrNull { kotlin.math.abs(started - it.start) } ?: continue
          val nearest = compatible.filter { kotlin.math.abs(started - it.start) == closest }
          if (nearest.size != 1 || nearest.single().id != call.id) continue
          writableDatabase.update("observations", ContentValues().apply { put("call_id", call.id) }, "id=? AND call_id IS NULL", arrayOf(observed.getString(5)))
        }
        return@use JSONObject().put("started", started)
          .put("answered", if (observed.isNull(1)) JSONObject.NULL else observed.getLong(1))
          .put("ended", ended ?: JSONObject.NULL).put("busy", observed.getInt(3) == 1).put("ambiguous", observed.getInt(4) == 1)
      }
      null
    }
  }

  private fun row(id: String): JSONObject? = readableDatabase.rawQuery("SELECT json FROM calls WHERE id=?", arrayOf(id)).use {
    if (it.moveToFirst()) JSONObject(it.getString(0)) else null
  }

  @Synchronized private fun saveRecord(record: JSONObject) {
    val old = row(record.getString("id"))
    // Compare logical contents without the transport revision.
    val comparable = JSONObject(record.toString()).apply { remove("revision") }
    val oldComparable = old?.let { JSONObject(it.toString()).apply { remove("revision") } }
    if (oldComparable != null && comparable.toString() == oldComparable.toString()) return
    record.put("revision", (old?.optLong("revision", 0L) ?: 0L) + 1L)
    writableDatabase.insertWithOnConflict("calls", null, ContentValues().apply {
      put("id", record.getString("id")); put("phone", normalized(record.optString("phone")))
      put("started", record.getLong("startedAt")); put("revision", record.getLong("revision")); put("json", record.toString())
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  /** Final OS rows reconcile outcomes, durations, waiting calls and calls during service downtime. */
  private fun reconcile() {
    val since = maxOf(baseline, prefs.getLong("latest_call", baseline) - TimeUnit.DAYS.toMillis(1))
    val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE)
    val cursor = context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, "${CallLog.Calls.DATE} >= ?", arrayOf(since.toString()), "${CallLog.Calls.DATE} ASC") ?: return
    val batch = ArrayList<FinalCall>()
    cursor.use {
      while (it.moveToNext()) {
        batch.add(FinalCall("$deviceId-${it.getLong(0)}", it.getString(1) ?: "", it.getLong(2), it.getLong(3).coerceAtLeast(0L), it.getInt(4)))
      }
    }
    var latest = since
    for (call in batch) {
        val id = call.id
        val phone = call.phone
        val start = call.start
        val duration = call.duration
        val type = call.type
        val direction = call.direction
        val old = row(id)
        val record = old ?: JSONObject().put("id", id).put("phone", phone).put("direction", direction).put("startedAt", start)
        val observation = matchObservation(call, batch)
        val outcome = when (type) {
          CallLog.Calls.MISSED_TYPE -> "missed"
          CallLog.Calls.REJECTED_TYPE -> "rejected"
          CallLog.Calls.BLOCKED_TYPE -> "blocked"
          CallLog.Calls.INCOMING_TYPE -> "answered"
          CallLog.Calls.OUTGOING_TYPE -> if (duration > 0L) "answered" else "unconfirmed"
          else -> "unknown"
        }
        // A waiting caller can stop ringing before the primary call ends; global IDLE cannot time that end.
        val exactEnd = observation?.optLong("ended", 0L)?.takeIf { value -> value > start && !observation.optBoolean("ambiguous") }
        val observedAnswer = observation?.optLong("answered", 0L)?.takeIf { value -> value >= start && (exactEnd == null || value <= exactEnd) && direction == "in" && outcome == "answered" }
        val inferredAnswer = if (outcome == "answered" && exactEnd != null) maxOf(start, exactEnd - duration * 1000L) else null
        val answer = observedAnswer ?: inferredAnswer
        record.put("outcome", outcome).put("talkSeconds", duration).put("callLogType", type)
          .put("answeredAt", answer ?: JSONObject.NULL)
          .put("endedAt", exactEnd ?: JSONObject.NULL)
          .put("ringSeconds", if (direction == "in" && observedAnswer != null && observation != null) maxOf(0L, (observedAnswer - observation.getLong("started")) / 1000L) else JSONObject.NULL)
          .put("ringDurationSeconds", if (direction == "in" && observation != null && exactEnd != null && outcome != "answered") maxOf(0L, (exactEnd - observation.getLong("started")) / 1000L) else JSONObject.NULL)
          .put("missedWhileBusy", if (outcome in setOf("missed", "rejected") && observation != null) observation.optBoolean("busy") else JSONObject.NULL)
          .put("timingSource", if (observedAnswer != null) "observed_answer" else if (inferredAnswer != null) "call_log_duration_estimate" else "call_log_only")
        val name = readableDatabase.rawQuery("SELECT name FROM names WHERE phone=?", arrayOf(normalized(phone))).use { names -> if (names.moveToFirst()) names.getString(0) else null }
        if (name != null) record.put("customerName", name)
        saveRecord(record)
        if (direction == "out" && normalized(phone).isNotBlank()) linkCallbacks(record)
        latest = maxOf(latest, start)
    }
    prefs.edit().putLong("latest_call", latest).remove("last_error").apply()
  }

  @Synchronized private fun linkCallbacks(outgoing: JSONObject) {
    val matches = ArrayList<JSONObject>()
    readableDatabase.rawQuery("SELECT json FROM calls WHERE phone=? AND started<? ORDER BY started ASC", arrayOf(normalized(outgoing.optString("phone")), outgoing.getLong("startedAt").toString())).use {
      while (it.moveToNext()) {
        val missed = JSONObject(it.getString(0))
        if (missed.optString("outcome") in setOf("missed", "rejected") &&
          (!missed.optBoolean("callbackConnected", false) || missed.optString("callbackCallId") == outgoing.getString("id"))) matches.add(missed)
      }
    }
    if (matches.isEmpty()) return
    val ids = outgoing.optJSONArray("callbackForIds") ?: JSONArray()
    val linkedIds = HashSet<String>()
    for (index in 0 until ids.length()) linkedIds.add(ids.getString(index))
    for (missed in matches) {
      if (missed.isNull("callbackAttemptAt")) {
        missed.put("callbackAttemptAt", outgoing.getLong("startedAt"))
        missed.put("callbackAttemptDelaySeconds", if (!missed.isNull("endedAt")) maxOf(0L, (outgoing.getLong("startedAt") - missed.getLong("endedAt")) / 1000L) else JSONObject.NULL)
      }
      if (outgoing.optString("outcome") == "answered") {
        val connected = outgoing.optLong("answeredAt", 0L).takeIf { it > 0L }
        missed.put("callbackConnected", true)
          .put("callbackConnectedAt", connected ?: JSONObject.NULL)
          .put("callbackCallId", outgoing.getString("id"))
          .put("callbackDelaySeconds", if (connected != null && !missed.isNull("endedAt")) maxOf(0L, (connected - missed.getLong("endedAt")) / 1000L) else JSONObject.NULL)
        // Even if exact answer timestamp is unavailable, don't assign a later call as first success.
      }
      if (linkedIds.add(missed.getString("id"))) ids.put(missed.getString("id"))
      saveRecord(missed)
    }
    outgoing.put("callbackForIds", ids)
    saveRecord(outgoing)
  }

  @Synchronized fun enrichCustomer(phone: String, name: String) {
    val key = normalized(phone)
    if (key.isBlank() || name.isBlank()) return
    val clean = name.take(200)
    writableDatabase.insertWithOnConflict("names", null, ContentValues().apply { put("phone", key); put("name", clean) }, SQLiteDatabase.CONFLICT_REPLACE)
    val records = ArrayList<JSONObject>()
    readableDatabase.rawQuery("SELECT json FROM calls WHERE phone=?", arrayOf(key)).use {
      while (it.moveToNext()) records.add(JSONObject(it.getString(0)))
    }
    for (record in records) saveRecord(record.put("customerName", clean))
  }

  @Synchronized fun pendingRecords(targetId: String): List<JSONObject> {
    val records = ArrayList<JSONObject>()
    readableDatabase.rawQuery("SELECT c.json FROM calls c LEFT JOIN pos_ack a ON a.id=c.id AND a.target=? WHERE a.revision IS NULL OR a.revision<c.revision ORDER BY c.started ASC LIMIT 50", arrayOf(targetId)).use {
      while (it.moveToNext()) records.add(JSONObject(it.getString(0)))
    }
    return records
  }

  @Synchronized fun acknowledge(targetId: String, id: String, revision: Long) {
    val current = row(id) ?: return
    if (revision < 1L || revision > current.optLong("revision")) return
    val oldRevision = readableDatabase.rawQuery("SELECT revision FROM pos_ack WHERE target=? AND id=?", arrayOf(targetId, id)).use {
      if (it.moveToFirst()) it.getLong(0) else 0L
    }
    writableDatabase.insertWithOnConflict("pos_ack", null, ContentValues().apply {
      put("target", targetId); put("id", id); put("revision", maxOf(oldRevision, revision))
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  /** Runs on the service worker; HTTP does not hold the observation/ledger lock. */
  fun tick(telegram: JSONObject) {
    try { reconcile() } catch (_: SecurityException) {
      prefs.edit().putString("last_error", "Qo'ng'iroqlar tarixiga ruxsat kerak").apply()
    } catch (_: Exception) { prefs.edit().putString("last_error", "Qo'ng'iroqlar tarixini o'qib bo'lmadi").apply() }
    if (!telegram.optBoolean("sendCallStats", false)) {
      prefs.edit().putBoolean("stats_enabled", false).remove("stats_error").apply()
      return
    }
    val chat = telegram.optString("statsChatId")
    val token = telegram.optString("botToken")
    if (chat.isBlank() || token.isBlank()) return
    val epoch = telegram.optString("_statsBaseline")
    val changed = !prefs.getBoolean("stats_enabled", false) || prefs.getString("stats_epoch", "") != epoch || prefs.getString("stats_chat", "") != chat
    if (changed) prefs.edit().putBoolean("stats_enabled", true).putString("stats_chat", chat).putString("stats_epoch", epoch)
      .putLong("stats_since", telegram.optLong("_statsBaselineAt", System.currentTimeMillis()))
      .remove("stats_error").remove("stats_last_sent").commit()
    val since = prefs.getLong("stats_since", System.currentTimeMillis())
    synchronized(this) {
      readableDatabase.rawQuery("SELECT id,revision FROM calls WHERE started>=?", arrayOf(since.toString())).use {
        while (it.moveToNext()) {
          val updated = writableDatabase.update("stats_queue", ContentValues().apply { put("revision", it.getLong(1)) }, "chat=? AND id=?", arrayOf(chat, it.getString(0)))
          if (updated == 0) writableDatabase.insertOrThrow("stats_queue", null, ContentValues().apply {
            put("chat", chat); put("id", it.getString(0)); put("revision", it.getLong(1)); put("due", System.currentTimeMillis() + 5000L)
          })
        }
      }
    }
    sendNext(chat, token, since)
  }

  private fun report(record: JSONObject): String {
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("uz"))
    fun stamp(key: String): String = record.optLong(key, 0L).takeIf { it > 0L }?.let { fmt.format(Date(it)) } ?: "aniqlanmadi"
    val outcome = when (record.optString("outcome")) {
      "answered" -> if (record.optString("direction") == "in") "Qabul qilingan qo'ng'iroq" else "Chiquvchi suhbat"
      "missed" -> "O'tkazib yuborilgan qo'ng'iroq"
      "rejected" -> "Rad etilgan qo'ng'iroq"
      "blocked" -> "Bloklangan qo'ng'iroq"
      "unconfirmed" -> "Chiquvchi urinish — suhbat tasdiqlanmadi"
      else -> "Qo'ng'iroq ma'lumotlari"
    }
    return buildString {
      append("☎️ ").append(outcome).append('\n')
      append("Mijoz: ").append(record.optString("customerName").ifBlank { "Ism topilmadi" }).append('\n')
      append("Raqam: ").append(record.optString("phone").ifBlank { "Yashirin raqam" }).append('\n')
      append("Boshlangan: ").append(stamp("startedAt")).append('\n')
      if (record.optString("direction") == "in" && record.optString("outcome") == "answered") {
        append("Javob berish: ").append(if (record.isNull("ringSeconds")) "aniqlanmadi" else "${record.optLong("ringSeconds")} soniya").append('\n')
      }
      if (!record.isNull("ringDurationSeconds")) append("Jiringlagan: ").append(record.optLong("ringDurationSeconds")).append(" soniya\n")
      append("Suhbat: ").append(record.optLong("talkSeconds")).append(" soniya\n")
      if (record.optString("outcome") in setOf("missed", "rejected")) {
        append("O'sha paytda boshqa qo'ng'iroq: ").append(if (record.isNull("missedWhileBusy")) "aniqlanmadi" else if (record.optBoolean("missedWhileBusy")) "ha (kuzatilgan)" else "kuzatilmadi").append('\n')
        if (!record.isNull("callbackAttemptAt")) {
          append("Qayta terildi: ").append(stamp("callbackAttemptAt"))
          if (!record.isNull("callbackAttemptDelaySeconds")) append(" · ").append(record.optLong("callbackAttemptDelaySeconds")).append(" soniyadan keyin")
          else append(" · kutish muddati aniqlanmadi")
          append('\n')
        } else append("Qayta qo'ng'iroq: hali qayd etilmagan\n")
        if (record.optBoolean("callbackConnected", false)) {
          append("Qayta suhbat: tasdiqlangan")
          if (!record.isNull("callbackDelaySeconds")) append(" · ").append(record.optLong("callbackDelaySeconds")).append(" soniyadan keyin (taxminiy)")
          append('\n')
        } else if (!record.isNull("callbackAttemptAt")) append("Qayta suhbat: hali tasdiqlanmagan\n")
        append("Javobsiz qolish sababi avtomatik aniqlanmaydi.\n")
      }
      if (record.optString("timingSource") == "call_log_only") append("Aniq javob/tugash vaqti kuzatilmagan.\n")
      append("ID: ").append(record.getString("id").takeLast(16))
    }.take(4000)
  }

  private fun sendNext(chat: String, token: String, since: Long) {
    val item = synchronized(this) {
      readableDatabase.rawQuery("SELECT q.id,q.revision,q.attempts,q.message_id,c.json FROM stats_queue q JOIN calls c ON c.id=q.id WHERE q.chat=? AND c.started>=? AND q.sent_revision<q.revision AND q.due<=? ORDER BY c.started ASC LIMIT 1", arrayOf(chat, since.toString(), System.currentTimeMillis().toString())).use {
        if (!it.moveToFirst()) null else JSONObject().put("id", it.getString(0)).put("revision", it.getLong(1)).put("attempts", it.getInt(2))
          .put("message", if (it.isNull(3)) JSONObject.NULL else it.getLong(3)).put("record", JSONObject(it.getString(4)))
      }
    } ?: return
    val editing = !item.isNull("message")
    val body = JSONObject().put("chat_id", chat).put("text", report(item.getJSONObject("record")))
    if (editing) body.put("message_id", item.getLong("message"))
    val method = if (editing) "editMessageText" else "sendMessage"
    var retrySeconds = minOf(3600L, 15L shl minOf(item.optInt("attempts"), 8))
    var error = "Telegramga hisobot yuborilmadi; qayta uriniladi"
    try {
      val request = Request.Builder().url("https://api.telegram.org/bot$token/$method")
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
      http.newCall(request).execute().use { response ->
        val result = JSONObject(response.body?.string() ?: "{}")
        if (result.optBoolean("ok") || (editing && result.optString("description").contains("message is not modified"))) {
          synchronized(this) {
            writableDatabase.update("stats_queue", ContentValues().apply {
              put("sent_revision", item.getLong("revision")); put("attempts", 0); putNull("error")
              if (!editing) put("message_id", result.getJSONObject("result").getLong("message_id"))
            }, "chat=? AND id=?", arrayOf(chat, item.getString("id")))
          }
          prefs.edit().remove("stats_error").putLong("stats_last_sent", System.currentTimeMillis()).apply()
          return
        }
        retrySeconds = result.optJSONObject("parameters")?.optLong("retry_after", retrySeconds) ?: retrySeconds
        if (response.code == 401 || response.code == 403) { retrySeconds = 3600L; error = "Hisobot guruhi yoki bot tokenini tekshiring" }
        else if (response.code == 400) { retrySeconds = 3600L; error = "Hisobot guruhi sozlamalarini va bot a'zoligini tekshiring" }
      }
    } catch (_: Exception) { /* Never expose token-bearing URLs from exception messages. */ }
    synchronized(this) {
      writableDatabase.update("stats_queue", ContentValues().apply {
        put("attempts", item.getInt("attempts") + 1); put("due", System.currentTimeMillis() + maxOf(1L, retrySeconds) * 1000L); put("error", error)
      }, "chat=? AND id=?", arrayOf(chat, item.getString("id")))
    }
    prefs.edit().putString("stats_error", error).apply()
  }

  @Synchronized fun snapshot(): JSONObject {
    val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM calls", null).use { it.moveToFirst(); it.getLong(0) }
    val pending = if (!prefs.getBoolean("stats_enabled", false)) 0L else readableDatabase.rawQuery(
      "SELECT COUNT(*) FROM stats_queue q JOIN calls c ON c.id=q.id WHERE q.chat=? AND c.started>=? AND q.sent_revision<q.revision",
      arrayOf(prefs.getString("stats_chat", "") ?: "", prefs.getLong("stats_since", Long.MAX_VALUE).toString())
    ).use { it.moveToFirst(); it.getLong(0) }
    return JSONObject().put("callCount", count).put("statsPending", pending).put("lastError", prefs.getString("last_error", null))
      .put("statsError", prefs.getString("stats_error", null)).put("statsLastSentAt", prefs.getLong("stats_last_sent", 0L))
  }

  override fun close() {
    http.dispatcher.cancelAll()
    super.close()
  }
}
