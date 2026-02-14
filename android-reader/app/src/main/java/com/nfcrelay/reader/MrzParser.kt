package com.nfcrelay.reader

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

/**
 * MRZ Parser and BAC (Basic Access Control) Key Derivation
 * 
 * Parses the Machine Readable Zone from passports and derives
 * the cryptographic keys needed for secure communication.
 */
object MrzParser {
    
    /**
     * MRZ Data class containing parsed information
     */
    data class MrzData(
        val documentNumber: String,      // 9 characters
        val dateOfBirth: String,         // YYMMDD
        val dateOfExpiry: String,        // YYMMDD
        val documentNumberCheckDigit: Char,
        val dateOfBirthCheckDigit: Char,
        val dateOfExpiryCheckDigit: Char,
        // Optional parsed data
        val surname: String? = null,
        val givenNames: String? = null,
        val nationality: String? = null,
        val sex: String? = null,
        val issuingState: String? = null
    ) {
        /**
         * Get the MRZ Information string used for BAC key derivation
         * Format: Document Number + Check Digit + DOB + Check Digit + DOE + Check Digit
         */
        fun getMrzInformation(): String {
            return documentNumber + documentNumberCheckDigit +
                   dateOfBirth + dateOfBirthCheckDigit +
                   dateOfExpiry + dateOfExpiryCheckDigit
        }
    }
    
