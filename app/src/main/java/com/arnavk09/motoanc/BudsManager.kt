package com.arnavk09.motoanc

import android.content.Context

/**
 * Process-wide singleton that owns a single [BudsConnection] and fans its
 * callbacks out to every registered listener (the activity and the QS tile).
 */
class BudsManager private constructor() : BudsConnection.Listener {
    private var connection: BudsConnection? = null
    private val listeners = ArrayList<BudsConnection.Listener>()
    private var targetAddress: String? = null
    private var connected = false
    private var currentAnc = BudsProtocol.ANC_OFF

    fun addListener(listener: BudsConnection.Listener) {
        val already: Boolean
        val wasConnected: Boolean
        val mode: Int
        synchronized(this) {
            already = listeners.contains(listener)
            if (!already) listeners.add(listener)
            wasConnected = connected
            mode = currentAnc
        }
        if (already) return
        if (wasConnected) listener.onConnected()
        listener.onAncMode(mode)
    }

    @Synchronized
    fun removeListener(listener: BudsConnection.Listener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun start(
        context: Context,
        address: String?,
    ) {
        targetAddress = address
        if (connection == null) {
            connection = BudsConnection(context.applicationContext, this)
            connection!!.start(address)
        }
    }

    @Synchronized
    fun stop(listener: BudsConnection.Listener) {
        removeListener(listener)
        if (listeners.isEmpty() && connection != null) {
            connection!!.stop()
            connection = null
        }
    }

    @Synchronized
    fun sendAncMode(mode: Int) {
        connection?.sendAncMode(mode)
    }

    @Synchronized
    fun queryAnc() {
        connection?.queryAnc()
    }

    @Synchronized
    fun queryBattery() {
        connection?.queryBattery()
    }

    @Synchronized
    fun isRunning(): Boolean = connection != null

    override fun onConnected() {
        val snap: List<BudsConnection.Listener>
        synchronized(this) {
            connected = true
            snap = ArrayList(listeners)
        }
        for (l in snap) l.onConnected()
    }

    override fun onDisconnected() {
        val snap: List<BudsConnection.Listener>
        synchronized(this) {
            connected = false
            snap = ArrayList(listeners)
        }
        for (l in snap) l.onDisconnected()
    }

    override fun onBattery(
        left: BudsProtocol.Battery,
        right: BudsProtocol.Battery,
        caseBattery: BudsProtocol.Battery,
    ) {
        val snap: List<BudsConnection.Listener>
        synchronized(this) {
            snap = ArrayList(listeners)
        }
        for (l in snap) l.onBattery(left, right, caseBattery)
    }

    override fun onAncMode(mode: Int) {
        val snap: List<BudsConnection.Listener>
        synchronized(this) {
            currentAnc = mode
            snap = ArrayList(listeners)
        }
        for (l in snap) l.onAncMode(mode)
    }

    companion object {
        private var instance: BudsManager? = null

        @JvmStatic
        @Synchronized
        fun get(context: Context): BudsManager {
            if (instance == null) instance = BudsManager()
            return instance!!
        }
    }
}
