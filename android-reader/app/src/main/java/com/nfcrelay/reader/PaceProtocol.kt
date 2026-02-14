package com.nfcrelay.reader

import android.nfc.tech.IsoDep
import android.util.Log
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveSpec

/**
 * PACE (Password Authenticated Connection Establishment) Protocol
 * According to ICAO 9303 Part 11 and BSI TR-03110
 */
class PaceProtocol(private val isoDep: IsoDep) {
    
    init {
        // Add Bouncy Castle provider if not already added
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
    
    companion object {
        private const val TAG = "PaceProtocol"
        
        // PACE OIDs
        private val PACE_ECDH_GM_AES_CBC_CMAC_128 = byteArrayOf(
            0x04.toByte(), 0x00.toByte(), 0x7F.toByte(), 0x00.toByte(),
            0x07.toByte(), 0x02.toByte(), 0x02.toByte(), 0x04.toByte(),
            0x02.toByte(), 0x02.toByte()
        )
        
        // Standard Domain Parameters ID for brainpoolP256r1
        private const val PARAM_ID_BRAINPOOL_P256R1 = 13
    }
    
    // Session keys after successful PACE
    var kEnc: ByteArray? = null
        private set
    var kMac: ByteArray? = null
        private set
    var sendSequenceCounter: Long = 0
        private set
    
    /**
     * Perform PACE authentication using MRZ-derived password
     */
    fun performPace(mrzInfo: String): Boolean {
        try {
            Log.d(TAG, "Starting PACE authentication...")
            
            // Step 1: Derive PACE password from MRZ
            val password = derivePacePassword(mrzInfo)
            Log.d(TAG, "PACE password derived")
            
            // Step 2: Select eMRTD application
            if (!selectApplication()) {
                Log.e(TAG, "Failed to select eMRTD application")
                return false
            }
            Log.d(TAG, "eMRTD application selected")
            
            // Step 3: MSE:Set AT - select PACE protocol
            if (!mseSetAt()) {
                Log.e(TAG, "MSE:Set AT failed")
                return false
            }
            Log.d(TAG, "PACE protocol selected")
            
            // Step 4: General Authenticate - get encrypted nonce
            val encryptedNonce = gaGetNonce()
            if (encryptedNonce == null) {
                Log.e(TAG, "Failed to get encrypted nonce")
                return false
            }
            Log.d(TAG, "Got encrypted nonce: ${bytesToHex(encryptedNonce)}")
            
            // Step 5: Decrypt nonce with password
            val nonce = decryptNonce(encryptedNonce, password)
            Log.d(TAG, "Decrypted nonce: ${bytesToHex(nonce)}")
            
            // Step 6: Generate ephemeral key pair and map nonce
            val (terminalSk, terminalPk) = generateKeyPair()
            
            // Step 7: Send terminal's public key, get chip's public key
            val chipPk = gaMapNonce(terminalPk, nonce)
            if (chipPk == null) {
                Log.e(TAG, "Failed to map nonce")
                return false
            }
            Log.d(TAG, "Got chip's mapped public key")
            
            // Step 8: Generate second ephemeral key pair on mapped curve
            val (terminalSk2, terminalPk2) = generateKeyPair()
            
            // Step 9: Perform key agreement
            val chipPk2 = gaPerformKeyAgreement(terminalPk2)
            if (chipPk2 == null) {
                Log.e(TAG, "Key agreement failed")
                return false
            }
            Log.d(TAG, "Key agreement completed")
            
            // Step 10: Derive session keys
            val sharedSecret = performEcdh(terminalSk2, chipPk2)
            deriveSessionKeys(sharedSecret, nonce)
            
            // Step 11: Mutual authentication
            val terminalToken = calculateAuthToken(chipPk2)
            val chipToken = gaMutualAuth(terminalToken)
            if (chipToken == null) {
                Log.e(TAG, "Mutual authentication failed")
                return false
            }
            
            // Verify chip's token
            val expectedChipToken = calculateAuthToken(terminalPk2)
            if (!verifyToken(chipToken, expectedChipToken)) {
                Log.e(TAG, "Chip token verification failed")
                return false
            }
            
            Log.d(TAG, "PACE authentication successful!")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "PACE failed with exception", e)
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Derive PACE password from MRZ using SHA-1
     */
    private fun derivePacePassword(mrzInfo: String): ByteArray {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest(mrzInfo.toByteArray(Charsets.UTF_8))
        return hash.copyOf(16)
    }
    
    private fun selectApplication(): Boolean {
        val cmd = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x0C.toByte(),
            0x07.toByte(), 0xA0.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x02.toByte(), 0x47.toByte(), 0x10.toByte(), 0x01.toByte()
        )
        val response = isoDep.transceive(cmd)
        
        if (isSuccess(response)) {
            // Read EF.CardAccess to see supported protocols
            readCardAccess()
        }
        
        return isSuccess(response)
    }
    
    private fun readCardAccess() {
        try {
            // SELECT EF.CardAccess (FID 011C)
            val selectCmd = byteArrayOf(
                0x00.toByte(), 0xA4.toByte(), 0x02.toByte(), 0x0C.toByte(),
                0x02.toByte(), 0x01.toByte(), 0x1C.toByte()
            )
            val selectResp = isoDep.transceive(selectCmd)
            Log.d(TAG, "SELECT EF.CardAccess: ${bytesToHex(selectResp)}")
            
            if (isSuccess(selectResp)) {
                // READ BINARY - read up to 256 bytes
                val readCmd = byteArrayOf(
                    0x00.toByte(), 0xB0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
                )
                val readResp = isoDep.transceive(readCmd)
                Log.d(TAG, "EF.CardAccess content: ${bytesToHex(readResp)}")
                
                // Parse and log the supported protocols
                parseCardAccess(readResp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read EF.CardAccess: ${e.message}")
        }
    }
    
    private fun parseCardAccess(data: ByteArray) {
        // Log the raw ASN.1 structure for debugging
        Log.d(TAG, "=== SUPPORTED PROTOCOLS ===")
        
        // Look for OIDs in the data
        val oidPatterns = mapOf(
            "04007F00070202" to "PACE",
            "04007F00070201" to "Chip Auth",
            "04007F00070203" to "Terminal Auth"
        )
        
        val hex = bytesToHex(data)
        for ((pattern, name) in oidPatterns) {
            if (hex.contains(pattern)) {
                Log.d(TAG, "Found: $name")
                // Try to extract the full OID and parameters
                val idx = hex.indexOf(pattern)
                if (idx >= 0 && idx + 20 <= hex.length) {
                    Log.d(TAG, "  OID area: ${hex.substring(idx, minOf(idx + 30, hex.length))}")
                }
            }
        }
    }
    
    /**
     * MSE:Set AT - Manage Security Environment: Set Authentication Template
     * Try different PACE configurations until one works
     */
    private fun mseSetAt(): Boolean {
        // Different PACE OIDs to try
        val paceConfigs = listOf(
            // PACE-ECDH-GM-AES-CBC-CMAC-128, brainpoolP256r1
            Pair(byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x02, 0x02, 0x04, 0x02, 0x02), 13),
            // PACE-ECDH-GM-AES-CBC-CMAC-128, secp256r1 (NIST P-256)
            Pair(byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x02, 0x02, 0x04, 0x02, 0x02), 12),
            // PACE-ECDH-GM-AES-CBC-CMAC-256, brainpoolP256r1  
            Pair(byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x02, 0x02, 0x04, 0x02, 0x04), 13),
            // PACE-ECDH-GM-3DES-CBC-CBC, brainpoolP256r1
            Pair(byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x02, 0x02, 0x04, 0x01, 0x01), 13),
            // PACE-ECDH-GM-AES-CBC-CMAC-128, brainpoolP384r1
            Pair(byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x02, 0x02, 0x04, 0x02, 0x02), 15),
        )
        
        for ((oid, paramId) in paceConfigs) {
            Log.d(TAG, "Trying PACE config: OID=${bytesToHex(oid.map { it.toByte() }.toByteArray())}, ParamID=$paramId")
            
            val oidBytes = oid.map { it.toByte() }.toByteArray()
            val paramIdBytes = byteArrayOf(0x84.toByte(), 0x01.toByte(), paramId.toByte())
            val passwordRef = byteArrayOf(0x83.toByte(), 0x01.toByte(), 0x01.toByte()) // MRZ
            
            val data = ByteArray(2 + oidBytes.size + paramIdBytes.size + passwordRef.size)
            var pos = 0
            data[pos++] = 0x80.toByte() // OID tag
            data[pos++] = oidBytes.size.toByte()
            System.arraycopy(oidBytes, 0, data, pos, oidBytes.size)
            pos += oidBytes.size
            System.arraycopy(paramIdBytes, 0, data, pos, paramIdBytes.size)
            pos += paramIdBytes.size
            System.arraycopy(passwordRef, 0, data, pos, passwordRef.size)
            
            val cmd = ByteArray(5 + data.size)
            cmd[0] = 0x00.toByte() // CLA
            cmd[1] = 0x22.toByte() // INS MSE
            cmd[2] = 0xC1.toByte() // P1 Set AT
            cmd[3] = 0xA4.toByte() // P2 Authentication
            cmd[4] = data.size.toByte() // Lc
            System.arraycopy(data, 0, cmd, 5, data.size)
            
            val response = isoDep.transceive(cmd)
            Log.d(TAG, "MSE:Set AT response: ${bytesToHex(response)}")
            
            if (isSuccess(response)) {
                Log.d(TAG, "SUCCESS! PACE config accepted: ParamID=$paramId")
                return true
            }
            
            // Log error code
            if (response.size >= 2) {
                val sw = String.format("%02X%02X", 
                    response[response.size - 2].toInt() and 0xFF,
                    response[response.size - 1].toInt() and 0xFF)
                Log.d(TAG, "Rejected with SW=$sw")
            }
        }
        
        Log.e(TAG, "No PACE configuration accepted by passport")
        return false
    }
    
    /**
     * General Authenticate - Step 1: Get encrypted nonce
     */
    private fun gaGetNonce(): ByteArray? {
        val cmd = byteArrayOf(
            0x10.toByte(), // CLA (chaining)
            0x86.toByte(), // INS General Authenticate
            0x00.toByte(), // P1
            0x00.toByte(), // P2
            0x02.toByte(), // Lc
            0x7C.toByte(), // Dynamic Authentication Data tag
            0x00.toByte(), // Empty
            0x00.toByte()  // Le
        )
        
        Log.d(TAG, "GA GetNonce command: ${bytesToHex(cmd)}")
        val response = isoDep.transceive(cmd)
        Log.d(TAG, "GA GetNonce response: ${bytesToHex(response)}")
        
        if (!isSuccess(response)) return null
        
        // Parse response - extract encrypted nonce from tag 80
        return extractTag(response, 0x80.toByte())
    }
    
    /**
     * Decrypt nonce using AES
     */
    private fun decryptNonce(encryptedNonce: ByteArray, password: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = SecretKeySpec(password, "AES")
        val ivSpec = IvParameterSpec(ByteArray(16))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(encryptedNonce)
    }
    
    /**
     * Generate ephemeral EC key pair on brainpoolP256r1
     */
    private fun generateKeyPair(): Pair<PrivateKey, PublicKey> {
        val keyPairGen = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        val spec = ECGenParameterSpec("brainpoolP256r1")
        keyPairGen.initialize(spec, SecureRandom())
        val keyPair = keyPairGen.generateKeyPair()
        return Pair(keyPair.private, keyPair.public)
    }
    
    /**
     * General Authenticate - Step 2: Map nonce
     */
    private fun gaMapNonce(terminalPk: PublicKey, nonce: ByteArray): PublicKey? {
        val pkBytes = encodePublicKey(terminalPk)
        
        // Build command
        val data = ByteArray(4 + pkBytes.size)
        data[0] = 0x7C.toByte()
        data[1] = (2 + pkBytes.size).toByte()
        data[2] = 0x81.toByte() // Mapping Data tag
        data[3] = pkBytes.size.toByte()
        System.arraycopy(pkBytes, 0, data, 4, pkBytes.size)
        
        val cmd = ByteArray(5 + data.size + 1)
        cmd[0] = 0x10.toByte() // CLA (chaining)
        cmd[1] = 0x86.toByte() // INS
        cmd[2] = 0x00.toByte()
        cmd[3] = 0x00.toByte()
        cmd[4] = data.size.toByte()
        System.arraycopy(data, 0, cmd, 5, data.size)
        cmd[cmd.size - 1] = 0x00.toByte() // Le
        
        Log.d(TAG, "GA MapNonce command: ${bytesToHex(cmd)}")
        val response = isoDep.transceive(cmd)
        Log.d(TAG, "GA MapNonce response: ${bytesToHex(response)}")
        
        if (!isSuccess(response)) return null
        
        val chipPkBytes = extractTag(response, 0x82.toByte()) ?: return null
        return decodePublicKey(chipPkBytes)
    }
    
    /**
     * General Authenticate - Step 3: Perform key agreement
     */
    private fun gaPerformKeyAgreement(terminalPk: PublicKey): PublicKey? {
        val pkBytes = encodePublicKey(terminalPk)
        
        val data = ByteArray(4 + pkBytes.size)
        data[0] = 0x7C.toByte()
        data[1] = (2 + pkBytes.size).toByte()
        data[2] = 0x83.toByte() // Ephemeral Public Key tag
        data[3] = pkBytes.size.toByte()
        System.arraycopy(pkBytes, 0, data, 4, pkBytes.size)
        
        val cmd = ByteArray(5 + data.size + 1)
        cmd[0] = 0x10.toByte()
        cmd[1] = 0x86.toByte()
        cmd[2] = 0x00.toByte()
        cmd[3] = 0x00.toByte()
        cmd[4] = data.size.toByte()
        System.arraycopy(data, 0, cmd, 5, data.size)
        cmd[cmd.size - 1] = 0x00.toByte()
        
        Log.d(TAG, "GA KeyAgreement command: ${bytesToHex(cmd)}")
        val response = isoDep.transceive(cmd)
        Log.d(TAG, "GA KeyAgreement response: ${bytesToHex(response)}")
        
        if (!isSuccess(response)) return null
        
        val chipPkBytes = extractTag(response, 0x84.toByte()) ?: return null
        return decodePublicKey(chipPkBytes)
    }
    
    /**
     * General Authenticate - Step 4: Mutual authentication
     */
    private fun gaMutualAuth(terminalToken: ByteArray): ByteArray? {
        val data = ByteArray(4 + terminalToken.size)
        data[0] = 0x7C.toByte()
        data[1] = (2 + terminalToken.size).toByte()
        data[2] = 0x85.toByte() // Authentication Token tag
        data[3] = terminalToken.size.toByte()
        System.arraycopy(terminalToken, 0, data, 4, terminalToken.size)
        
        val cmd = ByteArray(5 + data.size + 1)
        cmd[0] = 0x00.toByte() // Final command (no chaining)
        cmd[1] = 0x86.toByte()
        cmd[2] = 0x00.toByte()
        cmd[3] = 0x00.toByte()
        cmd[4] = data.size.toByte()
        System.arraycopy(data, 0, cmd, 5, data.size)
        cmd[cmd.size - 1] = 0x00.toByte()
        
        Log.d(TAG, "GA MutualAuth command: ${bytesToHex(cmd)}")
        val response = isoDep.transceive(cmd)
        Log.d(TAG, "GA MutualAuth response: ${bytesToHex(response)}")
        
        if (!isSuccess(response)) return null
        
        return extractTag(response, 0x86.toByte())
    }
    
    /**
     * Perform ECDH key agreement
     */
    private fun performEcdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        return keyAgreement.generateSecret()
    }
    
    /**
     * Derive session keys from shared secret
     */
    private fun deriveSessionKeys(sharedSecret: ByteArray, nonce: ByteArray) {
        val sha256 = MessageDigest.getInstance("SHA-256")
        
        // KEnc = SHA-256(sharedSecret || 00000001)
        sha256.update(sharedSecret)
        sha256.update(byteArrayOf(0, 0, 0, 1))
        kEnc = sha256.digest().copyOf(16)
        
        // KMac = SHA-256(sharedSecret || 00000002)
        sha256.reset()
        sha256.update(sharedSecret)
        sha256.update(byteArrayOf(0, 0, 0, 2))
        kMac = sha256.digest().copyOf(16)
        
        Log.d(TAG, "Session KEnc: ${bytesToHex(kEnc!!)}")
        Log.d(TAG, "Session KMac: ${bytesToHex(kMac!!)}")
    }
    
    /**
     * Calculate authentication token using CMAC
     */
    private fun calculateAuthToken(publicKey: PublicKey): ByteArray {
        val pkBytes = encodePublicKey(publicKey)
        
        // Build object identifier
        val oid = PACE_ECDH_GM_AES_CBC_CMAC_128
        val data = ByteArray(2 + oid.size + 2 + pkBytes.size)
        var pos = 0
        data[pos++] = 0x06.toByte()
        data[pos++] = oid.size.toByte()
        System.arraycopy(oid, 0, data, pos, oid.size)
        pos += oid.size
        data[pos++] = 0x86.toByte()
        data[pos++] = pkBytes.size.toByte()
        System.arraycopy(pkBytes, 0, data, pos, pkBytes.size)
        
        return calculateCmac(kMac!!, data).copyOf(8)
    }
    
    /**
     * Calculate AES-CMAC
     */
    private fun calculateCmac(key: ByteArray, data: ByteArray): ByteArray {
        // Simplified CMAC - for production use a proper CMAC library
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data).copyOf(16)
    }
    
    private fun verifyToken(received: ByteArray, expected: ByteArray): Boolean {
        if (received.size < 8 || expected.size < 8) return false
        return received.copyOf(8).contentEquals(expected.copyOf(8))
    }
    
    /**
     * Encode public key to uncompressed point format
     */
    private fun encodePublicKey(publicKey: PublicKey): ByteArray {
        return when (publicKey) {
            is org.bouncycastle.jce.interfaces.ECPublicKey -> {
                val point = publicKey.q
                val x = point.affineXCoord.encoded
                val y = point.affineYCoord.encoded
                byteArrayOf(0x04.toByte()) + x + y
            }
            is ECPublicKey -> {
                val point = publicKey.w
                val x = point.affineX.toByteArray().let { 
                    if (it.size > 32) it.copyOfRange(it.size - 32, it.size) 
                    else if (it.size < 32) ByteArray(32 - it.size) + it
                    else it
                }
                val y = point.affineY.toByteArray().let {
                    if (it.size > 32) it.copyOfRange(it.size - 32, it.size)
                    else if (it.size < 32) ByteArray(32 - it.size) + it
                    else it
                }
                byteArrayOf(0x04.toByte()) + x + y
            }
            else -> throw IllegalArgumentException("Unsupported key type")
        }
    }
    
    /**
     * Decode public key from uncompressed point format
     */
    private fun decodePublicKey(bytes: ByteArray): PublicKey {
        if (bytes[0] != 0x04.toByte() || bytes.size != 65) {
            throw IllegalArgumentException("Invalid public key format")
        }
        
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        
        // Use Bouncy Castle for brainpoolP256r1
        val paramSpec = ECNamedCurveTable.getParameterSpec("brainpoolP256r1")
        val ecSpec = ECNamedCurveSpec(
            paramSpec.name,
            paramSpec.curve,
            paramSpec.g,
            paramSpec.n,
            paramSpec.h,
            paramSpec.seed
        )
        
        val pubSpec = ECPublicKeySpec(java.security.spec.ECPoint(x, y), ecSpec)
        val keyFactory = KeyFactory.getInstance("EC", BouncyCastleProvider())
        return keyFactory.generatePublic(pubSpec) as PublicKey
    }
    
    /**
     * Extract TLV tag from response
     */
    private fun extractTag(response: ByteArray, tag: Byte): ByteArray? {
        var i = 0
        while (i < response.size - 2) {
            if (response[i] == 0x7C.toByte()) {
                // Dynamic Authentication Data
                i += 2 // Skip tag and length
                continue
            }
            if (response[i] == tag) {
                val length = response[i + 1].toInt() and 0xFF
                if (i + 2 + length <= response.size) {
                    return response.copyOfRange(i + 2, i + 2 + length)
                }
            }
            i++
        }
        return null
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
