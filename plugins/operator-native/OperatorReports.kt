package __PACKAGE__

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

enum class OperatorCallStatus { ANSWERED, WAITING, CALLED_BACK_NO_ANSWER, RESOLVED, LOST, CLOSED, OUTGOING, BLOCKED, OTHER }

/** Everything a Telegram report needs about one call; built from the native call ledger. */
data class OperatorCallView(
  val id: String,
  val phone: String,
  val direction: String,
  val outcome: String,
  val startedAt: Long,
  val customerName: String? = null,
  val endedAt: Long? = null,
  val ringSeconds: Long? = null,
  val ringDurationSeconds: Long? = null,
  val talkSeconds: Long = 0,
  val timingObserved: Boolean = true,
  val closed: Boolean = false,
  val shiftIndex: Int? = null,
  val busyWith: String? = null,
  val missedWhileBusy: Boolean? = null,
  val callbackAttempts: Int = 0,
  val firstCallbackAt: Long? = null,
  val resolvedBy: String? = null,
  val resolvedAt: Long? = null,
  val resolvedTalkSeconds: Long? = null,
  val lostAt: Long? = null,
  val managerAlertAt: Long? = null,
  val callIndexToday: Int = 1,
  val missedToday: Int = 0,
  val orderId: String? = null,
  val smsSentAt: Long? = null,
  val callbackForAt: Long? = null,
  /** Name saved in the operator phone's contacts, preferred over the POS customer name. */
  val contactName: String? = null,
)

data class OperatorLostEntry(val phone: String, val name: String?, val at: Long, val link: String?)

data class OperatorShiftSummary(
  val shiftName: String,
  val shiftIndex: Int,
  val startAt: Long,
  val endAt: Long,
  val total: Int,
  val answered: Int,
  val avgAnswerSeconds: Long?,
  val resolved: Int,
  val avgResolveSeconds: Long?,
  val waiting: Int,
  val lost: List<OperatorLostEntry>,
  val outgoing: Int,
  val outgoingTalked: Int,
  val blocked: Int,
  val talkSeconds: Long,
  val perHour: Map<Int, Int>,
  val orders: Int,
  val serviceUptimePercent: Int?,
  val posUptimePercent: Int?,
  val closedCalls: Int = 0,
  val closedMissed: Int = 0,
  val closedSms: Int = 0,
)

/** Pure Uzbek report texts (Telegram HTML) and lifecycle decisions. Only status circles carry colour. */
object OperatorReports {
  const val MANAGER_ALERT_MS = 2 * 60_000L
  const val LOST_MS = 5 * 60_000L

  fun isMissed(call: OperatorCallView) = call.outcome == "missed" || call.outcome == "rejected"

  /** When the caller gave up (or the operator declined). */
  fun missedAt(call: OperatorCallView): Long = call.endedAt ?: (call.startedAt + (call.ringDurationSeconds ?: 0L) * 1000L)

  fun status(call: OperatorCallView): OperatorCallStatus = when {
    call.outcome == "blocked" -> OperatorCallStatus.BLOCKED
    isMissed(call) && call.closed -> OperatorCallStatus.CLOSED
    isMissed(call) && call.resolvedAt != null -> OperatorCallStatus.RESOLVED
    isMissed(call) && call.lostAt != null -> OperatorCallStatus.LOST
    isMissed(call) && call.callbackAttempts > 0 -> OperatorCallStatus.CALLED_BACK_NO_ANSWER
    isMissed(call) -> OperatorCallStatus.WAITING
    call.direction == "out" -> OperatorCallStatus.OUTGOING
    call.outcome == "answered" -> OperatorCallStatus.ANSWERED
    else -> OperatorCallStatus.OTHER
  }

  /**
   * "Not called back in time": no callback attempt and no conversation yet. Time counts from
   * [quietSince] — the moment the phone became free after the missed call (the operator may
   * have been talking to another client).
   */
  fun needsManagerAlert(call: OperatorCallView, now: Long, delayMs: Long = MANAGER_ALERT_MS, quietSince: Long = 0L): Boolean =
    isMissed(call) && !call.closed && call.resolvedAt == null && call.lostAt == null && call.callbackAttempts == 0 &&
      call.managerAlertAt == null && now - maxOf(missedAt(call), quietSince) >= delayMs

  fun becomesLost(call: OperatorCallView, now: Long, lostMs: Long = LOST_MS, quietSince: Long = 0L): Boolean =
    isMissed(call) && !call.closed && call.resolvedAt == null && call.lostAt == null && now - maxOf(missedAt(call), quietSince) >= lostMs

