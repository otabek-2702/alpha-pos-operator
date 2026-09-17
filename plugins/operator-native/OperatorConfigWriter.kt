package __PACKAGE__

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import java.util.UUID

/**
 * The one place a configuration is validated and stored, for the app screens and for
 * Telegram admin commands alike. [IllegalArgumentException] messages are shown to users.
 */
object OperatorConfigWriter {
  private val lock = Any()
  private val INVITE = Regex("[A-Za-z0-9]{16,32}")

  /** Validates, completes (bot token, baselines, invite codes) and stores [value]; returns it. */
  fun save(context: Context, value: JSONObject): JSONObject = synchronized(lock) {
    val previous = OperatorRuntimeStore.config(context)
    val targets = value.optJSONArray("targets") ?: JSONArray()
    require(targets.length() <= 50) { "Ko'pi bilan 50 ta POS qo'shish mumkin" }
    val ids = HashSet<String>()
    for (i in 0 until targets.length()) {
      val target = targets.getJSONObject(i)
      require(target.optString("id").isNotBlank() && target.optString("id").length <= 128 && ids.add(target.getString("id"))) { "POS identifikatori noto'g'ri yoki takrorlangan" }
      require(target.optString("name").length <= 200) { "POS nomi juda uzun" }
      val uri = URI(target.getString("url"))
      require(uri.scheme in listOf("ws", "wss") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "POS manzili noto'g'ri" }
      require(target.optInt("discoveryPort", 8766) in 1..65535) { "POS qidirish porti noto'g'ri" }
      require(target.optString("role", "operator") in setOf("operator", "cashier")) { "POS turi noto'g'ri" }
    }
    value.put("targets", targets)
    val oldTelegram = previous.optJSONObject("telegram")
    val telegram = value.optJSONObject("telegram") ?: oldTelegram?.let { JSONObject(it.toString()) } ?: JSONObject().put("enabled", false)
    if (!telegram.has("botToken") && oldTelegram != null) telegram.put("botToken", oldTelegram.optString("botToken"))
    val token = telegram.optString("botToken").trim()
    telegram.put("botToken", token)
    if (token.isNotEmpty()) require(Regex("[0-9]{5,}:[A-Za-z0-9_-]{20,}").matches(token)) { "Telegram bot tokeni noto'g'ri" }
    for (field in listOf("chatId", "statsChatId", "backupChatId")) {
      if (telegram.has(field)) telegram.put(field, telegram.optString(field).trim())
      if (telegram.optString(field).isNotEmpty()) require(Regex("-?[0-9]+|@[A-Za-z0-9_]{5,}").matches(telegram.getString(field))) { "Telegram guruh ID si noto'g'ri" }
    }
    val folderUri = telegram.optString("folderUri")
    if (folderUri.isNotEmpty()) {
      if (Uri.parse(folderUri).scheme == "file") telegram.put("folderUri", Uri.fromFile(OperatorRecordings.localFolder(folderUri)).toString())
      else require(Uri.parse(folderUri).scheme == "content") { "Ovoz yozuvlari papkasini qayta tanlang" }
    }
    validateOperations(value)
    val recordings = OperatorRecordings(context)
    try {
      if (telegram.optBoolean("enabled")) require(recordings.configured(telegram)) { "Bot tokeni, guruh ID si va yozuvlar papkasini kiriting" }
      if (telegram.optBoolean("sendCallStats")) require(token.isNotBlank() && telegram.optString("statsChatId").isNotBlank()) { "Qo'ng'iroqlar hisoboti uchun bot va alohida guruh ID si kerak" }
      if (telegram.optBoolean("enabled") && telegram.optBoolean("sendCallStats")) require(telegram.optString("chatId") != telegram.optString("statsChatId")) { "Ovoz yozuvlari va qo'ng'iroqlar hisoboti uchun alohida guruhlarni tanlang" }
      recordings.prepareConfiguration(telegram, oldTelegram)
    } finally { recordings.close() }
    value.put("telegram", telegram)
    telegram.remove("_statsBaseline"); telegram.remove("_statsBaselineAt")
    if (telegram.optBoolean("sendCallStats")) {
      val sameStats = oldTelegram != null && oldTelegram.optBoolean("sendCallStats") &&
        oldTelegram.optString("statsChatId") == telegram.optString("statsChatId") && oldTelegram.optString("botToken").substringBefore(':') == token.substringBefore(':') && oldTelegram.optString("_statsBaseline").isNotBlank()
      telegram.put("_statsBaseline", if (sameStats) oldTelegram!!.getString("_statsBaseline") else UUID.randomUUID().toString())
      telegram.put("_statsBaselineAt", if (sameStats) oldTelegram!!.optLong("_statsBaselineAt") else System.currentTimeMillis())
    }
    // Owner link for bot commands: kept across app saves, "new" asks for a fresh one.
    val admin = value.optString("adminInvite")
    if (admin != "new" && !admin.matches(INVITE)) value.put("adminInvite", previous.optString("adminInvite").takeIf { it.matches(INVITE) } ?: "new")
    if (value.optString("adminInvite") == "new") value.put("adminInvite", inviteCode())
    OperatorRuntimeStore.saveConfig(context, value)
    OperatorRuntimeStore.update(context) { state ->
      state.remove("telegramBlocked"); state.remove("telegramError")
      state.put("configRevision", state.optLong("configRevision") + 1)
    }
    value
  }

