package com.nfcrelay.reader

import android.nfc.tech.IsoDep
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BAC (Basic Access Control) Protocol Implementation
 * According to ICAO 9303 Part 11
 */
class BacProtocol(private val isoDep: IsoDep) {
    
    companion object {
        private const val TAG = "BacProtocol"
        
        // APDU Commands
        private val SELECT_MRTD_APP = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x0C.toByte(),
            0x07.toByte(), 0xA0.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x02.toByte(), 0x47.toByte(), 0x10.toByte(), 0x01.toByte()
        )
        
        private val GET_CHALLENGE = byteArrayOf(
            0x00.toByte(), 0x84.toByte(), 0x00.toByte(), 0x00.toByte(), 0x08.toByte()
        )
    }
    
    private val random = SecureRandom()
    
    // Session keys after successful BAC
    var sessionKEnc: ByteArray? = null
        private set
    var sessionKMac: ByteArray? = null
        private set
    var sendSequenceCounter: Long = 0
        private set
    
    /**
     * Perform BAC authentication
     * Returns true if successful
     */
    fun performBac(bacKeys: MrzParser.BacKeys): Boolean {
        try {
            Log.d(TAG, "Starting BAC authentication...")
            
            // Step 1: Select eMRTD application
            val selectResponse = isoDep.transceive(SELECT_MRTD_APP)
            if (!isSuccess(selectResponse)) {
                Log.e(TAG, "Failed to select eMRTD application")
                return false
            }
            Log.d(TAG, "eMRTD application selected")
            
            // Step 2: Get challenge from passport
            val challengeResponse = isoDep.transceive(GET_CHALLENGE)
            Log.d(TAG, "Challenge response: ${bytesToHex(challengeResponse)}")
            Log.d(TAG, "Challenge response size: ${challengeResponse.size}")
            
            // Check if passport uses PACE instead of BAC
            if (challengeResponse.size >= 2) {
                val sw1 = challengeResponse[challengeResponse.size - 2].toInt() and 0xFF
                val sw2 = challengeResponse[challengeResponse.size - 1].toInt() and 0xFF
                if (sw1 == 0x6A && sw2 == 0x81) {
                    Log.e(TAG, "Passport does NOT support BAC - likely uses PACE instead!")
                    Log.e(TAG, "PACE protocol not yet implemented")
                    return false
                }
            }
            
            if (!isSuccess(challengeResponse) || challengeResponse.size < 10) {
                Log.e(TAG, "Failed to get challenge - SW: ${bytesToHex(challengeResponse.takeLast(2).toByteArray())}")
                return false
            }
            val rndIc = challengeResponse.copyOf(8) // Remove status bytes
            Log.d(TAG, "Got challenge from passport: ${bytesToHex(rndIc)}")
            
            // Step 3: Generate our random numbers
            val rndIfd = ByteArray(8)
            val kIfd = ByteArray(16)
            random.nextBytes(rndIfd)
            random.nextBytes(kIfd)
            Log.d(TAG, "Generated RND.IFD: ${bytesToHex(rndIfd)}")
            
            // Step 4: Build S = RND.IFD || RND.IC || KIFD
            val s = ByteArray(32)
            System.arraycopy(rndIfd, 0, s, 0, 8)
            System.arraycopy(rndIc, 0, s, 8, 8)
            System.arraycopy(kIfd, 0, s, 16, 16)
            
            // Step 5: Encrypt S with Kenc
            val eifd = encrypt3Des(s, bacKeys.kEnc)
            Log.d(TAG, "Encrypted EIFD: ${bytesToHex(eifd)}")
            
            // Step 6: Calculate MAC over EIFD
            val mifd = MrzParser.calculateMac(bacKeys.kMac, eifd)
            Log.d(TAG, "Calculated MIFD: ${bytesToHex(mifd)}")
            
            // Step 7: Build EXTERNAL AUTHENTICATE command
            val cmdData = ByteArray(40)
            System.arraycopy(eifd, 0, cmdData, 0, 32)
            System.arraycopy(mifd, 0, cmdData, 32, 8)
            
            val extAuth = ByteArray(46)
            extAuth[0] = 0x00.toByte() // CLA
            extAuth[1] = 0x82.toByte() // INS (EXTERNAL AUTHENTICATE)
            extAuth[2] = 0x00.toByte() // P1
            extAuth[3] = 0x00.toByte() // P2
            extAuth[4] = 0x28.toByte() // Lc (40 bytes)
            System.arraycopy(cmdData, 0, extAuth, 5, 40)
            extAuth[45] = 0x28.toByte() // Le (40 bytes expected)
            
            // Step 8: Send EXTERNAL AUTHENTICATE
            val authResponse = isoDep.transceive(extAuth)
            if (!isSuccess(authResponse) || authResponse.size < 42) {
                Log.e(TAG, "EXTERNAL AUTHENTICATE failed: ${bytesToHex(authResponse)}")
                return false
            }
            Log.d(TAG, "Got EXTERNAL AUTHENTICATE response")
            
            // Step 9: Extract and verify passport's response
            val eic = authResponse.copyOf(32)
            val mic = authResponse.copyOfRange(32, 40)
            
            // Verify MAC
            val expectedMic = MrzParser.calculateMac(bacKeys.kMac, eic)
            if (!mic.contentEquals(expectedMic)) {
                Log.e(TAG, "MAC verification failed")
                return false
            }
            Log.d(TAG, "MAC verified")
            
            // Step 10: Decrypt passport's response
            val decrypted = decrypt3Des(eic, bacKeys.kEnc)
            val rndIcResponse = decrypted.copyOf(8)
            val rndIfdResponse = decrypted.copyOfRange(8, 16)
            val kIc = decrypted.copyOfRange(16, 32)
            
            // Verify RND values
            if (!rndIfd.contentEquals(rndIfdResponse)) {
                Log.e(TAG, "RND.IFD verification failed")
                return false
            }
            Log.d(TAG, "RND verification passed")
            
            // Step 11: Derive session keys
            // Kseed = KIFD XOR KIC
            val kSeed = ByteArray(16)
            for (i in 0..15) {
                kSeed[i] = (kIfd[i].toInt() xor kIc[i].toInt()).toByte()
            }
            
            // Derive session keys using same KDF as BAC keys
            sessionKEnc = deriveSessionKey(kSeed, 1)
            sessionKMac = deriveSessionKey(kSeed, 2)
            
            // Initialize SSC (Send Sequence Counter)
            // SSC = RND.IC[4-7] || RND.IFD[4-7]
            sendSequenceCounter = 0
            for (i in 4..7) {
                sendSequenceCounter = (sendSequenceCounter shl 8) or (rndIc[i].toLong() and 0xFF)
            }
            for (i in 4..7) {
                sendSequenceCounter = (sendSequenceCounter shl 8) or (rndIfd[i].toLong() and 0xFF)
            }
            
            Log.d(TAG, "BAC authentication successful!")
            Log.d(TAG, "Session KEnc: ${bytesToHex(sessionKEnc!!)}")
            Log.d(TAG, "SSC: $sendSequenceCounter")
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "BAC failed with exception", e)
            return false
        }
    }
    
    /**
     * Send a secure messaging APDU
     */
    fun sendSecure(apdu: ByteArray): ByteArray? {
        if (sessionKEnc == null || sessionKMac == null) {
            Log.e(TAG, "Session keys not established")
            return null
        }
        
        // Increment SSC
        sendSequenceCounter++
        
        // Build secure messaging APDU
        // This is a simplified version - full SM requires proper DO87/DO97/DO8E construction
        return try {
            isoDep.transceive(apdu)
        } catch (e: Exception) {
            Log.e(TAG, "Secure send failed", e)
            null
        }
    }
    
    private fun deriveSessionKey(kSeed: ByteArray, c: Int): ByteArray {
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
        val d = ByteArray(kSeed.size + 4)
        System.arraycopy(kSeed, 0, d, 0, kSeed.size)
        d[kSeed.size + 3] = c.toByte()
        val hash = sha1.digest(d)
        val key = hash.copyOf(16)
        adjustParityBits(key)
        return key
    }
    
    private fun adjustParityBits(key: ByteArray) {
        for (i in key.indices) {
            var b = key[i].toInt() and 0xFF
            var parity = 0
            for (j in 0..6) {
                if ((b and (1 shl j)) != 0) parity++
            }
            if (parity % 2 == 0) {
                b = b or 1
            } else {
                b = b and 0xFE
            }
            key[i] = b.toByte()
        }
    }
    
    private fun encrypt3Des(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DESede/CBC/NoPadding")
        val keySpec = SecretKeySpec(to3DesKey(key), "DESede")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ByteArray(8)))
        return cipher.doFinal(data)
    }
    
    private fun decrypt3Des(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DESede/CBC/NoPadding")
        val keySpec = SecretKeySpec(to3DesKey(key), "DESede")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ByteArray(8)))
        return cipher.doFinal(data)
    }
    
    /**
     * Convert 16-byte key to 24-byte 3DES key (K1, K2, K1)
     */
    private fun to3DesKey(key: ByteArray): ByteArray {
        val key3des = ByteArray(24)
        System.arraycopy(key, 0, key3des, 0, 8)
        System.arraycopy(key, 8, key3des, 8, 8)
        System.arraycopy(key, 0, key3des, 16, 8)
        return key3des
    }
    
    private fun isSuccess(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        return sw1 == 0x90 && sw2 == 0x00
    }
    
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }
}