  fun isUrgent(call: OperatorCallView) = call.missedToday >= 2

  fun esc(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private fun digits(raw: String): String = raw.filter { it.isDigit() }.let { if (it.startsWith("00")) it.drop(2) else it }
    .let { if (it.length == 9) "998$it" else it }

  /** "+998 90 123 45 67"; Telegram makes it tappable. */
  fun phone(raw: String): String {
    val d = digits(raw)
    if (d.isEmpty()) return "Yashirin raqam"
    if (d.length == 12 && d.startsWith("998")) return "+998 ${d.substring(3, 5)} ${d.substring(5, 8)} ${d.substring(8, 10)} ${d.substring(10)}"
    return if (raw.trim().startsWith("+")) "+$d" else d
  }

  fun smsPhone(raw: String): String = digits(raw).let { if (it.isEmpty()) "yashirin raqam" else "+$it" }

  /** "+998901234567" without spaces, or null for a hidden number. */
  fun compactPhone(raw: String?): String? = raw?.let { digits(it) }?.takeIf { it.isNotEmpty() }?.let { "+$it" }

  /** SMS text in GSM-7: Uzbek apostrophes become ASCII so one SMS holds 160 characters. */
  fun smsSafe(text: String): String = text.replace('‘', '\'').replace('’', '\'').replace('ʻ', '\'').replace('ʼ', '\'')
    .replace('“', '"').replace('”', '"').replace('—', '-').replace('–', '-')

  /** Uzbek mobile operator codes; landline area codes (71, 65, 66, …) never receive SMS. */
  private val MOBILE_CODES = setOf("20", "33", "50", "55", "77", "88", "90", "91", "93", "94", "95", "97", "98", "99")

  fun isUzbekMobile(raw: String): Boolean = digits(raw).let { it.length == 12 && it.startsWith("998") && it.substring(3, 5) in MOBILE_CODES }

  fun duration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    return when {
      s < 60 -> "$s s"
      s < 3600 -> if (s % 60 == 0L) "${s / 60} daq" else "${s / 60} daq ${s % 60} s"
      else -> if ((s / 60) % 60 == 0L) "${s / 3600} soat" else "${s / 3600} soat ${(s / 60) % 60} daq"
    }
  }

  fun displayName(call: OperatorCallView): String? =
    (call.contactName?.takeIf { it.isNotBlank() } ?: call.customerName?.takeIf { it.isNotBlank() })?.trim()?.take(80)

  private fun who(call: OperatorCallView): String {
    val number = esc(phone(call.phone))
    val name = displayName(call)?.let { esc(it) }
    return if (name != null) "<b>$name</b> · $number" else number
  }

  fun tag(status: OperatorCallStatus): String = when (status) {
    OperatorCallStatus.ANSWERED -> "qabul"
    OperatorCallStatus.WAITING, OperatorCallStatus.CALLED_BACK_NO_ANSWER -> "otkazib"
    OperatorCallStatus.RESOLVED -> "hal_qilindi"
    OperatorCallStatus.LOST -> "yoqotilgan"
    OperatorCallStatus.CLOSED -> "yopiq"
    OperatorCallStatus.OUTGOING -> "chiquvchi"
    OperatorCallStatus.BLOCKED -> "bloklangan"
    OperatorCallStatus.OTHER -> "qongiroq"
  }

  /** "G‘ayrat Karimov" -> "#Gayrat_Karimov": a searchable tag for a person. */
  fun personTag(name: String): String? {
    val clean = name.replace(Regex("[‘’ʻʼ'`]"), "").trim()
      .replace(Regex("""[^\p{L}\p{N}]+"""), "_").trim('_').take(40)
    if (clean.isEmpty()) return null
    return "#" + if (clean.first().isDigit()) "m_$clean" else clean
  }

  /** Dated status tags only ("#qabul170926"), then the shift and the people on duty. */
  fun tags(names: List<String>, at: Long, tz: TimeZone, shiftIndex: Int?, people: List<String> = emptyList()): String {
    val day = OperatorSchedule.dateTag(at, tz)
    val result = LinkedHashSet<String>()
    for (name in names) result.add("#$name$day")
    shiftIndex?.let { result.add("#smena$it") }
    people.mapNotNullTo(result) { personTag(it) }
    return result.joinToString(" ")
  }

