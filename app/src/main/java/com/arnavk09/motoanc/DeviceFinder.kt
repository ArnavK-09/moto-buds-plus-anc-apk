package com.arnavk09.motoanc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import java.util.UUID

object DeviceFinder {
    private val NAME_TOKENS =
        arrayOf(
            "moto buds",
            "motobuds",
            "moto bud",
            "motobud",
            "buds+",
            "buds +",
            "buds plus",
        )

    private val UUID_SERVICE = UUID.fromString(BudsProtocol.SERVICE_UUID)
    private val UUID_SERVICE_OLD = UUID.fromString(BudsProtocol.SERVICE_UUID_OLD)
    private val UUID_SPP = UUID.fromString(BudsProtocol.SPP_UUID)

    @JvmStatic
    fun find(context: Context): String? {
        for (device in list(context)) {
            if (matches(device)) return device.address
        }
        return null
    }

    @JvmStatic
    fun list(context: Context): List<BluetoothDevice> {
        val adapter = btAdapter(context) ?: return emptyList()
        return try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    @JvmStatic
    fun matches(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        if (isBudsName(safeName(device))) return true
        val uuids =
            try {
                device.uuids
            } catch (e: Throwable) {
                null
            } ?: return false
        for (parcel in uuids) {
            if (parcel == null) continue
            val id = parcel.uuid
            if (id == UUID_SERVICE || id == UUID_SERVICE_OLD || id == UUID_SPP) return true
        }
        return false
    }

    @JvmStatic
    fun isBudsName(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase()
        for (token in NAME_TOKENS) {
            if (lower.contains(token)) return true
        }
        return false
    }

    @JvmStatic
    fun safeName(device: BluetoothDevice): String? =
        try {
            device.name
        } catch (e: Throwable) {
            null
        }

    @JvmStatic
    fun safeAddress(device: BluetoothDevice): String? =
        try {
            device.address
        } catch (e: Throwable) {
            null
        }

    private fun btAdapter(context: Context): BluetoothAdapter? =
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (ignored: Throwable) {
            null
        } ?: BluetoothAdapter.getDefaultAdapter()
}
