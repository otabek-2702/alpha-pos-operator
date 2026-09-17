package __PACKAGE__

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

data class OperatorAudioFile(val uri: String, val name: String, val size: Long, val modified: Long)

/** Durable outbox. A successful initial folder inventory is REQUIRED before configuration saves. */
class OperatorRecordings(private val context: Context, private val inventorySource: ((String) -> List<OperatorAudioFile>)? = null) : SQLiteOpenHelper(context, "operator_recordings_v1.db", null, 1) {
  companion object {
    val lock = Any()
    private val AUDIO = setOf("m4a", "mp3", "amr", "aac", "wav", "ogg", "opus", "3gp", "flac", "mp4")

    fun isAudio(name: String, mime: String = ""): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO || mime.startsWith("audio/")

    fun storageRoot(): File = Environment.getExternalStorageDirectory().canonicalFile

    /** The dedicated operator phone reads recordings directly; Android's folder chooser is only a fallback. */
    fun hasAllFilesAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
      else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** A `file://` folder must be inside shared storage and never the storage root itself. */
    fun localFolder(uri: String): File {
      val parsed = Uri.parse(uri)
      require(parsed.scheme == "file" && !parsed.path.isNullOrBlank()) { "Ovoz yozuvlari papkasini qayta tanlang" }
      val folder = File(parsed.path!!).canonicalFile
      require(folder.path.startsWith(storageRoot().path + File.separator)) { "Yozuvlar papkasi telefon xotirasida bo'lishi kerak" }
      return folder
    }
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE recordings (baseline TEXT NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, first_seen INTEGER NOT NULL, stable_since INTEGER NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0, sent_at INTEGER, message_id INTEGER, error TEXT, PRIMARY KEY(baseline,uri))")
    db.execSQL("CREATE INDEX recordings_queue ON recordings(baseline,state,next_attempt)")
  }
  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

  fun inventory(folder: String): List<OperatorAudioFile> {
    inventorySource?.let { return it(folder) }
    if (Uri.parse(folder).scheme == "file") return localInventory(localFolder(folder))
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
          else if (isAudio(name, mime)) {
            result.add(OperatorAudioFile(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, if (it.isNull(3)) -1L else it.getLong(3), if (it.isNull(4)) 0L else it.getLong(4)))
          }
          require(seen.size + result.size < 50_000) { "Papka juda katta; faqat qo'ng'iroq yozuvlari papkasini tanlang" }
        }
      }
    }
    walk(DocumentsContract.getTreeDocumentId(tree), 0)
    return result
  }

  private fun localInventory(folder: File): List<OperatorAudioFile> {
    require(hasAllFilesAccess(context)) { "Yozuvlarni o'qish uchun \"Barcha fayllarga kirish\" ruxsatini bering" }
    require(folder.isDirectory) { "Yozuvlar papkasi topilmadi; papkani qayta tanlang" }
    val result = ArrayList<OperatorAudioFile>()
    var visited = 0
    fun walk(directory: File, depth: Int) {
      require(depth <= 12) { "Papka juda chuqur; yozuvlar turgan papkani tanlang" }
      val children = directory.listFiles() ?: throw IOException("Papka ochilmadi; fayllarga ruxsatni tekshiring")
      for (child in children) {
        require(++visited < 50_000) { "Papka juda katta; faqat qo'ng'iroq yozuvlari papkasini tanlang" }
        if (child.isDirectory) walk(child, depth + 1)
        else if (isAudio(child.name)) result.add(OperatorAudioFile(Uri.fromFile(child).toString(), child.name, child.length(), child.lastModified()))
      }
    }
    walk(folder, 0)
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
          "pending", "queued", "watching", "failed" -> pending += cursor.getInt(1)
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
    val files = try { inventory(telegram.getString("folderUri")) } catch (failure: Exception) {
      // Validation messages are ours (e.g. missing all-files access); other errors stay generic.
      error((failure as? IllegalArgumentException)?.message ?: "Yozuvlar papkasi ochilmadi. Papkani va fayl ruxsatlarini tekshiring"); return@synchronized
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

  /** Stable new recordings ready for Telegram, with the call-time hints needed for a caption. */
  fun readyForDelivery(telegram: JSONObject, limit: Int = 20): List<OperatorAudioFile> = synchronized(lock) {
    if (!telegram.optBoolean("enabled") || !configured(telegram)) return@synchronized emptyList()
    val list = ArrayList<OperatorAudioFile>()
    readableDatabase.rawQuery("SELECT uri,name,size,modified FROM recordings WHERE baseline=? AND state='pending' ORDER BY first_seen LIMIT ?",
      arrayOf(telegram.optString("_baseline"), limit.toString())).use {
      while (it.moveToNext()) list.add(OperatorAudioFile(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3)))
    }
    list
  }

  /** Handed to the Telegram outbox; the outbox now owns retries. */
  fun markQueued(telegram: JSONObject, uri: String) = synchronized(lock) {
    writableDatabase.update("recordings", ContentValues().apply { put("state", "queued") }, "baseline=? AND uri=?", arrayOf(telegram.optString("_baseline"), uri))
  }

  fun markSent(uri: String, messageId: Long) = synchronized(lock) {
    writableDatabase.update("recordings", ContentValues().apply {
      put("state", "sent"); put("sent_at", System.currentTimeMillis()); put("message_id", messageId); putNull("error")
    }, "uri=? AND state='queued'", arrayOf(uri))
  }

  private fun error(message: String) = OperatorRuntimeStore.update(context) { it.put("telegramError", message) }
}
