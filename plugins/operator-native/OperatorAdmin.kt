package __PACKAGE__

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Owner commands in a private bot chat: view and change managers, groups and alert settings
 * on the phone. Only chats that opened the owner link (config "adminInvite") are obeyed;
 * a new link in the app disconnects every earlier owner chat.
 */
class OperatorAdmin(
  private val context: Context,
  private val bot: OperatorTelegram.Transport,
  private val managerChats: () -> Map<String, String>,
  private val onChanged: () -> Unit,
) {
  private val prefs = context.getSharedPreferences("operator_admin_v1", Context.MODE_PRIVATE)
  private val tz: TimeZone get() = TimeZone.getDefault()

  private fun admins(): JSONObject = try { JSONObject(prefs.getString("chats", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

  fun isAdmin(s: OperatorSettings, chatId: String): Boolean =
    s.adminInvite.length >= 16 && admins().optString(chatId) == s.adminInvite

  /** /start with the owner code; at most three owner chats per link. */
  fun register(s: OperatorSettings, chatId: String, code: String): String? {
    if (s.adminInvite.length < 16 || code != s.adminInvite) return null
    val chats = admins()
    val current = chats.keys().asSequence().filter { chats.optString(it) == s.adminInvite }.toList()
    if (chatId !in current && current.size >= 3) return "Bu havola bilan 3 ta hisob ulangan. Ilovada yangi boshqaruv havolasini yarating."
    val cleaned = JSONObject()
    for (key in current) cleaned.put(key, s.adminInvite)
    cleaned.put(chatId, s.adminInvite)
    prefs.edit().putString("chats", cleaned.toString()).commit()
    return "✅ Siz Smart Food Operator telefonining <b>boshqaruvchisi</b> sifatida ulandingiz.\n\n$HELP"
  }

  /** Returns the reply, or null when the command is not an owner command. */
  fun handle(s: OperatorSettings, command: String, argument: String): String? = try {
    when (command) {
      "/sozlamalar" -> settingsText(s)
      "/menejerlar" -> managersText(s)
      "/menejer_qosh" -> addManager(s, argument)
      "/menejer_ozgartir" -> changeManager(s, argument)
      "/menejer_ochir" -> removeManager(s, argument)
      "/havola" -> inviteText(s, argument)
      "/guruh" -> changeGroup(s, argument)
      "/yopiq_sms" -> closedSms(argument)
      "/vaqt" -> timing(argument)
      "/boshqaruv", "/admin" -> HELP
      else -> null
    }
  } catch (error: IllegalArgumentException) {
    "⚠️ ${OperatorReports.esc(error.message ?: "Buyruq bajarilmadi")}"
  } catch (_: Exception) {
    "⚠️ Sozlamani saqlab bo‘lmadi. Telefonda ilovani ochib tekshiring."
  }

  private fun save(change: (JSONObject) -> Unit) {
    OperatorConfigWriter.update(context, change)
    onChanged()
  }

  private fun e(value: String) = OperatorReports.esc(value)

  private fun scheduleText(s: OperatorSettings, schedule: OperatorManagerSchedule): String {
    val fixed = schedule.fixedShift
    if (fixed != null) return "$fixed-smena (doimiy)"
    val now = System.currentTimeMillis()
    val slot = OperatorSchedule.slotAt(now, s.shifts, tz) ?: s.shifts.firstOrNull()?.let { OperatorSchedule.slotOn(OperatorSchedule.startOfDay(now, tz), it, tz) }
    val current = slot?.let { OperatorSchedule.managerShift(schedule, it, s.shifts, tz) }
    return "har hafta almashadi" + (current?.let { " (bu hafta $it-smena)" } ?: "")
  }

  private fun settingsText(s: OperatorSettings): String {
    val lines = ArrayList<String>()
    lines.add("⚙️ <b>Operator telefoni sozlamalari</b>")
    fun group(id: String) = if (id.isBlank()) "yo‘q" else "<code>${e(id)}</code>"
    lines.add("🎙 Ovoz guruhi: ${group(s.recordingsChat)}" + if (s.audioEnabled) "" else " (o‘chirilgan)")
    lines.add("📊 Hisobot guruhi: ${group(s.statsChat)}" + if (s.reportsEnabled) "" else " (o‘chirilgan)")
    lines.add("🗄 Zaxira guruhi: ${group(s.backupChat)}")
    lines.add("🕒 Smenalar: " + s.shifts.joinToString(", ") { "${it.index}-smena ${OperatorSchedule.formatClock(it.startMinute)}–${OperatorSchedule.formatClock(it.endMinute)}" })
    lines.add("⏱ Menejerga xabar: ${s.managerAlertMs / 60_000} daq · Yo‘qotilgan: ${s.lostMs / 60_000} daq · SMS chegarasi: ${s.smsCap}/kun")
    lines.add("✉️ Yopiq vaqtdagi SMS: " + if (s.closedSmsEnabled) "yoqilgan" else "o‘chirilgan")
    val targets = s.config.optJSONArray("targets") ?: JSONArray()
    val pos = (0 until targets.length()).mapNotNull { targets.optJSONObject(it) }
      .joinToString(", ") { e(it.optString("name", "POS")) + if (it.optString("role") == "cashier") " (kassa)" else " (operator)" }
    lines.add("🖥 POS: " + pos.ifBlank { "qo‘shilmagan" })
    lines.add("")
    lines.add(managersText(s))
    return lines.joinToString("\n")
  }

  private fun managersText(s: OperatorSettings): String {
    if (s.managers.isEmpty()) return "👔 Menejerlar yo‘q. Qo‘shish: <code>/menejer_qosh Ism, +998901234567, 1</code>"
    val chats = managerChats()
    val lines = mutableListOf("👔 <b>Menejerlar</b>")
    s.managers.forEachIndexed { i, m ->
      val phone = if (m.phone.isBlank()) "raqamsiz" else e(OperatorReports.phone(m.phone))
      val tg = if (!m.telegram) "Telegram o‘chiq" else if (chats.containsKey(m.id)) "Telegram ulangan" else "Telegram ulanmagan"
      lines.add("${i + 1}. <b>${e(m.name)}</b> — $phone · ${scheduleText(s, m.schedule)} · SMS ${if (m.sms) "✅" else "❌"} · $tg")
    }
    return lines.joinToString("\n")
  }

  private fun managerIndex(s: OperatorSettings, raw: String): Int {
    val n = raw.trim().toIntOrNull() ?: throw IllegalArgumentException("Menejer raqamini yozing (/menejerlar ro‘yxatidagi tartib raqami)")
    require(n in 1..s.managers.size) { "Bunday tartib raqamli menejer yo‘q" }
    return n - 1
  }

  private fun phoneValue(raw: String): String {
    val clean = raw.trim()
    if (clean.isEmpty() || clean == "-") return ""
    require(OperatorReports.isUzbekMobile(clean)) { "Raqam noto‘g‘ri. Masalan: +998901234567" }
    return OperatorReports.phone(clean)
  }

  private fun scheduleValue(s: OperatorSettings, raw: String): JSONObject {
    val words = raw.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shiftIndexes = s.shifts.map { it.index }
    fun shift(value: String?): Int {
      val n = value?.toIntOrNull() ?: throw IllegalArgumentException("Smena raqamini yozing: ${shiftIndexes.joinToString(" yoki ")}")
      require(n in shiftIndexes) { "Bunday smena yo‘q: ${shiftIndexes.joinToString(" yoki ")}" }
      return n
    }
    return if (words.firstOrNull()?.startsWith("almash") == true) {
      JSONObject().put("type", "rotating").put("anchorWeekStart", OperatorSchedule.weekStart(System.currentTimeMillis(), tz)).put("anchorShift", shift(words.getOrNull(1)))
    } else JSONObject().put("type", "fixed").put("shift", shift(words.firstOrNull()))
  }

  private fun parts(argument: String, count: IntRange, usage: String): List<String> {
    val list = argument.split(',').map { it.trim() }
    require(argument.isNotBlank() && list.size in count) { "Yozilishi: $usage" }
    return list
  }

  private fun addManager(s: OperatorSettings, argument: String): String {
    val usage = "/menejer_qosh Ism, +998901234567, 1 (yoki: almashadi 1)"
    val (name, phone, schedule) = parts(argument, 3..3, usage)
    require(name.length in 1..60) { "Ism 1–60 belgi bo‘lsin" }
    val manager = JSONObject().put("id", "tg" + System.currentTimeMillis().toString(36)).put("name", name)
      .put("phone", phoneValue(phone)).put("schedule", scheduleValue(s, schedule)).put("sms", true).put("telegram", true)
    save { config -> config.put("managers", (config.optJSONArray("managers") ?: JSONArray()).put(manager)) }
    return "✅ <b>${e(name)}</b> qo‘shildi.\nTelegram havolasini olish: /havola ${s.managers.size + 1}"
  }

  private fun changeManager(s: OperatorSettings, argument: String): String {
    val usage = "/menejer_ozgartir 1, raqam, +998901234567 (maydon: ism, raqam, smena, sms, telegram)"
    val (index, field, value) = parts(argument, 3..3, usage)
    val i = managerIndex(s, index)
    val id = s.managers[i].id
    fun yes(v: String) = when (v.lowercase()) { "ha", "yoq", "yo‘q", "yo'q" -> v.lowercase() == "ha"; else -> throw IllegalArgumentException("ha yoki yoq deb yozing") }
    val apply: (JSONObject) -> Unit = when (field.lowercase()) {
      "ism" -> { m -> require(value.length in 1..60) { "Ism 1–60 belgi bo‘lsin" }; m.put("name", value) }
      "raqam" -> { m -> m.put("phone", phoneValue(value)) }
      "smena" -> { m -> m.put("schedule", scheduleValue(s, value)) }
      "sms" -> { m -> m.put("sms", yes(value)) }
      "telegram" -> { m -> m.put("telegram", yes(value)) }
      else -> throw IllegalArgumentException("Yozilishi: $usage")
    }
    save { config ->
      val managers = config.optJSONArray("managers") ?: JSONArray()
      val target = (0 until managers.length()).map { managers.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
        ?: throw IllegalArgumentException("Menejer topilmadi. /menejerlar ro‘yxatini qayta oching")
      apply(target)
    }
    return "✅ ${e(s.managers[i].name)}: o‘zgartirildi."
  }

  private fun removeManager(s: OperatorSettings, argument: String): String {
    val i = managerIndex(s, argument)
    val id = s.managers[i].id
    save { config ->
      val managers = config.optJSONArray("managers") ?: JSONArray()
      val kept = JSONArray()
      for (k in 0 until managers.length()) managers.getJSONObject(k).takeIf { it.optString("id") != id }?.let { kept.put(it) }
      config.put("managers", kept)
    }
    return "✅ ${e(s.managers[i].name)} o‘chirildi. U endi ogohlantirish olmaydi."
  }

  private fun inviteText(s: OperatorSettings, argument: String): String {
    val manager = s.managers[managerIndex(s, argument)]
    val username = context.getSharedPreferences("operator_supervisor_v1", Context.MODE_PRIVATE).getString("bot_username", "") ?: ""
    require(username.isNotBlank() && manager.invite.isNotBlank()) { "Havola hali tayyor emas. Bir daqiqadan keyin qayta urinib ko‘ring" }
    return "🔗 <b>${e(manager.name)}</b> uchun havola (faqat unga yuboring):\nhttps://t.me/${e(username)}?start=${e(manager.invite)}"
  }

  private fun changeGroup(s: OperatorSettings, argument: String): String {
    val usage = "/guruh ovoz|hisobot|zaxira -1001234567890 (zaxirani o‘chirish: /guruh zaxira ochir)"
    val words = argument.trim().split(Regex("\\s+"))
    require(words.size == 2) { "Yozilishi: $usage" }
    val field = when (words[0].lowercase()) { "ovoz" -> "chatId"; "hisobot" -> "statsChatId"; "zaxira" -> "backupChatId"; else -> throw IllegalArgumentException("Yozilishi: $usage") }
    val value = words[1]
    if (field == "backupChatId" && value.lowercase() in setOf("ochir", "o‘chir", "o'chir")) {
      save { config -> config.optJSONObject("telegram")?.put("backupChatId", "") }
      return "✅ Zaxira guruhi o‘chirildi."
    }
    require(Regex("-[0-9]{5,20}").matches(value)) { "Guruh ID si manfiy son bo‘lishi kerak, masalan -1001234567890" }
    val others = mapOf("chatId" to s.recordingsChat, "statsChatId" to s.statsChat, "backupChatId" to s.backupChat).filterKeys { it != field }.values
    require(value !in others) { "Bu guruh boshqa maqsad uchun ishlatilmoqda" }
    val label = when (field) { "chatId" -> "ovoz yozuvlari"; "statsChatId" -> "qo‘ng‘iroqlar hisoboti"; else -> "zaxira" }
    // The bot must be able to post there before the phone switches to it.
    val probe = bot.json(s.token, "sendMessage", JSONObject().put("chat_id", value).put("parse_mode", "HTML")
      .put("text", "✅ Bu guruh Smart Food Operator uchun <b>$label</b> guruhi sifatida ulandi."))
    require(probe.json.optBoolean("ok")) { "Bot bu guruhga yoza olmadi. Botni guruhga qo‘shing va ID ni tekshiring" }
    save { config -> (config.optJSONObject("telegram") ?: JSONObject().also { config.put("telegram", it) }).put(field, value) }
    return "✅ ${label.replaceFirstChar { it.uppercase() }} guruhi o‘zgartirildi: <code>${e(value)}</code>"
  }

  private fun closedSms(argument: String): String {
    val on = when (argument.trim().lowercase()) { "ha" -> true; "yoq", "yo‘q", "yo'q" -> false; else -> throw IllegalArgumentException("Yozilishi: /yopiq_sms ha yoki /yopiq_sms yoq") }
    save { config ->
      val closed = config.optJSONObject("closedSms") ?: JSONObject().put("text", OperatorSettings.DEFAULT_CLOSED_SMS).also { config.put("closedSms", it) }
      closed.put("enabled", on)
    }
    return if (on) "✅ Yopiq vaqtda qo‘ng‘iroq qilganlarga SMS yuboriladi." else "✅ Yopiq vaqtdagi SMS o‘chirildi."
  }

  private fun timing(argument: String): String {
    val usage = "/vaqt menejer 2 · /vaqt yoqotilgan 5 · /vaqt sms 50"
    val words = argument.trim().lowercase().split(Regex("\\s+"))
    require(words.size == 2) { "Yozilishi: $usage" }
    val n = words[1].toIntOrNull() ?: throw IllegalArgumentException("Son yozing. $usage")
    val (field, range, text) = when (words[0]) {
      "menejer" -> Triple("managerAfterMinutes", 1..60, "Menejerga xabar: $n daqiqa")
      "yoqotilgan", "yo‘qotilgan", "yo'qotilgan" -> Triple("lostAfterMinutes", 1..240, "Yo‘qotilgan mijoz: $n daqiqa")
      "sms" -> Triple("smsDailyCap", 0..500, "Kunlik SMS chegarasi: $n")
      else -> throw IllegalArgumentException("Yozilishi: $usage")
    }
    require(n in range) { "Qiymat ${range.first}–${range.last} oralig‘ida bo‘lsin" }
    save { config -> (config.optJSONObject("alerts") ?: JSONObject().also { config.put("alerts", it) }).put(field, n) }
    return "✅ $text."
  }

  companion object {
    const val HELP = "<b>Boshqaruv buyruqlari</b>\n" +
      "/sozlamalar — hozirgi sozlamalar\n" +
      "/menejerlar — menejerlar ro‘yxati\n" +
      "/menejer_qosh Ism, +998901234567, 1 — qo‘shish (smena: 1, 2 yoki «almashadi 1»)\n" +
      "/menejer_ozgartir 1, raqam, +998901234567 — o‘zgartirish (ism, raqam, smena, sms, telegram)\n" +
      "/menejer_ochir 1 — o‘chirish\n" +
      "/havola 1 — menejerning Telegram havolasi\n" +
      "/guruh ovoz|hisobot|zaxira -100… — guruhni almashtirish\n" +
      "/yopiq_sms ha|yoq — yopiq vaqtdagi SMS\n" +
      "/vaqt menejer 2 · /vaqt yoqotilgan 5 · /vaqt sms 50\n" +
      "/holat · /hisobot · /raqam 901234567"
  }
}