  fun callHtml(call: OperatorCallView, tz: TimeZone, lostMs: Long = LOST_MS, managers: List<String> = emptyList()): String {
    val status = status(call)
    val time = OperatorSchedule.clock(call.startedAt, tz)
    val lines = ArrayList<String>()
    lines.add(when (status) {
      OperatorCallStatus.ANSWERED -> "🟢 <b>Qabul qilindi</b> · $time"
      OperatorCallStatus.WAITING -> "🟡 <b>O‘tkazib yuborildi</b> · $time"
      OperatorCallStatus.CALLED_BACK_NO_ANSWER -> "🟡 <b>Qayta terildi, javob yo‘q</b> · $time"
      OperatorCallStatus.RESOLVED -> "🟢 <b>Hal qilindi</b> · javobsiz qo‘ng‘iroq $time"
      OperatorCallStatus.LOST -> "🔴 <b>YO‘QOTILGAN MIJOZ</b> · $time"
      OperatorCallStatus.CLOSED -> "⚪ <b>Kafe yopiq edi</b> · $time"
      OperatorCallStatus.OUTGOING -> "⚪ <b>Chiquvchi</b> · $time"
      OperatorCallStatus.BLOCKED -> "⚫ <b>Bloklangan raqam</b> · $time"
      OperatorCallStatus.OTHER -> "⚪ <b>Qo‘ng‘iroq</b> · $time"
    })
    lines.add(who(call))
    val missed = isMissed(call)
    val facts = ArrayList<String>()
    if (status == OperatorCallStatus.ANSWERED) {
      call.ringSeconds?.let { facts.add("Javob: ${duration(it)}") }
      facts.add("Suhbat: ${duration(call.talkSeconds)}")
    }
    if (missed) {
      if (call.outcome == "rejected") facts.add("Operator rad etdi")
      call.ringDurationSeconds?.let { facts.add("Jiringladi ${duration(it)}") }
      when {
        !call.busyWith.isNullOrBlank() -> facts.add("Operator band edi: ${esc(phone(call.busyWith))}")
        call.missedWhileBusy == true -> facts.add("Operator boshqa qo‘ng‘iroqda edi")
      }
    }
    if (facts.isNotEmpty()) lines.add(facts.joinToString(" · "))
    if (call.callIndexToday > 1 && call.direction == "in") {
      lines.add(if (isUrgent(call) && missed) "🔴 Bugun ${call.callIndexToday}-qo‘ng‘iroq · ${call.missedToday} marta javobsiz"
        else "Bugun ${call.callIndexToday}-qo‘ng‘iroq")
    }
    val attempts = if (call.callbackAttempts > 0 && call.firstCallbackAt != null)
      "qayta terildi ${call.callbackAttempts} marta (birinchisi ${OperatorSchedule.clock(call.firstCallbackAt, tz)}, ${duration((call.firstCallbackAt - missedAt(call)) / 1000)} keyin)" else null
    when (status) {
      OperatorCallStatus.WAITING -> lines.add("Qayta qo‘ng‘iroq kutilmoqda")
      OperatorCallStatus.CALLED_BACK_NO_ANSWER -> lines.add("${attempts?.replaceFirstChar { it.uppercase() } ?: "Qayta terildi"} · javob yo‘q")
      OperatorCallStatus.RESOLVED -> {
        val at = call.resolvedAt!!
        val by = if (call.resolvedBy == "client") "Mijozning o‘zi qayta qo‘ng‘iroq qildi" else "Operator qayta qo‘ng‘iroq qildi"
        val talk = call.resolvedTalkSeconds?.let { " · suhbat ${duration(it)}" } ?: ""
        val late = if (call.lostAt != null) " · kechikib" else ""
        lines.add("$by ${OperatorSchedule.clock(at, tz)} (${duration((at - missedAt(call)) / 1000)} keyin)$talk$late")
      }
      OperatorCallStatus.LOST -> lines.add("${lostMs / 60_000} daqiqa ichida bog‘lanilmadi" + (attempts?.let { " · $it, javob yo‘q" } ?: ""))
      OperatorCallStatus.CLOSED -> lines.add("Javobsiz" + (call.smsSentAt?.let { " · SMS yuborildi ${OperatorSchedule.clock(it, tz)}" } ?: ""))
      OperatorCallStatus.OUTGOING -> {
        lines.add(if (call.outcome == "answered") "Suhbat: ${duration(call.talkSeconds)}" else "Javob bermadi")
        call.callbackForAt?.let { lines.add("${OperatorSchedule.clock(it, tz)} dagi javobsiz qo‘ng‘iroqqa qayta qo‘ng‘iroq") }
      }
      OperatorCallStatus.BLOCKED -> lines.add("Telefon jiringlamadi")
      else -> Unit
    }
    call.managerAlertAt?.takeIf { missed }?.let { lines.add("Menejerlarga xabar berildi ${OperatorSchedule.clock(it, tz)}") }
    call.orderId?.let { lines.add("Buyurtma: #${esc(it.take(40))}") }
    if (!call.timingObserved) lines.add("<i>Aniq vaqt kuzatilmagan</i>")
    lines.add(tags(listOf(tag(status)), call.startedAt, tz, call.shiftIndex.takeIf { !call.closed }, managers))
    return lines.joinToString("\n").take(4000)
  }

