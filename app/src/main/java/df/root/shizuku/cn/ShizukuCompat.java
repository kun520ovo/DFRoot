package df.root.shizuku.cn;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import rikka.shizuku.Shizuku;

/** Shizuku connection and the recoverable, per-run init log setting. */
final class ShizukuCompat {
    private static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    private static final int REQUEST_PERMISSION = 0xdf02;
    // Bump when the UserService implementation or its IPC protocol changes.
    private static final int SERVICE_VERSION = 1;

    // Process singleton: the stored context comes from getApplicationContext().
    @SuppressLint("StaticFieldLeak")
    private static ShizukuCompat instance;

    private enum State {
        CHECKING(R.string.shizuku_checking), MISSING(R.string.shizuku_missing),
        STOPPED(R.string.shizuku_stopped), PERMISSION(R.string.shizuku_permission),
        REQUESTING(R.string.shizuku_requesting), OLD_VERSION(R.string.shizuku_old_version),
        READY(R.string.shizuku_ready), RUNNING(R.string.shizuku_running),
        REBOOTING(R.string.shizuku_rebooting),
        RESTORE_FAILED(R.string.shizuku_restore_pending), ERROR(R.string.shizuku_check_failed);

        final int caption;
        State(int caption) { this.caption = caption; }
    }

    static final class Result {
        final int rootCode;
        final boolean settingsRestored;
        final boolean recoveryPending;
        final boolean rebootRequested;
        final boolean rebootFailed;

        Result(int rootCode, boolean settingsRestored, boolean recoveryPending,
               boolean rebootRequested, boolean rebootFailed) {
            this.rootCode = rootCode;
            this.settingsRestored = settingsRestored;
            this.recoveryPending = recoveryPending;
            this.rebootRequested = rebootRequested;
            this.rebootFailed = rebootFailed;
        }
    }

    static synchronized ShizukuCompat get(Context context) {
        if (instance == null) instance = new ShizukuCompat(context);
        return instance;
    }

    private final Context context;
    private final SharedPreferences journal;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ReentrantLock operation = new ReentrantLock();
    private final Object stateLock = new Object();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Shizuku.UserServiceArgs serviceArgs;
    private volatile IInitLogService service;
    private volatile State state = State.CHECKING;
    private volatile boolean running, clearFailed, checking;
    private boolean binding, requesting;

    private final Runnable bindTimeout = this::onBindTimeout;

    private final Runnable permissionTimeout = () -> {
        requesting = false;
        refresh();
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            main.post(() -> {
                if (!binding || !binder.pingBinder()) return;
                binding = false;
                main.removeCallbacks(bindTimeout);
                service = IInitLogService.Stub.asInterface(binder);
                checkConnectedService();
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            main.post(() -> {
                service = null;
                binding = false;
                main.removeCallbacks(bindTimeout);
                setState(hasPendingRecovery() ? State.RESTORE_FAILED : State.STOPPED);
            });
        }
    };

    private ShizukuCompat(Context context) {
        this.context = context.getApplicationContext().createDeviceProtectedStorageContext();
        journal = this.context.getSharedPreferences("shizuku_recovery", Context.MODE_PRIVATE);
        serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(this.context, InitLogService.class))
                .tag("dfroot-init-log").processNameSuffix("shizuku")
                .daemon(true).version(SERVICE_VERSION);

        // These listeners belong to the application process, not an Activity or its views.
        Shizuku.addBinderReceivedListenerSticky(this::refresh);
        Shizuku.addBinderDeadListener(() -> main.post(() -> {
            service = null;
            binding = false;
            requesting = false;
            main.removeCallbacks(bindTimeout);
            main.removeCallbacks(permissionTimeout);
            setState(hasPendingRecovery() ? State.RESTORE_FAILED : State.STOPPED);
        }));
        Shizuku.addRequestPermissionResultListener((code, grantResult) -> {
            if (code != REQUEST_PERMISSION) return;
            main.post(() -> {
                requesting = false;
                main.removeCallbacks(permissionTimeout);
                refresh();
            });
        });
        refresh();
    }

    void addListener(Runnable listener) {
        listeners.addIfAbsent(listener);
    }

    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    boolean isReady() {
        IInitLogService current = service;
        return state == State.READY && !running && current != null && current.asBinder().pingBinder();
    }

