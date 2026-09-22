package com.dopamide.motoanc;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

public class BudsManager implements BudsConnection.Listener {
    private static BudsManager instance;
    private BudsConnection connection;
    private final List<BudsConnection.Listener> listeners = new ArrayList<>();
    private String targetAddress;
    private boolean connected = false;
    private int currentAnc = BudsProtocol.ANC_OFF;

    public static synchronized BudsManager get(Context context) {
        if (instance == null) instance = new BudsManager();
        return instance;
    }

    public void addListener(BudsConnection.Listener listener) {
        boolean already;
        boolean wasConnected;
        int mode;
        synchronized (this) {
            already = listeners.contains(listener);
            if (!already) listeners.add(listener);
            wasConnected = connected;
            mode = currentAnc;
        }
        if (already) return;
        if (wasConnected) listener.onConnected();
        listener.onAncMode(mode);
    }

    public synchronized void removeListener(BudsConnection.Listener listener) {
        listeners.remove(listener);
    }

    public synchronized void start(Context context, String address) {
        targetAddress = address;
        if (connection == null) {
            connection = new BudsConnection(context.getApplicationContext(), this);
            connection.start(address);
        }
    }

    public synchronized void stop(BudsConnection.Listener listener) {
        removeListener(listener);
        if (listeners.isEmpty() && connection != null) {
            connection.stop();
            connection = null;
        }
    }

    public synchronized void sendAncMode(int mode) {
        if (connection != null) connection.sendAncMode(mode);
    }

    public synchronized void queryAnc() {
        if (connection != null) connection.queryAnc();
    }

    public synchronized void queryBattery() {
        if (connection != null) connection.queryBattery();
    }

    public synchronized boolean isRunning() {
        return connection != null;
    }

    @Override
    public void onConnected() {
        List<BudsConnection.Listener> snap;
        synchronized (this) {
            connected = true;
            snap = new ArrayList<>(listeners);
        }
        for (BudsConnection.Listener l : snap) l.onConnected();
    }

    @Override
    public void onDisconnected() {
        List<BudsConnection.Listener> snap;
        synchronized (this) {
            connected = false;
            snap = new ArrayList<>(listeners);
        }
        for (BudsConnection.Listener l : snap) l.onDisconnected();
    }

    @Override
    public void onBattery(BudsProtocol.Battery left, BudsProtocol.Battery right, BudsProtocol.Battery caseBattery) {
        List<BudsConnection.Listener> snap;
        synchronized (this) {
            snap = new ArrayList<>(listeners);
        }
        for (BudsConnection.Listener l : snap) l.onBattery(left, right, caseBattery);
    }

    @Override
    public void onAncMode(int mode) {
        List<BudsConnection.Listener> snap;
        synchronized (this) {
            currentAnc = mode;
            snap = new ArrayList<>(listeners);
        }
        for (BudsConnection.Listener l : snap) l.onAncMode(mode);
    }
}
