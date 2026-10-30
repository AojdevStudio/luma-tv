package dev.luma.tv;

import java.util.ArrayList;
import java.util.Arrays;

public final class HealthSnapshotTest {
    static int failures;
    static final HealthSnapshot.Status HEALTHY = HealthSnapshot.Status.HEALTHY, DEGRADED = HealthSnapshot.Status.DEGRADED,
        UNAVAILABLE = HealthSnapshot.Status.UNAVAILABLE, UNKNOWN = HealthSnapshot.Status.UNKNOWN;
    static void expect(boolean ok, String message) {
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + message);
    }
    static boolean explained(HealthSnapshot.Reading reading) { return !reading.explanation.isEmpty() && !reading.value.isEmpty(); }
    static void check(String name, Runnable test) {
        try { test.run(); } catch (RuntimeException error) { expect(false, name + " threw " + error); }
    }
    public static void main(String[] args) {
        long GB = 1_000_000_000L;
        check("memory", () -> {
            HealthSnapshot.Reading fine = HealthSnapshot.memory(1_500_000_000L, 2 * GB, false, 10L);
            HealthSnapshot.Reading low = HealthSnapshot.memory(100_000_000L, 2 * GB, true, 10L);
            expect(fine.status == HEALTHY && low.status == DEGRADED, "memory follows Android's low-memory flag, not a Luma threshold");
            expect("1.5 GB free of 2.0 GB".equals(fine.value) && "100 MB free of 2.0 GB".equals(low.value) && explained(low), "memory shows free of total in plain units");
        });
        check("storage", () -> {
            HealthSnapshot.Reading fine = HealthSnapshot.storage(GB / 2, 8 * GB, false, 10L);
            HealthSnapshot.Reading low = HealthSnapshot.storage(7 * GB, 8 * GB, true, 10L);
            expect(fine.status == HEALTHY && low.status == DEGRADED && explained(low), "storage follows Android's low-storage signal, not free space");
        });
        check("network", () -> {
            expect(HealthSnapshot.network(null, false, 10L).status == DEGRADED, "no active network is degraded");
            HealthSnapshot.Reading unvalidated = HealthSnapshot.network("Ethernet", false, 10L);
            expect(unvalidated.status == DEGRADED && unvalidated.value.contains("Ethernet"), "a connection Android has not validated is not called healthy");
            expect(HealthSnapshot.network("Wi-Fi", true, 10L).status == HEALTHY, "a validated connection is healthy");
        });
        check("launcher", () -> {
            expect(HealthSnapshot.launcher("dev.luma.tv", "dev.luma.tv", 10L).status == HEALTHY, "Luma as the Home app is healthy");
            HealthSnapshot.Reading other = HealthSnapshot.launcher("com.google.android.tvlauncher", "dev.luma.tv", 10L);
            expect(other.status == DEGRADED && other.value.contains("com.google.android.tvlauncher"), "another Home app is degraded and named");
            expect(HealthSnapshot.launcher("android", "dev.luma.tv", 10L).status == DEGRADED, "no default Home app is degraded");
            expect(HealthSnapshot.launcher(null, "dev.luma.tv", 10L).status == UNAVAILABLE, "an unresolved Home intent is unavailable");
        });
        check("luma", () -> {
            HealthSnapshot.Reading version = HealthSnapshot.luma("2.4", 26, 10L);
            expect(version.status == UNKNOWN && version.value.contains("2.4") && explained(version), "installed version is reported but never called up to date");
        });
        check("house", () -> {
            HealthSnapshot.Reading missing = HealthSnapshot.houseControl(false, false, 10L);
            HealthSnapshot.Reading off = HealthSnapshot.houseControl(true, false, 10L);
            HealthSnapshot.Reading on = HealthSnapshot.houseControl(true, true, 10L);
            expect(missing.status == UNAVAILABLE && off.status == UNAVAILABLE && explained(missing), "missing or disabled House Control is unavailable, not degraded");
            expect(on.status == UNKNOWN, "installed House Control is not claimed to be working");
        });
        check("failed", () -> {
            HealthSnapshot.Reading failed = HealthSnapshot.failed(HealthSnapshot.Metric.NETWORK, 42L);
            expect(failed.status == UNAVAILABLE && failed.metric == HealthSnapshot.Metric.NETWORK && explained(failed), "an Android failure becomes an explained unavailable reading");
        });
        check("source", () -> {
            HealthSnapshot.Reading reading = HealthSnapshot.storage(GB, 8 * GB, false, 77L);
            expect(reading.at == 77L && reading.source.equals(HealthSnapshot.Metric.STORAGE.source), "each reading keeps its own source and measurement time");
        });
        check("snapshot", () -> {
            ArrayList<HealthSnapshot.Reading> readings = new ArrayList<>(Arrays.asList(HealthSnapshot.luma("2.4", 26, 5L)));
            HealthSnapshot snapshot = new HealthSnapshot(5L, readings);
            readings.add(HealthSnapshot.failed(HealthSnapshot.Metric.MEMORY, 6L));
            boolean sealed;
            try { snapshot.readings.clear(); sealed = false; } catch (UnsupportedOperationException expected) { sealed = true; }
            expect(snapshot.readings.size() == 1 && sealed, "a snapshot cannot change after it is taken");
        });
        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
