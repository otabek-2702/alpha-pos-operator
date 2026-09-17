package __PACKAGE__

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Durable Telegram delivery. One row per (key, chat): a higher revision edits the message
 * that was already sent, a deleted message is sent again, and every chat (main groups,
 * backup group, managers) has its own retry state so one failing chat never blocks another.
 */
class OperatorTelegram(
  private val context: Context,
  private val transport: Transport = HttpTransport(),
  name: String = "operator_telegram_v1.db",
  private val minIntervalMs: Long = 3_000L,
) : SQLiteOpenHelper(context, name, null, 1) {

  data class Response(val code: Int, val json: JSONObject)

  interface Transport {
    fun json(token: String, method: String, body: JSONObject): Response
    fun document(token: String, chat: String, name: String, size: Long, open: () -> InputStream, caption: String, html: Boolean): Response
    fun cancel() {}
  }

  interface Listener {
    fun delivered(key: String, chat: String, messageId: Long) {}
    fun chatMigrated(from: String, to: String) {}
  }

  @Volatile var listener: Listener? = null
  private val lastSent = HashMap<String, Long>()
  private val prefs = context.getSharedPreferences(name.removeSuffix(".db"), Context.MODE_PRIVATE)

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE outbox (key TEXT NOT NULL, chat TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, revision INTEGER NOT NULL, sent_revision INTEGER NOT NULL DEFAULT 0, message_id INTEGER, file_id TEXT, primary_chat TEXT, due INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, error TEXT, created INTEGER NOT NULL, hard INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(key, chat))")
    db.execSQL("CREATE INDEX outbox_due ON outbox(sent_revision, due)")
  }
  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

  /** Adds or updates a text message in each chat. */
  @Synchronized fun post(key: String, chats: List<String>, text: String, revision: Long, pin: Boolean = false, replyToKey: String? = null, editable: Boolean = true) {
    val payload = JSONObject().put("text", text).put("pin", pin).put("editable", editable)
    if (replyToKey != null) payload.put("replyTo", replyToKey)
    for (chat in chats.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) upsert(key, chat, "text", payload, revision, null)
  }

  /** The first chat uploads the file; the others reuse its Telegram file_id (or upload if it keeps failing). */
  @Synchronized fun postDocument(key: String, chats: List<String>, uri: String, name: String, size: Long, caption: String, revision: Long = 1) {
    val targets = chats.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val payload = JSONObject().put("uri", uri).put("name", name).put("size", size).put("text", caption)
    targets.forEachIndexed { index, chat -> upsert(key, chat, "document", payload, revision, if (index == 0) null else targets[0]) }
  }

  private fun upsert(key: String, chat: String, kind: String, payload: JSONObject, revision: Long, primary: String?) {
    val now = System.currentTimeMillis()
    val existing = readableDatabase.rawQuery("SELECT revision FROM outbox WHERE key=? AND chat=?", arrayOf(key, chat)).use {
      if (it.moveToFirst()) it.getLong(0) else null
    }
    if (existing == null) {
      writableDatabase.insertOrThrow("outbox", null, ContentValues().apply {
        put("key", key); put("chat", chat); put("kind", kind); put("payload", payload.toString()); put("revision", revision)
        put("due", now); put("created", now); if (primary != null) put("primary_chat", primary)
      })
    } else if (revision > existing) {
      writableDatabase.update("outbox", ContentValues().apply {
        put("payload", payload.toString()); put("revision", revision); put("due", now); put("attempts", 0); putNull("error"); put("hard", 0)
      }, "key=? AND chat=?", arrayOf(key, chat))
    }
  }

  /** Continues editing a message sent by an older app version. */
  @Synchronized fun adopt(key: String, chat: String, messageId: Long, sentRevision: Long) {
    val now = System.currentTimeMillis()
    writableDatabase.insertWithOnConflict("outbox", null, ContentValues().apply {
      put("key", key); put("chat", chat); put("kind", "text"); put("payload", JSONObject().put("text", "").put("editable", true).toString())
      put("revision", sentRevision); put("sent_revision", sentRevision); put("message_id", messageId); put("due", now); put("created", now)
    }, SQLiteDatabase.CONFLICT_IGNORE)
  }

  @Synchronized fun messageId(key: String, chat: String): Long? =
    readableDatabase.rawQuery("SELECT message_id FROM outbox WHERE key=? AND chat=?", arrayOf(key, chat)).use {
      if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
    }

  /** Message links exist for supergroups only (IDs starting with -100). */
  fun messageLink(key: String, chat: String): String? {
    if (!chat.startsWith("-100")) return null
    return messageId(key, chat)?.let { "https://t.me/c/${chat.removePrefix("-100")}/$it" }
  }

  @Synchronized fun pending(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE sent_revision<revision", null).use { it.moveToFirst(); it.getInt(0) }

  fun lastError(): String? = prefs.getString("error", null)
  fun lastSentAt(): Long = prefs.getLong("last_sent", 0L)

  /** After a restart or reconnect everything unsent is due now, except chats Telegram refused (bot removed or blocked). */
  @Synchronized fun retryNow() { writableDatabase.execSQL("UPDATE outbox SET due=0 WHERE sent_revision<revision AND hard=0") }

  @Synchronized fun renameChat(from: String, to: String) {
    writableDatabase.execSQL("UPDATE OR IGNORE outbox SET chat=? WHERE chat=?", arrayOf(to, from))
    // A row that already exists for the new chat was posted later, with the same or a newer revision.
    writableDatabase.delete("outbox", "chat=?", arrayOf(from))
    writableDatabase.execSQL("UPDATE outbox SET primary_chat=? WHERE primary_chat=?", arrayOf(to, from))
  }

  private data class Row(val key: String, val chat: String, val kind: String, val payload: JSONObject, val revision: Long,
    val messageId: Long?, val fileId: String?, val primary: String?, val attempts: Int)

  /** Sends the oldest due row of each chat, at most one message per chat every 3 seconds. Returns how many were delivered. */
  fun drain(token: String, budget: Int = 8): Int {
    if (token.isBlank()) return 0
    val now = System.currentTimeMillis()
    val rows = synchronized(this) {
      val day = OperatorSchedule.DAY_MS
      writableDatabase.delete("outbox", "sent_revision>=revision AND created<?", arrayOf((now - 21 * day).toString()))
      // Unsendable rows do not pile up: refused chats after 3 days, anything else after 30.
      writableDatabase.delete("outbox", "sent_revision<revision AND ((hard=1 AND created<?) OR created<?)",
        arrayOf((now - 3 * day).toString(), (now - 30 * day).toString()))
      val chats = ArrayList<String>()
      readableDatabase.rawQuery("SELECT chat FROM outbox WHERE sent_revision<revision AND due<=? GROUP BY chat ORDER BY MIN(created)", arrayOf(now.toString())).use {
        while (it.moveToNext()) chats.add(it.getString(0))
      }
      chats.mapNotNull { chat ->
        readableDatabase.rawQuery("SELECT key,chat,kind,payload,revision,message_id,file_id,primary_chat,attempts FROM outbox WHERE chat=? AND sent_revision<revision AND due<=? ORDER BY created LIMIT 1",
          arrayOf(chat, now.toString())).use {
          if (it.moveToFirst()) Row(it.getString(0), it.getString(1), it.getString(2), JSONObject(it.getString(3)), it.getLong(4),
            if (it.isNull(5)) null else it.getLong(5), it.getString(6), it.getString(7), it.getInt(8)) else null
        }
      }
    }
    var delivered = 0
    for (row in rows) {
      if (delivered >= budget) break
      if (System.currentTimeMillis() - (lastSent[row.chat] ?: 0L) < minIntervalMs) continue
      if (process(token, row)) delivered++
    }
    return delivered
  }

  private fun process(token: String, row: Row): Boolean {
    try {
      val response: Response
      if (row.kind == "document") {
        if (row.messageId != null) {
          response = transport.json(token, "editMessageCaption", captionBody(row).put("message_id", row.messageId))
        } else {
          val primaryFile = row.primary?.let { primaryState(row.key, it) }
          if (primaryFile?.fileId != null) {
            response = transport.json(token, "sendDocument", captionBody(row).put("document", primaryFile.fileId))
          } else if (primaryFile != null && !primaryFile.sent && primaryFile.attempts < 3) {
            defer(row, 15, null)   // Wait for the main chat's upload, then reuse its file.
            return false
          } else {
            response = upload(token, row)
          }
        }
      } else if (row.messageId != null) {
        if (!row.payload.optBoolean("editable", true)) { markSent(row, row.messageId, null); return false }
        response = transport.json(token, "editMessageText", textBody(row).put("message_id", row.messageId))
      } else {
        val body = textBody(row)
        row.payload.optString("replyTo").takeIf { it.isNotEmpty() }?.let { reply -> messageId(reply, row.chat) }?.let {
          body.put("reply_parameters", JSONObject().put("message_id", it).put("allow_sending_without_reply", true))
        }
        response = transport.json(token, "sendMessage", body)
      }
      lastSent[row.chat] = System.currentTimeMillis()
      return handle(token, row, response)
    } catch (_: SecurityException) {
      defer(row, 300, "Faylni o'qishga ruxsat yo'q")
    } catch (_: Exception) {
      defer(row, OperatorRecordingPolicy.retrySeconds(row.attempts), "Telegramga yuborilmadi; internet tiklangach qayta yuboriladi")
    }
    return false
  }

  private fun plain(row: Row) = row.payload.optBoolean("plain", false)

  /** HTML text, or the same text without tags after Telegram refused to parse it. */
  private fun text(row: Row): String = row.payload.optString("text").let {
    if (plain(row)) it.replace(Regex("<[^>]+>"), "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&") else it
  }

  private fun textBody(row: Row): JSONObject {
    val body = JSONObject().put("chat_id", row.chat).put("text", text(row).ifBlank { "—" })
      .put("link_preview_options", JSONObject().put("is_disabled", true))
    if (!plain(row)) body.put("parse_mode", "HTML")
    return body
  }

  private fun captionBody(row: Row): JSONObject {
    val body = JSONObject().put("chat_id", row.chat).put("caption", text(row))
    if (!plain(row)) body.put("parse_mode", "HTML")
    return body
  }

  private fun upload(token: String, row: Row): Response {
    val uri = Uri.parse(row.payload.getString("uri"))
    val local = if (uri.scheme == "file") File(uri.path ?: "") else null
    val open: () -> InputStream = {
      if (local != null) FileInputStream(local) else context.contentResolver.openInputStream(uri) ?: throw IOException("recording unavailable")
    }
    return transport.document(token, row.chat, row.payload.getString("name"), row.payload.optLong("size", -1L), open, text(row), !plain(row))
  }

  private data class PrimaryFile(val fileId: String?, val attempts: Int, val sent: Boolean)

  /** The main chat's upload: its file_id, failed attempts and whether it was sent at all. */
  @Synchronized private fun primaryState(key: String, chat: String): PrimaryFile? =
    readableDatabase.rawQuery("SELECT file_id,attempts,sent_revision FROM outbox WHERE key=? AND chat=?", arrayOf(key, chat)).use {
      if (it.moveToFirst()) PrimaryFile(it.getString(0), it.getInt(1), it.getLong(2) > 0L) else null
    }

  private fun handle(token: String, row: Row, response: Response): Boolean {
    val json = response.json
    val description = json.optString("description")
    if (json.optBoolean("ok")) {
      val result = json.opt("result")
      val message = result as? JSONObject
      val messageId = message?.optLong("message_id", 0L)?.takeIf { it > 0 } ?: row.messageId
      // Telegram may classify an uploaded recording as audio or voice instead of a document.
      val fileId = listOf("document", "audio", "voice", "video")
        .firstNotNullOfOrNull { type -> message?.optJSONObject(type)?.optString("file_id")?.takeIf { it.isNotEmpty() } } ?: row.fileId
      markSent(row, messageId, fileId)
      if (row.messageId == null && messageId != null && row.payload.optBoolean("pin")) {
        try { transport.json(token, "pinChatMessage", JSONObject().put("chat_id", row.chat).put("message_id", messageId).put("disable_notification", true)) }
        catch (_: Exception) { /* Pinning is best effort. */ }
      }
      if (messageId != null) listener?.delivered(row.key, row.chat, messageId)
      return true
    }
    val parameters = json.optJSONObject("parameters")
    val migrated = parameters?.optLong("migrate_to_chat_id", 0L) ?: 0L
    when {
      description.contains("message is not modified") -> { markSent(row, row.messageId, row.fileId); return false }
      migrated != 0L -> {
        synchronized(this) { renameChat(row.chat, migrated.toString()) }
        listener?.chatMigrated(row.chat, migrated.toString())
      }
      description.contains("message to edit not found") || description.contains("MESSAGE_ID_INVALID") -> synchronized(this) {
        // Someone deleted the message: send it again as a new one.
        writableDatabase.update("outbox", ContentValues().apply { putNull("message_id"); put("due", 0) }, "key=? AND chat=?", arrayOf(row.key, row.chat))
      }
      description.contains("can't parse entities") && !plain(row) -> synchronized(this) {
        writableDatabase.update("outbox", ContentValues().apply { put("payload", row.payload.put("plain", true).toString()); put("due", 0) },
          "key=? AND chat=?", arrayOf(row.key, row.chat))
      }
      response.code == 429 -> defer(row, parameters?.optLong("retry_after", 30L) ?: 30L, null)
      response.code == 401 || response.code == 403 || response.code == 400 ->
        defer(row, 3600, "Telegram guruhi yoki bot sozlamasini tekshiring (HTTP ${response.code})", hard = true)
      else -> defer(row, OperatorRecordingPolicy.retrySeconds(row.attempts), "Telegram vaqtincha mavjud emas (HTTP ${response.code})")
    }
    return false
  }

  @Synchronized private fun markSent(row: Row, messageId: Long?, fileId: String?) {
    // The message exists even if a newer revision arrived meanwhile: keep its ID so that revision edits it.
    val ids = ContentValues().apply {
      if (messageId != null) put("message_id", messageId)
      if (fileId != null) put("file_id", fileId)
    }
    if (ids.size() > 0) writableDatabase.update("outbox", ids, "key=? AND chat=?", arrayOf(row.key, row.chat))
    writableDatabase.update("outbox", ContentValues().apply {
      put("sent_revision", row.revision); put("attempts", 0); putNull("error"); put("hard", 0)
    }, "key=? AND chat=? AND revision=?", arrayOf(row.key, row.chat, row.revision.toString()))
    prefs.edit().remove("error").putLong("last_sent", System.currentTimeMillis()).apply()
  }

  @Synchronized private fun defer(row: Row, seconds: Long, error: String?, hard: Boolean = false) {
    writableDatabase.update("outbox", ContentValues().apply {
      put("attempts", row.attempts + 1); put("due", System.currentTimeMillis() + seconds.coerceIn(1L, 3600L) * 1000L)
      put("hard", if (hard) 1 else 0)
      if (error != null) put("error", error)
    }, "key=? AND chat=?", arrayOf(row.key, row.chat))
    if (error != null) prefs.edit().putString("error", error).apply()
  }

  fun cancel() = transport.cancel()

  class HttpTransport : Transport {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

    private fun execute(request: Request, timeoutSeconds: Long = 240L): Response =
      http.newCall(request).apply { timeout().timeout(timeoutSeconds, TimeUnit.SECONDS) }.execute().use { response ->
      val json = try { JSONObject(response.body?.string() ?: "{}") } catch (_: Exception) { JSONObject() }
      Response(json.optInt("error_code", response.code).takeIf { !json.optBoolean("ok") } ?: response.code, json)
    }

    // Exceptions never include the token-bearing URL in user-visible state.
    override fun json(token: String, method: String, body: JSONObject): Response = execute(Request.Builder()
      .url("https://api.telegram.org/bot$token/$method")
      .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())

    override fun document(token: String, chat: String, name: String, size: Long, open: () -> InputStream, caption: String, html: Boolean): Response {
      val file = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size
        override fun writeTo(sink: BufferedSink) { open().source().use { sink.writeAll(it) } }
      }
      val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("chat_id", chat).addFormDataPart("caption", caption).addFormDataPart("disable_content_type_detection", "true")
        .apply { if (html) addFormDataPart("parse_mode", "HTML") }
        .addFormDataPart("document", name.replace('\n', '_').replace('\r', '_'), file).build()
      // Slow mobile uplinks: allow about 20 KB/s for long recordings.
      val seconds = (240L + maxOf(size, 0L) / 20_000L).coerceAtMost(1800L)
      return execute(Request.Builder().url("https://api.telegram.org/bot$token/sendDocument").post(body).build(), seconds)
    }

    override fun cancel() = http.dispatcher.cancelAll()
  }
}
