package com.arinaggarwal.refract;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Launcher-owned app tile art, keyed by package so the app can update independently. */
final class AppArtwork {
    private AppArtwork() { }
    static final String XTRA = "com.github.razenfr.xtratv";
    private static final String PREFS = "app_artwork", CUSTOM = "custom", ORIGINAL = "original", BUILT_IN = "built_in";
    private static final long MAX_INPUT = 16L * 1024 * 1024;
    private static final int MAX_EDGE = 1024;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static String key(String pkg) { return "mode." + pkg; }
    static String mode(Context c, String pkg) { return prefs(c).getString(key(pkg), ""); }

    /** A hashed name avoids trusting package strings as file paths. */
    private static File file(Context c, String pkg) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(pkg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte b : digest) name.append(String.format(Locale.US, "%02x", b & 0xff));
        File dir = new File(c.getFilesDir(), "app-artwork");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Cannot create artwork directory");
        return new File(dir, name + ".jpg");
    }

    /** A custom selection wins, then the built-in Xtra tile; null uses the app's own art. */
    static Drawable load(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        String mode = mode(c, pkg);
        try {
            Bitmap b = null;
            if (CUSTOM.equals(mode)) b = BitmapFactory.decodeFile(file(c, pkg).getPath());
            if (b == null && XTRA.equals(pkg) && !ORIGINAL.equals(mode)) {
                try (InputStream in = c.getAssets().open("app-art/xtra-tv.jpg")) { b = BitmapFactory.decodeStream(in); }
            }
            return b == null ? null : new BitmapDrawable(c.getResources(), b);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean active(Context c, String pkg) {
        String mode = mode(c, pkg);
        return CUSTOM.equals(mode) || XTRA.equals(pkg) && !ORIGINAL.equals(mode);
    }

    static boolean canUseBuiltIn(Context c, String pkg) {
        return XTRA.equals(pkg) && (ORIGINAL.equals(mode(c, pkg)) || CUSTOM.equals(mode(c, pkg)));
    }

    static void useOriginal(Context c, String pkg) { prefs(c).edit().putString(key(pkg), ORIGINAL).apply(); }
    static void useBuiltIn(Context c, String pkg) { prefs(c).edit().putString(key(pkg), BUILT_IN).apply(); }

    /** Copy and downsample outside the UI thread; an invalid image never replaces working artwork. */
    static boolean importFile(Context c, String pkg, File source) {
        File temp = null;
        Bitmap bitmap = null;
        AtomicFile atomic = null;
        FileOutputStream out = null;
        try {
            temp = File.createTempFile("app-art-", ".image", c.getCacheDir());
            try (InputStream in = new FileInputStream(source); FileOutputStream raw = new FileOutputStream(temp)) {
                if (in == null) return false;
                byte[] buf = new byte[32768];
                long size = 0;
                for (int n; (n = in.read(buf)) != -1;) {
                    size += n;
                    if (size > MAX_INPUT) throw new java.io.IOException("Image exceeds size limit");
                    raw.write(buf, 0, n);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(temp.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > 32768 || o.outHeight > 32768) return false;
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > MAX_EDGE * 2) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            bitmap = BitmapFactory.decodeFile(temp.getPath(), o);
            if (bitmap == null) return false;
            int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longest > MAX_EDGE) {
                float scale = (float) MAX_EDGE / longest;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled; }
            }
            atomic = new AtomicFile(file(c, pkg));
            out = atomic.startWrite();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new java.io.IOException("Cannot encode image");
            atomic.finishWrite(out);
            out = null;
            prefs(c).edit().putString(key(pkg), CUSTOM).apply();
            return true;
        } catch (Exception | OutOfMemoryError e) {
            if (atomic != null && out != null) atomic.failWrite(out);
            return false;
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (temp != null) temp.delete();
        }
    }
}
