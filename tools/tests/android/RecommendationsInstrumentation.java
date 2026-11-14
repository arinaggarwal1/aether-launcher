package com.arinaggarwal.refract;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Emulator checks for recommendation attribution, preferences and exact player dispatch. */
public final class RecommendationsInstrumentation extends Instrumentation {
    static volatile String response = "tt0133093", requested = "";
    static volatile int status = 200;
    static final class Capture extends ContextWrapper {
        final LinkedBlockingQueue<Intent> launches = new LinkedBlockingQueue<>();
        Capture(Context context) { super(context); }
        @Override public void startActivity(Intent intent) { launches.add(intent); }
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static final class Connection extends HttpURLConnection {
        Connection(URL url) { super(url); requested = url.getPath(); }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() throws java.io.IOException {
            if (status == -1) throw new java.io.IOException("Offline");
            return status;
        }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(("{\"imdb_id\":\"" + response + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    void choice(Context c, String value) {
        Suggestions.prefs(c).edit().putString(Suggestions.K_TMDB_OPEN_IN, value).commit();
    }
    Suggestions.Item title(String type) {
        Suggestions.Item item = new Suggestions.Item();
        item.id = 603;
        item.type = type;
        item.title = "The Matrix";
        item.providerId = 8;
        return item;
    }
    Intent open(Capture c, Suggestions.Item item) throws Exception {
        runOnMainSync(() -> Suggestions.open(c, item));
        Intent intent = c.launches.poll(5, TimeUnit.SECONDS);
        check(intent != null, "No player launch");
        return intent;
    }
    boolean retention;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        retention = args != null && "1".equals(args.getString("retention"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (retention) {
                Context c = getTargetContext();
                check(Suggestions.tmdbOpenIn(c).equals(Suggestions.OPEN_NUVIO), "Update lost chosen player");
                check("emulator-test-key".equals(Secrets.get(c, Secrets.TMDB)), "Update lost encrypted key");
                Secrets.put(c, Secrets.TMDB, "");
                Secrets.prefs(c).edit().commit();
                result.putString("stream", "PASS: update retained player choice and encrypted API key\n");
                finish(0, result);
                return;
            }
            URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) { return new Connection(url); }
            } : null);
            Capture c = new Capture(getTargetContext());
            check(!Secrets.has(c, Secrets.TMDB) || "emulator-test-key".equals(Secrets.get(c, Secrets.TMDB)),
                    "Refusing to overwrite an existing API key on this emulator");
            Suggestions.prefs(c).edit().remove(Suggestions.K_TMDB_OPEN_IN)
                    .putString(Suggestions.K_SOURCE, Suggestions.SRC_TMDB).commit();
            check(Suggestions.tmdbOpenIn(c).equals(Suggestions.OPEN_SERVICE), "Legacy routing changed");
            check(title("movie").provider(c).id == 8, "Service default lost");
            Secrets.put(c, Secrets.TMDB, "emulator-test-key");
            for (String player : new String[]{Suggestions.OPEN_NUVIO, Suggestions.OPEN_STREMIO}) {
                choice(c, player);
                for (String type : new String[]{"movie", "tv"}) {
                    Suggestions.Item item = title(type);
                    check(item.provider().id == 8, "Streaming attribution lost");
                    check(item.provider(c).id == (player.equals(Suggestions.OPEN_NUVIO) ? -2 : -1), "Wrong player");
                    Intent intent = open(c, item);
                    String expected = player.equals(Suggestions.OPEN_NUVIO)
                            ? "nuvio://detail/" + (type.equals("tv") ? "series" : "movie") + "/tt0133093"
                            : "stremio:///detail/" + (type.equals("tv") ? "series" : "movie") + "/tt0133093"
                                    + (type.equals("movie") ? "/tt0133093" : "");
                    check(expected.equals(intent.getDataString()), "Wrong detail URL: " + intent.getDataString());
                    check(intent.getPackage().equals(item.provider(c).pkg), "Unscoped player intent");
                    check(requested.equals("/3/" + type + "/603/external_ids"), "Wrong TMDB lookup");
                    requested = "";
                    open(c, item);
                    check(requested.isEmpty(), "Repeated title lookup was not cached");
                }
            }
            response = "null";
            runOnMainSync(() -> Suggestions.open(c, title("movie")));
            check(c.launches.poll(1, TimeUnit.SECONDS) == null, "Missing IMDb ID opened another app");
            waitForIdleSync();
            status = 401;
            runOnMainSync(() -> Suggestions.open(c, title("tv")));
            check(c.launches.poll(1, TimeUnit.SECONDS) == null, "Failed lookup opened another app");
            waitForIdleSync();
            status = -1;
            runOnMainSync(() -> Suggestions.open(c, title("movie")));
            check(c.launches.poll(1, TimeUnit.SECONDS) == null, "Offline lookup opened another app");
            waitForIdleSync();
            choice(c, Suggestions.OPEN_NUVIO);
            Suggestions.prefs(c).edit().putString(Suggestions.K_SOURCE, Suggestions.SRC_JUSTWATCH).commit();
            check(title("movie").provider(c).id == 8, "TMDB preference leaked into JustWatch");
            Suggestions.prefs(c).edit().putString(Suggestions.K_SOURCE, Suggestions.SRC_TMDB).commit();
            check(Suggestions.tmdbPlayer(c) == Suggestions.NUVIO, "Player choice lost after source switch");
            result.putString("stream", "PASS: TMDB movie/series dispatch, attribution, default and saved choice, missing ID and HTTP failure\n");
            finish(0, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL: " + e + "\n");
            finish(1, result);
        }
    }
}
