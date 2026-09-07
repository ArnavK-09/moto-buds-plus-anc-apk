package com.dopamide.motoanc;

import java.util.ArrayList;
import java.util.List;

public class BudsProtocol {
    public static final String SERVICE_UUID = "fc9d9fe0-4899-11ee-be56-0242ac120002";

    private static final byte[] HEAD = {0x48, 0x45, 0x41, 0x44};
    private static final byte[] TAIL = {0x54, 0x41, 0x49, 0x4c};
    private static final int COMMAND_TYPE = 0x80;

    public static final int GET_BATTERY_LEVEL = 0x005;
    public static final int BATTERY_LEVEL_CHANGED = 0x009;
    public static final int GET_TOGGLE_CONFIGS = 0x100;
    public static final int GET_TOGGLE_CONFIG = 0x101;
    public static final int SET_TOGGLE_CONFIG = 0x102;
    public static final int TOGGLE_CONFIG_CHANGED = 0x105;
    public static final int GET_ANC_MODE = 0x200;
    public static final int SET_ANC_MODE = 0x201;
    public static final int SET_ADAPTATION_STATUS = 0x203;
    public static final int ANC_MODE_CHANGED = 0x204;
    public static final int GET_DUAL_CONNECTION = 0x406;
    public static final int SET_DUAL_CONNECTION = 0x407;
    public static final int DUAL_CONNECTION_CHANGED = 0x40f;

    public static final int TOGGLE_CATEGORY_ANC_PREFERENCE = 0x01;
    public static final int TOGGLE_CATEGORY_DUAL_CONNECTION = 0x08;

    public static final int ANC_OFF = 0;
    public static final int ANC_TRANSPARENCY = 1;
    public static final int ANC_ANC = 2;
    public static final int ANC_ADAPTIVE = 3;

    private static final byte[][] ANC_MODE_BYTES = {
        {0x00, 0x00},
        {0x02, 0x00},
        {0x01, 0x03},
        {0x01, 0x01}
    };

    private static final byte[][] ANC_PREF_BYTES = {
        {0x01, 0x00, 0x00},
        {0x01, 0x02, 0x00},
        {0x01, 0x01, 0x03},
        {0x01, 0x01, 0x01}
    };

    private int seq = 1;
    private List<Byte> reassembly = new ArrayList<>();

    public synchronized byte[] buildFrame(int opcode, byte[] payload) {
        int s = seq;
        seq = (seq + 1) & 0xffff;
        if (seq == 0) seq = 1;
        int len = payload.length;
        byte[] inner = new byte[8 + len];
        inner[0] = (byte) ((opcode >> 8) & 0xff);
        inner[1] = (byte) (opcode & 0xff);
        inner[2] = (byte) COMMAND_TYPE;
        inner[3] = 0x00;
        inner[4] = (byte) (len & 0xff);
        inner[5] = (byte) ((len >> 8) & 0xff);
        inner[6] = (byte) (s & 0xff);
        inner[7] = (byte) ((s >> 8) & 0xff);
        System.arraycopy(payload, 0, inner, 8, len);
        return frame(inner);
    }

    private static byte[] frame(byte[] inner) {
        int n = inner.length;
        byte[] out = new byte[4 + 2 + n + 4 + 4];
        int o = 0;
        for (byte b : HEAD) out[o++] = b;
        out[o++] = (byte) (n & 0xff);
        out[o++] = (byte) ((n >> 8) & 0xff);
        System.arraycopy(inner, 0, out, o, n);
        o += n;
        int crc = crc32(inner);
        out[o++] = (byte) (crc & 0xff);
        out[o++] = (byte) ((crc >> 8) & 0xff);
        out[o++] = (byte) ((crc >> 16) & 0xff);
        out[o++] = (byte) ((crc >> 24) & 0xff);
        for (byte b : TAIL) out[o++] = b;
        return out;
    }

