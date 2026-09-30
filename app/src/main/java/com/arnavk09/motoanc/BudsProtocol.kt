package com.arnavk09.motoanc

/**
 * Wire protocol for the Moto Buds+ RFCOMM service: frame construction, CRC32,
 * stream reassembly, and the small payload codecs the app needs.
 */
class BudsProtocol {
    private var seq = 1
    private var reassembly: MutableList<Byte> = ArrayList()

    @Synchronized
    fun buildFrame(
        opcode: Int,
        payload: ByteArray,
    ): ByteArray {
        val s = seq
        seq = (seq + 1) and 0xffff
        if (seq == 0) seq = 1
        val len = payload.size
        val inner = ByteArray(8 + len)
        inner[0] = ((opcode shr 8) and 0xff).toByte()
        inner[1] = (opcode and 0xff).toByte()
        inner[2] = COMMAND_TYPE
        inner[3] = 0x00
        inner[4] = (len and 0xff).toByte()
        inner[5] = ((len shr 8) and 0xff).toByte()
        inner[6] = (s and 0xff).toByte()
        inner[7] = ((s shr 8) and 0xff).toByte()
        payload.copyInto(inner, 8)
        return frame(inner)
    }

    @Synchronized
    fun unframe(chunk: ByteArray): List<ByteArray> {
        var buf = ArrayList<Byte>(reassembly)
        reassembly.clear()
        for (b in chunk) buf.add(b)
        val out = ArrayList<ByteArray>()
        while (buf.size >= 14) {
            var headIdx = -1
            for (i in 0..buf.size - 4) {
                if (buf[i] == HEAD[0] &&
                    buf[i + 1] == HEAD[1] &&
                    buf[i + 2] == HEAD[2] &&
                    buf[i + 3] == HEAD[3]
                ) {
                    headIdx = i
                    break
                }
            }
            if (headIdx == -1) break
            if (buf.size - headIdx < 14) {
                reassembly = ArrayList(buf.subList(headIdx, buf.size))
                break
            }
            val outerLen =
                (buf[headIdx + 4].toInt() and 0xff) or
                    ((buf[headIdx + 5].toInt() and 0xff) shl 8)
            val end = headIdx + 4 + 2 + outerLen + 4 + 4
            if (buf.size < end) {
                reassembly = ArrayList(buf.subList(headIdx, buf.size))
                break
            }
            val tailOk =
                buf[end - 4] == TAIL[0] &&
                    buf[end - 3] == TAIL[1] &&
                    buf[end - 2] == TAIL[2] &&
                    buf[end - 1] == TAIL[3]
            if (!tailOk) {
                buf = ArrayList(buf.subList(headIdx + 1, buf.size))
                continue
            }
            val inner = ByteArray(outerLen)
            for (i in 0 until outerLen) inner[i] = buf[headIdx + 6 + i]
            out.add(inner)
            buf = ArrayList(buf.subList(end, buf.size))
        }
        if (buf.size > 0 && buf.size < 1000) {
            reassembly = ArrayList(buf)
        } else {
            reassembly.clear()
        }
        return out
    }

    class Packet(
        val opcode: Int,
        val type: Int,
        val payload: ByteArray,
    )

    class Battery(
        val level: Int,
        val charging: Boolean,
        val reported: Boolean,
    )

    companion object {
        const val SERVICE_UUID = "fc9d9fe0-4899-11ee-be56-0242ac120002"

        private val HEAD = byteArrayOf(0x48, 0x45, 0x41, 0x44)
        private val TAIL = byteArrayOf(0x54, 0x41, 0x49, 0x4c)
        private val COMMAND_TYPE: Byte = 0x80.toByte()

        const val GET_BATTERY_LEVEL = 0x005
        const val BATTERY_LEVEL_CHANGED = 0x009
        const val GET_TOGGLE_CONFIGS = 0x100
        const val GET_TOGGLE_CONFIG = 0x101
        const val SET_TOGGLE_CONFIG = 0x102
        const val TOGGLE_CONFIG_CHANGED = 0x105
        const val GET_ANC_MODE = 0x200
        const val SET_ANC_MODE = 0x201
        const val SET_ADAPTATION_STATUS = 0x203
        const val ANC_MODE_CHANGED = 0x204
        const val TOGGLE_CATEGORY_ANC_PREFERENCE = 0x01

        const val ANC_OFF = 0
        const val ANC_TRANSPARENCY = 1
        const val ANC_ANC = 2
        const val ANC_ADAPTIVE = 3

        private val ANC_MODE_BYTES =
            arrayOf(
                byteArrayOf(0x00, 0x00),
                byteArrayOf(0x02, 0x00),
                byteArrayOf(0x01, 0x03),
                byteArrayOf(0x01, 0x01),
            )

        private val ANC_PREF_BYTES =
            arrayOf(
                byteArrayOf(0x01, 0x00, 0x00),
                byteArrayOf(0x01, 0x02, 0x00),
                byteArrayOf(0x01, 0x01, 0x03),
                byteArrayOf(0x01, 0x01, 0x01),
            )

        private fun frame(inner: ByteArray): ByteArray {
            val n = inner.size
            val out = ByteArray(4 + 2 + n + 4 + 4)
            var o = 0
            for (b in HEAD) out[o++] = b
            out[o++] = (n and 0xff).toByte()
            out[o++] = ((n shr 8) and 0xff).toByte()
            inner.copyInto(out, o)
            o += n
            val crc = crc32(inner)
            out[o++] = (crc and 0xff).toByte()
            out[o++] = ((crc shr 8) and 0xff).toByte()
            out[o++] = ((crc shr 16) and 0xff).toByte()
            out[o++] = ((crc shr 24) and 0xff).toByte()
            for (b in TAIL) out[o++] = b
            return out
        }

        private fun crc32(data: ByteArray): Int {
            var crc = 0xffffffff.toInt()
            for (b in data) {
                crc = crc xor (b.toInt() and 0xff)
                for (i in 0 until 8) {
                    val poly = if ((crc and 1) != 0) 0xedb88320.toInt() else 0
                    crc = (crc ushr 1) xor poly
                }
            }
            return (crc xor 0xffffffff.toInt()) ushr 0
        }

        fun decode(inner: ByteArray): Packet? {
            if (inner.size < 8) return null
            val opcode = ((inner[0].toInt() and 0xff) shl 8) or (inner[1].toInt() and 0xff)
            val type = inner[2].toInt() and 0xff
            val len = (inner[4].toInt() and 0xff) or ((inner[5].toInt() and 0xff) shl 8)
            if (inner.size < 8 + len) return null
            val payload = ByteArray(len)
            System.arraycopy(inner, 8, payload, 0, len)
            return Packet(opcode, type, payload)
        }

        fun decodeAnc(
            cat: Int,
            sub: Int,
        ): Int =
            when {
                cat == 0x00 && sub == 0x00 -> ANC_OFF
                cat == 0x02 && sub == 0x00 -> ANC_TRANSPARENCY
                cat == 0x01 && sub == 0x03 -> ANC_ANC
                cat == 0x01 && sub == 0x01 -> ANC_ADAPTIVE
                else -> ANC_OFF
            }

        fun ancModePayload(mode: Int): ByteArray = ANC_MODE_BYTES[mode].clone()

        fun ancPrefPayload(mode: Int): ByteArray = ANC_PREF_BYTES[mode].clone()

        fun parseBattery(b: Byte): Battery {
            val v = b.toInt() and 0xff
            if (v == 0xff) return Battery(0, false, false)
            return Battery(v and 0x7f, (v and 0x80) != 0, true)
        }
    }
}
