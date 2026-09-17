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
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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
import java.util.TimeZone
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
    private const val PROTOCOL = 3
    private const val HANDSHAKE_TIMEOUT_MS = 15_000L
    private const val RECENT_CALLS_MS = 10 * 60_000L
  }
  private val main = Handler(Looper.getMainLooper())
  private val networkPool = Executors.newFixedThreadPool(2)
  private val recordingWorker = Executors.newSingleThreadScheduledExecutor()
  private val callWorker = Executors.newSingleThreadScheduledExecutor()
  private val ledgerWorker = Executors.newSingleThreadExecutor()
  private val updateWorker = Executors.newSingleThreadScheduledExecutor()
  private val telegramWorker = Executors.newSingleThreadScheduledExecutor()
  private var botThread: Thread? = null
  private val websocketHttp = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
  private val peers = LinkedHashMap<String, Peer>()
  @Volatile private var config = JSONObject()
  @Volatile private var callInProgress = false
  @Volatile private var hasPhonePermissions = false
  @Volatile private var lastCallEndedAt = 0L
  /** Set while a self-update is about to restart the app; Telegram sends wait for the new process. */
  @Volatile private var pausedForUpdate = false
  @Volatile private var networkUp = true
  @Volatile private var peerHealth: List<OperatorPeerHealth> = emptyList()
  private var answered = false
  private var phone = ""
  private var direction = "in"
  private var announced = false
  private var listening = false
  private var lastState = TelephonyManager.CALL_STATE_IDLE
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null
  private lateinit var recordings: OperatorRecordings
  private lateinit var history: OperatorCallHistory
  private lateinit var updater: OperatorUpdater
  private lateinit var supervisor: OperatorSupervisor
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var lastHeartbeat = 0L
  private var lastUptimeTick = 0L
  private var destroyed = false
  private val liveCalls = ArrayList<LiveCall>()
  private val names = HashMap<String, String>()

  private class Peer(val id: String, var name: String, val configuredUrl: String, val discoveryPort: Int, var role: String) {
    var url = configuredUrl
    var socket: WebSocket? = null
    var status = "disconnected"
    var error: String? = null
    var lastConnectedAt: Long? = null
    var connectingSince = 0L
    var retryAt = 0L
    var attempt = 0
    var discoveryAt = 0L
    var discovering = false
  }

  /** A call the POS can offer for one-tap number entry. Main thread only. */
  private class LiveCall(val id: String, var phone: String, val direction: String, var state: String, val since: Long, var endedAt: Long? = null)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    instance = this
    recordings = OperatorRecordings(this)
    // Must post the visible foreground notification before any disk/network work.
    startForegroundNotification()
    history = OperatorCallHistory(this)
    supervisor = OperatorSupervisor(this, history, recordings)
    // Owner bot commands change the stored configuration; apply it like an app save.
    supervisor.configChanged = { main.post { reload() } }
    updater = OperatorUpdater(this)
    OperatorUpdater.finishIfInstalled(this)
    OperatorRuntimeStore.began(this)
    wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:operator").apply {
      setReferenceCounted(false)
      acquire()
    }
    try {
      // Keeps Wi-Fi awake with the screen off so POS connections survive in the background.
      wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:pos").apply { setReferenceCounted(false); acquire() }
    } catch (_: Exception) { }
    try {
      val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
      networkUp = manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
      networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          networkUp = true
          main.post { peers.values.forEach { it.retryAt = 0; it.discoveryAt = 0 }; tick() }
          retryPendingNow()
        }
        override fun onLost(network: Network) {
          networkUp = false
          main.post { peers.values.filter { it.status != "connected" }.forEach { it.retryAt = 0 } }
        }
      }.also { manager.registerDefaultNetworkCallback(it) }
    } catch (_: Exception) { /* Periodic reconnect remains active if callback is unavailable. */ }
    main.post(ticker)
    recordingWorker.scheduleWithFixedDelay({ processRecordings() }, 10, 15, TimeUnit.SECONDS)
    callWorker.scheduleWithFixedDelay({ processCallHistory() }, 5, 15, TimeUnit.SECONDS)
    telegramWorker.scheduleWithFixedDelay({ drainTelegram() }, 3, 2, TimeUnit.SECONDS)
    updateWorker.scheduleWithFixedDelay({ runUpdater() }, OperatorUpdatePolicy.FIRST_CHECK_DELAY_MS, 60_000, TimeUnit.MILLISECONDS)
    botThread = Thread({ pollBotLoop() }, "operator-bot").apply { isDaemon = true; start() }
    // A restart may follow an update, a crash or a reboot: resend everything still unsent right away.
    retryPendingNow()
  }

  private fun processRecordings() {
    try {
      val telegram = config.optJSONObject("telegram") ?: return
      recordings.scan(telegram, callInProgress, hasPhonePermissions)
    } catch (_: Exception) {
      OperatorRuntimeStore.update(this) { it.put("telegramError", "Yozuvlarni tekshirishda xato; papka va ruxsatlarni tekshiring") }
    }
  }

  private fun processCallHistory() {
    if (pausedForUpdate) return
    try {
      val current = config
      val shifts = OperatorSettings.parseShifts(current.optJSONArray("shifts"))
      history.tick(shifts, TimeZone.getDefault())
      supervisor.tick(current, OperatorEnvironment(callInProgress, peerHealth, networkUp, appVersion(), lastCallEndedAt))
      main.post { if (!destroyed) peers.values.filter { it.status == "connected" }.forEach { sendCallRecords(it) } }
    } catch (_: Exception) { OperatorRuntimeStore.setError(this, "Qo'ng'iroqlar hisobotini yangilab bo'lmadi; ruxsatlarni tekshiring") }
  }

  private fun drainTelegram() {
    if (pausedForUpdate || destroyed) return
    try { supervisor.drain(config) } catch (_: Exception) { }
  }

  private fun pollBotLoop() {
    while (!destroyed) {
      val ok = try { supervisor.pollBot(config) } catch (_: Exception) { false }
      if (!ok) try { Thread.sleep(30_000) } catch (_: InterruptedException) { return }
    }
  }

  private fun appVersion(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

  /** Makes queued messages due now; used after a restart and when internet returns. */
  private fun retryPendingNow() {
    if (destroyed) return
    try {
      telegramWorker.execute { try { supervisor.retryNow() } catch (_: Exception) { }; drainTelegram() }
      recordingWorker.execute { processRecordings() }
      callWorker.execute { processCallHistory() }
    } catch (_: Exception) { /* Executors are shut down while the service is being destroyed. */ }
  }

  private fun runUpdater() {
    try {
      updater.tick(callInProgress, lastCallEndedAt) { pauseForUpdate() }
    } catch (_: Exception) { }
    if (OperatorUpdater.state(this) != "installing") pausedForUpdate = false
  }

  /** Waits for in-flight Telegram sends so the update restart cannot cut or duplicate them. */
  private fun pauseForUpdate(): Boolean {
    pausedForUpdate = true
    try {
      telegramWorker.submit(Runnable {}).get(5, TimeUnit.MINUTES)
      callWorker.submit(Runnable {}).get(2, TimeUnit.MINUTES)
    } catch (_: Exception) { pausedForUpdate = false; return false }
    if (callInProgress) { pausedForUpdate = false; return false }
    return true
  }

  /** Install failed or needs the user: resume normal sending. */
  fun updateFinished() { pausedForUpdate = false }

  fun checkUpdateNow() {
    try { updateWorker.execute { runUpdater() } } catch (_: Exception) { }
  }

  fun supervisorSnapshot(): JSONObject = supervisor.snapshot()

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    reload()
    return START_STICKY
  }

  fun reload() {
    if (destroyed) return
    try {
      val next = OperatorRuntimeStore.config(this)
      if (config.optJSONObject("telegram")?.toString() != next.optJSONObject("telegram")?.toString()) supervisor.telegram.cancel()
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
      val role = if (target.optString("role") == "cashier") "cashier" else "operator"
      desired.add(id)
      val old = peers[id]
      // Preserve discovered addresses on routine saves; accept a newly scanned endpoint immediately.
      if (old == null || old.configuredUrl != target.getString("url") || old.discoveryPort != target.optInt("discoveryPort", 8766)) {
        old?.socket?.cancel()
        peers[id] = Peer(id, target.optString("name", "POS"), target.getString("url"), target.optInt("discoveryPort", 8766), role)
      } else {
        old.name = target.optString("name", "POS")
        if (old.role != role) {
          old.role = role
          if (old.status == "connected") sendHello(old)
        }
      }
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
      val now = System.currentTimeMillis()
      when (state) {
        TelephonyManager.CALL_STATE_RINGING -> {
          // A waiting call must not overwrite the original active caller on the POS.
          if (!callInProgress) {
            callInProgress = true; answered = false; direction = "in"; phone = number
            liveCalls.add(LiveCall(UUID.randomUUID().toString(), number, "in", "ringing", now))
          } else if (!answered && phone.isBlank() && number.isNotBlank()) {
            phone = number
            liveCalls.lastOrNull { it.endedAt == null }?.let { if (it.phone.isBlank()) it.phone = number }
          } else if (answered && number.isNotBlank() && liveCalls.none { it.endedAt == null && it.phone == number }) {
            liveCalls.add(LiveCall(UUID.randomUUID().toString(), number, "in", "waiting", now))
          }
          announceIfKnown()
        }
        TelephonyManager.CALL_STATE_OFFHOOK -> {
          if (!callInProgress) {
            callInProgress = true; direction = "out"; phone = number
            liveCalls.add(LiveCall(UUID.randomUUID().toString(), number, "out", "active", now))
          } else {
            liveCalls.firstOrNull { it.endedAt == null && it.state == "ringing" }?.state = "active"
          }
          answered = true
          announceIfKnown()
        }
        TelephonyManager.CALL_STATE_IDLE -> {
          if (announced) broadcastLegacy(JSONObject().put("type", "call_end").put("phone", phone))
          if (lastState != TelephonyManager.CALL_STATE_IDLE) lastCallEndedAt = now
          callInProgress = false; answered = false; announced = false; phone = ""
          liveCalls.filter { it.endedAt == null }.forEach { it.endedAt = now; it.state = "ended" }
        }
      }
      lastState = state
      publishCallState()
    }
  }

  private fun normalized(raw: String): String {
    val digits = raw.filter { it.isDigit() }.let { if (it.startsWith("00")) it.drop(2) else it }
    return if (digits.length == 9) "998$digits" else digits
  }

  private fun callStateMessage(): String {
    val now = System.currentTimeMillis()
    liveCalls.removeAll { it.endedAt != null && now - it.endedAt!! > RECENT_CALLS_MS }
    val current = liveCalls.filter { it.endedAt == null && it.phone.isNotBlank() }
    val recent = liveCalls.filter { it.endedAt != null && it.phone.isNotBlank() }.sortedByDescending { it.endedAt }.take(5)
    while (liveCalls.size > 20) liveCalls.removeAt(0)
    val calls = JSONArray()
    for (call in (current + recent).take(10)) {
      val item = JSONObject().put("id", call.id).put("phone", call.phone.take(40)).put("direction", call.direction)
        .put("state", call.state).put("since", call.since)
      names[normalized(call.phone)]?.let { item.put("customerName", it.take(200)) }
      calls.put(item)
    }
    return JSONObject().put("type", "call_state").put("calls", calls).toString()
  }

  private fun publishCallState() {
    val message = callStateMessage()
    for (peer in peers.values) if (peer.status == "connected") peer.socket?.send(message)
  }

  private fun sendHello(peer: Peer) {
    peer.socket?.send(JSONObject().put("type", "operator_hello").put("protocol", PROTOCOL).put("role", peer.role)
      .put("app", "Smart POS Operator ${appVersion()}").toString())
  }

  private fun announceIfKnown() {
    if (announced || phone.isBlank()) return
    announced = true
    broadcastLegacy(JSONObject().put("type", "call_start").put("phone", phone).put("direction", direction))
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
      // A handshake the POS never answers must not leave the phone "connecting" forever.
      if (peer.status == "connecting" && now - peer.connectingSince > HANDSHAKE_TIMEOUT_MS) {
        val stuck = peer.socket
        peer.socket = null; peer.status = "disconnected"; peer.error = "POS javob bermadi; qayta ulanmoqda"; peer.retryAt = now
        stuck?.cancel()
      }
      if (peer.status == "disconnected" && now >= peer.retryAt) connect(peer)
      if (peer.status != "connected" && now >= peer.discoveryAt && !peer.discovering) discover(peer)
    }
    peerHealth = peers.values.map { OperatorPeerHealth(it.id, it.name, it.status == "connected", it.role) }
    if (peers.isNotEmpty() && lastUptimeTick > 0) {
      val ratio = peers.values.count { it.status == "connected" }.toDouble() / peers.size
      val elapsed = now - lastUptimeTick
      val current = config
      try { callWorker.execute { supervisor.recordPosUptime(elapsed, ratio, current) } } catch (_: Exception) { }
    }
    lastUptimeTick = now
    if (now - lastHeartbeat >= 15_000) {
      lastHeartbeat = now
      OperatorRuntimeStore.update(this) { it.put("lastHeartbeatAt", now).put("phonePermission", hasPhonePermissions) }
      updateNotification()
    }
  }

  private fun connect(peer: Peer) {
    if (peers[peer.id] !== peer || destroyed) return
    peer.status = "connecting"
    peer.connectingSince = System.currentTimeMillis()
    try {
      peer.socket = websocketHttp.newWebSocket(Request.Builder().url(peer.url).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { main.post {
          if (destroyed || peers[peer.id] !== peer || peer.socket !== webSocket) { webSocket.cancel(); return@post }
          peer.status = "connected"; peer.attempt = 0; peer.error = null; peer.lastConnectedAt = System.currentTimeMillis()
          sendHello(peer)
          webSocket.send(callStateMessage())
          // Only current calls are replayed. Finished calls are never replayed as new orders.
          if (callInProgress && announced && peer.role != "cashier") webSocket.send(JSONObject().put("type", "call_start").put("phone", phone).put("direction", direction).toString())
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
                "customer_name" -> {
                  val number = message.optString("phone")
                  val name = message.optString("name").trim()
                  if (name.isNotEmpty() && normalized(number).isNotEmpty()) {
                    if (names[normalized(number)] != name) { names[normalized(number)] = name.take(200); publishCallState() }
                    ledgerWorker.execute { try { history.enrichCustomer(number, name) } catch (_: Exception) { } }
                  }
                }
                "call_record_ack" -> ledgerWorker.execute { try { history.acknowledge(peer.id, message.optString("id"), message.optLong("revision")) } catch (_: Exception) { } }
                "order_created" -> {
                  val orderId = message.opt("orderId")?.toString()?.trim().orEmpty()
                  val at = message.optLong("at", System.currentTimeMillis()).takeIf { it > 0 } ?: System.currentTimeMillis()
                  ledgerWorker.execute { try { history.linkOrder(message.optString("phone"), orderId, at) } catch (_: Exception) { } }
                }
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

  /** Protocol 2 messages: only operator POS show the popup; protocol 3 desktops ignore these after hello. */
  private fun broadcastLegacy(message: JSONObject): Int {
    var count = 0
    for (peer in peers.values) if (peer.role != "cashier" && peer.status == "connected" && peer.socket?.send(message.toString()) == true) count++
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
    val now = System.currentTimeMillis()
    val id = "test-$now"
    fun state(value: String) = JSONObject().put("type", "call_state").put("calls", JSONArray().put(JSONObject()
      .put("id", id).put("phone", testPhone).put("direction", "in").put("state", value).put("since", now).put("customerName", "Sinov qo‘ng‘irog‘i"))).toString()
    val legacy = JSONObject().put("type", "call_start").put("phone", testPhone).put("direction", "in").put("test", true).toString()
    val delivered = peers.values.filter { it.status == "connected" }.filter { peer ->
      val socket = peer.socket ?: return@filter false
      socket.send(state("ringing")) && (peer.role == "cashier" || socket.send(legacy))
    }.map { it to it.socket }
    main.postDelayed({
      val end = JSONObject().put("type", "call_end").put("phone", testPhone).put("test", true).toString()
      for ((peer, socket) in delivered) {
        socket?.send(state("ended"))
        if (peer.role != "cashier") socket?.send(end)
      }
      publishCallState()
    }, 1500)
    return delivered.size
  }

  fun snapshotTargets(): JSONArray {
    val result = JSONArray()
    peers.values.forEach { peer ->
      val target = JSONObject().put("id", peer.id).put("name", peer.name).put("url", peer.url).put("status", peer.status).put("role", peer.role)
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
    supervisor.telegram.cancel()
    botThread?.interrupt()
    history.close()
    recordingWorker.shutdownNow(); callWorker.shutdownNow(); ledgerWorker.shutdownNow(); updateWorker.shutdownNow(); telegramWorker.shutdownNow()
    networkPool.shutdownNow(); websocketHttp.dispatcher.cancelAll()
    if (wakeLock?.isHeld == true) wakeLock?.release()
    if (wifiLock?.isHeld == true) wifiLock?.release()
    OperatorRuntimeStore.ended(this, "Xizmat to'xtadi")
    super.onDestroy()
  }
}
