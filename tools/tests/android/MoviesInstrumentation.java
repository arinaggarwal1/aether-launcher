package com.arinaggarwal.refract;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;

/** Emulator-only check that Movies opens the selected title's Nuvio options directly. */
public final class MoviesInstrumentation extends Instrumentation {
    static void check(boolean v, String message) { if (!v) throw new AssertionError(message); }
    static final class Connection extends HttpURLConnection {
        Connection(URL url) { super(url); }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return 200; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() {
            String data;
            if (url.getHost().equals("apis.justwatch.com")) {
                data = "{\"data\":{\"popularTitles\":{\"edges\":[{\"node\":{\"objectType\":\"MOVIE\",\"content\":{\"title\":\"The Matrix\",\"externalIds\":{\"imdbId\":\"tt0133093\"}},\"offers\":[]}}]}}}";
            } else if (url.getPath().endsWith("/external_ids")) data = "{\"imdb_id\":\"tt0133093\"}";
            else data = "{\"results\":[],\"genres\":[]}";
            return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
        }
    }
    static boolean contains(View v, String text) {
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(text)) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (contains(g.getChildAt(i), text)) return true;
        }
        return false;
    }
    @Override public void onCreate(Bundle b) { super.onCreate(b); start(); }
    @Override public void onStart() {
        Bundle out = new Bundle();
        try {
            URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) { return new Connection(url); }
            } : null);
            check(!Secrets.has(getTargetContext(), Secrets.TMDB), "Refusing to overwrite emulator API key");
            Secrets.put(getTargetContext(), Secrets.TMDB, "emulator-test-key");
            MainActivity home = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                home.showMovies();
                Suggestions.Item movie = new Suggestions.Item();
                movie.id = 603; movie.type = "movie"; movie.title = "The Matrix";
                movie.overview = ""; movie.genres = ""; movie.year = "1999"; movie.backdrop = "";
                TextView from = new TextView(home);
                from.setFocusable(true);
                home.movies.layer.addView(from);
                home.movies.showOptions(movie, from);
                check(home.movies.details != null && home.search == null, "Movies opened Search");
            });
            boolean[] found = {false};
            for (int i = 0; i < 30 && !found[0]; i++) {
                Thread.sleep(100);
                runOnMainSync(() -> found[0] = home.movies != null && home.movies.details != null
                        && home.movies.details.sheet != null && contains(home.movies.details.sheet, "Open in Nuvio"));
            }
            check(found[0], "Nuvio option did not appear on movie details");
            runOnMainSync(() -> {
                home.movies.closeDetails();
                check(home.movies != null && home.movies.details == null, "Back did not return to Movies");
                home.closeMovies();
            });
            Secrets.put(getTargetContext(), Secrets.TMDB, "");
            out.putString("stream", "PASS: Movies opens viewing options with Nuvio directly\n");
            finish(0, out);
        } catch (Throwable e) {
            out.putString("stream", "FAIL: " + e + "\n"); finish(1, out);
        }
    }
}
