package com.arnavk09.motoanc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/**
 * Finds a bonded Moto Buds+ by name or by advertised RFCOMM service UUID.
 *
 * Runs on the UI thread, so it only consults cached state: no SDP refresh, no
 * sleeping. A refreshed-cache second pass lives in BudsConnection's connect
 * thread.
 */
object DeviceFinder {
    @JvmStatic
    fun find(context: Context): String? {
        val adapter = btAdapter(context) ?: return null
        try {
            val bonded: Set<BluetoothDevice> = adapter.getBondedDevices() ?: return null
            val spp = UUID.fromString(BudsProtocol.SERVICE_UUID)
            val old = UUID.fromString(BudsProtocol.SERVICE_UUID_OLD)
            for (d in bonded) {
                val name = d.getName()
                if (name != null) {
                    val lower = name.lowercase()
                    if (lower.contains("moto buds") || lower.contains("motobuds")) {
                        return d.getAddress()
                    }
                }
                val uuids: Array<ParcelUuid>? = d.getUuids()
                if (uuids != null) {
                    for (u in uuids) {
                        if (u != null && (spp == u.getUuid() || old == u.getUuid())) {
                            return d.getAddress()
                        }
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    /**
     * BluetoothAdapter.getDefaultAdapter() is the deprecated legacy entry point
     * and the first thing a new OS stop resolving; prefer BluetoothManager.
     */
    private fun btAdapter(context: Context): BluetoothAdapter? =
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (ignored: Throwable) {
            null
        } ?: BluetoothAdapter.getDefaultAdapter()
}
