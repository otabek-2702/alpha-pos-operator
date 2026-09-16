package __PACKAGE__

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class OperatorRuntimePackage : ReactPackage {
  override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(OperatorRuntimeModule(context))
  override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

class OperatorRuntimeModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  private val work = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())
  private var folderPromise: Promise? = null
  private val folderRequest = 8735
  override fun getName() = "OperatorRuntime"

  init {
    context.addActivityEventListener(object : BaseActivityEventListener() {
      override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != folderRequest) return
        val promise = folderPromise ?: return
        folderPromise = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) { promise.resolve(null); return }
        try {
          val grant = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
          context.contentResolver.takePersistableUriPermission(uri, grant)
          promise.resolve(uri.toString())
        } catch (_: Exception) { promise.reject("FOLDER_ACCESS", "Papkaga doimiy ruxsat berilmadi. Papkani qayta tanlang") }
      }
    })
  }

  @ReactMethod fun configure(raw: String, promise: Promise) {
    work.execute {
      try {
        val previous = OperatorRuntimeStore.config(context)
        val value = JSONObject(raw)
        val targets = value.optJSONArray("targets") ?: JSONArray()
        require(targets.length() <= 50) { "Ko'pi bilan 50 ta POS qo'shish mumkin" }
        val ids = HashSet<String>()
        for (i in 0 until targets.length()) {
          val target = targets.getJSONObject(i)
          require(target.optString("id").isNotBlank() && target.optString("id").length <= 128 && ids.add(target.getString("id"))) { "POS identifikatori noto'g'ri yoki takrorlangan" }
          require(target.optString("name").length <= 200) { "POS nomi juda uzun" }
          val uri = URI(target.getString("url"))
          require(uri.scheme in listOf("ws", "wss") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "POS manzili noto'g'ri" }
          require(target.optInt("discoveryPort", 8766) in 1..65535) { "POS qidirish porti noto'g'ri" }
        }
        value.put("targets", targets)
        val oldTelegram = previous.optJSONObject("telegram")
        val telegram = value.optJSONObject("telegram") ?: oldTelegram?.let { JSONObject(it.toString()) } ?: JSONObject().put("enabled", false)
        if (!telegram.has("botToken") && oldTelegram != null) telegram.put("botToken", oldTelegram.optString("botToken"))
        val token = telegram.optString("botToken").trim()
        telegram.put("botToken", token)
        if (token.isNotEmpty()) require(Regex("[0-9]{5,}:[A-Za-z0-9_-]{20,}").matches(token)) { "Telegram bot tokeni noto'g'ri" }
        for (field in listOf("chatId", "statsChatId")) {
          if (telegram.has(field)) telegram.put(field, telegram.optString(field).trim())
          if (telegram.optString(field).isNotEmpty()) require(Regex("-?[0-9]+|@[A-Za-z0-9_]{5,}").matches(telegram.getString(field))) { "Telegram guruh ID si noto'g'ri" }
        }
        val folderUri = telegram.optString("folderUri")
        if (folderUri.isNotEmpty()) {
          if (Uri.parse(folderUri).scheme == "file") telegram.put("folderUri", Uri.fromFile(OperatorRecordings.localFolder(folderUri)).toString())
          else require(Uri.parse(folderUri).scheme == "content") { "Ovoz yozuvlari papkasini qayta tanlang" }
        }
        val recordings = OperatorRecordings(context)
        try {
          if (telegram.optBoolean("enabled")) require(recordings.configured(telegram)) { "Bot tokeni, guruh ID si va yozuvlar papkasini kiriting" }
          if (telegram.optBoolean("sendCallStats")) require(token.isNotBlank() && telegram.optString("statsChatId").isNotBlank()) { "Qo'ng'iroqlar hisoboti uchun bot va alohida guruh ID si kerak" }
          if (telegram.optBoolean("enabled") && telegram.optBoolean("sendCallStats")) require(telegram.optString("chatId") != telegram.optString("statsChatId")) { "Ovoz yozuvlari va qo'ng'iroqlar hisoboti uchun alohida guruhlarni tanlang" }
          recordings.prepareConfiguration(telegram, oldTelegram)
        } finally { recordings.close() }
        value.put("telegram", telegram)
        telegram.remove("_statsBaseline"); telegram.remove("_statsBaselineAt")
        if (telegram.optBoolean("sendCallStats")) {
          val sameStats = oldTelegram != null && oldTelegram.optBoolean("sendCallStats") &&
            oldTelegram.optString("statsChatId") == telegram.optString("statsChatId") && oldTelegram.optString("botToken").substringBefore(':') == token.substringBefore(':') && oldTelegram.optString("_statsBaseline").isNotBlank()
          telegram.put("_statsBaseline", if (sameStats) oldTelegram!!.getString("_statsBaseline") else UUID.randomUUID().toString())
          telegram.put("_statsBaselineAt", if (sameStats) oldTelegram!!.optLong("_statsBaselineAt") else System.currentTimeMillis())
        }
        OperatorRuntimeStore.saveConfig(context, value)
        OperatorRuntimeStore.update(context) { it.remove("telegramBlocked"); it.remove("telegramError") }
        main.post {
          try {
            if (OperatorRuntimeStore.shouldRun(value)) {
              val service = CallBridgeForegroundService.instance
              if (service != null) service.reload()
              else ContextCompat.startForegroundService(context, Intent(context, CallBridgeForegroundService::class.java))
            } else context.stopService(Intent(context, CallBridgeForegroundService::class.java))
            promise.resolve(null)
          } catch (_: Exception) { promise.reject("START_SERVICE", "Sozlamalar saqlandi. Xizmatni boshlash uchun ilovani qayta oching") }
        }
      } catch (error: Exception) {
        // Only our validation messages may reach JS; never leak HTTP URLs or ciphertext errors.
        val message = if (error is IllegalArgumentException) error.message else "Sozlamalar saqlanmadi. Papka ruxsatini va ma'lumotlarni tekshiring"
        promise.reject("CONFIGURE", message)
      }
    }
  }

  @ReactMethod fun getConfiguration(promise: Promise) {
    work.execute {
      try {
        val value = OperatorRuntimeStore.config(context)
        value.optJSONObject("telegram")?.let { it.remove("botToken"); it.remove("_baseline"); it.remove("_baselineAt"); it.remove("_statsBaseline"); it.remove("_statsBaselineAt") }
        promise.resolve(value.toString())
      } catch (_: Exception) { promise.reject("READ_CONFIG", "Saqlangan sozlamalar ochilmadi") }
    }
  }

  @ReactMethod fun getSnapshot(promise: Promise) {
    main.post {
      val service = CallBridgeForegroundService.instance
      val targets = service?.snapshotTargets()
      val listenerReady = service?.isCallListenerReady()
      work.execute {
        try {
          val config = OperatorRuntimeStore.config(context)
          val state = OperatorRuntimeStore.state(context)
          val connectedTargets = targets ?: JSONArray().apply {
            val configured = config.optJSONArray("targets") ?: JSONArray()
            for (i in 0 until configured.length()) put(JSONObject(configured.getJSONObject(i).toString()).put("status", "disconnected"))
          }
          state.put("running", service != null).put("targets", connectedTargets)
          if (service == null) state.put("startedAt", JSONObject.NULL)
          if (!state.has("lastHeartbeatAt")) state.put("lastHeartbeatAt", JSONObject.NULL)
          if (!state.has("periods")) state.put("periods", JSONArray())
          state.put("phonePermission", listenerReady ?: phonePermissions())
          val historyStats = service?.historySnapshot()
          if (historyStats != null) {
            state.put("callStats", historyStats)
            val ledgerError = if (historyStats.isNull("lastError")) "" else historyStats.optString("lastError")
            if (ledgerError.isNotBlank()) {
              val existing = if (state.isNull("lastError")) "" else state.optString("lastError")
              state.put("lastError", if (existing.isBlank() || existing == ledgerError) ledgerError else "$existing\n$ledgerError")
            }
          }
          val recordings = OperatorRecordings(context)
          try {
            val telegram = recordings.snapshot(config.optJSONObject("telegram") ?: JSONObject())
            if (historyStats != null) {
              for (field in listOf("statsError", "statsPending", "statsLastSentAt")) if (historyStats.has(field)) telegram.put(field, historyStats.get(field))
            }
            state.put("telegram", telegram)
          } finally { recordings.close() }
          state.remove("telegramError"); state.remove("telegramBlocked")
          state.put("update", OperatorUpdater.status(context))
          promise.resolve(state.toString())
        } catch (_: Exception) { promise.reject("SNAPSHOT", "Xizmat holati o'qilmadi") }
      }
    }
  }

  @ReactMethod fun sendTest(promise: Promise) {
    main.post {
      try { promise.resolve(CallBridgeForegroundService.instance?.sendTest() ?: 0) }
      catch (_: Exception) { promise.reject("TEST", "Sinovni qo'ng'iroq tugagach yuboring") }
    }
  }

  private fun phonePermissions() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

  @ReactMethod fun checkAccess(promise: Promise) {
    val allFiles = OperatorRecordings.hasAllFilesAccess(context)
    val installs = OperatorUpdater.canInstallPackages(context)
    val battery = (context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    promise.resolve(JSONObject().put("allFiles", allFiles).put("battery", battery).put("installs", installs).toString())
  }

  /** Checks GitHub now; also retries an install that is waiting for the user (the app is on screen). */
  @ReactMethod fun checkForUpdate(promise: Promise) {
    OperatorUpdater.requestCheck(context)
    val service = CallBridgeForegroundService.instance
    if (service != null) service.checkUpdateNow()
    else work.execute {
      try { OperatorUpdater(context).tick(false, 0L, OperatorRuntimeStore.config(context).optJSONObject("telegram") ?: JSONObject()) { true } }
      catch (_: Exception) { }
    }
    promise.resolve(null)
  }

  @ReactMethod fun openInstallSettings(promise: Promise) {
    if (Build.VERSION.SDK_INT < 26) { promise.resolve(null); return }
    openSettings(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")), promise,
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
  }

  private fun openSettings(intent: Intent, promise: Promise, fallback: Intent? = null) {
    main.post {
      try {
        val activity = currentActivity ?: throw IllegalStateException()
        try { activity.startActivity(intent) } catch (error: Exception) { if (fallback != null) activity.startActivity(fallback) else throw error }
        promise.resolve(null)
      } catch (_: Exception) { promise.reject("OPEN_SETTINGS", "Sozlamalarni ochib bo'lmadi; telefon sozlamalaridan ruxsat bering") }
    }
  }

  @ReactMethod fun requestAllFilesAccess(promise: Promise) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    if (Build.VERSION.SDK_INT >= 30) openSettings(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")), promise, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    else openSettings(details, promise)
  }

  @ReactMethod fun requestBatteryAccess(promise: Promise) = openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")), promise, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

  @ReactMethod fun openBatterySettings(promise: Promise) = openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), promise, Intent(Settings.ACTION_SETTINGS))

  private val knownRecordingFolders = listOf(
    "Recordings/Call", "Call", "Recordings/Call recordings", "Recordings/Call Recordings", "Sounds/Call recordings",
    "Music/Call Recordings", "Call Recordings", "CallRecordings", "Record/Call", "MIUI/sound_recorder/call_rec",
  )

  /** Audio count and newest recording time; bounded so a huge folder cannot stall the worker. */
  private fun describeFolder(root: File, folder: File, depth: Int): JSONObject {
    var count = 0
    var latest = 0L
    var visited = 0
    fun walk(directory: File, level: Int) {
      val children = directory.listFiles() ?: return
      for (child in children) {
        if (++visited > 20_000) return
        if (child.isDirectory) { if (level < depth && !child.name.startsWith(".")) walk(child, level + 1) }
        else if (OperatorRecordings.isAudio(child.name)) { count++; latest = maxOf(latest, child.lastModified()) }
      }
    }
    if (depth >= 0) walk(folder, 0)
    return JSONObject().put("uri", Uri.fromFile(folder).toString()).put("path", folder.path)
      .put("name", folder.path.removePrefix(root.path).trimStart('/')).put("audioCount", count)
      .put("latestAudioAt", latest).put("truncated", visited > 20_000)
  }

  /** Known phone-recorder folders first, then a shallow search for folders named like call recordings. */
  @ReactMethod fun findRecordingFolders(promise: Promise) {
    work.execute {
      try {
        if (!OperatorRecordings.hasAllFilesAccess(context)) {
          promise.resolve(JSONObject().put("access", false).put("folders", JSONArray()).toString())
          return@execute
        }
        val root = OperatorRecordings.storageRoot()
        val found = LinkedHashMap<String, File>()
        for (relative in knownRecordingFolders) File(root, relative).takeIf { it.isDirectory }?.canonicalFile?.let { found.putIfAbsent(it.path, it) }
        val queue = ArrayDeque<Pair<File, Int>>().apply { add(root to 0) }
        var visited = 0
        while (queue.isNotEmpty() && visited < 5_000) {
          val (directory, depth) = queue.removeFirst()
          val children = directory.listFiles() ?: continue
          for (child in children) {
            if (!child.isDirectory || child.name.startsWith(".") || (depth == 0 && child.name == "Android")) continue
            visited++
            if (child.name.contains("call", ignoreCase = true)) child.canonicalFile.let { found.putIfAbsent(it.path, it) }
            if (depth < 3) queue.addLast(child to depth + 1)
          }
        }
        val folders = found.values.filter { it.path.startsWith(root.path + File.separator) }.map { describeFolder(root, it, 3) }
          .sortedWith(compareByDescending<JSONObject> { it.getInt("audioCount") > 0 }.thenByDescending { it.getLong("latestAudioAt") })
        promise.resolve(JSONObject().put("access", true).put("folders", JSONArray(folders.take(10))).toString())
      } catch (_: Exception) { promise.reject("FIND_FOLDERS", "Yozuvlar papkasini qidirib bo'lmadi") }
    }
  }

  /** Read-only folder listing confined to shared storage, for the in-app folder browser. */
  @ReactMethod fun listFolders(path: String?, promise: Promise) {
    work.execute {
      try {
        if (!OperatorRecordings.hasAllFilesAccess(context)) {
          promise.resolve(JSONObject().put("access", false).toString())
          return@execute
        }
        val root = OperatorRecordings.storageRoot()
        val folder = if (path.isNullOrBlank()) root else File(path).canonicalFile
        val isRoot = folder.path == root.path
        require(isRoot || folder.path.startsWith(root.path + File.separator)) { "Papka telefon xotirasida bo'lishi kerak" }
        require(folder.isDirectory) { "Papka topilmadi" }
        val children = (folder.listFiles() ?: throw IOException("unreadable folder"))
          .filter { it.isDirectory && !it.name.startsWith(".") && !(isRoot && it.name == "Android") }
          .sortedBy { it.name.lowercase(Locale.ROOT) }.take(500)
        val result = describeFolder(root, folder, if (isRoot) -1 else 2).put("access", true).put("isRoot", isRoot)
          .put("parent", if (isRoot) JSONObject.NULL else folder.parentFile?.path ?: JSONObject.NULL)
          .put("folders", JSONArray(children.map { JSONObject().put("path", it.path).put("name", it.name) }))
        promise.resolve(result.toString())
      } catch (error: IllegalArgumentException) { promise.reject("LIST_FOLDERS", error.message ?: "Papkani ochib bo'lmadi") }
      catch (_: Exception) { promise.reject("LIST_FOLDERS", "Papkani ochib bo'lmadi") }
    }
  }

  @ReactMethod fun pickRecordingFolder(promise: Promise) {
    main.post {
      // A result lost while Android's picker was open must not block every later attempt.
      folderPromise?.resolve(null)
      folderPromise = null
      try {
        val activity = currentActivity ?: throw IllegalStateException()
        folderPromise = promise
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), folderRequest)
      } catch (_: Exception) { folderPromise = null; promise.reject("PICK_FOLDER", "Papka tanlash oynasi ochilmadi") }
    }
  }
}
