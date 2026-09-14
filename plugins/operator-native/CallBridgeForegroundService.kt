package __PACKAGE__

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class CallBridgeForegroundService : Service() {
  companion object {
    @Volatile var instance: CallBridgeForegroundService? = null
      private set
    private const val CHANNEL = "smart_pos_operator_runtime"
    private const val NOTIFICATION = 7651
  }
  private val main = Handler(Looper.getMainLooper())
  private val networkPool = Executors.newFixedThreadPool(2)
  private val recordingWorker = Executors.newSingleThreadScheduledExecutor()
  private val callWorker = Executors.newSingleThreadScheduledExecutor()
  private val ledgerWorker = Executors.newSingleThreadExecutor()
  private val websocketHttp = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
  private val peers = LinkedHashMap<String, Peer>()
  @Volatile private var config = JSONObject()
  @Volatile private var callInProgress = false
  @Volatile private var hasPhonePermissions = false
  private var answered = false
  private var phone = ""
  private var direction = "in"
  private var announced = false
  private var listening = false
  private var lastState = TelephonyManager.CALL_STATE_IDLE
  private var wakeLock: PowerManager.WakeLock? = null
  private lateinit var recordings: OperatorRecordings
  private lateinit var history: OperatorCallHistory
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var lastHeartbeat = 0L
  private var destroyed = false

  private class Peer(val id: String, var name: String, val configuredUrl: String, val discoveryPort: Int) {
    var url = configuredUrl
    var socket: WebSocket? = null
    var status = "disconnected"
    var error: String? = null
    var lastConnectedAt: Long? = null
    var retryAt = 0L
    var attempt = 0
    var discoveryAt = 0L
    var discovering = false
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    instance = this
    recordings = OperatorRecordings(this)
    // Must post the visible foreground notification before any disk/network work.
    startForegroundNotification()
    history = OperatorCallHistory(this)
    OperatorRuntimeStore.began(this)
    wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:operator").apply {
      setReferenceCounted(false)
      acquire()
    }
    try {
      val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
      networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { peers.values.forEach { it.retryAt = 0; it.discoveryAt = 0 }; tick() } }
        override fun onLost(network: Network) { main.post { peers.values.filter { it.status != "connected" }.forEach { it.retryAt = 0 } } }
      }.also { manager.registerDefaultNetworkCallback(it) }
    } catch (_: Exception) { /* Periodic reconnect remains active if callback is unavailable. */ }
    main.post(ticker)
    recordingWorker.scheduleWithFixedDelay({
      try {
        val telegram = config.optJSONObject("telegram") ?: return@scheduleWithFixedDelay
        recordings.scan(telegram, callInProgress, hasPhonePermissions)
        if (!callInProgress) recordings.uploadNext(telegram)
      } catch (_: Exception) {
        OperatorRuntimeStore.update(this) { it.put("telegramError", "Yozuvlarni tekshirishda xato; papka va ruxsatlarni tekshiring") }
      }
    }, 10, 15, TimeUnit.SECONDS)
    callWorker.scheduleWithFixedDelay({
      try {
        history.tick(config.optJSONObject("telegram") ?: JSONObject())
        main.post { if (!destroyed) peers.values.filter { it.status == "connected" }.forEach { sendCallRecords(it) } }
      } catch (_: Exception) { OperatorRuntimeStore.setError(this, "Qo'ng'iroqlar hisobotini yangilab bo'lmadi; ruxsatlarni tekshiring") }
    }, 5, 15, TimeUnit.SECONDS)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    reload()
    return START_STICKY
  }

  fun reload() {
    if (destroyed) return
    try {
      val next = OperatorRuntimeStore.config(this)
      if (config.optJSONObject("telegram")?.toString() != next.optJSONObject("telegram")?.toString()) recordings.cancelUploads()
      config = next
    } catch (_: Exception) {
      OperatorRuntimeStore.setError(this, "Saqlangan sozlamalar ochilmadi; ilovada sozlamalarni tekshiring")
      stopSelf(); return
    }
    if (!OperatorRuntimeStore.shouldRun(config)) { stopSelf(); return }
    val targets = config.optJSONArray("targets") ?: JSONArray()
    val desired = HashSet<String>()
    for (i in 0 until targets.length()) {
      val target = targets.getJSONObject(i)
      val id = target.getString("id")
      desired.add(id)
      val old = peers[id]
      // Preserve discovered addresses on routine saves; accept a newly scanned endpoint immediately.
      if (old == null || old.configuredUrl != target.getString("url") || old.discoveryPort != target.optInt("discoveryPort", 8766)) {
        old?.socket?.cancel()
        peers[id] = Peer(id, target.optString("name", "POS"), target.getString("url"), target.optInt("discoveryPort", 8766))
      } else old.name = target.optString("name", "POS")
    }
    peers.keys.filter { it !in desired }.forEach { peers.remove(it)?.socket?.close(1000, "Removed") }
    refreshCallPermission()
    tick()
  }

  private val listener = object : PhoneStateListener() {
    override fun onCallStateChanged(state: Int, incomingNumber: String?) {
      if (destroyed || !hasPhonePermissions) return
      val number = incomingNumber?.trim().orEmpty()
      history.onPhoneState(state, number)
      when (state) {
        TelephonyManager.CALL_STATE_RINGING -> {
          // A waiting call must not overwrite the original active caller on the POS.
          if (!callInProgress) { callInProgress = true; answered = false; direction = "in"; phone = number }
          else if (!answered && phone.isBlank() && number.isNotBlank()) phone = number
          announceIfKnown()
        }
        TelephonyManager.CALL_STATE_OFFHOOK -> {
          if (!callInProgress) { callInProgress = true; direction = "out"; phone = number }
          answered = true
          announceIfKnown()
        }
        TelephonyManager.CALL_STATE_IDLE -> {
          if (announced) broadcast(JSONObject().put("type", "call_end").put("phone", phone))
          callInProgress = false; answered = false; announced = false; phone = ""
        }
      }
      lastState = state
    }
  }

  private fun announceIfKnown() {
    if (announced || phone.isBlank()) return
    announced = true
    broadcast(JSONObject().put("type", "call_start").put("phone", phone).put("direction", direction))
  }

  private fun refreshCallPermission() {
    val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    hasPhonePermissions = allowed
    if (allowed && !listening) {
      try {
        (getSystemService(TELEPHONY_SERVICE) as TelephonyManager).listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        listening = true
        OperatorRuntimeStore.update(this) { it.remove("lastError") }
      } catch (_: Exception) { hasPhonePermissions = false; OperatorRuntimeStore.setError(this, "Qo'ng'iroqlarni kuzatib bo'lmadi; telefon ruxsatlarini tekshiring") }
    } else if (!allowed && listening) {
      try { (getSystemService(TELEPHONY_SERVICE) as TelephonyManager).listen(listener, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) { }
      listening = false
      callInProgress = false; announced = false; phone = ""
    }
    if (!allowed && peers.isNotEmpty()) OperatorRuntimeStore.setError(this, "Telefon va qo'ng'iroqlar jurnaliga ruxsat kerak")
  }

  private val ticker = object : Runnable {
    override fun run() { if (!destroyed) { tick(); main.postDelayed(this, 5_000) } }
  }

  private fun tick() {
    if (destroyed) return
    refreshCallPermission()
    val now = System.currentTimeMillis()
    for (peer in peers.values) {
      if (peer.status == "disconnected" && now >= peer.retryAt) connect(peer)
      if (peer.status != "connected" && now >= peer.discoveryAt && !peer.discovering) discover(peer)
    }
    if (now - lastHeartbeat >= 15_000) {
      lastHeartbeat = now
      OperatorRuntimeStore.update(this) { it.put("lastHeartbeatAt", now).put("phonePermission", hasPhonePermissions) }
      updateNotification()
    }
  }

  private fun connect(peer: Peer) {
    if (peers[peer.id] !== peer || destroyed) return
    peer.status = "connecting"
    try {
      peer.socket = websocketHttp.newWebSocket(Request.Builder().url(peer.url).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { main.post {
          if (destroyed || peers[peer.id] !== peer || peer.socket !== webSocket) { webSocket.cancel(); return@post }
          peer.status = "connected"; peer.attempt = 0; peer.error = null; peer.lastConnectedAt = System.currentTimeMillis()
          // Only current calls are replayed. Finished calls are never replayed as new orders.
          if (callInProgress && announced) webSocket.send(JSONObject().put("type", "call_start").put("phone", phone).put("direction", direction).toString())
          sendCallRecords(peer)
          updateNotification()
        } }
        override fun onMessage(webSocket: WebSocket, text: String) {
          if (text.length > 16_384) return
          main.post {
            if (destroyed || peers[peer.id] !== peer || peer.socket !== webSocket) return@post
            try {
              val message = JSONObject(text)
              when (message.optString("type")) {
                "customer_name" -> ledgerWorker.execute { try { history.enrichCustomer(message.optString("phone"), message.optString("name")) } catch (_: Exception) { } }
                "call_record_ack" -> ledgerWorker.execute { try { history.acknowledge(peer.id, message.optString("id"), message.optLong("revision")) } catch (_: Exception) { } }
              }
            } catch (_: Exception) { }
          }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post { disconnected(peer, webSocket, "POS bilan aloqa uzildi") } }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { main.post {
          disconnected(peer, webSocket, if (response?.code == 401 || response?.code == 403) "Ulanish kaliti rad etildi; POS QR kodini qayta qo'shing" else "POS mavjud emas; tarmoq va operator rejimini tekshiring")
        } }
      })
    } catch (_: Exception) {
      peer.status = "disconnected"; peer.error = "POS manzili noto'g'ri"; peer.retryAt = System.currentTimeMillis() + 30_000
    }
  }

  private fun disconnected(peer: Peer, socket: WebSocket, error: String) {
    if (destroyed || peers[peer.id] !== peer || peer.socket !== socket) return
    peer.socket = null; peer.status = "disconnected"; peer.error = error
    peer.retryAt = System.currentTimeMillis() + minOf(30_000L, 1_000L * (1L shl minOf(peer.attempt++, 5)))
    updateNotification()
  }

  private fun discover(peer: Peer) {
    peer.discovering = true; peer.discoveryAt = System.currentTimeMillis() + 30_000
    networkPool.execute {
      var address: String? = null
      try {
        DatagramSocket().use { socket ->
          socket.broadcast = true; socket.soTimeout = 1800
          val nonce = UUID.randomUUID().toString()
          val bytes = JSONObject().put("type", "operator_discover").put("id", peer.id).put("nonce", nonce).toString().toByteArray(Charsets.UTF_8)
          val destinations = LinkedHashSet<InetAddress>()
          destinations.add(InetAddress.getByName("255.255.255.255"))
          NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { network -> network.interfaceAddresses.mapNotNullTo(destinations) { it.broadcast } }
          destinations.forEach { try { socket.send(DatagramPacket(bytes, bytes.size, it, peer.discoveryPort)) } catch (_: Exception) { } }
          val deadline = System.currentTimeMillis() + 1800
          while (System.currentTimeMillis() < deadline && address == null) {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            socket.receive(packet)
            val reply = try { JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8)) } catch (_: Exception) { continue }
            if (reply.optString("type") == "operator_discovered" && reply.optString("id") == peer.id && reply.optString("nonce") == nonce && reply.optInt("port") == 8765) address = packet.address.hostAddress
          }
        }
      } catch (_: Exception) { /* Discovery is best effort; original URL always continues retrying. */ }
      main.post {
        peer.discovering = false
        if (destroyed || peers[peer.id] !== peer || address == null || peer.status == "connected") return@post
        try {
          val old = URI(peer.url)
          val resolved = URI(old.scheme, null, address, 8765, old.path, old.query, null).toString()
          if (resolved != peer.url) {
            peer.socket?.cancel(); peer.socket = null; peer.url = resolved; peer.status = "disconnected"; peer.retryAt = 0
            connect(peer)
          }
        } catch (_: Exception) { }
      }
    }
  }

  private fun broadcast(message: JSONObject): Int {
    var count = 0
    for (peer in peers.values) if (peer.status == "connected" && peer.socket?.send(message.toString()) == true) count++
    return count
  }

  private fun sendCallRecords(peer: Peer) {
    try {
      for (record in history.pendingRecords(peer.id)) {
        if (peer.socket?.send(JSONObject().put("type", "call_record").put("record", record).toString()) != true) break
      }
    } catch (_: Exception) { }
  }

  fun historySnapshot(): JSONObject = history.snapshot()
  fun isCallListenerReady(): Boolean = !destroyed && hasPhonePermissions && listening

  /** This count means accepted by connected sockets, not an acknowledgement from desktop. */
  fun sendTest(): Int {
    if (callInProgress) throw IllegalStateException("Sinovni qo'ng'iroq tugagach yuboring")
    val testPhone = "+998900000000"
    val message = JSONObject().put("type", "call_start").put("phone", testPhone).put("direction", "in").put("test", true)
    val delivered = peers.values.filter { it.status == "connected" && it.socket?.send(message.toString()) == true }.mapNotNull { it.socket }
    main.postDelayed({
      val end = JSONObject().put("type", "call_end").put("phone", testPhone).put("test", true).toString()
      delivered.forEach { it.send(end) }
    }, 1500)
    return delivered.size
  }

  fun snapshotTargets(): JSONArray {
    val result = JSONArray()
    peers.values.forEach { peer ->
      val target = JSONObject().put("id", peer.id).put("name", peer.name).put("url", peer.url).put("status", peer.status)
      peer.lastConnectedAt?.let { target.put("lastConnectedAt", it) }
      peer.error?.let { target.put("error", it) }
      result.put(target)
    }
    return result
  }

  private fun notification(): android.app.Notification {
    val connected = peers.values.count { it.status == "connected" }
    val status = if (!hasPhonePermissions && peers.isNotEmpty()) "Telefon ruxsatlari kerak" else if (peers.isNotEmpty()) "$connected / ${peers.size} POS ulangan" else "Yangi ovoz yozuvlari kuzatilmoqda"
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val pending = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
    return NotificationCompat.Builder(this, CHANNEL).setContentTitle("Smart POS Operator").setContentText(status)
      .setSmallIcon(applicationInfo.icon).setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW)
      .setContentIntent(pending).build()
  }

  private fun startForegroundNotification() {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Operator xizmati", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    else startForeground(NOTIFICATION, notification())
  }
  private fun updateNotification() { if (!destroyed) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification()) }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // stopWithTask=false and START_STICKY preserve the native runtime after the UI is swiped away.
    OperatorRuntimeStore.update(this) { it.put("lastHeartbeatAt", System.currentTimeMillis()) }
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    destroyed = true
    instance = null
    main.removeCallbacksAndMessages(null)
    peers.values.forEach { it.socket?.cancel() }; peers.clear()
    try { (getSystemService(TELEPHONY_SERVICE) as TelephonyManager).listen(listener, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) { }
    networkCallback?.let { try { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) { } }
    recordings.cancelUploads()
    history.close()
    recordingWorker.shutdownNow(); callWorker.shutdownNow(); ledgerWorker.shutdownNow(); networkPool.shutdownNow(); websocketHttp.dispatcher.cancelAll()
    if (wakeLock?.isHeld == true) wakeLock?.release()
    OperatorRuntimeStore.ended(this, "Xizmat to'xtadi")
    super.onDestroy()
  }
}
