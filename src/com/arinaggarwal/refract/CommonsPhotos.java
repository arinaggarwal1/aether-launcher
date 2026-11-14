package com.arinaggarwal.refract;

import android.content.Context;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** One cached, credited Wikimedia featured photo; all network and decoding runs off the UI thread. */
final class CommonsPhotos {
    private CommonsPhotos() { }
    static final String ID = "commons-daily", CATEGORY = "commons";
    static final String AGENT = "RefractLauncher/1.0 (https://github.com/arinaggarwal1/refract-launcher)";
    private static final long RETRY_MS = 60 * 60 * 1000L;
    private static long lastAttempt;
    private static String lastAttemptKey = "";

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "commons");
        d.mkdirs();
        return d;
    }

    static File image(Context c, Wallpapers.Photo p) {
        if (p.file == null || !p.file.matches("daily-[0-9]{4}-[0-9]{2}-[0-9]{2}-(1080|2160)\\.jpg")) return null;
        File f = new File(dir(c), p.file);
        return f.isFile() ? f : null;
    }

    /** A placeholder remains selectable even before the first successful download. */
    static Wallpapers.Photo cached(Context c) {
        try (InputStream in = new FileInputStream(new File(dir(c), "daily.json"))) {
            Wallpapers.Photo p = Wallpapers.fromJson(new JSONObject(new String(Suggestions.readAll(in), StandardCharsets.UTF_8)));
            if (ID.equals(p.id) && image(c, p) != null) return p;
        } catch (Exception ignored) { }
        Wallpapers.Photo p = new Wallpapers.Photo();
        p.id = ID; p.file = ""; p.category = CATEGORY; p.theme = "dark";
        p.title = "Featured photo of the day"; p.photographer = "Wikimedia contributors";
        p.photographerUrl = ""; p.sourceUrl = "https://commons.wikimedia.org/wiki/Commons:Picture_of_the_day";
        p.source = "Wikimedia Commons"; p.license = ""; p.licenseUrl = "";
        return p;
    }

    static String day() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    static HttpURLConnection connection(String url) throws Exception {
        URL u = new URL(url);
        String h = u.getHost();
        if (!"https".equals(u.getProtocol()) || !(h.equals("en.wikipedia.org") || h.equals("commons.wikimedia.org")
                || h.equals("thumb.wikimedia.org") || h.equals("upload.wikimedia.org"))) throw new IllegalArgumentException("Unexpected photo host");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestProperty("User-Agent", AGENT);
        c.setConnectTimeout(8000); c.setReadTimeout(20000);
        return c;
    }

    static JSONObject json(String url) throws Exception {
        HttpURLConnection c = connection(url);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("Image service HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(Suggestions.readAll(in), StandardCharsets.UTF_8));
            }
        } finally { c.disconnect(); }
    }

    /** Refresh at most daily; failed requests back off, leaving the last working photo in place. */
    static synchronized boolean refresh(Context c) {
        if (!Wallpapers.online(c)) return false;
        String date = day(), quality = Wallpapers.quality(c);
        Wallpapers.Photo prior = cached(c);
        if (prior.file.equals("daily-" + date + "-" + quality + ".jpg")) return false;
        long now = System.currentTimeMillis();
        String attemptKey = date + ":" + quality;
        if (attemptKey.equals(lastAttemptKey) && now - lastAttempt < RETRY_MS) return false;
        lastAttempt = now;
        lastAttemptKey = attemptKey;
        File tmp = new File(dir(c), "daily-download.part");
        File metadata = new File(dir(c), "daily.json.part");
        try {
            String[] d = date.split("-");
            JSONObject feed = json("https://en.wikipedia.org/api/rest_v1/feed/featured/" + d[0] + "/" + d[1] + "/" + d[2]);
            JSONObject image = feed.getJSONObject("image");
            String title = image.getString("title");
            JSONObject artist = image.optJSONObject("artist"), license = image.optJSONObject("license");
            String author = artist == null ? "" : artist.optString("text", "");
            String licenseName = license == null ? "" : license.optString("type", "");
            String licenseUrl = license == null ? "" : license.optString("url", "");
            String source = image.optString("file_page", "");
            if (author.isEmpty() || licenseName.isEmpty() || source.isEmpty()) throw new IllegalStateException("Photo credit unavailable");
            String api = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2&prop=imageinfo&iiprop=url"
                    + "&iiurlwidth=" + (quality.equals("2160") ? 3840 : 1920) + "&titles=" + URLEncoder.encode(title, "UTF-8");
            JSONObject info = json(api).getJSONObject("query").getJSONArray("pages").getJSONObject(0).getJSONArray("imageinfo").getJSONObject(0);
            String imageUrl = info.optString("thumburl", "");
            if (imageUrl.isEmpty()) throw new IllegalStateException("No thumbnail");
            HttpURLConnection photo = connection(imageUrl);
            try {
                if (photo.getResponseCode() != 200) throw new java.io.IOException("Photo unavailable");
                try (InputStream in = photo.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] b = new byte[32768]; long total = 0;
                    for (int n; (n = in.read(b)) != -1;) {
                        total += n;
                        if (total > 16L * 1024 * 1024) throw new java.io.IOException("Photo too large");
                        out.write(b, 0, n);
                    }
                }
            } finally { photo.disconnect(); }
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("Invalid photo");
            String filename = "daily-" + date + "-" + quality + ".jpg";
            File dest = new File(dir(c), filename);
            if (!tmp.renameTo(dest)) throw new java.io.IOException("Cannot save photo");
            JSONObject saved = new JSONObject().put("id", ID).put("file", filename).put("category", CATEGORY)
                    .put("theme", "dark").put("title", "Featured photo of the day").put("photographer", author)
                    .put("source_url", source).put("source", "Wikimedia Commons").put("license", licenseName)
                    .put("license_url", licenseUrl);
            try (FileOutputStream out = new FileOutputStream(metadata)) { out.write(saved.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!metadata.renameTo(new File(dir(c), "daily.json"))) throw new java.io.IOException("Cannot save photo details");
            Wallpapers.invalidate();
            File[] files = dir(c).listFiles();
            if (files != null) for (File f : files) {
                if (!f.getName().equals(filename) && f.getName().matches("daily-[0-9]{4}-[0-9]{2}-[0-9]{2}-(1080|2160)\\.jpg")) f.delete();
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w("TvHome", "Wikimedia photo unavailable: " + e.getClass().getSimpleName());
            return false;
        } finally { tmp.delete(); metadata.delete(); }
    }
}
