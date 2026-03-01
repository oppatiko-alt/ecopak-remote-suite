package com.ecopak.remote

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
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
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var editServer: EditText
    private lateinit var editSession: EditText
    private lateinit var editToken: EditText
    private lateinit var txtStatus: TextView
    private lateinit var imgStream: ImageView

    private var ws: WebSocket? = null

    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editServer = findViewById(R.id.editServer)
        editSession = findViewById(R.id.editSession)
        editToken = findViewById(R.id.editToken)
        txtStatus = findViewById(R.id.txtStatus)
        imgStream = findViewById(R.id.imgStream)

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            connectRelay()
        }

        findViewById<Button>(R.id.btnForward).setOnClickListener { sendCommand("a") }
        findViewById<Button>(R.id.btnBack).setOnClickListener { sendCommand("b") }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { sendCommand("e") }
        findViewById<Button>(R.id.btnRight).setOnClickListener { sendCommand("f") }
        findViewById<Button>(R.id.btnStop).setOnClickListener { sendCommand("c") }
    }

    private fun connectRelay() {
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
        setStatus("Connecting...")

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

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { setStatus("Relay closed: $code") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { setStatus("Relay error: ${t.message}") }
            }
        })
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
                decodeExecutor.execute {
                    try {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val latency = System.currentTimeMillis() - ts
                        runOnUiThread {
                            imgStream.setImageBitmap(bmp)
                            txtStatus.text = "Video OK | latency: ${latency}ms"
                        }
                    } catch (_: Exception) {
                        // ignore single frame decode errors
                    }
                }
            }

            "error" -> runOnUiThread {
                setStatus("Relay error: ${obj.optString("message")}")
            }
        }
    }

    private fun sendCommand(command: String) {
        val msg = JSONObject()
            .put("type", "command")
            .put("command", command)
        ws?.send(msg.toString())
    }

    private fun setStatus(text: String) {
        txtStatus.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        ws?.close(1000, "destroy")
        wsClient.dispatcher.executorService.shutdown()
        decodeExecutor.shutdown()
    }
}
