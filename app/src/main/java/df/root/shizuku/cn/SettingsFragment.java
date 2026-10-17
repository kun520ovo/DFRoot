package df.root.shizuku.cn;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;

public class SettingsFragment extends PreferenceFragment {
    private SwitchPreference softRebootPref;
    private ShizukuCompat shizuku;
    private final Runnable updateRebootState = () -> {
        if (softRebootPref != null && shizuku != null) {
            softRebootPref.setEnabled(!shizuku.isRunning());
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        Context ctx = getActivity();
        shizuku = ShizukuCompat.get(ctx);
        SharedPreferences dePrefs = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);

        ComponentName bootReceiver = new ComponentName(ctx, BootReceiver.class);
        SwitchPreference bootPref = (SwitchPreference) findPreference("boot_start");
        bootPref.setPersistent(false);
        int state = ctx.getPackageManager().getComponentEnabledSetting(bootReceiver);
        bootPref.setChecked(state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        bootPref.setOnPreferenceChangeListener((pref, value) -> {
            ctx.getPackageManager().setComponentEnabledSetting(bootReceiver,
                    (Boolean) value ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            return true;
        });

        softRebootPref = (SwitchPreference) findPreference("soft_reboot");
        softRebootPref.setPersistent(false);
        softRebootPref.setSummary(R.string.pref_soft_reboot_shizuku_summary);
        softRebootPref.setChecked(dePrefs.getBoolean("soft_reboot", false));
        softRebootPref.setOnPreferenceChangeListener((pref, value) -> {
            return shizuku.setSoftRebootEnabled((Boolean) value);
        });

        SwitchPreference disableModulesPref = (SwitchPreference) findPreference("disable_modules");
        disableModulesPref.setPersistent(false);
        disableModulesPref.setChecked(dePrefs.getBoolean("disable_modules", false));
        disableModulesPref.setOnPreferenceChangeListener((pref, value) -> {
            dePrefs.edit().putBoolean("disable_modules", (Boolean) value).apply();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        shizuku.addListener(updateRebootState);
        updateRebootState.run();
    }

    @Override
    public void onPause() {
        shizuku.removeListener(updateRebootState);
        super.onPause();
    }
}
