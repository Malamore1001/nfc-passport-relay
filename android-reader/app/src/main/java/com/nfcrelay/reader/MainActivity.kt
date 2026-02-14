package com.nfcrelay.reader

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "NFCReader"
        private const val CAMERA_PERMISSION_CODE = 100
    }
    
    // NFC
    private var nfcAdapter: NfcAdapter? = null
    private var isoDep: IsoDep? = null
    private var isNfcConnected = false
    private var currentTag: Tag? = null
    private var bacProtocol: BacProtocol? = null
    private var paceProtocol: PaceProtocol? = null
    
    // eMRTD applet AID (passport)
    private val EMRTD_AID = byteArrayOf(
        0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01
    )
    
    // WebSocket
    private var webSocket: WebSocket? = null
    private var sessionId: String? = null
    
    // MRZ
    private var mrzData: MrzParser.MrzData? = null
    private var bacKeys: MrzParser.BacKeys? = null
    
    // Camera
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // UI
    private lateinit var statusText: TextView
    private lateinit var sessionCodeText: TextView
    private lateinit var logText: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var connectButton: Button
    private lateinit var createSessionButton: Button
    private lateinit var scanMrzButton: Button
    private lateinit var mrzManualInput: LinearLayout
    private lateinit var mrzLine1Input: EditText
    private lateinit var mrzLine2Input: EditText
    private lateinit var saveMrzButton: Button
    private lateinit var cameraPreview: PreviewView
    private lateinit var cameraContainer: FrameLayout
    private lateinit var mrzStatusText: TextView
    private lateinit var passportStatusText: TextView
    private lateinit var logScrollView: ScrollView
    
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        initViews()
        initNfc()
    }
    
    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        sessionCodeText = findViewById(R.id.sessionCodeText)
        logText = findViewById(R.id.logText)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        connectButton = findViewById(R.id.connectButton)
        createSessionButton = findViewById(R.id.createSessionButton)
        scanMrzButton = findViewById(R.id.scanMrzButton)
        mrzManualInput = findViewById(R.id.mrzManualInput)
        mrzLine1Input = findViewById(R.id.mrzLine1Input)
        mrzLine2Input = findViewById(R.id.mrzLine2Input)
        saveMrzButton = findViewById(R.id.saveMrzButton)
        cameraPreview = findViewById(R.id.cameraPreview)
        cameraContainer = findViewById(R.id.cameraContainer)
        mrzStatusText = findViewById(R.id.mrzStatusText)
        passportStatusText = findViewById(R.id.passportStatusText)
        logScrollView = findViewById(R.id.logScrollView)
        
        connectButton.setOnClickListener {
            val url = serverUrlInput.text.toString()
            if (url.isNotEmpty()) {
                connectToServer(url)
            }
        }
        
        createSessionButton.setOnClickListener {
            createSession()
        }
        createSessionButton.isEnabled = false
        
        scanMrzButton.setOnClickListener {
            if (cameraContainer.visibility == View.VISIBLE) {
                stopCamera()
                mrzManualInput.visibility = View.VISIBLE
                scanMrzButton.text = "📷 Öppna kamera"
            } else {
                startCamera()
                mrzManualInput.visibility = View.GONE
                scanMrzButton.text = "✏️ Skriv in manuellt istället"
            }
        }
        
        saveMrzButton.setOnClickListener {
            parseMrzManual()
        }
        
        // AUTO-START CAMERA för MRZ-skanning!
        mrzManualInput.visibility = View.GONE
        startCamera()
    }
    
    private fun initNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        if (nfcAdapter == null) {
            statusText.text = "❌ NFC stöds inte på denna enhet"
            log("NFC not supported")
            return
        }
        
        if (!nfcAdapter!!.isEnabled) {
            statusText.text = "⚠️ NFC är avstängt - aktivera i inställningar"
            log("NFC is disabled")
            return
        }
        
        statusText.text = "✅ NFC redo"
        log("NFC initialized")
    }
    
    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
    }
    
    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }
    
    private fun enableNfcForegroundDispatch() {
        nfcAdapter?.let { adapter ->
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            val techList = arrayOf(
                arrayOf(IsoDep::class.java.name)
            )
            
            adapter.enableForegroundDispatch(this, pendingIntent, null, techList)
        }
    }
    
    private fun disableNfcForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        
        val action = intent.action
        log("📡 NFC Intent: $action")
        
        when (action) {
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val tag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
                }
                
                if (tag != null) {
                    log("🎫 Tag technologies: ${tag.techList.joinToString(", ")}")
                    handleNfcTag(tag)
                } else {
                    log("❌ No tag in intent")
                }
            }
        }
    }
    
    private fun handleNfcTag(tag: Tag) {
        log("🎫 NFC tag detected!")
        passportStatusText.text = "📱 Pass detekterat..."
        
        // Store tag for potential reconnection
        currentTag = tag
        
        // Check if IsoDep is supported
        val iso = IsoDep.get(tag)
        if (iso == null) {
            log("❌ This tag does not support IsoDep (not a passport?)")
            passportStatusText.text = "❌ Kortet stöder inte IsoDep"
            isNfcConnected = false
            return
        }
        
        try {
            // Close previous connection if any
            try {
                isoDep?.close()
            } catch (e: Exception) {
                // Ignore
            }
            
            isoDep = iso
            
            // Set timeout BEFORE connecting for better reliability
            isoDep?.timeout = 5000  // 5 sekunder initial timeout
            
            log("📡 Connecting to NFC...")
            isoDep?.connect()
            
            if (isoDep?.isConnected != true) {
                log("❌ Failed to connect - isConnected = false")
                passportStatusText.text = "❌ Kunde inte ansluta"
                isNfcConnected = false
                return
            }
            
            // Increase timeout after connection for long operations
            isoDep?.timeout = 30000  // 30 sekunder för APDU-kommandon
            
            log("✅ NFC Connected!")
            log("   MaxTransceiveLength: ${isoDep?.maxTransceiveLength}")
            log("   Timeout: ${isoDep?.timeout}ms")
            log("   Extended: ${isoDep?.isExtendedLengthApduSupported}")
            
            // Test connection by selecting the eMRTD applet
            log("📡 Selecting eMRTD applet...")
            val selectResponse = selectEmrtdApplet()
            
            if (selectResponse != null && selectResponse.size >= 2) {
                val sw1 = selectResponse[selectResponse.size - 2].toInt() and 0xFF
                val sw2 = selectResponse[selectResponse.size - 1].toInt() and 0xFF
                
                if (sw1 == 0x90 && sw2 == 0x00) {
                    log("✅ eMRTD applet selected successfully!")
                    isNfcConnected = true
                    passportStatusText.text = "✅ Pass anslutet - redo för relay!"
                    
                    // Notify server that passport is ready
                    sendToServer(JSONObject().apply {
                        put("type", "passport_ready")
                        put("relayMode", true)
                        put("maxTransceiveLength", isoDep?.maxTransceiveLength ?: 0)
                        put("extendedLength", isoDep?.isExtendedLengthApduSupported ?: false)
                    })
                } else {
                    log("⚠️ SELECT returned: ${String.format("%02X%02X", sw1, sw2)}")
                    // Still might work for relay
                    isNfcConnected = true
                    passportStatusText.text = "⚠️ Pass anslutet (varning: ${String.format("%02X%02X", sw1, sw2)})"
                    
                    sendToServer(JSONObject().apply {
                        put("type", "passport_ready")
                        put("relayMode", true)
                        put("maxTransceiveLength", isoDep?.maxTransceiveLength ?: 0)
                    })
                }
            } else {
                log("❌ No response from SELECT command")
                isNfcConnected = false
                passportStatusText.text = "❌ Passet svarar inte"
            }
            
        } catch (e: android.nfc.TagLostException) {
            log("❌ Tag lost - håll passet stilla!")
            passportStatusText.text = "❌ Tappade kontakten - håll passet stilla!"
            isNfcConnected = false
        } catch (e: java.io.IOException) {
            log("❌ IO Error: ${e.message}")
            passportStatusText.text = "❌ Kommunikationsfel: ${e.message}"
            isNfcConnected = false
        } catch (e: Exception) {
            log("❌ Error: ${e.javaClass.simpleName}: ${e.message}")
            passportStatusText.text = "❌ Fel: ${e.message}"
            isNfcConnected = false
        }
    }
    
    private fun selectEmrtdApplet(): ByteArray? {
        // SELECT command: 00 A4 04 0C [length] [AID]
        val selectCmd = byteArrayOf(
            0x00,                    // CLA
            0xA4.toByte(),           // INS (SELECT)
            0x04,                    // P1 (Select by DF name)
            0x0C,                    // P2 (No response data)
            EMRTD_AID.size.toByte()  // Lc
        ) + EMRTD_AID
        
        return try {
            isoDep?.transceive(selectCmd)
        } catch (e: Exception) {
            log("SELECT error: ${e.message}")
            null
        }
    }
    
    // Authentication is handled by the emulator side via APDU relay
    // This reader just forwards raw APDU commands to the passport
    
    // ==================== MRZ Scanning ====================
    
    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, 
                arrayOf(Manifest.permission.CAMERA), 
                CAMERA_PERMISSION_CODE
            )
            return
        }
        
        cameraContainer.visibility = View.VISIBLE
        scanMrzButton.text = "❌ Stäng kamera"
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }
            
            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                log("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(this))
        
        cameraContainer.visibility = View.GONE
        scanMrzButton.text = "📷 Skanna MRZ med kamera"
    }
    
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            textRecognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text
                    extractMrzFromText(text)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
    
    private fun extractMrzFromText(text: String) {
        // Look for MRZ pattern (two lines of 44 characters with < symbols)
        val lines = text.replace(" ", "").split("\n")
        
        for (i in 0 until lines.size - 1) {
            // Line 1 contains NAME - don't replace I with 1 there!
            val line1 = lines[i].uppercase()
            // Line 2 contains numbers - only fix common OCR errors in numeric parts
            val line2raw = lines[i + 1].uppercase()
            // Only replace O->0 in positions where numbers are expected (not in country code)
            val line2 = fixMrzLine2OcrErrors(line2raw)
            
            if (line1.length >= 44 && line2.length >= 44 && 
                line1.contains("<") && line2.contains("<") &&
                line1.startsWith("P")) {
                
                val mrz1 = line1.take(44)
                val mrz2 = line2.take(44)
                
                mainScope.launch(Dispatchers.Main) {
                    log("📄 MRZ detected!")
                    mrzLine1Input.setText(mrz1)
                    mrzLine2Input.setText(mrz2)
                    stopCamera()
                    parseMrzManual()
                }
                return
            }
        }
    }
    
    private fun parseMrzManual() {
        val line1 = mrzLine1Input.text.toString().uppercase().trim()
        val line2 = mrzLine2Input.text.toString().uppercase().trim()
        
        if (line1.length < 44 || line2.length < 44) {
            log("❌ MRZ must be 44 characters per line")
            mrzStatusText.text = "❌ MRZ måste vara 44 tecken per rad"
            return
        }
        
        mrzData = MrzParser.parseTD3(line1, line2)
        
        if (mrzData != null) {
            bacKeys = MrzParser.deriveBacKeys(mrzData!!)
            
            val mrzInfo = mrzData!!.getMrzInformation()
            log("✅ MRZ parsed successfully!")
            log("   Name: ${mrzData?.givenNames} ${mrzData?.surname}")
            log("   Doc: '${mrzData?.documentNumber}'")
            log("   DocCheck: '${mrzData?.documentNumberCheckDigit}'")
            log("   DOB: '${mrzData?.dateOfBirth}'")
            log("   DOBCheck: '${mrzData?.dateOfBirthCheckDigit}'")
            log("   Exp: '${mrzData?.dateOfExpiry}'")
            log("   ExpCheck: '${mrzData?.dateOfExpiryCheckDigit}'")
            log("   MRZ Info for BAC: '$mrzInfo'")
            
            mrzStatusText.text = "✅ MRZ: ${mrzData?.givenNames} ${mrzData?.surname}"
            
            // Send MRZ to server (for syncing with emulator)
            sendToServer(JSONObject().apply {
                put("type", "mrz_data")
                put("data", JSONObject().apply {
                    put("line1", line1)
                    put("line2", line2)
                    put("documentNumber", mrzData?.documentNumber)
                    put("dateOfBirth", mrzData?.dateOfBirth)
                    put("dateOfExpiry", mrzData?.dateOfExpiry)
                })
            })
        } else {
            log("❌ Failed to parse MRZ")
            mrzStatusText.text = "❌ Kunde inte tolka MRZ"
        }
    }
    
    // ==================== WebSocket ====================
    
    private fun connectToServer(url: String) {
        log("Connecting to $url...")
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainScope.launch {
                    log("🔗 Connected to server")
                    statusText.text = "🔗 Ansluten till server"
                    connectButton.isEnabled = false
                    createSessionButton.isEnabled = true
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
                    log("❌ Connection failed: ${t.message}")
                    statusText.text = "❌ Anslutning misslyckades"
                    connectButton.isEnabled = true
                    createSessionButton.isEnabled = false
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
                
                "session_created" -> {
                    sessionId = json.optString("sessionId")
                    sessionCodeText.text = sessionId
                    log("📍 Session created: $sessionId")
                }
                
                "session_ready" -> {
                    log("✅ Session ready - both devices connected!")
                    statusText.text = "✅ Session redo!"
                    passportStatusText.text = "📱 Lägg passet på telefonen"
                }
                
                "apdu_command" -> {
                    val apdu = json.optString("apdu")
                    val commandId = json.optString("commandId")
                    val timestamp = json.optLong("relayTimestamp", 0)
                    handleApduCommand(apdu, commandId, timestamp)
                }
                
                "peer_disconnected" -> {
                    log("⚠️ Emulator disconnected")
                    statusText.text = "⚠️ Emulator frånkopplad"
                }
                
                "error" -> {
                    log("❌ Error: ${json.optString("message")}")
                }
            }
        } catch (e: Exception) {
            log("Error parsing message: ${e.message}")
        }
    }
    
    private fun handleApduCommand(apduHex: String, commandId: String, timestamp: Long) {
        log("← APDU: ${apduHex.take(40)}...")
        
        // Check if NFC is connected
        if (!isNfcConnected || isoDep == null) {
            log("❌ NFC not connected!")
            mainScope.launch(Dispatchers.Main) {
                passportStatusText.text = "❌ Pass ej anslutet - lägg passet på telefonen"
            }
            sendToServer(JSONObject().apply {
                put("type", "apdu_response")
                put("apdu", "")
                put("commandId", commandId)
                put("error", "NFC not connected")
            })
            return
        }
        
        mainScope.launch(Dispatchers.IO) {
            try {
                // Verify connection is still active
                if (isoDep?.isConnected != true) {
                    log("⚠️ NFC connection lost, attempting reconnect...")
                    try {
                        isoDep?.connect()
                        isoDep?.timeout = 30000
                    } catch (e: Exception) {
                        mainScope.launch(Dispatchers.Main) {
                            log("❌ Reconnect failed: ${e.message}")
                            passportStatusText.text = "❌ Tappade kontakten - lägg tillbaka passet"
                            isNfcConnected = false
                        }
                        sendToServer(JSONObject().apply {
                            put("type", "apdu_response")
                            put("apdu", "")
                            put("commandId", commandId)
                            put("error", "Connection lost, reconnect failed")
                        })
                        return@launch
                    }
                }
                
                val apduBytes = hexStringToByteArray(apduHex)
                val response = isoDep?.transceive(apduBytes)
                
                response?.let {
                    val responseHex = byteArrayToHexString(it)
                    
                    mainScope.launch(Dispatchers.Main) {
                        val sw = if (it.size >= 2) {
                            String.format("%02X%02X", it[it.size-2].toInt() and 0xFF, it[it.size-1].toInt() and 0xFF)
                        } else "??"
                        log("→ Response (${it.size} bytes, SW=$sw): ${responseHex.take(40)}...")
                    }
                    
                    sendToServer(JSONObject().apply {
                        put("type", "apdu_response")
                        put("apdu", responseHex)
                        put("commandId", commandId)
                        put("relayTimestamp", timestamp)
                    })
                } ?: run {
                    mainScope.launch(Dispatchers.Main) {
                        log("⚠️ No response from passport (null)")
                        passportStatusText.text = "⚠️ Inget svar - håll passet stilla"
                    }
                    sendToServer(JSONObject().apply {
                        put("type", "apdu_response")
                        put("apdu", "")
                        put("commandId", commandId)
                        put("error", "No response (null)")
                    })
                }
            } catch (e: android.nfc.TagLostException) {
                mainScope.launch(Dispatchers.Main) {
                    log("❌ Tag lost! Håll passet stilla på telefonen!")
                    passportStatusText.text = "❌ Tappade kontakten - lägg tillbaka passet!"
                    isNfcConnected = false
                }
                sendToServer(JSONObject().apply {
                    put("type", "apdu_response")
                    put("apdu", "")
                    put("commandId", commandId)
                    put("error", "Tag lost - hold passport steady")
                })
            } catch (e: java.io.IOException) {
                mainScope.launch(Dispatchers.Main) {
                    log("❌ IO Error: ${e.message}")
                    passportStatusText.text = "❌ Kommunikationsfel - försök igen"
                    isNfcConnected = false
                }
                sendToServer(JSONObject().apply {
                    put("type", "apdu_response")
                    put("apdu", "")
                    put("commandId", commandId)
                    put("error", "IO Error: ${e.message}")
                })
            } catch (e: Exception) {
                mainScope.launch(Dispatchers.Main) {
                    log("❌ APDU error: ${e.javaClass.simpleName}: ${e.message}")
                    passportStatusText.text = "❌ Fel: ${e.message}"
                }
                sendToServer(JSONObject().apply {
                    put("type", "apdu_response")
                    put("apdu", "")
                    put("commandId", commandId)
                    put("error", "${e.javaClass.simpleName}: ${e.message}")
                })
            }
        }
    }
    
    private fun createSession() {
        sendToServer(JSONObject().apply {
            put("type", "create_session")
            put("role", "reader")
        })
    }
    
    private fun sendToServer(json: JSONObject) {
        webSocket?.send(json.toString())
    }
    
    private fun log(message: String) {
        Log.d(TAG, message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        
        // Always run on UI thread
        runOnUiThread {
            logText.append("[$timestamp] $message\n")
            logScrollView.post {
                logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
    
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
    
    private fun byteArrayToHexString(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }
    
    /**
     * Fix OCR errors in MRZ line 2, but only in numeric positions
     * Line 2 format: DocNo(9) + Check(1) + Country(3) + DOB(6) + Check(1) + Sex(1) + DOE(6) + Check(1) + Optional(14) + Check(1)
     */
    private fun fixMrzLine2OcrErrors(line: String): String {
        if (line.length < 44) return line
        
        val chars = line.toCharArray()
        
        // Positions that should be numeric (0-indexed):
        // 9: check digit, 13-18: DOB, 19: check, 21-26: DOE, 27: check, 42-43: checks
        val numericPositions = listOf(9, 13, 14, 15, 16, 17, 18, 19, 21, 22, 23, 24, 25, 26, 27, 42, 43)
        
        for (pos in numericPositions) {
            if (pos < chars.size) {
                when (chars[pos]) {
                    'O' -> chars[pos] = '0'
                    'I' -> chars[pos] = '1'
                    'S' -> chars[pos] = '5'
                    'B' -> chars[pos] = '8'
                }
            }
        }
        
        return String(chars)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.isNotEmpty() 
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Activity destroyed")
        try {
            isoDep?.close()
        } catch (e: Exception) {
            // Ignore
        }
        isNfcConnected = false
        cameraExecutor.shutdown()
        mainScope.cancel()
    }
}
