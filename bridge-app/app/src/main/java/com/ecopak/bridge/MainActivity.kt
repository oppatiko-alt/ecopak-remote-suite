package com.ecopak.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Size
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okio.ByteString.Companion.toByteString

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var txtStatus: TextView
    private lateinit var editServer: EditText
    private lateinit var editSession: EditText
    private lateinit var editToken: EditText
    private lateinit var editBtMac: EditText

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var ws: WebSocket? = null
    private var keepWsConnected = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var lastFrameSentAt = 0L
    private val minVideoFps = 12
    private val maxVideoFps = 18
    private val minJpegQuality = 12
    private val maxJpegQuality = 24

    @Volatile
    private var currentVideoFps = 18

    @Volatile
    private var currentFrameIntervalMs = 1000L / currentVideoFps

    @Volatile
    private var currentJpegQuality = 16

    @Volatile
    private var remoteViewerCount = 0

    private val frameSendAttempts = AtomicInteger(0)
    private val frameSendSuccess = AtomicInteger(0)
    private val frameQueuePressureHits = AtomicInteger(0)
    private val maxWsQueueBytes = 262_144L
    private val frameMagic: Byte = 0x45

    private val prefs by lazy { getSharedPreferences("bridge_prefs", MODE_PRIVATE) }

    private val btBridge = PersistentBluetoothBridge(this) { message ->
        runOnUiThread { setStatus(message) }
    }

    private val reconnectRunnable = Runnable {
        if (keepWsConnected) {
            openWebSocket()
        }
    }

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            sendTelemetry()
            mainHandler.postDelayed(this, 2000)
        }
    }

    private val videoAdaptationRunnable = object : Runnable {
        override fun run() {
            adaptVideoEncodingProfile()
            mainHandler.postDelayed(this, 2000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasAllPermissions()) {
            startCamera()
            startAutoConnections()
        } else {
            setStatus("Permissions missing")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        txtStatus = findViewById(R.id.txtStatus)
        editServer = findViewById(R.id.editServer)
        editSession = findViewById(R.id.editSession)
        editToken = findViewById(R.id.editToken)
        editBtMac = findViewById(R.id.editBtMac)

        loadDefaults()
        bindUi()

        if (hasAllPermissions()) {
            startCamera()
            startAutoConnections()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    private fun bindUi() {
        findViewById<Button>(R.id.btnConnectServer).setOnClickListener {
            connectServer()
        }

        findViewById<Button>(R.id.btnConnectBt).setOnClickListener {
            startBluetoothPersistent()
        }
    }

    private fun startAutoConnections() {
        connectServer()
        val mac = editBtMac.text.toString().trim()
        btBridge.startPersistent(mac)
        if (mac.isNotEmpty()) {
            setStatus("BT auto-connect started: $mac")
        } else {
            setStatus("BT auto-connect started: ECOPAK auto-detect")
        }
        mainHandler.postDelayed(telemetryRunnable, 1500)
        mainHandler.removeCallbacks(videoAdaptationRunnable)
        mainHandler.postDelayed(videoAdaptationRunnable, 2000)
    }

    private fun requiredPermissions(): Array<String> {
        val p = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_CONNECT)
            p.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return p.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun connectServer() {
        keepWsConnected = true
        saveDefaults()
        openWebSocket()
    }

    private fun openWebSocket() {
        val raw = editServer.text.toString().trim()
        val session = editSession.text.toString().trim()
        val token = editToken.text.toString().trim()

        if (raw.isEmpty() || session.isEmpty()) {
            setStatus("Server URL and session required")
            return
        }

        val wsUrl = raw
            .replace("http://", "ws://")
            .replace("https://", "wss://")

        ws?.close(1000, "reconnect")
        reconnectHandler.removeCallbacks(reconnectRunnable)
        setStatus("Connecting relay...")

        val request = Request.Builder().url(wsUrl).build()
        ws = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                remoteViewerCount = 0
                frameSendAttempts.set(0)
                frameSendSuccess.set(0)
                frameQueuePressureHits.set(0)
                val reg = JSONObject()
                    .put("type", "register_bridge")
                    .put("sessionId", session)
                    .put("token", token)
                webSocket.send(reg.toString())
                runOnUiThread { setStatus("Relay connected") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRelayMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                remoteViewerCount = 0
                frameQueuePressureHits.set(0)
                runOnUiThread { setStatus("Relay closed: $code") }
                scheduleWsReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                remoteViewerCount = 0
                frameQueuePressureHits.set(0)
                runOnUiThread { setStatus("Relay error: ${t.message}") }
                scheduleWsReconnect()
            }
        })
    }

    private fun scheduleWsReconnect() {
        if (!keepWsConnected) return
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectHandler.postDelayed(reconnectRunnable, 2500)
    }

    private fun startBluetoothPersistent() {
        val mac = editBtMac.text.toString().trim()
        saveDefaults()
        btBridge.startPersistent(mac)
        if (mac.isNotEmpty()) {
            setStatus("BT persistent connect started: $mac")
        } else {
            setStatus("BT persistent connect started: auto ECOPAK")
        }
    }

    private fun handleRelayMessage(text: String) {
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }

        when (obj.optString("type")) {
            "registered" -> runOnUiThread { setStatus("Bridge registered") }
            "viewer_count" -> {
                remoteViewerCount = obj.optInt("count", 0).coerceAtLeast(0)
            }
            "command" -> {
                val command = obj.optString("command")
                if (command.isNotEmpty()) {
                    btBridge.send(command)
                    runOnUiThread { setStatus("CMD queued to BT: $command") }
                }
            }

            "error" -> runOnUiThread { setStatus("Relay error: ${obj.optString("message")}") }
            else -> Unit
        }
    }

    private fun sendTelemetry() {
        val payload = JSONObject()
            .put("type", "telemetry")
            .put("ts", System.currentTimeMillis())
            .put("status", if (btBridge.isConnected()) "bt_connected" else "bt_disconnected")
            .put("battery", 0)
        ws?.send(payload.toString())
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(128, 72))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { image ->
                handleFrame(image)
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            setStatus("Camera ready")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleFrame(image: ImageProxy) {
        try {
            if (remoteViewerCount <= 0) return

            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameSentAt < currentFrameIntervalMs) return
            val wsQueueSize = ws?.queueSize() ?: 0L
            if (wsQueueSize > maxWsQueueBytes) {
                frameQueuePressureHits.incrementAndGet()
                frameSendAttempts.incrementAndGet()
                return
            }
            val jpegBytes = imageToJpegBytes(image, currentJpegQuality) ?: return
            val ts = System.currentTimeMillis()
            val packet = buildBinaryFramePacket(ts, image.width, image.height, jpegBytes)

            frameSendAttempts.incrementAndGet()
            val sent = ws?.send(packet.toByteString()) == true
            if (sent) {
                frameSendSuccess.incrementAndGet()
                lastFrameSentAt = now
            }
        } finally {
            image.close()
        }
    }

    private fun buildBinaryFramePacket(
        ts: Long,
        width: Int,
        height: Int,
        jpegBytes: ByteArray
    ): ByteArray {
        val payload = ByteArray(13 + jpegBytes.size)
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        bb.put(frameMagic)
        bb.putLong(ts)
        bb.putShort(width.coerceIn(0, 65535).toShort())
        bb.putShort(height.coerceIn(0, 65535).toShort())
        bb.put(jpegBytes)
        return payload
    }

    private fun adaptVideoEncodingProfile() {
        val attempts = frameSendAttempts.getAndSet(0)
        val success = frameSendSuccess.getAndSet(0)
        val queuePressure = frameQueuePressureHits.getAndSet(0)
        if (attempts <= 0 && queuePressure <= 0) return

        val ratio = if (attempts <= 0) 1.0 else success.toDouble() / attempts.toDouble()

        if (ratio < 0.90 || queuePressure > 0) {
            currentVideoFps = (currentVideoFps - 2).coerceAtLeast(minVideoFps)
            currentJpegQuality = (currentJpegQuality - 2).coerceAtLeast(minJpegQuality)
        } else if (ratio > 0.98) {
            currentVideoFps = (currentVideoFps + 1).coerceAtMost(maxVideoFps)
            currentJpegQuality = (currentJpegQuality + 1).coerceAtMost(maxJpegQuality)
        }

        currentFrameIntervalMs = (1000L / currentVideoFps).coerceAtLeast(55L)
    }

    private fun imageToJpegBytes(image: ImageProxy, quality: Int): ByteArray? {
        return try {
            val nv21 = yuv420ToNv21(image)
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val nv21 = ByteArray(width * height + (width * height / 2))
        copyLuma(yPlane.buffer, yPlane.rowStride, width, height, nv21)

        val chromaHeight = height / 2
        val chromaWidth = width / 2

        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        var outputOffset = width * height
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                nv21[outputOffset++] = vBuffer.safeGet(vIndex)
                nv21[outputOffset++] = uBuffer.safeGet(uIndex)
            }
        }

        return nv21
    }

    private fun copyLuma(
        yBuffer: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
        output: ByteArray
    ) {
        var outputOffset = 0
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                output[outputOffset++] = yBuffer.safeGet(rowStart + col)
            }
        }
    }

    private fun ByteBuffer.safeGet(index: Int): Byte {
        if (index < 0 || index >= limit()) return 0
        return get(index)
    }

    private fun setStatus(text: String) {
        txtStatus.text = text
    }

    private fun loadDefaults() {
        editServer.setText(prefs.getString("server", "wss://ecopakremote.onrender.com"))
        editSession.setText(prefs.getString("session", "ecopak-demo"))
        editToken.setText(prefs.getString("token", "6em44j45"))
        editBtMac.setText(prefs.getString("btMac", ""))
    }

    private fun saveDefaults() {
        prefs.edit()
            .putString("server", editServer.text.toString().trim())
            .putString("session", editSession.text.toString().trim())
            .putString("token", editToken.text.toString().trim())
            .putString("btMac", editBtMac.text.toString().trim())
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        keepWsConnected = false
        reconnectHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(telemetryRunnable)
        mainHandler.removeCallbacks(videoAdaptationRunnable)
        ws?.close(1000, "destroy")
        wsClient.dispatcher.executorService.shutdown()
        cameraExecutor.shutdown()
        btBridge.shutdown()
        saveDefaults()
    }

    private class PersistentBluetoothBridge(
        private val context: Context,
        private val statusCallback: (String) -> Unit
    ) {
        private enum class ConnectionMode { NONE, CLASSIC, BLE }

        private data class CandidateDevice(
            val device: BluetoothDevice,
            val source: String
        )

        private val classicUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val bleServiceUuid: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val bleRxUuid: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val bleTxUuid: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private val commandQueue = LinkedBlockingQueue<String>(200)
        private val movementCommands = setOf("w", "s", "a", "b", "x", "f", "e", "c", "g", "m", "r")
        private val heartbeatCommand = "ü"

        @Volatile
        private var running = true

        @Volatile
        private var keepConnected = false

        @Volatile
        private var targetMac: String? = null

        @Volatile
        private var mode = ConnectionMode.NONE

        @Volatile
        private var classicSocket: BluetoothSocket? = null

        @Volatile
        private var bleGatt: BluetoothGatt? = null

        @Volatile
        private var bleRx: BluetoothGattCharacteristic? = null

        @Volatile
        private var bleConnected = false

        @Volatile
        private var bleWriteInProgress = false

        @Volatile
        private var bleLastWriteSuccess = true

        private val bleWriteLock = Object()

        @Volatile
        private var lastControlCommandAt = 0L

        @Volatile
        private var lastHeartbeatAt = 0L

        private val worker = Thread {
            while (running) {
                if (!keepConnected) {
                    sleepQuiet(250)
                    continue
                }

                if (!isConnected()) {
                    connectLoop()
                    continue
                }

                try {
                    val next = commandQueue.poll(120, TimeUnit.MILLISECONDS)
                    if (next != null) {
                        val retries = if (isMovementCommand(next)) 10 else 5
                        if (!sendWithRetry(next, retries)) {
                            statusCallback("Bluetooth write failed, reconnecting...")
                            closeTransports(true)
                            sleepQuiet(350)
                        } else if (isMovementCommand(next)) {
                            lastControlCommandAt = SystemClock.elapsedRealtime()
                        }
                    } else {
                        validateTransport()
                        maybeSendHeartbeat()
                    }
                } catch (_: Exception) {
                    statusCallback("Bluetooth disconnected, reconnecting...")
                    closeTransports(true)
                    sleepQuiet(500)
                }
            }
            closeTransports(true)
        }.apply {
            name = "BT-Persistent-Worker"
            isDaemon = true
            start()
        }

        fun startPersistent(mac: String?) {
            targetMac = normalizeMac(mac)
            keepConnected = true
            if (targetMac.isNullOrBlank()) {
                statusCallback("Bluetooth target: auto ECOPAK discovery")
            } else {
                statusCallback("Bluetooth target: $targetMac")
            }
        }

        fun send(command: String) {
            if (!keepConnected || command.isBlank()) return
            if (!commandQueue.offer(command)) {
                commandQueue.poll()
                commandQueue.offer(command)
            }
        }

        fun isConnected(): Boolean {
            return when (mode) {
                ConnectionMode.CLASSIC -> classicSocket?.isConnected == true
                ConnectionMode.BLE -> bleConnected && bleGatt != null && bleRx != null
                ConnectionMode.NONE -> false
            }
        }

        fun shutdown() {
            running = false
            keepConnected = false
            closeTransports(true)
            worker.interrupt()
        }

        private fun isMovementCommand(command: String): Boolean {
            return movementCommands.contains(command.trim().lowercase())
        }

        private fun maybeSendHeartbeat() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastControlCommandAt < 150L) return
            if (now - lastHeartbeatAt < 350L) return
            if (sendWithRetry(heartbeatCommand, 1)) {
                lastHeartbeatAt = now
            }
        }

        private fun sendWithRetry(command: String, maxRetries: Int): Boolean {
            for (i in 0..maxRetries) {
                val sent = when (mode) {
                    ConnectionMode.CLASSIC -> sendClassic(command)
                    ConnectionMode.BLE -> sendBle(command)
                    ConnectionMode.NONE -> false
                }
                if (sent) return true
                if (!isConnected()) return false
                sleepQuiet(60)
            }
            return false
        }

        private fun sendClassic(command: String): Boolean {
            val socket = classicSocket ?: return false
            return try {
                socket.outputStream.write(command.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun sendBle(command: String): Boolean {
            val gatt = bleGatt ?: return false
            val rx = bleRx ?: return false
            if (!bleConnected) return false

            return synchronized(bleWriteLock) {
                if (bleWriteInProgress) {
                    try {
                        bleWriteLock.wait(80)
                    } catch (_: InterruptedException) {
                        return@synchronized false
                    }
                }
                if (bleWriteInProgress || !bleConnected) return@synchronized false

                val noResp = (rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                rx.writeType = if (noResp) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                rx.value = command.toByteArray(Charsets.UTF_8)
                bleLastWriteSuccess = true
                bleWriteInProgress = true
                val queued = runCatching { gatt.writeCharacteristic(rx) }.getOrDefault(false)
                if (!queued) {
                    bleWriteInProgress = false
                    return@synchronized false
                }
                if (noResp) {
                    bleWriteInProgress = false
                    return@synchronized true
                }
                try {
                    bleWriteLock.wait(120)
                } catch (_: InterruptedException) {
                    bleWriteInProgress = false
                    return@synchronized false
                }
                bleWriteInProgress = false
                bleLastWriteSuccess
            }
        }

        private fun validateTransport() {
            when (mode) {
                ConnectionMode.CLASSIC -> {
                    val socket = classicSocket ?: throw IllegalStateException("No classic socket")
                    if (!socket.isConnected) throw IllegalStateException("Classic socket disconnected")
                    socket.inputStream.available()
                }
                ConnectionMode.BLE -> if (!bleConnected || bleGatt == null || bleRx == null) {
                    throw IllegalStateException("BLE disconnected")
                }
                ConnectionMode.NONE -> throw IllegalStateException("No transport")
            }
        }

        @SuppressLint("MissingPermission")
        private fun connectLoop() {
            if (!hasConnectPermission()) {
                statusCallback("Bluetooth permission missing")
                sleepQuiet(1500)
                return
            }

            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                statusCallback("Bluetooth adapter missing")
                sleepQuiet(1500)
                return
            }
            if (!adapter.isEnabled) {
                statusCallback("Bluetooth is off")
                sleepQuiet(1500)
                return
            }

            val candidate = resolveTargetDevice(adapter)
            if (candidate == null) {
                statusCallback("ECOPAK device not found, retrying...")
                sleepQuiet(1800)
                return
            }

            val display = candidate.device.name ?: candidate.device.address
            statusCallback("Connecting $display (${candidate.source})")
            val ok = connectClassic(candidate.device)
            if (ok) {
                statusCallback("Bluetooth connected (classic): $display")
                return
            }

            if (candidate.device.bondState != BluetoothDevice.BOND_BONDED) {
                statusCallback("Bluetooth connect failed: pair robot in Android Bluetooth settings")
            } else {
                statusCallback("Bluetooth connect failed, retrying...")
            }
            closeTransports(true)
            sleepQuiet(1400)
        }

        @SuppressLint("MissingPermission")
        private fun resolveTargetDevice(adapter: BluetoothAdapter): CandidateDevice? {
            val manual = targetMac
            if (!manual.isNullOrBlank()) {
                val device = runCatching { adapter.getRemoteDevice(manual) }.getOrNull()
                if (device != null) return CandidateDevice(device, "manual")
            }

            val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
                .sortedBy { it.name ?: it.address }
            bonded.firstOrNull { isEcopakName(it.name) }?.let { return CandidateDevice(it, "bonded_ecopak") }

            // If name is not exposed but only one bonded robot-like module exists, try it.
            bonded.firstOrNull()?.let { return CandidateDevice(it, "bonded_any") }
            return null
        }

        @SuppressLint("MissingPermission")
        private fun connectClassic(device: BluetoothDevice): Boolean {
            if (!hasConnectPermission()) return false
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            runCatching { adapter.cancelDiscovery() }
            closeClassicSocket()

            val sockets = listOfNotNull(
                runCatching {
                    val method = device.javaClass.getMethod(
                        "createInsecureRfcommSocketToServiceRecord",
                        UUID::class.java
                    )
                    method.invoke(device, classicUuid) as? BluetoothSocket
                }.getOrNull(),
                runCatching { device.createInsecureRfcommSocketToServiceRecord(classicUuid) }.getOrNull(),
            )
            for (socket in sockets) {
                try {
                    socket.connect()
                    classicSocket = socket
                    mode = ConnectionMode.CLASSIC
                    return true
                } catch (_: Exception) {
                    runCatching { socket.close() }
                }
            }
            return false
        }

        @SuppressLint("MissingPermission")
        private fun connectBle(device: BluetoothDevice): Boolean {
            if (!hasConnectPermission()) return false
            closeBle()
            val readySignal = CountDownLatch(1)
            var ready = false
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        bleConnected = true
                        runCatching { gatt.discoverServices() }
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        bleConnected = false
                        readySignal.countDown()
                        runCatching { gatt.close() }
                    }
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        readySignal.countDown()
                        return
                    }
                    val service = gatt.getService(bleServiceUuid)
                    val rx = service?.getCharacteristic(bleRxUuid)
                    val tx = service?.getCharacteristic(bleTxUuid)
                    if (rx == null || tx == null) {
                        readySignal.countDown()
                        return
                    }
                    bleRx = rx
                    runCatching { gatt.setCharacteristicNotification(tx, true) }
                    tx.getDescriptor(cccdUuid)?.let { descriptor ->
                        runCatching {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                    ready = true
                    readySignal.countDown()
                }
                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    synchronized(bleWriteLock) {
                        bleLastWriteSuccess = status == BluetoothGatt.GATT_SUCCESS
                        bleWriteInProgress = false
                        bleWriteLock.notifyAll()
                    }
                }
            }

            bleGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
            if (bleGatt == null) return false
            val signaled = runCatching { readySignal.await(12, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!signaled || !ready || !bleConnected || bleRx == null) {
                closeBle()
                return false
            }
            mode = ConnectionMode.BLE
            return true
        }

        @SuppressLint("MissingPermission")
        private fun scanBleForEcopak(adapter: BluetoothAdapter, timeoutMs: Long): BluetoothDevice? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
            if (!hasScanPermission()) return null
            val scanner = adapter.bluetoothLeScanner ?: return null
            val found = AtomicReference<BluetoothDevice?>(null)
            val done = CountDownLatch(1)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    if (isEcopakName(device.name) && found.compareAndSet(null, device)) {
                        done.countDown()
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    done.countDown()
                }
            }
            return try {
                scanner.startScan(
                    null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    callback
                )
                done.await(timeoutMs, TimeUnit.MILLISECONDS)
                found.get()
            } catch (_: Exception) {
                null
            } finally {
                runCatching { scanner.stopScan(callback) }
            }
        }

        private fun hasConnectPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun hasScanPermission(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            }
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun isEcopakName(name: String?): Boolean {
            if (name.isNullOrBlank()) return false
            return name.startsWith("ECOPAK-", ignoreCase = true) ||
                name.contains("ECOPAK", ignoreCase = true)
        }

        private fun normalizeMac(raw: String?): String? {
            val value = raw?.trim()?.uppercase() ?: return null
            if (value.isBlank()) return null
            val pattern = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
            return if (pattern.matches(value)) value else null
        }

        private fun closeTransports(clearQueue: Boolean) {
            closeClassicSocket()
            closeBle()
            mode = ConnectionMode.NONE
            if (clearQueue) commandQueue.clear()
        }

        private fun closeClassicSocket() {
            try {
                classicSocket?.close()
            } catch (_: Exception) {
                // ignore
            } finally {
                classicSocket = null
            }
        }

        private fun closeBle() {
            synchronized(bleWriteLock) {
                bleWriteInProgress = false
                bleWriteLock.notifyAll()
            }
            runCatching { bleGatt?.disconnect() }
            runCatching { bleGatt?.close() }
            bleGatt = null
            bleRx = null
            bleConnected = false
        }

        private fun sleepQuiet(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                // ignore
            }
        }
    }
}