  /** Read-modify-write on the stored configuration (native edits, never a stale JS copy). */
  fun update(context: Context, change: (JSONObject) -> Unit): JSONObject = synchronized(lock) {
    val current = OperatorRuntimeStore.config(context)
    change(current)
    save(context, current)
  }

  /** Shifts, closed-hours SMS, alert timings and managers; missing manager invite codes are generated here. */
  fun validateOperations(value: JSONObject) {
    value.optJSONArray("shifts")?.let { shifts ->
      require(shifts.length() in 1..6) { "Smenalar soni 1 dan 6 gacha bo'lishi kerak" }
      val indexes = HashSet<Int>()
      for (i in 0 until shifts.length()) {
        val shift = shifts.getJSONObject(i)
        require(OperatorSchedule.parseClock(shift.optString("start")) != null && OperatorSchedule.parseClock(shift.optString("end")) != null) { "Smena vaqti noto'g'ri (masalan 08:00)" }
        require(indexes.add(shift.optInt("index", i + 1)) && shift.optString("name").length <= 40) { "Smena ma'lumotlari noto'g'ri" }
      }
    }
    value.optJSONObject("closedSms")?.let { require(it.optString("text").length <= 300) { "SMS matni 300 belgidan oshmasin" } }
    value.optJSONObject("alerts")?.let {
      require(it.optInt("managerAfterMinutes", 1) in 1..60 && it.optInt("lostAfterMinutes", 5) in 1..240 && it.optInt("smsDailyCap", 50) in 0..500) { "Ogohlantirish vaqtlari noto'g'ri" }
    }
    value.optJSONArray("managers")?.let { managers ->
      require(managers.length() <= 30) { "Ko'pi bilan 30 ta menejer qo'shish mumkin" }
      val ids = HashSet<String>()
      for (i in 0 until managers.length()) {
        val manager = managers.getJSONObject(i)
        require(manager.optString("id").matches(Regex("[A-Za-z0-9_-]{1,64}")) && ids.add(manager.getString("id"))) { "Menejer identifikatori noto'g'ri" }
        require(manager.optString("name").trim().length in 1..60) { "Menejer ismini kiriting" }
        require(manager.optString("phone").isBlank() || OperatorReports.isUzbekMobile(manager.optString("phone"))) { "Menejer raqami noto'g'ri (+998 …)" }
        if (!manager.optString("invite").matches(Regex("[A-Za-z0-9]{16,32}"))) manager.put("invite", inviteCode())
        val schedule = manager.optJSONObject("schedule") ?: JSONObject().put("type", "fixed").put("shift", 1)
        require(schedule.optString("type") in setOf("fixed", "rotating")) { "Menejer jadvali noto'g'ri" }
        if (schedule.optString("type") == "rotating") require(schedule.optLong("anchorWeekStart", 0L) > 0L) { "Almashinuv boshlanadigan haftani tanlang" }
        manager.put("schedule", schedule)
      }
    }
  }

  fun inviteCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    val random = SecureRandom()
    return (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
  }

}
