package com.dopamide.motoanc;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity implements BudsConnection.Listener {
    private static final int PERMISSION_REQUEST = 1;
    private static final int REQUEST_ENABLE_BT = 2;
    private static final String[] BT_PERMISSIONS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ? new String[]{android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN}
            : new String[]{android.Manifest.permission.BLUETOOTH, android.Manifest.permission.BLUETOOTH_ADMIN};

    private TextView statusDot;
    private TextView statusText;
    private ProgressBar syncSpinner;
    private BatteryView batteryLeftView;
    private BatteryView batteryRightView;
    private BatteryView batteryCaseView;
    private LinearLayout[] ancCards = new LinearLayout[4];
    private ImageView[] ancIcons = new ImageView[4];
    private TextView[] ancLabels = new TextView[4];

    private BudsConnection connection;
    private int currentAnc = BudsProtocol.ANC_OFF;
    private boolean isConnected = false;
    private boolean isSynced = false;
    private boolean requestedEnable = false;
    private final BroadcastReceiver btStateReceiver = new BtStateReceiver(this);

    private static class BtStateReceiver extends BroadcastReceiver {
        private final MainActivity activity;
        BtStateReceiver(MainActivity a) { activity = a; }
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON && activity.hasPermission()) {
                    activity.requestedEnable = false;
                    activity.startConnection();
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(color(R.color.background));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.background));
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        root.setFitsSystemWindows(true);
        root.addView(createToolbar());
        root.addView(createStatusRow());
        root.addView(createHero());
        root.addView(createBatteryRow());
        root.addView(createAncGrid());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        connection = new BudsConnection(this, this);
        checkPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(btStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        if (hasPermission()) startConnection();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(btStateReceiver); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        connection.stop();
    }

    private void checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission()) {
                requestPermissions(BT_PERMISSIONS, PERMISSION_REQUEST);
            } else {
                startConnection();
            }
        } else {
            startConnection();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted) startConnection();
            else updateStatus(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            requestedEnable = false;
            startConnection();
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (String p : BT_PERMISSIONS) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
            }
        }
        return true;
    }

    private void startConnection() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            updateStatus(false);
            statusText.setText(R.string.status_no_device);
            return;
        }
        if (!adapter.isEnabled()) {
            updateStatus(false);
            if (!requestedEnable) {
                requestedEnable = true;
                try {
                    startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
                } catch (Exception e) {
                    requestedEnable = false;
                }
            }
            return;
        }
        connection.start(findDeviceAddress());
    }

    private String findDeviceAddress() {
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
        } catch (SecurityException e) {
            updateStatus(false);
        }
        return null;
    }

    @Override
    public void onConnected() {
        isConnected = true;
        isSynced = false;
        updateStatus(true);
    }

    @Override
    public void onDisconnected() {
        isConnected = false;
        isSynced = false;
        updateStatus(false);
    }

    @Override
    public void onBattery(BudsProtocol.Battery left, BudsProtocol.Battery right, BudsProtocol.Battery caseBattery) {
        if ((left.reported || right.reported || caseBattery.reported) && !isSynced) {
            isSynced = true;
            updateStatus(true);
        }
        updateBattery(left, right, caseBattery);
    }

    @Override
    public void onAncMode(int mode) {
        currentAnc = mode;
        updateAnc();
    }

    @Override
    public void onDualConnection(boolean enabled) {}

    @Override
    public void onLog(String line) {}

    private LinearLayout createToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(marginBottom(dp(16)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_earbuds);
        icon.setColorFilter(color(R.color.text_primary));
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView title = new TextView(this);
        title.setText("Moto Buds+");
        title.setTextColor(color(R.color.text_primary));
        title.setTextSize(20);
        title.setGravity(Gravity.START);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(dp(12));
        title.setLayoutParams(titleParams);

        bar.addView(icon);
        bar.addView(title);
        return bar;
    }

    private LinearLayout createStatusRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(marginBottom(dp(16)));

        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextSize(10);
        statusDot.setTextColor(color(R.color.text_muted));

        statusText = new TextView(this);
        statusText.setText(R.string.status_disconnected);
        statusText.setTextColor(color(R.color.text_secondary));
        statusText.setTextSize(13);
        statusText.setPadding(dp(6), 0, 0, 0);

        TextView spacer = new TextView(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));

        syncSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        syncSpinner.setIndeterminate(true);
        syncSpinner.getIndeterminateDrawable().setColorFilter(color(R.color.text_secondary), android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        spinnerParams.setMarginEnd(dp(8));
        syncSpinner.setLayoutParams(spinnerParams);
        syncSpinner.setVisibility(View.GONE);

        TextView bose = new TextView(this);
        bose.setText("SOUND BY BOSE");
        bose.setTextColor(color(R.color.text_secondary));
        bose.setTextSize(10);
        bose.setLetterSpacing(0.1f);

        row.addView(statusDot);
        row.addView(statusText);
        row.addView(spacer);
        row.addView(syncSpinner);
        row.addView(bose);
        return row;
    }

    private FrameLayout createHero() {
        FrameLayout frame = new FrameLayout(this);
        frame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        frame.setPadding(0, dp(16), 0, dp(16));

        View circle = new View(this);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(color(R.color.surface_variant));
        circle.setBackground(oval);
        FrameLayout.LayoutParams circleParams = new FrameLayout.LayoutParams(dp(260), dp(260));
        circleParams.gravity = Gravity.CENTER;
        circle.setLayoutParams(circleParams);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.hero_earbuds);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(240), dp(240));
        iconParams.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconParams);

        frame.addView(circle);
        frame.addView(icon);
        return frame;
    }

    private LinearLayout createBatteryRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setWeightSum(3);
        row.setLayoutParams(marginBottom(dp(8)));

        row.addView(batteryItem(R.string.battery_left, 0));
        row.addView(batteryItem(R.string.battery_case, 1));
        row.addView(batteryItem(R.string.battery_right, 2));
        return row;
    }

    private LinearLayout batteryItem(int labelRes, int index) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colParams.setMargins(dp(6), 0, dp(6), 0);
        col.setLayoutParams(colParams);
        col.setPadding(dp(8), 0, dp(8), 0);
        addClickEffect(col);

        BatteryView bv = new BatteryView(this);
        bv.setLayoutParams(new LinearLayout.LayoutParams(dp(90), dp(90)));
        bv.setColors(color(R.color.accent), color(R.color.surface_variant), color(R.color.text_primary));
        if (index == 0) batteryLeftView = bv;
        else if (index == 1) batteryCaseView = bv;
        else batteryRightView = bv;

        TextView tv = new TextView(this);
        tv.setText(labelRes);
        tv.setTextColor(color(R.color.text_secondary));
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutParams(marginTop(dp(10)));

        col.addView(bv);
        col.addView(tv);
        return col;
    }

    private LinearLayout createAncGrid() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setLayoutParams(marginBottom(dp(16)));

        TextView header = new TextView(this);
        header.setText("Noise control");
        header.setTextColor(color(R.color.text_primary));
        header.setTextSize(16);
        header.setLayoutParams(marginBottom(dp(12)));
        section.addView(header);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.addView(ancRow(0, 1));
        grid.addView(ancRow(2, 3));
        section.addView(grid);
        return section;
    }

    private LinearLayout ancRow(int a, int b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(2);
        row.addView(ancCard(a));
        row.addView(ancCard(b));
        return row;
    }

    private LinearLayout ancCard(int mode) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(ancCardBg(false));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(140), 1f);
        p.setMargins(dp(6), dp(6), dp(6), dp(6));
        card.setLayoutParams(p);
        card.setPadding(dp(12), dp(16), dp(12), dp(16));

        int[] icons = {R.drawable.ic_anc_off, R.drawable.ic_transparency, R.drawable.ic_anc_on, R.drawable.ic_adaptive};
        String[] names = {"Off", "Transparency", "ANC", "Adaptive"};

        ImageView icon = new ImageView(this);
        icon.setImageResource(icons[mode]);
        icon.setColorFilter(color(R.color.text_primary));
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView label = new TextView(this);
        label.setText(names[mode]);
        label.setTextColor(color(R.color.text_primary));
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        label.setLayoutParams(marginTop(dp(14)));

        card.addView(icon);
        card.addView(label);
        addClickEffect(card);
        addRipple(card);
        card.setOnClickListener(v -> {
            currentAnc = mode;
            updateAnc();
            connection.sendAncMode(mode);
        });

        ancCards[mode] = card;
        ancIcons[mode] = icon;
        ancLabels[mode] = label;
        return card;
    }

    private void updateStatus(boolean connected) {
        runOnUiThread(() -> {
            if (connected) {
                statusDot.setTextColor(color(R.color.green));
                statusText.setText(R.string.status_connected);
                statusText.setTextColor(color(R.color.green));
                syncSpinner.setVisibility(isSynced ? View.GONE : View.VISIBLE);
            } else {
                statusDot.setTextColor(color(R.color.text_muted));
                statusText.setText(R.string.status_disconnected);
                statusText.setTextColor(color(R.color.text_secondary));
                syncSpinner.setVisibility(View.GONE);
            }
        });
    }

    private void updateBattery(BudsProtocol.Battery left, BudsProtocol.Battery right, BudsProtocol.Battery caseBattery) {
        runOnUiThread(() -> {
            batteryLeftView.setLevel(left.level, left.charging, left.reported);
            batteryRightView.setLevel(right.level, right.charging, right.reported);
            batteryCaseView.setLevel(caseBattery.level, caseBattery.charging, caseBattery.reported);
        });
    }

    private void updateAnc() {
        runOnUiThread(() -> {
            for (int i = 0; i < 4; i++) {
                boolean sel = i == currentAnc;
                ancCards[i].setBackground(ancCardBg(sel));
                ancIcons[i].setColorFilter(sel ? color(R.color.background) : color(R.color.text_primary));
                ancLabels[i].setTextColor(sel ? color(R.color.background) : color(R.color.text_primary));
            }
        });
    }

    private String ancName(int mode) {
        switch (mode) {
            case BudsProtocol.ANC_OFF: return "Off";
            case BudsProtocol.ANC_TRANSPARENCY: return "Transparency";
            case BudsProtocol.ANC_ANC: return "ANC";
            case BudsProtocol.ANC_ADAPTIVE: return "Adaptive";
            default: return "Off";
        }
    }

    private GradientDrawable ancCardBg(boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(selected ? color(R.color.text_primary) : color(R.color.surface));
        d.setCornerRadius(dp(22));
        return d;
    }

    private void addClickEffect(View v) {
        v.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }
            return false;
        });
    }

    private void addRipple(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            mask.setCornerRadius(dp(22));
            v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, mask));
        }
    }

    private LinearLayout.LayoutParams weight1Margined(int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(margin, 0, margin, 0);
        return p;
    }

    private LinearLayout.LayoutParams marginBottom(int px) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, px);
        return p;
    }

    private LinearLayout.LayoutParams marginTop(int px) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, px, 0, 0);
        return p;
    }

    private int dp(float px) {
        return (int) (px * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int color(int resId) {
        return getResources().getColor(resId, getTheme());
    }
}
