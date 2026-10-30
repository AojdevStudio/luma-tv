package dev.luma.tv;

import java.util.ArrayList;
import java.util.Arrays;

/** Checks the Capabilities panel's sections and Health refresh rules without Android classes. Run from the repository root:
 *    javac --release 8 -d build/test src/dev/luma/tv/CapabilityPanel.java src/dev/luma/tv/HealthSnapshot.java tools/CapabilityPanelTest.java && java -cp build/test dev.luma.tv.CapabilityPanelTest */
public final class CapabilityPanelTest {
    static int failures;
    static void expect(boolean ok, String message) {
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + message);
    }
    static HealthSnapshot snapshot(long at) {
        long GB = 1_000_000_000L;
        return new HealthSnapshot(at, Arrays.asList(
            HealthSnapshot.memory(GB, 2 * GB, false, at),
            HealthSnapshot.storage(GB, 8 * GB, true, at),
            HealthSnapshot.network("Wi-Fi", true, at),
            HealthSnapshot.launcher("dev.luma.tv", "dev.luma.tv", at),
            HealthSnapshot.luma("2.4", 26, at),
            HealthSnapshot.houseControl(false, false, at)));
    }
    public static void main(String[] args) {
        expect(CapabilityPanel.sections(false).equals(Arrays.asList(CapabilityPanel.Section.ACTIONS, CapabilityPanel.Section.ASK, CapabilityPanel.Section.HEALTH)),
            "without House Control the panel has no Scenes section");
        expect(CapabilityPanel.sections(true).equals(Arrays.asList(CapabilityPanel.Section.ACTIONS, CapabilityPanel.Section.ASK, CapabilityPanel.Section.SCENES, CapabilityPanel.Section.HEALTH)),
            "with House Control the panel adds Scenes");

        CapabilityPanel.Health health = new CapabilityPanel.Health();
        int first = health.refresh();
        expect(health.shown() == null && health.stale(), "the first check shows no values while it measures");
        expect(health.accept(first, snapshot(100L)) && health.shown().at == 100L && !health.stale(), "the newest check replaces the report");
        int older = health.refresh();
        expect(health.stale() && health.shown().at == 100L, "refresh marks the shown values stale at once and keeps their time");
        int newer = health.refresh();
        expect(!health.accept(older, snapshot(200L)) && health.shown().at == 100L && health.stale(), "an older refresh that answers late is ignored");
        expect(health.accept(newer, snapshot(300L)) && health.shown().at == 300L && !health.stale(), "the newest refresh lands");
        int late = health.refresh();
        health.close();
        expect(!health.accept(late, snapshot(400L)) && health.shown().at == 300L, "nothing lands after the panel closes");

        ArrayList<String> lines = new ArrayList<>();
        for (HealthSnapshot.Reading reading : snapshot(500L).readings) {
            String[] current = CapabilityPanel.row(reading, "20:42:05", false);
            String[] old = CapabilityPanel.row(reading, "20:42:05", true);
            lines.add(current[0]);
            expect(current[0].contains(reading.metric.label) && current[1].contains(reading.source) && current[1].contains("20:42:05") && !current[1].contains("Stale"),
                reading.metric.label + " shows its status, source and measurement time");
            expect(old[1].startsWith("Stale.") && old[1].contains("20:42:05"), reading.metric.label + " keeps its own time and says stale after refresh");
        }
        expect(lines.size() == 6 && lines.get(1).contains("Degraded") && lines.get(5).contains("Unavailable"), "all six metrics show a plain status word");
        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