  fun shortStatus(status: OperatorCallStatus): String = when (status) {
    OperatorCallStatus.ANSWERED -> "🟢 Qabul qilindi"
    OperatorCallStatus.WAITING -> "🟡 O‘tkazib yuborildi"
    OperatorCallStatus.CALLED_BACK_NO_ANSWER -> "🟡 Qayta terildi, javob yo‘q"
    OperatorCallStatus.RESOLVED -> "🟢 Hal qilindi"
    OperatorCallStatus.LOST -> "🔴 Yo‘qotilgan"
    OperatorCallStatus.CLOSED -> "⚪ Kafe yopiq edi"
    OperatorCallStatus.OUTGOING -> "⚪ Chiquvchi"
    OperatorCallStatus.BLOCKED -> "⚫ Bloklangan"
    OperatorCallStatus.OTHER -> "⚪ Qo‘ng‘iroq"
  }

  private val STAMP = Regex("""(?<!\d)(\d{2})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})(?!\d)""")
  private val RECORDING_PREFIXES = listOf("Qo‘ng‘iroqni yozib olish", "Qo'ng'iroqni yozib olish", "Call recording", "Запись вызова", "Запись разговора")

  private fun withoutPrefix(text: String): String {
    var result = text.trim()
    for (prefix in RECORDING_PREFIXES) if (result.startsWith(prefix, ignoreCase = true)) result = result.substring(prefix.length)
    return result.trim().trim('_', '-', ' ')
  }

  /** Samsung names recordings "… +998901234567_260917_095857.m4a" (number or contact name, yymmdd_hhmmss). */
  fun parseRecordingName(name: String, tz: TimeZone): Pair<String?, Long?> {
    val stamp = STAMP.findAll(name).lastOrNull()
    val at = stamp?.groupValues?.let { g ->
      val (yy, mo, dd, hh, mi, ss) = g.drop(1).map { it.toInt() }
      if (mo !in 1..12 || dd !in 1..31 || hh > 23 || mi > 59 || ss > 59) null
      else Calendar.getInstance(tz, Locale.ROOT).apply { clear(); set(2000 + yy, mo - 1, dd, hh, mi, ss) }.timeInMillis
    }
    val before = if (stamp != null) name.substring(0, stamp.range.first) else name
    val phone = Regex("""\+?\d{9,15}""").findAll(before).lastOrNull()?.value
    return Pair(phone, at)
  }

  private operator fun <T> List<T>.component6(): T = this[5]

  /** The contact name Samsung put into the file name ("Aziz aka"), when there is no number. */
  fun recordingLabel(name: String): String? {
    val base = name.substringBeforeLast('.')
    val stamp = STAMP.findAll(base).lastOrNull()
    val label = withoutPrefix(if (stamp != null) base.substring(0, stamp.range.first) else base)
    return label.takeIf { it.isNotEmpty() && Regex("""\+?\d{9,15}""").find(it) == null }
  }

