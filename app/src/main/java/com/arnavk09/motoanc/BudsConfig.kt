package com.arnavk09.motoanc

/**
 * Config features exposed by the Moto Buds RFCOMM protocol.
 *
 * Every feature is addressed by a get/set opcode pair, with an optional
 * "changed" notification opcode. Values are a single byte (0 / nonzero);
 * "dual" features take a two byte payload (slot, value).
 */
object BudsConfig {
    class Feature(
        val key: String,
        val label: String,
        val getOp: Int,
        val setOp: Int,
        val notifyOp: Int,
        val dual: Boolean = false,
    )

    val ANC = Feature("anc", "Noise control", 0x200, 0x201, 0x204)

    /** Boolean config features available on Moto Buds+ XT2441-1. */
    val TOGGLES =
        listOf(
            Feature("hires", "Hi-Res Mode", 0x30c, 0x30d, 0x311),
            Feature("game", "Game mode", 0x30e, 0x30f, 0x312),
            Feature("volboost", "Volume boost", 0x313, 0x314, 0x315),
            Feature("ineardetect", "In-ear Detection", 0x402, 0x403, 0x404),
            Feature("dual", "Dual connection", 0x406, 0x407, 0x40f, dual = true),
        )

    val ALL = listOf(ANC) + TOGGLES

    private val byResponse = HashMap<Int, Feature>()
    private val byKey = HashMap<String, Feature>()

    init {
        for (f in ALL) {
            byResponse[f.getOp] = f
            byResponse[f.notifyOp] = f
            byKey[f.key] = f
        }
    }

    fun forResponse(opcode: Int): Feature? = byResponse[opcode]

    fun forKey(key: String): Feature? = byKey[key]

    /** Payload for a set request. */
    fun payload(
        f: Feature,
        on: Boolean,
    ): ByteArray {
        val v = if (on) 0x01 else 0x00
        return if (f.dual) byteArrayOf(0x01, v.toByte()) else byteArrayOf(v.toByte())
    }

    /** Interpret a get / notification payload: last byte is the state. */
    fun stateOf(p: ByteArray): Boolean = p.isNotEmpty() && (p[p.size - 1].toInt() and 0xff) != 0
}
