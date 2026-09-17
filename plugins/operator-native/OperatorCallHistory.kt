package __PACKAGE__

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.CallLog
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Native call ledger. Answer observations and Android's final call log have distinct provenance. */
class OperatorCallHistory(private val context: Context) : SQLiteOpenHelper(context, "operator_calls_v1.db", null, 3) {
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

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE observations (id TEXT PRIMARY KEY, phone TEXT, started INTEGER, answered INTEGER, ended INTEGER, busy INTEGER, direction TEXT, session TEXT, ambiguous INTEGER NOT NULL DEFAULT 0, call_id TEXT, busy_with TEXT)")
    db.execSQL("CREATE INDEX observations_match ON observations(started,phone)")
    db.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, phone TEXT, started INTEGER, revision INTEGER, json TEXT NOT NULL, reported INTEGER NOT NULL DEFAULT 0)")
    db.execSQL("CREATE INDEX calls_phone ON calls(phone,started)")
    db.execSQL("CREATE TABLE pos_ack (target TEXT, id TEXT, revision INTEGER, PRIMARY KEY(target,id))")
    db.execSQL("CREATE TABLE names (phone TEXT PRIMARY KEY, name TEXT NOT NULL)")
    db.execSQL("CREATE TABLE stats_queue (chat TEXT, id TEXT, revision INTEGER, due INTEGER, attempts INTEGER DEFAULT 0, message_id INTEGER, sent_revision INTEGER DEFAULT 0, error TEXT, PRIMARY KEY(chat,id))")
    db.execSQL("CREATE TABLE orders (phone TEXT NOT NULL, order_id TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(phone, order_id))")
  }
  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE observations ADD COLUMN session TEXT")
      db.execSQL("ALTER TABLE observations ADD COLUMN ambiguous INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE observations ADD COLUMN call_id TEXT")
    }
    if (oldVersion < 3) {
      db.execSQL("ALTER TABLE observations ADD COLUMN busy_with TEXT")
      // 2.1.x already reported calls through stats_queue; those are handed to the new outbox.
      db.execSQL("ALTER TABLE calls ADD COLUMN reported INTEGER NOT NULL DEFAULT 0")
      db.execSQL("CREATE TABLE IF NOT EXISTS orders (phone TEXT NOT NULL, order_id TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(phone, order_id))")
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
          val busyWith = if (busy && primary != null) readableDatabase.rawQuery("SELECT phone FROM observations WHERE id=?", arrayOf(primary!!)).use {
            if (it.moveToFirst()) it.getString(0) else null
          } else null
          writableDatabase.insertOrThrow("observations", null, ContentValues().apply {
            put("id", id); put("phone", phone); put("started", now); put("busy", if (busy) 1 else 0); put("direction", "in")
            put("session", observationSession); put("ambiguous", if (busy) 1 else 0)
            if (!busyWith.isNullOrBlank()) put("busy_with", busyWith)
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
      "SELECT started,answered,ended,busy,ambiguous,id,phone,call_id,busy_with FROM observations WHERE direction=? AND (call_id=? OR (call_id IS NULL AND started BETWEEN ? AND ? AND (phone=? OR phone='') AND (ended IS NULL OR ended>=?))) ORDER BY CASE WHEN call_id=? THEN 0 ELSE 1 END, ABS(started-?)",
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
          .put("busyWith", observed.getString(8) ?: "")
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
    val values = ContentValues().apply {
      put("phone", normalized(record.optString("phone")))
      put("started", record.getLong("startedAt")); put("revision", record.getLong("revision")); put("json", record.toString())
    }
    // Update in place so the Telegram "reported" marker survives.
    if (old == null) writableDatabase.insertOrThrow("calls", null, values.apply { put("id", record.getString("id")) })
    else writableDatabase.update("calls", values, "id=?", arrayOf(record.getString("id")))
  }

  private fun isMissedOutcome(record: JSONObject) = record.optString("outcome") in setOf("missed", "rejected")

  /** Final OS rows reconcile outcomes, durations, waiting calls and calls during service downtime. */
  private fun reconcile(shifts: List<OperatorShift>, tz: TimeZone) {
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
        .put("busyWith", observation?.optString("busyWith")?.takeIf { it.isNotBlank() && outcome in setOf("missed", "rejected") } ?: JSONObject.NULL)
        .put("timingSource", if (observedAnswer != null) "observed_answer" else if (inferredAnswer != null) "call_log_duration_estimate" else "call_log_only")
        .put("observed", observation != null)
      if (!record.has("shiftId")) annotate(record, shifts, tz)
      if (record.isNull("orderId")) pendingOrder(normalized(phone), start)?.let { record.put("orderId", it.first).put("orderAt", it.second) }
      val name = readableDatabase.rawQuery("SELECT name FROM names WHERE phone=?", arrayOf(normalized(phone))).use { names -> if (names.moveToFirst()) names.getString(0) else null }
      if (name != null) record.put("customerName", name)
      saveRecord(record)
      if (normalized(phone).isNotBlank()) {
        if (direction == "out") linkCallbacks(record)
        else if (outcome == "answered") linkClientRecall(record)
      }
      latest = maxOf(latest, start)
    }
    prefs.edit().putLong("latest_call", latest).remove("last_error").apply()
  }

  /** Shift, closed hours and repeat-caller counts, fixed when the call is first recorded. */
  private fun annotate(record: JSONObject, shifts: List<OperatorShift>, tz: TimeZone) {
    val start = record.getLong("startedAt")
    val slot = OperatorSchedule.slotAt(start, shifts, tz)
    record.put("closed", slot == null).put("shiftIndex", slot?.shift?.index ?: JSONObject.NULL).put("shiftId", slot?.id ?: JSONObject.NULL)
    val phone = normalized(record.optString("phone"))
    if (record.optString("direction") != "in" || phone.isBlank()) return
    var earlier = 0
    var missedEarlier = 0
    readableDatabase.rawQuery("SELECT json FROM calls WHERE phone=? AND started>=? AND started<?",
      arrayOf(phone, OperatorSchedule.startOfDay(start, tz).toString(), start.toString())).use {
      while (it.moveToNext()) {
        val other = JSONObject(it.getString(0))
        if (other.optString("direction") != "in") continue
        earlier++
        if (isMissedOutcome(other)) missedEarlier++
      }
    }
    record.put("callIndexToday", earlier + 1).put("missedToday", missedEarlier + if (isMissedOutcome(record)) 1 else 0)
  }

  @Synchronized private fun linkCallbacks(outgoing: JSONObject) {
    val outgoingId = outgoing.getString("id")
    val outgoingStart = outgoing.getLong("startedAt")
    val matches = ArrayList<JSONObject>()
    readableDatabase.rawQuery("SELECT json FROM calls WHERE phone=? AND started<? AND started>=? ORDER BY started ASC",
      arrayOf(normalized(outgoing.optString("phone")), outgoingStart.toString(), (outgoingStart - OperatorSchedule.DAY_MS).toString())).use {
      while (it.moveToNext()) {
        val missed = JSONObject(it.getString(0))
        if (!isMissedOutcome(missed)) continue
        val open = missed.isNull("resolvedAt") && !missed.optBoolean("callbackConnected", false)
        if (open || missed.optString("callbackCallId") == outgoingId || missed.optString("resolvedCallId") == outgoingId) matches.add(missed)
      }
    }
    if (matches.isEmpty()) return
    val ids = outgoing.optJSONArray("callbackForIds") ?: JSONArray()
    val linkedIds = HashSet<String>()
    for (index in 0 until ids.length()) linkedIds.add(ids.getString(index))
    for (missed in matches) {
      val attempts = missed.optJSONArray("callbackAttemptIds") ?: JSONArray()
      if ((0 until attempts.length()).none { attempts.getString(it) == outgoingId }) attempts.put(outgoingId)
      missed.put("callbackAttemptIds", attempts).put("callbackAttempts", attempts.length())
      if (missed.isNull("callbackAttemptAt")) {
        missed.put("callbackAttemptAt", outgoingStart)
        missed.put("callbackAttemptDelaySeconds", if (!missed.isNull("endedAt")) maxOf(0L, (outgoingStart - missed.getLong("endedAt")) / 1000L) else JSONObject.NULL)
      }
      if (outgoing.optString("outcome") == "answered") {
        val connected = outgoing.optLong("answeredAt", 0L).takeIf { it > 0L }
        missed.put("callbackConnected", true)
          .put("callbackConnectedAt", connected ?: JSONObject.NULL)
          .put("callbackCallId", outgoingId)
          .put("callbackDelaySeconds", if (connected != null && !missed.isNull("endedAt")) maxOf(0L, (connected - missed.getLong("endedAt")) / 1000L) else JSONObject.NULL)
        if (missed.isNull("resolvedAt") || missed.optString("resolvedCallId") == outgoingId) {
          missed.put("resolvedBy", "operator").put("resolvedAt", connected ?: outgoingStart)
            .put("resolvedCallId", outgoingId).put("resolvedTalkSeconds", outgoing.optLong("talkSeconds"))
        }
      }
      if (linkedIds.add(missed.getString("id"))) ids.put(missed.getString("id"))
      saveRecord(missed)
    }
    outgoing.put("callbackForIds", ids).put("callbackForAt", matches.minOf { it.getLong("startedAt") })
    saveRecord(outgoing)
  }

  /** The client called again and was answered: earlier missed calls from that number are solved. */
  @Synchronized private fun linkClientRecall(incoming: JSONObject) {
    val start = incoming.getLong("startedAt")
    val records = ArrayList<JSONObject>()
    readableDatabase.rawQuery("SELECT json FROM calls WHERE phone=? AND started<? AND started>=?",
      arrayOf(normalized(incoming.optString("phone")), start.toString(), (start - OperatorSchedule.DAY_MS).toString())).use {
      while (it.moveToNext()) records.add(JSONObject(it.getString(0)))
    }
    for (missed in records) {
      if (!isMissedOutcome(missed) || !missed.isNull("resolvedAt") || missed.optBoolean("callbackConnected", false)) continue
      missed.put("resolvedBy", "client").put("resolvedAt", incoming.optLong("answeredAt", 0L).takeIf { it > 0L } ?: start)
        .put("resolvedCallId", incoming.getString("id")).put("resolvedTalkSeconds", incoming.optLong("talkSeconds"))
      saveRecord(missed)
    }
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

  @Synchronized fun customerName(phone: String): String? = readableDatabase.rawQuery("SELECT name FROM names WHERE phone=?", arrayOf(normalized(phone))).use {
    if (it.moveToFirst()) it.getString(0) else null
  }

  /** The POS saved an order with this phone number: link it to the latest call within three hours. */
  @Synchronized fun linkOrder(phone: String, orderId: String, at: Long) {
    val key = normalized(phone)
    val id = orderId.trim().take(40)
    if (key.isBlank() || id.isBlank()) return
    writableDatabase.insertWithOnConflict("orders", null, ContentValues().apply { put("phone", key); put("order_id", id); put("at", at) }, SQLiteDatabase.CONFLICT_IGNORE)
    val record = readableDatabase.rawQuery("SELECT json FROM calls WHERE phone=? AND started<=? AND started>=? ORDER BY started DESC LIMIT 1",
      arrayOf(key, (at + 60_000L).toString(), (at - 3 * 3_600_000L).toString())).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null } ?: return
    if (!record.isNull("orderId")) return
    saveRecord(record.put("orderId", id).put("orderAt", at))
  }

  private fun pendingOrder(phone: String, start: Long): Pair<String, Long>? {
    if (phone.isBlank()) return null
    return readableDatabase.rawQuery("SELECT order_id,at FROM orders WHERE phone=? AND at>=? AND at<=? ORDER BY at ASC LIMIT 1",
      arrayOf(phone, (start - 60_000L).toString(), (start + 3 * 3_600_000L).toString())).use { if (it.moveToFirst()) Pair(it.getString(0), it.getLong(1)) else null }
  }

  @Synchronized fun update(id: String, change: (JSONObject) -> Unit): JSONObject? {
    val record = row(id) ?: return null
    change(record)
    saveRecord(record)
    return row(id)
  }

  @Synchronized fun get(id: String): JSONObject? = row(id)

  private fun query(sql: String, args: Array<String>): List<JSONObject> {
    val list = ArrayList<JSONObject>()
    readableDatabase.rawQuery(sql, args).use { while (it.moveToNext()) list.add(JSONObject(it.getString(0))) }
    return list
  }

  @Synchronized fun between(from: Long, to: Long): List<JSONObject> =
    query("SELECT json FROM calls WHERE started>=? AND started<? ORDER BY started ASC", arrayOf(from.toString(), to.toString()))

  @Synchronized fun recentMissed(since: Long): List<JSONObject> = between(since, Long.MAX_VALUE).filter { isMissedOutcome(it) }

  @Synchronized fun numberHistory(phone: String, limit: Int): List<JSONObject> =
    query("SELECT json FROM calls WHERE phone=? ORDER BY started DESC LIMIT ?", arrayOf(normalized(phone), limit.toString()))

  /** Calls whose latest revision has not been handed to the Telegram outbox yet. */
  @Synchronized fun unreported(since: Long, limit: Int): List<JSONObject> =
    query("SELECT json FROM calls WHERE started>=? AND reported<revision ORDER BY started ASC LIMIT ?", arrayOf(since.toString(), limit.toString()))

  @Synchronized fun markReported(id: String, revision: Long) {
    writableDatabase.execSQL("UPDATE calls SET reported=? WHERE id=? AND reported<?", arrayOf(revision, id, revision))
  }

  /** The best call for a Samsung recording: same number (if known) and closest start. */
  @Synchronized fun findForRecording(phone: String?, at: Long): JSONObject? {
    val candidates = if (!phone.isNullOrBlank()) query("SELECT json FROM calls WHERE phone=? AND started>=? AND started<=?",
      arrayOf(normalized(phone), (at - 3 * 3_600_000L).toString(), (at + 5 * 60_000L).toString()))
      else between(at - 3 * 3_600_000L, at + 5 * 60_000L)
    return candidates.filter { it.optString("outcome") == "answered" }
      .filter { call -> val end = call.getLong("startedAt") + call.optLong("talkSeconds") * 1000L + 120_000L; at <= end && at >= call.getLong("startedAt") - 120_000L }
      .minByOrNull { kotlin.math.abs(it.getLong("startedAt") - at) }
  }

  /** Messages sent by 2.1.x: (chat, callId, messageId, sentRevision). */
  @Synchronized fun legacyReports(): List<Array<Any>> {
    val list = ArrayList<Array<Any>>()
    readableDatabase.rawQuery("SELECT chat,id,message_id,sent_revision FROM stats_queue WHERE message_id IS NOT NULL", null).use {
      while (it.moveToNext()) list.add(arrayOf(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3)))
    }
    return list
  }

  /** Start of the Telegram report window, or null while reports are off. Re-enabling starts a new window. */
  @Synchronized fun reportWindow(telegram: JSONObject): Long? {
    if (!telegram.optBoolean("sendCallStats", false)) {
      prefs.edit().putBoolean("stats_enabled", false).apply()
      return null
    }
    val epoch = telegram.optString("_statsBaseline")
    if (!prefs.getBoolean("stats_enabled", false) || prefs.getString("stats_epoch", "") != epoch) {
      prefs.edit().putBoolean("stats_enabled", true).putString("stats_epoch", epoch)
        .putLong("stats_since", telegram.optLong("_statsBaselineAt", System.currentTimeMillis())).commit()
    }
    return prefs.getLong("stats_since", System.currentTimeMillis())
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

  /** Reads Android's final call log; runs on the service worker. */
  fun tick(shifts: List<OperatorShift> = OperatorSchedule.DEFAULT_SHIFTS, tz: TimeZone = TimeZone.getDefault()) {
    try { reconcile(shifts, tz) } catch (_: SecurityException) {
      prefs.edit().putString("last_error", "Qo'ng'iroqlar tarixiga ruxsat kerak").apply()
    } catch (_: Exception) { prefs.edit().putString("last_error", "Qo'ng'iroqlar tarixini o'qib bo'lmadi").apply() }
  }

  @Synchronized fun snapshot(): JSONObject {
    val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM calls", null).use { it.moveToFirst(); it.getLong(0) }
    return JSONObject().put("callCount", count).put("lastError", prefs.getString("last_error", null))
  }

  companion object {
    private fun longOrNull(r: JSONObject, key: String): Long? = if (r.isNull(key)) null else r.optLong(key)

    /** Ledger JSON → report view. Older 2.1.x records simply lack the newer fields. */
    fun view(r: JSONObject): OperatorCallView {
      val connected = r.optBoolean("callbackConnected", false)
      return OperatorCallView(
        id = r.getString("id"), phone = r.optString("phone"), direction = r.optString("direction", "in"),
        outcome = r.optString("outcome", "unknown"), startedAt = r.getLong("startedAt"),
        customerName = r.optString("customerName").takeIf { it.isNotBlank() },
        endedAt = longOrNull(r, "endedAt"), ringSeconds = longOrNull(r, "ringSeconds"), ringDurationSeconds = longOrNull(r, "ringDurationSeconds"),
        talkSeconds = r.optLong("talkSeconds"),
        timingObserved = r.optBoolean("observed", true) || r.optString("direction") == "out" || r.optString("outcome") == "blocked",
        closed = r.optBoolean("closed", false), shiftIndex = if (r.isNull("shiftIndex")) null else r.optInt("shiftIndex"),
        busyWith = r.optString("busyWith").takeIf { it.isNotBlank() && it != "null" },
        missedWhileBusy = if (r.isNull("missedWhileBusy")) null else r.optBoolean("missedWhileBusy"),
        callbackAttempts = r.optInt("callbackAttempts", if (r.isNull("callbackAttemptAt")) 0 else 1),
        firstCallbackAt = longOrNull(r, "callbackAttemptAt"),
        resolvedBy = r.optString("resolvedBy").takeIf { it.isNotBlank() } ?: if (connected) "operator" else null,
        resolvedAt = longOrNull(r, "resolvedAt") ?: if (connected) (longOrNull(r, "callbackConnectedAt") ?: longOrNull(r, "callbackAttemptAt")) else null,
        resolvedTalkSeconds = longOrNull(r, "resolvedTalkSeconds"), lostAt = longOrNull(r, "lostAt"), managerAlertAt = longOrNull(r, "managerAlertAt"),
        callIndexToday = r.optInt("callIndexToday", 1), missedToday = r.optInt("missedToday", 0),
        orderId = r.optString("orderId").takeIf { it.isNotBlank() && !r.isNull("orderId") },
        smsSentAt = longOrNull(r, "smsSentAt"), callbackForAt = longOrNull(r, "callbackForAt"),
      )
    }
  }
}
