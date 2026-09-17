package com.alphapos.operatorlink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** Owner bot commands against the real encrypted configuration store (isolated preferences). */
object OperatorAdminChecks {
  fun run(context: Context) {
    var probeOk = true
    val probes = ArrayList<String>()
    val bot = object : OperatorTelegram.Transport {
      override fun json(token: String, method: String, body: JSONObject): OperatorTelegram.Response {
        probes.add("$method ${body.optString("chat_id")}")
        return OperatorTelegram.Response(if (probeOk) 200 else 403, JSONObject().put("ok", probeOk))
      }
      override fun document(token: String, chat: String, name: String, size: Long, open: () -> InputStream, caption: String, html: Boolean,
        audio: Boolean, title: String, performer: String) = OperatorTelegram.Response(404, JSONObject().put("ok", false))
    }
    var changes = 0
    val admin = OperatorAdmin(context, bot, { emptyMap() }, { changes++ })
    fun settings() = OperatorSettings(OperatorRuntimeStore.config(context), emptyMap())
    fun config() = OperatorRuntimeStore.config(context)

    OperatorConfigWriter.save(context, JSONObject().put("targets", JSONArray()).put("telegram", JSONObject().put("enabled", false)))
    val code = settings().adminInvite
    check(code.matches(Regex("[A-Za-z0-9]{16,32}"))) { "An owner link code is created" }
    OperatorConfigWriter.save(context, JSONObject().put("targets", JSONArray()).put("telegram", JSONObject().put("enabled", false)))
    check(settings().adminInvite == code) { "App saves without the code keep the owner link" }

    check(admin.register(settings(), "111", "wrong-code-wrong-code") == null) { "A wrong code is not an owner link" }
    check(admin.register(settings(), "111", code)?.contains("boshqaruvchisi") == true && admin.isAdmin(settings(), "111"))
    check(!admin.isAdmin(settings(), "222")) { "Other chats are not owners" }

    val added = admin.handle(settings(), "/menejer_qosh", "G‘ayrat Karimov, 90 123 45 67, almashadi 2")
    check(added?.startsWith("✅") == true) { "Manager added: $added" }
    val manager = config().getJSONArray("managers").getJSONObject(0)
    check(manager.getString("phone") == "+998 90 123 45 67" && manager.getJSONObject("schedule").getString("type") == "rotating" &&
      manager.getJSONObject("schedule").getInt("anchorShift") == 2 && manager.optString("invite").length >= 16) { "Stored manager: $manager" }
    check(admin.handle(settings(), "/menejer_ozgartir", "1, raqam, +998931112233")?.startsWith("✅") == true)
    check(config().getJSONArray("managers").getJSONObject(0).getString("phone") == "+998 93 111 22 33")
    check(admin.handle(settings(), "/menejer_ozgartir", "1, raqam, 12345")?.startsWith("⚠️") == true) { "Bad numbers are refused" }
    check(admin.handle(settings(), "/menejer_ozgartir", "1, smena, 1")?.startsWith("✅") == true &&
      config().getJSONArray("managers").getJSONObject(0).getJSONObject("schedule").getInt("shift") == 1)
    check(admin.handle(settings(), "/menejer_qosh", "Ali")?.startsWith("⚠️") == true) { "Incomplete commands explain the format" }
    check(admin.handle(settings(), "/menejerlar", "")?.contains("G‘ayrat Karimov") == true)

    check(admin.handle(settings(), "/vaqt", "menejer 3")?.startsWith("✅") == true && settings().managerAlertMs == 180_000L)
    check(admin.handle(settings(), "/vaqt", "menejer 99")?.startsWith("⚠️") == true && settings().managerAlertMs == 180_000L)
    check(admin.handle(settings(), "/yopiq_sms", "ha")?.startsWith("✅") == true && settings().closedSmsEnabled)

    check(admin.handle(settings(), "/guruh", "zaxira -1004304543678")?.startsWith("✅") == true && settings().backupChat == "-1004304543678")
    check(probes.last() == "sendMessage -1004304543678") { "The bot proves it can post before switching: $probes" }
    probeOk = false
    check(admin.handle(settings(), "/guruh", "hisobot -1005550001")?.startsWith("⚠️") == true && settings().statsChat.isEmpty()) { "Unreachable groups are refused" }
    probeOk = true
    check(admin.handle(settings(), "/guruh", "hisobot -1004304543678")?.startsWith("⚠️") == true) { "One group cannot serve two purposes" }
    check(admin.handle(settings(), "/guruh", "zaxira ochir")?.startsWith("✅") == true && settings().backupChat.isEmpty())
    val settingsText = admin.handle(settings(), "/sozlamalar", "")
    check(settingsText?.contains("Menejerga xabar: 3 daq") == true && settingsText.contains("Yopiq vaqtdagi SMS: yoqilgan")) { "$settingsText" }

    check(admin.handle(settings(), "/menejer_ochir", "1")?.startsWith("✅") == true && config().getJSONArray("managers").length() == 0)
    check(admin.handle(settings(), "/nomalum", "") == null) { "Unknown commands fall through" }
    check(changes == 8) { "The service reloads after every change: $changes" }

    OperatorConfigWriter.update(context) { it.put("adminInvite", "new") }
    check(settings().adminInvite != code && !admin.isAdmin(settings(), "111")) { "A new owner link disconnects earlier owners" }
  }
}