    private static int crc32(byte[] data) {
        int crc = 0xffffffff;
        for (byte b : data) {
            crc ^= (b & 0xff);
            for (int i = 0; i < 8; i++) {
                crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0xedb88320 : 0);
            }
        }
        return (crc ^ 0xffffffff) >>> 0;
    }

    public synchronized List<byte[]> unframe(byte[] chunk) {
        List<Byte> buf = new ArrayList<>(reassembly);
        reassembly.clear();
        for (byte b : chunk) buf.add(b);
        List<byte[]> out = new ArrayList<>();
        while (buf.size() >= 14) {
            int headIdx = -1;
            for (int i = 0; i <= buf.size() - 4; i++) {
                if (buf.get(i) == HEAD[0] && buf.get(i + 1) == HEAD[1] && buf.get(i + 2) == HEAD[2] && buf.get(i + 3) == HEAD[3]) {
                    headIdx = i;
                    break;
                }
            }
            if (headIdx == -1) break;
            if (buf.size() - headIdx < 14) {
                reassembly = new ArrayList<>(buf.subList(headIdx, buf.size()));
                break;
            }
            int outerLen = (buf.get(headIdx + 4) & 0xff) | ((buf.get(headIdx + 5) & 0xff) << 8);
            int end = headIdx + 4 + 2 + outerLen + 4 + 4;
            if (buf.size() < end) {
                reassembly = new ArrayList<>(buf.subList(headIdx, buf.size()));
                break;
            }
            boolean tailOk = buf.get(end - 4) == TAIL[0] && buf.get(end - 3) == TAIL[1] && buf.get(end - 2) == TAIL[2] && buf.get(end - 1) == TAIL[3];
            if (!tailOk) {
                buf = new ArrayList<>(buf.subList(headIdx + 1, buf.size()));
                continue;
            }
            byte[] inner = new byte[outerLen];
            for (int i = 0; i < outerLen; i++) inner[i] = buf.get(headIdx + 6 + i);
            out.add(inner);
            buf = new ArrayList<>(buf.subList(end, buf.size()));
        }
        if (buf.size() > 0 && buf.size() < 1000) reassembly = new ArrayList<>(buf);
        else reassembly.clear();
        return out;
    }

    public static Packet decode(byte[] inner) {
        if (inner.length < 8) return null;
        int opcode = ((inner[0] & 0xff) << 8) | (inner[1] & 0xff);
        int type = inner[2] & 0xff;
        int len = (inner[4] & 0xff) | ((inner[5] & 0xff) << 8);
        if (inner.length < 8 + len) return null;
        byte[] payload = new byte[len];
        System.arraycopy(inner, 8, payload, 0, len);
        return new Packet(opcode, type, payload);
    }

    public static int decodeAnc(int cat, int sub) {
        if (cat == 0x00 && sub == 0x00) return ANC_OFF;
        if (cat == 0x02 && sub == 0x00) return ANC_TRANSPARENCY;
        if (cat == 0x01 && sub == 0x03) return ANC_ANC;
        if (cat == 0x01 && sub == 0x01) return ANC_ADAPTIVE;
        return ANC_OFF;
    }

    public static byte[] ancModePayload(int mode) {
        return ANC_MODE_BYTES[mode].clone();
    }

    public static byte[] ancPrefPayload(int mode) {
        return ANC_PREF_BYTES[mode].clone();
    }

    public static final class Packet {
        public final int opcode;
        public final int type;
        public final byte[] payload;

        public Packet(int opcode, int type, byte[] payload) {
            this.opcode = opcode;
            this.type = type;
            this.payload = payload;
        }
    }

    public static final class Battery {
        public final int level;
        public final boolean charging;
        public final boolean reported;

        public Battery(int level, boolean charging, boolean reported) {
            this.level = level;
            this.charging = charging;
            this.reported = reported;
        }
    }

    public static Battery parseBattery(byte b) {
        int v = b & 0xff;
        if (v == 0xff) return new Battery(0, false, false);
        return new Battery(v & 0x7f, (v & 0x80) != 0, true);
    }
}
