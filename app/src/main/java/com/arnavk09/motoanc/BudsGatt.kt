package com.arnavk09.motoanc

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BudsGatt(
    context: Context,
    private val onPacket: (ByteArray) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val context: Context = context.applicationContext
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private val ready = AtomicBoolean(false)
    private val readyLatch = CountDownLatch(1)
    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    private var writerThread: Thread? = null

    @Volatile
    private var pendingWrite: CountDownLatch? = null

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt?,
                status: Int,
                newState: Int,
            ) {
                log("gatt state status=$status new=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        try {
                            g?.discoverServices()
                        } catch (e: Throwable) {
                            log("discoverServices failed " + e.message)
                            close()
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        ready.set(false)
                        readyLatch.countDown()
                        onClosed()
                    }
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt?,
                status: Int,
            ) {
                if (g == null || status != BluetoothGatt.GATT_SUCCESS) {
                    log("gatt discover status=$status")
                    close()
                    return
                }
                var write: BluetoothGattCharacteristic? = null
                var notify: BluetoothGattCharacteristic? = null
                for (service in g.services) {
                    log("gatt service " + service.uuid)
                    for (characteristic in service.characteristics) {
                        log(
                            "gatt char " + characteristic.uuid +
                                " props=0x" + Integer.toHexString(characteristic.properties),
                        )
                        if (isKnownWrite(characteristic.uuid)) {
                            write = characteristic
                        } else if (isKnownNotify(characteristic.uuid)) {
                            notify = characteristic
                        }
                        if (write == null && canWrite(characteristic)) write = characteristic
                        if (notify == null && canNotify(characteristic)) notify = characteristic
                    }
                }
                writeChar = write
                notifyChar = notify
                if (notify == null) {
                    log("no notify characteristic found")
                    close()
                    return
                }
                log("using notify " + notify.uuid + " write " + (write?.uuid ?: "none"))
                enableNotifications(g, notify)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int,
            ) {
                log("gatt descriptor write status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    close()
                    return
                }
                ready.set(true)
                readyLatch.countDown()
                startWriter()
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int,
            ) {
                pendingWrite?.countDown()
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                onPacket(value)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
            ) {
                val value = characteristicValue(characteristic) ?: return
                onPacket(value)
            }
        }

    fun connect(
        target: BluetoothDevice,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val scanned = scanForDevice(target, deadline)
        val device = scanned ?: target
        val autoConnect = scanned == null
        log("gatt: " + (if (autoConnect) "autoConnect" else "direct") + " to " + device.address)
        val result =
            try {
                device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: Throwable) {
                log("gatt connectGatt failed " + e.message)
                null
            }
        if (result == null) return false
        gatt = result
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1000L)
        val ok =
            try {
                readyLatch.await(remaining, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                false
            }
        if (!ok || !ready.get()) {
            log("gatt: not ready in time")
            close()
            return false
        }
        log("gatt ready")
        return true
    }

    fun write(data: ByteArray) {
        if (!ready.get()) return
        writeQueue.offer(data)
    }

    fun close() {
        ready.set(false)
        writerThread?.interrupt()
        writerThread = null
        writeQueue.clear()
        pendingWrite?.countDown()
        pendingWrite = null
        val g = gatt
        gatt = null
        try {
            g?.disconnect()
        } catch (ignored: Throwable) {
        }
        try {
            g?.close()
        } catch (ignored: Throwable) {
        }
        readyLatch.countDown()
    }

    private fun enableNotifications(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        try {
            g.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD)
            if (cccd == null) {
                log("no CCCD; assuming notifications enabled")
                ready.set(true)
                readyLatch.countDown()
                startWriter()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                writeDescriptorLegacy(g, cccd)
            }
        } catch (e: Throwable) {
            log("enableNotifications failed " + e.message)
            close()
        }
    }

    private fun startWriter() {
        if (writerThread?.isAlive == true) return
        writerThread =
            Thread({
                while (ready.get()) {
                    val data =
                        try {
                            writeQueue.poll(500, TimeUnit.MILLISECONDS)
                        } catch (e: InterruptedException) {
                            break
                        } ?: continue
                    val g = gatt ?: break
                    val characteristic = writeChar
                    if (characteristic == null) {
                        log("gatt write skipped: no write characteristic")
                        continue
                    }
                    val latch = CountDownLatch(1)
                    pendingWrite = latch
                    val queued: Boolean =
                        try {
                            writeToGatt(g, characteristic, data)
                        } catch (e: Throwable) {
                            log("gatt write failed " + e.message)
                            false
                        }
                    if (!queued) log("gatt write not queued")
                    try {
                        latch.await(2000, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        break
                    }
                    pendingWrite = null
                }
            }, "buds-gatt-write")
        writerThread!!.isDaemon = true
        writerThread!!.start()
    }

    private fun scanForDevice(
        target: BluetoothDevice,
        deadline: Long,
    ): BluetoothDevice? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            log("gatt: no LE scanner")
            return null
        }
        val found = AtomicReference<BluetoothDevice>()
        var seen = 0
        val scanCallback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult?,
                ) {
                    val device = result?.device ?: return
                    val name = result.scanRecord?.deviceName ?: safeName(device)
                    if (seen < 20) {
                        seen++
                        log("le seen " + (name ?: "?") + " " + device.address)
                    }
                    if (device.address.equals(target.address, true) ||
                        DeviceFinder.isBudsName(name) ||
                        isBudsServiceAdvertisement(result)
                    ) {
                        log("le scan match " + (name ?: "?") + " " + device.address)
                        found.compareAndSet(null, device)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    log("le scan failed code=$errorCode")
                }
            }
        try {
            val settings =
                ScanSettings
                    .Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
            scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
            log("gatt: scanning for buds")
        } catch (e: Throwable) {
            log("le scan start failed " + e.message)
            return null
        }
        val started = System.currentTimeMillis()
        val budget = (deadline - started).coerceIn(2000L, SCAN_MS)
        try {
            while (found.get() == null && System.currentTimeMillis() - started < budget) {
                try {
                    Thread.sleep(200)
                } catch (e: InterruptedException) {
                }
            }
        } finally {
            try {
                scanner.stopScan(scanCallback)
            } catch (ignored: Throwable) {
            }
        }
        val result = found.get()
        log("gatt: scan finished found=" + (result?.address ?: "none"))
        return result
    }

    private fun isBudsServiceAdvertisement(result: ScanResult): Boolean {
        val uuids = result.scanRecord?.serviceUuids ?: return false
        for (parcel in uuids) {
            val id = parcel?.uuid?.toString() ?: continue
            if (id.startsWith("fc9d") || id.startsWith("192d") || id.startsWith("1829")) return true
        }
        return false
    }

    private fun canWrite(characteristic: BluetoothGattCharacteristic): Boolean {
        val properties = characteristic.properties
        return (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
            (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }

    private fun canNotify(characteristic: BluetoothGattCharacteristic): Boolean {
        val properties = characteristic.properties
        return (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
            (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }

    private fun isKnownWrite(uuid: UUID): Boolean = uuid == UUID_WRITE

    private fun isKnownNotify(uuid: UUID): Boolean = uuid == UUID_NOTIFY

    private fun safeName(device: BluetoothDevice): String? =
        try {
            device.name
        } catch (e: Throwable) {
            null
        }

    private fun writeToGatt(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status =
                g.writeCharacteristic(
                    characteristic,
                    data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
            status == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            writeCharacteristicLegacy(g, characteristic, data)
        }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicLegacy(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ): Boolean {
        characteristic.value = data
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return g.writeCharacteristic(characteristic)
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorLegacy(
        g: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
    ): Boolean {
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return g.writeDescriptor(descriptor)
    }

    @Suppress("DEPRECATION")
    private fun characteristicValue(characteristic: BluetoothGattCharacteristic?): ByteArray? = characteristic?.value

    private fun log(s: String) {
        BudsLog.d(s)
    }

    companion object {
        private const val SCAN_MS = 8000L
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val UUID_WRITE: UUID = UUID.fromString("fc9d0002-4899-11ee-be56-0242ac120002")
        private val UUID_NOTIFY: UUID = UUID.fromString("fc9d0003-4899-11ee-be56-0242ac120002")
    }
}
