package df.root.shizuku.cn;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Runs only in Shizuku's separate shell/root process. */
public final class InitLogService extends IInitLogService.Stub {
    static final String BOOT_ID = "boot_id";
    static final String ORIGINAL = "original";
    static final String ERROR = "error";
    static final String DETAIL = "detail";
    static final String RESTORED = "restored";
    static final String READY = "ready";
    static final String EXPIRED = "expired";
    static final String PROPERTY = "log.tag.init";
    static final String TEMPORARY_VALUE = "I";

    private final int ownerUid;
    private final Object lock = new Object();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private Session active;

    // Shizuku API 13+ creates this class reflectively with the application's package context.
    public InitLogService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
    }

    @Override
    public Bundle snapshot() {
        enforceOwner();
        synchronized (lock) {
            if (active != null) return failure("busy", "", false);
            try {
                Bundle result = new Bundle();
                result.putString(BOOT_ID, readBootId());
                result.putString(ORIGINAL, readProperty());
                return result;
            } catch (IOException e) {
                return failure("io", e.getMessage(), false);
            }
        }
    }

    @Override
    public Bundle begin(IBinder owner, String bootId, String originalValue) {
        enforceOwner();
        synchronized (lock) {
            if (active != null) return failure("busy", "", false);
            if (owner == null || !validRecord(bootId, originalValue)) {
                return failure("record", "", true);
            }
            try {
                if (!sameBoot(bootId) || !originalValue.equals(readProperty())) {
                    return failure("changed", "", true);
                }
            } catch (IOException e) {
                return failure("io", e.getMessage(), true);
            }

            Session session = new Session(owner, bootId, originalValue);
            active = session;
            try {
                owner.linkToDeath(session.death, 0);
                session.linked = true;
                session.timeout = timer.schedule(() -> expire(session), 120, TimeUnit.SECONDS);
                if (!TEMPORARY_VALUE.equals(originalValue)) {
                    // Record the attempt before writing: a failed reply does not prove no change.
                    session.writeAttempted = true;
                    writeProperty(TEMPORARY_VALUE);
                }
                if (!TEMPORARY_VALUE.equals(readProperty())) {
                    throw new IOException("init log property verification failed");
                }
                session.verified = true;
                Bundle result = new Bundle();
                result.putBoolean(READY, true);
                return result;
            } catch (IOException | RemoteException e) {
                Bundle cleanup = restoreSession(session);
                release(session);
                Bundle result = failure("io", e.getMessage(), cleanup.getBoolean(RESTORED));
                result.putString("restore_error", cleanup.getString(ERROR, ""));
                return result;
            }
        }
    }

    @Override
    public Bundle finish(IBinder owner) {
        enforceOwner();
        synchronized (lock) {
            if (active == null) return failure("missing", "", false);
            if (!active.owner.equals(owner)) return failure("busy", "", false);
            Session session = active;
            Bundle result = restoreSession(session);
            result.putBoolean(EXPIRED, session.expired);
            release(session);
            return result;
        }
    }

    @Override
    public Bundle recover(String bootId, String originalValue) {
        enforceOwner();
        synchronized (lock) {
            if (active != null) return failure("busy", "", false);
            return restoreValue(bootId, originalValue);
        }
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            if (active != null) {
                Session session = active;
                logCleanupFailure(restoreSession(session));
                release(session);
            }
        }
        timer.shutdown();
        System.exit(0);
    }

    private void enforceOwner() {
        if (Binder.getCallingUid() != ownerUid) {
            throw new SecurityException("Only the owning application may use this service");
        }
    }

    private void expire(Session session) {
        synchronized (lock) {
            if (active != session) return;
            session.expired = true;
            logCleanupFailure(restoreSession(session));
            // The native task may still be cleaning up. Keep its lease until finish/client death.
        }
    }

    private void ownerDied(Session session) {
        synchronized (lock) {
            if (active != session) return;
            logCleanupFailure(restoreSession(session));
            release(session);
        }
    }

    private Bundle restoreSession(Session session) {
        if (session.restored || (!session.writeAttempted && !session.verified)) {
            Bundle result = new Bundle();
            result.putBoolean(RESTORED, true);
            return result;
        }
        Bundle result = restoreValue(session.bootId, session.original);
        session.restored = result.getBoolean(RESTORED);
        return result;
    }

    private static Bundle restoreValue(String bootId, String original) {
        if (!validRecord(bootId, original)) return failure("record", "", false);
        try {
            Bundle result = new Bundle();
            // Non-persistent properties reset on reboot; never apply an old boot's value.
            if (sameBoot(bootId)) {
                String current = readProperty();
                if (!original.equals(current)) {
                    if (!TEMPORARY_VALUE.equals(current)) return failure("changed", "", false);
                    writeProperty(original);
                    if (!original.equals(readProperty())) return failure("verify", "", false);
                }
            }
            result.putBoolean(RESTORED, true);
            return result;
        } catch (IOException e) {
            return failure("io", e.getMessage(), false);
        }
    }

    private void release(Session session) {
        if (session.timeout != null) session.timeout.cancel(false);
        if (session.linked) {
            try {
                session.owner.unlinkToDeath(session.death, 0);
            } catch (NoSuchElementException alreadyDead) {
                // Binder can remove a death recipient before its callback runs.
                Log.d("dfroot", "Shizuku client death recipient already removed");
            }
        }
        if (active == session) active = null;
    }

    private static void logCleanupFailure(Bundle result) {
        if (!result.getBoolean(RESTORED)) {
            Log.e("dfroot", "Shizuku cleanup pending: " + result.getString(ERROR));
        }
    }

    private static boolean validRecord(String bootId, String original) {
        return bootId != null && bootId.matches("(?:[0-9a-fA-F-]{36}|count:[0-9]{1,20})") && original != null
                && original.getBytes(StandardCharsets.UTF_8).length <= 91
                && original.indexOf('\0') < 0;
    }

    private static Bundle failure(String code, String detail, boolean restored) {
        Bundle result = new Bundle();
        result.putString(ERROR, code);
        result.putString(DETAIL, detail == null ? "" : detail);
        result.putBoolean(RESTORED, restored);
        return result;
    }

    private static String readBootId() throws IOException {
        try {
            return readProcBootId();
        } catch (IOException restrictedProc) {
            return readBootCount();
        }
    }

    private static boolean sameBoot(String recorded) throws IOException {
        // A root transition can change proc visibility; keep using the recorded identity source.
        return recorded.equals(recorded.startsWith("count:") ? readBootCount() : readProcBootId());
    }

    private static String readBootCount() throws IOException {
        String count = command("/system/bin/cmd", "settings", "get", "global", "boot_count").trim();
        if (!count.matches("[0-9]{1,20}")) throw new IOException("boot count unavailable");
        return "count:" + count;
    }

    private static String readProcBootId() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String value = reader.readLine();
            if (value == null || !value.matches("[0-9a-fA-F-]{36}")) {
                throw new IOException("boot identity unavailable");
            }
            return value;
        }
    }

    private static String readProperty() throws IOException {
        String output = command("/system/bin/getprop", PROPERTY);
        // Preserve the original value, including an empty value and any spaces.
        return output.endsWith("\n") ? output.substring(0, output.length() - 1) : output;
    }

    private static void writeProperty(String value) throws IOException {
        command("/system/bin/setprop", PROPERTY, value);
    }

    private static String command(String... arguments) throws IOException {
        java.lang.Process process = new ProcessBuilder(arguments).start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                throw new IOException("property command timed out");
            }
            try (InputStream output = process.getInputStream(); InputStream error = process.getErrorStream()) {
                String value = readOutput(output);
                String detail = readOutput(error);
                if (process.exitValue() != 0) {
                    throw new IOException("property command exited " + process.exitValue() + ": " + detail.trim());
                }
                return value;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("property command interrupted", e);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String readOutput(InputStream input) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (bytes.size() + count > 4096) throw new IOException("property response too large");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private final class Session {
        final IBinder owner;
        final String bootId;
        final String original;
        final IBinder.DeathRecipient death = () -> ownerDied(this);
        ScheduledFuture<?> timeout;
        boolean linked, writeAttempted, verified, restored, expired;

        Session(IBinder owner, String bootId, String original) {
            this.owner = owner;
            this.bootId = bootId;
            this.original = original;
        }
    }
}
