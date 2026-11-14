package com.arinaggarwal.refract;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/** Fetches public release assets and validates them before the OS installer sees an APK. */
final class Updates {
    static final String LATEST = "https://api.github.com/repos/arinaggarwal1/refract-launcher/releases/latest";
    static final int MAX_JSON = 512 * 1024;

    static final class NoRelease extends Exception { }
    static final class NotReady extends Exception { }
    static final class Cancelled extends IOException { }
    interface Progress { boolean update(long bytes, long total); }

    static final class Release {
        String name, apkUrl, checksum;
        long version, size;
    }

    private static HttpsURLConnection connect(String address) throws IOException {
        for (int n = 0; n < 6; n++) {
            if (!UpdatePolicy.trustedUrl(address)) throw new IOException("Untrusted download address");
            HttpsURLConnection c = (HttpsURLConnection) new URL(address).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "Refract-Custom-Updater");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int status = c.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new IOException("Missing redirect");
                address = new URL(new URL(address), location).toString();
                continue;
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    private static byte[] readLimited(HttpsURLConnection c, int limit) throws IOException {
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > limit) throw new IOException("Response too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    private static JSONObject json(String address) throws Exception {
        HttpsURLConnection c = connect(address);
        if (c.getResponseCode() != 200) {
            int status = c.getResponseCode();
            c.disconnect();
            throw new IOException("GitHub returned " + status);
        }
        return new JSONObject(new String(readLimited(c, MAX_JSON), java.nio.charset.StandardCharsets.UTF_8));
    }

    static Release check() throws Exception {
        HttpsURLConnection conn = connect(LATEST);
        int status = conn.getResponseCode();
        if (status == 404) { conn.disconnect(); throw new NoRelease(); }
        if (status != 200) { conn.disconnect(); throw new IOException("GitHub returned " + status); }
        JSONObject data = new JSONObject(new String(readLimited(conn, MAX_JSON), java.nio.charset.StandardCharsets.UTF_8));
        if (data.optBoolean("draft") || data.optBoolean("prerelease")) throw new NotReady();
        JSONArray assets = data.optJSONArray("assets");
        String metadataUrl = null, apkUrl = null;
        long size = -1;
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !"uploaded".equals(asset.optString("state"))) continue;
            String name = asset.optString("name"), url = asset.optString("browser_download_url");
            if ("release.json".equals(name)) {
                if (metadataUrl != null) throw new IOException("Duplicate release metadata");
                metadataUrl = url;
            } else if ("refract-custom.apk".equals(name)) {
                if (apkUrl != null) throw new IOException("Duplicate APK");
                apkUrl = url;
                size = asset.optLong("size", -1);
            }
        }
        if (metadataUrl == null || apkUrl == null) throw new NotReady();
        if (!UpdatePolicy.releaseAssetUrl(metadataUrl) || !UpdatePolicy.releaseAssetUrl(apkUrl)
                || size <= 0 || size > UpdatePolicy.MAX_APK) throw new IOException("Invalid release assets");
        JSONObject meta = json(metadataUrl);
        Release release = new Release();
        release.name = data.optString("name", "").trim();
        if (release.name.isEmpty()) release.name = data.optString("tag_name", "Update");
        release.name = release.name.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (release.name.isEmpty()) release.name = "Update";
        if (release.name.length() > 60) release.name = release.name.substring(0, 60);
        release.version = meta.optLong("versionCode", -1);
        release.checksum = meta.optString("apkSha256", "");
        UpdatePolicy.validateMetadata(meta.optString("applicationId", ""), release.version,
                meta.optString("certificateSha256", ""), release.checksum);
        release.apkUrl = apkUrl;
        release.size = size;
        return release;
    }

    static long installedVersion(Context c) throws Exception {
        return version(c.getPackageManager().getPackageInfo(c.getPackageName(), 0));
    }

    @SuppressWarnings("deprecation")
    private static long version(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private static String sha(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo p) {
        if (Build.VERSION.SDK_INT >= 28) return p.signingInfo == null ? null : p.signingInfo.getApkContentsSigners();
        return p.signatures;
    }

    /** Android still performs its own signature and update validation during installation. */
    @SuppressWarnings("deprecation")
    static void verifyApk(Context context, File apk, Release release) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
        if (candidate == null || !UpdatePolicy.PACKAGE.equals(candidate.packageName)
                || version(candidate) != release.version || release.version <= version(installed))
            throw new IOException("The downloaded APK isn't a newer version of this launcher.");
        Signature[] existing = signers(installed), offered = signers(candidate);
        if (existing == null || offered == null || existing.length != 1 || offered.length != 1
                || !UpdatePolicy.CERT.equals(sha(existing[0].toByteArray()))
                || !UpdatePolicy.CERT.equals(sha(offered[0].toByteArray())))
            throw new IOException("The update's signing certificate doesn't match.");
    }

    static File download(Context c, Release release, Progress progress) throws Exception {
        File part = new File(c.getCacheDir(), "update-download.part.apk");
        File ready = new File(c.getCacheDir(), "verified-update.apk");
        // A cancelled or failed request never exposes an old file as the new update.
        if (part.exists() && !part.delete()) throw new IOException("Can't prepare download");
        HttpsURLConnection conn = connect(release.apkUrl);
        try {
            if (conn.getResponseCode() != 200) throw new IOException("Download failed (" + conn.getResponseCode() + ")");
            long headerSize = conn.getContentLengthLong();
            if (headerSize > UpdatePolicy.MAX_APK || (headerSize >= 0 && headerSize != release.size))
                throw new IOException("Download size doesn't match the release.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > release.size || total > UpdatePolicy.MAX_APK) throw new IOException("Download too large");
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    if (!progress.update(total, release.size)) throw new Cancelled();
                }
                out.getFD().sync();
            }
            StringBuilder sum = new StringBuilder();
            for (byte b : digest.digest()) sum.append(String.format(Locale.US, "%02x", b & 0xff));
            if (total != release.size || !release.checksum.equalsIgnoreCase(sum.toString()))
                throw new IOException("Download checksum doesn't match. Please try again.");
            verifyApk(c, part, release);
            if (!progress.update(total, release.size)) throw new Cancelled();
            if (ready.exists() && !ready.delete()) throw new IOException("Can't replace old download");
            if (!part.renameTo(ready)) throw new IOException("Can't save the update");
            return ready;
        } finally {
            conn.disconnect();
            if (part.exists()) part.delete();
        }
    }
}
