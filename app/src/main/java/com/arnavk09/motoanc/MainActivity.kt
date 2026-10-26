package com.arnavk09.motoanc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity :
    Activity(),
    BudsConnection.Listener {
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var syncSpinner: ProgressBar
    private lateinit var batteryLeftView: BatteryView
    private lateinit var batteryRightView: BatteryView
    private lateinit var batteryCaseView: BatteryView
    private lateinit var batteryRow: LinearLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var detailScreen: View

    private val ancCards = arrayOfNulls<LinearLayout>(4)
    private val ancIcons = arrayOfNulls<ImageView>(4)
    private var dualDevicesLabel: TextView? = null
    private var dualDevicesText: TextView? = null
    private val ancLabels = arrayOfNulls<TextView>(4)
    private val configState = HashMap<String, Boolean>()
    private val configKnown = HashSet<String>()
    private val configSwitches = HashMap<String, Switch>()
    private val configStatusLabels = HashMap<String, TextView>()
    private val configStatusSpinners = HashMap<String, ProgressBar>()
    private val gestureActions = HashMap<String, Int>()
    private val gestureActionViews = HashMap<String, TextView>()
    private val ringSwitches = HashMap<Int, Switch>()
    private var updatingConfig = false

    private lateinit var prefs: SharedPreferences
    private var currentAnc = BudsProtocol.ANC_OFF
    private var isConnected = false
    private var isSynced = false
    private var requestedEnable = false
    private var case100Count = 0
    private var bondLost = false
    private var activeSettingsPage: String? = null
    private var gestureSide = 0
    private var pullStartY = 0f
    private var pollCount = 0
    private val configPollHandler = Handler(Looper.getMainLooper())
    private val ringHandler = Handler(Looper.getMainLooper())
    private val ringTimeout = Runnable { stopRingBuds() }
    private var ringLeft = false
    private var ringRight = false
    private val configPoll =
        object : Runnable {
            override fun run() {
                if (isConnected) {
                    val manager = BudsManager.get(this@MainActivity)
                    manager.queryAnc()
                    pollCount++
                    if (pollCount % CONFIG_GROUP_POLL_EVERY == 0) {
                        manager.queryConfigs()
                        manager.queryGestures()
                    }
                }
                configPollHandler.postDelayed(this, CONFIG_POLL_MS)
            }
        }
    private val btStateReceiver: BroadcastReceiver = BtStateReceiver(this)

    private class BtStateReceiver(
        private val activity: MainActivity,
    ) : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent.action) {
                val state =
                    intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR,
                    )
                if (state == BluetoothAdapter.STATE_ON && activity.hasPermission()) {
                    activity.requestedEnable = false
                    activity.startConnection()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(R.color.background)
        prefs = getSharedPreferences("buds", Context.MODE_PRIVATE)
        currentAnc = prefs.getInt("anc_mode", BudsProtocol.ANC_OFF)
        loadGestureCache()
        contentHost = FrameLayout(this)
        contentHost.setBackgroundColor(color(R.color.background))
        detailScreen = createDetailScreen()
        contentHost.addView(detailScreen)
        setContentView(contentHost)
        loadBattery()
        updateAnc()
        checkPermission()
    }

    private fun createDetailScreen(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(color(R.color.background))
        root.setPadding(20.dp(), 12.dp(), 20.dp(), 24.dp())
        root.fitsSystemWindows = true
        root.addView(createToolbar())
        root.addView(createStatusRow())
        val scroll = ScrollView(this)
        scroll.clipToPadding = false
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.addView(createHero())
        batteryRow = createBatteryRow()
        batteryRow.visibility = if (isConnected) View.VISIBLE else View.GONE
        body.addView(batteryRow)
        body.addView(createAncGrid())
        body.addView(createConfigSection())
        scroll.addView(body)
        scroll.isFillViewport = true
        scroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pullStartY = event.y
                    false
                }

                MotionEvent.ACTION_UP -> {
                    val pulled = event.y - pullStartY
                    if (scroll.scrollY == 0 && pulled > 84.dp()) {
                        reload()
                        true
                    } else {
                        false
                    }
                }

                else -> {
                    false
                }
            }
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    override fun onResume() {
        super.onResume()
        BudsLog.d("activity resume")
        registerBtReceiver()
        BudsManager.get(this).addListener(this)
        if (hasPermission()) startConnection()
        configPollHandler.removeCallbacks(configPoll)
        configPollHandler.postDelayed(configPoll, CONFIG_POLL_MS)
    }

    /**
     * System-broadcast receivers are exempt from the exported-flag requirement,
     * but newer OS builds grow stricter about it; declaring NOT_EXPORTED
     * explicitly is valid for protected broadcasts and survives the tightening.
     */
    private fun registerBtReceiver() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(btStateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        BudsLog.d("activity pause")
        configPollHandler.removeCallbacks(configPoll)
        stopRingBuds()
        try {
            unregisterReceiver(btStateReceiver)
        } catch (ignored: Exception) {
        }
        BudsManager.get(this).stop(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        BudsManager.get(this).stop(this)
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission()) {
                requestPermissions(BT_PERMISSIONS, PERMISSION_REQUEST)
            } else {
                startConnection()
            }
        } else {
            startConnection()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            var allGranted = true
            for (r in grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) allGranted = false
            }
            if (allGranted) startConnection() else updateStatus(false)
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            requestedEnable = false
            startConnection()
        }
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (p in BT_PERMISSIONS) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false
            }
        }
        return true
    }

    private fun startConnection() {
        val adapter = btAdapter()
        if (adapter == null) {
            updateStatus(false)
            statusText.setText(R.string.status_no_device)
            return
        }
        if (!adapter.isEnabled()) {
            updateStatus(false)
            if (!requestedEnable) {
                requestedEnable = true
                try {
                    startActivityForResult(
                        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQUEST_ENABLE_BT,
                    )
                } catch (e: Exception) {
                    requestedEnable = false
                }
            }
            return
        }
        BudsManager.get(this).start(this, DeviceFinder.find(this))
    }

    override fun onConnected() {
        isConnected = true
        isSynced = false
        bondLost = false
        updateStatus(true)
        runOnUiThread {
            batteryRow.visibility = View.VISIBLE
            updateBattery(loadBattery("left"), loadBattery("right"), loadBattery("case"))
        }
        runOnUiThread { updateConfigAvailability() }
    }

    override fun onDisconnected() {
        isConnected = false
        isSynced = false
        ringLeft = false
        ringRight = false
        ringHandler.removeCallbacks(ringTimeout)
        updateStatus(false)
        runOnUiThread {
            batteryRow.visibility = View.GONE
            updateConfigAvailability()
        }
    }

    override fun onBattery(
        left: BudsProtocol.Battery,
        right: BudsProtocol.Battery,
        caseBattery: BudsProtocol.Battery,
    ) {
        val merged = mergeBattery(left, right, caseBattery)
        val mergedLeft = merged[0]
        val mergedRight = merged[1]
        val mergedCase = merged[2]
        if ((mergedLeft.reported || mergedRight.reported || mergedCase.reported) && !isSynced) {
            isSynced = true
            updateStatus(true)
        }
        updateBattery(mergedLeft, mergedRight, mergedCase)
        saveBattery(mergedLeft, mergedRight, mergedCase)
    }

    override fun onAncMode(mode: Int) {
        currentAnc = mode
        prefs.edit().putInt("anc_mode", mode).apply()
        updateAnc()
    }

    override fun onDualDevices(raw: ByteArray) {
        runOnUiThread {
            dualDevicesText?.text = raw.joinToString(" ") { "%02x".format(it) }
        }
    }

    override fun onConfig(
        key: String,
        on: Boolean,
    ) {
        configState[key] = on
        configKnown.add(key)
        runOnUiThread {
            updatingConfig = true
            configSwitches[key]?.let {
                it.isChecked = on
                it.isEnabled = isConnected && supportedConfig(key)
            }
            configStatusLabels[key]?.text = officialFeatureSubtitle(key)
            configStatusSpinners[key]?.visibility = View.GONE
            updatingConfig = false
        }
    }

    override fun onGesture(
        side: Int,
        index: Int,
        action: Int,
    ) {
        if (side !in 0..1 || index !in 1..3) return
        val key = gestureKey(side, index)
        gestureActions[key] = action
        prefs.edit().putInt(key, action).apply()
        runOnUiThread { updateGestureActionView(key) }
    }

    private fun updateConfigAvailability() {
        for ((key, view) in configSwitches) {
            view.isEnabled = isConnected && supportedConfig(key)
            configStatusLabels[key]?.text =
                when {
                    !isConnected -> "Connect to sync"
                    !configKnown.contains(key) -> "Syncing…"
                    else -> officialFeatureSubtitle(key)
                }
            configStatusSpinners[key]?.visibility =
                if (isConnected && !configKnown.contains(key)) View.VISIBLE else View.GONE
        }
    }

    private fun createConfigSection(): LinearLayout {
        val section = LinearLayout(this)
        section.orientation = LinearLayout.VERTICAL
        section.layoutParams = marginBottom(24.dp())
        section.addView(categoryCards())
        section.addView(sectionHeading("Connected"))
        val settings = LinearLayout(this)
        settings.orientation = LinearLayout.VERTICAL
        settings.background = roundedSurface(28.dp())
        settings.setPadding(16.dp(), 4.dp(), 16.dp(), 4.dp())
        val connectedKeys = listOf("ineardetect", "dual")
        for ((index, key) in connectedKeys.withIndex()) {
            val item = BudsConfig.forKey(key) ?: continue
            settings.addView(configRow(item))
            if (index != connectedKeys.lastIndex) settings.addView(divider())
        }
        section.addView(settings, marginBottom(20.dp()))
        dualDevicesLabel = TextView(this)
        dualDevicesLabel!!.text = "Connected devices (raw)"
        dualDevicesLabel!!.setTextColor(color(R.color.text_secondary))
        dualDevicesLabel!!.setTextSize(12f)
        dualDevicesLabel!!.layoutParams = marginBottom(4.dp())
        section.addView(dualDevicesLabel)
        dualDevicesText = TextView(this)
        dualDevicesText!!.text = "—"
        dualDevicesText!!.setTextColor(color(R.color.text_primary))
        dualDevicesText!!.setTextSize(12f)
        dualDevicesText!!.typeface = Typeface.MONOSPACE
        section.addView(dualDevicesText, marginBottom(20.dp()))
        return section
    }

    private fun categoryCards(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = marginBottom(24.dp())
        row.addView(categoryCard("Sound", R.drawable.official_sound) { openSettingsPage("Sound", "sound") })
        row.addView(categoryCard("Gestures", R.drawable.official_gesture) { openSettingsPage("Gestures", "gestures") })
        row.addView(categoryCard("Find", R.drawable.official_find) { openSettingsPage("Find", "more") })
        return row
    }

    private fun categoryCard(
        title: String,
        iconRes: Int,
        onClick: () -> Unit,
    ): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER
        card.background = roundedSurface(28.dp())
        card.setPadding(5.dp(), 15.dp(), 5.dp(), 15.dp())
        val params = LinearLayout.LayoutParams(0, 96.dp(), 1f)
        params.marginEnd = 6.dp()
        card.layoutParams = params
        val icon = ImageView(this)
        icon.setImageResource(iconRes)
        icon.setColorFilter(color(R.color.text_primary))
        card.addView(icon, LinearLayout.LayoutParams(26.dp(), 26.dp()))
        val label = TextView(this)
        label.text = title
        label.setTextColor(color(R.color.text_primary))
        label.setTextSize(12f)
        label.typeface = Typeface.DEFAULT_BOLD
        label.gravity = Gravity.CENTER
        label.maxLines = 2
        label.includeFontPadding = false
        val labelParams = marginTop(8.dp())
        card.addView(label, labelParams)
        card.isClickable = true
        card.isFocusable = true
        addClickEffect(card)
        addRipple(card)
        card.setOnClickListener { onClick() }
        return card
    }

    private fun openSettingsPage(
        title: String,
        group: String,
    ) {
        if (activeSettingsPage == "more" && group != "more") stopRingBuds()
        activeSettingsPage = group
        configSwitches.clear()
        configStatusLabels.clear()
        configStatusSpinners.clear()
        ringSwitches.clear()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(color(R.color.background))
        root.setPadding(20.dp(), 12.dp(), 20.dp(), 24.dp())
        root.fitsSystemWindows = true
        root.addView(settingsToolbar(title))
        val scroll = ScrollView(this)
        scroll.clipToPadding = false
        scroll.isFillViewport = true
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(0, 0, 0, 0)
        when (group) {
            "sound" -> {
                addFeatureGroup(body, listOf("hires", "game", "volboost"))
            }

            "more" -> {
                addRingBudsControls(body)
            }

            else -> {
                addGestureSettings(body)
            }
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        contentHost.removeAllViews()
        root.alpha = 0f
        root.translationX = 12.dp().toFloat()
        contentHost.addView(root)
        root
            .animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(180L)
            .start()
    }

    private fun settingsToolbar(title: String): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.layoutParams = marginBottom(8.dp())
        val backTarget = FrameLayout(this)
        backTarget.contentDescription = "Back"
        backTarget.isClickable = true
        backTarget.isFocusable = true
        backTarget.setOnClickListener { showDetails() }
        val back = ImageView(this)
        back.setImageResource(R.drawable.official_back)
        back.setColorFilter(color(R.color.text_primary))
        val backIconParams = FrameLayout.LayoutParams(20.dp(), 20.dp(), Gravity.START or Gravity.CENTER_VERTICAL)
        backTarget.addView(back, backIconParams)
        bar.addView(backTarget, LinearLayout.LayoutParams(40.dp(), 40.dp()))
        val heading = TextView(this)
        heading.text = title
        heading.setTextColor(color(R.color.text_primary))
        heading.setTextSize(18f)
        heading.typeface = Typeface.DEFAULT
        val p = LinearLayout.LayoutParams(0, -2, 1f)
        p.marginStart = (-8).dp()
        bar.addView(heading, p)
        return bar
    }

    @Deprecated("Deprecated in Android API; delegated to the system back dispatcher by Activity")
    override fun onBackPressed() {
        if (activeSettingsPage != null) {
            showDetails()
        } else {
            super.onBackPressed()
        }
    }

    private fun showDetails() {
        if (activeSettingsPage == "more") stopRingBuds()
        activeSettingsPage = null
        configSwitches.clear()
        configStatusLabels.clear()
        configStatusSpinners.clear()
        detailScreen = createDetailScreen()
        detailScreen.alpha = 0f
        detailScreen.translationX = -12.dp().toFloat()
        contentHost.removeAllViews()
        contentHost.addView(detailScreen)
        detailScreen
            .animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(180L)
            .start()
        batteryRow.visibility = if (isConnected) View.VISIBLE else View.GONE
        updateStatus(isConnected)
        updateConfigAvailability()
        updateBattery(loadBattery("left"), loadBattery("right"), loadBattery("case"))
        updateAnc()
    }

    private fun addFeatureGroup(
        parent: LinearLayout,
        keys: List<String>,
    ) {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = roundedSurface(24.dp())
        panel.setPadding(16.dp(), 4.dp(), 16.dp(), 4.dp())
        for ((index, key) in keys.withIndex()) {
            val item = BudsConfig.forKey(key) ?: continue
            panel.addView(configRow(item))
            if (index != keys.lastIndex) panel.addView(divider())
        }
        parent.addView(panel, marginBottom(20.dp()))
    }

    private fun addRingBudsControls(parent: LinearLayout) {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = roundedSurface(24.dp())
        panel.setPadding(16.dp(), 4.dp(), 16.dp(), 4.dp())
        panel.addView(ringRow(0, "Left earbud"))
        panel.addView(divider())
        panel.addView(ringRow(1, "Right earbud"))
        parent.addView(panel)
    }

    private fun ringRow(
        side: Int,
        title: String,
    ): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 10.dp(), 0, 10.dp())
        val label = TextView(this)
        label.text = title
        label.setTextColor(color(R.color.text_primary))
        label.setTextSize(14f)
        label.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        row.addView(label)
        val toggle = Switch(this)
        toggle.isChecked = if (side == 0) ringLeft else ringRight
        toggle.isEnabled = isConnected
        toggle.setOnCheckedChangeListener { _, checked ->
            if (!isConnected) {
                toggle.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (side == 0) ringLeft = checked else ringRight = checked
            sendRingState()
        }
        ringSwitches[side] = toggle
        row.addView(toggle)
        return row
    }

    private fun sendRingState() {
        ringHandler.removeCallbacks(ringTimeout)
        if (!isConnected) {
            ringLeft = false
            ringRight = false
            updateRingSwitches()
            return
        }
        BudsManager.get(this).setRingBuds(ringLeft, ringRight)
        if (ringLeft || ringRight) ringHandler.postDelayed(ringTimeout, RING_TIMEOUT_MS)
    }

    private fun stopRingBuds() {
        ringHandler.removeCallbacks(ringTimeout)
        val wasRinging = ringLeft || ringRight
        ringLeft = false
        ringRight = false
        if (wasRinging && isConnected) BudsManager.get(this).setRingBuds(false, false)
        updateRingSwitches()
    }

    private fun updateRingSwitches() {
        ringSwitches[0]?.let {
            it.isChecked = ringLeft
            it.isEnabled = isConnected
        }
        ringSwitches[1]?.let {
            it.isChecked = ringRight
            it.isEnabled = isConnected
        }
    }

    private fun addGestureSettings(parent: LinearLayout) {
        val sides = LinearLayout(this)
        sides.orientation = LinearLayout.HORIZONTAL
        sides.background = roundedSurface(22.dp())
        sides.setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        sides.layoutParams = marginBottom(16.dp())
        for (side in 0..1) {
            val tab = TextView(this)
            tab.text = if (side == 0) "Left" else "Right"
            tab.gravity = Gravity.CENTER
            tab.setTextSize(13f)
            tab.typeface = Typeface.DEFAULT_BOLD
            tab.setPadding(8.dp(), 10.dp(), 8.dp(), 10.dp())
            tab.background = roundedSurface(18.dp())
            tab.setTextColor(color(R.color.text_secondary))
            if (side == gestureSide) {
                tab.background = ancCardBg(true)
                tab.setTextColor(color(R.color.background))
            }
            val params = LinearLayout.LayoutParams(0, 42.dp(), 1f)
            if (side == 0) params.marginEnd = 4.dp()
            sides.addView(tab, params)
            tab.setOnClickListener {
                if (gestureSide != side) {
                    gestureSide = side
                    openSettingsPage("Gestures", "gestures")
                }
            }
        }
        parent.addView(sides)
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = roundedSurface(24.dp())
        panel.setPadding(16.dp(), 4.dp(), 16.dp(), 4.dp())
        val gestureTypes = listOf(1 to "Double press", 2 to "Triple press", 3 to "Long press")
        for ((position, gesture) in gestureTypes.withIndex()) {
            panel.addView(gestureActionRow(gesture.first, gesture.second))
            if (position < gestureTypes.lastIndex) panel.addView(divider())
        }
        parent.addView(panel)
    }

    private fun gestureActionRow(
        index: Int,
        title: String,
    ): View {
        val key = gestureKey(gestureSide, index)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 12.dp(), 0, 12.dp())
        val copy = LinearLayout(this)
        copy.orientation = LinearLayout.VERTICAL
        copy.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val heading = TextView(this)
        heading.text = title
        heading.setTextColor(color(R.color.text_primary))
        heading.setTextSize(14f)
        copy.addView(heading)
        val action = TextView(this)
        action.setTextColor(color(R.color.text_secondary))
        action.setTextSize(12f)
        action.layoutParams = marginTop(4.dp())
        gestureActionViews[key] = action
        updateGestureActionView(key)
        copy.addView(action)
        row.addView(copy)
        val chooser = TextView(this)
        chooser.text = "›"
        chooser.setTextColor(color(R.color.text_secondary))
        chooser.setTextSize(22f)
        row.addView(chooser, LinearLayout.LayoutParams(32.dp(), 40.dp()))
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { chooseGestureAction(key, title) }
        return row
    }

    private fun chooseGestureAction(
        key: String,
        title: String,
    ) {
        if (!isConnected) return
        val choices = gestureChoices()
        val labels = choices.map { it.second }.toTypedArray()
        val current = gestureActions[key] ?: 0
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, choices.indexOfFirst { it.first == current }) { dialog, which ->
                val action = choices[which].first
                val parts = key.split(":")
                val side = parts[0].toInt()
                val index = parts[1].toInt()
                gestureActions[key] = action
                prefs.edit().putInt(key, action).apply()
                updateGestureActionView(key)
                BudsManager.get(this).setGesture(side, index, action)
                BudsManager.get(this).queryGestures()
                dialog.dismiss()
            }.setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun gestureChoices(): List<Pair<Int, String>> =
        listOf(
            0 to "Off",
            1 to "Play / Pause",
            2 to "Previous",
            3 to "Next",
            4 to "Voice Assistant",
            5 to "None",
            7 to "ANC on / off",
            8 to "Transparency off",
            9 to "ANC / transparency",
            10 to "Cycle noise control",
            11 to "Volume up",
            12 to "Volume down",
        )

    private fun gestureKey(
        side: Int,
        index: Int,
    ): String = "$side:$index"

    private fun loadGestureCache() {
        for (side in 0..1) {
            for (index in 1..3) {
                val key = gestureKey(side, index)
                if (prefs.contains(key)) gestureActions[key] = prefs.getInt(key, 0)
            }
        }
    }

    private fun updateGestureActionView(key: String) {
        val view = gestureActionViews[key] ?: return
        val action = gestureActions[key]
        view.text =
            if (action == null) {
                "Syncing…"
            } else {
                gestureChoices().firstOrNull { it.first == action }?.second ?: "Unknown action"
            }
    }

    private fun sectionHeading(text: String): TextView {
        val heading = TextView(this)
        heading.text = text
        heading.setTextColor(color(R.color.text_primary))
        heading.setTextSize(16f)
        heading.typeface = Typeface.DEFAULT_BOLD
        heading.layoutParams = marginBottom(12.dp())
        return heading
    }

    private fun roundedSurface(radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color(R.color.surface_variant))
            cornerRadius = radius.toFloat()
        }

    private fun divider(): View =
        View(this).apply {
            setBackgroundColor(color(R.color.surface))
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        }

    private fun configRow(f: BudsConfig.Feature): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 12.dp(), 0, 12.dp())

        val copy = LinearLayout(this)
        copy.orientation = LinearLayout.VERTICAL
        copy.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val label = TextView(this)
        label.text = officialFeatureLabel(f)
        label.setTextColor(color(R.color.text_primary))
        label.setTextSize(14f)
        label.maxLines = 2
        copy.addView(label)
        val subtitleText = officialFeatureSubtitle(f.key)
        if (subtitleText.isNotEmpty() || !configKnown.contains(f.key)) {
            val statusLine = LinearLayout(this)
            statusLine.orientation = LinearLayout.HORIZONTAL
            statusLine.gravity = Gravity.CENTER_VERTICAL
            val subtitle = TextView(this)
            subtitle.text =
                when {
                    !isConnected -> "Connect to sync"
                    !configKnown.contains(f.key) -> "Syncing…"
                    else -> subtitleText
                }
            subtitle.setTextColor(color(R.color.text_secondary))
            subtitle.setTextSize(12f)
            val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall)
            spinner.isIndeterminate = true
            spinner.indeterminateDrawable.setColorFilter(color(R.color.text_secondary), PorterDuff.Mode.SRC_IN)
            spinner.visibility = if (isConnected && !configKnown.contains(f.key)) View.VISIBLE else View.GONE
            val spinnerParams = LinearLayout.LayoutParams(14.dp(), 14.dp())
            spinnerParams.marginEnd = 6.dp()
            statusLine.addView(spinner, spinnerParams)
            statusLine.addView(subtitle)
            val subParams = marginTop(4.dp())
            copy.addView(statusLine, subParams)
            configStatusLabels[f.key] = subtitle
            configStatusSpinners[f.key] = spinner
        }
        row.addView(copy)

        val sw = Switch(this)
        sw.isChecked = configState[f.key] == true
        sw.isEnabled = isConnected && supportedConfig(f.key)
        sw.contentDescription = "${officialFeatureLabel(f)} switch"
        sw.setOnCheckedChangeListener { _, checked ->
            if (updatingConfig) return@setOnCheckedChangeListener
            if (!isConnected) {
                updatingConfig = true
                sw.isChecked = !checked
                updatingConfig = false
                return@setOnCheckedChangeListener
            }
            configState[f.key] = checked
            BudsManager.get(this).setConfig(f.key, checked)
        }
        configSwitches[f.key] = sw
        configStatusLabels[f.key]?.text =
            when {
                !isConnected -> "Connect to sync"
                !configKnown.contains(f.key) -> "Syncing…"
                else -> officialFeatureSubtitle(f.key)
            }
        row.addView(sw)
        return row
    }

    private fun officialFeatureLabel(feature: BudsConfig.Feature): String =
        when (feature.key) {
            "hires" -> "Hi-Res Mode"
            "game" -> "Game mode"
            "volboost" -> "Volume boost"
            "ineardetect" -> "In-ear Detection"
            "dual" -> "Dual connection"
            else -> feature.label
        }

    private fun officialFeatureSubtitle(key: String): String =
        when (key) {
            "hires" -> "Play audio in high resolution"
            "game" -> "Reduce audio latency while gaming"
            "volboost" -> "Increase the maximum volume"
            "ineardetect" -> "Play when inserted; pause when removed"
            "dual" -> "Connect to a second device"
            else -> ""
        }

    private fun supportedConfig(key: String): Boolean = key in setOf("hires", "game", "volboost", "ineardetect", "dual")

    override fun onStatus(text: String) {
        runOnUiThread {
            if (bondLost) return@runOnUiThread
            statusText.text = text
            statusText.setTextColor(color(R.color.text_secondary))
            statusDot.setTextColor(color(R.color.text_muted))
        }
    }

    override fun onBondLost() {
        runOnUiThread {
            bondLost = true
            isSynced = false
            statusText.setText(R.string.status_bond_lost)
            statusText.setTextColor(color(R.color.amber))
            statusDot.setTextColor(color(R.color.amber))
            syncSpinner.visibility = View.GONE
        }
    }

    private fun createToolbar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.layoutParams = marginBottom(12.dp())

        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_earbuds)
        icon.setColorFilter(color(R.color.text_primary))
        icon.layoutParams = LinearLayout.LayoutParams(24.dp(), 24.dp())

        val title = TextView(this)
        title.setText(R.string.app_title)
        title.setTextColor(color(R.color.text_primary))
        title.setTextSize(18f)
        title.gravity = Gravity.START
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titleParams.marginStart = 12.dp()
        title.layoutParams = titleParams

        bar.addView(icon)
        bar.addView(title)
        bar.addView(
            toolbarAction(R.drawable.official_log, R.string.action_logs) {
                showLogs()
            },
        )
        return bar
    }

    private fun toolbarAction(
        iconRes: Int,
        labelRes: Int,
        onClick: () -> Unit,
    ): ImageView {
        val view = ImageView(this)
        view.setImageResource(iconRes)
        view.setColorFilter(color(R.color.text_primary))
        val params = LinearLayout.LayoutParams(40.dp(), 40.dp())
        params.marginStart = 4.dp()
        view.layoutParams = params
        view.setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
        view.contentDescription = getString(labelRes)
        view.isClickable = true
        view.isFocusable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.foreground = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
        }
        addClickEffect(view)
        view.setOnClickListener { onClick() }
        return view
    }

    private fun createStatusRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = marginBottom(16.dp())

        statusDot = TextView(this)
        statusDot.text = "●"
        statusDot.setTextSize(10f)
        statusDot.setTextColor(color(R.color.text_muted))

        statusText = TextView(this)
        statusText.setText(R.string.status_disconnected)
        statusText.setTextColor(color(R.color.text_secondary))
        statusText.setTextSize(13f)
        statusText.setPadding(6.dp(), 0, 0, 0)

        val openSettings = View.OnClickListener { if (bondLost) showBondDialog() }
        statusDot.setOnClickListener(openSettings)
        statusText.setOnClickListener(openSettings)

        val spacer = TextView(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)

        syncSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall)
        syncSpinner.isIndeterminate = true
        syncSpinner.indeterminateDrawable.setColorFilter(
            color(R.color.text_secondary),
            PorterDuff.Mode.SRC_IN,
        )
        val spinnerParams = LinearLayout.LayoutParams(16.dp(), 16.dp())
        spinnerParams.marginEnd = 8.dp()
        syncSpinner.layoutParams = spinnerParams
        syncSpinner.visibility = View.GONE

        val bose = TextView(this)
        bose.setText(R.string.sound_by_bose)
        bose.setTextColor(color(R.color.text_secondary))
        bose.setTextSize(10f)
        bose.letterSpacing = 0.1f

        row.addView(statusDot)
        row.addView(statusText)
        row.addView(spacer)
        row.addView(syncSpinner)
        row.addView(bose)
        if (bondLost) {
            statusText.setText(R.string.status_bond_lost)
            statusText.setTextColor(color(R.color.amber))
            statusDot.setTextColor(color(R.color.amber))
        } else {
            updateStatus(isConnected)
        }
        return row
    }

    private fun createHero(): FrameLayout {
        val frame = FrameLayout(this)
        frame.layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 284.dp())
        frame.setPadding(0, 0, 0, 4.dp())

        val icon = ImageView(this)
        icon.setImageResource(R.drawable.official_buds_case)
        icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val iconParams = FrameLayout.LayoutParams(284.dp(), 284.dp())
        iconParams.gravity = Gravity.CENTER
        icon.layoutParams = iconParams

        frame.addView(icon)
        return frame
    }

    private fun createBatteryRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.weightSum = 3f
        row.layoutParams = marginBottom(16.dp())

        row.addView(batteryItem(R.string.battery_left, 0))
        row.addView(batteryItem(R.string.battery_case, 1))
        row.addView(batteryItem(R.string.battery_right, 2))
        return row
    }

    private fun batteryItem(
        labelRes: Int,
        index: Int,
    ): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        val colParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        colParams.setMargins(3.dp(), 0, 3.dp(), 0)
        col.layoutParams = colParams
        col.setPadding(4.dp(), 8.dp(), 4.dp(), 8.dp())
        col.background = roundedSurface(18.dp())
        addClickEffect(col)

        val bv = BatteryView(this)
        bv.layoutParams = LinearLayout.LayoutParams(44.dp(), 56.dp())
        bv.setCenterIcon(
            when (index) {
                0 -> R.drawable.official_left_bud
                1 -> R.drawable.official_case
                else -> R.drawable.official_right_bud
            },
        )
        bv.setColors(
            color(R.color.accent),
            color(R.color.surface_variant),
            color(R.color.text_primary),
        )
        if (index == 0) {
            batteryLeftView = bv
        } else if (index == 1) {
            batteryCaseView = bv
        } else {
            batteryRightView = bv
        }

        val tv = TextView(this)
        tv.setText(labelRes)
        tv.setTextColor(color(R.color.text_secondary))
        tv.setTextSize(11f)
        tv.gravity = Gravity.CENTER
        tv.layoutParams = marginTop(2.dp())

        col.addView(bv)
        col.addView(tv)
        return col
    }

    private fun createAncGrid(): LinearLayout {
        val section = LinearLayout(this)
        section.orientation = LinearLayout.VERTICAL
        section.layoutParams = marginBottom(20.dp())

        val header = TextView(this)
        header.setText(R.string.noise_control)
        header.setTextColor(color(R.color.text_primary))
        header.setTextSize(18f)
        header.typeface = Typeface.DEFAULT_BOLD
        header.layoutParams = marginBottom(14.dp())
        section.addView(header)

        val modes = LinearLayout(this)
        modes.orientation = LinearLayout.HORIZONTAL
        modes.weightSum = 4f
        modes.setPadding(0, 4.dp(), 0, 4.dp())
        for (mode in ANC_DISPLAY_ORDER) modes.addView(ancCard(mode))
        section.addView(modes)

        val labels = LinearLayout(this)
        labels.orientation = LinearLayout.HORIZONTAL
        labels.weightSum = 4f
        for (mode in ANC_DISPLAY_ORDER) {
            val label = TextView(this)
            label.setText(ancLabelRes(mode))
            label.setTextColor(color(R.color.text_secondary))
            label.setTextSize(10f)
            label.gravity = Gravity.CENTER
            label.maxLines = 2
            label.setPadding(2.dp(), 7.dp(), 2.dp(), 0)
            ancLabels[mode] = label
            labels.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        }
        section.addView(labels)
        return section
    }

    private fun ancLabelRes(mode: Int): Int =
        when (mode) {
            BudsProtocol.ANC_TRANSPARENCY -> R.string.anc_transparency
            BudsProtocol.ANC_ANC -> R.string.anc_anc
            BudsProtocol.ANC_ADAPTIVE -> R.string.anc_adaptive
            else -> R.string.anc_off
        }

    private fun ancCard(mode: Int): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER
        card.background = ancCardBg(mode == currentAnc)
        val p = LinearLayout.LayoutParams(0, 58.dp(), 1f)
        p.setMargins(3.dp(), 1.dp(), 3.dp(), 1.dp())
        card.layoutParams = p
        card.setPadding(3.dp(), 4.dp(), 3.dp(), 4.dp())
        card.isEnabled = isConnected
        card.alpha = if (isConnected) 1f else 0.45f

        val icons =
            intArrayOf(
                R.drawable.ic_anc_off,
                R.drawable.ic_transparency,
                R.drawable.ic_anc_on,
                R.drawable.ic_adaptive,
            )
        val icon = ImageView(this)
        icon.setImageResource(icons[mode])
        icon.setColorFilter(if (mode == currentAnc) color(R.color.background) else color(R.color.text_primary))
        icon.layoutParams = LinearLayout.LayoutParams(26.dp(), 26.dp())
        card.addView(icon)
        addClickEffect(card)
        addRipple(card)
        card.setOnClickListener {
            if (!isConnected) return@setOnClickListener
            currentAnc = mode
            updateAnc()
            BudsManager.get(this).sendAncMode(mode)
        }

        ancCards[mode] = card
        ancIcons[mode] = icon
        return card
    }

    private fun updateStatus(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                statusDot.setTextColor(color(R.color.green))
                statusText.setText(R.string.status_connected)
                statusText.setTextColor(color(R.color.green))
                syncSpinner.visibility = if (isSynced) View.GONE else View.VISIBLE
            } else {
                statusDot.setTextColor(color(R.color.text_muted))
                statusText.setText(R.string.status_disconnected)
                statusText.setTextColor(color(R.color.text_secondary))
                syncSpinner.visibility = View.GONE
            }
        }
    }

    private fun updateBattery(
        left: BudsProtocol.Battery,
        right: BudsProtocol.Battery,
        caseBattery: BudsProtocol.Battery,
    ) {
        runOnUiThread {
            batteryLeftView.setLevel(left.level, left.charging, left.reported)
            batteryRightView.setLevel(right.level, right.charging, right.reported)
            batteryCaseView.setLevel(caseBattery.level, caseBattery.charging, caseBattery.reported)
        }
    }

    private fun loadBattery() {
        updateBattery(loadBattery("left"), loadBattery("right"), loadBattery("case"))
    }

    private fun loadBattery(key: String): BudsProtocol.Battery {
        val level = prefs.getInt(key + "_level", 0)
        val charging = prefs.getBoolean(key + "_charging", false)
        val reported = prefs.getBoolean(key + "_reported", false)
        return BudsProtocol.Battery(level, charging, reported)
    }

    private fun saveBattery(
        left: BudsProtocol.Battery,
        right: BudsProtocol.Battery,
        caseBattery: BudsProtocol.Battery,
    ) {
        prefs
            .edit()
            .putInt("left_level", left.level)
            .putBoolean("left_charging", left.charging)
            .putBoolean("left_reported", left.reported)
            .putInt("right_level", right.level)
            .putBoolean("right_charging", right.charging)
            .putBoolean("right_reported", right.reported)
            .putInt("case_level", caseBattery.level)
            .putBoolean("case_charging", caseBattery.charging)
            .putBoolean("case_reported", caseBattery.reported)
            .apply()
    }

    private fun mergeBattery(
        left: BudsProtocol.Battery,
        right: BudsProtocol.Battery,
        caseBattery: BudsProtocol.Battery,
    ): Array<BudsProtocol.Battery> {
        val persistedLeft = loadBattery("left")
        val persistedRight = loadBattery("right")
        val persistedCase = loadBattery("case")

        var leftReported = left.reported
        var rightReported = right.reported
        if (leftReported && rightReported) {
            if (!left.charging && right.charging) {
                rightReported = false
            } else if (left.charging && !right.charging) {
                leftReported = false
            }
        }

        val mergedLeft =
            if (leftReported) {
                left
            } else {
                BudsProtocol.Battery(persistedLeft.level, persistedLeft.charging, false)
            }
        val mergedRight =
            if (rightReported) {
                right
            } else {
                BudsProtocol.Battery(persistedRight.level, persistedRight.charging, false)
            }

        val mergedCase: BudsProtocol.Battery
        if (caseBattery.reported) {
            if (caseBattery.level == 100 && persistedCase.level != 100) {
                case100Count++
                mergedCase = if (case100Count >= 2) caseBattery else persistedCase
            } else {
                case100Count = 0
                mergedCase = caseBattery
            }
        } else {
            case100Count = 0
            mergedCase = persistedCase
        }

        return arrayOf(mergedLeft, mergedRight, mergedCase)
    }

    private fun updateAnc() {
        runOnUiThread {
            for (i in 0 until 4) {
                val sel = i == currentAnc
                ancCards[i]?.background = ancCardBg(sel)
                ancCards[i]?.isEnabled = isConnected
                ancCards[i]?.alpha = if (isConnected) 1f else 0.45f
                ancIcons[i]?.setColorFilter(if (sel) color(R.color.background) else color(R.color.text_primary))
                ancLabels[i]?.setTextColor(color(R.color.text_primary))
            }
        }
    }

    private fun ancCardBg(selected: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(if (selected) color(R.color.text_primary) else color(R.color.surface))
        d.cornerRadius = 18.dp().toFloat()
        return d
    }

    private fun addClickEffect(v: View) {
        v.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                view
                    .animate()
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(80)
                    .start()
            } else if (event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL
            ) {
                view
                    .animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
            false
        }
    }

    private fun addRipple(v: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mask = GradientDrawable()
            mask.setColor(0xFFFFFFFF.toInt())
            mask.cornerRadius = 22.dp().toFloat()
            v.foreground =
                RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, mask)
        }
    }

    private fun marginBottom(px: Int): LinearLayout.LayoutParams {
        val p =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        p.setMargins(0, 0, 0, px)
        return p
    }

    private fun marginTop(px: Int): LinearLayout.LayoutParams {
        val p =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        p.setMargins(0, px, 0, 0)
        return p
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    @Suppress("DEPRECATION")
    private fun color(resId: Int): Int = resources.getColor(resId, theme)

    private fun btAdapter(): BluetoothAdapter? =
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (ignored: Throwable) {
            null
        } ?: BluetoothAdapter.getDefaultAdapter()

    private fun reload() {
        bondLost = false
        isSynced = false
        loadBattery()
        updateStatus(false)
        statusText.setText(R.string.status_connecting)
        BudsManager.get(this).reload(this)
    }

    private fun showLogs() {
        val text = TextView(this)
        text.setTextSize(11f)
        text.typeface = Typeface.MONOSPACE
        text.setTextColor(color(R.color.text_primary))
        text.setTextIsSelectable(true)
        val pad = 16.dp()
        text.setPadding(pad, pad, pad, pad)

        val scroll = ScrollView(this)
        scroll.addView(text)

        val render = Runnable { text.text = BudsLog.snapshot() }
        render.run()

        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(R.string.logs_title)
                .setView(scroll)
                .setPositiveButton(R.string.action_copy, null)
                .setNeutralButton(R.string.action_clear, null)
                .setNegativeButton(R.string.action_close, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                copyLogs(BudsLog.snapshot())
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                BudsLog.clear()
                render.run()
            }
        }
        dialog.setOnDismissListener { BudsLog.removeListener(render) }
        BudsLog.addListener(render)
        dialog.show()
    }

    private fun copyLogs(content: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.logs_title), content),
            )
            Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
        }
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Throwable) {
        }
    }

    private fun showBondDialog() {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.status_bond_lost)
            .setMessage(R.string.bond_help)
            .setPositiveButton(R.string.action_repair) { _, _ -> repair() }
            .setNeutralButton(R.string.action_settings) { _, _ -> openBluetoothSettings() }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun repair() {
        bondLost = false
        isSynced = false
        loadBattery()
        updateStatus(false)
        statusText.setText(R.string.status_connecting)
        BudsManager.get(this).forgetAndReload(this)
    }

    companion object {
        private const val PERMISSION_REQUEST = 1
        private const val REQUEST_ENABLE_BT = 2
        private const val CONFIG_POLL_MS = 5000L
        private const val CONFIG_GROUP_POLL_EVERY = 3
        private const val RING_TIMEOUT_MS = 60000L
        private val ANC_DISPLAY_ORDER =
            intArrayOf(
                BudsProtocol.ANC_OFF,
                BudsProtocol.ANC_TRANSPARENCY,
                BudsProtocol.ANC_ADAPTIVE,
                BudsProtocol.ANC_ANC,
            )
        private val BT_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                )
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                )
            }
    }
}
