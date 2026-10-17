package df.root.shizuku.cn;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActionBar;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements IReporter {

    private static final String TAG = "dfroot";
    private static final String APP_LIST_PERMISSION = "com.android.permission.GET_INSTALLED_APPS";
    private static final int APP_LIST_REQUEST = 0xdf01;
    private static final String PREF_PERMISSION_ASKED = "app_list_permission_asked";
    private static final String PREF_PERMISSION_SETTINGS = "app_list_permission_settings";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private Button btnRun;
    private Button btnShizuku;
    private ShizukuCompat mShizuku;
    private Button btnFullscreenLog;
    private ScrollView outputScroll;
    private TextView outputView;
    private Dialog mLogDialog;
    private ScrollView mFullscreenLogScroll;
    private TextView mFullscreenLogView;
    private TextView tvKernel, tvKmi, tvWarn;
    private GridLayout supportedKernelGrid;
    private Spinner spinnerSuManager;
    private Context mDeCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mExec = Executors.newSingleThreadExecutor();
    private final ExecutorService mListExec = Executors.newSingleThreadExecutor();
    private int mValidSuManagerPos = 0;
    private SuManagerAdapter mAdapter;
    private SharedPreferences mPrefs;
    private TextView tvManagerStatus;
    private boolean mNeedsAppListPermission, mPermissionInFlight, mAutoPromptAttempted;
    private boolean mPermissionRetrying;
    private boolean mPermissionRetryHint, mListLoading, mListFailed, mUpdatingAdapter, mResumed, mDestroyed;
    private boolean mRefreshAfterRun;
    private int mScanGeneration;
    private Future<?> mScanTask;
    private String mSelectedPackage;

    private final Runnable mShizukuChanged = () -> {
        if (mDestroyed) return;
        updateRunButton();
    };

    private final Runnable mPermissionWatchdog = () -> {
        if (mDestroyed || !mResumed || !mPermissionInFlight || !hasWindowFocus()) return;
        // Some OEM controllers return no callback when no dialog can be shown.
        mPermissionInFlight = false;
        mPermissionRetryHint = !hasAppListPermission();
        mPrefs.edit().putBoolean(PREF_PERMISSION_SETTINGS, mPermissionRetryHint).apply();
        refreshAppListState(false);
    };

    private final Runnable mRunStateRefresh = new Runnable() {
        @Override public void run() {
            if (mDestroyed || !mResumed) return;
            updateRunButton();
            if (RUNNING.get()) {
                mMain.postDelayed(this, 250);
            } else if (mRefreshAfterRun) {
                mRefreshAfterRun = false;
                refreshAppListState(false);
            }
        }
    };

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        mMain.post(() -> {
            if (mDestroyed) return;
            outputView.append(msg);
            outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
            final ScrollView fullscreenScroll = mFullscreenLogScroll;
            if (mFullscreenLogView != null && fullscreenScroll != null
                    && mLogDialog != null && mLogDialog.isShowing()) {
                mFullscreenLogView.append(msg);
                // Closing the dialog clears the field before this callback may run.
                fullscreenScroll.post(() -> {
                    if (!mDestroyed && fullscreenScroll.isAttachedToWindow()) {
                        fullscreenScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDeCtx = createDeviceProtectedStorageContext();
        setContentView(R.layout.activity_main);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.app_name);
            actionBar.setSubtitle(R.string.main_repository_attribution);
            // Use the same native title/subtitle layout and typography as upstream.
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setDisplayShowCustomEnabled(false);
            // The native title ID has no public SDK constant; keep the fallback for ROM differences.
            @SuppressLint("DiscouragedApi")
            int titleId = getResources().getIdentifier("action_bar_title", "id", "android");
            View title = titleId == 0 ? null : findViewById(titleId);
            View header;
            if (title != null && title.getParent() instanceof View) {
                header = (View) title.getParent();
            } else {
                // Keep navigation available on ROMs with a different ActionBar layout.
                actionBar.setDisplayShowTitleEnabled(false);
                actionBar.setDisplayShowCustomEnabled(true);
                actionBar.setCustomView(R.layout.action_bar_main);
                header = actionBar.getCustomView();
            }
            header.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            header.setForeground(null);
            header.setFocusable(true);
            header.setContentDescription(getString(R.string.main_repository_description));
            header.setOnClickListener(v -> openProjectRepository());
        }

        btnRun = findViewById(R.id.btnRun);
        btnShizuku = findViewById(R.id.btnShizuku);
        btnFullscreenLog = findViewById(R.id.btnFullscreenLog);
        outputScroll = findViewById(R.id.outputScroll);
        outputView = findViewById(R.id.outputView);
        spinnerSuManager = findViewById(R.id.spinnerSuManager);
        tvKernel    = findViewById(R.id.tvKernel);
        tvKmi       = findViewById(R.id.tvKmi);
        supportedKernelGrid = findViewById(R.id.supportedKernelGrid);
        supportedKernelGrid.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) updateSupportedKernelColumns();
        });
        tvWarn      = findViewById(R.id.tvWarn);
        tvManagerStatus = findViewById(R.id.tvManagerStatus);
        mNeedsAppListPermission = needsAppListPermission();
        if (savedInstanceState != null) {
            mPermissionInFlight = savedInstanceState.getBoolean("permission_in_flight", false);
            mAutoPromptAttempted = savedInstanceState.getBoolean("permission_attempted", false);
            mPermissionRetryHint = savedInstanceState.getBoolean("permission_retry_hint", false);
            mPermissionRetrying = savedInstanceState.getBoolean("permission_retrying", false);
        }

        mPrefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        mShizuku = ShizukuCompat.get(this);

        mAdapter = new SuManagerAdapter(this, new ArrayList<>());
        mAdapter.add(new SuManagerEntry(null, getString(R.string.main_select_manager), null));
        spinnerSuManager.setAdapter(mAdapter);
        spinnerSuManager.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (mUpdatingAdapter || mListLoading || RUNNING.get()) return;
                SuManagerEntry e = mAdapter.getItem(pos);
                mSelectedPackage = e == null ? null : e.packageName;
                mValidSuManagerPos = mSelectedPackage == null ? 0 : pos;
                if (mSelectedPackage != null) {
                    mPrefs.edit().putString(ExploitRunner.PREF_SU_MANAGER, mSelectedPackage).apply();
                    tvManagerStatus.setVisibility(View.GONE);
                } else if (hasAppListPermission() && mAdapter.getCount() > 1) {
                    showManagerStatus(R.string.manager_choose);
                }
                updateRunButton();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnFullscreenLog.setOnClickListener(v -> showFullscreenLog());
        btnShizuku.setOnClickListener(v -> mShizuku.check(this));
        mShizuku.addListener(mShizukuChanged);
        updateShizukuButton();
        showKernelPanel();
        showSupportInfo();
        final String initialSupportText = outputView.getText().toString();
        mListExec.execute(() -> {
            ExploitRunner.refreshKernelVersion();
            mMain.post(() -> {
                if (mDestroyed) return;
                showKernelPanel();
                if (initialSupportText.contentEquals(outputView.getText())) showSupportInfo();
                updateRunButton();
            });
        });

        btnRun.setOnClickListener(v -> {
            if (mDestroyed || !mResumed || mListLoading || mPermissionInFlight
                    || RUNNING.get() || mShizuku.isRunning()) return;
            if (shouldOpenPermissionSettings()) {
                openAppListPermissionSettings();
                return;
            }
            if (!hasAppListPermission() || mListFailed || mAdapter.getCount() <= 1) {
                requestAppListPermission();
                return;
            }
            if (!mShizuku.isReady()
                    || mValidSuManagerPos < 1 || !ExploitRunner.isSupported()
                    || new File("/dev/df").exists() || !RUNNING.compareAndSet(false, true)) return;
            mRefreshAfterRun = true;
            updateRunButton();
            outputView.setText("");
            mMain.post(mRunStateRefresh);
            mExec.execute(this::runExploit);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        refreshAppListState(true);
        mShizuku.refresh();
        if (mPermissionInFlight) schedulePermissionWatchdog();
        if (RUNNING.get()) {
            mRefreshAfterRun = true;
            mMain.post(mRunStateRefresh);
        }
    }

    @Override
    protected void onPause() {
        mResumed = false;
        mMain.removeCallbacks(mPermissionWatchdog);
        mMain.removeCallbacks(mRunStateRefresh);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("permission_in_flight", mPermissionInFlight);
        state.putBoolean("permission_attempted", mAutoPromptAttempted);
        state.putBoolean("permission_retry_hint", mPermissionRetryHint);
        state.putBoolean("permission_retrying", mPermissionRetrying);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        if (mShizuku != null) mShizuku.removeListener(mShizukuChanged);
        if (mLogDialog != null) mLogDialog.dismiss();
        cancelManagerScan();
        mListExec.shutdownNow();
        // A running native task must be allowed to finish its own cleanup.
        mExec.shutdown();
        mMain.removeCallbacks(mPermissionWatchdog);
        mMain.removeCallbacks(mRunStateRefresh);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mPermissionInFlight) schedulePermissionWatchdog();
    }

    private void openProjectRepository() {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.main_repository_url)));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        // Resolve a generic web link locally so GitHub's app link cannot replace
        // the default browser. Some OEM browsers have no CATEGORY_APP_BROWSER entry.
        try {
            Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            ResolveInfo browser = getPackageManager().resolveActivity(probe,
                    PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_RESOLVED_FILTER);
            if (browser != null && browser.activityInfo != null && browser.filter != null
                    && browser.filter.countDataAuthorities() == 0) {
                startActivity(new Intent(intent).setPackage(browser.activityInfo.packageName));
                return;
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Default browser unavailable; trying the system web handler", e);
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Opening project repository failed", e);
            Toast.makeText(this, R.string.main_repository_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showManagerStatus(int messageRes) {
        tvManagerStatus.setText(messageRes);
        tvManagerStatus.setVisibility(View.VISIBLE);
    }

    private boolean needsAppListPermission() {
        try {
            PermissionInfo permission = getPackageManager().getPermissionInfo(APP_LIST_PERMISSION, 0);
            return (permission.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                    == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Standard Android does not define this HONOR/Huawei runtime permission.
            return false;
        }
    }

    private boolean hasAppListPermission() {
        return !mNeedsAppListPermission
                || checkSelfPermission(APP_LIST_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean shouldOpenPermissionSettings() {
        return !hasAppListPermission() && mPrefs.getBoolean(PREF_PERMISSION_SETTINGS, false);
    }

    private void openAppListPermissionSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Opening app permission settings failed", e);
            Toast.makeText(this, R.string.manager_settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void schedulePermissionWatchdog() {
        mMain.removeCallbacks(mPermissionWatchdog);
        mMain.postDelayed(mPermissionWatchdog, 3000);
    }

    private void requestAppListPermission() {
        if (mDestroyed || !mResumed || mPermissionInFlight
                || RUNNING.get() || mShizuku.isRunning()) return;
        if (hasAppListPermission()) {
            refreshAppListState(false);
            return;
        }
        if (shouldOpenPermissionSettings()) return;
        mPermissionRetrying = mPrefs.getBoolean(PREF_PERMISSION_ASKED, false);
        mPrefs.edit().putBoolean(PREF_PERMISSION_ASKED, true).apply();
        mAutoPromptAttempted = true;
        mPermissionInFlight = true;
        mPermissionRetryHint = false;
        refreshAppListState(false);
        try {
            requestPermissions(new String[]{APP_LIST_PERMISSION}, APP_LIST_REQUEST);
            schedulePermissionWatchdog();
        } catch (RuntimeException e) {
            Log.w(TAG, "App list permission dialog unavailable", e);
            mPermissionInFlight = false;
            mPermissionRetryHint = true;
            mPrefs.edit().putBoolean(PREF_PERMISSION_SETTINGS, true).apply();
            refreshAppListState(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != APP_LIST_REQUEST || mDestroyed) return;
        mPermissionInFlight = false;
        mMain.removeCallbacks(mPermissionWatchdog);
        mPermissionRetryHint = !hasAppListPermission();
        // Some OEM permissions report no rationale after the first denial;
        // allow one retry before treating that result as a settings-only path.
        boolean needsSettings = mPermissionRetryHint && mPermissionRetrying
                && results.length > 0 && results[0] == PackageManager.PERMISSION_DENIED
                && !shouldShowRequestPermissionRationale(APP_LIST_PERMISSION);
        mPrefs.edit().putBoolean(PREF_PERMISSION_SETTINGS, needsSettings).apply();
        if (mResumed) refreshAppListState(false);
    }

    private void cancelManagerScan() {
        ++mScanGeneration;
        if (mScanTask != null) mScanTask.cancel(true);
        mScanTask = null;
        mListLoading = false;
    }

    private void refreshAppListState(boolean allowAutomaticPrompt) {
        if (mDestroyed) return;
        if (!hasAppListPermission()) {
            cancelManagerScan();
            mSelectedPackage = null;
            mValidSuManagerPos = 0;
            mUpdatingAdapter = true;
            mAdapter.setNotifyOnChange(false);
            mAdapter.clear();
            mAdapter.add(new SuManagerEntry(null, getString(R.string.main_select_manager), null));
            mAdapter.notifyDataSetChanged();
            spinnerSuManager.setSelection(0);
            mUpdatingAdapter = false;
            showManagerStatus(mPermissionInFlight ? R.string.manager_permission_waiting
                    : shouldOpenPermissionSettings() ? R.string.manager_permission_settings_hint
                    : mPermissionRetryHint ? R.string.manager_permission_retry_hint
                    : R.string.manager_permission_needed);
            updateRunButton();
            if (allowAutomaticPrompt && !mAutoPromptAttempted && !mPermissionInFlight
                    && !mPrefs.getBoolean(PREF_PERMISSION_ASKED, false) && !mPermissionRetryHint
                    && !shouldOpenPermissionSettings()) {
                mAutoPromptAttempted = true;
                mMain.post(this::requestAppListPermission);
            }
            return;
        }
        mPermissionInFlight = false;
        mPermissionRetryHint = false;
        if (mPrefs.getBoolean(PREF_PERMISSION_SETTINGS, false)) {
            mPrefs.edit().putBoolean(PREF_PERMISSION_SETTINGS, false).apply();
        }
        mMain.removeCallbacks(mPermissionWatchdog);
        if (RUNNING.get()) {
            mRefreshAfterRun = true;
            updateRunButton();
        } else {
            refreshSuManagerList();
        }
    }

    private void refreshSuManagerList() {
        cancelManagerScan();
        final int generation = mScanGeneration;
        final String currentPackage = mSelectedPackage;
        final String savedPackage = mPrefs.getString(ExploitRunner.PREF_SU_MANAGER, null);
        mListLoading = true;
        mListFailed = false;
        showManagerStatus(R.string.manager_loading);
        updateRunButton();
        mScanTask = mListExec.submit(() -> {
            List<SuManagerEntry> entries = new ArrayList<>();
            try {
                if (!hasAppListPermission()) {
                    mMain.post(() -> {
                        if (!mDestroyed && generation == mScanGeneration) refreshAppListState(false);
                    });
                    return;
                }
                PackageManager pm = getPackageManager();
                for (ApplicationInfo app : pm.getInstalledApplications(0)) {
                    if (Thread.currentThread().isInterrupted()) return;
                    if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || !ExploitRunner.hasKsud(app)) continue;
                    CharSequence label;
                    Drawable icon;
                    try {
                        label = pm.getApplicationLabel(app);
                        icon = pm.getApplicationIcon(app);
                    } catch (RuntimeException unavailableResources) {
                        label = app.packageName;
                        icon = pm.getDefaultActivityIcon();
                    }
                    if (label == null) label = app.packageName;
                    entries.add(new SuManagerEntry(app.packageName, label, icon));
                }
                entries.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
                mMain.post(() -> {
                    if (mDestroyed || generation != mScanGeneration) return;
                    if (!hasAppListPermission()) {
                        refreshAppListState(false);
                        return;
                    }
                    applyManagerList(entries, currentPackage, savedPackage);
                });
            } catch (RuntimeException e) {
                mMain.post(() -> {
                    if (mDestroyed || generation != mScanGeneration) return;
                    mListLoading = false;
                    mListFailed = true;
                    if (!hasAppListPermission()) {
                        refreshAppListState(false);
                        return;
                    }
                    mValidSuManagerPos = 0;
                    mSelectedPackage = null;
                    showManagerStatus(R.string.manager_scan_failed);
                    Log.w(TAG, "Reading app list failed", e);
                    updateRunButton();
                });
            }
        });
    }

    private static int findManager(List<SuManagerEntry> entries, String packageName) {
        if (packageName == null) return -1;
        for (int i = 0; i < entries.size(); i++) {
            if (packageName.equals(entries.get(i).packageName)) return i;
        }
        return -1;
    }

    private void applyManagerList(List<SuManagerEntry> entries, String currentPackage, String savedPackage) {
        // Match identities from the previous list, never positions in the new list.
        int selected = findManager(entries, currentPackage);
        if (selected < 0) selected = findManager(entries, savedPackage);
        if (selected < 0 && entries.size() == 1) selected = 0;
        int count = entries.size();
        entries.add(0, new SuManagerEntry(null, getString(R.string.main_select_manager), null));
        mUpdatingAdapter = true;
        mAdapter.setNotifyOnChange(false);
        mAdapter.clear();
        mAdapter.addAll(entries);
        mAdapter.notifyDataSetChanged();
        mValidSuManagerPos = selected < 0 ? 0 : selected + 1;
        SuManagerEntry entry = entries.get(mValidSuManagerPos);
        mSelectedPackage = entry.packageName;
        spinnerSuManager.setSelection(mValidSuManagerPos);
        mUpdatingAdapter = false;
        mListLoading = false;
        mListFailed = false;
        mScanTask = null;
        if (mSelectedPackage != null) {
            mPrefs.edit().putString(ExploitRunner.PREF_SU_MANAGER, mSelectedPackage).apply();
            tvManagerStatus.setVisibility(View.GONE);
        } else {
            // A denied or filtered scan must not erase the user's saved choice.
            showManagerStatus(count == 0 ? R.string.manager_not_found : R.string.manager_choose);
        }
        updateRunButton();
    }

    private void showFullscreenLog() {
        if (mDestroyed || (mLogDialog != null && mLogDialog.isShowing())) return;

        final Dialog dialog = new Dialog(this, R.style.Theme_DirtyFrag_Log);
        dialog.setContentView(R.layout.dialog_log);
        final ScrollView scroll = dialog.findViewById(R.id.fullscreenLogScroll);
        final TextView text = dialog.findViewById(R.id.fullscreenLogView);
        text.setText(outputView.getText());
        dialog.findViewById(R.id.btnCloseLog).setOnClickListener(view -> dialog.dismiss());

        mLogDialog = dialog;
        mFullscreenLogScroll = scroll;
        mFullscreenLogView = text;
        dialog.setOnDismissListener(ignored -> {
            if (mLogDialog == dialog) {
                mLogDialog = null;
                mFullscreenLogScroll = null;
                mFullscreenLogView = null;
            }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Paint the always-visible kernel panel at the top of the screen. */
    private void showKernelPanel() {
        String rel = System.getProperty("os.version", "");
        String cur = ExploitRunner.currentKmi();
        boolean ok = cur != null;

        tvKernel.setText(rel.isEmpty() ? getString(R.string.kernel_unknown) : rel);
        tvKmi.setText(ok ? cur : getString(R.string.panel_unsupported));

        // Green when the device's KMI is covered, red when it is not.
        int okColor = getColor(R.color.dfroot_success);
        int badColor = getColor(R.color.dfroot_error);
        tvKernel.setTextColor(ok ? outputView.getCurrentTextColor() : badColor);
        tvKmi.setTextColor(ok ? okColor : badColor);

        supportedKernelGrid.removeAllViews();
        for (String name : ExploitRunner.supportedKmiList().split("\n")) {
            TextView item = (TextView) getLayoutInflater().inflate(
                    R.layout.item_supported_kernel, supportedKernelGrid, false);
            if (name.equals(cur)) {
                item.setText(getString(R.string.panel_kernel_current, name));
                item.setTextColor(okColor);
                item.setContentDescription(getString(R.string.panel_kernel_current_description, name));
            } else {
                item.setText(name);
            }
            supportedKernelGrid.addView(item);
        }

        tvWarn.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    private void updateSupportedKernelColumns() {
        int cellWidth = 1;
        for (int i = 0; i < supportedKernelGrid.getChildCount(); i++) {
            TextView item = (TextView) supportedKernelGrid.getChildAt(i);
            GridLayout.LayoutParams params = (GridLayout.LayoutParams) item.getLayoutParams();
            int width = (int) Math.ceil(item.getPaint().measureText(item.getText().toString()))
                    + item.getPaddingLeft() + item.getPaddingRight()
                    + params.leftMargin + params.rightMargin;
            cellWidth = Math.max(cellWidth, width);
        }
        int availableWidth = supportedKernelGrid.getWidth()
                - supportedKernelGrid.getPaddingLeft() - supportedKernelGrid.getPaddingRight();
        int columns = availableWidth >= cellWidth * 2 ? 2 : 1;
        if (columns == supportedKernelGrid.getColumnCount()) return;

        // GridLayout retains assigned cells after layout; clear them before changing columns.
        for (int i = 0; i < supportedKernelGrid.getChildCount(); i++) {
            View item = supportedKernelGrid.getChildAt(i);
            GridLayout.LayoutParams params = (GridLayout.LayoutParams) item.getLayoutParams();
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            item.setLayoutParams(params);
        }
        supportedKernelGrid.setColumnCount(columns);
    }

    /** Print the supported kernel list into the log area. */
    private void showSupportInfo() {
        outputView.setText(ExploitRunner.supportInfo(this));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateShizukuButton() {
        String title = getString(R.string.shizuku_check);
        String label = title + "\n" + mShizuku.caption();
        if (!label.contentEquals(btnShizuku.getText())) {
            SpannableString text = new SpannableString(label);
            text.setSpan(new RelativeSizeSpan(12f / 17f), title.length() + 1,
                    text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnShizuku.setText(text);
        }
        boolean enabled = !RUNNING.get() && !mShizuku.isRunning();
        btnShizuku.setEnabled(enabled);
        btnShizuku.setAlpha(enabled ? 1f : 0.5f);
    }

    private void updateRunButton() {
        // Permission requests and list retries are independent of Root readiness.
        // Actual Root execution still requires a supported kernel and manager.
        boolean supported = ExploitRunner.isSupported();

        // Also require a SU manager AND that the exploit hasn't already run in
        // this boot (/dev/df is the libc++ hook's marker).
        boolean granted = hasAppListPermission();
        boolean running = RUNNING.get() || mShizuku.isRunning();
        boolean shizukuReady = mShizuku.isReady();
        boolean retryList = granted && (mListFailed || mAdapter.getCount() <= 1);
        boolean ready = supported && granted && !retryList && mValidSuManagerPos >= 1
                && shizukuReady && !new File("/dev/df").exists();

        btnRun.setEnabled(mResumed && !mDestroyed && !running && !mListLoading
                && !mPermissionInFlight && (!granted || retryList || ready));
        btnRun.setText(running ? (mShizuku.isWaitingForReboot()
                    ? R.string.main_waiting_soft_reboot : R.string.main_running)
                : !granted ? (mPermissionInFlight ? R.string.manager_permission_requesting
                    : shouldOpenPermissionSettings() ? R.string.manager_permission_settings
                    : R.string.manager_permission_required_button)
                : mListLoading ? R.string.manager_loading
                : retryList ? R.string.manager_retry
                : !supported ? R.string.main_launch_root_blocked
                : !shizukuReady ? R.string.main_shizuku_required : R.string.main_launch_root);
        spinnerSuManager.setEnabled(granted && !mListLoading && !mListFailed
                && !running && mAdapter.getCount() > 1);
        updateShizukuButton();
    }

    private void runExploit() {
        try {
            // Second line of defence: the button is already disabled for an
            // unsupported kernel, but never trust a single gate before handing
            // control to ring-0 code.
            if (!ExploitRunner.isSupported()) {
                report(getString(R.string.warn_unsupported) + "\n");
                return;
            }
            ShizukuCompat.Result result = mShizuku.run(mDeCtx, this);
            int rc = result.rootCode;
            String msg = !result.settingsRestored
                    ? getString(R.string.result_settings_restore_pending)
                    : result.recoveryPending ? getString(R.string.result_recovery_record_pending)
                    : result.rebootFailed ? getString(R.string.result_soft_reboot_failed)
                    : result.rebootRequested ? getString(R.string.result_soft_reboot_requested)
                    : rc == 0 ? getString(R.string.result_success)
                    : rc == 1 ? getString(R.string.result_failed_ksud)
                    : rc == 2 ? getString(R.string.result_failed_logs)
                    : getString(R.string.result_failed_patch);
            mMain.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            report(getString(R.string.log_exception) + e + "\n");
        } finally {
            RUNNING.set(false);
            mMain.post(() -> {
                if (mDestroyed) return;
                mRefreshAfterRun = false;
                if (mResumed) refreshAppListState(false);
            });
        }
    }

    private static class SuManagerEntry {
        final String packageName;
        final CharSequence label;
        final Drawable icon;

        SuManagerEntry(String pkg, CharSequence label, Drawable icon) {
            this.packageName = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private static class SuManagerAdapter extends ArrayAdapter<SuManagerEntry> {
        SuManagerAdapter(Context ctx, List<SuManagerEntry> items) {
            super(ctx, R.layout.item_su_manager, items);
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null || v.getTag() != Boolean.FALSE)
                v = LayoutInflater.from(getContext()).inflate(R.layout.item_su_manager_closed, parent, false);
            v.setTag(Boolean.FALSE);
            return bindView(pos, v);
        }

        @Override
        public View getDropDownView(int pos, View v, ViewGroup parent) {
            if (v == null || v.getTag() != Boolean.TRUE)
                v = LayoutInflater.from(getContext()).inflate(R.layout.item_su_manager, parent, false);
            v.setTag(Boolean.TRUE);
            return bindView(pos, v);
        }

        @Override
        public boolean isEnabled(int pos) {
            return getItem(pos).packageName != null;
        }

        private View bindView(int pos, View v) {
            SuManagerEntry e = getItem(pos);
            TextView label = (TextView) v;
            label.setText(e.label);
            Drawable icon = e.packageName == null ? null : e.icon;
            if (icon != null) {
                // Selected and dropdown rows should own separate drawable callbacks.
                Drawable.ConstantState state = icon.getConstantState();
                if (state != null) icon = state.newDrawable(getContext().getResources()).mutate();
                int size = Math.round(32 * getContext().getResources().getDisplayMetrics().density);
                int width = icon.getIntrinsicWidth(), height = icon.getIntrinsicHeight();
                if (width > 0 && height > 0 && width != height) {
                    // Preserve ImageView's aspect ratio inside the fixed icon slot.
                    float scale = (float) size / Math.max(width, height);
                    int scaledWidth = Math.round(width * scale);
                    int scaledHeight = Math.round(height * scale);
                    int left = (size - scaledWidth) / 2;
                    int top = (size - scaledHeight) / 2;
                    icon = new InsetDrawable(icon, left, top,
                            size - scaledWidth - left, size - scaledHeight - top);
                }
                icon.setBounds(0, 0, size, size);
            }
            // Passing null also clears an icon left by a recycled manager row.
            label.setCompoundDrawablesRelative(icon, null, null, null);
            return v;
        }
    }
}
