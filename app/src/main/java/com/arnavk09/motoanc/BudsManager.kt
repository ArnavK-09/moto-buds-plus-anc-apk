package com.arnavk09.motoanc

import android.content.Context

class BudsManager private constructor() : BudsConnection.Listener {
    private var connection: BudsConnection? = null
    private val listeners = ArrayList<BudsConnection.Listener>()
    private var targetAddress: String? = null
    private var connected = false
    private var currentAnc = BudsProtocol.ANC_OFF
    private var lastStatus: String? = null

    fun addListener(listener: BudsConnection.Listener) {
        val already: Boolean
        val wasConnected: Boolean
        val mode: Int
        val status: String?
        synchronized(this) {
            already = listeners.contains(listener)
            if (!already) listeners.add(listener)
            wasConnected = connected
            mode = currentAnc
            status = lastStatus
        }
        if (already) return
        if (wasConnected) listener.onConnected()
        listener.onAncMode(mode)
        status?.let { listener.onStatus(it) }
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
    fun reload(context: Context) {
        BudsLog.d("manager reload")
        teardown()
        start(context, DeviceFinder.find(context))
    }

    @Synchronized
    fun forgetAndReload(context: Context) {
        val address = targetAddress
        BudsLog.d("manager forgetAndReload " + address)
        connection?.forgetBond()
        teardown()
        start(context, address)
    }

    private fun teardown() {
        connection?.stop()
        connection = null
        connected = false
    }

    @Synchronized
    fun stop(listener: BudsConnection.Listener) {
        removeListener(listener)
        if (listeners.isEmpty() && connection != null) {
            BudsLog.d("manager stop: no listeners, tearing down")
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
    fun queryConfigs() {
        connection?.queryConfigs()
    }

    @Synchronized
    fun setConfig(
        key: String,
        on: Boolean,
    ) {
        connection?.setConfig(key, on)
    }

    @Synchronized
    fun setGesture(
        side: Int,
        index: Int,
        action: Int,
    ) {
        connection?.setGesture(side, index, action)
    }

    @Synchronized
    fun queryGestures() {
        connection?.queryToggleConfigs()
    }

    @Synchronized
    fun setRingBuds(
        left: Boolean,
        right: Boolean,
    ) {
        connection?.setRingBuds(left, right)
    }

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

    override fun onConfig(
        key: String,
        on: Boolean,
    ) {
        val snap: List<BudsConnection.Listener>
        synchronized(this) {
            snap = ArrayList(listeners)
        }
        for (l in snap) l.onConfig(key, on)
    }

    override fun onGesture(
        side: Int,
        index: Int,
        action: Int,
    ) {
        val snap: List<BudsConnection.Listener>
        synchronized(this) {
            snap = ArrayList(listeners)
        }
        for (l in snap) l.onGesture(side, index, action)
    }

    override fun onDualDevices(raw: ByteArray) {
        val snap: List<BudsConnection.Listener>
        synchronized(this) {
            snap = ArrayList(listeners)
        }
        for (l in snap) l.onDualDevices(raw)
    }

    override fun onStatus(text: String) {
        val snap: List<BudsConnection.Listener>
        synchronized(this) {
            lastStatus = text
            snap = ArrayList(listeners)
        }
        for (l in snap) l.onStatus(text)
    }

    override fun onBondLost() {
        val snap: List<BudsConnection.Listener>
        synchronized(this) {
            snap = ArrayList(listeners)
        }
        for (l in snap) l.onBondLost()
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
