package dev.luma.tv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.StatFs;
import java.util.ArrayList;

/** Measures a HealthSnapshot with Android's ordinary app APIs: no root, no shell and no new permission. Every call
 *  measures again and keeps nothing, so a refresh never passes off an older value as current. Call it off the main
 *  thread, since package and storage queries can block. */
final class HealthReader {
    private static final String HOUSE_CONTROL = "dev.housecontrol.tv";

    static HealthSnapshot read(Context context) {
        ArrayList<HealthSnapshot.Reading> readings = new ArrayList<>();
        long started = System.currentTimeMillis();
        for (HealthSnapshot.Metric metric : HealthSnapshot.Metric.values()) {
            long at = System.currentTimeMillis();
            try { readings.add(measure(context, metric, at)); }
            catch (RuntimeException | PackageManager.NameNotFoundException error) { readings.add(HealthSnapshot.failed(metric, at)); }
        }
        return new HealthSnapshot(started, readings);
    }

    @SuppressWarnings("deprecation")
    private static HealthSnapshot.Reading measure(Context context, HealthSnapshot.Metric metric, long at) throws PackageManager.NameNotFoundException {
        PackageManager packages = context.getPackageManager();
        switch (metric) {
            case MEMORY: {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
                return HealthSnapshot.memory(memory.availMem, memory.totalMem, memory.lowMemory, at);
            }
            case STORAGE: {
                StatFs disk = new StatFs(context.getFilesDir().getPath());
                // Android keeps this broadcast sticky while its storage monitor says the data partition is low.
                boolean low = context.registerReceiver(null, new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW)) != null;
                return HealthSnapshot.storage(disk.getAvailableBytes(), disk.getTotalBytes(), low, at);
            }
            case NETWORK: {
                ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkCapabilities network = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
                if (network == null) return HealthSnapshot.network(null, false, at);
                String transport = network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet"
                    : network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile data" : "Other network";
                return HealthSnapshot.network(transport, network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), at);
            }
            case LAUNCHER: {
                ResolveInfo home = packages.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
                return HealthSnapshot.launcher(home == null ? null : home.activityInfo.packageName, context.getPackageName(), at);
            }
            case LUMA: {
                PackageInfo luma = packages.getPackageInfo(context.getPackageName(), 0);
                return HealthSnapshot.luma(luma.versionName, luma.versionCode, at);
            }
            case HOUSE_CONTROL:
                try { return HealthSnapshot.houseControl(true, packages.getApplicationInfo(HOUSE_CONTROL, 0).enabled, at); }
                catch (PackageManager.NameNotFoundException missing) { return HealthSnapshot.houseControl(false, false, at); }
        }
        throw new AssertionError(metric);
    }
}
