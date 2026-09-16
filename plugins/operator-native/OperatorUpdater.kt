package __PACKAGE__

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Receives PackageInstaller results for our own self-update sessions. */
class OperatorUpdateReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == OperatorUpdater.ACTION_RESULT) OperatorUpdater.onResult(context, intent)
  }
}

/**
 * Self-update from the public GitHub releases repository. Android 12+ installs our own
 * update without a prompt when "Install unknown apps" is allowed for this app; otherwise
 * Android asks once and we show a notification. The APK is checked against the release
 * SHA-256, our package name, the announced version and our current signing certificate.
 */
class OperatorUpdater(private val context: Context) {
  companion object {
    const val ACTION_RESULT = "__PACKAGE__.OPERATOR_UPDATE_RESULT"
    private const val CHANNEL = "smart_pos_operator_updates"
    private const val NOTIFICATION = 7652
    private val lock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences("operator_updater_v1", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    fun installedVersion(context: Context): Pair<Long, String> {
      val info = context.packageManager.getPackageInfo(context.packageName, 0)
      val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
      return code to (info.versionName ?: "")
    }

    fun canInstallPackages(context: Context): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    private fun isEmulator(): Boolean = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk") || Build.HARDWARE.contains("ranchu")

    fun source(context: Context): String {
      val override = prefs(context).getString("test_source", null)
      return if (override != null && isEmulator() && OperatorUpdatePolicy.isTestSource(override)) override else OperatorUpdatePolicy.DEFAULT_SOURCE
    }

    /** Emulator regression tests only: serve updates from the host machine. */
    fun setTestSource(context: Context, source: String?) {
      check(isEmulator()) { "Test update source is emulator-only" }
      val edit = prefs(context).edit().putLong("next_check", 0L)
      if (source == null) edit.remove("test_source") else { require(OperatorUpdatePolicy.isTestSource(source)); edit.putString("test_source", source) }
      check(edit.commit())
    }

    fun state(context: Context): String = prefs(context).getString("state", "idle") ?: "idle"

    fun requestCheck(context: Context) { prefs(context).edit().putBoolean("check_requested", true).commit() }

    /**
     * Records a finished self-update as soon as the new version runs; the installer's success
     * broadcast rarely arrives because the old process is killed. Idempotent and lock-free, so
     * status reads never wait behind a download.
     */
    fun finishIfInstalled(context: Context) {
      val p = prefs(context)
      val pending = p.getLong("pending_code", 0L)
      if (pending <= 0L) return
      val (code, name) = installedVersion(context)
      if (code < pending) return
      p.edit().remove("pending_code").putString("state", "idle").remove("error").putInt("failures", 0)
        .putString("report_text", "✅ Smart POS Operator avtomatik yangilandi: $name versiyasi o'rnatildi.").commit()
      File(context.filesDir, "updates").listFiles()?.forEach { it.delete() }
      (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION)
    }

    fun status(context: Context): JSONObject {
      finishIfInstalled(context)
      val p = prefs(context)
      val (code, name) = installedVersion(context)
      return JSONObject().put("installedCode", code).put("installedName", name).put("state", state(context))
        .put("latestCode", p.getLong("latest_code", 0L)).put("latestName", p.getString("latest_name", "") ?: "")
        .put("notes", p.getString("latest_notes", "") ?: "").put("lastCheckAt", p.getLong("last_check", 0L))
        .put("nextCheckAt", p.getLong("next_check", 0L)).put("downloaded", p.getLong("downloaded", 0L))
        .put("size", p.getLong("latest_size", 0L)).put("error", p.getString("error", "") ?: "")
        .put("canInstallPackages", canInstallPackages(context))
    }

    /** A successful self-update normally kills this process before this broadcast arrives. */
    fun onResult(context: Context, intent: Intent) {
      when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
          @Suppress("DEPRECATION")
          val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
          val reason = if (canInstallPackages(context)) "Android yangilashni tasdiqlashni so'rayapti"
            else "Avtomatik yangilash uchun \"Noma'lum ilovalarni o'rnatish\" ruxsatini bering"
          prefs(context).edit().putString("state", "waiting_user").putString("error", reason).commit()
          if (confirm != null) askUser(context, confirm)
        }
        PackageInstaller.STATUS_SUCCESS -> prefs(context).edit().putString("state", "installed").commit()
        else -> fail(context, when (status) {
          PackageInstaller.STATUS_FAILURE_ABORTED -> "O'rnatish bekor qilindi"
          PackageInstaller.STATUS_FAILURE_BLOCKED -> "Telefon o'rnatishni blokladi"
          PackageInstaller.STATUS_FAILURE_CONFLICT -> "Ilova imzosi yoki versiyasi mos kelmadi"
          PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Yangi versiya bu telefonga mos emas"
          PackageInstaller.STATUS_FAILURE_INVALID -> "Yangilanish fayli buzilgan"
          PackageInstaller.STATUS_FAILURE_STORAGE -> "Telefon xotirasida joy yetarli emas"
          else -> "Yangilanishni o'rnatib bo'lmadi"
        }, report = true)
      }
      CallBridgeForegroundService.instance?.updateFinished()
    }

