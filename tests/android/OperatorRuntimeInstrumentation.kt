package com.alphapos.operatorlink

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.provider.CallLog
import android.telephony.TelephonyManager
import org.json.JSONObject

/** Runs only in the isolated emulator; writes synthetic call-log rows and removes those exact rows. */
class OperatorRuntimeInstrumentation : Instrumentation() {
  private var mode = "test"
  override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); mode = arguments?.getString("mode") ?: "test"; start() }
  override fun onStart() {
    val result = Bundle()
    if (mode != "test") {
      try { result.putString("stream", OperatorScenario.run(targetContext, mode).toString() + "\n"); finish(-1, result) }
      catch (error: Throwable) { result.putString("stream", "FAIL: ${error.message}\n"); finish(0, result) }
      return
    }
    var resultCode = 0
    val inserted = ArrayList<android.net.Uri>()
    var history: OperatorCallHistory? = null
    try {
      check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Use an emulator, never a real operator phone" }
      uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG", "android.permission.READ_PHONE_STATE")
      val isolated = object : ContextWrapper(targetContext) {
        private val prefix = "operator_test_${System.currentTimeMillis()}_"
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(prefix + name, mode)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = baseContext.openOrCreateDatabase(prefix + name, mode, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = baseContext.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)
      }
      OperatorRecordingChecks.run(isolated)
      OperatorStorageChecks.run(isolated)
      check(isolated.getSharedPreferences("operator_call_history_v1", Context.MODE_PRIVATE).edit()
        .putLong("baseline", System.currentTimeMillis() - 60_000L).commit())
      history = OperatorCallHistory(isolated)
      val ledger = history
      fun addLog(phone: String, at: Long, duration: Long, type: Int) {
        val uri = targetContext.contentResolver.insert(CallLog.Calls.CONTENT_URI, ContentValues().apply {
          put(CallLog.Calls.NUMBER, phone); put(CallLog.Calls.DATE, at); put(CallLog.Calls.DURATION, duration); put(CallLog.Calls.TYPE, type)
        }) ?: error("Call-log insert failed")
        inserted.add(uri)
      }
      fun reconcile() = ledger.tick(JSONObject().put("sendCallStats", false))
      val incoming = "+998900001001"
      val start = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, "")
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, incoming)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, "")
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, incoming)
      Thread.sleep(1200)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, incoming)
      Thread.sleep(1200)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_IDLE, "")
      addLog(incoming, start, 1, CallLog.Calls.INCOMING_TYPE)
      reconcile()
      val answered = ledger.pendingRecords("pos-a").single { it.optString("phone") == incoming }
      check(answered.getString("outcome") == "answered")
      check(answered.getLong("ringSeconds") in 1..3)
      check(answered.getLong("talkSeconds") == 1L)
      check(answered.getString("timingSource") == "observed_answer")
      ledger.readableDatabase.rawQuery("SELECT COUNT(*) FROM observations WHERE direction='in' AND started>=?", arrayOf(start.toString())).use {
        check(it.moveToFirst() && it.getInt(0) == 1) { "Blank/number duplicate callbacks must produce one incoming observation" }
      }

      // The next call has no live observation; the first call's observation is already claimed.
      val quickRepeatAt = System.currentTimeMillis()
      addLog(incoming, quickRepeatAt, 0, CallLog.Calls.MISSED_TYPE)
      reconcile()
      val quickRepeat = ledger.pendingRecords("pos-a").single { it.optString("phone") == incoming && it.getString("id") != answered.getString("id") }
      check(quickRepeat.isNull("answeredAt") && quickRepeat.isNull("endedAt")) { "A quick repeated number must not reuse another call's timing" }

      val missedPhone = "+998900001002"
      val missedStart = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, missedPhone)
      Thread.sleep(1100)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_IDLE, "")
      addLog(missedPhone, missedStart, 0, CallLog.Calls.MISSED_TYPE)
      reconcile()
      val missed = ledger.pendingRecords("pos-a").single { it.optString("phone") == missedPhone }
      check(missed.getString("outcome") == "missed")
      check(!missed.getBoolean("missedWhileBusy"))
      check(missed.isNull("ringSeconds"))

      Thread.sleep(100)
      val attemptAt = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, missedPhone)
      Thread.sleep(1100)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_IDLE, "")
      addLog(missedPhone, attemptAt, 0, CallLog.Calls.OUTGOING_TYPE)
      reconcile()
      var linked = ledger.pendingRecords("pos-a").single { it.getString("id") == missed.getString("id") }
      check(!linked.isNull("callbackAttemptAt"))
      check(!linked.optBoolean("callbackConnected")) { "Dialing must not count as a connected callback" }

      val connectedAt = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, missedPhone)
      Thread.sleep(1200)
      // Final CallLog can become visible before the queued IDLE observation.
      addLog(missedPhone, connectedAt, 1, CallLog.Calls.OUTGOING_TYPE)
      reconcile()
      linked = ledger.pendingRecords("pos-a").single { it.getString("id") == missed.getString("id") }
      check(linked.optBoolean("callbackConnected"))
      check(linked.isNull("callbackConnectedAt")) { "Connection can be confirmed before its timestamp is known" }
      val firstSuccessId = linked.getString("callbackCallId")
      ledger.onPhoneState(TelephonyManager.CALL_STATE_IDLE, "")
      reconcile()
      linked = ledger.pendingRecords("pos-a").single { it.getString("id") == missed.getString("id") }
      check(!linked.isNull("callbackConnectedAt") && !linked.isNull("callbackDelaySeconds")) { "The same first successful callback must gain later timing evidence" }
      check(linked.getString("callbackCallId") == firstSuccessId)
      check(linked.optLong("callbackAttemptAt") == attemptAt) { "First callback attempt must remain stable" }
      val firstSuccessTime = linked.getLong("callbackConnectedAt")
      Thread.sleep(20)
      val laterSuccessAt = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, missedPhone)
      Thread.sleep(40)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_IDLE, "")
      addLog(missedPhone, laterSuccessAt, 1, CallLog.Calls.OUTGOING_TYPE)
      reconcile()
      linked = ledger.pendingRecords("pos-a").single { it.getString("id") == missed.getString("id") }
      check(linked.getString("callbackCallId") == firstSuccessId && linked.getLong("callbackConnectedAt") == firstSuccessTime) { "A later successful call must not replace the first one" }
      val revisions = ledger.pendingRecords("pos-b").associate { it.getString("id") to it.getLong("revision") }
      reconcile()
      check(ledger.pendingRecords("pos-b").associate { it.getString("id") to it.getLong("revision") } == revisions) { "Unchanged reconciliation must not advance call revisions" }

      val primaryPhone = "+998900001003"
      val busyStart = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, primaryPhone)
      Thread.sleep(100)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, primaryPhone)
      val waitingPhone = "+998900001004"
      val waitingStart = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, waitingPhone)
      Thread.sleep(1100)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, primaryPhone)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_IDLE, "")
      addLog(primaryPhone, busyStart, 1, CallLog.Calls.INCOMING_TYPE)
      addLog(waitingPhone, waitingStart, 0, CallLog.Calls.MISSED_TYPE)
      reconcile()
      val waiting = ledger.pendingRecords("pos-a").single { it.optString("phone") == waitingPhone }
      check(waiting.getBoolean("missedWhileBusy"))
      check(waiting.isNull("endedAt")) { "Waiting-call end must not be confused with primary-call end" }
      check(ledger.pendingRecords("pos-a").single { it.optString("phone") == primaryPhone }.isNull("endedAt")) { "Overlapping primary-call end must also remain unknown" }

      // Duplicate waiting callbacks must never fill the OUTGOING primary's number,
      // lose its busy state, or count a second same-number ring burst as the first.
      val outgoingPrimary = "+998900001007"
      val outgoingStart = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, outgoingPrimary)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, "")
      val duplicateWaiting = "+998900001008"
      val waitingOneAt = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, "")
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, duplicateWaiting)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, "")
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, outgoingPrimary)
      Thread.sleep(40)
      val waitingTwoAt = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, "")
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, duplicateWaiting)
      ledger.readableDatabase.rawQuery("SELECT phone,answered FROM observations WHERE direction='out' AND started>=?", arrayOf(outgoingStart.toString())).use {
        check(it.moveToFirst() && it.getString(0) == "998900001007" && it.isNull(1)) { "Waiting caller cannot rename an outgoing call or mark dialing as answered" }
      }
      ledger.readableDatabase.rawQuery("SELECT COUNT(*) FROM observations WHERE direction='in' AND phone=? AND started>=? AND busy=1 AND ambiguous=1", arrayOf("998900001008", outgoingStart.toString())).use {
        check(it.moveToFirst() && it.getInt(0) == 2) { "Each waiting ring burst needs exactly one busy observation" }
      }
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, outgoingPrimary)
      Thread.sleep(20)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_IDLE, "")
      addLog(outgoingPrimary, outgoingStart, 1, CallLog.Calls.OUTGOING_TYPE)
      addLog(duplicateWaiting, waitingOneAt, 0, CallLog.Calls.MISSED_TYPE)
      addLog(duplicateWaiting, waitingTwoAt, 0, CallLog.Calls.MISSED_TYPE)
      reconcile()
      val duplicateWaitingRecords = ledger.pendingRecords("pos-a").filter { it.optString("phone") == duplicateWaiting }
      check(duplicateWaitingRecords.size == 2 && duplicateWaitingRecords.all { it.optBoolean("missedWhileBusy") && it.isNull("endedAt") }) { "Both waiting calls must retain observed busy state and unknown end timing" }

      val blankOutgoingAt = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, "")
      val blankPrimaryWaitingAt = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, "")
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, "+998900001011")
      ledger.readableDatabase.rawQuery("SELECT phone,answered FROM observations WHERE direction='out' AND started>=?", arrayOf(blankOutgoingAt.toString())).use {
        check(it.moveToFirst() && it.getString(0).isEmpty() && it.isNull(1)) { "A waiting number must not fill an unknown outgoing number" }
      }
      ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, "")
      Thread.sleep(20)
      ledger.onPhoneState(TelephonyManager.CALL_STATE_IDLE, "")
      addLog("+998900001012", blankOutgoingAt, 0, CallLog.Calls.OUTGOING_TYPE)
      addLog("+998900001011", blankPrimaryWaitingAt, 0, CallLog.Calls.MISSED_TYPE)
      reconcile()
      check(ledger.pendingRecords("pos-a").single { it.optString("phone") == "+998900001011" }.optBoolean("missedWhileBusy"))

      // Earlier calls without observations cannot claim later calls, even inside
      // the timestamp tolerance and before that later call's OS row exists.
      fun checkLaterObservation(phone: String, gap: Long) {
        val currentStart = System.currentTimeMillis()
        val oldStart = currentStart - gap
        addLog(phone, oldStart, 0, CallLog.Calls.MISSED_TYPE)
        ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, phone)
        reconcile()
        check(ledger.pendingRecords("pos-a").single { it.optString("phone") == phone }.isNull("endedAt"))
        ledger.readableDatabase.rawQuery("SELECT call_id FROM observations WHERE phone=? AND started>=?", arrayOf(phone.removePrefix("+"), currentStart.toString())).use {
          check(it.moveToFirst() && it.isNull(0)) { "Older missing call must not claim the later ongoing call" }
        }
        Thread.sleep(30)
        ledger.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, phone)
        Thread.sleep(30)
        ledger.onPhoneState(TelephonyManager.CALL_STATE_IDLE, "")
        addLog(phone, currentStart, 0, CallLog.Calls.INCOMING_TYPE)
        reconcile()
        val records = ledger.pendingRecords("pos-a").filter { it.optString("phone") == phone }
        val older = records.single { it.getLong("startedAt") == oldStart }
        val current = records.single { it.getLong("startedAt") == currentStart }
        check(older.isNull("answeredAt") && older.isNull("endedAt") && older.isNull("ringDurationSeconds")) { "Earlier call cannot borrow future timing" }
        check(current.getString("timingSource") == "observed_answer" && current.getLong("answeredAt") >= currentStart && !current.isNull("endedAt")) { "The later actual call must retain its own observation" }
      }
      checkLaterObservation("+998900001009", 20_000L)
      checkLaterObservation("+998900001010", 1000L)

      val unknownStart = System.currentTimeMillis()
      addLog("", unknownStart, 0, CallLog.Calls.MISSED_TYPE)
      reconcile()
      val unknown = ledger.pendingRecords("pos-a").single { it.optString("phone") == "" }
      check(unknown.isNull("answeredAt") && unknown.isNull("endedAt") && unknown.isNull("missedWhileBusy"))

      ledger.enrichCustomer("900001001", "Sinov mijoz")
      val enriched = ledger.pendingRecords("pos-a").single { it.getString("id") == answered.getString("id") }
      check(enriched.optString("customerName") == "Sinov mijoz")
      ledger.acknowledge("pos-a", enriched.getString("id"), enriched.getLong("revision"))
      check(ledger.pendingRecords("pos-a").none { it.getString("id") == enriched.getString("id") })
      check(ledger.pendingRecords("pos-b").any { it.getString("id") == enriched.getString("id") })
      val interruptedStart = System.currentTimeMillis()
      ledger.onPhoneState(TelephonyManager.CALL_STATE_RINGING, "+998900001005")
      ledger.close()
      history = OperatorCallHistory(isolated)
      history.onPhoneState(TelephonyManager.CALL_STATE_RINGING, "+998900001006")
      history.onPhoneState(TelephonyManager.CALL_STATE_IDLE, "")
      addLog("+998900001005", interruptedStart, 0, CallLog.Calls.MISSED_TYPE)
      history.tick(JSONObject().put("sendCallStats", false))
      check(history.pendingRecords("pos-b").single { it.optString("phone") == "+998900001005" }.isNull("endedAt")) { "A later session must not finish a pre-restart call" }
      check(history.pendingRecords("pos-a").none { it.getString("id") == enriched.getString("id") })
      check(history.pendingRecords("pos-b").any { it.getString("id") == enriched.getString("id") })
      result.putString("stream", "PASS: answer timing; duplicate ringing; duration; missed outcome; callback refinement and stable revisions; busy waiting calls; unobserved-call matching; hidden number; customer name; independent POS ACK; restart persistence\n")
      resultCode = -1
    } catch (error: Throwable) {
      result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n${error.stackTrace.take(7).joinToString("\n")}\n")
    } finally {
      try { history?.close() } catch (_: Exception) { }
      for (uri in inserted) try { targetContext.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
      uiAutomation.dropShellPermissionIdentity()
    }
    finish(resultCode, result)
  }
}
