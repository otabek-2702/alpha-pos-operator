package __PACKAGE__

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A working shift in local time; an end at or before the start finishes on the next day. */
data class OperatorShift(val index: Int, val name: String, val startMinute: Int, val endMinute: Int)

/** One concrete occurrence of a shift. The shift's date is the date it starts on. */
data class OperatorShiftSlot(val shift: OperatorShift, val startAt: Long, val endAt: Long, val date: String) {
  val id: String get() = "$date-${shift.index}"
}

/** Fixed shift, or a weekly rotation (weeks start on Sunday) anchored at [anchorWeekStart] on [anchorShift]. */
data class OperatorManagerSchedule(val fixedShift: Int? = null, val anchorWeekStart: Long? = null, val anchorShift: Int? = null)

/** Pure calendar rules shared by the service and the JVM tests. Uzbekistan has no DST. */
object OperatorSchedule {
  const val DAY_MS = 86_400_000L
  val DEFAULT_SHIFTS = listOf(OperatorShift(1, "1-smena", 8 * 60, 17 * 60), OperatorShift(2, "2-smena", 17 * 60, 2 * 60))

  private fun calendar(tz: TimeZone, at: Long): Calendar = Calendar.getInstance(tz, Locale.ROOT).apply { timeInMillis = at }

  fun startOfDay(at: Long, tz: TimeZone): Long = calendar(tz, at).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
  }.timeInMillis

  fun addDays(at: Long, days: Int, tz: TimeZone): Long = calendar(tz, at).apply { add(Calendar.DAY_OF_YEAR, days) }.timeInMillis

  private fun atMinute(dayStart: Long, minute: Int, tz: TimeZone): Long = calendar(tz, dayStart).apply {
    set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60)
  }.timeInMillis

  /** "HH:mm" → minutes after midnight, or null. */
  fun parseClock(value: String): Int? {
    val match = Regex("([01]?\\d|2[0-3]):([0-5]\\d)").matchEntire(value.trim()) ?: return null
    return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
  }

  fun formatClock(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

  fun slotOn(dayStart: Long, shift: OperatorShift, tz: TimeZone): OperatorShiftSlot {
    val start = atMinute(dayStart, shift.startMinute, tz)
    val endDay = if (shift.endMinute <= shift.startMinute) addDays(dayStart, 1, tz) else dayStart
    return OperatorShiftSlot(shift, start, atMinute(endDay, shift.endMinute, tz), dateTag(start, tz))
  }

  /** The shift covering [at], or null while the cafe is closed. */
  fun slotAt(at: Long, shifts: List<OperatorShift>, tz: TimeZone): OperatorShiftSlot? {
    val today = startOfDay(at, tz)
    for (day in listOf(addDays(today, -1, tz), today)) {
      for (shift in shifts) {
        val slot = slotOn(day, shift, tz)
        if (at >= slot.startAt && at < slot.endAt) return slot
      }
    }
    return null
  }

  /** Shifts that ended in (after, until], oldest first. Long gaps only report the last two days. */
  fun slotsEndedBetween(after: Long, until: Long, shifts: List<OperatorShift>, tz: TimeZone): List<OperatorShiftSlot> {
    if (until <= after || shifts.isEmpty()) return emptyList()
    val from = maxOf(after, until - 2 * DAY_MS)
    val result = ArrayList<OperatorShiftSlot>()
    var day = addDays(startOfDay(from, tz), -1, tz)
    val lastDay = startOfDay(until, tz)
    while (day <= lastDay) {
      for (shift in shifts) {
        val slot = slotOn(day, shift, tz)
        if (slot.endAt > from && slot.endAt <= until) result.add(slot)
      }
      day = addDays(day, 1, tz)
    }
    return result.sortedBy { it.endAt }
  }

  /** The closed gap that contains [at] starts where the previous shift ended. */
  fun closedPeriodStart(at: Long, shifts: List<OperatorShift>, tz: TimeZone): Long =
    slotsEndedBetween(at - 2 * DAY_MS, at, shifts, tz).lastOrNull()?.endAt ?: startOfDay(at, tz)

  /** Start of the closed gap right before [slot] (the "morning report" window), or null when shifts touch. */
  fun closedGapBefore(slot: OperatorShiftSlot, shifts: List<OperatorShift>, tz: TimeZone): LongRange? {
    val previousEnd = slotsEndedBetween(slot.startAt - 2 * DAY_MS, slot.startAt, shifts, tz).lastOrNull()?.endAt ?: return null
    return if (previousEnd < slot.startAt) previousEnd until slot.startAt else null
  }

  fun dateTag(at: Long, tz: TimeZone): String {
    val c = calendar(tz, at)
    return String.format(Locale.ROOT, "%02d%02d%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100)
  }

  fun clock(at: Long, tz: TimeZone): String {
    val c = calendar(tz, at)
    return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
  }

  fun date(at: Long, tz: TimeZone): String {
    val c = calendar(tz, at)
    return String.format(Locale.ROOT, "%02d.%02d.%04d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
  }

  fun hourOf(at: Long, tz: TimeZone): Int = calendar(tz, at).get(Calendar.HOUR_OF_DAY)

  /** Sunday 00:00 of the week containing [at]. */
  fun weekStart(at: Long, tz: TimeZone): Long {
    val day = startOfDay(at, tz)
    return addDays(day, -(calendar(tz, day).get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY), tz)
  }

  /** The shift a manager works in the week of [slot]; null when not scheduled. */
  fun managerShift(schedule: OperatorManagerSchedule, slot: OperatorShiftSlot, shifts: List<OperatorShift>, tz: TimeZone): Int? {
    schedule.fixedShift?.let { return it }
    val anchor = schedule.anchorWeekStart ?: return null
    val anchorIndex = shifts.indexOfFirst { it.index == schedule.anchorShift }
    if (anchorIndex < 0) return null
    val weeks = Math.round((weekStart(slot.startAt, tz) - weekStart(anchor, tz)).toDouble() / (7 * DAY_MS)).toInt()
    return shifts[Math.floorMod(anchorIndex + weeks, shifts.size)].index
  }

  fun isOnDuty(schedule: OperatorManagerSchedule, slot: OperatorShiftSlot, shifts: List<OperatorShift>, tz: TimeZone): Boolean =
    managerShift(schedule, slot, shifts, tz) == slot.shift.index
}
