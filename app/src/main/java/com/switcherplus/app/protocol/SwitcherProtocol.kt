package com.switcherplus.app.protocol

/**
 * Pure (no Android) implementation of the local Switcher "Type 1" protocol
 * (Power Plug, V2, V4, Touch). Ported from the open-source aioswitcher
 * library (Apache-2.0) by Tomer Figenblat.
 */
object SwitcherProtocol {

    const val TCP_PORT = 9957
    val UDP_PORTS = intArrayOf(20002, 10002)

    /** Supported Type-1 device models (hex code at bytes 74..76 of the broadcast). */
    val SUPPORTED_MODELS = mapOf(
        "01a8" to "Switcher Power Plug",
        "030b" to "Switcher Touch",
        "01a7" to "Switcher V2",
        "01a1" to "Switcher V2",
        "0317" to "Switcher V4",
    )

    private const val REQUEST_FORMAT = "%s340001000000000000000000%s00000000000000000000f0fe"
    private val PAD_72_ZEROS = "0".repeat(72)
    private const val P_SESSION = "00000000"
    const val NO_TIMER = "00000000"

    // ---------- hex helpers ----------

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun bytesToHex(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String {
        val sb = StringBuilder((to - from) * 2)
        for (i in from until to) {
            val v = bytes[i].toInt() and 0xff
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private fun byteHex(v: Int): String = bytesToHex(byteArrayOf(v.toByte()))

    /** Unsigned 32-bit little-endian as 8 hex chars. */
    fun u32LeHex(value: Long): String {
        val b = ByteArray(4) { i -> ((value shr (8 * i)) and 0xff).toByte() }
        return bytesToHex(b)
    }

    fun readU32Le(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (bytes[offset + i].toLong() and 0xff)
        return v
    }

    fun readU16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    // ---------- CRC signing (binascii.crc_hqx compatible) ----------

    fun crcHqx(data: ByteArray, initial: Int): Int {
        var crc = initial and 0xffff
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xffff
            }
        }
        return crc
    }

    /** CRC as two bytes, low byte first, as hex (matches the python slicing). */
    private fun crcLeHex(crc: Int): String = byteHex(crc and 0xff) + byteHex((crc shr 8) and 0xff)

    fun signPacket(hexPacket: String): String {
        val packetCrc = crcLeHex(crcHqx(hexToBytes(hexPacket), 0x1021))
        val key = hexToBytes(packetCrc + "30".repeat(32))
        val keyCrc = crcLeHex(crcHqx(key, 0x1021))
        return hexPacket + packetCrc + keyCrc
    }

    /** Overwrites the length field (bytes 2..4) with total length incl. 4 CRC bytes. */
    fun setMessageLength(message: String): String {
        val length = message.length / 2 + 4
        val lenHex = byteHex(length and 0xff) + byteHex((length shr 8) and 0xff)
        return "fef0" + lenHex + message.substring(8)
    }

    fun finalizePacket(hex: String): ByteArray = hexToBytes(signPacket(setMessageLength(hex)))

    fun timestampHex(epochSeconds: Long = System.currentTimeMillis() / 1000): String = u32LeHex(epochSeconds)

    fun minutesToHexSeconds(minutes: Int): String = u32LeHex(minutes.toLong() * 60)

    // ---------- packet builders (unsigned hex, before length/CRC) ----------

    fun loginPacket(timestamp: String, deviceKey: String): String =
        "fef052000232a100" + P_SESSION + REQUEST_FORMAT.substring(2).format(timestamp) +
            deviceKey + PAD_72_ZEROS + "00"

    fun getStatePacket(session: String, timestamp: String, deviceId: String): String =
        "fef0300002320103" + REQUEST_FORMAT.format(session, timestamp) + deviceId + "00"

    /** on=true turns on; timerMinutes>0 makes the device turn itself off after that many minutes. */
    fun controlPacket(session: String, timestamp: String, deviceId: String, on: Boolean, timerMinutes: Int): String {
        val timer = if (on && timerMinutes > 0) minutesToHexSeconds(timerMinutes) else NO_TIMER
        val cmd = if (on) "1" else "0"
        return "fef05d0002320102" + REQUEST_FORMAT.format(session, timestamp) + deviceId +
            PAD_72_ZEROS + "000106000" + cmd + "00" + timer
    }

    // ---------- parsers ----------

    fun parseSessionId(loginResponse: ByteArray): String {
        require(loginResponse.size >= 12) { "login response too short" }
        return bytesToHex(loginResponse, 8, 12)
    }

    data class StateResponse(
        val on: Boolean,
        val powerWatts: Int,
        val timeLeftSec: Long,
        val timeOnSec: Long,
        val autoShutdownSec: Long,
    )

    fun parseState(resp: ByteArray): StateResponse {
        require(resp.size >= 101) { "state response too short (${resp.size})" }
        return StateResponse(
            on = (resp[75].toInt() and 0xff) == 1,
            powerWatts = readU16Le(resp, 77),
            timeLeftSec = readU32Le(resp, 89),
            timeOnSec = readU32Le(resp, 93),
            autoShutdownSec = readU32Le(resp, 97),
        )
    }

    data class Broadcast(
        val deviceId: String,
        val deviceKey: String,
        val name: String,
        val model: String,
        val modelName: String,
        val mac: String,
        val on: Boolean,
        val powerWatts: Int,
        val remainingSec: Long,
        val autoShutdownSec: Long,
    )

    /** Returns null for anything that isn't a supported Type-1 Switcher broadcast. */
    fun parseBroadcast(msg: ByteArray): Broadcast? {
        if (msg.size != 165) return null
        if ((msg[0].toInt() and 0xff) != 0xfe || (msg[1].toInt() and 0xff) != 0xf0) return null
        val model = bytesToHex(msg, 74, 76)
        val modelName = SUPPORTED_MODELS[model] ?: return null
        val on = (msg[133].toInt() and 0xff) == 1
        val nameBytes = msg.copyOfRange(42, 74)
        val nameEnd = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
        val mac = (80 until 86).joinToString(":") { byteHex(msg[it].toInt() and 0xff).uppercase() }
        return Broadcast(
            deviceId = bytesToHex(msg, 18, 21),
            deviceKey = bytesToHex(msg, 40, 41),
            name = String(nameBytes, 0, nameEnd, Charsets.UTF_8).trim(),
            model = model,
            modelName = modelName,
            mac = mac,
            on = on,
            powerWatts = if (on) readU16Le(msg, 135) else 0,
            remainingSec = readU32Le(msg, 147),
            autoShutdownSec = readU32Le(msg, 155),
        )
    }
}
