# Shizuku loads this service by class name in another process and invokes the
# Context constructor reflectively; there is no direct Java constructor call.
-keep class df.root.shizuku.cn.InitLogService {
    public <init>(android.content.Context);
}

# Android recreates this Fragment through its public no-argument constructor
# when restoring the settings screen.
-keep class df.root.shizuku.cn.SettingsFragment {
    public <init>();
}
