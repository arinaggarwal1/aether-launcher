package com.arinaggarwal.refract;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;

/** Emulator-only verification of rotation, cached attribution and update retention. */
public final class WallpaperInstrumentation extends Instrumentation {
    static boolean retention;
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static final class Connection extends HttpURLConnection {
        Connection(URL url) { super(url); }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return 200; }
        @Override public InputStream getInputStream() {
            if (url.getHost().equals("thumb.wikimedia.org")) {
                return new ByteArrayInputStream(android.util.Base64.decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aYxkAAAAASUVORK5CYII=", 0));
            }
            String body = url.getHost().equals("en.wikipedia.org")
                    ? "{\"image\":{\"title\":\"File:Example.jpg\",\"file_page\":\"https://commons.wikimedia.org/wiki/File:Example.jpg\",\"artist\":{\"text\":\"Test Photographer\"},\"license\":{\"type\":\"CC BY 4.0\",\"url\":\"https://creativecommons.org/licenses/by/4.0\"}}}"
                    : "{\"query\":{\"pages\":[{\"imageinfo\":[{\"thumburl\":\"https://thumb.wikimedia.org/test.jpg\"}]}]}}";
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        retention = args != null && "1".equals(args.getString("retention"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context c = getTargetContext();
            if (retention) {
                check(Wallpapers.rotationMinutes(c) == 30, "Update lost rotation interval");
                check(Wallpapers.style(c).equals(Wallpapers.PHOTO + CommonsPhotos.ID), "Update lost photo choice");
                Wallpapers.Photo p = CommonsPhotos.cached(c);
                check(CommonsPhotos.image(c, p) != null && p.photographer.equals("Test Photographer"), "Update lost photo or credit");
                Wallpapers.setRotation(c, 0);
                result.putString("stream", "PASS: update retained rotation, selected photo and credit\n");
                finish(0, result); return;
            }
            URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) { return new Connection(url); }
            } : null);
            Wallpapers.setRotation(c, 30);
            long now = System.currentTimeMillis();
            Wallpapers.home(c).edit().putBoolean(Wallpapers.K_ROTATE_ALL, true)
                    .putLong(Wallpapers.K_ROTATED_AT, now - 31 * 60000L).commit();
            String before = Wallpapers.style(c);
            check(Wallpapers.rotateIfDue(c, now), "Due rotation did not advance");
            check(!before.equals(Wallpapers.style(c)), "Rotation repeated current photo");
            check(!Wallpapers.rotateIfDue(c, now), "Rotation repeated before interval");
            check(CommonsPhotos.refresh(c), "Wikimedia photo did not refresh");
            Wallpapers.Photo daily = CommonsPhotos.cached(c);
            check(CommonsPhotos.image(c, daily) != null, "Photo missing");
            check(daily.credit().contains("Test Photographer") && daily.license.equals("CC BY 4.0"), "Credit missing");
            check(daily.sourceUrl.contains("File:Example.jpg") && !daily.licenseUrl.isEmpty(), "Source or license link missing");
            check(!CommonsPhotos.refresh(c), "Daily photo fetched twice");
            Wallpapers.select(c, daily);
            Wallpapers.home(c).edit().commit();
            result.putString("stream", "PASS: background rotation and Wikimedia photo cache\n");
            finish(0, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL: " + e + "\n");
            finish(1, result);
        }
    }
}
