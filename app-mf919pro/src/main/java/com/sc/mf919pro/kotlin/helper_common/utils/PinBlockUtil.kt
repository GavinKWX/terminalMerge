package com.sc.mf919pro.kotlin.helper_common.utils

import java.util.Random

object PinBlockUtil {

    enum class ISO { ISO_0, ISO_1, ISO_2, ISO_3 }

    private val rng = Random()

    /**
     * Encode PIN to 16-hex-char PIN block.
     * pan: full PAN string (card number). Uses 12 rightmost digits excluding check digit.
     */
    @JvmStatic
    fun pinBlockEncode(pinDigits: String, pan: String, iso: ISO): String {
        require(pinDigits.matches(Regex("\\d{4,12}"))) { "PIN must be 4-12 digits" }
        require(pan.length >= 13) { "PAN too short" }

        val mode = iso.ordinal
        val lenNibble = Integer.toHexString(pinDigits.length).uppercase()
        var pinField = Integer.toHexString(mode).uppercase() + lenNibble + pinDigits

        val pan12 = pan.substring(pan.length - 13, pan.length - 1) // 12 rightmost excluding check digit

        return when (mode) {
            0 -> {
                val pinBlock = paddingWith(pinField, "F", 16, end = true)
                val panBlock = paddingWith(pan12, "0", 16, end = false)
                xorHexBlocks(pinBlock, panBlock)
            }

            1 -> {
                while (pinField.length < 16) {
                    pinField += Integer.toHexString(rng.nextInt(16)).uppercase()
                }
                pinField
            }

            2 -> paddingWith(pinField, "F", 16, end = true)

            3 -> {
                while (pinField.length < 16) {
                    pinField += Integer.toHexString(rng.nextInt(16)).uppercase()
                }
                val panBlock = paddingWith(pan12, "0", 16, end = false)
                xorHexBlocks(pinField, panBlock)
            }

            else -> throw IllegalArgumentException("Unsupported ISO mode: $mode")
        }
    }

    /**
     * Decode 16-hex-char PIN block back to PIN digits.
     * pan: full PAN string (card number). Uses 12 rightmost digits excluding check digit.
     */
    @JvmStatic
    fun pinBlockDecode(pinBlockHex: String, pan: String): String {
        require(pinBlockHex.matches(Regex("^[0-9A-Fa-f]{16}$"))) { "PIN block must be 16 hex chars" }
        require(pan.length >= 13) { "PAN too short" }

        val mode = pinBlockHex.substring(0, 1).toInt(16)
        val pan12 = pan.substring(pan.length - 13, pan.length - 1)

        val clearBlock = when (mode) {
            0, 3 -> {
                val panBlock = paddingWith(pan12, "0", 16, end = false)
                xorHexBlocks(pinBlockHex.uppercase(), panBlock.uppercase())
            }
            1, 2 -> pinBlockHex.uppercase()
            else -> throw IllegalArgumentException("Unsupported ISO mode: $mode")
        }

        // clearBlock format: [mode nibble][len nibble][PIN digits...][padding]
        val pinLen = clearBlock.substring(1, 2).toInt(16)
        require(pinLen in 4..12) { "Invalid PIN length: $pinLen" }

        return clearBlock.substring(2, 2 + pinLen)
    }

    // ---------------- internal helpers ----------------

    private fun xorHexBlocks(hex16A: String, hex16B: String): String {
        val a = hexStringToByte(hex16A)
        val b = hexStringToByte(hex16B)
        val out = StringBuilder(16)
        for (i in a.indices) {
            val v = (byte2Int(a[i]) xor byte2Int(b[i])) and 0xFF
            out.append(zeroPadding(Integer.toHexString(v).uppercase(), 2))
        }
        return out.toString()
    }

    @JvmStatic
    fun paddingWith(s: String, f: String, length: Int, end: Boolean): String {
        if (s.length >= length) return s
        val sb = StringBuilder(s)
        while (sb.length != length) {
            if (end) sb.append(f) else sb.insert(0, f)
        }
        return sb.toString()
    }

    private fun byte2Int(b: Byte): Int {
        var a = b.toInt()
        if (a < 0) a += 256
        return a
    }

    private fun zeroPadding(s: String, len: Int): String = paddingWith(s, "0", len, end = false)

    private fun hexStringToByte(hex: String): ByteArray {
        var hs = hex
        var len = hs.length
        if (len % 2 != 0) {
            hs = zeroPadding(hs, len + 1)
            len++
        }

        val output = ByteArray(len / 2)
        var idx = 0
        for (i in 0 until len / 2) {
            val v = hs.substring(idx, idx + 2).toInt(16)
            output[i] = v.toByte()
            idx += 2
        }
        return output
    }
}