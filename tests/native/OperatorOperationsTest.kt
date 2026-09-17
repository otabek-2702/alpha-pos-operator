package __PACKAGE__.operations

import __PACKAGE__.*
import java.util.Calendar
import java.util.TimeZone

/** Shift, rotation, call-lifecycle and report rules against the production Kotlin code. */
fun main() {
  var checks = 0
  fun expect(name: String, condition: Boolean) { check(condition) { name }; checks++ }
  val tz = TimeZone.getTimeZone("Asia/Tashkent")
  fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, s: Int = 0): Long =
    Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, h, min, s) }.timeInMillis
  val shifts = OperatorSchedule.DEFAULT_SHIFTS
  val schedule = OperatorSchedule

  // Shifts and closed hours
  expect("morning belongs to shift 1", schedule.slotAt(at(2026, 9, 17, 9), shifts, tz)?.id == "170926-1")
  expect("08:00 opens shift 1", schedule.slotAt(at(2026, 9, 17, 8), shifts, tz)?.shift?.index == 1)
  expect("17:00 belongs to shift 2", schedule.slotAt(at(2026, 9, 17, 17), shifts, tz)?.id == "170926-2")
  expect("after midnight still belongs to the evening shift of the previous date", schedule.slotAt(at(2026, 9, 18, 1, 30), shifts, tz)?.id == "170926-2")
  expect("02:00 is closed", schedule.slotAt(at(2026, 9, 18, 2), shifts, tz) == null)
  expect("03:00 is closed", schedule.slotAt(at(2026, 9, 18, 3), shifts, tz) == null)
  val evening = schedule.slotAt(at(2026, 9, 17, 20), shifts, tz)!!
  expect("evening shift ends at 02:00 next day", evening.endAt == at(2026, 9, 18, 2))
  val ended = schedule.slotsEndedBetween(at(2026, 9, 17, 16, 59), at(2026, 9, 17, 17), shifts, tz)
  expect("shift 1 is reported once at 17:00", ended.map { it.id } == listOf("170926-1"))
  expect("the night check finds the evening shift", schedule.slotsEndedBetween(at(2026, 9, 18, 1), at(2026, 9, 18, 3), shifts, tz).map { it.id } == listOf("170926-2"))
  expect("nothing ends between shifts", schedule.slotsEndedBetween(at(2026, 9, 18, 3), at(2026, 9, 18, 7, 59), shifts, tz).isEmpty())
  expect("a long outage reports at most two days", schedule.slotsEndedBetween(0, at(2026, 9, 18, 3), shifts, tz).size <= 4)
  expect("closed period starts when the evening shift ends", schedule.closedPeriodStart(at(2026, 9, 18, 3), shifts, tz) == at(2026, 9, 18, 2))
  val morning = schedule.slotAt(at(2026, 9, 18, 9), shifts, tz)!!
  expect("the morning report covers 02:00-08:00", schedule.closedGapBefore(morning, shifts, tz) == (at(2026, 9, 18, 2) until at(2026, 9, 18, 8)))
  expect("no gap between touching shifts", schedule.closedGapBefore(evening, shifts, tz) == null)
  expect("date tags are ddmmyy", schedule.dateTag(at(2026, 9, 7, 10), tz) == "070926")
  expect("clock is local", schedule.clock(at(2026, 9, 17, 14, 32), tz) == "14:32")
  expect("clock parsing", schedule.parseClock("8:05") == 485 && schedule.parseClock("24:00") == null && schedule.parseClock("17:60") == null)
  expect("weeks start on Sunday", schedule.weekStart(at(2026, 9, 19, 23), tz) == at(2026, 9, 13, 0))

  // Weekly manager rotation, anchored on shift 1 in the week of Sunday 13 September
  val rotating = OperatorManagerSchedule(anchorWeekStart = at(2026, 9, 15, 12), anchorShift = 1)
  expect("anchor week keeps shift 1", schedule.managerShift(rotating, morning, shifts, tz) == 1)
  val nextSunday = schedule.slotAt(at(2026, 9, 20, 9), shifts, tz)!!
  expect("from the next Sunday the manager works shift 2", schedule.managerShift(rotating, nextSunday, shifts, tz) == 2)
  expect("so Sunday morning is not their shift", !schedule.isOnDuty(rotating, nextSunday, shifts, tz))
  expect("Sunday evening is their shift", schedule.isOnDuty(rotating, schedule.slotAt(at(2026, 9, 20, 18), shifts, tz)!!, shifts, tz))
  expect("a shift starting Saturday evening belongs to the old week", !schedule.isOnDuty(rotating, schedule.slotAt(at(2026, 9, 20, 1), shifts, tz)!!, shifts, tz))
  expect("the previous week was shift 2", schedule.managerShift(rotating, schedule.slotAt(at(2026, 9, 10, 9), shifts, tz)!!, shifts, tz) == 2)
  expect("two weeks later is shift 1 again", schedule.managerShift(rotating, schedule.slotAt(at(2026, 9, 28, 9), shifts, tz)!!, shifts, tz) == 1)
  expect("fixed schedules never rotate", schedule.isOnDuty(OperatorManagerSchedule(fixedShift = 2), evening, shifts, tz))
  expect("unscheduled managers are never on duty", !schedule.isOnDuty(OperatorManagerSchedule(), evening, shifts, tz))

  // Call lifecycle
  val r = OperatorReports
  val started = at(2026, 9, 17, 14, 32)
  val missed = OperatorCallView(id = "c1", phone = "+998901234567", direction = "in", outcome = "missed", startedAt = started,
    ringDurationSeconds = 18, customerName = "Aziz <Karimov>", shiftIndex = 1)
  val missedAt = started + 18_000
  expect("missed call waits", r.status(missed) == OperatorCallStatus.WAITING)
  expect("no manager alert before a minute", !r.needsManagerAlert(missed, missedAt + 59_999))
  expect("manager alert after a minute", r.needsManagerAlert(missed, missedAt + 60_000))
  expect("a callback attempt cancels the manager alert", !r.needsManagerAlert(missed.copy(callbackAttempts = 1, firstCallbackAt = missedAt + 20_000), missedAt + 60_000))
  expect("the manager alert is sent once", !r.needsManagerAlert(missed.copy(managerAlertAt = missedAt + 60_000), missedAt + 120_000))
  expect("not lost before five minutes", !r.becomesLost(missed, missedAt + 299_999))
  expect("lost after five minutes", r.becomesLost(missed, missedAt + 300_000))
  expect("closed-hours calls are never lost", !r.becomesLost(missed.copy(closed = true), missedAt + 900_000) && r.status(missed.copy(closed = true)) == OperatorCallStatus.CLOSED)
  expect("closed-hours calls never alert managers", !r.needsManagerAlert(missed.copy(closed = true), missedAt + 900_000))
  val lost = missed.copy(lostAt = missedAt + 300_000)
  expect("lost status", r.status(lost) == OperatorCallStatus.LOST)
  expect("a later conversation resolves even a lost call", r.status(lost.copy(resolvedAt = missedAt + 600_000, resolvedBy = "client")) == OperatorCallStatus.RESOLVED)
  expect("unanswered callback status", r.status(missed.copy(callbackAttempts = 2, firstCallbackAt = missedAt + 30_000)) == OperatorCallStatus.CALLED_BACK_NO_ANSWER)
  expect("rejected counts as missed", r.status(missed.copy(outcome = "rejected")) == OperatorCallStatus.WAITING)
  expect("blocked status", r.status(missed.copy(outcome = "blocked")) == OperatorCallStatus.BLOCKED)
  expect("answered status", r.status(missed.copy(outcome = "answered")) == OperatorCallStatus.ANSWERED)
  expect("outgoing status", r.status(missed.copy(direction = "out", outcome = "unconfirmed")) == OperatorCallStatus.OUTGOING)
  expect("two missed calls today are urgent", r.isUrgent(missed.copy(missedToday = 2)) && !r.isUrgent(missed.copy(missedToday = 1)))

  // Texts
  val waitingText = r.callHtml(missed.copy(busyWith = "998931112233", callIndexToday = 2, missedToday = 2), tz)
  expect("waiting text", waitingText.startsWith("🟡 <b>O‘tkazib yuborildi</b> · 14:32") && "Qayta qo‘ng‘iroq kutilmoqda" in waitingText)
  expect("names are escaped", "Aziz &lt;Karimov&gt;" in waitingText && "<Karimov>" !in waitingText)
  expect("phones are readable", "+998 90 123 45 67" in waitingText && "Operator band edi: +998 93 111 22 33" in waitingText)
  expect("urgent repeat caller", "🔴 Bugun 2-qo‘ng‘iroq · 2 marta javobsiz" in waitingText)
  expect("status and dated hashtags", "#otkazib #otkazib170926 #kun170926 #smena1" in waitingText)
  val lostText = r.callHtml(lost.copy(managerAlertAt = missedAt + 60_000), tz)
  expect("lost text", lostText.startsWith("🔴 <b>YO‘QOTILGAN MIJOZ</b>") && "5 daqiqa ichida bog‘lanilmadi" in lostText && "#yoqotilgan170926" in lostText)
  expect("manager alert is noted", "Menejerlarga xabar berildi 14:33" in lostText)
  val resolvedText = r.callHtml(lost.copy(resolvedAt = missedAt + 8 * 60_000, resolvedBy = "client", resolvedTalkSeconds = 130), tz)
  expect("client recall text", "Mijozning o‘zi qayta qo‘ng‘iroq qildi 14:40 (8 daq keyin) · suhbat 2 daq 10 s · kechikib" in resolvedText && "#hal_qilindi" in resolvedText)
  val operatorText = r.callHtml(missed.copy(resolvedAt = missedAt + 90_000, resolvedBy = "operator"), tz)
  expect("operator callback text", "Operator qayta qo‘ng‘iroq qildi" in operatorText && "kechikib" !in operatorText)
  expect("closed text with SMS", "Kafe yopiq edi" in r.callHtml(missed.copy(closed = true, smsSentAt = at(2026, 9, 17, 14, 33)), tz) &&
    "SMS yuborildi 14:33" in r.callHtml(missed.copy(closed = true, smsSentAt = at(2026, 9, 17, 14, 33)), tz))
  expect("blocked text", "Telefon jiringlamadi" in r.callHtml(missed.copy(outcome = "blocked"), tz))
  val answered = r.callHtml(missed.copy(outcome = "answered", ringSeconds = 6, talkSeconds = 192, orderId = "38092"), tz)
  expect("answered text", "🟢 <b>Qabul qilindi</b>" in answered && "Javob: 6 s · Suhbat: 3 daq 12 s" in answered && "Buyurtma: #38092" in answered)
  expect("unknown timing is marked", "Aniq vaqt kuzatilmagan" in r.callHtml(missed.copy(timingObserved = false), tz))
  expect("hidden numbers", r.phone("") == "Yashirin raqam" && r.smsPhone("") == "yashirin raqam")
  expect("local numbers gain the country code", r.phone("901234567") == "+998 90 123 45 67")
  expect("durations", r.duration(59) == "59 s" && r.duration(120) == "2 daq" && r.duration(3660) == "1 soat 1 daq")
  expect("SMS text is GSM-7", r.managerAlertSms(missed, tz).all { it.code < 128 } && "+998901234567 (Aziz <Karimov>), 14:32" in r.managerAlertSms(missed, tz))
  expect("Uzbek mobile detection", r.isUzbekMobile("+998 90 123 45 67") && !r.isUzbekMobile("+998712000000") && !r.isUzbekMobile("") && !r.isUzbekMobile("+7 901 234 56 78"))
  expect("lost alert", "🔴 <b>Yo‘qotilgan mijoz</b>" in r.lostAlertHtml(missed, tz) && "#ogohlantirish" in r.lostAlertHtml(missed, tz))

  val summary = OperatorShiftSummary(shiftName = "1-smena", shiftIndex = 1, startAt = at(2026, 9, 17, 8), endAt = at(2026, 9, 17, 17),
    total = 84, answered = 70, avgAnswerSeconds = 5, resolved = 6, avgResolveSeconds = 420, waiting = 1,
    lost = listOf(OperatorLostEntry("998901234567", "Aziz", started, "https://t.me/c/123/45"), OperatorLostEntry("998931112233", null, started, null)),
    outgoing = 12, outgoingTalked = 9, blocked = 1, talkSeconds = 11_520, perHour = mapOf(12 to 14, 13 to 11, 19 to 9, 9 to 1),
    orders = 40, serviceUptimePercent = 100, posUptimePercent = 98, closedCalls = 4, closedMissed = 3, closedSms = 3)
  val report = r.shiftReportHtml(summary, tz)
  expect("report header", report.startsWith("📊 <b>1-smena hisoboti</b> · 17.09.2026 · 08:00–17:00"))
  expect("report links lost clients", "<a href=\"https://t.me/c/123/45\">+998 90 123 45 67 (Aziz)</a> · 14:32" in report && "  • +998 93 111 22 33 · 14:32" in report)
  expect("report busiest hours", "Eng band soatlar: 12:00 (14), 13:00 (11), 19:00 (9)" in report)
  expect("report totals", "Jami suhbat: 3 soat 12 daq" in report && "Xizmat: 100% · POS ulanishi: 98%" in report && "Yopiq vaqtda: 4 qo‘ng‘iroq (3 javobsiz, 3 SMS)" in report)
  expect("report hashtags", "#hisobot #hisobot170926 #yoqotilgan #yoqotilgan170926 #kun170926 #smena1" in report)
  val caption = r.recordingCaptionHtml("Qo‘ng‘iroqni yozib olish +998901234567_260917_143210.m4a", started, missed.copy(outcome = "answered", talkSeconds = 65), tz)
  expect("recording caption", "Kiruvchi · suhbat 1 daq 5 s" in caption && "#ovoz170926" in caption && caption.length <= 1000)
  expect("POS health texts", "🔴 <b>POS uzildi</b>" in r.posHealthHtml("Kassa", started, null, tz) && "(18 daq)" in r.posHealthHtml("Kassa", started, started + 18 * 60_000, tz))
  val (recordedPhone, recordedAt) = r.parseRecordingName("Qo‘ng‘iroqni yozib olish +998335569975_260917_095857.m4a", tz)
  expect("Samsung recording names give number and time", recordedPhone == "+998335569975" && recordedAt == at(2026, 9, 17, 9, 58, 57))
  val (namedPhone, namedAt) = r.parseRecordingName("Qo‘ng‘iroqni yozib olish Aziz aka_260917_101500.m4a", tz)
  expect("contact-named recordings still give the time", namedPhone == null && namedAt == at(2026, 9, 17, 10, 15))
  expect("unrelated names give nothing", r.parseRecordingName("voice.m4a", tz) == Pair<String?, Long?>(null, null))
  println("$checks operator schedule and report checks passed")
}
