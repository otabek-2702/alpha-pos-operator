package __PACKAGE__

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** Live facts from the foreground service for alerts and /holat. */
data class OperatorPeerHealth(val id: String, val name: String, val connected: Boolean, val role: String)

data class OperatorEnvironment(val callActive: Boolean, val peers: List<OperatorPeerHealth>, val networkUp: Boolean, val appVersion: String,
  /** When the last call on the phone ended (0 = none since the service started). */
  val lastCallEndedAt: Long = 0L)

data class OperatorManager(val id: String, val name: String, val phone: String, val schedule: OperatorManagerSchedule,
  val invite: String, val sms: Boolean, val telegram: Boolean)

/** Settings from the encrypted configuration, with chat IDs that Telegram has since upgraded. */
class OperatorSettings(val config: JSONObject, private val overrides: Map<String, String>) {
  val telegram: JSONObject = config.optJSONObject("telegram") ?: JSONObject()
  val token: String = telegram.optString("botToken")
  private fun chat(raw: String) = raw.trim().let { overrides[it] ?: it }
  val recordingsChat = chat(telegram.optString("chatId"))
  val statsChat = chat(telegram.optString("statsChatId"))
  val backupChat = chat(telegram.optString("backupChatId"))
  val audioEnabled = telegram.optBoolean("enabled") && token.isNotBlank() && recordingsChat.isNotBlank()
  val reportsEnabled = telegram.optBoolean("sendCallStats") && token.isNotBlank() && statsChat.isNotBlank()
  val shifts: List<OperatorShift> = parseShifts(config.optJSONArray("shifts"))
  private val alerts = config.optJSONObject("alerts") ?: JSONObject()
  val managerAlertMs = alerts.optInt("managerAfterMinutes", 2).coerceIn(1, 60) * 60_000L
  val lostMs = alerts.optInt("lostAfterMinutes", 5).coerceIn(1, 240) * 60_000L
  val smsCap = alerts.optInt("smsDailyCap", 50).coerceIn(0, 500)
  private val closed = config.optJSONObject("closedSms") ?: JSONObject()
  val closedSmsEnabled = closed.optBoolean("enabled", false)
  val closedSmsText: String = closed.optString("text").trim().ifBlank { DEFAULT_CLOSED_SMS }
  val managers: List<OperatorManager> = parseManagers(config.optJSONArray("managers"))
  /** Owner link code for remote settings through the bot. */
  val adminInvite: String = config.optString("adminInvite")

  companion object {
    const val DEFAULT_CLOSED_SMS = "Smart Food kafesi hozir ishlamayapti. Ish vaqtimiz: har kuni 08:00 dan 02:00 gacha. Qo'ng'iroq qilganingiz uchun rahmat!"

    fun parseShifts(array: JSONArray?): List<OperatorShift> {
      if (array == null || array.length() == 0) return OperatorSchedule.DEFAULT_SHIFTS
      val list = ArrayList<OperatorShift>()
      for (i in 0 until minOf(array.length(), 6)) {
        val item = array.optJSONObject(i) ?: continue
        val start = OperatorSchedule.parseClock(item.optString("start")) ?: continue
        val end = OperatorSchedule.parseClock(item.optString("end")) ?: continue
        val index = item.optInt("index", i + 1)
        list.add(OperatorShift(index, item.optString("name").ifBlank { "$index-smena" }.take(40), start, end))
      }
      return list.ifEmpty { OperatorSchedule.DEFAULT_SHIFTS }
    }

    fun parseManagers(array: JSONArray?): List<OperatorManager> {
      if (array == null) return emptyList()
      val list = ArrayList<OperatorManager>()
      for (i in 0 until minOf(array.length(), 30)) {
        val item = array.optJSONObject(i) ?: continue
        val id = item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: continue
        val s = item.optJSONObject("schedule") ?: JSONObject()
        val schedule = if (s.optString("type") == "fixed") OperatorManagerSchedule(fixedShift = s.optInt("shift", 1))
          else OperatorManagerSchedule(anchorWeekStart = s.optLong("anchorWeekStart", 0L).takeIf { it > 0 }, anchorShift = s.optInt("anchorShift", 1))
        list.add(OperatorManager(id, item.optString("name").take(60), item.optString("phone").take(20), schedule,
          item.optString("invite"), item.optBoolean("sms", true), item.optBoolean("telegram", true)))
      }
      return list
    }
  }
}

