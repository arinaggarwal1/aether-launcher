package com.arinaggarwal.refract;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** Emulator-only integration checks with a mocked HTTPS transport and real signed APK parsing. */
public final class UpdaterInstrumentation extends Instrumentation {
    static final String APK_URL = "https://github.com/arinaggarwal1/refract-launcher/releases/download/v1/refract-custom.apk";
    static final String META_URL = "https://github.com/arinaggarwal1/refract-launcher/releases/download/v1/release.json";
    static final Map<String, Response> responses = new HashMap<>();

    static final class Response {
        int status;
        byte[] data;
        Response(int status, byte[] data) { this.status = status; this.data = data; }
    }
    static final class FakeConnection extends HttpsURLConnection {
        final Response response;
        FakeConnection(URL url, Response response) { super(url); this.response = response; }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return response.status; }
        @Override public long getContentLengthLong() { return response.data.length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response.data); }
        @Override public String getCipherSuite() { return "test"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static byte[] bytes(InputStream in) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[32768]; int n;
            while ((n = source.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
    static String sha(byte[] data) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data))
            result.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return result.toString();
    }
    static void releases(byte[] apk, long version, String checksum) throws Exception {
        JSONObject meta = new JSONObject().put("applicationId", UpdatePolicy.PACKAGE)
                .put("versionCode", version).put("certificateSha256", UpdatePolicy.CERT)
                .put("apkSha256", checksum);
        JSONArray assets = new JSONArray()
                .put(new JSONObject().put("name", "release.json").put("state", "uploaded")
                        .put("browser_download_url", META_URL))
                .put(new JSONObject().put("name", "refract-custom.apk").put("state", "uploaded")
                        .put("size", apk.length).put("browser_download_url", APK_URL));
        JSONObject release = new JSONObject().put("tag_name", "v1").put("assets", assets);
        responses.put(Updates.LATEST, new Response(200, release.toString().getBytes("UTF-8")));
        responses.put(META_URL, new Response(200, meta.toString().getBytes("UTF-8")));
        responses.put(APK_URL, new Response(200, apk));
    }
    Bundle options;
    @Override public void onCreate(Bundle args) { options = args; super.onCreate(args); start(); }
    static android.widget.TextView findText(android.view.View view, String label) {
        if (view instanceof android.widget.TextView && label.contentEquals(((android.widget.TextView) view).getText()))
            return (android.widget.TextView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found = findText(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) throws java.io.IOException {
                    Response response = responses.get(url.toString());
                    if (response == null) throw new java.io.IOException("Unexpected URL");
                    return new FakeConnection(url, response);
                }
            } : null);
            Context c = getTargetContext();
            byte[] apk = bytes(getContext().getAssets().open("candidate.apk"));
            byte[] wrong = bytes(getContext().getAssets().open("wrong-signer.apk"));
            java.io.File candidate = new java.io.File(c.getCacheDir(), "candidate-test.apk");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(candidate)) { out.write(apk); }
            PackageInfo info = c.getPackageManager().getPackageArchiveInfo(candidate.getAbsolutePath(), 0);
            check(info != null, "candidate APK did not parse");
            long version = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            check(version > Updates.installedVersion(c), "candidate is not newer than installed test APK");
            responses.put(Updates.LATEST, new Response(404, new byte[0]));
            try { Updates.check(); throw new AssertionError("404 not handled"); }
            catch (Updates.NoRelease expected) { }
            responses.put(Updates.LATEST, new Response(200, "{\"assets\":[]}".getBytes("UTF-8")));
            try { Updates.check(); throw new AssertionError("incomplete release not handled"); }
            catch (Updates.NotReady expected) { }
            releases(apk, version, sha(apk));
            Updates.Release release = Updates.check();
            final boolean[] progress = {false};
            java.io.File verified = Updates.download(c, release, (current, total) -> {
                progress[0] = true; return true;
            });
            check(progress[0] && verified.isFile(), "verified download missing");
            Uri uri = new Uri.Builder().scheme("content").authority(UpdateProvider.AUTHORITY)
                    .path(UpdateProvider.PATH).build();
            try (ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(uri, "r")) {
                check(fd != null && fd.getStatSize() == apk.length, "provider cannot serve update");
            }
            try {
                c.getContentResolver().openFileDescriptor(uri.buildUpon().path("/other.apk").build(), "r");
                throw new AssertionError("provider exposed another file");
            } catch (java.io.FileNotFoundException expected) { }
            releases(apk, version, "0".repeat(64));
            try { Updates.download(c, Updates.check(), (x, y) -> true); throw new AssertionError("bad hash accepted"); }
            catch (java.io.IOException expected) { }
            releases(wrong, version, sha(wrong));
            try { Updates.download(c, Updates.check(), (x, y) -> true); throw new AssertionError("wrong signer accepted"); }
            catch (java.io.IOException expected) { }
            releases(apk, version, sha(apk));
            try { Updates.download(c, Updates.check(), (x, y) -> false); throw new AssertionError("cancel ignored"); }
            catch (Updates.Cancelled expected) { }
            check(!new java.io.File(c.getCacheDir(), "update-download.part.apk").exists(), "partial download retained");
            candidate.delete();
            if ("1".equals(options.getString("permission_prompt"))) {
                check(!c.getPackageManager().canRequestPackageInstalls(), "test install permission unexpectedly allowed");
                Intent screen = new Intent().setClassName(c, "com.arinaggarwal.refract.SettingsActivity")
                        .putExtra("page", "About").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                android.app.Activity activity = startActivitySync(screen);
                runOnMainSync(() -> {
                    try {
                        java.lang.reflect.Method method = activity.getClass().getDeclaredMethod("offerUpdateInstall", Updates.Release.class);
                        method.setAccessible(true);
                        method.invoke(activity, release);
                        if (findText(activity.getWindow().getDecorView(), "Open TV Settings") == null)
                            throw new AssertionError("permission help missing");
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
            if ("1".equals(options.getString("install_prompt"))) {
                check(c.getPackageManager().canRequestPackageInstalls(), "test install permission unavailable");
                Intent screen = new Intent().setClassName(c, "com.arinaggarwal.refract.SettingsActivity")
                        .putExtra("page", "About").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                android.app.Activity activity = startActivitySync(screen);
                runOnMainSync(() -> {
                    try {
                        java.lang.reflect.Method method = activity.getClass().getDeclaredMethod("offerUpdateInstall", Updates.Release.class);
                        method.setAccessible(true);
                        method.invoke(activity, release);
                        android.widget.TextView install = findText(activity.getWindow().getDecorView(), "Install");
                        if (install == null || !(install.getParent() instanceof android.view.View))
                            throw new AssertionError("Install row missing");
                        ((android.view.View) install.getParent()).performClick();
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                android.app.UiAutomation ui = getUiAutomation();
                boolean installerVisible = false;
                for (int i = 0; i < 24; i++) {
                    android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow();
                    CharSequence pkg = root == null ? null : root.getPackageName();
                    if (pkg != null && pkg.toString().contains("packageinstaller")) {
                        installerVisible = true;
                        break;
                    }
                    Thread.sleep(250);
                }
                check(installerVisible, "system installer confirmation did not open");
                ui.injectInputEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK), true);
                ui.injectInputEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK), true);
            }
            result.putString("result", "PASS: release discovery, pending assets, APK signature/hash, provider and cancellation" +
                    ("1".equals(options.getString("install_prompt")) ? "; system installer confirmation opened" : "") +
                    ("1".equals(options.getString("permission_prompt")) ? "; permission guidance shown" : ""));
            finish(0, result);
        } catch (Throwable t) {
            result.putString("error", android.util.Log.getStackTraceString(t));
            finish(1, result);
        }
    }
}