    /**
     * BAC Keys derived from MRZ
     */
    data class BacKeys(
        val kEnc: ByteArray,  // Encryption key (16 bytes)
        val kMac: ByteArray   // MAC key (16 bytes)
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BacKeys) return false
            return kEnc.contentEquals(other.kEnc) && kMac.contentEquals(other.kMac)
        }
        
        override fun hashCode(): Int {
            return 31 * kEnc.contentHashCode() + kMac.contentHashCode()
        }
    }
    
    /**
     * Parse TD3 format MRZ (standard passport, 2 lines of 44 characters)
     */
    fun parseTD3(line1: String, line2: String): MrzData? {
        if (line1.length != 44 || line2.length != 44) {
            return null
        }
        
        try {
            // Line 1: Type (2) + Country (3) + Name (39)
            val issuingState = line1.substring(2, 5).replace("<", "")
            val namePart = line1.substring(5)
            val nameParts = namePart.split("<<")
            val surname = nameParts.getOrNull(0)?.replace("<", " ")?.trim()
            val givenNames = nameParts.getOrNull(1)?.replace("<", " ")?.trim()
            
            // Line 2: DocNo (9) + Check (1) + Nationality (3) + DOB (6) + Check (1) + 
            //         Sex (1) + DOE (6) + Check (1) + Optional (14) + Check (1)
            // Keep raw document number with < padding for BAC key derivation
            val documentNumber = line2.substring(0, 9)
            val documentNumberCheckDigit = line2[9]
            val nationality = line2.substring(10, 13).replace("<", "")
            val dateOfBirth = line2.substring(13, 19)
            val dateOfBirthCheckDigit = line2[19]
            val sex = line2.substring(20, 21)
            val dateOfExpiry = line2.substring(21, 27)
            val dateOfExpiryCheckDigit = line2[27]
            
            return MrzData(
                documentNumber = documentNumber,
                dateOfBirth = dateOfBirth,
                dateOfExpiry = dateOfExpiry,
                documentNumberCheckDigit = documentNumberCheckDigit,
                dateOfBirthCheckDigit = dateOfBirthCheckDigit,
                dateOfExpiryCheckDigit = dateOfExpiryCheckDigit,
                surname = surname,
                givenNames = givenNames,
                nationality = nationality,
                sex = sex,
                issuingState = issuingState
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Calculate MRZ check digit according to ICAO 9303
     */
    fun calculateCheckDigit(input: String): Int {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        
        for (i in input.indices) {
            val c = input[i]
            val value = when {
                c in '0'..'9' -> c - '0'
                c in 'A'..'Z' -> c - 'A' + 10
                c == '<' -> 0
                else -> 0
            }
            sum += value * weights[i % 3]
        }
        
        return sum % 10
    }
    
    /**
     * Verify check digit
     */
    fun verifyCheckDigit(data: String, checkDigit: Char): Boolean {
        val expected = calculateCheckDigit(data)
        val actual = checkDigit - '0'
        return expected == actual
    }
    
    /**
     * Derive BAC keys from MRZ information
     * According to ICAO 9303 Part 11
     */
    fun deriveBacKeys(mrzData: MrzData): BacKeys {
        val mrzInfo = mrzData.getMrzInformation()
        return deriveBacKeys(mrzInfo)
    }
    
    /**
     * Derive BAC keys from MRZ information string
     */
    fun deriveBacKeys(mrzInformation: String): BacKeys {
        android.util.Log.d("MrzParser", "=== BAC KEY DERIVATION ===")
        android.util.Log.d("MrzParser", "MRZ Info: '$mrzInformation'")
        android.util.Log.d("MrzParser", "MRZ Info length: ${mrzInformation.length}")
        
        // Step 1: Calculate SHA-1 hash of MRZ information
        val sha1 = MessageDigest.getInstance("SHA-1")
        val mrzBytes = mrzInformation.toByteArray(Charsets.UTF_8)
        android.util.Log.d("MrzParser", "MRZ Bytes: ${mrzBytes.joinToString("") { "%02X".format(it) }}")
        val hash = sha1.digest(mrzBytes)
        android.util.Log.d("MrzParser", "SHA-1 Hash: ${hash.joinToString("") { "%02X".format(it) }}")
        
        // Step 2: Take first 16 bytes as Kseed
        val kSeed = hash.copyOf(16)
        
        // Step 3: Derive Kenc and Kmac using key diversification
        val kEnc = deriveKey(kSeed, 1) // c = 1 for encryption
        val kMac = deriveKey(kSeed, 2) // c = 2 for MAC
        
        return BacKeys(kEnc, kMac)
    }
    
    /**
     * Key derivation function according to ICAO 9303
     * KDF(K, c) = H(K || c)[0..15]
     */
    private fun deriveKey(kSeed: ByteArray, c: Int): ByteArray {
        val sha1 = MessageDigest.getInstance("SHA-1")
        
        // D = Kseed || counter (4 bytes, big-endian)
        val d = ByteArray(kSeed.size + 4)
        System.arraycopy(kSeed, 0, d, 0, kSeed.size)
        d[kSeed.size] = 0
        d[kSeed.size + 1] = 0
        d[kSeed.size + 2] = 0
        d[kSeed.size + 3] = c.toByte()
        
        val hash = sha1.digest(d)
        
        // Take first 16 bytes
        val key = hash.copyOf(16)
        
        // Adjust parity bits for DES (every 8th bit)
        adjustParityBits(key)
        
        return key
    }
    
    /**
     * Adjust DES parity bits
     */
    private fun adjustParityBits(key: ByteArray) {
        for (i in key.indices) {
            var b = key[i].toInt() and 0xFF
            // Count bits
            var parity = 0
            for (j in 0..6) {
                if ((b and (1 shl j)) != 0) parity++
            }
            // Set parity bit (bit 0)
            if (parity % 2 == 0) {
                b = b or 1
            } else {
                b = b and 0xFE
            }
            key[i] = b.toByte()
        }
    }
    
    /**
     * Pad data according to ISO 9797-1 Method 2
     */
    fun padData(data: ByteArray): ByteArray {
        val blockSize = 8
        val paddingLength = blockSize - ((data.size + 1) % blockSize)
        val padded = ByteArray(data.size + 1 + paddingLength)
        System.arraycopy(data, 0, padded, 0, data.size)
        padded[data.size] = 0x80.toByte()
        // Rest is already 0x00
        return padded
    }
    
    /**
     * Remove ISO 9797-1 Method 2 padding
     */
    fun unpadData(data: ByteArray): ByteArray {
        var i = data.size - 1
        while (i >= 0 && data[i] == 0x00.toByte()) {
            i--
        }
        if (i >= 0 && data[i] == 0x80.toByte()) {
            return data.copyOf(i)
        }
        return data
    }
    
    /**
     * Calculate MAC according to ISO 9797-1 Algorithm 3 (Retail MAC)
     */
    fun calculateMac(key: ByteArray, data: ByteArray): ByteArray {
        val paddedData = padData(data)
        
        // Split key into Ka and Kb (each 8 bytes)
        val ka = key.copyOfRange(0, 8)
        val kb = key.copyOfRange(8, 16)
        
        // CBC-MAC with Ka
        var y = ByteArray(8) // IV = 0
        val cipher = Cipher.getInstance("DES/CBC/NoPadding")
        
        val kaSpec = SecretKeySpec(ka, "DES")
        cipher.init(Cipher.ENCRYPT_MODE, kaSpec, IvParameterSpec(ByteArray(8)))
        
        for (i in paddedData.indices step 8) {
            val block = paddedData.copyOfRange(i, i + 8)
            for (j in 0..7) {
                block[j] = (block[j].toInt() xor y[j].toInt()).toByte()
            }
            y = cipher.doFinal(block)
        }
        
        // Final block: Decrypt with Kb, then encrypt with Ka
        val kbSpec = SecretKeySpec(kb, "DES")
        cipher.init(Cipher.DECRYPT_MODE, kbSpec, IvParameterSpec(ByteArray(8)))
        y = cipher.doFinal(y)
        
        cipher.init(Cipher.ENCRYPT_MODE, kaSpec, IvParameterSpec(ByteArray(8)))
        y = cipher.doFinal(y)
        
        return y
    }
}
