package com.dopamide.motoanc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.ParcelUuid;

import java.util.Set;
import java.util.UUID;

public class DeviceFinder {
    public static String find(Context context) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return null;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return null;
            for (BluetoothDevice d : bonded) {
                String name = d.getName();
                if (name != null) {
                    String lower = name.toLowerCase();
                    if (lower.contains("moto buds") || lower.contains("motobuds")) {
                        return d.getAddress();
                    }
                }
                ParcelUuid[] uuids = d.getUuids();
                if (uuids != null) {
                    UUID spp = UUID.fromString(BudsProtocol.SERVICE_UUID);
                    for (ParcelUuid u : uuids) {
                        if (u != null && spp.equals(u.getUuid())) {
                            return d.getAddress();
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {}
        return null;
    }
}
