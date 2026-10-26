package com.arnavk09.motoanc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

        fun onConfig(
            key: String,
            on: Boolean,
        )

        fun onGesture(
            side: Int,
            index: Int,
            action: Int,
        )

        fun onDualDevices(raw: ByteArray)

        fun onStatus(text: String)

        fun onBondLost()
    }

    private val context: Context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    @Volatile
    private var dialingSocket: BluetoothSocket? = null

    @Volatile
    private var gatt: BudsGatt? = null

    @Volatile
    private var preferGatt = false

    @Volatile
    private var lastReadAt = 0L

    @Volatile
    private var connectedAt = 0L

    private var authFailures = 0
    private var shortLived = 0

    @Volatile
    private var lastErrorWasAuth = false

    private var receiversRegistered = false

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                broadcastContext: Context?,
                intent: Intent?,
            ) {
                val received = intent ?: return
                when (received.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device = deviceExtra(received) ?: return
                        log("acl connected " + describe(device))
                        if (DeviceFinder.matches(device)) adopt(device)
                    }

                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val device = deviceExtra(received) ?: return
                        log("acl disconnected " + describe(device))
                    }

                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = deviceExtra(received) ?: return
                        val state =
                            received.getIntExtra(
                                BluetoothDevice.EXTRA_BOND_STATE,
                                BluetoothDevice.ERROR,
                            )
                        log("bond state " + stateName(state) + " " + describe(device))
                        if (state == BluetoothDevice.BOND_BONDED) {
                            authFailures = 0
                            shortLived = 0
                            if (DeviceFinder.matches(device)) adopt(device)
                        }
                    }

                    ACTION_KEY_MISSING -> {
                        val device = deviceExtra(received)
                        log("key missing (remote bond loss) " + describe(device))
                        postBondLost()
                    }
                }
            }
        }

    fun start(address: String?) {
        stop()
        targetAddress = address
        preferGatt = prefs.getBoolean(KEY_PREFER_GATT, false)
        shouldRun.set(true)
        registerReceivers()
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
        unregisterReceivers()
    }

    private fun connect() {
        if (connectThread?.isAlive == true) return
        connectThread = Thread({ connectLoop() }, "buds-connect")
        connectThread!!.start()
    }

    private fun btAdapter(): BluetoothAdapter? =
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (ignored: Throwable) {
            null
        } ?: BluetoothAdapter.getDefaultAdapter()

    private fun connectLoop() {
        var attempt = 0
        while (shouldRun.get()) {
            attempt++
            var established = false
            try {
                val adapter = btAdapter()
                if (adapter == null) {
                    log("bluetooth not available")
                    delayReconnect(attempt)
                    continue
                }
                if (!adapter.isEnabled) {
                    log("bluetooth is off")
                    postStatus(context.getString(R.string.status_bt_off))
                    sleep(RECONNECT_BASE_MS)
                    continue
                }

                val device = resolveDevice(adapter)
                if (device == null) {
                    log("no Moto Buds found")
                    postStatus(context.getString(R.string.status_no_device))
                    delayReconnect(attempt)
                    continue
                }

                log("trying " + describe(device))
                postStatus(context.getString(R.string.status_connecting))

                val connected = attemptConnect(device, attempt)

                if (!connected) {
                    log("connect failed")
                    if (lastErrorWasAuth) {
                        authFailures++
                        if (authFailures >= AUTH_FAILURE_HINT) postBondLost()
                    }
                    delayReconnect(attempt)
                    continue
                }

                established = true
                authFailures = 0
                adopt(device)

                while (shouldRun.get() && running.get()) {
                    sleep(1000)
                }
            } catch (e: Throwable) {
                log("connect loop " + e.message)
            }

            if (established) {
                running.set(false)
                closeRfcomm()
                postDisconnected()
            }
            if (!shouldRun.get()) break
            delayReconnect(attempt)
        }
    }

    private fun resolveDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        val explicit = targetAddress
        if (explicit != null) {
            try {
                log("target address $explicit")
                return adapter.getRemoteDevice(explicit)
            } catch (e: Throwable) {
                log("bad address $explicit " + e.message)
            }
        }

        var device = strictMatch(adapter)
        if (device != null) return device

        log("no bonded match; refreshing SDP")
        refreshSdp(adapter)
        device = strictMatch(adapter)
        if (device != null) return device

        val stored = storedAddress()
        if (stored != null && stored != explicit) {
            try {
                log("stored address $stored")
                return adapter.getRemoteDevice(stored)
            } catch (e: Throwable) {
                log("bad stored address " + e.message)
            }
        }

        return discoverByScan(adapter)
    }

    private fun strictMatch(adapter: BluetoothAdapter): BluetoothDevice? {
        val bonded =
            try {
                adapter.bondedDevices
            } catch (e: Throwable) {
                log("bonded list failed " + e.message)
                return null
            }
        log("bonded devices: " + bonded.size)
        var audioCandidate: BluetoothDevice? = null
        var audioCount = 0
        for (d in bonded) {
            val name = DeviceFinder.safeName(d)
            log("bonded " + (name ?: "?") + " " + d.address)
            if (DeviceFinder.isBudsName(name)) return d
            val uuids = tryRfcommUuids(d)
            if (uuids != null) {
                for (u in uuids) {
                    if (u != null &&
                        (
                            SERVICE_UUID == u.uuid ||
                                SERVICE_UUID_OLD == u.uuid ||
                                SPP_UUID == u.uuid
                        )
                    ) {
                        return d
                    }
                }
            } else {
                log("no cached uuids: " + (name ?: d.address))
            }
            if (isAudioDevice(d)) {
                audioCount++
                audioCandidate = d
            }
        }
        if (audioCount == 1) {
            log("single bonded audio device: " + describe(audioCandidate))
            return audioCandidate
        }
        return null
    }

    private fun isAudioDevice(device: BluetoothDevice): Boolean =
        try {
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        } catch (e: Throwable) {
            false
        }

    private fun tryRfcommUuids(device: BluetoothDevice): List<android.os.ParcelUuid>? =
        try {
            device.uuids?.toList()
        } catch (e: Throwable) {
            null
        }

    private fun refreshSdp(adapter: BluetoothAdapter) {
        val bonded =
            try {
                adapter.bondedDevices
            } catch (e: Throwable) {
                log("bonded refresh " + e.message)
                return
            }
        var fetched = 0
        for (d in bonded) {
            if (fetched >= SDP_FETCH_LIMIT) break
            try {
                if (d.fetchUuidsWithSdp()) fetched++
            } catch (e: Throwable) {
                log("sdp fetch " + e.message)
            }
        }
        log("sdp refresh requested for $fetched devices")
        if (fetched > 0) sleep(500)
    }

    private fun discoverByScan(adapter: BluetoothAdapter): BluetoothDevice? {
        val found = AtomicReference<BluetoothDevice>()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    broadcastContext: Context?,
                    intent: Intent?,
                ) {
                    val received = intent ?: return
                    if (received.action != BluetoothDevice.ACTION_FOUND) return
                    val device = deviceExtra(received) ?: return
                    val name = DeviceFinder.safeName(device)
                    log("discovered " + (name ?: device.address))
                    if (DeviceFinder.isBudsName(name) || DeviceFinder.matches(device)) {
                        found.compareAndSet(null, device)
                    }
                }
            }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Throwable) {
            log("scan receiver failed " + e.message)
            return null
        }

        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            if (!adapter.startDiscovery()) {
                log("discovery not started")
                return null
            }
            postStatus(context.getString(R.string.status_searching))
            log("scanning for buds")
            var waited = 0L
            while (shouldRun.get() && waited < SCAN_TIMEOUT_MS && found.get() == null) {
                sleep(250)
                waited += 250
            }
            val result = found.get()
            if (result == null) log("scan found no buds") else log("scan matched " + describe(result))
            return result
        } catch (e: Throwable) {
            log("scan failed " + e.message)
            return null
        } finally {
            try {
                adapter.cancelDiscovery()
            } catch (ignored: Throwable) {
            }
            try {
                context.unregisterReceiver(receiver)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun attemptConnect(
        device: BluetoothDevice,
        attempt: Int,
    ): Boolean {
        lastErrorWasAuth = false
        if (preferGatt) {
            if (tryGatt(device)) return true
            return tryRfcomm(device)
        }
        if (tryRfcomm(device)) return true
        if (attempt > 1 && attempt % BLE_RETRY_EVERY != 0) return false
        log("rfcomm busy; trying BLE")
        val viaGatt = tryGatt(device)
        if (viaGatt) {
            preferGatt = true
            prefs.edit().putBoolean(KEY_PREFER_GATT, true).apply()
            log("will use BLE for future connects")
        }
        return viaGatt
    }

    private fun tryRfcomm(device: BluetoothDevice): Boolean =
        try {
            connectRfcomm(device)
        } catch (e: Throwable) {
            log("rfcomm error " + e.message)
            false
        }

    private fun tryGatt(device: BluetoothDevice): Boolean =
        try {
            connectGatt(device)
        } catch (e: Throwable) {
            log("gatt error " + e.message)
            false
        }

    private fun connectGatt(device: BluetoothDevice): Boolean {
        log("gatt connect start " + describe(device))
        var transport: BudsGatt? = null
        transport = BudsGatt(context, { bytes -> onGattPacket(bytes) }, { onGattClosed(transport) })
        gatt = transport
        val ok = transport.connect(device, GATT_TIMEOUT_MS)
        if (!ok || !shouldRun.get()) {
            gatt = null
            transport.close()
            log("gatt connect failed")
            return false
        }
        connectedAt = System.currentTimeMillis()
        lastReadAt = connectedAt
        running.set(true)
        postConnected()
        startRefresh()
        log("gatt connected")
        return true
    }

    private fun onGattPacket(bytes: ByteArray) {
        if (!running.get()) return
        lastReadAt = System.currentTimeMillis()
        val pkt = BudsProtocol.decode(bytes)
        if (pkt != null) handle(pkt) else handleBytes(bytes)
    }

    private fun onGattClosed(transport: BudsGatt?) {
        if (gatt !== transport) return
        if (running.get()) {
            log("gatt link closed")
            running.set(false)
        }
    }

    private fun connectRfcomm(device: BluetoothDevice): Boolean {
        var socket: BluetoothSocket? = null
        try {
            btAdapter()?.cancelDiscovery()
        } catch (ignored: Throwable) {
        }

        log("connect start " + describe(device) + " bond=" + stateName(bondStateOf(device)))
        refreshSdpFor(device)

        val deadline = System.currentTimeMillis() + CONNECT_BUDGET_MS

        for (uuid in UUID_CANDIDATES) {
            if (!shouldRun.get() || System.currentTimeMillis() > deadline) break
            socket = tryRfcommUuid(device, uuid, true)
            if (socket == null) socket = tryRfcommUuid(device, uuid, false)
            if (socket != null) {
                log("rfcomm via uuid $uuid")
                break
            }
        }

        if (socket == null) {
            for (channel in PREFERRED_CHANNELS) {
                if (!shouldRun.get() || System.currentTimeMillis() > deadline) break
                socket = tryRfcommChannel(device, channel, true)
                if (socket == null) socket = tryRfcommChannel(device, channel, false)
                if (socket != null) {
                    log("rfcomm via preferred channel $channel")
                    break
                }
            }
        }

        if (socket == null) {
            for (channel in 1..CHANNEL_MAX) {
                if (channel in PREFERRED_CHANNELS) continue
                if (!shouldRun.get() || System.currentTimeMillis() > deadline) break
                socket = tryRfcommChannel(device, channel, true)
                if (socket == null) socket = tryRfcommChannel(device, channel, false)
                if (socket != null) {
                    log("rfcomm via channel $channel")
                    break
                }
                sleep(50)
            }
        }

        if (socket == null) {
            log("connect exhausted candidates")
            return false
        }

        return try {
            rfcommSocket = socket
            rfcommIn = socket.inputStream
            rfcommOut = socket.outputStream
            connectedAt = System.currentTimeMillis()
            lastReadAt = connectedAt
            running.set(true)
            startIoThreads()
            postConnected()
            startRefresh()
            log("rfcomm connected")
            true
        } catch (e: Throwable) {
            log("rfcomm streams failed " + e.message)
            closeRfcomm()
            false
        }
    }

    private fun tryRfcommUuid(
        device: BluetoothDevice,
        uuid: UUID,
        insecure: Boolean,
    ): BluetoothSocket? =
        try {
            val socket =
                if (insecure) {
                    device.createInsecureRfcommSocketToServiceRecord(uuid)
                } else {
                    device.createRfcommSocketToServiceRecord(uuid)
                }
            log("uuid dial $uuid insecure=$insecure")
            dialingSocket = socket
            val ok = connectWithTimeout(socket, CONNECT_TIMEOUT_MS)
            dialingSocket = null
            if (ok) socket else null
        } catch (e: Throwable) {
            dialingSocket = null
            if (looksLikeAuthFailure(e)) lastErrorWasAuth = true
            log("uuid failed $uuid insecure=$insecure " + e.message)
            null
        }

    private fun tryRfcommChannel(
        device: BluetoothDevice,
        channel: Int,
        insecure: Boolean,
    ): BluetoothSocket? =
        try {
            val method =
                if (insecure) {
                    device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                } else {
                    device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                }
            val socket = method.invoke(device, channel) as BluetoothSocket
            log("channel dial $channel insecure=$insecure")
            dialingSocket = socket
            val ok = connectWithTimeout(socket, CHANNEL_TIMEOUT_MS)
            dialingSocket = null
            if (ok) socket else null
        } catch (e: Throwable) {
            dialingSocket = null
            if (looksLikeAuthFailure(e)) lastErrorWasAuth = true
            log("channel failed $channel insecure=$insecure " + e.message)
            null
        }

    private fun connectWithTimeout(
        socket: BluetoothSocket,
        timeoutMs: Long,
    ): Boolean {
        val done = AtomicBoolean(false)
        val closer =
            Thread({
                try {
                    Thread.sleep(timeoutMs)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (done.compareAndSet(false, true)) {
                    lastErrorWasAuth = true
                    log("connect timeout ${timeoutMs}ms, closing socket")
                    try {
                        socket.close()
                    } catch (ignored: Throwable) {
                    }
                }
            }, "buds-dial-timeout")
        closer.isDaemon = true
        closer.start()
        return try {
            socket.connect()
            log("connect returned")
            true
        } catch (e: Throwable) {
            if (looksLikeAuthFailure(e)) lastErrorWasAuth = true
            log("connect error: " + e.message)
            false
        } finally {
            done.set(true)
            closer.interrupt()
        }
    }

    private fun looksLikeAuthFailure(e: Throwable): Boolean {
        val message = (e.message ?: "").lowercase()
        return message.contains("auth") ||
            message.contains("encrypt") ||
            message.contains("key") ||
            message.contains("bond") ||
            message.contains("read failed") ||
            message.contains("socket might closed") ||
            message.contains("page timeout")
    }

    private fun startIoThreads() {
        readThread = Thread({ readLoop() }, "buds-read")
        readThread!!.start()
        writeThread = Thread({ writeLoop() }, "buds-write")
        writeThread!!.start()
    }

    private fun readLoop() {
        val buffer = ByteArray(2048)
        while (running.get()) {
            try {
                val input = rfcommIn ?: break
                val n = input.read(buffer)
                if (n < 0) {
                    log("rfcomm eof (read returned -1)")
                    break
                }
                if (n > 0) {
                    lastReadAt = System.currentTimeMillis()
                    val chunk = ByteArray(n)
                    System.arraycopy(buffer, 0, chunk, 0, n)
                    handleBytes(chunk)
                }
            } catch (e: Throwable) {
                log("rfcomm read " + e.message)
                break
            }
        }
        val lived = System.currentTimeMillis() - connectedAt
        if (lived < SHORT_LIVED_MS) {
            shortLived++
            log("rfcomm dropped after ${lived}ms (short-lived #$shortLived)")
            if (shortLived >= SHORT_LIVED_HINT) postBondLost()
        } else {
            shortLived = 0
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
            } catch (e: Throwable) {
                log("rfcomm write " + e.message)
                break
            }
        }
    }

    private fun closeRfcomm() {
        running.set(false)
        try {
            rfcommSocket?.close()
        } catch (ignored: Throwable) {
        }
        try {
            dialingSocket?.close()
        } catch (ignored: Throwable) {
        }
        dialingSocket = null
        rfcommIn = null
        rfcommOut = null
        rfcommSocket = null
        writeQueue.clear()
        val g = gatt
        gatt = null
        try {
            g?.close()
        } catch (ignored: Throwable) {
        }
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
        val type = pkt.type and 0xe0
        val isResponse = type == 0xa0 || type == 0x20
        val isNotification = type == 0xc0 || type == 0x40
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
                val half = p.size / 2
                val entries = minOf(7, half / 2)
                if (p.size >= 4 && entries > 0) {
                    for (index in 0 until entries) {
                        val left = p[index * 2 + 1].toInt() and 0xff
                        val right = p[half + index * 2 + 1].toInt() and 0xff
                        postGesture(0, index, left)
                        postGesture(1, index, right)
                    }
                }
            }

            BudsProtocol.GET_TOGGLE_CONFIG,
            BudsProtocol.TOGGLE_CONFIG_CHANGED,
            -> {
                if (p.size >= 3) {
                    postGesture(p[0].toInt() and 0xff, p[1].toInt() and 0xff, p[2].toInt() and 0xff)
                }
            }

            BudsProtocol.GET_DUAL_CONNECTION_DEVICE,
            BudsProtocol.DUAL_CONNECTION_DEVICE_CHANGED,
            -> {
                log("dual devices raw: " + p.joinToString(" ") { "%02x".format(it) })
                mainHandler.post { listener?.onDualDevices(p) }
            }

            else -> {
                val feature = BudsConfig.forResponse(pkt.opcode)
                if (feature != null && p.isNotEmpty()) {
                    postConfig(feature.key, BudsConfig.stateOf(p))
                }
            }
        }
    }

    fun setConfig(
        key: String,
        on: Boolean,
    ) {
        val feature = BudsConfig.forKey(key) ?: return
        enqueue(feature.setOp, BudsConfig.payload(feature, on))
        queryConfigs()
    }

    fun queryConfigs() {
        for (f in BudsConfig.TOGGLES) {
            if (!running.get()) return
            enqueue(f.getOp, ByteArray(0))
        }
    }

    fun setGesture(
        side: Int,
        index: Int,
        action: Int,
    ) {
        if (side !in 0..1 || index !in 0..6) return
        enqueue(
            BudsProtocol.SET_TOGGLE_CONFIG,
            byteArrayOf(side.toByte(), index.toByte(), action.toByte()),
        )
    }

    fun setRingBuds(
        left: Boolean,
        right: Boolean,
    ) {
        if (!running.get()) return
        enqueue(
            BudsProtocol.SET_RING_BUDS,
            byteArrayOf(if (left) 1 else 0, if (right) 1 else 0),
        )
    }

    fun sendAncMode(mode: Int) {
        enqueue(BudsProtocol.SET_ANC_MODE, BudsProtocol.ancModePayload(mode))
        enqueue(BudsProtocol.SET_TOGGLE_CONFIG, BudsProtocol.ancPrefPayload(mode))
        if (mode == BudsProtocol.ANC_ADAPTIVE) {
            enqueue(BudsProtocol.SET_ADAPTATION_STATUS, byteArrayOf(0x01))
        }
    }

    fun queryBattery() {
        enqueue(BudsProtocol.GET_BATTERY_LEVEL, ByteArray(0))
    }

    fun queryAnc() {
        enqueue(BudsProtocol.GET_ANC_MODE, ByteArray(0))
    }

    fun queryDualDevices() {
        enqueue(BudsProtocol.GET_DUAL_CONNECTION_DEVICE, ByteArray(0))
    }

    fun queryToggleConfigs() {
        enqueue(BudsProtocol.GET_TOGGLE_CONFIGS, ByteArray(0))
    }

    private fun enqueue(
        opcode: Int,
        payload: ByteArray,
    ) {
        if (!running.get()) return
        log("send op=0x" + Integer.toHexString(opcode))
        val inner = protocol.buildInner(opcode, payload)
        val transport = gatt
        if (transport != null) {
            transport.write(inner)
        } else {
            writeQueue.offer(protocol.wrap(inner))
        }
    }

    private fun startRefresh() {
        if (refreshThread?.isAlive == true) return
        refreshThread =
            Thread({
                sleep(250)
                if (!running.get()) return@Thread
                querySequence()
                while (running.get()) {
                    sleep(60000)
                    if (!running.get()) break
                    if (System.currentTimeMillis() - lastReadAt > DEAD_LINK_MS) {
                        log("watchdog: link silent, forcing reconnect")
                        closeRfcomm()
                        break
                    }
                    queryBattery()
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
                BudsProtocol.GET_DUAL_CONNECTION_DEVICE -> queryDualDevices()
            }
            sleep(250)
        }
        for (f in BudsConfig.TOGGLES) {
            if (!running.get()) return
            enqueue(f.getOp, ByteArray(0))
            sleep(150)
        }
    }

    private fun adopt(device: BluetoothDevice) {
        try {
            targetAddress = device.address
            prefs.edit().putString(KEY_ADDRESS, device.address).apply()
            log("remembered address " + device.address)
        } catch (e: Throwable) {
            log("remember failed " + e.message)
        }
    }

    fun forgetBond() {
        val address = targetAddress ?: storedAddress()
        if (address == null) {
            log("forgetBond: no address")
            return
        }
        try {
            val adapter = btAdapter()
            if (adapter == null) {
                log("forgetBond: no adapter")
                return
            }
            val device = adapter.getRemoteDevice(address)
            val method = device.javaClass.getMethod("removeBond")
            val result = method.invoke(device)
            log("removeBond $address -> $result")
        } catch (e: Throwable) {
            log("removeBond failed " + e.message)
        }
    }

    private fun storedAddress(): String? = prefs.getString(KEY_ADDRESS, null)

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(ACTION_KEY_MISSING)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiversRegistered = true
            log("bluetooth receivers registered")
        } catch (e: Throwable) {
            log("register receivers failed " + e.message)
        }
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        receiversRegistered = false
        try {
            context.unregisterReceiver(receiver)
        } catch (ignored: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceExtra(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun describe(device: BluetoothDevice?): String {
        if (device == null) return "?"
        val name = DeviceFinder.safeName(device)
        val address = DeviceFinder.safeAddress(device)
        return (name ?: "?") + " " + (address ?: "?")
    }

    private fun stateName(state: Int): String =
        when (state) {
            BluetoothDevice.BOND_NONE -> "none"
            BluetoothDevice.BOND_BONDING -> "bonding"
            BluetoothDevice.BOND_BONDED -> "bonded"
            else -> "unknown($state)"
        }

    private fun bondStateOf(device: BluetoothDevice): Int =
        try {
            device.bondState
        } catch (e: Throwable) {
            BluetoothDevice.ERROR
        }

    private fun refreshSdpFor(device: BluetoothDevice) {
        val cached = tryRfcommUuids(device)
        if (cached != null && cached.any { it?.uuid == SERVICE_UUID }) {
            log("sdp cache has service uuid")
            return
        }
        try {
            val requested = device.fetchUuidsWithSdp()
            log("sdp refresh requested: $requested")
            if (!requested) return
            sleep(1200)
            val uuids = tryRfcommUuids(device)
            if (uuids == null) {
                log("sdp refresh: still no uuids")
            } else {
                for (u in uuids) log("sdp uuid " + u.uuid)
            }
        } catch (e: Throwable) {
            log("sdp refresh failed " + e.message)
        }
    }

    private fun delayReconnect(attempt: Int) {
        val backoff = (RECONNECT_BASE_MS * attempt).coerceIn(RECONNECT_BASE_MS, RECONNECT_MAX_MS)
        sleep(backoff)
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
        BudsLog.d(s)
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

    private fun postConfig(
        key: String,
        on: Boolean,
    ) {
        post { listener?.onConfig(key, on) }
    }

    private fun postGesture(
        side: Int,
        index: Int,
        action: Int,
    ) {
        post { listener?.onGesture(side, index, action) }
    }

    private fun postBattery(
        l: BudsProtocol.Battery,
        r: BudsProtocol.Battery,
        c: BudsProtocol.Battery,
    ) {
        post { listener?.onBattery(l, r, c) }
    }

    private fun postStatus(text: String) {
        post { listener?.onStatus(text) }
    }

    private fun postBondLost() {
        post { listener?.onBondLost() }
    }

    private fun post(r: Runnable) {
        mainHandler.post(r)
    }

    companion object {
        private const val PREFS = "buds"
        private const val KEY_ADDRESS = "device_address"
        private const val KEY_PREFER_GATT = "prefer_gatt"
        private const val ACTION_KEY_MISSING = "android.bluetooth.device.action.KEY_MISSING"

        private val SERVICE_UUID: UUID = UUID.fromString(BudsProtocol.SERVICE_UUID)
        private val SERVICE_UUID_OLD: UUID = UUID.fromString(BudsProtocol.SERVICE_UUID_OLD)
        private val SERVICE_UUID_ALT: UUID = UUID.fromString("18290100-4899-11ee-be56-0242ac120002")
        private val SERVICE_UUID_ALT2: UUID = UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")
        private val SPP_UUID: UUID = UUID.fromString(BudsProtocol.SPP_UUID)
        private val UUID_CANDIDATES =
            arrayOf(
                SERVICE_UUID,
                SERVICE_UUID_ALT,
                SERVICE_UUID_OLD,
                SERVICE_UUID_ALT2,
                SPP_UUID,
            )
        private val PREFERRED_CHANNELS = intArrayOf(16)

        private const val SDP_FETCH_LIMIT = 6
        private const val CHANNEL_MAX = 30
        private const val RECONNECT_BASE_MS = 2000L
        private const val RECONNECT_MAX_MS = 15000L
        private const val SCAN_TIMEOUT_MS = 12000L
        private const val CONNECT_TIMEOUT_MS = 4000L
        private const val CHANNEL_TIMEOUT_MS = 3000L
        private const val CONNECT_BUDGET_MS = 6000L
        private const val GATT_TIMEOUT_MS = 25000L
        private const val BLE_RETRY_EVERY = 5
        private const val SHORT_LIVED_MS = 3000L
        private const val SHORT_LIVED_HINT = 3
        private const val AUTH_FAILURE_HINT = 3
        private const val DEAD_LINK_MS = 150000L
    }
}