    private fun askUser(context: Context, confirm: Intent) {
      confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      // Works while the app is on screen (e.g. "Hozir yangilash"); otherwise the notification remains.
      try { context.startActivity(confirm) } catch (_: Exception) { }
      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ilova yangilanishlari", NotificationManager.IMPORTANCE_HIGH))
      val pending = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val name = prefs(context).getString("latest_name", "") ?: ""
      manager.notify(NOTIFICATION, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(context.applicationInfo.icon)
        .setContentTitle("Operator $name yangilanishi tayyor").setContentText("O'rnatish uchun bosing")
        .setContentIntent(pending).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

    private fun fail(context: Context, reason: String, report: Boolean) {
      val p = prefs(context)
      val failures = p.getInt("failures", 0) + 1
      val edit = p.edit().putString("state", "error").putString("error", reason).putInt("failures", failures)
        .putLong("next_check", System.currentTimeMillis() + OperatorUpdatePolicy.retryDelayMs(failures)).remove("pending_code")
      val code = p.getLong("latest_code", 0L)
      if (report && p.getLong("reported_failure_code", 0L) != code) {
        edit.putLong("reported_failure_code", code)
          .putString("report_text", "⚠️ Smart POS Operator ${p.getString("latest_name", "")} yangilanishi o'rnatilmadi: $reason. Ilova keyinroq qayta urinadi.")
      }
      edit.commit()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
  }

  private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
  private val directory = File(context.filesDir, "updates")

  /**
   * Called about once a minute by the service worker. Checks every 30 minutes (or when asked),
   * downloads a newer build, and installs it only when [prepareInstall] confirms no call and no
   * Telegram upload is in flight.
   */
  fun tick(callActive: Boolean, lastCallEndedAt: Long, telegram: JSONObject, prepareInstall: () -> Boolean) = synchronized(lock) {
    val p = prefs(context)
    val now = System.currentTimeMillis()
    val (installedCode, _) = installedVersion(context)
    reportOutcome(telegram)
    if (state(context) == "installing") {
      if (now - p.getLong("install_started", 0L) < OperatorUpdatePolicy.INSTALL_TIMEOUT_MS) return@synchronized
      fail(context, "Yangilanish o'rnatilmadi; qayta uriniladi", report = true)
    }
    val requested = p.getBoolean("check_requested", false)
    if (requested || now >= p.getLong("next_check", 0L) || now < p.getLong("last_check", 0L)) {
      p.edit().putBoolean("check_requested", false).commit()
      checkRelease(installedCode, now)
    }
    val ready = readyFile(installedCode) ?: return@synchronized
    if (state(context) == "error" && !requested && now < p.getLong("next_check", 0L)) return@synchronized
    // Android already asked the user; retry silently only after the install permission appears.
    if (state(context) == "waiting_user" && !requested &&
      !(canInstallPackages(context) && now - p.getLong("install_started", 0L) > OperatorUpdatePolicy.CHECK_INTERVAL_MS)) return@synchronized
    if (!OperatorUpdatePolicy.canInstallNow(callActive, lastCallEndedAt, now) || !prepareInstall()) return@synchronized
    try { install(ready) } catch (error: Exception) {
      fail(context, (error as? IllegalArgumentException)?.message ?: "Yangilanishni o'rnatib bo'lmadi", report = true)
    }
  }

  /** Fetches update.json and downloads a newer, verified APK. */
  private fun checkRelease(installedCode: Long, now: Long) {
    val p = prefs(context)
    val source = source(context)
    p.edit().putString("state", "checking").putLong("last_check", now).putLong("next_check", now + OperatorUpdatePolicy.CHECK_INTERVAL_MS).commit()
    try {
      val manifest = http.newCall(Request.Builder().url(source).header("Cache-Control", "no-cache").build()).execute().use { response ->
        if (response.code == 404) { p.edit().putString("state", "idle").remove("error").commit(); return }  // Nothing published yet.
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val text = response.body?.string().orEmpty()
        require(text.length in 2..16_384) { "Yangilanish ma'lumoti noto'g'ri" }
        JSONObject(text)
      }
      val code = manifest.optLong("versionCode")
      val name = manifest.optString("versionName")
      val url = manifest.optString("apkUrl")
      val sha = manifest.optString("sha256").lowercase()
      val size = manifest.optLong("size")
      OperatorUpdatePolicy.validate(code, name, url, sha, size, source)?.let { throw IllegalArgumentException(it) }
      p.edit().putLong("latest_code", code).putString("latest_name", name).putString("latest_notes", manifest.optString("notes").take(500))
        .putLong("latest_size", size).putString("latest_sha", sha).commit()
      if (!OperatorUpdatePolicy.isNewer(code, installedCode)) {
        p.edit().putString("state", "idle").remove("error").putInt("failures", 0).commit()
        cleanup(keep = null)
        return
      }
      val target = File(directory, "operator-$code.apk")
      if (!(target.isFile && target.length() == size && sha256(target) == sha)) download(url, sha, size, target)
      verifyArchive(target, code)
      p.edit().putString("state", "ready").putLong("downloaded", size).remove("error").commit()
    } catch (error: Exception) {
      fail(context, (error as? IllegalArgumentException)?.message ?: "Yangilanishni tekshirib bo'lmadi; internet tiklangach qayta uriniladi", report = false)
    }
  }

  private fun readyFile(installedCode: Long): File? {
    val p = prefs(context)
    val code = p.getLong("latest_code", 0L)
    if (!OperatorUpdatePolicy.isNewer(code, installedCode)) return null
    val file = File(directory, "operator-$code.apk")
    return file.takeIf { it.isFile && it.length() == p.getLong("latest_size", -1L) }
  }

  private fun download(url: String, sha: String, size: Long, target: File) {
    directory.mkdirs()
    cleanup(keep = null)
    val part = File(directory, target.name + ".part")
    prefs(context).edit().putString("state", "downloading").putLong("downloaded", 0L).commit()
    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
      if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
      val digest = MessageDigest.getInstance("SHA-256")
      var total = 0L
      var reported = 0L
      (response.body ?: throw IOException("empty body")).byteStream().use { input ->
        part.outputStream().use { output ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= size) { "Yuklab olingan fayl kutilganidan katta" }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            if (total - reported >= 1_000_000) { reported = total; prefs(context).edit().putLong("downloaded", total).apply() }
          }
        }
      }
      require(total == size && hex(digest.digest()) == sha) { "Yuklab olingan fayl tekshiruvdan o'tmadi" }
    }
    target.delete()
    if (!part.renameTo(target)) throw IOException("rename failed")
  }

  @Suppress("DEPRECATION")
  private fun verifyArchive(file: File, code: Long) {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = pm.getPackageArchiveInfo(file.path, flags)
    require(archive != null && archive.packageName == context.packageName) { "Yangilanish fayli boshqa ilovaga tegishli" }
    val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    require(archiveCode == code) { "Yangilanish versiyasi e'londagi bilan mos emas" }
    val current = signers(pm.getPackageInfo(context.packageName, flags), history = false)
    val offered = signers(archive, history = true)
    // If Android cannot report archive signers, PackageInstaller still enforces signer continuity.
    if (current.isNotEmpty() && offered.isNotEmpty()) require(offered.containsAll(current)) { "Yangilanish boshqa kalit bilan imzolangan" }
  }

  @Suppress("DEPRECATION")
  private fun signers(info: PackageInfo, history: Boolean): Set<String> {
    val raw = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let { signing ->
      if (history && !signing.hasMultipleSigners()) signing.signingCertificateHistory else signing.apkContentsSigners
    } else info.signatures
    return raw.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
  }

  private fun install(file: File) {
    val p = prefs(context)
    require(sha256(file) == p.getString("latest_sha", "")) { "Yangilanish fayli o'zgargan; qayta yuklab olinadi" }
    val installer = context.packageManager.packageInstaller
    for (session in installer.mySessions) try { installer.abandonSession(session.sessionId) } catch (_: Exception) { }
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
      setAppPackageName(context.packageName)
      setSize(file.length())
      if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
    }
    p.edit().putString("state", "installing").putLong("install_started", System.currentTimeMillis())
      .putLong("pending_code", p.getLong("latest_code", 0L)).remove("error").commit()
    val sessionId = installer.createSession(params)
    try {
      installer.openSession(sessionId).use { session ->
        file.inputStream().use { input ->
          session.openWrite("operator.apk", 0, file.length()).use { output -> input.copyTo(output, 64 * 1024); session.fsync(output) }
        }
        val intent = Intent(context, OperatorUpdateReceiver::class.java).setAction(ACTION_RESULT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
      }
    } catch (error: Exception) {
      try { installer.abandonSession(sessionId) } catch (_: Exception) { }
      throw error
    }
  }

  /** Reports a finished or failed update to the report group; retried until Telegram accepts it. */
  private fun reportOutcome(telegram: JSONObject) {
    val p = prefs(context)
    finishIfInstalled(context)
    val text = p.getString("report_text", null) ?: return
    val chat = telegram.optString("statsChatId").ifBlank { telegram.optString("chatId") }
    val token = telegram.optString("botToken")
    if (chat.isBlank() || token.isBlank()) return
    try {
      val body = JSONObject().put("chat_id", chat).put("text", text).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
      http.newCall(Request.Builder().url("https://api.telegram.org/bot$token/sendMessage").post(body).build()).execute().use { response ->
        if (response.isSuccessful) p.edit().remove("report_text").commit()
      }
    } catch (_: Exception) { /* Retried next tick; never log the token-bearing URL. */ }
  }

  private fun cleanup(keep: String?) {
    directory.listFiles()?.forEach { if (it.name != keep) it.delete() }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
    }
    return hex(digest.digest())
  }
}
