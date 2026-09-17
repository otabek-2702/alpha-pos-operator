package com.alphapos.operatorlink

import android.content.Context
import org.json.JSONObject
import java.io.InputStream

/** Durable Telegram outbox against a fake Bot API: mirroring, edits, re-sends, file reuse and group upgrades. */
object OperatorTelegramChecks {
  fun run(context: Context) {
    val calls = ArrayList<String>()
    var nextId = 100L
    var editFails = false
    fun ok(result: Any) = OperatorTelegram.Response(200, JSONObject().put("ok", true).put("result", result))
    fun message(fileId: String? = null): JSONObject = JSONObject().put("message_id", nextId++).also { m ->
      if (fileId != null) m.put("document", JSONObject().put("file_id", fileId))
    }
    val transport = object : OperatorTelegram.Transport {
      override fun json(token: String, method: String, body: JSONObject): OperatorTelegram.Response {
        calls.add("$method ${body.optString("chat_id")} ${body.optString("document")} ${body.optLong("message_id")}".trim())
        return when (method) {
          "sendMessage" -> if (body.optString("chat_id") == "-7") OperatorTelegram.Response(400, JSONObject().put("ok", false)
            .put("description", "Bad Request: group chat was upgraded to a supergroup chat")
            .put("parameters", JSONObject().put("migrate_to_chat_id", -1007L)))
            else ok(message())
          "editMessageText" -> if (editFails) OperatorTelegram.Response(400, JSONObject().put("ok", false).put("description", "Bad Request: message to edit not found"))
            else ok(message())
          "sendDocument" -> ok(message(fileId = "FILE-1"))
          "pinChatMessage" -> ok(true)
          else -> OperatorTelegram.Response(404, JSONObject().put("ok", false))
        }
      }
      override fun document(token: String, chat: String, name: String, size: Long, open: () -> InputStream, caption: String): OperatorTelegram.Response {
        calls.add("upload $chat $name")
        return ok(message(fileId = "FILE-1"))
      }
    }
    val name = "operator_telegram_test_${System.currentTimeMillis()}.db"
    val outbox = OperatorTelegram(context, transport, name, minIntervalMs = 0)
    var migrated: Pair<String, String>? = null
    outbox.listener = object : OperatorTelegram.Listener {
      override fun chatMigrated(from: String, to: String) { migrated = from to to }
    }
    fun drainAll() { repeat(6) { outbox.drain("123:test") } }
    try {
      outbox.post("call:1", listOf("-100main", "-100backup"), "<b>bir</b>", 1, pin = true)
      drainAll()
      check(calls.count { it.startsWith("sendMessage") } == 2) { "Main group and backup each get their own message: $calls" }
      check(calls.count { it.startsWith("pinChatMessage") } == 2) { "Shift reports are pinned in both groups" }
      val mainId = outbox.messageId("call:1", "-100main")!!
      check(outbox.messageLink("call:1", "-100main") == "https://t.me/c/main/$mainId")
      check(outbox.pending() == 0)

      calls.clear()
      outbox.post("call:1", listOf("-100main", "-100backup"), "<b>ikki</b>", 2)
      drainAll()
      check(calls.count { it.startsWith("editMessageText") } == 2 && calls.none { it.startsWith("sendMessage") }) { "A new revision edits both copies: $calls" }
      outbox.post("call:1", listOf("-100main"), "<b>ikki</b>", 2)
      drainAll()
      check(calls.count { it.startsWith("editMessageText") } == 2) { "The same revision is never sent twice" }

      calls.clear()
      editFails = true
      outbox.post("call:1", listOf("-100backup"), "<b>uch</b>", 3)
      drainAll()
      editFails = false
      drainAll()
      check(calls.any { it.startsWith("sendMessage -100backup") }) { "A deleted message is sent again: $calls" }

      calls.clear()
      outbox.postDocument("rec:a", listOf("-100main", "-100backup"), "file:///nonexistent/a.m4a", "a.m4a", 10, "<b>ovoz</b>")
      drainAll()
      check(calls.count { it.startsWith("upload") } == 1 && calls.any { it == "upload -100main a.m4a" }) { "Audio is uploaded once: $calls" }
      check(calls.any { it.startsWith("sendDocument -100backup FILE-1") }) { "The backup reuses the uploaded file: $calls" }

      calls.clear()
      outbox.post("alert:1", listOf("-7"), "ogohlantirish", 1)
      drainAll()
      check(migrated == ("-7" to "-1007")) { "A group upgrade is reported" }
      check(calls.any { it.startsWith("sendMessage -1007") } && outbox.pending() == 0) { "The message follows the upgraded group: $calls" }

      calls.clear()
      outbox.adopt("call:old", "-100main", 55, 3)
      outbox.post("call:old", listOf("-100main"), "yangi", 4)
      drainAll()
      check(calls.any { it == "editMessageText -100main  55" }) { "Messages from 2.1 keep being edited: $calls" }
    } finally {
      outbox.close()
      context.deleteDatabase(name)
    }
  }
}
