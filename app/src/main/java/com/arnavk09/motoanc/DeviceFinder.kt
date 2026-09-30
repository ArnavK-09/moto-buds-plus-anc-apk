package com.arnavk09.motoanc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.ParcelUuid

/** Finds a bonded Moto Buds+ by name or by advertised RFCOMM service UUID. */
object DeviceFinder {
    @JvmStatic
    fun find(context: Context): String? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        try {
            val bonded: Set<BluetoothDevice> = adapter.getBondedDevices() ?: return null
            for (d in bonded) {
                val name = d.getName()
                if (name != null) {
                    val lower = name.lowercase()
                    if (lower.contains("moto buds") || lower.contains("motobuds")) {
                        return d.getAddress()
                    }
                }
                val uuids = d.getUuids()
                if (uuids != null) {
                    val spp = java.util.UUID.fromString(BudsProtocol.SERVICE_UUID)
                    for (u in uuids) {
                        if (u != null && spp == u.getUuid()) {
                            return d.getAddress()
                        }
                    }
                }
            }
        } catch (ignored: SecurityException) {
        }
        return null
    }
}
