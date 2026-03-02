package com.ecopak.remote

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {
    private data class PendingFrame(
        val jpegBytes: ByteArray,
        val ts: Long
    )

    private lateinit var editServer: EditText
    private lateinit var editSession: EditText
    private lateinit var editToken: EditText
    private lateinit var txtStatus: TextView
    private lateinit var txtSpeed: TextView
    private lateinit var imgStream: ImageView

    private var ws: WebSocket? = null
    private var shouldKeepWs = false
    private val reconnectHandler = Handler(Looper.getMainLooper())

    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("remote_prefs", MODE_PRIVATE) }
    private val latestFrameRef = AtomicReference<PendingFrame?>(null)

    @Volatile
    private var decodeWorkerRunning = true

    @Volatile
    private var lastUiFrameAt = 0L

    @Volatile
    private var latencyEwmaMs = -1.0

    private val latencyAlpha = 0.2
    private val uiFrameIntervalMs = 1000L / 18L
    private val frameMagic = 0x45

    private var movingForward = false
    private var movingBackward = false

    private var lightOn = false
    private var valveOn = false
    private var sensorsOn = true

    private val speedValues = listOf("0", "1", "3", "5", "7", "9", "11", "13", "15", "17")
    private val speedCommands = listOf("h", "i", "j", "k", "l", "s", "t", "u", "v", "y")
    private var speedIndex = 2

    private val reconnectRunnable = Runnable {
        if (shouldKeepWs) {
            openWebSocket()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editServer = findViewById(R.id.editServer)
        editSession = findViewById(R.id.editSession)
        editToken = findViewById(R.id.editToken)
        txtStatus = findViewById(R.id.txtStatus)
        txtSpeed = findViewById(R.id.txtSpeed)
        imgStream = findViewById(R.id.imgStream)

        loadDefaults()
        bindControls()
        updateSpeedLabel()
        startDecodeWorker()
        connectRelay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindControls() {
        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectRelay() }

        findViewById<Button>(R.id.btnForward).setOnClickListener {
            if (speedIndex > 0) {
                movingForward = true
                movingBackward = false
                sendCommand("a")
            } else {
                setStatus("Speed is 0; increase speed first")
            }
        }

        findViewById<Button>(R.id.btnBackward).setOnClickListener {
            if (speedIndex > 0) {
                movingForward = false
                movingBackward = true
                sendCommand("b")
            } else {
                setStatus("Speed is 0; increase speed first")
            }
        }

        findViewById<Button>(R.id.btnRobotStop).setOnClickListener {
            movingForward = false
            movingBackward = false
            sendCommand("c")
        }

        findViewById<Button>(R.id.btnEmergencyStop).setOnClickListener {
            movingForward = false
            movingBackward = false
            sendCommand("c")
            sendCommand("c")
            sendCommand("c")
            setStatus("Emergency stop sent")
        }

        findViewById<Button>(R.id.btnTurnLeft).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (movingForward || movingBackward) {
                        sendCommand("x")
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendCommand(getResumeCommand())
                    true
                }

                else -> true
            }
        }

        findViewById<Button>(R.id.btnTurnRight).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (movingForward || movingBackward) {
                        sendCommand("w")
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendCommand(getResumeCommand())
                    true
                }

                else -> true
            }
        }

        findViewById<Button>(R.id.btnTankLeft).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sendCommand("f")
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendCommand(getResumeCommand())
                    true
                }

                else -> true
            }
        }

        findViewById<Button>(R.id.btnTankRight).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sendCommand("e")
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendCommand(getResumeCommand())
                    true
                }

                else -> true
            }
        }

        findViewById<Button>(R.id.btnSpeedUp).setOnClickListener {
            if (speedIndex < speedCommands.lastIndex) {
                speedIndex += 1
                sendCommand(speedCommands[speedIndex])
                updateSpeedLabel()
            }
        }

        findViewById<Button>(R.id.btnSpeedDown).setOnClickListener {
            if (speedIndex > 0) {
                speedIndex -= 1
                sendCommand(speedCommands[speedIndex])
                updateSpeedLabel()
            }
        }

        findViewById<Button>(R.id.btnSpeedSlow).setOnClickListener {
            speedIndex = 2
            sendCommand(speedCommands[speedIndex])
            updateSpeedLabel()
        }

        findViewById<Button>(R.id.btnSpeedNormal).setOnClickListener {
            speedIndex = 5
            sendCommand(speedCommands[speedIndex])
            updateSpeedLabel()
        }

        findViewById<Button>(R.id.btnBrushLeft).setOnClickListener { sendCommand("g") }
        findViewById<Button>(R.id.btnBrushRight).setOnClickListener { sendCommand("m") }
        findViewById<Button>(R.id.btnBrushStop).setOnClickListener { sendCommand("r") }

        findViewById<Button>(R.id.btnLight).setOnClickListener {
            lightOn = !lightOn
            sendCommand("p")
            setStatus(if (lightOn) "Light ON" else "Light OFF")
        }

        findViewById<Button>(R.id.btnValve).setOnClickListener {
            valveOn = !valveOn
            sendCommand("o")
            setStatus(if (valveOn) "Valve ON" else "Valve OFF")
        }

        findViewById<Button>(R.id.btnSensors).setOnClickListener {
            sensorsOn = !sensorsOn
            sendCommand("z")
            setStatus(if (sensorsOn) "Sensors ON" else "Sensors OFF")
        }

        findViewById<Button>(R.id.btnAngleSensor).setOnClickListener {
            sendCommand("d")
            setStatus("Angle sensor enable sent")
        }
    }

    private fun connectRelay() {
        shouldKeepWs = true
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
        latestFrameRef.set(null)
        latencyEwmaMs = -1.0
        setStatus("Connecting...")

        val request = Request.Builder().url(wsUrl).build()
        ws = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val reg = JSONObject()
                    .put("type", "register_remote")
                    .put("sessionId", session)
                    .put("token", token)
                webSocket.send(reg.toString())
                runOnUiThread { setStatus("Relay connected") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRelayMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryFrame(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { setStatus("Relay closed: $code") }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { setStatus("Relay error: ${t.message}") }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldKeepWs) return
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectHandler.postDelayed(reconnectRunnable, 2500)
    }

    private fun handleRelayMessage(text: String) {
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }

        when (obj.optString("type")) {
            "registered" -> {
                val bridgeConnected = obj.optBoolean("bridgeConnected", false)
                runOnUiThread {
                    setStatus(if (bridgeConnected) "Ready (bridge online)" else "Connected (bridge offline)")
                }
            }

            "bridge_status" -> {
                val connected = obj.optBoolean("connected", false)
                runOnUiThread {
                    setStatus(if (connected) "Bridge online" else "Bridge offline")
                }
            }

            "video_frame" -> {
                val b64 = obj.optString("jpegBase64")
                val ts = obj.optLong("ts", System.currentTimeMillis())
                if (b64.isNotEmpty()) {
                    runCatching {
                        Base64.decode(b64, Base64.DEFAULT)
                    }.getOrNull()?.let { jpeg ->
                        latestFrameRef.set(PendingFrame(jpeg, ts))
                    }
                }
            }

            "error" -> runOnUiThread {
                setStatus("Relay error: ${obj.optString("message")}")
            }
        }
    }

    private fun handleBinaryFrame(packet: ByteArray) {
        if (packet.size < 13) return
        if ((packet[0].toInt() and 0xFF) != frameMagic) return

        val bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        bb.position(1)
        val ts = bb.long
        val jpeg = packet.copyOfRange(13, packet.size)
        if (jpeg.isEmpty()) return
        latestFrameRef.set(PendingFrame(jpeg, ts))
    }

    private fun startDecodeWorker() {
        decodeExecutor.execute {
            while (decodeWorkerRunning) {
                try {
                    val pending = latestFrameRef.getAndSet(null)
                    if (pending == null) {
                        Thread.sleep(8)
                        continue
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiFrameAt < uiFrameIntervalMs) {
                        continue
                    }

                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                        inSampleSize = 1
                    }

                    val bmp = BitmapFactory.decodeByteArray(
                        pending.jpegBytes,
                        0,
                        pending.jpegBytes.size,
                        options
                    ) ?: continue
                    lastUiFrameAt = now

                    val latencyNow = (System.currentTimeMillis() - pending.ts).coerceAtLeast(0)
                    latencyEwmaMs = if (latencyEwmaMs < 0.0) {
                        latencyNow.toDouble()
                    } else {
                        (latencyAlpha * latencyNow) + ((1.0 - latencyAlpha) * latencyEwmaMs)
                    }

                    runOnUiThread {
                        imgStream.setImageBitmap(bmp)
                        txtStatus.text = "Video OK | latency: ${latencyEwmaMs.toInt()}ms"
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    // ignore decode errors
                }
            }
        }
    }

    private fun sendCommand(command: String) {
        val msg = JSONObject()
            .put("type", "command")
            .put("command", command)
        ws?.send(msg.toString())
    }

    private fun getResumeCommand(): String {
        return when {
            movingForward -> "a"
            movingBackward -> "b"
            else -> "c"
        }
    }

    private fun updateSpeedLabel() {
        txtSpeed.text = "Speed: ${speedValues[speedIndex]} (${speedCommands[speedIndex]})"
    }

    private fun setStatus(text: String) {
        txtStatus.text = text
    }

    private fun loadDefaults() {
        editServer.setText(prefs.getString("server", "wss://ecopakremote.onrender.com"))
        editSession.setText(prefs.getString("session", "ecopak-demo"))
        editToken.setText(prefs.getString("token", "6em44j45"))
        speedIndex = prefs.getInt("speedIndex", 2).coerceIn(0, speedCommands.lastIndex)
    }

    private fun saveDefaults() {
        prefs.edit()
            .putString("server", editServer.text.toString().trim())
            .putString("session", editSession.text.toString().trim())
            .putString("token", editToken.text.toString().trim())
            .putInt("speedIndex", speedIndex)
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldKeepWs = false
        reconnectHandler.removeCallbacks(reconnectRunnable)
        ws?.close(1000, "destroy")
        wsClient.dispatcher.executorService.shutdown()
        decodeWorkerRunning = false
        decodeExecutor.shutdownNow()
        saveDefaults()
    }
}
