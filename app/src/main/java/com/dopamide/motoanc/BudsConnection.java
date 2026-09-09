package com.dopamide.motoanc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class BudsConnection {
    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onBattery(BudsProtocol.Battery left, BudsProtocol.Battery right, BudsProtocol.Battery caseBattery);
        void onAncMode(int mode);
    }

    private static final String TAG = "MotoBuds";
    private static final UUID SERVICE_UUID = UUID.fromString(BudsProtocol.SERVICE_UUID);
    private static final UUID SERVICE_UUID_OLD = UUID.fromString("00009fe0-4899-11ee-be56-0242ac120002");
    private static final int RECONNECT_DELAY_MS = 5000;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BudsProtocol protocol = new BudsProtocol();
    private final BlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shouldRun = new AtomicBoolean(false);
    private volatile String targetAddress;
    private Thread connectThread;
    private Thread readThread;
    private Thread writeThread;
    private Thread refreshThread;

    private BluetoothSocket rfcommSocket;
    private InputStream rfcommIn;
    private OutputStream rfcommOut;

    public BudsConnection(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start(String address) {
        stop();
        targetAddress = address;
        shouldRun.set(true);
        connect();
    }

    public void stop() {
        shouldRun.set(false);
        running.set(false);
        closeRfcomm();
        interrupt(connectThread);
        interrupt(readThread);
        interrupt(writeThread);
        interrupt(refreshThread);
    }

    private void connect() {
        if (connectThread != null && connectThread.isAlive()) return;
        connectThread = new Thread(this::connectLoop, "buds-connect");
        connectThread.start();
    }

    private void connectLoop() {
        while (shouldRun.get()) {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) {
                    log("bluetooth not available");
                    sleep(RECONNECT_DELAY_MS);
                    continue;
                }
                if (!adapter.isEnabled()) {
                    log("bluetooth is off");
                    sleep(RECONNECT_DELAY_MS);
                    continue;
                }

                BluetoothDevice device = null;
                if (targetAddress != null) {
                    try { device = adapter.getRemoteDevice(targetAddress); } catch (Exception e) { log("bad address " + targetAddress); }
                }
                if (device == null) device = findBondedDevice(adapter);
                if (device == null) {
                    log("no Moto Buds found");
                    sleep(RECONNECT_DELAY_MS);
                    continue;
                }

                String name = device.getName();
                log("trying " + (name != null ? name : device.getAddress()));

                boolean connected = false;
                try {
                    connected = connectRfcomm(device);
                } catch (Exception e) {
                    log("rfcomm error " + e.getMessage());
                }

                if (!connected) {
                    log("rfcomm failed");
                    sleep(RECONNECT_DELAY_MS);
                    continue;
                }

                while (shouldRun.get() && running.get()) {
                    sleep(1000);
                }
            } catch (Exception e) {
                log("conn " + e.getMessage());
                Log.e(TAG, "connection error", e);
            } finally {
                running.set(false);
                closeRfcomm();
                postDisconnected();
            }
            if (!shouldRun.get()) break;
            sleep(RECONNECT_DELAY_MS);
        }
    }

    private BluetoothDevice findBondedDevice(BluetoothAdapter adapter) {
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String name = d.getName();
                if (name != null) {
                    String lower = name.toLowerCase();
                    if (lower.contains("moto buds") || lower.contains("motobuds") || lower.contains("moto buds+")) {
                        return d;
                    }
                }
                ParcelUuid[] uuids = d.getUuids();
                if (uuids != null) {
                    for (ParcelUuid u : uuids) {
                        if (u != null && (SERVICE_UUID.equals(u.getUuid()) || SERVICE_UUID_OLD.equals(u.getUuid()))) {
                            return d;
                        }
                    }
                }
            }
        } catch (SecurityException e) {
            log("scan perm " + e.getMessage());
        }
        return null;
    }

    private boolean connectRfcomm(BluetoothDevice device) {
        BluetoothSocket socket = null;

        log("rfcomm secure uuid");
        socket = tryRfcommUuid(device, false);
        if (socket == null) {
            log("rfcomm insecure uuid");
            socket = tryRfcommUuid(device, true);
        }
        if (socket == null) {
            for (int channel = 1; channel <= 30 && socket == null; channel++) {
                if (!shouldRun.get()) break;
                log("rfcomm channel " + channel);
                socket = tryRfcommChannel(device, channel, false);
                if (socket == null) {
                    log("rfcomm insecure channel " + channel);
                    socket = tryRfcommChannel(device, channel, true);
                }
                if (socket == null) sleep(100);
            }
        }
        if (socket == null) return false;

        try {
            rfcommSocket = socket;
            rfcommIn = socket.getInputStream();
            rfcommOut = socket.getOutputStream();
            running.set(true);
            startIoThreads();
            postConnected();
            startRefresh();
            log("rfcomm connected");
            return true;
        } catch (Exception e) {
            log("rfcomm streams failed " + e.getMessage());
            closeRfcomm();
            return false;
        }
    }

    private BluetoothSocket tryRfcommUuid(BluetoothDevice device, boolean insecure) {
        try {
            BluetoothSocket socket;
            if (insecure) {
                Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class);
                socket = (BluetoothSocket) m.invoke(device, SERVICE_UUID);
            } else {
                socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
            }
            socket.connect();
            return socket;
        } catch (Exception e) {
            log("uuid connect failed " + e.getMessage());
            return null;
        }
    }

    private BluetoothSocket tryRfcommChannel(BluetoothDevice device, int channel, boolean insecure) {
        try {
            Method m;
            if (insecure) m = device.getClass().getMethod("createInsecureRfcommSocket", int.class);
            else m = device.getClass().getMethod("createRfcommSocket", int.class);
            BluetoothSocket socket = (BluetoothSocket) m.invoke(device, channel);
            socket.connect();
            return socket;
        } catch (Exception e) {
            log("channel connect failed " + e.getMessage());
            return null;
        }
    }

    private void startIoThreads() {
        readThread = new Thread(this::readLoop, "buds-read");
        readThread.start();
        writeThread = new Thread(this::writeLoop, "buds-write");
        writeThread.start();
    }

    private void readLoop() {
        byte[] buffer = new byte[1024];
        while (running.get()) {
            try {
                if (rfcommIn == null) break;
                int n = rfcommIn.read(buffer);
                if (n < 0) {
                    log("rfcomm eof");
                    break;
                }
                if (n > 0) {
                    byte[] chunk = new byte[n];
                    System.arraycopy(buffer, 0, chunk, 0, n);
                    handleBytes(chunk);
                }
            } catch (Exception e) {
                log("rfcomm read " + e.getMessage());
                break;
            }
        }
        running.set(false);
        closeRfcomm();
    }

    private void writeLoop() {
        while (running.get()) {
            try {
                byte[] frame = writeQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                if (rfcommOut == null) break;
                rfcommOut.write(frame);
                rfcommOut.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log("rfcomm write " + e.getMessage());
                break;
            }
        }
    }

    private void closeRfcomm() {
        running.set(false);
        try { if (rfcommIn != null) rfcommIn.close(); } catch (Exception ignored) {}
        try { if (rfcommOut != null) rfcommOut.close(); } catch (Exception ignored) {}
        try { if (rfcommSocket != null) rfcommSocket.close(); } catch (Exception ignored) {}
        rfcommIn = null;
        rfcommOut = null;
        rfcommSocket = null;
        writeQueue.clear();
    }

    private void handleBytes(byte[] value) {
        if (value == null || value.length == 0) return;
        List<byte[]> inners = protocol.unframe(value);
        if (inners.isEmpty()) {
            BudsProtocol.Packet pkt = BudsProtocol.decode(value);
            if (pkt != null) handle(pkt);
        } else {
            for (byte[] inner : inners) {
                BudsProtocol.Packet pkt = BudsProtocol.decode(inner);
                if (pkt != null) handle(pkt);
            }
        }
    }

    private void handle(BudsProtocol.Packet pkt) {
        int t = pkt.type & 0xe0;
        boolean isResponse = t == 0xa0 || t == 0x20;
        boolean isNotification = t == 0xc0 || t == 0x40;
        if (!isResponse && !isNotification) return;
        log("recv op=0x" + Integer.toHexString(pkt.opcode) + " type=0x" + Integer.toHexString(pkt.type) + " len=" + pkt.payload.length);
        byte[] p = pkt.payload;
        switch (pkt.opcode) {
            case BudsProtocol.GET_BATTERY_LEVEL:
            case BudsProtocol.BATTERY_LEVEL_CHANGED:
                if (p.length >= 3) {
                    BudsProtocol.Battery l = BudsProtocol.parseBattery(p[0]);
                    BudsProtocol.Battery r = BudsProtocol.parseBattery(p[1]);
                    BudsProtocol.Battery c = BudsProtocol.parseBattery(p[2]);
                    postBattery(l, r, c);
                }
                break;
            case BudsProtocol.GET_ANC_MODE:
            case BudsProtocol.ANC_MODE_CHANGED:
                if (p.length >= 2) {
                    postAnc(BudsProtocol.decodeAnc(p[0] & 0xff, p[1] & 0xff));
                } else if (p.length == 1) {
                    postAnc(p[0] & 0xff);
                }
                break;
            case BudsProtocol.GET_TOGGLE_CONFIGS:
                if (p.length >= 1) {
                    int count = p[0] & 0xff;
                    for (int i = 1, n = 0; n < count && i + 2 < p.length; i += 3, n++) {
                        int cat = p[i] & 0xff;
                        if (cat == BudsProtocol.TOGGLE_CATEGORY_ANC_PREFERENCE) postAnc(BudsProtocol.decodeAnc(p[i + 1] & 0xff, p[i + 2] & 0xff));
                    }
                }
                break;
            case BudsProtocol.GET_TOGGLE_CONFIG:
            case BudsProtocol.TOGGLE_CONFIG_CHANGED:
                if (p.length >= 3) {
                    int cat = p[0] & 0xff;
                    if (cat == BudsProtocol.TOGGLE_CATEGORY_ANC_PREFERENCE) postAnc(BudsProtocol.decodeAnc(p[1] & 0xff, p[2] & 0xff));
                }
                break;
        }
    }

    public void sendAncMode(int mode) {
        if (!running.get()) return;
        queueOrWrite(protocol.buildFrame(BudsProtocol.SET_ANC_MODE, BudsProtocol.ancModePayload(mode)));
        queueOrWrite(protocol.buildFrame(BudsProtocol.SET_TOGGLE_CONFIG, BudsProtocol.ancPrefPayload(mode)));
        if (mode == BudsProtocol.ANC_ADAPTIVE) {
            queueOrWrite(protocol.buildFrame(BudsProtocol.SET_ADAPTATION_STATUS, new byte[]{0x01}));
        }
    }

    public void queryBattery() {
        if (!running.get()) return;
        queueOrWrite(protocol.buildFrame(BudsProtocol.GET_BATTERY_LEVEL, new byte[0]));
    }

    public void queryAnc() {
        if (!running.get()) return;
        queueOrWrite(protocol.buildFrame(BudsProtocol.GET_ANC_MODE, new byte[0]));
    }

    public void queryToggleConfigs() {
        if (!running.get()) return;
        queueOrWrite(protocol.buildFrame(BudsProtocol.GET_TOGGLE_CONFIGS, new byte[0]));
    }

    private void queueOrWrite(byte[] data) {
        int innerOffset = 6;
        if (data.length >= innerOffset + 2) {
            int opcode = ((data[innerOffset] & 0xff) << 8) | (data[innerOffset + 1] & 0xff);
            log("send op=0x" + Integer.toHexString(opcode));
        }
        if (rfcommOut != null) writeQueue.offer(data);
    }

    private void startRefresh() {
        if (refreshThread != null && refreshThread.isAlive()) return;
        refreshThread = new Thread(() -> {
            sleep(200);
            if (!running.get()) return;
            querySequence();
            while (running.get()) {
                sleep(60000);
                if (running.get()) queryBattery();
            }
        }, "buds-refresh");
        refreshThread.start();
    }

    private void querySequence() {
        int[] ops = {
            BudsProtocol.GET_BATTERY_LEVEL,
            BudsProtocol.GET_ANC_MODE,
            BudsProtocol.GET_TOGGLE_CONFIGS,
        };
        for (int op : ops) {
            if (!running.get()) return;
            switch (op) {
                case BudsProtocol.GET_BATTERY_LEVEL: queryBattery(); break;
                case BudsProtocol.GET_ANC_MODE: queryAnc(); break;
                case BudsProtocol.GET_TOGGLE_CONFIGS: queryToggleConfigs(); break;
            }
            sleep(250);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void interrupt(Thread t) { if (t != null) t.interrupt(); }
    private void log(String s) { Log.d(TAG, s); }
    private void postConnected() { post(() -> { if (listener != null) listener.onConnected(); }); }
    private void postDisconnected() { post(() -> { if (listener != null) listener.onDisconnected(); }); }
    private void postAnc(int m) { post(() -> { if (listener != null) listener.onAncMode(m); }); }
    private void postBattery(BudsProtocol.Battery l, BudsProtocol.Battery r, BudsProtocol.Battery c) { post(() -> { if (listener != null) listener.onBattery(l, r, c); }); }
    private void post(Runnable r) { mainHandler.post(r); }
}
