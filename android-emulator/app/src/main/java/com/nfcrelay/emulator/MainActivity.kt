package com.nfcrelay.emulator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "NFCEmulator"
        const val ACTION_APDU_COMMAND = "com.nfcrelay.emulator.APDU_COMMAND"
        const val ACTION_NFC_DEACTIVATED = "com.nfcrelay.emulator.NFC_DEACTIVATED"
        const val EXTRA_APDU = "apdu"
        const val EXTRA_COMMAND_ID = "commandId"
    }
    
    private var webSocket: WebSocket? = null
    private var sessionId: String? = null
    private var nfcAdapter: NfcAdapter? = null
    
    private lateinit var statusText: TextView
    private lateinit var sessionCodeInput: EditText
    private lateinit var logText: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var connectButton: Button
    private lateinit var joinSessionButton: Button
    private lateinit var hceStatusText: TextView
    
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    
    // Receiver for APDU commands from HCE service
    private val apduReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_APDU_COMMAND -> {
                    val apdu = intent.getStringExtra(EXTRA_APDU) ?: return
                    val commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: return
                    handleApduFromHce(apdu, commandId)
                }
                ACTION_NFC_DEACTIVATED -> {
                    val reason = intent.getStringExtra("reason") ?: "Unknown"
                    log("NFC deactivated: $reason")
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        initNfc()
        registerReceivers()
    }
    
    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        sessionCodeInput = findViewById(R.id.sessionCodeInput)
        logText = findViewById(R.id.logText)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        connectButton = findViewById(R.id.connectButton)
        joinSessionButton = findViewById(R.id.joinSessionButton)
        hceStatusText = findViewById(R.id.hceStatusText)
        
        connectButton.setOnClickListener {
            val url = serverUrlInput.text.toString()
            if (url.isNotEmpty()) {
                connectToServer(url)
            }
        }
        
        joinSessionButton.setOnClickListener {
            joinSession()
        }
        joinSessionButton.isEnabled = false
    }
    
    private fun initNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        if (nfcAdapter == null) {
            hceStatusText.text = "❌ NFC stöds inte"
            log("NFC not supported")
            return
        }
        
        if (!nfcAdapter!!.isEnabled) {
            hceStatusText.text = "⚠️ NFC är avstängt"
            log("NFC is disabled")
            return
        }
        
        // Check HCE support
        val cardEmulation = CardEmulation.getInstance(nfcAdapter)
        if (cardEmulation != null) {
            hceStatusText.text = "✅ HCE redo"
            log("HCE initialized")
            
            // Check if we're the default service
            val component = android.content.ComponentName(this, PassportHceService::class.java)
            val isDefault = cardEmulation.isDefaultServiceForCategory(
                component,
                CardEmulation.CATEGORY_OTHER
            )
            
            if (!isDefault) {
                log("⚠️ Set this app as default NFC payment app in settings")
            }
        } else {
            hceStatusText.text = "❌ HCE stöds inte"
            log("HCE not supported")
        }
    }
    
    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(ACTION_APDU_COMMAND)
            addAction(ACTION_NFC_DEACTIVATED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(apduReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(apduReceiver, filter)
        }
    }
    
    private fun handleApduFromHce(apduHex: String, commandId: String) {
        log("← APDU from reader: ${apduHex.take(20)}...")
        
        // Send to server for relay to the real passport
        sendToServer(JSONObject().apply {
            put("type", "apdu_command")
            put("apdu", apduHex)
            put("commandId", commandId)
        })
    }
    
    private fun connectToServer(url: String) {
        log("Connecting to $url...")
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainScope.launch {
                    log("Connected to server")
                    statusText.text = "🔗 Ansluten till server"
                    connectButton.isEnabled = false
                    joinSessionButton.isEnabled = true
                }
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                mainScope.launch {
                    handleServerMessage(text)
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                mainScope.launch {
                    log("Connection closing: $reason")
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainScope.launch {
                    log("Connection failed: ${t.message}")
                    statusText.text = "❌ Anslutning misslyckades"
                    connectButton.isEnabled = true
                    joinSessionButton.isEnabled = false
                }
            }
        })
    }
    
    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            
            when (type) {
                "welcome" -> {
                    log("Client ID: ${json.optString("clientId")}")
                }
                
                "session_joined" -> {
                    sessionId = json.optString("sessionId")
                    log("Joined session: $sessionId")
                }
                
                "session_ready" -> {
                    log("Session ready! Waiting for passport...")
                    statusText.text = "✅ Session redo - väntar på pass i Sverige"
                }
                
                "passport_ready" -> {
                    log("🛂 Passport connected! Ready for relay!")
                    statusText.text = "🛂 Pass anslutet! Lägg telefonen på NFC-läsaren"
                }
                
                "apdu_response" -> {
                    // Response from real passport via reader
                    val apdu = json.optString("apdu")
                    val commandId = json.optString("commandId")
                    val latency = json.optInt("latency", -1)
                    
                    log("→ Response from passport (${latency}ms): ${apdu.take(20)}...")
                    
                    // Forward to HCE service
                    PassportHceService.instance?.onResponseReceived(apdu, commandId)
                }
                
                "peer_disconnected" -> {
                    log("Reader disconnected")
                    statusText.text = "⚠️ Reader frånkopplad"
                }
                
                "error" -> {
                    log("Error: ${json.optString("message")}")
                }
            }
        } catch (e: Exception) {
            log("Error parsing message: ${e.message}")
        }
    }
    
    private fun joinSession() {
        val code = sessionCodeInput.text.toString().uppercase()
        if (code.length != 6) {
            log("Session code must be 6 characters")
            return
        }
        
        sendToServer(JSONObject().apply {
            put("type", "join_session")
            put("sessionId", code)
            put("role", "emulator")
        })
    }
    
    private fun sendToServer(json: JSONObject) {
        webSocket?.send(json.toString())
    }
    
    private fun log(message: String) {
        Log.d(TAG, message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logText.append("[$timestamp] $message\n")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(apduReceiver)
        webSocket?.close(1000, "Activity destroyed")
        mainScope.cancel()
    }
}
