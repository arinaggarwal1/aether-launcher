package com.arinaggarwal.refract;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Remote-friendly Wi-Fi controls, with Android's settings as the fallback for restricted networks. */
final class WifiPanel {
    private final MainActivity a;
    private final WifiManager wifi;
    private FrameLayout layer;
    private boolean registered, closed, scanning;
    private List<Network> networks = new ArrayList<>();
    private String notice;

    private static final int OPEN = 0, PSK = 1, OTHER = 2;
    private static final class Network {
        String ssid;
        int security, level;
        boolean connected;
        String key() { return ssid + "\n" + security; }
    }

    WifiPanel(MainActivity activity) {
        a = activity;
        wifi = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) scanning = false;
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction()) && wifi != null
                    && wifi.isWifiEnabled()) scan();
            readNetworks();
        }
    };

    void open() {
        layer = new FrameLayout(a);
        layer.setTag("wifi");
        layer.setClickable(true);
        layer.setClipChildren(false);
        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0x8C000000, 0x33000000, 0x00000000}));
        layer.addView(shade, new FrameLayout.LayoutParams(a.screenW * 3 / 5, -1, Gravity.END));
        a.root.addView(layer, a.match());
        a.pickerLayer = layer;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else a.registerReceiver(receiver, filter);
            registered = true;
        } catch (Exception ignored) { }
        render();
        scan();
    }

    void close() {
        closed = true;
        if (registered) {
            try { a.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
            registered = false;
        }
    }

    void permissionResult() { render(); scan(); }

    /** A scan is asynchronous and may be throttled; never block the Home thread waiting for it. */
    private void scan() {
        if (closed || wifi == null || !Perms.hasLocation(a) || !wifi.isWifiEnabled()) return;
        android.location.LocationManager location = (android.location.LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 28 && location != null && !location.isLocationEnabled()) {
            networks.clear();
            notice = "Turn on Location in TV settings to see networks";
            render();
            return;
        }
        scanning = true;
        render();
        new Thread(() -> {
            boolean started;
            try { started = wifi.startScan(); } catch (Exception e) { started = false; }
            if (!started) a.handler.post(() -> {
                if (closed) return;
                scanning = false;
                notice = "Showing available results; scan unavailable";
                readNetworks();
            });
            else a.handler.postDelayed(() -> {
                if (closed || !scanning) return;
                scanning = false;
                readNetworks();
            }, 6000);
        }, "refract-wifi-scan").start();
    }

    private static int security(String capabilities) {
        String c = capabilities == null ? "" : capabilities.toUpperCase(Locale.ROOT);
        if (c.contains("EAP") || c.contains("WEP") || c.contains("SAE") || c.contains("OWE") || c.contains("WAPI")) return OTHER;
        if (c.contains("PSK")) return PSK;
        if (c.contains("WPA") || c.contains("RSN")) return OTHER;
        return OPEN;
    }

    private static String unquote(String s) {
        if (s == null) return "";
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private void readNetworks() {
        if (closed || wifi == null || !Perms.hasLocation(a)) return;
        new Thread(() -> {
            List<Network> found = new ArrayList<>();
            String error = null;
            try {
                WifiInfo info = wifi.getConnectionInfo();
                String current = info != null && info.getSupplicantState() == android.net.wifi.SupplicantState.COMPLETED
                        ? unquote(info.getSSID()) : "";
                Map<String, Network> unique = new LinkedHashMap<>();
                List<ScanResult> results = wifi.getScanResults();
                if (results != null) for (ScanResult result : results) {
                    if (result.SSID == null || result.SSID.isEmpty()) continue;
                    Network n = new Network();
                    n.ssid = result.SSID;
                    n.security = security(result.capabilities);
                    n.level = result.level;
                    n.connected = n.ssid.equals(current);
                    Network old = unique.get(n.key());
                    if (old == null || n.level > old.level) unique.put(n.key(), n);
                }
                found.addAll(unique.values());
                Collections.sort(found, (x, y) -> x.connected != y.connected ? (x.connected ? -1 : 1)
                        : x.level != y.level ? Integer.compare(y.level, x.level) : x.ssid.compareToIgnoreCase(y.ssid));
            } catch (SecurityException e) { error = "Turn on Location in TV settings to see networks"; }
            catch (Exception e) { error = "Networks unavailable; open Wi-Fi settings"; }
            final String problem = error;
            a.handler.post(() -> {
                if (closed) return;
                networks = found;
                if (problem != null) notice = problem;
                render();
            });
        }, "refract-wifi-results").start();
    }

    private void render() {
        if (closed || layer == null) return;
        View previous = a.getCurrentFocus();
        Object selected = previous == null ? null : previous.getTag();
        while (layer.getChildCount() > 1) layer.removeViewAt(1);
        int width = a.dp(284), pad = a.dp(14), row = a.dp(58), gap = a.dp(10);
        FrameLayout panel = new FrameLayout(a);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setBackground(a.theme.panel(a.dp(30), a.unit));
        panel.setElevation(a.dp(20));
        TextView title = a.text(15, Ui.font(a, "semibold"), 1f);
        title.setText("Wi-Fi");
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(width, a.dp(24));
        tlp.leftMargin = pad; tlp.topMargin = pad;
        panel.addView(title, tlp);
        TextView more = a.text(11.5f, Ui.font(a, "medium"), 0.8f);
        more.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(a.dp(150), a.dp(24), Gravity.TOP | Gravity.END);
        mlp.topMargin = pad;
        mlp.rightMargin = pad + a.dp(8);
        panel.addView(more, mlp);
        FrameLayout content = new FrameLayout(a);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        List<View> cells = new ArrayList<>();
        int y = a.dp(8);
        if (wifi != null) {
            boolean enabled = wifi.isWifiEnabled();
            View toggle = a.ccTile(content, a.dp(8), y, width - a.dp(16), row, "wifi", "Wi-Fi",
                    () -> enabled ? "On" : "Off", false, () -> enabled, v -> toggle());
            toggle.setTag("toggle"); cells.add(toggle); y += row + gap;
            if (enabled && !Perms.hasLocation(a)) {
                View allow = a.ccTile(content, a.dp(8), y, width - a.dp(16), row, "wifi", "Allow Location",
                        () -> "Required to see networks", false, v -> Perms.requestLocation(a));
                allow.setTag("permission"); cells.add(allow); y += row + gap;
            } else if (enabled) {
                for (Network n : networks) {
                    String status = n.connected ? "Connected" : n.security == OPEN ? "Open network"
                            : n.security == PSK ? "Password protected" : "Open TV settings to connect";
                    View cell = a.ccTile(content, a.dp(8), y, width - a.dp(16), row, "wifi", n.ssid,
                            () -> status, false, () -> n.connected, v -> connect(n));
                    cell.setTag(n.key()); cells.add(cell); y += row + gap;
                }
                if (networks.isEmpty()) {
                    TextView info = a.text(12, Ui.font(a, "regular"), 0.8f);
                    info.setText(notice != null ? notice : scanning ? "Looking for networks..." : "No networks found");
                    FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(width - a.dp(16), a.dp(45));
                    ilp.leftMargin = a.dp(8); ilp.topMargin = y;
                    content.addView(info, ilp); y += a.dp(48);
                }
                View refresh = a.ccTile(content, a.dp(8), y, width - a.dp(16), row, "refresh", "Refresh",
                        () -> scanning ? "Looking for networks" : "Scan for networks", false, v -> { notice = null; scan(); });
                refresh.setTag("refresh"); cells.add(refresh); y += row + gap;
            }
        }
        View settings = a.ccTile(content, a.dp(8), y, width - a.dp(16), row, "gear", "Wi-Fi Settings",
                () -> "More network options", false, v -> openSettings());
        settings.setTag("settings"); cells.add(settings); y += row + a.dp(8);
        ScrollView scroll = new ScrollView(a);
        scroll.setClipChildren(true);
        scroll.addView(content, new ScrollView.LayoutParams(width, y));
        // Five complete rows at most; the header shows which way has more content.
        int height = Math.min(y, 5 * row + 4 * gap + a.dp(16));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(width, height);
        slp.leftMargin = pad; slp.topMargin = a.dp(40);
        panel.addView(scroll, slp);
        Runnable updateMore = () -> {
            boolean above = scroll.canScrollVertically(-1), below = scroll.canScrollVertically(1);
            more.setText(above && below ? "\u2191  More  \u2193" : above ? "\u2191  More" : below ? "More  \u2193" : "");
        };
        scroll.setOnScrollChangeListener((v, x, sy, ox, oy) -> updateMore.run());
        scroll.post(updateMore);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(width + 2 * pad, a.dp(40) + height + pad,
                Gravity.TOP | Gravity.END);
        plp.topMargin = a.dp(68); plp.rightMargin = a.dockOuter;
        layer.addView(panel, plp);
        a.pickerCells = cells;
        View focus = cells.get(0);
        if (selected != null) for (View cell : cells) if (selected.equals(cell.getTag())) { focus = cell; break; }
        focus.requestFocus();
    }

    /** Move through the logical row order even when a focused row is outside the viewport. */
    boolean move(int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return true;
        List<View> cells = a.pickerCells;
        if (cells.isEmpty()) return true;
        int index = cells.indexOf(a.getCurrentFocus());
        if (index < 0) index = 0;
        else index = Math.max(0, Math.min(cells.size() - 1,
                index + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
        cells.get(index).requestFocus();
        return true;
    }

    private void openSettings() {
        if (!Suggestions.start(a, new Intent(Settings.ACTION_WIFI_SETTINGS)))
            a.toast("Wi-Fi settings unavailable on this TV");
    }

    private void toggle() {
        if (Build.VERSION.SDK_INT >= 29) { openSettings(); return; }
        try {
            if (!wifi.setWifiEnabled(!wifi.isWifiEnabled())) a.toast("Use Wi-Fi settings to change Wi-Fi");
        } catch (SecurityException e) { a.toast("Use Wi-Fi settings to change Wi-Fi"); }
    }

    private void connect(Network n) {
        if (n.connected) { a.toast("Already connected to " + n.ssid); return; }
        if (Build.VERSION.SDK_INT >= 29 || n.security == OTHER) { openSettings(); return; }
        try {
            List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
            if (saved != null) for (WifiConfiguration c : saved) {
                boolean psk = c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK);
                boolean open = c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                        && (c.wepKeys == null || c.wepKeys[0] == null);
                if (n.ssid.equals(unquote(c.SSID)) && ((n.security == PSK && psk) || (n.security == OPEN && open))) {
                    connectId(c.networkId, n.ssid);
                    return;
                }
            }
        } catch (Exception e) { openSettings(); return; }
        if (n.security == OPEN) addNetwork(n, null);
        else password(n);
    }

    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    private void addNetwork(Network n, String password) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = quote(n.ssid);
        if (n.security == OPEN) config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        else {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            config.preSharedKey = password.matches("[0-9A-Fa-f]{64}") ? password : quote(password);
        }
        try {
            int id = wifi.addNetwork(config);
            if (id < 0) { a.toast("Can't add network; use Wi-Fi settings"); return; }
            connectId(id, n.ssid);
        } catch (SecurityException e) { a.toast("Can't add network; use Wi-Fi settings"); }
    }

    private void connectId(int id, String name) {
        try {
            if (id < 0 || !wifi.enableNetwork(id, true)) { a.toast("Can't connect; use Wi-Fi settings"); return; }
            wifi.reconnect();
            a.toast("Connecting to " + name);
        } catch (SecurityException e) { a.toast("Can't connect; use Wi-Fi settings"); }
    }

    /** The system keyboard enters the password; Refract never stores it in its own preferences. */
    private void password(Network n) {
        final android.app.Dialog dialog = new android.app.Dialog(a);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(a.dp(24), a.dp(20), a.dp(24), a.dp(20));
        card.setBackground(a.theme.panel(a.dp(28), a.unit));
        TextView title = a.text(16, Ui.font(a, "semibold"), 1f);
        title.setText("Join " + n.ssid);
        card.addView(title);
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setTextColor(a.theme.text);
        input.setHintTextColor(a.theme.textDim);
        input.setHint("Wi-Fi password");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(input, new LinearLayout.LayoutParams(a.dp(300), a.dp(60)));
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        android.widget.Button cancel = new android.widget.Button(a);
        cancel.setText("Cancel"); cancel.setOnClickListener(v -> dialog.dismiss()); buttons.addView(cancel);
        android.widget.Button join = new android.widget.Button(a);
        join.setText("Join"); join.setOnClickListener(v -> {
            String pass = input.getText().toString();
            if (!pass.matches("[0-9A-Fa-f]{64}") && (pass.length() < 8 || pass.length() > 63)) {
                input.setError("Enter 8 to 63 characters"); return;
            }
            dialog.dismiss();
            addNetwork(n, pass);
        });
        buttons.addView(join); card.addView(buttons);
        dialog.setContentView(card);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        input.requestFocus();
    }
}
