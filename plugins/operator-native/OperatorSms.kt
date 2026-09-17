package __PACKAGE__

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.TimeZone

/** SMS from the operator SIM: each key is sent at most once and a daily cap protects the balance. */
class OperatorSms(private val context: Context) {
  private val prefs = context.getSharedPreferences("operator_sms_v1", Context.MODE_PRIVATE)

  fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

  fun sentToday(tz: TimeZone = TimeZone.getDefault()): Int {
    val today = OperatorSchedule.dateTag(System.currentTimeMillis(), tz)
    return if (prefs.getString("day", "") == today) prefs.getInt("count", 0) else 0
  }

  fun wasSent(key: String): Boolean = sentKeys().has(key)

  private fun sentKeys(): JSONObject = try { JSONObject(prefs.getString("keys", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

  /** False when not permitted, already sent, over the cap, or the phone rejected it. */
  @Synchronized fun send(key: String, phone: String, text: String, dailyCap: Int, tz: TimeZone = TimeZone.getDefault()): Boolean {
    if (!hasPermission() || phone.isBlank() || text.isBlank()) return false
    val keys = sentKeys()
    if (keys.has(key)) return false
    val now = System.currentTimeMillis()
    if (sentToday(tz) >= dailyCap) {
      prefs.edit().putString("error", "Kunlik SMS chegarasi ($dailyCap) tugadi").apply()
      return false
    }
    try {
      @Suppress("DEPRECATION")
      val manager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
      val safe = OperatorReports.smsSafe(text)
      manager.sendMultipartTextMessage(phone, null, manager.divideMessage(safe), null, null)
    } catch (_: Exception) {
      prefs.edit().putString("error", "SMS yuborilmadi; SIM karta va balansni tekshiring").apply()
      return false
    }
    keys.put(key, now)
    // Keep a week of keys: enough for closed-period and per-call deduplication.
    val trimmed = JSONObject()
    for (name in keys.keys()) if (now - keys.optLong(name) < 7 * OperatorSchedule.DAY_MS) trimmed.put(name, keys.optLong(name))
    val today = OperatorSchedule.dateTag(now, tz)
    prefs.edit().putString("keys", trimmed.toString()).putString("day", today).putInt("count", sentToday(tz) + 1)
      .putLong("last_sent", now).remove("error").apply()
    return true
  }

  fun lastError(): String? = prefs.getString("error", null)
}

/** Heads-up reminder on the operator phone with a one-tap call back. */
object OperatorCallbackReminder {
  private const val CHANNEL = "smart_pos_operator_callbacks"

  private fun id(callId: String) = 20_000 + (callId.hashCode() and 0xFFFF)

  fun show(context: Context, call: OperatorCallView, tz: TimeZone) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Qayta qo‘ng‘iroq eslatmalari", NotificationManager.IMPORTANCE_HIGH))
    val number = OperatorReports.smsPhone(call.phone)
    val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    val dial = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val pending = PendingIntent.getActivity(context, id(call.id), dial, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val title = "Javobsiz qo‘ng‘iroq: ${OperatorReports.phone(call.phone)}"
    val text = (call.customerName?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") + "${OperatorSchedule.clock(call.startedAt, tz)} · qayta qo‘ng‘iroq qiling"
    manager.notify(id(call.id), NotificationCompat.Builder(context, CHANNEL).setSmallIcon(context.applicationInfo.icon)
      .setContentTitle(title).setContentText(text).setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_CALL).setContentIntent(pending).setAutoCancel(true)
      .addAction(0, "Qo‘ng‘iroq qilish", pending).build())
  }

  fun cancel(context: Context, callId: String) {
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id(callId))
  }
}
