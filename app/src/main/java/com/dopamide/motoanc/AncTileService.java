package com.dopamide.motoanc;

import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class AncTileService extends TileService implements BudsConnection.Listener {
    private static final int CLICK_TIMEOUT_MS = 8000;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clickTimeout = this::stopSelfConnection;
    private boolean clickPending = false;
    private int currentAnc = BudsProtocol.ANC_OFF;
    private boolean isConnected = false;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("buds", MODE_PRIVATE);
        currentAnc = prefs.getInt("anc_mode", BudsProtocol.ANC_OFF);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
        BudsManager.get(this).addListener(this);
        BudsManager.get(this).start(this, DeviceFinder.find(this));
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        BudsManager.get(this).stop(this);
        handler.removeCallbacks(clickTimeout);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        BudsManager.get(this).stop(this);
        handler.removeCallbacks(clickTimeout);
    }

    @Override
    public void onClick() {
        super.onClick();
        if (clickPending) return;
        clickPending = true;
        handler.postDelayed(clickTimeout, CLICK_TIMEOUT_MS);
        BudsManager.get(this).addListener(this);
        BudsManager.get(this).start(this, DeviceFinder.find(this));
        maybeSendToggle();
    }

    private void maybeSendToggle() {
        if (isConnected && clickPending) sendToggle();
    }

    private void sendToggle() {
        int target = (currentAnc == BudsProtocol.ANC_OFF) ? BudsProtocol.ANC_ANC : BudsProtocol.ANC_OFF;
        BudsManager.get(this).sendAncMode(target);
        finishClick();
    }

    @Override
    public void onConnected() {
        isConnected = true;
        updateTile();
        BudsManager.get(this).queryAnc();
        maybeSendToggle();
    }

    @Override
    public void onDisconnected() {
        isConnected = false;
        updateTile();
    }

    @Override
    public void onBattery(BudsProtocol.Battery left, BudsProtocol.Battery right, BudsProtocol.Battery caseBattery) {
        updateTile();
    }

    @Override
    public void onAncMode(int mode) {
        currentAnc = mode;
        prefs.edit().putInt("anc_mode", mode).apply();
        updateTile();
    }

    private void finishClick() {
        clickPending = false;
        handler.removeCallbacks(clickTimeout);
    }

    private void stopSelfConnection() {
        finishClick();
        BudsManager.get(this).stop(this);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        int state = isConnected ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        String subtitle;
        if (!isConnected) {
            subtitle = "Disconnected";
            state = Tile.STATE_INACTIVE;
        } else if (currentAnc == BudsProtocol.ANC_OFF) {
            subtitle = "Off";
            state = Tile.STATE_INACTIVE;
        } else if (currentAnc == BudsProtocol.ANC_TRANSPARENCY) {
            subtitle = "Transparency";
            state = Tile.STATE_ACTIVE;
        } else if (currentAnc == BudsProtocol.ANC_ANC) {
            subtitle = "ANC";
            state = Tile.STATE_ACTIVE;
        } else {
            subtitle = "Adaptive";
            state = Tile.STATE_ACTIVE;
        }

        tile.setState(state);
        tile.setLabel("Moto Buds+");
        tile.setSubtitle(subtitle);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_anc_on));
        tile.updateTile();
    }
}