/**
 * Turns the call ledger into Telegram reports, alerts, SMS and shift reports.
 * Runs on the service workers; all delivery goes through the durable [OperatorTelegram] outbox.
 */
class OperatorSupervisor(private val context: Context, private val history: OperatorCallHistory, private val recordings: OperatorRecordings) :
  OperatorTelegram.Listener {
  val telegram = OperatorTelegram(context).also { it.listener = this }
  val sms = OperatorSms(context)
  private val contacts = OperatorContacts(context)
  private val bot = OperatorTelegram.HttpTransport()
  /** Called after an owner changed the configuration through the bot. */
  @Volatile var configChanged: (() -> Unit)? = null
  private val admin = OperatorAdmin(context, bot, { managerChats() }, { configChanged?.invoke() })
  private val prefs = context.getSharedPreferences("operator_supervisor_v1", Context.MODE_PRIVATE)
  private val tz: TimeZone get() = TimeZone.getDefault()
  @Volatile private var environment = OperatorEnvironment(false, emptyList(), true, "")
  private val downSince = HashMap<String, Long>()
  private val posAlerts = HashMap<String, String>()
  private var offlineSince: Long? = null
  private var pluggedBefore: Boolean? = null
  private var unpluggedAt: Long? = null
  @Volatile var batteryLevel = -1; private set
  @Volatile var charging = false; private set
  private var uptimeTotal = HashMap<String, Long>()
  private var uptimeUp = HashMap<String, Long>()
  private var uptimeFlushedAt = 0L

  init {
    // Alerts and closed-hours SMS only for calls after this version started.
    if (prefs.getLong("lifecycle_since", 0L) == 0L) prefs.edit().putLong("lifecycle_since", System.currentTimeMillis()).apply()
  }

  private fun json(key: String): JSONObject = try { JSONObject(prefs.getString(key, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
  private fun map(key: String): Map<String, String> = json(key).let { o -> o.keys().asSequence().associateWith { o.optString(it) } }

  fun overrides(): Map<String, String> = map("chat_overrides")
  fun settings(config: JSONObject) = OperatorSettings(config, overrides())
  fun managerChats(): Map<String, String> = map("manager_chats")
  fun botUsername(): String = prefs.getString("bot_username", "") ?: ""

  override fun chatMigrated(from: String, to: String) {
    val o = json("chat_overrides")
    for (key in o.keys().asSequence().toList()) if (o.optString(key) == from) o.put(key, to)
    o.put(from, to)
    // Written before the next post: later rows must use the new chat ID.
    val edit = prefs.edit().putString("chat_overrides", o.toString())
    if (prefs.getString("recordings_chat", "") == from) edit.putString("recordings_chat", to)
    edit.commit()
  }

  override fun delivered(key: String, chat: String, messageId: Long) {
    if (key.startsWith("rec:") && chat == prefs.getString("recordings_chat", "")) recordings.markSent(key.removePrefix("rec:"), messageId)
  }

  private fun reportChats(s: OperatorSettings, at: Long): List<String> {
    if (!s.reportsEnabled) return emptyList()
    return listOfNotNull(s.statsChat, backupFor(s, at))
  }

  private fun audioChats(s: OperatorSettings, at: Long): List<String> {
    if (!s.audioEnabled) return emptyList()
    return listOfNotNull(s.recordingsChat, backupFor(s, at))
  }

  /** The backup starts with data created after it was configured, never with old history. */
  private fun backupFor(s: OperatorSettings, at: Long): String? {
    if (s.backupChat.isBlank()) return null
    if (prefs.getString("backup_chat", "") != s.backupChat) {
      val previous = prefs.getString("backup_chat", "") ?: ""
      val migrated = overrides()[previous] == s.backupChat
      val edit = prefs.edit().putString("backup_chat", s.backupChat)
      if (!migrated) edit.putLong("backup_since", System.currentTimeMillis())
      edit.commit()
    }
    return if (at >= prefs.getLong("backup_since", Long.MAX_VALUE)) s.backupChat else null
  }

  private fun onDuty(s: OperatorSettings, at: Long): List<OperatorManager> {
    val slot = OperatorSchedule.slotAt(at, s.shifts, tz) ?: return emptyList()
    return s.managers.filter { OperatorSchedule.isOnDuty(it.schedule, slot, s.shifts, tz) }
  }

  private fun onDuty(s: OperatorSettings, slot: OperatorShiftSlot): List<OperatorManager> =
    s.managers.filter { OperatorSchedule.isOnDuty(it.schedule, slot, s.shifts, tz) }

  private fun managerNames(s: OperatorSettings, at: Long): List<String> = onDuty(s, at).map { it.name }.filter { it.isNotBlank() }

  /** Adds the name saved in the phone's contacts (if the app may read them). */
  private fun named(view: OperatorCallView): OperatorCallView =
    contacts.name(view.phone)?.let { view.copy(contactName = it) } ?: view

  private fun managerTelegram(managers: List<OperatorManager>): List<String> {
    val chats = managerChats()
    return managers.filter { it.telegram }.mapNotNull { chats[it.id] }
  }

  /** Every 15 seconds, after the call log was reconciled. */
  fun tick(config: JSONObject, env: OperatorEnvironment) {
    environment = env
    val s = settings(config)
    val now = System.currentTimeMillis()
    prefs.edit().putString("recordings_chat", s.recordingsChat).apply()
    migrateLegacy(s)
    step { reportCalls(s, now) }
    step { lifecycle(s, now) }
    step { deliverRecordings(s, now) }
    step { shiftReports(s, now) }
    step { health(s, now) }
    step { OperatorUpdater.takeReport(context)?.let { text ->
      val chats = listOf(s.statsChat.ifBlank { s.recordingsChat }, s.backupChat).filter { it.isNotBlank() }
      telegram.post("update:$now", chats, text, 1, editable = false)
    } }
  }

  private inline fun step(block: () -> Unit) {
    try { block() } catch (_: Exception) { prefs.edit().putString("error", "Hisobotlarni tayyorlashda xato").apply() }
  }

  fun drain(config: JSONObject): Int = telegram.drain(settings(config).token)

  private fun migrateLegacy(s: OperatorSettings) {
    if (prefs.getBoolean("legacy_migrated", false)) return
    for (row in history.legacyReports()) {
      val chat = row[0] as String
      telegram.adopt("call:${row[1]}", overrides()[chat] ?: chat, row[2] as Long, row[3] as Long)
    }
    prefs.edit().putBoolean("legacy_migrated", true).apply()
  }

  private fun reportCalls(s: OperatorSettings, now: Long) {
    val since = history.reportWindow(s.telegram) ?: return
    repeat(10) {
      val batch = history.unreported(since, 40)
      for (record in batch) {
        val view = OperatorCallHistory.view(record)
        val revision = record.optLong("revision")
        // A late change to an old call (for example a customer name) is not posted again as a new message.
        if (now - view.startedAt < 20 * OperatorSchedule.DAY_MS) {
          val managers = if (view.closed) emptyList() else managerNames(s, view.startedAt)
          telegram.post("call:${view.id}", reportChats(s, view.startedAt), OperatorReports.callHtml(named(view), tz, s.lostMs, managers), revision)
        }
        history.markReported(view.id, revision)
      }
      if (batch.size < 40) return
    }
  }

  private fun numberKey(phone: String) = phone.filter { it.isDigit() }.takeLast(9)

  private fun lifecycle(s: OperatorSettings, now: Long) {
    val since = maxOf(prefs.getLong("lifecycle_since", now), now - 12 * 3_600_000L)
    val missed = history.recentMissed(since).map { OperatorCallHistory.view(it) }
    val env = environment
    // Timers run only while the phone is free: they start when the last call (missed or not) ended.
    val quietSince = env.lastCallEndedAt
    // One alert per waiting client: redials of the same number do not alert again.
    val alerted = missed.filter { it.resolvedAt == null && it.managerAlertAt != null }.map { numberKey(it.phone) }.toMutableSet()
    val lost = missed.filter { it.resolvedAt == null && it.lostAt != null }.map { numberKey(it.phone) }.toMutableSet()
    for (view in missed) {
      if (view.resolvedAt != null) { OperatorCallbackReminder.cancel(context, view.id); continue }
      if (view.closed) { closedSms(s, view, now); continue }
      val number = numberKey(view.phone)
      if (number.isEmpty()) continue   // A hidden number cannot be called back.
      if (env.callActive) continue   // The operator is talking (maybe calling this client back).
      val missedAt = OperatorReports.missedAt(view)
      val freeAt = maxOf(missedAt, quietSince)
      if (OperatorReports.needsManagerAlert(view, now, s.managerAlertMs, quietSince)) {
        history.update(view.id) { it.put("managerAlertAt", now) }
        // After downtime, calls that are already stale are only marked, never alerted in a burst.
        if (number !in alerted && now - freeAt < maxOf(s.lostMs, s.managerAlertMs + 120_000L)) {
          alerted.add(number)
          val shown = named(view)
          OperatorCallbackReminder.show(context, shown, tz)
          val duty = onDuty(s, view.startedAt)
          val minutes = s.managerAlertMs / 60_000
          telegram.post("alert:${view.id}", managerTelegram(duty), OperatorReports.managerAlertHtml(shown, tz, minutes, duty.map { it.name }), 1, editable = false)
          val shift = OperatorSchedule.slotAt(view.startedAt, s.shifts, tz)?.id ?: OperatorSchedule.dateTag(view.startedAt, tz)
          for (manager in duty.filter { it.sms && it.phone.isNotBlank() }) {
            // At most one SMS per client, shift and manager.
            sms.send("alert:$shift:$number:${manager.id}", OperatorReports.smsPhone(manager.phone), OperatorReports.managerAlertSms(shown, tz, minutes), s.smsCap, tz)
          }
        }
      }
      if (OperatorReports.becomesLost(view, now, s.lostMs, quietSince)) {
        val lostAt = minOf(now, freeAt + s.lostMs)
        history.update(view.id) { it.put("lostAt", lostAt) }
        if (number !in lost && now - freeAt < s.lostMs + 15 * 60_000L) {
          lost.add(number)
          val text = OperatorReports.lostAlertHtml(named(view).copy(lostAt = lostAt), tz, s.lostMs, managerNames(s, view.startedAt))
          telegram.post("lost:${view.id}", reportChats(s, view.startedAt), text, 1, replyToKey = "call:${view.id}", editable = false)
          telegram.post("lost:${view.id}", managerTelegram(onDuty(s, view.startedAt)), text, 1, editable = false)
        }
      }
    }
  }

  private fun closedSms(s: OperatorSettings, view: OperatorCallView, now: Long) {
    if (!s.closedSmsEnabled || view.smsSentAt != null || !OperatorReports.isUzbekMobile(view.phone)) return
    if (now - view.startedAt > 30 * 60_000L) return
    val period = OperatorSchedule.closedPeriodStart(view.startedAt, s.shifts, tz)
    val number = OperatorReports.smsPhone(view.phone)
    if (sms.send("closed:$period:$number", number, s.closedSmsText, s.smsCap, tz)) history.update(view.id) { it.put("smsSentAt", now) }
  }

  private fun deliverRecordings(s: OperatorSettings, now: Long) {
    if (!s.audioEnabled) return
    val audio = json("audio_calls")
    for (file in recordings.readyForDelivery(s.telegram)) {
      val (phone, stamped) = OperatorReports.parseRecordingName(file.name, tz)
      val at = stamped ?: file.modified.takeIf { it > 0 } ?: now
      val call = history.findForRecording(phone, at)
      if (call != null) audio.put(call.getString("id"), now)
      val view = call?.let { named(OperatorCallHistory.view(it)) }
      val callAt = view?.startedAt ?: at
      val caption = OperatorReports.recordingCaptionHtml(file.name, at, view, tz, managerNames(s, callAt), phone)
      val uploadName = OperatorReports.recordingFileName(file.name, view?.phone?.takeIf { it.isNotBlank() } ?: phone)
      val audioPlayer = OperatorReports.isPlayableAudio(file.name)
      val (title, performer) = OperatorReports.recordingPlayerTitle(file.name, at, view, tz, phone)
      telegram.postDocument("rec:${file.uri}", audioChats(s, now), file.uri, uploadName, file.size, caption,
        audio = audioPlayer, title = title, performer = performer)
      recordings.markQueued(s.telegram, file.uri)
    }
    val trimmed = JSONObject()
    for (key in audio.keys()) if (now - audio.optLong(key) < 2 * OperatorSchedule.DAY_MS) trimmed.put(key, audio.optLong(key))
    prefs.edit().putString("audio_calls", trimmed.toString()).apply()
  }

  private fun shiftReports(s: OperatorSettings, now: Long) {
    val last = prefs.getLong("shift_checked", 0L)
    if (last == 0L) { prefs.edit().putLong("shift_checked", now).apply(); return }
    for (slot in OperatorSchedule.slotsEndedBetween(last, now, s.shifts, tz)) {
      val text = OperatorReports.shiftReportHtml(summarize(s, slot, slot.endAt), tz)
      telegram.post("shift:${slot.id}", reportChats(s, slot.endAt), text, 1, pin = true)
      telegram.post("shift:${slot.id}", managerTelegram(onDuty(s, slot)), text, 1)
    }
    prefs.edit().putLong("shift_checked", now).apply()
  }

  fun summarize(s: OperatorSettings, slot: OperatorShiftSlot, until: Long): OperatorShiftSummary {
    val calls = history.between(slot.startAt, minOf(slot.endAt, until)).map { OperatorCallHistory.view(it) }
    val statuses = calls.associateWith { OperatorReports.status(it) }
    val answered = calls.filter { statuses[it] == OperatorCallStatus.ANSWERED }
    val missed = calls.filter { OperatorReports.isMissed(it) && !it.closed }
    val resolved = missed.filter { it.resolvedAt != null }
    val lost = missed.filter { statuses[it] == OperatorCallStatus.LOST }
    val outgoing = calls.filter { it.direction == "out" }
    val perHour = HashMap<Int, Int>()
    for (call in calls.filter { it.direction == "in" }) perHour.merge(OperatorSchedule.hourOf(call.startedAt, tz), 1, Int::plus)
    val gap = OperatorSchedule.closedGapBefore(slot, s.shifts, tz)
    val closed = gap?.let { history.between(it.first, it.last + 1).map { r -> OperatorCallHistory.view(r) }.filter { c -> c.direction == "in" } } ?: emptyList()
    return OperatorShiftSummary(
      shiftName = slot.shift.name, shiftIndex = slot.shift.index, startAt = slot.startAt, endAt = minOf(slot.endAt, until),
      total = calls.size, answered = answered.size,
      avgAnswerSeconds = answered.mapNotNull { it.ringSeconds }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
      resolved = resolved.size,
      avgResolveSeconds = resolved.map { (it.resolvedAt!! - OperatorReports.missedAt(it)) / 1000 }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
      waiting = missed.count { statuses[it] == OperatorCallStatus.WAITING || statuses[it] == OperatorCallStatus.CALLED_BACK_NO_ANSWER },
      lost = lost.map { OperatorLostEntry(it.phone, it.customerName, it.startedAt, telegram.messageLink("call:${it.id}", s.statsChat)) },
      outgoing = outgoing.size, outgoingTalked = outgoing.count { it.outcome == "answered" },
      blocked = calls.count { statuses[it] == OperatorCallStatus.BLOCKED },
      talkSeconds = calls.sumOf { it.talkSeconds }, perHour = perHour, orders = calls.count { it.orderId != null },
      serviceUptimePercent = serviceUptime(slot, until), posUptimePercent = posUptime(slot),
      closedCalls = closed.size, closedMissed = closed.count { OperatorReports.isMissed(it) }, closedSms = closed.count { it.smsSentAt != null },
    )
  }

  private fun serviceUptime(slot: OperatorShiftSlot, until: Long): Int? {
    val end = minOf(slot.endAt, until)
    if (end <= slot.startAt) return null
    val periods = OperatorRuntimeStore.state(context).optJSONArray("periods") ?: return null
    var covered = 0L
    for (i in 0 until periods.length()) {
      val p = periods.optJSONObject(i) ?: continue
      val from = maxOf(p.optLong("startedAt"), slot.startAt)
      val to = minOf(if (p.isNull("endedAt")) System.currentTimeMillis() else p.optLong("endedAt"), end)
      if (to > from) covered += to - from
    }
    return (covered * 100 / (end - slot.startAt)).toInt().coerceIn(0, 100)
  }

  /** Called by the service every few seconds with the share of POS connections that are up. */
  @Synchronized fun recordPosUptime(elapsedMs: Long, connectedRatio: Double, config: JSONObject) {
    if (elapsedMs <= 0 || elapsedMs > 60_000) return
    val slot = OperatorSchedule.slotAt(System.currentTimeMillis(), settings(config).shifts, tz) ?: return
    uptimeTotal.merge(slot.id, elapsedMs, Long::plus)
    uptimeUp.merge(slot.id, (elapsedMs * connectedRatio).toLong(), Long::plus)
    val now = System.currentTimeMillis()
    if (now - uptimeFlushedAt < 60_000) return
    uptimeFlushedAt = now
    val stored = json("pos_uptime")
    for ((id, total) in uptimeTotal) {
      val entry = stored.optJSONObject(id) ?: JSONObject()
      stored.put(id, entry.put("total", entry.optLong("total") + total).put("up", entry.optLong("up") + (uptimeUp[id] ?: 0L)).put("at", now))
    }
    uptimeTotal.clear(); uptimeUp.clear()
    val trimmed = JSONObject()
    for (key in stored.keys()) stored.optJSONObject(key)?.takeIf { now - it.optLong("at") < 3 * OperatorSchedule.DAY_MS }?.let { trimmed.put(key, it) }
    prefs.edit().putString("pos_uptime", trimmed.toString()).apply()
  }

  @Synchronized private fun posUptime(slot: OperatorShiftSlot): Int? {
    val entry = json("pos_uptime").optJSONObject(slot.id)
    val total = (entry?.optLong("total") ?: 0L) + (uptimeTotal[slot.id] ?: 0L)
    val up = (entry?.optLong("up") ?: 0L) + (uptimeUp[slot.id] ?: 0L)
    return if (total < 60_000) null else (up * 100 / total).toInt().coerceIn(0, 100)
  }

  private fun health(s: OperatorSettings, now: Long) {
    val env = environment
    val inShift = OperatorSchedule.slotAt(now, s.shifts, tz) != null
    val alertChats = reportChats(s, now) + managerTelegram(onDuty(s, now))
    for (peer in env.peers) {
      if (!peer.connected) {
        val since = downSince.getOrPut(peer.id) { now }
        if (inShift && now - since >= 5 * 60_000L && peer.id !in posAlerts) {
          val key = "pos:${peer.id}:$since"
          posAlerts[peer.id] = key
          telegram.post(key, alertChats, OperatorReports.posHealthHtml(peer.name, since, null, tz), 1)
        }
      } else {
        val since = downSince.remove(peer.id)
        val key = posAlerts.remove(peer.id)
        if (since != null && key != null) telegram.post(key, alertChats, OperatorReports.posHealthHtml(peer.name, since, now, tz), 2)
      }
    }
    downSince.keys.retainAll(env.peers.map { it.id }.toSet())
    if (!env.networkUp) {
      if (offlineSince == null) offlineSince = now
    } else offlineSince?.let { from ->
      offlineSince = null
      if (now - from >= 2 * 60_000L) telegram.post("net:$from", alertChats, OperatorReports.internetOutageHtml(from, now, tz), 1, editable = false)
    }
    battery(alertChats, now)
    if (s.audioEnabled) missingAudio(s, alertChats, now)
  }

  private fun battery(chats: List<String>, now: Long) {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    batteryLevel = level; charging = plugged
    if (level in 0..20 && !plugged && !prefs.getBoolean("battery_low_alerted", false)) {
      prefs.edit().putBoolean("battery_low_alerted", true).apply()
      telegram.post("battery:low:$now", chats, OperatorReports.batteryHtml(level, false, true, now, tz), 1, editable = false)
    }
    if (plugged || level > 30) prefs.edit().putBoolean("battery_low_alerted", false).apply()
    if (pluggedBefore == true && !plugged) unpluggedAt = now
    if (plugged) unpluggedAt = null
    pluggedBefore = plugged
    val since = unpluggedAt
    if (since != null && now - since >= 2 * 60_000L && level < 95) {
      unpluggedAt = null
      telegram.post("battery:unplugged:$since", chats, OperatorReports.batteryHtml(level, false, false, now, tz), 1, editable = false)
    }
  }

  private fun missingAudio(s: OperatorSettings, chats: List<String>, now: Long) {
    val audio = json("audio_calls")
    val alerted = json("audio_alerted")
    val since = maxOf(prefs.getLong("lifecycle_since", now), now - 3 * 3_600_000L)
    for (record in history.between(since, now)) {
      val view = OperatorCallHistory.view(record)
      if (view.outcome != "answered" || view.talkSeconds < 5 || view.closed || audio.has(view.id) || alerted.has(view.id)) continue
      if (now - (view.startedAt + view.talkSeconds * 1000L) < 10 * 60_000L) continue
      alerted.put(view.id, now)
      telegram.post("noaudio:${view.id}", chats, OperatorReports.missingAudioHtml(view, tz), 1, editable = false)
    }
    val trimmed = JSONObject()
    for (key in alerted.keys()) if (now - alerted.optLong(key) < OperatorSchedule.DAY_MS) trimmed.put(key, alerted.optLong(key))
    prefs.edit().putString("audio_alerted", trimmed.toString()).apply()
  }

  // ---- Bot commands (long polling by the phone; there is no server) ----

  /** One long-poll round. Returns false when the bot is not configured or Telegram refused. */
  fun pollBot(config: JSONObject): Boolean {
    val s = settings(config)
    if (s.token.isBlank()) return false
    if (botUsername().isBlank()) {
      val me = bot.json(s.token, "getMe", JSONObject())
      me.json.optJSONObject("result")?.optString("username")?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString("bot_username", it).apply() }
    }
    val body = JSONObject().put("offset", prefs.getLong("bot_offset", 0L)).put("timeout", 25).put("allowed_updates", JSONArray().put("message"))
    val response = bot.json(s.token, "getUpdates", body)
    if (!response.json.optBoolean("ok")) {
      prefs.edit().putString("bot_error", "Bot buyruqlari qabul qilinmadi (HTTP ${response.code})").apply()
      return false
    }
    prefs.edit().remove("bot_error").apply()
    val updates = response.json.optJSONArray("result") ?: return true
    var offset = prefs.getLong("bot_offset", 0L)
    for (i in 0 until updates.length()) {
      val update = updates.optJSONObject(i) ?: continue
      offset = maxOf(offset, update.optLong("update_id") + 1)
      try { update.optJSONObject("message")?.let { handleMessage(s, it) } } catch (_: Exception) { }
    }
    prefs.edit().putLong("bot_offset", offset).apply()
    return true
  }

  private fun handleMessage(s: OperatorSettings, message: JSONObject) {
    val text = message.optString("text").trim()
    if (!text.startsWith("/")) return
    val chat = message.optJSONObject("chat") ?: return
    val chatId = chat.optLong("id").toString()
    val word = text.substringBefore(' ')
    val addressed = word.substringAfter('@', "")
    if (addressed.isNotEmpty() && !addressed.equals(botUsername(), ignoreCase = true)) return
    val command = word.substringBefore('@').lowercase()
    val argument = text.substringAfter(' ', "").trim()
    val replyKey = "cmd:$chatId:${message.optLong("message_id")}"
    val private = chat.optString("type") == "private"
    if (command == "/start" && private) {
      val reply = admin.register(s, chatId, argument) ?: register(s, chatId, argument)
      telegram.post(replyKey, listOf(chatId), reply, 1, editable = false)
      return
    }
    val owner = private && admin.isAdmin(s, chatId)
    if (owner) {
      admin.handle(s, command, argument)?.let { reply ->
        telegram.post(replyKey, listOf(chatId), reply, 1, editable = false)
        return
      }
    }
    val managerIds = s.managers.map { it.id }.toSet()
    val allowed = setOf(s.statsChat, s.backupChat, s.recordingsChat).filter { it.isNotBlank() } +
      managerChats().filterKeys { it in managerIds }.values
    if (chatId !in allowed && !owner) return
    val reply = when (command) {
      "/holat" -> statusText(s)
      "/hisobot" -> OperatorSchedule.slotAt(System.currentTimeMillis(), s.shifts, tz)
        ?.let { OperatorReports.shiftReportHtml(summarize(s, it, System.currentTimeMillis()), tz) }
        ?: "⚪ Hozir smena yo‘q — kafe yopiq."
      "/raqam" -> numberText(argument)
      "/start", "/yordam", "/help" -> if (owner) OperatorAdmin.HELP else HELP
      else -> return
    }
    telegram.post(replyKey, listOf(chatId), reply, 1, editable = false)
  }

  private fun register(s: OperatorSettings, chatId: String, code: String): String {
    val manager = s.managers.firstOrNull { code.length >= 8 && it.invite == code }
      ?: return "Havola noto‘g‘ri yoki eskirgan. Operator telefonidan yangi havola so‘rang."
    val chats = json("manager_chats")
    val bound = chats.optString(manager.id)
    // A forwarded link cannot take over a manager who is already connected.
    if (bound.isNotEmpty() && bound != chatId) {
      return "Bu havola boshqa Telegram hisobiga ulangan. Operator telefonida menejerni o‘chirib, qayta qo‘shing va yangi havolani yuboring."
    }
    chats.put(manager.id, chatId)
    prefs.edit().putString("manager_chats", chats.toString()).commit()
    return "Salom, <b>${OperatorReports.esc(manager.name)}</b>! Siz Smart Food menejeri sifatida ulandingiz.\n" +
      "Smenangizdagi javobsiz va yo‘qotilgan qo‘ng‘iroqlar hamda smena hisobotlari shu yerga keladi.\n\n$HELP"
  }

  private fun statusText(s: OperatorSettings): String {
    val env = environment
    val now = System.currentTimeMillis()
    val slot = OperatorSchedule.slotAt(now, s.shifts, tz)
    val duty = slot?.let { onDuty(s, it).map { m -> OperatorReports.esc(m.name) } }.orEmpty()
    val today = history.between(OperatorSchedule.startOfDay(now, tz), now).map { OperatorCallHistory.view(it) }
    val lines = ArrayList<String>()
    lines.add("<b>Operator holati</b> · ${OperatorSchedule.clock(now, tz)}")
    lines.add("Versiya: ${OperatorReports.esc(env.appVersion)} · Batareya: ${if (batteryLevel >= 0) "$batteryLevel%" else "?"}" + if (charging) " (quvvatlanmoqda)" else "")
    lines.add("Internet: ${if (env.networkUp) "bor" else "yo‘q"} · Navbatdagi xabarlar: ${telegram.pending()}")
    lines.add("POS: " + env.peers.joinToString(" · ") { (if (it.connected) "🟢 " else "🔴 ") + OperatorReports.esc(it.name) }.ifBlank { "qo‘shilmagan" })
    lines.add(if (slot == null) "⚪ Kafe yopiq" else "Smena: ${OperatorReports.esc(slot.shift.name)} (${OperatorSchedule.formatClock(slot.shift.startMinute)}–${OperatorSchedule.formatClock(slot.shift.endMinute)})" +
      (if (duty.isNotEmpty()) " · Menejer: ${duty.joinToString(", ")}" else ""))
    val lost = today.count { OperatorReports.status(it) == OperatorCallStatus.LOST }
    lines.add("Bugun: ${today.size} qo‘ng‘iroq" + if (lost > 0) " · 🔴 $lost yo‘qotilgan" else "")
    telegram.lastError()?.let { lines.add("🟡 ${OperatorReports.esc(it)}") }
    return lines.joinToString("\n")
  }

  private fun numberText(argument: String): String {
    val digits = argument.filter { it.isDigit() }
    if (digits.length < 9) return "Raqamni yozing, masalan: <code>/raqam 901234567</code>"
    val calls = history.numberHistory(digits, 10).map { OperatorCallHistory.view(it) }
    if (calls.isEmpty()) return "${OperatorReports.esc(OperatorReports.phone(digits))} raqamidan qo‘ng‘iroq topilmadi."
    val lines = ArrayList<String>()
    lines.add("<b>${OperatorReports.esc(OperatorReports.phone(digits))}</b>" + (calls.firstNotNullOfOrNull { it.customerName }?.let { " · ${OperatorReports.esc(it)}" } ?: ""))
    for (call in calls) {
      val talk = if (call.talkSeconds > 0) " · ${OperatorReports.duration(call.talkSeconds)}" else ""
      lines.add("${OperatorSchedule.date(call.startedAt, tz).take(5)} ${OperatorSchedule.clock(call.startedAt, tz)} · ${OperatorReports.shortStatus(OperatorReports.status(call))}$talk")
    }
    return lines.joinToString("\n")
  }

  fun snapshot(): JSONObject = JSONObject()
    .put("outboxPending", telegram.pending()).put("outboxError", telegram.lastError() ?: JSONObject.NULL)
    .put("lastSentAt", telegram.lastSentAt()).put("smsToday", sms.sentToday(tz)).put("smsError", sms.lastError() ?: JSONObject.NULL)
    .put("smsPermission", sms.hasPermission()).put("botUsername", botUsername()).put("botError", prefs.getString("bot_error", null) ?: JSONObject.NULL)
    .put("managerChats", json("manager_chats")).put("backupSince", prefs.getLong("backup_since", 0L))

  fun retryNow() = telegram.retryNow()

  companion object {
    const val HELP = "Buyruqlar:\n/holat — telefon va POS holati\n/hisobot — joriy smena hisoboti\n/raqam 901234567 — raqam tarixi"
  }
}
