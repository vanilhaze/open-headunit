package com.andrerinas.openheadunit.smartgt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MySpinLink(private val ctx: Context, private val log: (String) -> Unit) {

    companion object {
        val MYSPIN_UUID: UUID = UUID.fromString("31FD954E-8138-4367-A201-0276B9716FB1")
        const val DEFAULT_UDP_PORT = 8158

        fun writeLE32(out: OutputStream, v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }

        fun writeLE16(out: OutputStream, v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }

        fun writeLE64(out: OutputStream, v: Long) {
            for (i in 0..7) {
                out.write(((v ushr (i * 8)) and 0xFF).toInt())
            }
        }

        fun readLE32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

        fun hexOf(b: ByteArray, n: Int): String = b.take(n).joinToString(" ") { "%02X".format(it) }

        fun writeCS(out: OutputStream, str: String) {
            val bytes = str.toByteArray(Charsets.UTF_8)
            out.write(1) // Kodowanie 1 = UTF-8 (CS.java)
            writeLE16(out, bytes.size)
            out.write(bytes)
        }
    }

    private val running = AtomicBoolean(false)
    private val tcpStarted = AtomicBoolean(false)
    private val p2pConnecting = AtomicBoolean(false)
    private val cachedPeers = ArrayList<WifiP2pDevice>()

    @Volatile private var btServer: BluetoothServerSocket? = null
    @Volatile private var btSocket: BluetoothSocket? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var tcpSocket: Socket? = null
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var isIviInitDone = false
    @Volatile private var isIviWlanReady = false
    @Volatile private var isPinRequested = false
    @Volatile private var savedPin: String? = null
    @Volatile private var targetDeviceName: String? = null

    // Protokół 2.0.x (wg UDP "version":2 i MySpinStateMachineV20x): ServerInit ma inny układ,
    // dochodzi ServerInitDone (18) i ClientInitDone (17); czas wysyłany dopiero po 17.
    @Volatile private var protoMajor = 1
    private var fmtA = 2
    private var fmtB = 2
    private var fmtD = 0
    private var compMode = 1
    private var requestId = 0
    private var screenWidth = 800
    private var screenHeight = 480
    private var selectedMenuIndex = 0

    fun start(peerFilter: Regex, udpPort: Int) {
        if (!running.compareAndSet(false, true)) return
        tcpStarted.set(false)
        p2pConnecting.set(false)
        cachedPeers.clear()
        isIviInitDone = false
        isIviWlanReady = false
        isPinRequested = false
        savedPin = null
        targetDeviceName = null

        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("MySpinUdpLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}

        startRfcomm()
        startUdp(udpPort)
        startP2p()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        if (mode == Mode.ANDROID_AUTO) { mode = Mode.MENU; stopAndroidAuto() }
        closeQuietly(btServer); closeQuietly(btSocket)
        udpSocket?.close(); closeQuietly(tcpSocket)
        p2pReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
        }
        p2pReceiver = null
        val m = p2pManager
        val c = p2pChannel
        if (m != null && c != null) {
            try {
                m.stopPeerDiscovery(c, null)
                m.removeGroup(c, null)
            } catch (_: SecurityException) {}
        }
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        log("Zatrzymano")
    }

    private fun closeQuietly(c: Closeable?) {
        try { c?.close() } catch (_: IOException) {}
    }

    // =========================================================================
    // KROK 1: BLUETOOTH RFCOMM (I6.b -> I6.c -> READY -> J6.e dla PIN)
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startRfcomm() {
        thread(name = "rfcomm") {
            try {
                val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter == null || !adapter.isEnabled) {
                    log("RFCOMM: Bluetooth wyłączony albo niedostępny")
                    return@thread
                }
                val server = adapter.listenUsingRfcommWithServiceRecord("MySpinBluetooth", MYSPIN_UUID)
                btServer = server
                log("RFCOMM: Nasłuch na UUID $MYSPIN_UUID")
                val socket = server.accept()
                btSocket = socket
                closeQuietly(server)
                log("RFCOMM: Połączono z ${socket.remoteDevice.name} / ${socket.remoteDevice.address}")

                val output = socket.outputStream
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))

                thread(name = "rfcomm-detect") {
                    try {
                        Thread.sleep(300)
                        val detectJson = JSONObject().apply {
                            put("version", "3")
                            put("id", UUID.randomUUID().toString())
                            put("type", "phoneDetectRequest")
                        }
                        sendJson(output, detectJson)
                    } catch (e: Exception) {
                        log("RFCOMM detect błąd: ${e.message}")
                    }
                }

                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (line.trim().isEmpty()) continue
                    log("RFCOMM <- $line")

                    try {
                        val json = JSONObject(line)
                        val type = json.optString("type")
                        val payload = json.optJSONObject("payload")
                        handleIviMessage(output, type, payload)
                    } catch (e: JSONException) {
                        log("RFCOMM błąd parsowania JSON: ${e.message}")
                    }
                }
                log("RFCOMM: Rozłączono")
            } catch (e: IOException) {
                if (running.get()) log("RFCOMM błąd: ${e.message}")
            }
        }
    }

    private fun sendJson(output: OutputStream, json: JSONObject) {
        val payloadStr = json.toString() + "\n"
        log("RFCOMM -> wysyłam: ${json.optString("type")}")
        output.write(payloadStr.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun handleIviMessage(output: OutputStream, type: String, payload: JSONObject?) {
        when (type) {
            "iviDetectResponse" -> {
                log("RFCOMM: SUKCES DETECT! Wysyłam phoneInitRequest...")
                val initPayload = JSONObject().apply {
                    put("regionCode", "EU")
                    put("version", 3)
                    put("phoneOS", "Android")
                }
                val initJson = JSONObject().apply {
                    put("id", UUID.randomUUID().toString())
                    put("type", "phoneInitRequest")
                    put("payload", initPayload)
                }
                sendJson(output, initJson)
            }
            "iviInitResponse" -> {
                log("RFCOMM: SUKCES INIT! Motocykl autoryzowany: $payload")
                isIviInitDone = true
                if (isIviWlanReady && !isPinRequested) {
                    sendPinRequest(output)
                } else {
                    log("RFCOMM: Czekam na powiadomienie READY modułu Wi-Fi...")
                }
            }
            "iviWlanStateNotification" -> {
                val state = payload?.optString("iviWlanState") ?: ""
                log("RFCOMM: Stan Wi-Fi motocykla: $state")
                if (state == "READY") {
                    isIviWlanReady = true
                    if (isIviInitDone && !isPinRequested) {
                        sendPinRequest(output)
                    }
                }
            }
            "iviWlanP2PPinConnectionResponse" -> {
                val pin = payload?.optString("pin")
                val devName = payload?.optString("deviceName")
                log("RFCOMM: SUKCES! ODEBRANO KOD PIN: $pin DLA SIECI: $devName")
                savedPin = pin
                targetDeviceName = devName

                triggerP2pConnectWithPin()
            }
            "iviErrorNotification" -> {
                log("RFCOMM: Status błędu: ${payload?.optString("iviError")}")
            }
        }
    }

    private fun sendPinRequest(output: OutputStream) {
        isPinRequested = true
        log("RFCOMM: Wi-Fi motocykla jest READY! Żądam kodu PIN (phoneWlanP2PPinConnectionRequest)...")
        val pinReq = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("type", "phoneWlanP2PPinConnectionRequest")
            put("payload", JSONObject())
        }
        sendJson(output, pinReq)
    }

    // =========================================================================
    // KROK 2: WI-FI DIRECT Z KODEM PIN (ZGODNIE Z C1574j30.java)
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startP2p() {
        val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return
        val ch = mgr.initialize(ctx, Looper.getMainLooper(), null)
        p2pManager = mgr
        p2pChannel = ch

        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                        mgr.requestPeers(ch) { list ->
                            synchronized(cachedPeers) {
                                cachedPeers.clear()
                                cachedPeers.addAll(list.deviceList)
                            }
                            if (!savedPin.isNullOrEmpty()) {
                                triggerP2pConnectWithPin()
                            }
                        }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                        mgr.requestConnectionInfo(ch) { info ->
                            if (info.groupFormed) {
                                log("P2P: GRUPA WI-FI POŁĄCZONA! Czekam na pakiet rozgłoszeniowy UDP z portem...")
                            }
                        }
                }
            }
        }
        p2pReceiver = rcv
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val exportFlag = if (Build.VERSION.SDK_INT >= 33) ContextCompat.RECEIVER_EXPORTED else 0
        ContextCompat.registerReceiver(ctx, rcv, f, exportFlag)
        mgr.discoverPeers(ch, null)
    }

    @SuppressLint("MissingPermission")
    private fun triggerP2pConnectWithPin() {
        val pin = savedPin ?: return
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        if (p2pConnecting.get()) return

        synchronized(cachedPeers) {
            val target = cachedPeers.firstOrNull { d ->
                val name = d.deviceName ?: ""
                name.contains("2W-Wifi", ignoreCase = true) ||
                (targetDeviceName != null && name.equals(targetDeviceName, ignoreCase = true))
            }

            if (target != null) {
                if (!p2pConnecting.compareAndSet(false, true)) return

                val cfg = WifiP2pConfig().apply {
                    deviceAddress = target.deviceAddress
                    groupOwnerIntent = 0
                    wps.setup = WpsInfo.KEYPAD
                    wps.pin = pin
                }

                log("P2P: Łączę z ${target.deviceName} używając PIN: $pin...")
                mgr.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        log("P2P connect: Autoryzacja PIN wysłana do motocykla!")
                    }
                    override fun onFailure(r: Int) {
                        log("P2P connect: Błąd kod=$r")
                        p2pConnecting.set(false)
                    }
                })
            } else {
                mgr.discoverPeers(ch, null)
            }
        }
    }

    // =========================================================================
    // KROK 3: ODBIÓR PRAWDZIWEGO PORTU Z UDP 8158 (ZGODNIE Z c.java I f.java)
    // =========================================================================
    private fun startUdp(port: Int) {
        thread(name = "udp") {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(port))
                }
                udpSocket = s
                log("UDP: Nasłuch rozgłoszeń motocykla na porcie $port...")
                val buf = ByteArray(1024)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val rawText = String(p.data, 0, p.length, Charsets.UTF_8)
                    val text = rawText.substringBefore('\u0000').trim()
                    log("UDP <- Otrzymano pakiet: $text")
                    try {
                        val j = JSONObject(text)
                        val host = j.optString("hostname").ifEmpty { p.address.hostAddress ?: "192.168.10.2" }
                        val realTcpPort = j.getInt("port")
                        log("UDP: Wykryto prawdziwy port TCP z motocykla: $host:$realTcpPort")
                        connectTcp(host, realTcpPort)
                    } catch (e: Exception) {
                        log("UDP błąd parsowania JSON: ${e.message}")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // KROK 4: STRUMIEŃ TCP ZGODNY 1:1 Z C1411gx, C0990bx (Czas w SEKUNDACH!)
    // =========================================================================
    private fun connectTcp(host: String, port: Int) {
        if (host.isEmpty() || !tcpStarted.compareAndSet(false, true)) return
        thread(name = "tcp") {
            try {
                val s = Socket()

                try {
                    val p2pIf = NetworkInterface.getNetworkInterfaces().toList().firstOrNull { ni ->
                        ni.name.contains("p2p", ignoreCase = true) || ni.name.contains("wlan", ignoreCase = true)
                    }
                    val localP2pIp = p2pIf?.inetAddresses?.toList()?.firstOrNull { addr ->
                        addr.hostAddress?.startsWith("192.168.") == true
                    }
                    if (localP2pIp != null) {
                        s.bind(InetSocketAddress(localP2pIp, 0))
                        log("TCP: Związano gniazdo z lokalnym IP Wi-Fi Direct: ${localP2pIp.hostAddress}")
                    }
                } catch (_: Exception) {}

                s.keepAlive = true
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 10_000)
                tcpSocket = s
                log("TCP: POŁĄCZONO Z KOKPITEM $host:$port!")
                log("TCP: Czekam na pakiet powitalny motocykla (Typ 1)...")

                val out = s.getOutputStream()
                val inp = DataInputStream(s.getInputStream().buffered())

                val headerBuf = ByteArray(9)
                while (running.get()) {
                    inp.readFully(headerBuf)
                    val packetType = headerBuf[0].toInt() and 0xFF
                    val payloadLen = readLE32(headerBuf, 5)

                    if (payloadLen < 0 || payloadLen > 5_000_000) {
                        log("TCP: Błędny rozmiar pakietu ($payloadLen)")
                        break
                    }
                    val body = ByteArray(payloadLen)
                    inp.readFully(body)

                    if (packetType != 25) log("TCP <- typ=$packetType len=$payloadLen hdr=${hexOf(headerBuf, 9)} ciało=${hexOf(body, minOf(body.size, 48))}")
                    handleTcpPacket(out, packetType, body)
                }
            } catch (e: IOException) {
                log("TCP rozłączony: ${e.javaClass.simpleName}: ${e.message} (running=${running.get()}, klatek=$framesSent)")
            } finally {
                log("TCP: koniec sesji, wysłano $framesSent klatek")
                closeQuietly(tcpSocket)
                tcpStarted.set(false)
            }
        }
    }

    private fun handleTcpPacket(out: OutputStream, type: Int, body: ByteArray) {
        when (type) {
            1 -> {
                if (body.size >= 4) {
                    val major = (body[0].toInt() and 0xFF) or ((body[1].toInt() and 0xFF) shl 8)
                    val minor = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8)
                    protoMajor = if (major >= 2) 2 else 1
                    log("TCP <- ProtocolVersion: $major.$minor (używam układu ${if (protoMajor == 2) "2.0.x" else "1.1.x"})")
                } else log("TCP <- ProtocolVersion (Typ 1), nieznany układ ciała")
                sendServerProtocolAck(out)
                log("TCP -> Czekam w Stanie 2 na pakiet Capabilities (Typ 11) od motocykla...")
            }
            11 -> {
                // Odczyt rozdzielczości ekranu z ciała Pakietu 11 (C1822mE: f=width, g=height)
                if (body.size >= 7) {
                    val nameLen = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
                    val offset = 3 + nameLen
                    if (body.size >= offset + 4) {
                        val w = (body[offset].toInt() and 0xFF) or ((body[offset + 1].toInt() and 0xFF) shl 8)
                        val h = (body[offset + 2].toInt() and 0xFF) or ((body[offset + 3].toInt() and 0xFF) shl 8)
                        if (w > 0 && h > 0) {
                            screenWidth = w
                            screenHeight = h
                        }
                    }
                }
                parseClientInitFormat(body)
                log("TCP <- ClientInit (Typ 11, ekran ${screenWidth}x${screenHeight}px, format=($fmtA,$fmtB,$fmtD))")
                sendServerInitAck(out)
                if (protoMajor >= 2) {
                    sendServerInitV2(out)
                    sendSimple(out, 18, 1, "ServerInitDone (18)")
                    log("TCP: czekam na ClientInitDone (17), czas wyślę dopiero po nim")
                } else {
                    sendServerInit(out)
                    sendUtcTime(out)
                    sendLocalTime(out)
                }
            }
            17 -> {
                log("TCP <- ClientInitDone (Typ 17) ${if (body.isNotEmpty()) body[0].toInt() else -1}")
                sendUtcTime(out)
                sendLocalTime(out)
            }
            21 -> {
                if (body.size >= 3) { fmtA = body[0].toInt() and 0xFF; fmtB = body[1].toInt() and 0xFF; fmtD = body[2].toInt() and 0xFF }
                log("TCP <- SetPixelFormat (Typ 21) format=($fmtA,$fmtB,$fmtD). Odsyłam Ack (Typ 22)...")
                sendPixelFormatAck(out)
            }
            23 -> {
                if (body.size >= 4) compMode = readLE32(body, 0)
                log("TCP <- OverrideCompression (Typ 23) tryb=$compMode. Odsyłam Ack (Typ 24)...")
                sendOverrideCompressionAck(out)
            }
            25 -> {
                if (body.size >= 4) requestId = readLE32(body, 0)
                onFrameRequest(out)
            }
            31 -> {
                // Przycisk WSTECZ motocykla: ekran wysyla parami [1 1 ..] (nacisniecie) i [2 1 ..] (puszczenie).
                if (body.size >= 2) {
                    val ev = body[0].toInt() and 0xFF
                    val button = body[1].toInt() and 0xFF
                    log("PRZYCISK(31): zdarzenie=$ev przycisk=$button")
                    if (ev == 1) onBackButton(out)
                }
            }
            67 -> onClientCustomData(out, body)
            128 -> {
                sendPingAck(out, body)
            }
        }
    }

    // ClientInit (2.0 i 1.1): [ES napis][u16 f][u16 g][u16 h][u16 i][u32 j][fmt: u8 a, u8 b, u32 c, u8 d][u32 l]...
    private fun parseClientInitFormat(body: ByteArray) {
        try {
            if (body.size < 3) return
            val nameLen = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
            val o = 3 + nameLen + 8 + 4
            if (body.size >= o + 7) {
                fmtA = body[o].toInt() and 0xFF
                fmtB = body[o + 1].toInt() and 0xFF
                val mask = readLE32(body, o + 2)
                fmtD = body[o + 6].toInt() and 0xFF
                compMode = if (mask and 1 != 0) 1 else if (mask and 2 != 0) 2 else if (mask and 4 != 0) 4 else 1
                log("ClientInit: maskaKompresji=$mask -> tryb=$compMode")
            }
        } catch (e: Exception) { log("parseClientInitFormat błąd: ${e.message}") }
    }

    private fun sendSimple(out: OutputStream, type: Int, value: Int, name: String) {
        val packet = ByteArray(10)
        packet[0] = type.toByte()
        writeLE32Direct(packet, 5, 1)
        packet[9] = value.toByte()
        synchronized(out) { out.write(packet); out.flush() }
        log("TCP -> $name")
    }

    // ServerInit 2.0.x (C1824mG): [VW liczba=1][bajt 1][u32 = 14][ES "Android"][ES pusty]
    private fun sendServerInitV2(out: OutputStream) {
        val payload = ByteArrayOutputStream()
        payload.write(1)
        payload.write(1)
        writeLE32(payload, 14)
        writeCS(payload, "Android")
        payload.write(1); writeLE16(payload, 0)
        val pb = payload.toByteArray()
        val header = ByteArray(9)
        header[0] = 14
        writeLE32Direct(header, 5, pb.size)
        synchronized(out) { out.write(header); out.write(pb); out.flush() }
        log("TCP -> ServerInit 2.0 (Typ 14)")
    }

    // ClientCustomData 2.0.x (67): [u8 e][NW f = akcja][NW g = długość][dane: u8 kod klawisza, opcjonalnie znacznik czasu]
    // Akcje: 1 press, 2 release, 3 long press, 4 click, 6 abort. Kody: 12 OK, 14 Back, 20/21 pokrętło CW/CCW,
    // 30 góra, 31 dół, 32 lewo, 33 prawo (z oficjalnego handleFocusControlEvent; działa dla kodów 12..33).
    private fun onClientCustomData(out: OutputStream, body: ByteArray) {
        if (body.size < 6) return
        val action = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        val len = (body[3].toInt() and 0xFF) or ((body[4].toInt() and 0xFF) shl 8)
        if (len < 1 || body.size < 5 + len) return
        val key = body[5].toInt() and 0xFF
        if (key !in 12..33) return // komunikat informacyjny ekranu (Suzuki, ICC, firmware)
        log("MANETKA: akcja=$action kod=$key")
        // WSTECZ (14): z dowolnego źródła wracamy do menu (długie przytrzymanie lub krótkie – na razie oba)
        if (key == 14 && mode != Mode.MENU && (action == 3 || (action == 1 && mode != Mode.ANDROID_AUTO))) {
            switchMode(Mode.MENU, out)
            return
        }
        // Android Auto: OK dziala dopiero po puszczeniu; przytrzymanie >= 800 ms = powrot do menu SmartGT
        if (mode == Mode.ANDROID_AUTO && key == 12) {
            val now = System.currentTimeMillis()
            if (action == 1) okDownMs = now
            else if (action == 2) {
                val held = if (okDownMs > 0) now - okDownMs else 0
                okDownMs = 0
                if (held >= 800) switchMode(Mode.MENU, out) else onSourceKey(12, out)
            }
            return
        }
        if (action != 1) return
        if (mode != Mode.MENU) { onSourceKey(key, out); return }
        val mapped = when (key) { 31, 20 -> 1; 30, 21 -> 2; 33 -> 3; 32 -> 4; 12 -> 5; else -> 0 }
        if (mapped != 0) handleHandlebarJoystick(mapped, out)
    }

    private fun sendServerProtocolAck(out: OutputStream) {
        val packet = ByteArray(10)
        packet[0] = 2
        writeLE32Direct(packet, 1, 0)
        writeLE32Direct(packet, 5, 1)
        packet[9] = 1
        synchronized(out) {
            out.write(packet)
            out.flush()
        }
        log("TCP -> Wysłano PacketServerProtocolAcknowledge (Typ 2)")
    }

    private fun sendServerInitAck(out: OutputStream) {
        val packet = ByteArray(10)
        packet[0] = 12
        writeLE32Direct(packet, 1, 0)
        writeLE32Direct(packet, 5, 1)
        packet[9] = 1
        synchronized(out) {
            out.write(packet)
            out.flush()
        }
        log("TCP -> Wysłano PacketServerInitAcknowledge (Typ 12)")
    }

    private fun sendServerInit(out: OutputStream) {
        val payload = ByteArrayOutputStream()
        writeCS(payload, "mySPIN Android Server")
        payload.write(1)
        payload.write(1)
        payload.write(1)
        writeLE32(payload, 2)
        writeCS(payload, "Android")

        val payloadBytes = payload.toByteArray()
        val header = ByteArray(9)
        header[0] = 14
        writeLE32Direct(header, 1, 0)
        writeLE32Direct(header, 5, payloadBytes.size)

        synchronized(out) {
            out.write(header)
            out.write(payloadBytes)
            out.flush()
        }
        log("TCP -> Wysłano PacketServerInit (Typ 14)")
    }

    // Zgodnie z T() w AbstractC0783Yw.java: czas w SEKUNDACH (/ 1000L)
    private fun sendUtcTime(out: OutputStream) {
        val nowUtcSec = System.currentTimeMillis() / 1000L
        val dataStream = ByteArrayOutputStream()
        writeLE64(dataStream, nowUtcSec)
        val dataBytes = dataStream.toByteArray()

        val payload = ByteArrayOutputStream()
        payload.write(2) // this.e = 2
        writeLE16(payload, 4) // this.f = 4 (UTC Time identifier)
        writeLE16(payload, dataBytes.size) // this.g = 8
        payload.write(dataBytes)

        val payloadBytes = payload.toByteArray()
        val header = ByteArray(9)
        header[0] = 68
        writeLE32Direct(header, 1, 0)
        writeLE32Direct(header, 5, payloadBytes.size)

        synchronized(out) {
            out.write(header)
            out.write(payloadBytes)
            out.flush()
        }
        log("TCP -> Wysłano czas UTC w sekundach (Pakiet 68)")
    }

    // Zgodnie z S() w AbstractC0783Yw.java: czas lokalny w SEKUNDACH (/ 1000L)
    private fun sendLocalTime(out: OutputStream) {
        val nowMs = System.currentTimeMillis()
        val nowLocalSec = (nowMs + TimeZone.getDefault().getOffset(nowMs).toLong()) / 1000L
        val dataStream = ByteArrayOutputStream()
        writeLE64(dataStream, nowLocalSec)
        val dataBytes = dataStream.toByteArray()

        val payload = ByteArrayOutputStream()
        payload.write(2) // this.e = 2
        writeLE16(payload, 6) // this.f = 6 (Local Time identifier)
        writeLE16(payload, dataBytes.size) // this.g = 8
        payload.write(dataBytes)

        val payloadBytes = payload.toByteArray()
        val header = ByteArray(9)
        header[0] = 68
        writeLE32Direct(header, 1, 0)
        writeLE32Direct(header, 5, payloadBytes.size)

        synchronized(out) {
            out.write(header)
            out.write(payloadBytes)
            out.flush()
        }
        log("TCP -> Wysłano czas lokalny w sekundach (Pakiet 68)")
    }

    private fun sendPixelFormatAck(out: OutputStream) {
        val packet = ByteArray(10)
        packet[0] = 22
        writeLE32Direct(packet, 1, 0)
        writeLE32Direct(packet, 5, 1)
        packet[9] = 1
        synchronized(out) {
            out.write(packet)
            out.flush()
        }
        log("TCP -> Wysłano PacketPixelFormatAcknowledge (Typ 22)")
    }

    private fun sendOverrideCompressionAck(out: OutputStream) {
        val packet = ByteArray(10)
        packet[0] = 24
        writeLE32Direct(packet, 1, 0)
        writeLE32Direct(packet, 5, 1)
        packet[9] = 1
        synchronized(out) {
            out.write(packet)
            out.flush()
        }
        log("TCP -> Wysłano PacketOverrideCompressionAcknowledge (Typ 24)")
    }

    private fun handleHandlebarJoystick(keyCode: Int, out: OutputStream) {
        log("MANETKA: Naciśnięto przycisk kod=$keyCode")
        when (keyCode) {
            1, 3 -> {
                selectedMenuIndex = (selectedMenuIndex + 1) % 3
                requestRedraw(out)
            }
            2, 4 -> {
                selectedMenuIndex = if (selectedMenuIndex - 1 < 0) 2 else selectedMenuIndex - 1
                requestRedraw(out)
            }
            5 -> {
                log("MANETKA: Wybrano opcję: $selectedMenuIndex!")
                switchMode(
                    when (selectedMenuIndex) { 0 -> Mode.ANDROID_AUTO; 1 -> Mode.VIDEO; else -> Mode.STATUS },
                    out
                )
            }
        }
    }

    // =========================================================================
    // TRYBY / ŹRÓDŁA OBRAZU
    // =========================================================================
    enum class Mode { MENU, ANDROID_AUTO, VIDEO, STATUS }
    @Volatile private var mode = Mode.MENU
    private val sessionStartMs = System.currentTimeMillis()
    @Volatile private var lastAaSeq = -1L
    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    private fun switchMode(newMode: Mode, out: OutputStream) {
        log("TRYB: $mode -> $newMode")
        val old = mode
        mode = newMode
        if (old == Mode.ANDROID_AUTO && newMode != Mode.ANDROID_AUTO) stopAndroidAuto()
        if (newMode == Mode.ANDROID_AUTO && old != Mode.ANDROID_AUTO) startAndroidAuto(out)
        requestRedraw(out)
    }

    /** Zdarzenia z manetki poza menu. Tu trafią później AA (D-pad) i odtwarzacz wideo. */
    private fun onSourceKey(key: Int, out: OutputStream) {
        if (mode != Mode.ANDROID_AUTO) {
            // Ekranowy WSTECZ obsluguje sam motocykl (nie dociera do aplikacji), wiec wracamy joystickiem.
            if (key == 32 || key == 12) switchMode(Mode.MENU, out)
            else log("ŹRÓDŁO($mode): klawisz $key (jeszcze nieobsługiwany)")
            return
        }
        val android = when (key) {
            30, 21 -> 19   // DPAD_UP  (joystick gora / pokretlo CCW)
            31, 20 -> 20   // DPAD_DOWN
            32 -> 21       // DPAD_LEFT
            33 -> 22       // DPAD_RIGHT
            12 -> 23       // DPAD_CENTER (OK)
            14 -> 4        // BACK
            else -> 0
        }
        if (android == 0) return
        log("AA: klawisz TFT $key -> Android $android")
        try {
            val cm = com.andrerinas.openheadunit.App.provide(ctx).commManager
            cm.sendKey(android, true, null, "smartgt")
            mainHandler.postDelayed({ cm.sendKey(android, false, null, "smartgt") }, 50)
        } catch (e: Exception) { log("AA: blad wysylania klawisza: ${e.message}") }
    }

    private var lastBackMs = 0L
    @Volatile private var okDownMs = 0L

    /** WSTECZ z motocykla: poza menu wraca do menu; w Android Auto: raz = Wstecz w AA, dwa razy szybko = menu. */
    private fun onBackButton(out: OutputStream) {
        val now = System.currentTimeMillis()
        when (mode) {
            Mode.MENU -> log("WSTECZ w menu glownym (ekran moze sam zamknac widok telefonu)")
            Mode.ANDROID_AUTO -> {
                if (now - lastBackMs < 600) {
                    lastBackMs = 0
                    switchMode(Mode.MENU, out)
                } else {
                    lastBackMs = now
                    onSourceKey(14, out)
                }
            }
            else -> switchMode(Mode.MENU, out)
        }
    }

    private fun startAndroidAuto(out: OutputStream) {
        log("AA: start (tryb samodzielny)")
        SmartGtBridge.log = log
        SmartGtBridge.targetW = screenWidth
        SmartGtBridge.targetH = screenHeight
        SmartGtBridge.enabled = true
        SmartGtBridge.latestJpeg = null
        SmartGtBridge.onFrame = { requestRedraw(out) }
        try {
            val i = Intent(ctx, com.andrerinas.openheadunit.aap.AapService::class.java)
                .setAction(com.andrerinas.openheadunit.aap.AapService.ACTION_START_SELF_MODE)
            ContextCompat.startForegroundService(ctx, i)
        } catch (e: Exception) { log("AA: nie udalo sie uruchomic: ${e.message}") }
    }

    private fun stopAndroidAuto() {
        log("AA: stop")
        SmartGtBridge.enabled = false
        SmartGtBridge.onFrame = null
        try {
            val i = Intent(ctx, com.andrerinas.openheadunit.aap.AapService::class.java)
                .setAction(com.andrerinas.openheadunit.aap.AapService.ACTION_STOP_SELF_MODE)
            ctx.startService(i)
        } catch (e: Exception) { log("AA: blad zatrzymania: ${e.message}") }
        SmartGtBridge.release()
    }

    private fun renderModeScreen(): ByteArray? {
        try {
            val bmp = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            val p = Paint().apply { isAntiAlias = true }
            c.drawColor(Color.rgb(15, 20, 30))
            p.color = Color.rgb(30, 40, 60)
            c.drawRect(0f, 0f, screenWidth.toFloat(), 50f, p)
            p.color = Color.CYAN; p.textSize = 24f; p.isFakeBoldText = true
            val title = when (mode) {
                Mode.ANDROID_AUTO -> "ANDROID AUTO"
                Mode.VIDEO -> "WIDEO"
                else -> "STATUS GX"
            }
            c.drawText(title, 20f, 35f, p)
            p.color = Color.WHITE; p.textSize = 22f; p.isFakeBoldText = false
            when (mode) {
                Mode.STATUS -> {
                    val sec = (System.currentTimeMillis() - sessionStartMs) / 1000
                    c.drawText("Połączono z ekranem: OK", 30f, 110f, p)
                    c.drawText("Protokół mySPIN: ${protoMajor}.x   Ekran: ${screenWidth}x${screenHeight}", 30f, 150f, p)
                    c.drawText("Wysłane klatki: $framesSent   Czas sesji: ${sec / 60} min ${sec % 60} s", 30f, 190f, p)
                }
                Mode.ANDROID_AUTO -> {
                    c.drawText("Łączenie z Android Auto…", 30f, 120f, p)
                    c.drawText("Telefon musi mieć włączony serwer / tryb samodzielny.", 30f, 160f, p)
                }
                else -> {
                    c.drawText("Wkrótce – źródło jeszcze niepodłączone.", 30f, 120f, p)
                }
            }
            p.color = Color.LTGRAY; p.textSize = 18f
            c.drawText(
                if (mode == Mode.ANDROID_AUTO) "Przytrzymaj OK (1 s) – powrót do menu"
                else "Joystick w lewo lub OK – powrót do menu",
                30f, (screenHeight - 25).toFloat(), p
            )
            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            bmp.recycle()
            return stream.toByteArray()
        } catch (e: Exception) { log("Render ekranu trybu błąd: ${e.message}"); return null }
    }

    // =========================================================================
    // GENERATOR PULPITU DLA EKRANU TFT SUZUKI GX (Typ 26)
    // =========================================================================
    private val frameLock = Any()
    private val sendExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    @Volatile private var pendingReq = false
    @Volatile private var dirty = true
    private var cachedJpeg: ByteArray? = null
    private var cachedMinute = ""
    private var lastSendMs = 0L
    @Volatile private var framesSent = 0
    private var lastStatMs = 0L

    private fun onFrameRequest(out: OutputStream) {
        pendingReq = true
        val minuteNow = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        if (minuteNow != cachedMinute) dirty = true
        if (mode == Mode.STATUS && System.currentTimeMillis() - lastSendMs >= 1000) dirty = true
        if (mode == Mode.ANDROID_AUTO && SmartGtBridge.frameSeq != lastAaSeq) dirty = true
        val wait = if (dirty) 0L else maxOf(0L, 500L - (System.currentTimeMillis() - lastSendMs))
        sendExec.schedule({ flushFrame(out) }, wait, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun requestRedraw(out: OutputStream) {
        dirty = true
        if (pendingReq) sendExec.execute { flushFrame(out) }
    }

    private fun flushFrame(out: OutputStream) {
        synchronized(frameLock) {
            if (!pendingReq) return
            pendingReq = false
            try {
                if (dirty || cachedJpeg == null) {
                    cachedJpeg = renderDashboardJpeg()
                    dirty = false
                }
                cachedJpeg?.let {
                    sendFramebufferUpdate(out, it)
                    framesSent++
                    lastSendMs = System.currentTimeMillis()
                    if (lastSendMs - lastStatMs > 5000) {
                        lastStatMs = lastSendMs
                        log("STAT: klatek=$framesSent, ostatnia=${it.size} B")
                    }
                }
            } catch (e: Exception) { log("Wysyłka klatki błąd: ${e.message}") }
        }
    }

    private fun renderDashboardJpeg(): ByteArray? {
        if (mode == Mode.ANDROID_AUTO) {
            val f = SmartGtBridge.latestJpeg
            lastAaSeq = SmartGtBridge.frameSeq
            return f ?: renderModeScreen()
        }
        if (mode != Mode.MENU) return renderModeScreen()
        try {
            val bmp = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            val p = Paint().apply { isAntiAlias = true }

            canvas.drawColor(Color.rgb(15, 20, 30))

            p.color = Color.rgb(30, 40, 60)
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), 50f, p)

            p.color = Color.CYAN
            p.textSize = 24f
            p.isFakeBoldText = true
            canvas.drawText("SUZUKI GSX-S 1000 GX", 20f, 35f, p)

            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            cachedMinute = timeStr
            p.color = Color.WHITE
            canvas.drawText(timeStr, (screenWidth - 90).toFloat(), 35f, p)

            val menuTitles = arrayOf("ANDROID AUTO", "WIDEO", "STATUS GX")
            val cardW = (screenWidth - 80) / 3f
            val cardH = 130f
            val cardY = 75f

            for (i in 0..2) {
                val cardX = 30f + i * (cardW + 10f)
                val isSelected = (i == selectedMenuIndex)

                p.color = if (isSelected) Color.rgb(0, 100, 200) else Color.rgb(25, 32, 48)
                canvas.drawRoundRect(RectF(cardX, cardY, cardX + cardW, cardY + cardH), 16f, 16f, p)

                if (isSelected) {
                    p.color = Color.CYAN
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 4f
                    canvas.drawRoundRect(RectF(cardX, cardY, cardX + cardW, cardY + cardH), 16f, 16f, p)
                    p.style = Paint.Style.FILL
                }

                p.color = Color.WHITE
                p.textSize = 22f
                p.isFakeBoldText = true
                canvas.drawText(menuTitles[i], cardX + 25f, cardY + 50f, p)

                p.color = Color.LTGRAY
                p.textSize = 16f
                p.isFakeBoldText = false
                val desc = when (i) {
                    0 -> "Nawigacja, muzyka"
                    1 -> "YouTube (postój)"
                    else -> "Połączenie, klatki"
                }
                canvas.drawText(desc, cardX + 25f, cardY + 100f, p)
            }

            p.color = Color.GREEN
            p.textSize = 18f
            canvas.drawText("● POŁĄCZONO PRZEZ SMART-GX BRIDGE | Sterowanie: Lewa manetka", 30f, (cardY + cardH + 40f), p)

            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val jpegBytes = stream.toByteArray()
            bmp.recycle()
            return jpegBytes
        } catch (e: Exception) { log("Render błąd: ${e.message}"); return null }
    }

    // FramebufferUpdate 2.0.x (VE + C0995c1): [u32 id z żądania][u8 liczba=1]
    //  prostokąt: [u16 x][u16 y][u16 f][u16 g z ClientInit][u8 fmtA][u8 fmtB][u32 kompresja][u8 fmtD][u8 100][u8 100][u32 dł][dane]
    // 1.1.x: bez pola id na początku.
    private fun sendFramebufferUpdate(out: OutputStream, frameBytes: ByteArray) {
        if (compMode != 1) log("UWAGA: ekran chce kompresji $compMode, a wysyłam JPEG (1)")
        val body = ByteArrayOutputStream()
        if (protoMajor >= 2) writeLE32(body, requestId)
        body.write(1)
        writeLE16(body, 0); writeLE16(body, 0)
        writeLE16(body, screenWidth); writeLE16(body, screenHeight)
        body.write(fmtA); body.write(fmtB)
        writeLE32(body, 1)
        body.write(fmtD)
        body.write(100); body.write(100)
        writeLE32(body, frameBytes.size)
        body.write(frameBytes)
        val bb = body.toByteArray()
        val header = ByteArray(9)
        header[0] = 26
        writeLE32Direct(header, 5, bb.size)
        synchronized(out) { out.write(header); out.write(bb); out.flush() }
    }

    private fun sendPingAck(out: OutputStream, pingBody: ByteArray) {
        val header = ByteArray(9)
        header[0] = 0x81.toByte()
        writeLE32Direct(header, 1, 0)
        writeLE32Direct(header, 5, pingBody.size)
        synchronized(out) {
            out.write(header)
            out.write(pingBody)
            out.flush()
        }
    }

    private fun writeLE32Direct(b: ByteArray, offset: Int, v: Int) {
        b[offset] = (v and 0xFF).toByte()
        b[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        b[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        b[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
