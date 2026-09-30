package com.arnavk09.motoanc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the RFCOMM link to the earbuds: connect/reconnect on a background
 * thread, pump reads and writes, and fan parsed events out to [Listener] on
 * the main thread.
 */
class BudsConnection(
    context: Context,
    private val listener: Listener?,
) {
    interface Listener {
        fun onConnected()

        fun onDisconnected()

        fun onBattery(
            left: BudsProtocol.Battery,
            right: BudsProtocol.Battery,
            caseBattery: BudsProtocol.Battery,
        )

        fun onAncMode(mode: Int)
    }

    private val context: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val protocol = BudsProtocol()
    private val writeQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    private val running = AtomicBoolean(false)
    private val shouldRun = AtomicBoolean(false)

    @Volatile
    private var targetAddress: String? = null

    private var connectThread: Thread? = null
    private var readThread: Thread? = null
    private var writeThread: Thread? = null
    private var refreshThread: Thread? = null

    private var rfcommSocket: BluetoothSocket? = null
    private var rfcommIn: InputStream? = null
    private var rfcommOut: OutputStream? = null

    fun start(address: String?) {
        stop()
        targetAddress = address
        shouldRun.set(true)
        connect()
    }

    fun stop() {
        shouldRun.set(false)
        running.set(false)
        closeRfcomm()
        interrupt(connectThread)
        interrupt(readThread)
        interrupt(writeThread)
        interrupt(refreshThread)
    }

    private fun connect() {
        if (connectThread?.isAlive == true) return
        connectThread = Thread({ connectLoop() }, "buds-connect")
        connectThread!!.start()
    }

    private fun connectLoop() {
        while (shouldRun.get()) {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null) {
                    log("bluetooth not available")
                    sleep(RECONNECT_DELAY_MS)
                    continue
                }
                if (!adapter.isEnabled()) {
                    log("bluetooth is off")
                    sleep(RECONNECT_DELAY_MS)
                    continue
                }

                var device: BluetoothDevice? = null
                targetAddress?.let { address ->
                    try {
                        device = adapter.getRemoteDevice(address)
                    } catch (e: Exception) {
                        log("bad address $address")
                    }
                }
                if (device == null) device = findBondedDevice(adapter)
                if (device == null) {
                    log("no Moto Buds found")
                    sleep(RECONNECT_DELAY_MS)
                    continue
                }

                val name = device.getName()
                log("trying " + (name ?: device.getAddress()))

                var connected = false
                try {
                    connected = connectRfcomm(device)
                } catch (e: Exception) {
                    log("rfcomm error " + e.message)
                }

                if (!connected) {
                    log("rfcomm failed")
                    sleep(RECONNECT_DELAY_MS)
                    continue
                }

                while (shouldRun.get() && running.get()) {
                    sleep(1000)
                }
            } catch (e: Exception) {
                log("conn " + e.message)
                Log.e(TAG, "connection error", e)
            } finally {
                running.set(false)
                closeRfcomm()
                postDisconnected()
            }
            if (!shouldRun.get()) break
            sleep(RECONNECT_DELAY_MS)
        }
    }

    private fun findBondedDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        try {
            for (d in adapter.getBondedDevices()) {
                val name = d.getName()
                if (name != null) {
                    val lower = name.lowercase()
                    if (lower.contains("moto buds") ||
                        lower.contains("motobuds") ||
                        lower.contains("moto buds+")
                    ) {
                        return d
                    }
                }
                val uuids = d.getUuids()
                if (uuids != null) {
                    for (u in uuids) {
                        if (u != null && (SERVICE_UUID == u.getUuid() || SERVICE_UUID_OLD == u.getUuid())) {
                            return d
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            log("scan perm " + e.message)
        }
        return null
    }

    private fun connectRfcomm(device: BluetoothDevice): Boolean {
        var socket: BluetoothSocket? = null

        log("rfcomm secure uuid")
        socket = tryRfcommUuid(device, false)
        if (socket == null) {
            log("rfcomm insecure uuid")
            socket = tryRfcommUuid(device, true)
        }
        if (socket == null) {
            for (channel in 1..30) {
                if (!shouldRun.get()) break
                log("rfcomm channel $channel")
                socket = tryRfcommChannel(device, channel, false)
                if (socket == null) {
                    log("rfcomm insecure channel $channel")
                    socket = tryRfcommChannel(device, channel, true)
                }
                if (socket == null) sleep(100)
                if (socket != null) break
            }
        }
        if (socket == null) return false

        return try {
            rfcommSocket = socket
            rfcommIn = socket.getInputStream()
            rfcommOut = socket.getOutputStream()
            running.set(true)
            startIoThreads()
            postConnected()
            startRefresh()
            log("rfcomm connected")
            true
        } catch (e: Exception) {
            log("rfcomm streams failed " + e.message)
            closeRfcomm()
            false
        }
    }

    private fun tryRfcommUuid(
        device: BluetoothDevice,
        insecure: Boolean,
    ): BluetoothSocket? =
        try {
            val socket =
                if (insecure) {
                    val m =
                        device.javaClass.getMethod(
                            "createInsecureRfcommSocketToServiceRecord",
                            UUID::class.java,
                        )
                    m.invoke(device, SERVICE_UUID) as BluetoothSocket
                } else {
                    device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                }
            socket.connect()
            socket
        } catch (e: Exception) {
            log("uuid connect failed " + e.message)
            null
        }

    private fun tryRfcommChannel(
        device: BluetoothDevice,
        channel: Int,
        insecure: Boolean,
    ): BluetoothSocket? =
        try {
            val m =
                if (insecure) {
                    device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                } else {
                    device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                }
            val socket = m.invoke(device, channel) as BluetoothSocket
            socket.connect()
            socket
        } catch (e: Exception) {
            log("channel connect failed " + e.message)
            null
        }

    private fun startIoThreads() {
        readThread = Thread({ readLoop() }, "buds-read")
        readThread!!.start()
        writeThread = Thread({ writeLoop() }, "buds-write")
        writeThread!!.start()
    }

    private fun readLoop() {
        val buffer = ByteArray(1024)
        while (running.get()) {
            try {
                val input = rfcommIn ?: break
                val n = input.read(buffer)
                if (n < 0) {
                    log("rfcomm eof")
                    break
                }
                if (n > 0) {
                    val chunk = ByteArray(n)
                    System.arraycopy(buffer, 0, chunk, 0, n)
                    handleBytes(chunk)
                }
            } catch (e: Exception) {
                log("rfcomm read " + e.message)
                break
            }
        }
        running.set(false)
        closeRfcomm()
    }

    private fun writeLoop() {
        while (running.get()) {
            try {
                val frame = writeQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val out = rfcommOut ?: break
                out.write(frame)
                out.flush()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                log("rfcomm write " + e.message)
                break
            }
        }
    }

    private fun closeRfcomm() {
        running.set(false)
        try {
            rfcommSocket?.close()
        } catch (ignored: Exception) {
        }
        rfcommIn = null
        rfcommOut = null
        rfcommSocket = null
        writeQueue.clear()
    }

    private fun handleBytes(value: ByteArray) {
        if (value.isEmpty()) return
        val inners = protocol.unframe(value)
        if (inners.isEmpty()) {
            val pkt = BudsProtocol.decode(value)
            if (pkt != null) handle(pkt)
        } else {
            for (inner in inners) {
                val pkt = BudsProtocol.decode(inner)
                if (pkt != null) handle(pkt)
            }
        }
    }

    private fun handle(pkt: BudsProtocol.Packet) {
        val t = pkt.type and 0xe0
        val isResponse = t == 0xa0 || t == 0x20
        val isNotification = t == 0xc0 || t == 0x40
        if (!isResponse && !isNotification) return
        log(
            "recv op=0x" + Integer.toHexString(pkt.opcode) +
                " type=0x" + Integer.toHexString(pkt.type) +
                " len=" + pkt.payload.size,
        )
        val p = pkt.payload
        when (pkt.opcode) {
            BudsProtocol.GET_BATTERY_LEVEL,
            BudsProtocol.BATTERY_LEVEL_CHANGED,
            -> {
                if (p.size >= 3) {
                    val l = BudsProtocol.parseBattery(p[0])
                    val r = BudsProtocol.parseBattery(p[1])
                    val c = BudsProtocol.parseBattery(p[2])
                    postBattery(l, r, c)
                }
            }

            BudsProtocol.GET_ANC_MODE,
            BudsProtocol.ANC_MODE_CHANGED,
            -> {
                if (p.size >= 2) {
                    postAnc(BudsProtocol.decodeAnc(p[0].toInt() and 0xff, p[1].toInt() and 0xff))
                } else if (p.size == 1) {
                    postAnc(p[0].toInt() and 0xff)
                }
            }

            BudsProtocol.GET_TOGGLE_CONFIGS -> {
                if (p.size >= 1) {
                    val count = p[0].toInt() and 0xff
                    var i = 1
                    var n = 0
                    while (n < count && i + 2 < p.size) {
                        val cat = p[i].toInt() and 0xff
                        if (cat == BudsProtocol.TOGGLE_CATEGORY_ANC_PREFERENCE) {
                            postAnc(
                                BudsProtocol.decodeAnc(
                                    p[i + 1].toInt() and 0xff,
                                    p[i + 2].toInt() and 0xff,
                                ),
                            )
                        }
                        i += 3
                        n++
                    }
                }
            }

            BudsProtocol.GET_TOGGLE_CONFIG,
            BudsProtocol.TOGGLE_CONFIG_CHANGED,
            -> {
                if (p.size >= 3) {
                    val cat = p[0].toInt() and 0xff
                    if (cat == BudsProtocol.TOGGLE_CATEGORY_ANC_PREFERENCE) {
                        postAnc(
                            BudsProtocol.decodeAnc(p[1].toInt() and 0xff, p[2].toInt() and 0xff),
                        )
                    }
                }
            }
        }
    }

    fun sendAncMode(mode: Int) {
        if (!running.get()) return
        queueOrWrite(
            protocol.buildFrame(BudsProtocol.SET_ANC_MODE, BudsProtocol.ancModePayload(mode)),
        )
        queueOrWrite(
            protocol.buildFrame(BudsProtocol.SET_TOGGLE_CONFIG, BudsProtocol.ancPrefPayload(mode)),
        )
        if (mode == BudsProtocol.ANC_ADAPTIVE) {
            queueOrWrite(
                protocol.buildFrame(
                    BudsProtocol.SET_ADAPTATION_STATUS,
                    byteArrayOf(0x01),
                ),
            )
        }
    }

    fun queryBattery() {
        if (!running.get()) return
        queueOrWrite(protocol.buildFrame(BudsProtocol.GET_BATTERY_LEVEL, ByteArray(0)))
    }

    fun queryAnc() {
        if (!running.get()) return
        queueOrWrite(protocol.buildFrame(BudsProtocol.GET_ANC_MODE, ByteArray(0)))
    }

    fun queryToggleConfigs() {
        if (!running.get()) return
        queueOrWrite(protocol.buildFrame(BudsProtocol.GET_TOGGLE_CONFIGS, ByteArray(0)))
    }

    private fun queueOrWrite(data: ByteArray) {
        val innerOffset = 6
        if (data.size >= innerOffset + 2) {
            val opcode =
                ((data[innerOffset].toInt() and 0xff) shl 8) or
                    (data[innerOffset + 1].toInt() and 0xff)
            log("send op=0x" + Integer.toHexString(opcode))
        }
        if (rfcommOut != null) writeQueue.offer(data)
    }

    private fun startRefresh() {
        if (refreshThread?.isAlive == true) return
        refreshThread =
            Thread({
                sleep(200)
                if (!running.get()) return@Thread
                querySequence()
                while (running.get()) {
                    sleep(60000)
                    if (running.get()) queryBattery()
                }
            }, "buds-refresh")
        refreshThread!!.start()
    }

    private fun querySequence() {
        val ops =
            intArrayOf(
                BudsProtocol.GET_BATTERY_LEVEL,
                BudsProtocol.GET_ANC_MODE,
                BudsProtocol.GET_TOGGLE_CONFIGS,
            )
        for (op in ops) {
            if (!running.get()) return
            when (op) {
                BudsProtocol.GET_BATTERY_LEVEL -> queryBattery()
                BudsProtocol.GET_ANC_MODE -> queryAnc()
                BudsProtocol.GET_TOGGLE_CONFIGS -> queryToggleConfigs()
            }
            sleep(250)
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun interrupt(t: Thread?) {
        t?.interrupt()
    }

    private fun log(s: String) {
        Log.d(TAG, s)
    }

    private fun postConnected() {
        post { listener?.onConnected() }
    }

    private fun postDisconnected() {
        post { listener?.onDisconnected() }
    }

    private fun postAnc(m: Int) {
        post { listener?.onAncMode(m) }
    }

    private fun postBattery(
        l: BudsProtocol.Battery,
        r: BudsProtocol.Battery,
        c: BudsProtocol.Battery,
    ) {
        post { listener?.onBattery(l, r, c) }
    }

    private fun post(r: Runnable) {
        mainHandler.post(r)
    }

    companion object {
        private const val TAG = "MotoBuds"
        private val SERVICE_UUID: UUID = UUID.fromString(BudsProtocol.SERVICE_UUID)
        private val SERVICE_UUID_OLD: UUID = UUID.fromString("00009fe0-4899-11ee-be56-0242ac120002")
        private const val RECONNECT_DELAY_MS = 5000L
    }
}
