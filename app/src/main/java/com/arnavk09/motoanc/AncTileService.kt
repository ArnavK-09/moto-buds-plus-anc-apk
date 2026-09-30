package com.arnavk09.motoanc

import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile that toggles ANC on/off, reusing the shared connection. */
class AncTileService :
    TileService(),
    BudsConnection.Listener {
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val clickTimeout = Runnable { stopSelfConnection() }
    private var clickPending = false
    private var currentAnc = BudsProtocol.ANC_OFF
    private var isConnected = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("buds", MODE_PRIVATE)
        currentAnc = prefs.getInt("anc_mode", BudsProtocol.ANC_OFF)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        BudsManager.get(this).addListener(this)
        BudsManager.get(this).start(this, DeviceFinder.find(this))
    }

    override fun onStopListening() {
        super.onStopListening()
        BudsManager.get(this).stop(this)
        handler.removeCallbacks(clickTimeout)
    }

    override fun onDestroy() {
        super.onDestroy()
        BudsManager.get(this).stop(this)
        handler.removeCallbacks(clickTimeout)
    }

    override fun onClick() {
        super.onClick()
        if (clickPending) return
        clickPending = true
        handler.postDelayed(clickTimeout, CLICK_TIMEOUT_MS)
        BudsManager.get(this).addListener(this)
        BudsManager.get(this).start(this, DeviceFinder.find(this))
        maybeSendToggle()
    }

    private fun maybeSendToggle() {
        if (isConnected && clickPending) sendToggle()
    }

    private fun sendToggle() {
        val target = if (currentAnc == BudsProtocol.ANC_OFF) BudsProtocol.ANC_ANC else BudsProtocol.ANC_OFF
        BudsManager.get(this).sendAncMode(target)
        finishClick()
    }

    override fun onConnected() {
        isConnected = true
        updateTile()
        BudsManager.get(this).queryAnc()
        maybeSendToggle()
    }

    override fun onDisconnected() {
        isConnected = false
        updateTile()
    }

    override fun onBattery(
        left: BudsProtocol.Battery,
        right: BudsProtocol.Battery,
        caseBattery: BudsProtocol.Battery,
    ) {
        updateTile()
    }

    override fun onAncMode(mode: Int) {
        currentAnc = mode
        prefs.edit().putInt("anc_mode", mode).apply()
        updateTile()
    }

    private fun finishClick() {
        clickPending = false
        handler.removeCallbacks(clickTimeout)
    }

    private fun stopSelfConnection() {
        finishClick()
        BudsManager.get(this).stop(this)
    }

    @Suppress("DEPRECATION")
    private fun updateTile() {
        val tile: Tile = getQsTile() ?: return

        var state = if (isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val subtitle: String
        if (!isConnected) {
            subtitle = "Disconnected"
            state = Tile.STATE_INACTIVE
        } else if (currentAnc == BudsProtocol.ANC_OFF) {
            subtitle = "Off"
            state = Tile.STATE_INACTIVE
        } else if (currentAnc == BudsProtocol.ANC_TRANSPARENCY) {
            subtitle = "Transparency"
            state = Tile.STATE_ACTIVE
        } else if (currentAnc == BudsProtocol.ANC_ANC) {
            subtitle = "ANC"
            state = Tile.STATE_ACTIVE
        } else {
            subtitle = "Adaptive"
            state = Tile.STATE_ACTIVE
        }

        tile.setState(state)
        tile.setLabel("Moto Buds+")
        tile.setSubtitle(subtitle)
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_anc_on))
        tile.updateTile()
    }

    companion object {
        private const val CLICK_TIMEOUT_MS = 8000L
    }
}
