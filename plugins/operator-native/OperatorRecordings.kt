package __PACKAGE__

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OperatorAudioFile(val uri: String, val name: String, val size: Long, val modified: Long)

/** Durable outbox. A successful initial folder inventory is REQUIRED before configuration saves. */
class OperatorRecordings(private val context: Context, private val inventorySource: ((String) -> List<OperatorAudioFile>)? = null) : SQLiteOpenHelper(context, "operator_recordings_v1.db", null, 1) {
  companion object {
    val lock = Any()
    private val AUDIO = setOf("m4a", "mp3", "amr", "aac", "wav", "ogg", "opus", "3gp", "flac", "mp4")
  }
  private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE recordings (baseline TEXT NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, first_seen INTEGER NOT NULL, stable_since INTEGER NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0, sent_at INTEGER, message_id INTEGER, error TEXT, PRIMARY KEY(baseline,uri))")
    db.execSQL("CREATE INDEX recordings_queue ON recordings(baseline,state,next_attempt)")
  }
  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

  fun inventory(folder: String): List<OperatorAudioFile> {
    inventorySource?.let { return it(folder) }
    val tree = Uri.parse(folder)
    require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "Ovoz yozuvlari papkasini qayta tanlang" }
    val result = ArrayList<OperatorAudioFile>()
    val seen = HashSet<String>()
    fun walk(parentId: String, depth: Int) {
      require(depth <= 12) { "Papka juda chuqur; yozuvlar turgan papkani tanlang" }
      if (!seen.add(parentId)) return
      val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
      val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
      val cursor = context.contentResolver.query(children, projection, null, null, null)
        ?: throw IOException("Papka ochilmadi; fayllarga ruxsatni tekshiring")
      cursor.use {
        while (it.moveToNext()) {
          val id = it.getString(0)
          val name = it.getString(1) ?: "recording"
          val mime = it.getString(2) ?: ""
          if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, depth + 1)
          else if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO || mime.startsWith("audio/")) {
            result.add(OperatorAudioFile(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, if (it.isNull(3)) -1L else it.getLong(3), if (it.isNull(4)) 0L else it.getLong(4)))
          }
          require(seen.size + result.size < 50_000) { "Papka juda katta; faqat qo'ng'iroq yozuvlari papkasini tanlang" }
        }
      }
    }
    walk(DocumentsContract.getTreeDocumentId(tree), 0)
    return result
  }

  /** Called before encrypted config is committed. Existing files never enter the pending queue. */
  fun prepareConfiguration(telegram: JSONObject, previous: JSONObject?) = synchronized(lock) {
    telegram.remove("_baseline")
    telegram.remove("_baselineAt")
    if (!telegram.optBoolean("enabled") || !configured(telegram)) return@synchronized
    val same = previous != null && previous.optBoolean("enabled") &&
      listOf("folderUri", "chatId").all { telegram.optString(it) == previous.optString(it) } &&
      telegram.optString("botToken").substringBefore(':') == previous.optString("botToken").substringBefore(':') && previous.optString("_baseline").isNotBlank()
    if (same) {
      telegram.put("_baseline", previous!!.getString("_baseline"))
      telegram.put("_baselineAt", previous.optLong("_baselineAt"))
      return@synchronized
    }
    val files = inventory(telegram.getString("folderUri"))
    val baseline = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    val db = writableDatabase
    db.beginTransaction()
    try {
      for (file in files) db.insertOrThrow("recordings", null, values(file, baseline, now, "ignored"))
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
    telegram.put("_baseline", baseline)
    telegram.put("_baselineAt", now)
    OperatorRuntimeStore.update(context) { state -> state.remove("telegramError"); state.remove("telegramBlocked") }
  }

  private fun values(file: OperatorAudioFile, baseline: String, now: Long, state: String) = ContentValues().apply {
    put("baseline", baseline); put("uri", file.uri); put("name", file.name); put("size", file.size)
    put("modified", file.modified); put("first_seen", now); put("stable_since", now); put("state", state)
  }

  fun configured(telegram: JSONObject): Boolean = telegram.optString("botToken").isNotBlank() && telegram.optString("chatId").isNotBlank() && telegram.optString("folderUri").isNotBlank()

  fun snapshot(telegram: JSONObject): JSONObject = synchronized(lock) {
    val baseline = telegram.optString("_baseline")
    val result = JSONObject().put("enabled", telegram.optBoolean("enabled")).put("configured", configured(telegram))
      .put("hasToken", telegram.optString("botToken").isNotBlank()).put("sendCallStats", telegram.optBoolean("sendCallStats"))
      .put("statsChatId", telegram.optString("statsChatId"))
      .put("folderUri", telegram.optString("folderUri")).put("folderName", telegram.optString("folderName"))
      .put("pending", 0).put("sent", 0)
    readableDatabase.rawQuery("SELECT state,COUNT(*),MAX(sent_at) FROM recordings WHERE baseline=? GROUP BY state", arrayOf(baseline)).use { cursor ->
      var pending = 0
      while (cursor.moveToNext()) {
        when (cursor.getString(0)) {
          "pending", "watching", "failed" -> pending += cursor.getInt(1)
          "sent" -> { result.put("sent", cursor.getInt(1)); result.put("lastSentAt", cursor.getLong(2)) }
        }
      }
      result.put("pending", pending)
    }
    val state = OperatorRuntimeStore.state(context)
    if (state.has("telegramError")) result.put("lastError", state.get("telegramError"))
    else readableDatabase.rawQuery("SELECT error FROM recordings WHERE baseline=? AND state='failed' AND error IS NOT NULL ORDER BY first_seen DESC LIMIT 1", arrayOf(baseline)).use {
      if (it.moveToFirst()) result.put("lastError", it.getString(0))
      Unit
    }
    result
  }

  /** All scan/config DB transitions share a lock; uploads are single-threaded by the service. */
  fun scan(telegram: JSONObject, callActive: Boolean, hasCallPermissions: Boolean) = synchronized(lock) {
    if (!telegram.optBoolean("enabled") || !configured(telegram)) return@synchronized
    val baseline = telegram.optString("_baseline")
    if (baseline.isBlank()) { error("Papka uchun boshlang'ich tekshiruv kerak; Telegram sozlamalarini saqlang"); return@synchronized }
    val files = try { inventory(telegram.getString("folderUri")) } catch (_: Exception) {
      error("Yozuvlar papkasi ochilmadi. Papkani va fayl ruxsatlarini tekshiring"); return@synchronized
    }
    val now = System.currentTimeMillis()
    val db = writableDatabase
    db.beginTransaction()
    try {
      for (file in files) {
        db.rawQuery("SELECT size,modified,stable_since,state,first_seen FROM recordings WHERE baseline=? AND uri=?", arrayOf(baseline, file.uri)).use { row ->
          if (!row.moveToFirst()) {
            // Also reject older files moved/renamed into the selected folder after setup.
            val historical = OperatorRecordingPolicy.isHistorical(file.modified, telegram.optLong("_baselineAt"))
            db.insertOrThrow("recordings", null, values(file, baseline, now, if (historical) "ignored" else "watching"))
          } else {
            val status = row.getString(3)
            if (status == "watching" || status == "pending") {
              if (row.getLong(0) != file.size || row.getLong(1) != file.modified) {
                db.update("recordings", ContentValues().apply { put("size", file.size); put("modified", file.modified); put("name", file.name); put("stable_since", now); put("state", "watching") }, "baseline=? AND uri=?", arrayOf(baseline, file.uri))
              } else if (OperatorRecordingPolicy.canQueue(file.size, row.getLong(2), row.getLong(4), now, callActive, hasCallPermissions)) {
                val tooLarge = OperatorRecordingPolicy.isTooLarge(file.size)
                db.update("recordings", ContentValues().apply {
                  put("state", if (tooLarge) "failed" else "pending")
                  if (tooLarge) put("error", "Yozuv 50 MB dan katta; Telegram qabul qilmaydi")
                }, "baseline=? AND uri=?", arrayOf(baseline, file.uri))
                if (tooLarge) error("Yozuv 50 MB dan katta; Telegram qabul qilmaydi")
              }
            }
          }
          Unit
        }
      }
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  fun uploadNext(telegram: JSONObject) {
    if (!telegram.optBoolean("enabled") || !configured(telegram)) return
    if (OperatorRuntimeStore.state(context).optBoolean("telegramBlocked")) return
    val baseline = telegram.optString("_baseline")
    val now = System.currentTimeMillis()
    var file: OperatorAudioFile? = null
    var attempts = 0
    synchronized(lock) {
      readableDatabase.rawQuery("SELECT uri,name,size,modified,attempts FROM recordings WHERE baseline=? AND state='pending' AND next_attempt<=? ORDER BY first_seen LIMIT 1", arrayOf(baseline, now.toString())).use {
        if (it.moveToFirst()) { file = OperatorAudioFile(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3)); attempts = it.getInt(4) }
      }
    }
    val selected = file ?: return
    try {
      val uri = Uri.parse(selected.uri)
      // Recheck just before streaming so a recording that resumed writing is never uploaded early.
      context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { row ->
        if (!row.moveToFirst()) throw IOException("missing recording")
        if (row.getLong(0) != selected.size || row.getLong(1) != selected.modified) {
          synchronized(lock) { writableDatabase.update("recordings", ContentValues().apply { put("state", "watching"); put("stable_since", now) }, "baseline=? AND uri=?", arrayOf(baseline, selected.uri)) }
          return
        }
      } ?: throw IOException("missing recording")
      val body = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = selected.size
        override fun writeTo(sink: BufferedSink) {
          val input = context.contentResolver.openInputStream(uri) ?: throw IOException("recording unavailable")
          input.source().use { sink.writeAll(it) }
        }
      }
      val whenRecorded = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(Date(if (selected.modified > 0) selected.modified else now))
      val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("chat_id", telegram.getString("chatId"))
        .addFormDataPart("caption", "Smart POS Operator • Ovoz yozuvi\n$whenRecorded\n${selected.name.take(200)}")
        .addFormDataPart("document", selected.name.replace('\n', '_').replace('\r', '_'), body).build()
      val request = Request.Builder().url("https://api.telegram.org/bot${telegram.getString("botToken")}/sendDocument").post(multipart).build()
      http.newCall(request).execute().use { response ->
        val json = try { JSONObject(response.body?.string() ?: "{}") } catch (_: Exception) { JSONObject() }
        if (response.isSuccessful && json.optBoolean("ok")) {
          synchronized(lock) {
            writableDatabase.update("recordings", ContentValues().apply {
              put("state", "sent"); put("sent_at", System.currentTimeMillis()); put("message_id", json.optJSONObject("result")?.optLong("message_id") ?: 0L); putNull("error")
            }, "baseline=? AND uri=?", arrayOf(baseline, selected.uri))
          }
          OperatorRuntimeStore.update(context) { it.remove("telegramError") }
        } else {
          val code = json.optInt("error_code", response.code)
          if (code == 400 || code == 401 || code == 403 || code == 404) {
            error("Telegram sozlamalari yoki guruh ruxsati xato (HTTP $code). Token va guruh ID sini tekshiring")
            OperatorRuntimeStore.update(context) { it.put("telegramBlocked", true) }
          } else {
            val seconds = if (code == 429) maxOf(1L, json.optJSONObject("parameters")?.optLong("retry_after", 60L) ?: 60L) else OperatorRecordingPolicy.retrySeconds(attempts)
            retry(selected, baseline, attempts, seconds, "Telegram vaqtincha mavjud emas (HTTP $code); qayta yuboriladi")
          }
        }
      }
    } catch (_: SecurityException) {
      error("Yozuvni o'qishga ruxsat yo'q; papkani qayta tanlang")
      retry(selected, baseline, attempts, 300, "Yozuvni o'qishga ruxsat yo'q; papkani qayta tanlang")
    } catch (_: Exception) {
      retry(selected, baseline, attempts, OperatorRecordingPolicy.retrySeconds(attempts), "Yozuv yuborilmadi; internet tiklangach qayta yuboriladi")
    }
  }

  private fun retry(file: OperatorAudioFile, baseline: String, attempts: Int, seconds: Long, message: String) {
    synchronized(lock) { writableDatabase.update("recordings", ContentValues().apply {
      put("attempts", attempts + 1); put("next_attempt", System.currentTimeMillis() + seconds.coerceIn(1L, 2_147_483_647L) * 1000); put("error", message)
    }, "baseline=? AND uri=?", arrayOf(baseline, file.uri)) }
    error(message)
  }
  private fun error(message: String) = OperatorRuntimeStore.update(context) { it.put("telegramError", message) }
  fun cancelUploads() { http.dispatcher.cancelAll() }
}
