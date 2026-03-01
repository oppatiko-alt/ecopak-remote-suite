package com.ecopak.bridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
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
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var txtStatus: TextView
    private lateinit var editServer: EditText
    private lateinit var editSession: EditText
    private lateinit var editToken: EditText
    private lateinit var editBtMac: EditText

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var ws: WebSocket? = null
    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var lastFrameSentAt = 0L
    private val frameIntervalMs = 160L

    private val btBridge = BluetoothBridge()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasAllPermissions()) {
            startCamera()
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

        findViewById<Button>(R.id.btnConnectServer).setOnClickListener {
            connectServer()
        }

        findViewById<Button>(R.id.btnConnectBt).setOnClickListener {
            connectBluetooth()
        }

        if (hasAllPermissions()) {
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    private fun requiredPermissions(): Array<String> {
        val p = mutableListOf(
            Manifest.permission.CAMERA
        )
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

        val request = Request.Builder().url(wsUrl).build()
        setStatus("Connecting relay...")

        ws = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
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
                runOnUiThread { setStatus("Relay closed: $code") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { setStatus("Relay error: ${t.message}") }
            }
        })
    }

    private fun connectBluetooth() {
        val mac = editBtMac.text.toString().trim()
        if (mac.isEmpty()) {
            setStatus("Bluetooth MAC required")
            return
        }

        ioExecutor.execute {
            val ok = btBridge.connect(mac)
            runOnUiThread {
                setStatus(if (ok) "Bluetooth connected" else "Bluetooth failed")
            }
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
            "command" -> {
                val command = obj.optString("command")
                if (command.isNotEmpty()) {
                    btBridge.send(command)
                    runOnUiThread { setStatus("CMD -> BT: $command") }
                }
            }
            "error" -> runOnUiThread { setStatus("Relay error: ${obj.optString("message")}") }
            else -> Unit
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
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
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameSentAt < frameIntervalMs) {
                return
            }
            val base64 = imageToJpegBase64(image, 45) ?: return
            lastFrameSentAt = now

            val msg = JSONObject()
                .put("type", "video_frame")
                .put("jpegBase64", base64)
                .put("ts", System.currentTimeMillis())
                .put("width", image.width)
                .put("height", image.height)

            ws?.send(msg.toString())
        } finally {
            image.close()
        }
    }

    private fun imageToJpegBase64(image: ImageProxy, quality: Int): String? {
        return try {
            val nv21 = yuv420ToNv21(image)
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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

    override fun onDestroy() {
        super.onDestroy()
        ws?.close(1000, "destroy")
        wsClient.dispatcher.executorService.shutdown()
        cameraExecutor.shutdown()
        ioExecutor.shutdown()
        btBridge.close()
    }

    private class BluetoothBridge {
        private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private var socket: BluetoothSocket? = null

        fun connect(mac: String): Boolean {
            return try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
                val device = adapter.getRemoteDevice(mac)
                adapter.cancelDiscovery()

                close()
                val s = device.createRfcommSocketToServiceRecord(uuid)
                s.connect()
                socket = s
                true
            } catch (_: Exception) {
                close()
                false
            }
        }

        fun send(command: String) {
            try {
                val payload = (command + "\n").toByteArray(Charsets.UTF_8)
                socket?.outputStream?.write(payload)
                socket?.outputStream?.flush()
            } catch (_: Exception) {
                close()
            }
        }

        fun close() {
            try {
                socket?.close()
            } catch (_: Exception) {
                // ignore
            } finally {
                socket = null
            }
        }
    }
}
