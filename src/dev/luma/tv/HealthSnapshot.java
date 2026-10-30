package dev.luma.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** One health check of the TV: memory, storage, network, the Home app, installed Luma and House Control. A reading
 *  is healthy or degraded only when Android itself says so. Luma never invents a threshold. No Android classes,
 *  so tools/HealthSnapshotTest.java checks it on a computer. HealthReader fills it on the TV. */
final class HealthSnapshot {
    /** HEALTHY and DEGRADED come from an Android signal. UNAVAILABLE means Luma could not measure it, or an optional
     *  part is absent. UNKNOWN means Luma has a value but Android gives no signal to judge it by. */
    enum Status { HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN }

    enum Metric {
        MEMORY("Memory", "Android memory service"),
        STORAGE("Storage", "Android storage monitor"),
        NETWORK("Network", "Android connectivity service"),
        LAUNCHER("Home app", "Android package manager"),
        LUMA("Luma", "Luma's package details"),
        HOUSE_CONTROL("House Control", "Android package manager");
        final String label;
        final String source;
        Metric(String label, String source) { this.label = label; this.source = source; }
    }

    static final class Reading {
        final Metric metric;
        final Status status;
        final String value;
        final String explanation;
        final String source;
        final long at;
        private Reading(Metric metric, Status status, String value, String explanation, long at) {
            this.metric = metric;
            this.status = status;
            this.value = value;
            this.explanation = explanation;
            this.source = metric.source;
            this.at = at;
        }
    }

    /** When this check started. Each reading keeps the time it was measured. */
    final long at;
    final List<Reading> readings;

    HealthSnapshot(long at, List<Reading> readings) {
        this.at = at;
        this.readings = Collections.unmodifiableList(new ArrayList<>(readings));
    }

    static Reading memory(long available, long total, boolean low, long at) {
        String value = size(available) + " free of " + size(total);
        return low ? new Reading(Metric.MEMORY, Status.DEGRADED, value, "Android reports low memory. Apps may close or reload.", at)
            : new Reading(Metric.MEMORY, Status.HEALTHY, value, "Android does not report low memory.", at);
    }

    static Reading storage(long free, long total, boolean low, long at) {
        String value = size(free) + " free of " + size(total);
        return low ? new Reading(Metric.STORAGE, Status.DEGRADED, value, "Android reports low storage. Apps may fail to install or update.", at)
            : new Reading(Metric.STORAGE, Status.HEALTHY, value, "Android does not report low storage.", at);
    }

    /** transport: "Ethernet", "Wi-Fi" and so on, or null with no active network. */
    static Reading network(String transport, boolean validated, long at) {
        if (transport == null) return new Reading(Metric.NETWORK, Status.DEGRADED, "Not connected", "The TV has no active network connection.", at);
        return validated ? new Reading(Metric.NETWORK, Status.HEALTHY, transport, "Connected. Android confirmed internet access.", at)
            : new Reading(Metric.NETWORK, Status.DEGRADED, transport + ", no internet", "Connected, but Android has not confirmed internet access.", at);
    }

    /** home: the package Android opens for the Home key, "android" for its chooser, or null when none resolves. */
    static Reading launcher(String home, String self, long at) {
        if (home == null) return new Reading(Metric.LAUNCHER, Status.UNAVAILABLE, "Not found", "Android did not name a Home app.", at);
        if (home.equals(self)) return new Reading(Metric.LAUNCHER, Status.HEALTHY, "Luma", "Luma opens when you press Home.", at);
        if (home.equals("android")) return new Reading(Metric.LAUNCHER, Status.DEGRADED, "Not set", "No default Home app is set, so Android asks which one to open.", at);
        return new Reading(Metric.LAUNCHER, Status.DEGRADED, home, "Another app opens when you press Home.", at);
    }

    static Reading luma(String versionName, long versionCode, long at) {
        return new Reading(Metric.LUMA, Status.UNKNOWN, versionName + " (" + versionCode + ")", "The installed version. Luma does not check for updates.", at);
    }

    static Reading houseControl(boolean installed, boolean enabled, long at) {
        if (!installed) return new Reading(Metric.HOUSE_CONTROL, Status.UNAVAILABLE, "Not installed", "House Control is optional. Luma works without it.", at);
        if (!enabled) return new Reading(Metric.HOUSE_CONTROL, Status.UNAVAILABLE, "Turned off", "House Control is installed but turned off in the TV's settings.", at);
        return new Reading(Metric.HOUSE_CONTROL, Status.UNKNOWN, "Installed", "House Control is installed. This does not check that it can reach your home.", at);
    }

    /** Android refused or failed to answer. The cause stays out of the report, since it can carry private detail. */
    static Reading failed(Metric metric, long at) {
        return new Reading(metric, Status.UNAVAILABLE, "Not measured", "Android did not answer, so Luma cannot measure this right now.", at);
    }

    /** Decimal units, as Android's own storage settings show them. */
    private static String size(long bytes) {
        return bytes >= 1_000_000_000L ? String.format(Locale.US, "%.1f GB", bytes / 1e9) : bytes / 1_000_000L + " MB";
    }
}
