package df.root.shizuku.cn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.util.concurrent.TimeUnit;

public class BootReceiver extends BroadcastReceiver implements IReporter {
    private static final String TAG = "dfroot";
    private static final long WAKE_LOCK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (new File("/dev/df").exists()) {
            Log.i(TAG, "boot: already hooked, skipping");
            return;
        }
        Log.i(TAG, "boot: " + intent.getAction());
        final Context deCtx = context.createDeviceProtectedStorageContext();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            Log.e(TAG, "boot: PowerManager unavailable");
            return;
        }
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:boot");
        // One acquisition per worker; a timeout racing with finally must be safe.
        wl.setReferenceCounted(false);
        try {
            new Thread(() -> {
                try {
                    // Acquire only after startup succeeds, and bound battery impact if it stalls.
                    wl.acquire(WAKE_LOCK_TIMEOUT_MS);
                    int rc = ExploitRunner.run(deCtx, this);
                    Log.i(TAG, "boot: exploit rc=" + rc);
                } catch (Exception e) {
                    Log.e(TAG, "boot: exploit exception", e);
                } finally {
                    if (wl.isHeld()) wl.release();
                }
            }, "dfroot-boot").start();
        } catch (RuntimeException e) {
            Log.e(TAG, "boot: unable to start worker", e);
        }
    }
}