  /** "Qo‘ng‘iroqni yozib olish +998901234567_260917_153114.m4a" -> "+998901234567_260917_153114.m4a". */
  fun recordingFileName(original: String, phone: String?): String {
    val ext = original.substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }?.let { ".${it.lowercase()}" } ?: ""
    val base = if (ext.isEmpty()) original else original.substringBeforeLast('.')
    val stamp = STAMP.findAll(base).lastOrNull()
    val label = compactPhone(phone) ?: withoutPrefix(if (stamp != null) base.substring(0, stamp.range.first) else base)
    val safe = label.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").take(60)
    val name = listOfNotNull(safe.takeIf { it.isNotEmpty() }, stamp?.value).joinToString("_")
    return (name.ifEmpty { "ovoz" } + ext).take(100)
  }

  /** Telegram plays MP3 and M4A inline when they are sent as audio. */
  fun isPlayableAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("m4a", "mp3")

  fun managerAlertHtml(call: OperatorCallView, tz: TimeZone, minutes: Long = MANAGER_ALERT_MS / 60_000, managers: List<String> = emptyList()): String = listOf(
    "🟡 <b>Javobsiz qo‘ng‘iroq</b> · ${OperatorSchedule.clock(call.startedAt, tz)}",
    who(call),
    "Operator bo‘sh bo‘lgach $minutes daqiqa ichida qayta qo‘ng‘iroq qilinmadi.",
    tags(listOf("ogohlantirish"), call.startedAt, tz, call.shiftIndex, managers),
  ).joinToString("\n")

  fun managerAlertSms(call: OperatorCallView, tz: TimeZone, minutes: Long = MANAGER_ALERT_MS / 60_000): String = smsSafe(
    "Smart Food: javobsiz qo‘ng‘iroq ${smsPhone(call.phone)}" +
      (displayName(call)?.let { " (${it.take(40)})" } ?: "") +
      ", ${OperatorSchedule.clock(call.startedAt, tz)}. $minutes daqiqa ichida qayta qo‘ng‘iroq qilinmadi.")

  fun lostAlertHtml(call: OperatorCallView, tz: TimeZone, lostMs: Long = LOST_MS, managers: List<String> = emptyList()): String = listOf(
    "🔴 <b>Yo‘qotilgan mijoz</b>: ${who(call)} · ${OperatorSchedule.clock(call.startedAt, tz)}",
    "${lostMs / 60_000} daqiqa ichida bog‘lanilmadi.",
    tags(listOf("yoqotilgan"), call.startedAt, tz, call.shiftIndex, managers),
  ).joinToString("\n")

  fun shiftReportHtml(s: OperatorShiftSummary, tz: TimeZone): String {
    val lines = ArrayList<String>()
    lines.add("📊 <b>${esc(s.shiftName)} hisoboti</b> · ${OperatorSchedule.date(s.startAt, tz)} · ${OperatorSchedule.clock(s.startAt, tz)}–${OperatorSchedule.clock(s.endAt, tz)}")
    lines.add("Jami: <b>${s.total}</b> qo‘ng‘iroq")
    lines.add("🟢 Qabul qilingan: ${s.answered}" + (s.avgAnswerSeconds?.let { " · o‘rtacha javob ${duration(it)}" } ?: ""))
    lines.add("🟢 Qayta bog‘lanilgan: ${s.resolved}" + (s.avgResolveSeconds?.let { " · o‘rtacha ${duration(it)} keyin" } ?: ""))
    if (s.waiting > 0) lines.add("🟡 Kutilmoqda: ${s.waiting}")
    lines.add("🔴 Yo‘qotilgan mijozlar: ${s.lost.size}")
    for (entry in s.lost.take(30)) {
      val label = esc(phone(entry.phone)) + (entry.name?.takeIf { it.isNotBlank() }?.let { " (${esc(it.take(40))})" } ?: "")
      val linked = entry.link?.let { "<a href=\"${esc(it)}\">$label</a>" } ?: label
      lines.add("  • $linked · ${OperatorSchedule.clock(entry.at, tz)}")
    }
    if (s.lost.size > 30) lines.add("  … yana ${s.lost.size - 30} ta")
    lines.add("⚪ Chiquvchi: ${s.outgoing} (suhbat bo‘lgan ${s.outgoingTalked}) · ⚫ Bloklangan: ${s.blocked}")
    lines.add("Buyurtma bilan tugagan: ${s.orders}")
    lines.add("Jami suhbat: ${duration(s.talkSeconds)}")
    val busiest = s.perHour.entries.filter { it.value > 0 }.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).take(3)
    if (busiest.isNotEmpty()) lines.add("Eng band soatlar: " + busiest.joinToString(", ") { String.format(Locale.ROOT, "%02d:00 (%d)", it.key, it.value) })
    val uptime = listOfNotNull(s.serviceUptimePercent?.let { "Xizmat: $it%" }, s.posUptimePercent?.let { "POS ulanishi: $it%" })
    if (uptime.isNotEmpty()) lines.add(uptime.joinToString(" · "))
    if (s.closedCalls > 0) lines.add("⚪ Yopiq vaqtda: ${s.closedCalls} qo‘ng‘iroq (${s.closedMissed} javobsiz, ${s.closedSms} SMS)")
    val names = mutableListOf("hisobot")
    if (s.lost.isNotEmpty()) names.add("yoqotilgan")
    lines.add(tags(names, s.startAt, tz, s.shiftIndex))
    return lines.joinToString("\n").take(4000)
  }

  /**
   * Audio caption: managers on duty, direction/time/length, one dated tag, and the caller
   * (number without spaces, with the contact or customer name) at the bottom.
   */
  fun recordingCaptionHtml(fileName: String, recordedAt: Long, call: OperatorCallView?, tz: TimeZone,
    managers: List<String> = emptyList(), filePhone: String? = null): String {
    val lines = ArrayList<String>()
    if (managers.isNotEmpty()) lines.add("👔 Menejer: " + managers.joinToString(", ") { esc(it.trim().take(40)) })
    val at = call?.startedAt ?: recordedAt
    val icon = when (call?.direction) { "out" -> "📤"; "in" -> "📥"; else -> "🎙" }
    val facts = mutableListOf("$icon ${OperatorSchedule.clock(at, tz)}")
    if (call != null && call.talkSeconds > 0) facts.add(duration(call.talkSeconds))
    call?.orderId?.let { facts.add("🧾 #${esc(it.take(40))}") }
    lines.add(facts.joinToString(" · "))
    lines.add(tags(listOf("ovoz"), at, tz, null))
    val number = compactPhone(call?.phone) ?: compactPhone(filePhone)
    val name = call?.let { displayName(it) } ?: recordingLabel(fileName)
    lines.add(listOfNotNull(number, name?.let { "<b>${esc(it.take(60))}</b>" }).joinToString(" · ").ifEmpty { "Yashirin raqam" })
    return lines.joinToString("\n").take(1000)
  }

  /** Title and performer shown by Telegram's audio player. */
  fun recordingPlayerTitle(fileName: String, recordedAt: Long, call: OperatorCallView?, tz: TimeZone, filePhone: String? = null): Pair<String, String> {
    val number = compactPhone(call?.phone) ?: compactPhone(filePhone)
    val name = call?.let { displayName(it) } ?: recordingLabel(fileName)
    val icon = when (call?.direction) { "out" -> "📤"; "in" -> "📥"; else -> "🎙" }
    val title = (name ?: number ?: "Yashirin raqam").take(60)
    val performer = listOfNotNull(number.takeIf { name != null }, "$icon ${OperatorSchedule.clock(call?.startedAt ?: recordedAt, tz)}").joinToString(" · ")
    return title to performer
  }

  fun posHealthHtml(name: String, since: Long, restoredAt: Long?, tz: TimeZone): String {
    val first = if (restoredAt == null) "🔴 <b>POS uzildi</b>: ${esc(name)} · ${OperatorSchedule.clock(since, tz)} dan beri"
      else "🟢 <b>POS qayta ulandi</b>: ${esc(name)} · ${OperatorSchedule.clock(since, tz)}–${OperatorSchedule.clock(restoredAt, tz)} (${duration((restoredAt - since) / 1000)})"
    return first + "\n" + tags(listOf("ogohlantirish"), since, tz, null)
  }

  fun internetOutageHtml(from: Long, to: Long, tz: TimeZone): String =
    "🟡 <b>Internet uzilgan edi</b>: ${OperatorSchedule.clock(from, tz)}–${OperatorSchedule.clock(to, tz)} (${duration((to - from) / 1000)}). Yig‘ilgan ma’lumotlar yuborilmoqda.\n" +
      tags(listOf("ogohlantirish"), from, tz, null)

  fun batteryHtml(level: Int, charging: Boolean, low: Boolean, at: Long, tz: TimeZone): String {
    val first = if (low) "🔴 <b>Telefon quvvati kam</b>: $level%" + (if (charging) " · quvvatlanmoqda" else " · quvvatlanmayapti")
      else "🟡 <b>Quvvatlash to‘xtadi</b>: $level%. Telefonni quvvatga ulang."
    return first + "\n" + tags(listOf("ogohlantirish"), at, tz, null)
  }

  fun missingAudioHtml(call: OperatorCallView, tz: TimeZone): String = listOf(
    "🟡 <b>Ovoz yozuvi kelmadi</b> · ${OperatorSchedule.clock(call.startedAt, tz)}",
    "${who(call)} · suhbat ${duration(call.talkSeconds)}",
    "Samsung Telefon ilovasida qo‘ng‘iroqlarni avtomatik yozib olish yoqilganini tekshiring.",
    tags(listOf("ogohlantirish"), call.startedAt, tz, call.shiftIndex),
  ).joinToString("\n")
}
