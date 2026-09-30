package com.arnavk09.motoanc

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Single-screen controller UI, built programmatically: status row, hero image,
 * three battery gauges, and the 2x2 ANC mode grid.
 */
class MainActivity :
    Activity(),
    BudsConnection.Listener {
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var syncSpinner: ProgressBar
    private lateinit var batteryLeftView: BatteryView
    private lateinit var batteryRightView: BatteryView
    private lateinit var batteryCaseView: BatteryView

    private val ancCards = arrayOfNulls<LinearLayout>(4)
    private val ancIcons = arrayOfNulls<ImageView>(4)
    private val ancLabels = arrayOfNulls<TextView>(4)

    private lateinit var prefs: SharedPreferences
    private var currentAnc = BudsProtocol.ANC_OFF
    private var isConnected = false
    private var isSynced = false
    private var requestedEnable = false
    private var case100Count = 0
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
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(color(R.color.background))
        root.setPadding(20.dp(), 28.dp(), 20.dp(), 28.dp())
        root.fitsSystemWindows = true
        root.addView(createToolbar())
        root.addView(createStatusRow())
        root.addView(createHero())
        root.addView(createBatteryRow())
        root.addView(createAncGrid())
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        prefs = getSharedPreferences("buds", Context.MODE_PRIVATE)
        currentAnc = prefs.getInt("anc_mode", BudsProtocol.ANC_OFF)
        loadBattery()
        updateAnc()
        checkPermission()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        BudsManager.get(this).addListener(this)
        if (hasPermission()) startConnection()
    }

    override fun onPause() {
        super.onPause()
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
        val adapter = BluetoothAdapter.getDefaultAdapter()
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
        updateStatus(true)
    }

    override fun onDisconnected() {
        isConnected = false
        isSynced = false
        updateStatus(false)
        updateBattery(
            BudsProtocol.Battery(0, false, false),
            BudsProtocol.Battery(0, false, false),
            BudsProtocol.Battery(0, false, false),
        )
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

    private fun createToolbar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.layoutParams = marginBottom(16.dp())

        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_earbuds)
        icon.setColorFilter(color(R.color.text_primary))
        icon.layoutParams = LinearLayout.LayoutParams(28.dp(), 28.dp())

        val title = TextView(this)
        title.setText(R.string.app_title)
        title.setTextColor(color(R.color.text_primary))
        title.setTextSize(20f)
        title.gravity = Gravity.START
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titleParams.marginStart = 12.dp()
        title.layoutParams = titleParams

        bar.addView(icon)
        bar.addView(title)
        return bar
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
        return row
    }

    private fun createHero(): FrameLayout {
        val frame = FrameLayout(this)
        frame.layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300.dp())
        frame.setPadding(0, 16.dp(), 0, 16.dp())

        val circle = View(this)
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(color(R.color.surface_variant))
        circle.background = oval
        val circleParams = FrameLayout.LayoutParams(260.dp(), 260.dp())
        circleParams.gravity = Gravity.CENTER
        circle.layoutParams = circleParams

        val icon = ImageView(this)
        icon.setImageResource(R.drawable.hero_earbuds)
        icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val iconParams = FrameLayout.LayoutParams(240.dp(), 240.dp())
        iconParams.gravity = Gravity.CENTER
        icon.layoutParams = iconParams

        frame.addView(circle)
        frame.addView(icon)
        return frame
    }

    private fun createBatteryRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.weightSum = 3f
        row.layoutParams = marginBottom(8.dp())

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
        col.gravity = Gravity.CENTER_HORIZONTAL
        val colParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        colParams.setMargins(6.dp(), 0, 6.dp(), 0)
        col.layoutParams = colParams
        col.setPadding(8.dp(), 0, 8.dp(), 0)
        addClickEffect(col)

        val bv = BatteryView(this)
        bv.layoutParams = LinearLayout.LayoutParams(90.dp(), 90.dp())
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
        tv.setTextSize(13f)
        tv.gravity = Gravity.CENTER
        tv.layoutParams = marginTop(10.dp())

        col.addView(bv)
        col.addView(tv)
        return col
    }

    private fun createAncGrid(): LinearLayout {
        val section = LinearLayout(this)
        section.orientation = LinearLayout.VERTICAL
        section.layoutParams = marginBottom(16.dp())

        val header = TextView(this)
        header.setText(R.string.noise_control)
        header.setTextColor(color(R.color.text_primary))
        header.setTextSize(16f)
        header.layoutParams = marginBottom(12.dp())
        section.addView(header)

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.addView(ancRow(0, 1))
        grid.addView(ancRow(2, 3))
        section.addView(grid)
        return section
    }

    private fun ancRow(
        a: Int,
        b: Int,
    ): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.weightSum = 2f
        row.addView(ancCard(a))
        row.addView(ancCard(b))
        return row
    }

    private fun ancCard(mode: Int): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER
        card.background = ancCardBg(false)
        val p = LinearLayout.LayoutParams(0, 140.dp(), 1f)
        p.setMargins(6.dp(), 6.dp(), 6.dp(), 6.dp())
        card.layoutParams = p
        card.setPadding(12.dp(), 16.dp(), 12.dp(), 16.dp())

        val icons =
            intArrayOf(
                R.drawable.ic_anc_off,
                R.drawable.ic_transparency,
                R.drawable.ic_anc_on,
                R.drawable.ic_adaptive,
            )
        val names =
            intArrayOf(
                R.string.anc_off,
                R.string.anc_transparency,
                R.string.anc_anc,
                R.string.anc_adaptive,
            )

        val icon = ImageView(this)
        icon.setImageResource(icons[mode])
        icon.setColorFilter(color(R.color.text_primary))
        icon.layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp())

        val label = TextView(this)
        label.text = getString(names[mode])
        label.setTextColor(color(R.color.text_primary))
        label.setTextSize(13f)
        label.gravity = Gravity.CENTER
        label.layoutParams = marginTop(14.dp())

        card.addView(icon)
        card.addView(label)
        addClickEffect(card)
        addRipple(card)
        card.setOnClickListener {
            currentAnc = mode
            updateAnc()
            BudsManager.get(this).sendAncMode(mode)
        }

        ancCards[mode] = card
        ancIcons[mode] = icon
        ancLabels[mode] = label
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
        updateBattery(
            BudsProtocol.Battery(0, false, false),
            BudsProtocol.Battery(0, false, false),
            BudsProtocol.Battery(0, false, false),
        )
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
                ancCards[i]!!.background = ancCardBg(sel)
                ancIcons[i]!!.setColorFilter(
                    if (sel) color(R.color.background) else color(R.color.text_primary),
                )
                ancLabels[i]!!.setTextColor(
                    if (sel) color(R.color.background) else color(R.color.text_primary),
                )
            }
        }
    }

    private fun ancCardBg(selected: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(if (selected) color(R.color.text_primary) else color(R.color.surface))
        d.cornerRadius = 22.dp().toFloat()
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

    private fun Float.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    @Suppress("DEPRECATION")
    private fun color(resId: Int): Int = resources.getColor(resId, theme)

    companion object {
        private const val PERMISSION_REQUEST = 1
        private const val REQUEST_ENABLE_BT = 2
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
