package com.nfcrelay.emulator

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HCE Service that emulates a passport NFC interface.
 * 
 * When an NFC reader (like a passport scanner) touches this phone,
 * APDU commands are received here, relayed to the server (and onwards to
 * the real passport), and the response is returned.
 */
class PassportHceService : HostApduService() {
    
    companion object {
        private const val TAG = "PassportHCE"
        const val ACTION_APDU_RESPONSE = "com.nfcrelay.emulator.APDU_RESPONSE"
        const val EXTRA_RESPONSE = "response"
        const val EXTRA_COMMAND_ID = "commandId"
        
        // Timeout for waiting for response from server/real passport
        // Need longer timeout for international relay
        private const val RESPONSE_TIMEOUT_MS = 10000L
        
        // Standard status words
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_UNKNOWN = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        
        // Singleton reference for receiving responses
        @Volatile
        var instance: PassportHceService? = null
    }
    
    private var commandCounter = 0
    private var pendingResponse: ByteArray? = null
    private var responseLatch: CountDownLatch? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "HCE Service created")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "HCE Service destroyed")
    }
    
    /**
     * Called when an APDU command is received from an NFC reader.
     * We relay this to the WebSocket connection, wait for response,
     * and return it to the reader.
     */
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val commandHex = byteArrayToHexString(commandApdu)
        val commandId = "cmd_${++commandCounter}"
        
        Log.d(TAG, "Received APDU: $commandHex")
        
        // Reset response state
        pendingResponse = null
        responseLatch = CountDownLatch(1)
        
        // Send command to MainActivity which will relay via WebSocket
        val intent = Intent(MainActivity.ACTION_APDU_COMMAND).apply {
            putExtra(MainActivity.EXTRA_APDU, commandHex)
            putExtra(MainActivity.EXTRA_COMMAND_ID, commandId)
        }
        sendBroadcast(intent)
        
        // Wait for response with timeout
        try {
            val received = responseLatch?.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: false
            
            if (received && pendingResponse != null) {
                Log.d(TAG, "Returning response: ${byteArrayToHexString(pendingResponse!!)}")
                return pendingResponse!!
            } else {
                Log.w(TAG, "Response timeout or null response")
                return SW_UNKNOWN
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Wait interrupted", e)
            return SW_UNKNOWN
        }
    }
    
    /**
     * Called when the NFC link is deactivated.
     */
    override fun onDeactivated(reason: Int) {
        val reasonStr = when (reason) {
            DEACTIVATION_LINK_LOSS -> "Link loss"
            DEACTIVATION_DESELECTED -> "Deselected"
            else -> "Unknown ($reason)"
        }
        Log.d(TAG, "NFC Deactivated: $reasonStr")
        
        // Notify MainActivity
        val intent = Intent(MainActivity.ACTION_NFC_DEACTIVATED).apply {
            putExtra("reason", reasonStr)
        }
        sendBroadcast(intent)
    }
    
    /**
     * Called from MainActivity when response is received from WebSocket
     */
    fun onResponseReceived(responseHex: String, commandId: String) {
        Log.d(TAG, "Response received for $commandId: ${responseHex.take(20)}...")
        
        if (responseHex.isNotEmpty()) {
            pendingResponse = hexStringToByteArray(responseHex)
        } else {
            pendingResponse = SW_UNKNOWN
        }
        
        responseLatch?.countDown()
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
}
