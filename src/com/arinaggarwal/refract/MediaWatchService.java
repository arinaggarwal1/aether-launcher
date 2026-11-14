package com.arinaggarwal.refract;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Continue Watching list. Android's own Watch Next list is reserved for system
 * launchers, so instead this listens to the media sessions apps publish while they play (title,
 * artwork, position) and remembers the latest per title. Requires notification-listener access:
 *   adb shell cmd notification allow_listener com.arinaggarwal.refract/com.arinaggarwal.refract.MediaWatchService
 */
public class MediaWatchService extends NotificationListenerService {

    MediaSessionManager msm;
    final Map<MediaSession.Token, MediaController.Callback> tracked = new HashMap<>();
    final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::onSessions;

    /** Live instance while the listener is connected (Control Center uses it for Do Not Disturb). */
    static MediaWatchService instance;

    @Override
    public void onListenerConnected() {
        instance = this;
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaWatchService.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessions, me);
            onSessions(msm.getActiveSessions(me));
        } catch (SecurityException e) {
            android.util.Log.w("Refract", "media sessions: " + e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        tracked.clear();
    }

    void onSessions(List<MediaController> list) {
        if (list == null) return;
        for (MediaController mc : list) {
            if (tracked.containsKey(mc.getSessionToken())) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata m) {
                    Store.record(MediaWatchService.this, mc.getPackageName(), m, mc.getPlaybackState());
                }

                @Override public void onPlaybackStateChanged(PlaybackState s) {
                    Store.record(MediaWatchService.this, mc.getPackageName(), mc.getMetadata(), s);
                }

                @Override public void onSessionDestroyed() {
                    tracked.remove(mc.getSessionToken());
                }
            };
            mc.registerCallback(cb);
            tracked.put(mc.getSessionToken(), cb);
            Store.record(this, mc.getPackageName(), mc.getMetadata(), mc.getPlaybackState());
        }
    }

    static boolean granted(Context c) {
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(new ComponentName(c, MediaWatchService.class));
    }

    /** The Continue Watching list on disk (files/continue.json + artwork in files/cw/). */
    static final class Store {
        static final int MAX = 20;
        static final String INCLUDED_APPS = "cw_included_apps", APPS_INITIALIZED = "cw_apps_initialized";

        /** Keep existing apps working on upgrade; apps installed after initialization need opt-in. */
        static synchronized java.util.Set<String> includedApps(Context c) {
            return includedAppsForProfile(c, Profiles.current(c).id);
        }

        static synchronized void initializeProfiles(Context c) {
            for (Profiles.Profile profile : Profiles.list(c)) includedAppsForProfile(c, profile.id);
        }

        private static java.util.Set<String> includedAppsForProfile(Context c, String profileId) {
            android.content.SharedPreferences prefs = c.getSharedPreferences(Profiles.prefsName(profileId), Context.MODE_PRIVATE);
            if (!prefs.getBoolean(APPS_INITIALIZED, false)) {
                java.util.Set<String> initial = new java.util.HashSet<>();
                android.content.pm.PackageManager pm = c.getPackageManager();
                for (String category : new String[]{android.content.Intent.CATEGORY_LEANBACK_LAUNCHER,
                        android.content.Intent.CATEGORY_LAUNCHER}) {
                    android.content.Intent query = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(category);
                    for (android.content.pm.ResolveInfo app : pm.queryIntentActivities(query, 0)) {
                        if (app.activityInfo != null && !c.getPackageName().equals(app.activityInfo.packageName))
                            initial.add(app.activityInfo.packageName);
                    }
                }
                JSONArray history;
                try {
                    File historyFile = new File(c.getFilesDir(), Profiles.continueFile(profileId));
                    history = new JSONArray(new String(Suggestions.readAll(new FileInputStream(historyFile)), StandardCharsets.UTF_8));
                } catch (Exception ignored) { history = new JSONArray(); }
                for (int i = 0; i < history.length(); i++) {
                    JSONObject entry = history.optJSONObject(i);
                    if (entry != null && !entry.optString("pkg").isEmpty()) initial.add(entry.optString("pkg"));
                }
                prefs.edit().putStringSet(INCLUDED_APPS, initial).putBoolean(APPS_INITIALIZED, true).apply();
                return initial;
            }
            return new java.util.HashSet<>(prefs.getStringSet(INCLUDED_APPS, java.util.Collections.emptySet()));
        }

        static synchronized void setIncludedApps(Context c, java.util.Set<String> packages) {
            Profiles.homePrefs(c).edit().putStringSet(INCLUDED_APPS, new java.util.HashSet<>(packages))
                    .putBoolean(APPS_INITIALIZED, true).apply();
        }

        static File file(Context c) {
            return new File(c.getFilesDir(), Profiles.continueFile(Profiles.current(c).id));
        }

        static synchronized JSONArray read(Context c) {
            try {
                return new JSONArray(new String(Suggestions.readAll(new FileInputStream(file(c))), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return new JSONArray();
            }
        }

        static synchronized void write(Context c, JSONArray a) {
            try (OutputStream os = new FileOutputStream(file(c))) {
                os.write(a.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }

        static String text(MediaMetadata m, String... keys) {
            for (String k : keys) {
                CharSequence s = m.getText(k);
                if (s != null && s.length() > 0) return s.toString();
            }
            return "";
        }

        static synchronized void record(Context c, String pkg, MediaMetadata m, PlaybackState s) {
            if (m == null || pkg == null || pkg.equals(c.getPackageName()) || !includedApps(c).contains(pkg)) return;
            String title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            if (title.isEmpty()) return;
            String sub = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM);
            long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long pos = -1;
            if (s != null) {
                pos = s.getPosition();
                if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
                }
            }
            String key = pkg + "|" + title;
            JSONArray old = read(c);
            JSONArray out = new JSONArray();
            JSONObject prev = null;
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                if (key.equals(o.optString("key"))) prev = o;
                else out.put(o);
            }
            // Finished (last 3%): drop it from the list.
            if (dur > 0 && pos > dur * 0.97) {
                write(c, out);
                return;
            }
            try {
                String art = prev != null ? prev.optString("art") : "";
                Bitmap bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (bmp != null && bmp.getWidth() >= 160) {
                    File dir = new File(c.getFilesDir(), "cw");
                    dir.mkdirs();
                    File f = new File(dir, Integer.toHexString(key.hashCode()) + ".jpg");
                    float sc = Math.min(1f, 960f / bmp.getWidth());
                    Bitmap small = sc < 1f ? Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * sc),
                            Math.round(bmp.getHeight() * sc), true) : bmp;
                    try (OutputStream os = new FileOutputStream(f)) {
                        small.compress(Bitmap.CompressFormat.JPEG, 88, os);
                    }
                    art = f.getAbsolutePath();
                } else if (art.isEmpty()) {
                    String uri = text(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                    if (uri.startsWith("http")) art = uri;
                }
                JSONObject e = new JSONObject().put("key", key).put("pkg", pkg).put("title", title).put("sub", sub)
                        .put("art", art).put("pos", pos).put("dur", dur).put("ts", System.currentTimeMillis());
                JSONArray res = new JSONArray().put(e);
                for (int i = 0; i < out.length() && res.length() < MAX; i++) res.put(out.get(i));
                write(c, res);
            } catch (Exception ignored) {
            }
        }

        /** Drops entries last played more than 7 days ago (and their saved artwork). */
        static synchronized JSONArray pruneExpired(Context c) {
            JSONArray a = read(c), keep = new JSONArray();
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (now - o.optLong("ts", now) > EXPIRE_MS) {
                    changed = true;
                    String art = o.optString("art");
                    if (art.startsWith("/")) new File(art).delete();
                } else {
                    keep.put(o);
                }
            }
            if (changed) write(c, keep);
            return keep;
        }

        static synchronized void remove(Context c, String key) {
            JSONArray old = read(c), out = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !key.equals(o.optString("key"))) out.put(o);
            }
            write(c, out);
        }

        static synchronized void clear(Context c) {
            write(c, new JSONArray());
        }

        /** Newest first, as carousel items (pkg set, progress 0..1 or -1). */
        /** Titles not played again for this long drop off Continue Watching. */
        static final long EXPIRE_MS = 7L * 24 * 60 * 60 * 1000;

        static List<Suggestions.Item> list(Context c) {
            JSONArray a = pruneExpired(c);
            List<Suggestions.Item> out = new ArrayList<>();
            java.util.Set<String> included = includedApps(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || !included.contains(o.optString("pkg")) || !Suggestions.installed(c, o.optString("pkg"))) continue;
                Suggestions.Item it = new Suggestions.Item();
                it.id = o.optString("key").hashCode();
                it.type = "continue";
                it.pkg = o.optString("pkg");
                it.key = o.optString("key");
                it.title = o.optString("title");
                it.overview = o.optString("sub");
                it.backdrop = o.optString("art");
                it.year = "";
                it.genres = "";
                long pos = o.optLong("pos", -1), dur = o.optLong("dur", 0);
                it.progress = dur > 0 && pos >= 0 ? Math.min(1f, pos / (float) dur) : -1f;
                it.remainingMin = dur > 0 && pos >= 0 ? (int) Math.max(1, (dur - pos) / 60000) : -1;
                out.add(it);
            }
            return out;
        }
    }
}
