package com.arinaggarwal.refract;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Profile-local folders. App keys stay unique across folders and the home grid. */
final class Folders {
    static final String PREFIX = "folder:";
    private static final String PREF = "folders";

    static final class Folder {
        String key, name;
        final List<String> apps = new ArrayList<>();
    }

    final List<Folder> list = new ArrayList<>();

    void load(SharedPreferences prefs, Set<String> available, Set<String> hidden) {
        list.clear();
        Set<String> assigned = new HashSet<>();
        Set<String> ids = new HashSet<>();
        try {
            JSONArray data = new JSONArray(prefs.getString(PREF, "[]"));
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null) continue;
                Folder f = new Folder();
                f.key = o.optString("key", "");
                f.name = o.optString("name", "Folder").trim();
                if (!f.key.startsWith(PREFIX) || !ids.add(f.key)) continue;
                if (f.name.isEmpty()) f.name = "Folder";
                JSONArray members = o.optJSONArray("apps");
                if (members == null) continue;
                for (int j = 0; j < members.length(); j++) {
                    String key = members.optString(j, "");
                    if (available.contains(key) && !hidden.contains(key) && assigned.add(key)) f.apps.add(key);
                }
                if (!f.apps.isEmpty()) list.add(f);
            }
        } catch (org.json.JSONException ignored) { }
    }

    Folder find(String key) {
        for (Folder f : list) if (f.key.equals(key)) return f;
        return null;
    }

    Folder containing(String app) {
        for (Folder f : list) if (f.apps.contains(app)) return f;
        return null;
    }

    Folder create(String name) {
        Folder f = new Folder();
        f.key = PREFIX + UUID.randomUUID();
        f.name = name;
        list.add(f);
        return f;
    }

    /** Removing the last app also removes its empty folder. */
    void removeApp(String key) {
        for (Folder f : new ArrayList<>(list)) {
            f.apps.remove(key);
            if (f.apps.isEmpty()) list.remove(f);
        }
    }

    void save(SharedPreferences.Editor edit) {
        JSONArray data = new JSONArray();
        try {
            for (Folder f : list) data.put(new JSONObject().put("key", f.key)
                    .put("name", f.name).put("apps", new JSONArray(f.apps)));
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("Unable to save folders", e);
        }
        edit.putString(PREF, data.toString());
    }
}