    boolean isRunning() {
        return running;
    }

    boolean isWaitingForReboot() {
        return running && state == State.REBOOTING;
    }

    boolean softRebootEnabled() {
        return context.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(ExploitRunner.PREF_SOFT_REBOOT, false);
    }

    boolean setSoftRebootEnabled(boolean enabled) {
        if (!operation.tryLock()) return false;
        try {
            context.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(ExploitRunner.PREF_SOFT_REBOOT, enabled).apply();
            setState(state);
            return true;
        } finally {
            operation.unlock();
        }
    }

    String caption() {
        if (clearFailed) return context.getString(R.string.shizuku_clear_record_failed);
        return context.getString(state == State.READY && softRebootEnabled()
                ? R.string.shizuku_ready_reboot : state.caption);
    }

    void check(Activity activity) {
        if (running || requesting) return;
        try {
            if (!Shizuku.pingBinder()) {
                openManager(activity);
            } else if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                openManager(activity);
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.shizuku_permission_title)
                            .setMessage(R.string.shizuku_permission_rationale)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setNeutralButton(R.string.shizuku_open_manager,
                                    (dialog, which) -> openManager(activity))
                            .setPositiveButton(R.string.shizuku_permission_allow,
                                    (dialog, which) -> requestPermission())
                            .show();
                } else {
                    requestPermission();
                }
            } else {
                refresh();
            }
        } catch (RuntimeException e) {
            requesting = false;
            Log.w("dfroot", "Shizuku check failed", e);
            setState(State.ERROR);
            Toast.makeText(activity, R.string.shizuku_check_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void requestPermission() {
        if (requesting || running) return;
        requesting = true;
        setState(State.REQUESTING);
        try {
            Shizuku.requestPermission(REQUEST_PERMISSION);
            main.postDelayed(permissionTimeout, 10000);
        } catch (RuntimeException e) {
            requesting = false;
            Log.w("dfroot", "Shizuku permission request failed", e);
            setState(State.ERROR);
        }
    }

    void refresh() {
        main.post(() -> {
            if (running || requesting) return;
            try {
                if (!Shizuku.pingBinder()) {
                    service = null;
                    setState(hasPendingRecovery() ? State.RESTORE_FAILED
                            : managerInstalled() ? State.STOPPED : State.MISSING);
                } else if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                    setState(State.OLD_VERSION);
                } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    setState(State.PERMISSION);
                } else if (service != null && service.asBinder().pingBinder()) {
                    checkConnectedService();
                } else if (!binding) {
                    setState(State.CHECKING);
                    binding = true;
                    Shizuku.bindUserService(serviceArgs, connection);
                    main.postDelayed(bindTimeout, 10000);
                }
            } catch (RuntimeException e) {
                binding = false;
                main.removeCallbacks(bindTimeout);
                Log.w("dfroot", "Shizuku connection failed", e);
                setState(State.ERROR);
            }
        });
    }

    private void checkConnectedService() {
        if (running || checking) return;
        checking = true;
        IInitLogService current = service;
        setState(State.CHECKING);
        io.execute(() -> {
            if (!operation.tryLock()) {
                checking = false;
                setState(current != null && current == service && current.asBinder().pingBinder()
                        ? hasPendingRecovery() ? State.RESTORE_FAILED : State.READY
                        : State.STOPPED);
                return;
            }
            State outcome;
            try {
                if (current == null || current != service || !current.asBinder().pingBinder()) {
                    outcome = State.STOPPED;
                } else {
                    if (journal.contains(InitLogService.BOOT_ID)) {
                        Bundle result = current.recover(journal.getString(InitLogService.BOOT_ID, null),
                                journal.getString(InitLogService.ORIGINAL, null));
                        if (!result.getBoolean(InitLogService.RESTORED)) throw serviceError(result);
                        clearJournal();
                    } else if (clearFailed) {
                        clearJournal();
                    }
                    Bundle snapshot = current.snapshot();
                    if (snapshot.containsKey(InitLogService.ERROR)) throw serviceError(snapshot);
                    outcome = current.asBinder().pingBinder() ? State.READY : State.STOPPED;
                }
            } catch (Exception e) {
                Log.w("dfroot", "Shizuku readiness/recovery check failed", e);
                outcome = hasPendingRecovery() ? State.RESTORE_FAILED : State.ERROR;
            } finally {
                checking = false;
                operation.unlock();
            }
            // Wake a waiting run only after the recovery/check lock has been released.
            if (current == service) setState(outcome);
        });
    }

    Result run(Context executionContext, IReporter reporter) throws Exception {
        // Serialize preparation and restoration with the independent boot entry too.
        synchronized (ExploitRunner.class) {
            awaitReady();
            if (!operation.tryLock()) throw new IOException(context.getString(R.string.shizuku_busy));
            running = true;
            setState(State.RUNNING);
            IInitLogService current = service;
            IBinder owner = new Binder();
            String bootId = null, original = null;
            boolean cleanupNeeded = false, restored = false;
            boolean softRebootWanted = false;
            SoftRebootGate rebootGate = null;
            int rootCode = -1, rebootOutcome = SoftRebootGate.PENDING;
            try {
                softRebootWanted = softRebootEnabled();
                if (hasPendingRecovery()) throw new IOException(context.getString(R.string.shizuku_restore_pending));
                if (current == null) throw new IOException(context.getString(R.string.shizuku_stopped));
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw new IOException(context.getString(R.string.shizuku_permission));
                }
                if (new File("/dev/df").exists()) {
                    restored = true;
                    reporter.report(context.getString(R.string.log_already_hooked));
                    return new Result(0, true, false, false, false);
                }
                Bundle snapshot = current.snapshot();
                if (snapshot.containsKey(InitLogService.ERROR)) throw serviceError(snapshot);
                bootId = snapshot.getString(InitLogService.BOOT_ID);
                original = snapshot.getString(InitLogService.ORIGINAL);
                if (bootId == null || original == null) throw new IOException(context.getString(R.string.shizuku_bad_record));
                rebootGate = new SoftRebootGate(executionContext.getDataDir());

                if (!InitLogService.TEMPORARY_VALUE.equals(original)
                        && !journal.edit().putString(InitLogService.BOOT_ID, bootId)
                        .putString(InitLogService.ORIGINAL, original).commit()) {
                    throw new IOException(context.getString(R.string.shizuku_record_failed));
                }
                cleanupNeeded = true;
                Bundle prepared = current.begin(owner, bootId, original);
                if (!prepared.getBoolean(InitLogService.READY)) {
                    if (prepared.getBoolean(InitLogService.RESTORED)) {
                        restored = true;
                        cleanupNeeded = false;
                        clearJournal();
                    }
                    throw serviceError(prepared);
                }
                reporter.report(context.getString(R.string.shizuku_environment_ready) + "\n");
                rootCode = ExploitRunner.run(executionContext, reporter, true);
                reporter.report(context.getString(R.string.shizuku_root_result, rootCode) + "\n");
            } finally {
                try {
                    if (cleanupNeeded) {
                        Bundle cleanup = finishSession(current, owner, bootId, original);
                        restored = cleanup.getBoolean(InitLogService.RESTORED);
                        if (restored) {
                            clearJournal();
                            reporter.report(context.getString(cleanup.getBoolean(InitLogService.EXPIRED)
                                    ? R.string.shizuku_restored_by_timeout : R.string.shizuku_restored) + "\n");
                        } else {
                            reporter.report(context.getString(R.string.shizuku_restore_failed,
                                    serviceError(cleanup).getMessage()) + "\n");
                        }
                    }
                } catch (Exception e) {
                    Log.w("dfroot", "Shizuku restore remains pending", e);
                    String message = restored ? context.getString(R.string.shizuku_record_clear_pending)
                            : context.getString(R.string.shizuku_restore_failed, e.getMessage());
                    reporter.report(message + "\n");
                } finally {
                    try {
                        rebootOutcome = finishSoftReboot(rebootGate, softRebootWanted, rootCode, restored, reporter);
                    } finally {
                        running = false;
                        operation.unlock();
                        if (hasPendingRecovery()) setState(State.RESTORE_FAILED);
                        else if (cleanupNeeded && !restored) setState(State.ERROR);
                        else if (service != null && service.asBinder().pingBinder()) setState(State.READY);
                        else setState(State.STOPPED);
                    }
                }
            }
            return new Result(rootCode, restored, hasPendingRecovery(),
                    rebootOutcome == SoftRebootGate.REQUESTED,
                    rebootOutcome == SoftRebootGate.FAILED || rebootOutcome == SoftRebootGate.TIMED_OUT);
        }
    }

    private int finishSoftReboot(SoftRebootGate gate, boolean requested, int rootCode,
                                boolean restored, IReporter reporter) {
        if (gate == null) return SoftRebootGate.PENDING;
        try {
            if (!gate.authorize(requested, rootCode, restored, hasPendingRecovery())) {
                if (requested) reporter.report(context.getString(R.string.soft_reboot_cancelled) + "\n");
                return SoftRebootGate.PENDING;
            }
            setState(State.REBOOTING);
            reporter.report(context.getString(R.string.soft_reboot_waiting) + "\n");
            int outcome = gate.awaitResult(125000);
            reporter.report(context.getString(outcome == SoftRebootGate.REQUESTED
                    ? R.string.soft_reboot_requested : R.string.soft_reboot_unconfirmed) + "\n");
            return outcome;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.w("dfroot", "Automatic soft reboot was not confirmed", e);
            reporter.report(context.getString(R.string.soft_reboot_unconfirmed) + "\n");
            return requested && rootCode == 0 ? SoftRebootGate.FAILED : SoftRebootGate.PENDING;
        } finally {
            try {
                gate.close();
            } catch (IOException e) {
                Log.w("dfroot", "Closing the soft reboot acknowledgement failed", e);
            }
        }
    }

    private Bundle finishSession(IInitLogService originalService, IBinder owner,
                                 String bootId, String original) throws Exception {
        try {
            Bundle result = originalService.finish(owner);
            if (!"missing".equals(result.getString(InitLogService.ERROR))) return result;
        } catch (Exception e) {
            if (service == null || service == originalService) throw e;
        }
        IInitLogService current = service;
        if (current == null) throw new IOException(context.getString(R.string.shizuku_stopped));
        return current.recover(bootId, original);
    }

    private void awaitReady() throws IOException, InterruptedException {
        if (isReady()) return;
        refresh();
        long deadline = SystemClock.elapsedRealtime() + 10000;
        synchronized (stateLock) {
            while (state == State.CHECKING) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                stateLock.wait(remaining);
            }
        }
        if (!isReady()) throw new IOException(caption());
    }

    private boolean hasPendingRecovery() {
        return clearFailed || journal.contains(InitLogService.BOOT_ID);
    }

    private void clearJournal() throws IOException {
        if (!hasPendingRecovery()) return;
        clearFailed = !journal.edit().clear().commit();
        if (clearFailed) throw new IOException(context.getString(R.string.shizuku_clear_record_failed));
    }

    private IOException serviceError(Bundle result) {
        String code = result.getString(InitLogService.ERROR, "io");
        int message = "busy".equals(code) ? R.string.shizuku_busy
                : "changed".equals(code) ? R.string.shizuku_value_changed
                : "record".equals(code) ? R.string.shizuku_bad_record : R.string.shizuku_operation_failed;
        String detail = result.getString(InitLogService.DETAIL, "");
        return new IOException(detail.isEmpty() ? context.getString(message)
                : context.getString(R.string.shizuku_error_detail, context.getString(message), detail));
    }

    private void onBindTimeout() {
        if (!binding) return;
        binding = false;
        try {
            Shizuku.unbindUserService(serviceArgs, connection, false);
        } catch (RuntimeException e) {
            Log.w("dfroot", "Unbinding a timed out Shizuku connection", e);
        }
        setState(State.ERROR);
    }

    private void setState(State next) {
        synchronized (stateLock) {
            state = next;
            stateLock.notifyAll();
        }
        main.post(() -> {
            for (Runnable listener : listeners) listener.run();
        });
    }

    private boolean managerInstalled() {
        try {
            context.getPackageManager().getPackageInfo(MANAGER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openManager(Activity activity) {
        Intent intent = managerInstalled()
                ? context.getPackageManager().getLaunchIntentForPackage(MANAGER_PACKAGE)
                : new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"));
        if (intent == null) {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", MANAGER_PACKAGE, null));
        }
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.shizuku_open_failed, Toast.LENGTH_LONG).show();
        }
    }
}
