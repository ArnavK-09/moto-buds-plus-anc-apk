package com.arnavk09.motoanc

import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class AncTileService :
    TileService(),
    BudsConnection.Listener {
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val clickTimeout = Runnable { finishClick() }
    private val stopTimeout = Runnable { BudsManager.get(this).stop(this) }
    private var clickPending = false
    private var currentAnc = BudsProtocol.ANC_OFF
    private var isConnected = false
    private var bondLost = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("buds", MODE_PRIVATE)
        currentAnc = prefs.getInt("anc_mode", BudsProtocol.ANC_OFF)
    }

    override fun onStartListening() {
        super.onStartListening()
        handler.removeCallbacks(stopTimeout)
        updateTile()
        BudsManager.get(this).addListener(this)
        BudsManager.get(this).start(this, DeviceFinder.find(this))
    }

    override fun onStopListening() {
        super.onStopListening()
        handler.removeCallbacks(stopTimeout)
        handler.postDelayed(stopTimeout, STOP_DELAY_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stopTimeout)
        handler.removeCallbacks(clickTimeout)
        BudsManager.get(this).stop(this)
    }

    override fun onClick() {
        super.onClick()
        if (clickPending) return
        clickPending = true
        handler.removeCallbacks(clickTimeout)
        handler.postDelayed(clickTimeout, CLICK_TIMEOUT_MS)
        handler.removeCallbacks(stopTimeout)
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
        bondLost = false
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

    override fun onConfig(
        key: String,
        on: Boolean,
    ) {
    }

    override fun onGesture(
        side: Int,
        index: Int,
        action: Int,
    ) {
    }

    override fun onStatus(text: String) {
    }

    override fun onBondLost() {
        bondLost = true
        finishClick()
        updateTile()
    }

    private fun finishClick() {
        clickPending = false
        handler.removeCallbacks(clickTimeout)
    }

    @Suppress("DEPRECATION")
    private fun updateTile() {
        val tile: Tile = getQsTile() ?: return

        var state = if (isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val subtitle: String
        if (bondLost) {
            subtitle = "Re-pair"
            state = Tile.STATE_INACTIVE
        } else if (!isConnected) {
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
        val iconRes =
            if (!isConnected || bondLost) {
                R.drawable.ic_anc_off
            } else {
                when (currentAnc) {
                    BudsProtocol.ANC_TRANSPARENCY -> R.drawable.ic_transparency
                    BudsProtocol.ANC_ADAPTIVE -> R.drawable.ic_adaptive
                    BudsProtocol.ANC_ANC -> R.drawable.ic_anc_on
                    else -> R.drawable.ic_anc_off
                }
            }
        tile.setIcon(Icon.createWithResource(this, iconRes))
        tile.updateTile()
    }

    companion object {
        private const val CLICK_TIMEOUT_MS = 15000L
        private const val STOP_DELAY_MS = 30000L
    }
}
