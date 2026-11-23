package com.shahaabapps.obscura.probe;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Another app receiving a share. It lives in the test APK and runs in its own process, so it has
 * its own UID and holds only the read grant the share hands it -- exactly what a messaging or
 * files app would hold. It reads the link at once and then every EVERY_MS for FOR_MS, logging
 * each attempt under the tag "ShareProbe" as "read#n uid=.. ok name=.. size=.. bytes=.. sha=.."
 * or "read#n uid=.. fail <exception>", so a test or a person can see exactly when the link stops
 * working.
 *
 * Java, not Kotlin: the test APK does not carry the Kotlin runtime (the app under test does), and
 * this runs outside the app's process, where only the test APK's own classes are loaded.
 */
public class ShareProbeActivity extends Activity {

    static final String TAG = "ShareProbe";
    static final long EVERY_MS = 3_000L;
    static final long FOR_MS = 120_000L;

    private final HandlerThread thread = new HandlerThread("probe");
    private Handler handler;
    private int attempt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        thread.start();
        handler = new Handler(thread.getLooper());
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        Log.i(TAG, "received uid=" + Process.myUid() + " uri=" + uri);
        handler.removeCallbacksAndMessages(null);
        attempt = 0;
        if (uri == null) return;
        final long start = System.currentTimeMillis();
        handler.post(new Runnable() {
            @Override
            public void run() {
                int n = attempt++;
                try {
                    String name = null;
                    long size = -1;
                    try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                            size = c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE));
                        }
                    }
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    long total = 0;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            md.update(buf, 0, r);
                            total += r;
                        }
                    }
                    StringBuilder sha = new StringBuilder();
                    for (byte b : md.digest()) sha.append(String.format("%02x", b));
                    Log.i(TAG, "read#" + n + " uid=" + Process.myUid() + " ok name=" + name + " size=" + size + " bytes=" + total + " sha=" + sha);
                } catch (Throwable e) {
                    Log.i(TAG, "read#" + n + " uid=" + Process.myUid() + " fail " + e);
                }
                if (System.currentTimeMillis() - start < FOR_MS) handler.postDelayed(this, EVERY_MS);
            }
        });
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
        super.onDestroy();
    }
}
