package dev.luma.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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
    static final class Names implements CapabilityPanel.Names {
        final Map<String, String> apps = new HashMap<>();
        boolean house, loaded = true;
        public String app(String pkg) { return apps.get(pkg); }
        public String widget(String id) { return id.equals("house") ? "Smart Home" : id.equals("upnext") ? "Up Next" : id.equals("music") ? "Now Playing" : id; }
        public String scene(String id) { return house && loaded && id.equals("movie") ? "Movie" : null; }
        public String wallpaper(int index) { return "Silver"; }
        public boolean house() { return house; }
    }
    static final class Tv implements HomePresets.Tv {
        final Names names;
        Tv(Names names) { this.names = names; }
        public boolean hasApp(String pkg) { return names.apps.containsKey(pkg); }
        public boolean hasWidget(String id) { return !id.equals("house") || names.house; }
        public boolean hasScene(String id) { return names.scene(id) != null; }
    }
    static String unavailable(String text) {
        for (String line : text.split("\n")) if (line.startsWith("Unavailable.")) return line;
        return "";
    }
    public static void main(String[] args) {
        Profiles normal = Profiles.DEFAULT;
        expect(CapabilityPanel.sections(false, normal).equals(Arrays.asList(CapabilityPanel.Section.ACTIONS, CapabilityPanel.Section.ASK, CapabilityPanel.Section.LAYOUTS, CapabilityPanel.Section.HEALTH, CapabilityPanel.Section.MODE)),
            "without House Control the panel has no Scenes section");
        expect(CapabilityPanel.sections(true, normal).equals(Arrays.asList(CapabilityPanel.Section.ACTIONS, CapabilityPanel.Section.ASK, CapabilityPanel.Section.LAYOUTS, CapabilityPanel.Section.SCENES, CapabilityPanel.Section.HEALTH, CapabilityPanel.Section.MODE)),
            "with House Control the panel adds Scenes");
        expect(CapabilityPanel.sections(true, normal.enter(Profiles.Mode.GUEST)).equals(Arrays.asList(CapabilityPanel.Section.SCENES, CapabilityPanel.Section.MODE)),
            "Guest mode keeps House Control's scenes beside Mode");
        expect(CapabilityPanel.sections(false, normal.enter(Profiles.Mode.GUEST)).equals(Collections.singletonList(CapabilityPanel.Section.MODE)),
            "Guest mode without House Control opens only the Mode section");
        expect(CapabilityPanel.sections(true, normal.enter(Profiles.Mode.CHILD)).equals(Collections.singletonList(CapabilityPanel.Section.MODE)),
            "Child mode opens only the Mode section, with no scenes");

        Names names = new Names();
        names.apps.put("com.disney.disneyplus", "Disney+");
        names.apps.put("com.amazon.amazonvideo.livingroom", "Prime Video");
        HomePresets.Layout owner = new HomePresets.Layout(7, Arrays.asList("weather"), Arrays.asList("com.google.android.youtube.tv"), Collections.<String>emptyList());
        HomePresets.Selection movies = null;
        for (HomePresets.Selection choice : HomePresets.EMPTY.choices()) if (choice.name().equals("Movies")) movies = choice;
        String bare = CapabilityPanel.layout(HomePresets.EMPTY.preview(movies, owner, new Tv(names)), names);
        String missing = unavailable(bare);
        expect(missing.contains("com.plexapp.android is not installed") && missing.contains("Smart Home needs House Control TV") && missing.contains("movie scene needs House Control TV"),
            "a layout preview names each missing app, widget and scene as unavailable");
        expect(!missing.contains("Disney+") && !missing.contains("Prime Video") && bare.contains("Apps. com.plexapp.android, Disney+, Prime Video"),
            "installed apps keep their names and a missing app shows its package, not an invented name");
        names.apps.put("com.plexapp.android", "Plex");
        names.house = true;
        String full = CapabilityPanel.layout(HomePresets.EMPTY.preview(movies, owner, new Tv(names)), names);
        expect(unavailable(full).isEmpty() && full.contains("Scenes. Movie") && full.contains("Widgets. Smart Home, Up Next, Now Playing"), "a complete layout reports nothing unavailable");
        names.loaded = false;
        String unloaded = unavailable(CapabilityPanel.layout(HomePresets.EMPTY.preview(movies, owner, new Tv(names)), names));
        expect(unloaded.contains("Luma's House Control data") && !unloaded.contains("has no"), "an unloaded scene inventory is unavailable data, not proof the scene does not exist");

        expect(CapabilityPanel.mode(Profiles.Mode.CHILD, Collections.<String>emptyList()).contains("None yet"), "the Child preview says when no apps are approved");
        expect(CapabilityPanel.mode(Profiles.Mode.CHILD, Arrays.asList("YouTube", "Plex")).contains("YouTube, Plex"), "the Child preview lists the approved apps");

        CapabilityPanel.Health health = new CapabilityPanel.Health();
        int first = health.refresh();
        expect(health.shown() == null && health.stale(), "the first check shows no values while it measures");
        expect(health.accept(first, snapshot(100L)) && health.shown().started == 100L && !health.stale(), "the newest check replaces the report");
        int older = health.refresh();
        expect(health.stale() && health.shown().started == 100L, "refresh marks the shown values stale at once and keeps their time");
        int newer = health.refresh();
        expect(!health.accept(older, snapshot(200L)) && health.shown().started == 100L && health.stale(), "an older refresh that answers late is ignored");
        expect(health.accept(newer, snapshot(300L)) && health.shown().started == 300L && !health.stale(), "the newest refresh lands");
        int late = health.refresh();
        health.close();
        expect(!health.accept(late, snapshot(400L)) && health.shown().started == 300L, "nothing lands after the panel closes");

        ArrayList<String> lines = new ArrayList<>();
        for (HealthSnapshot.Reading reading : snapshot(500L).readings) {
            String[] current = CapabilityPanel.row(reading, "20:42:05", false);
            String[] old = CapabilityPanel.row(reading, "20:42:05", true);
            lines.add(current[0]);
            expect(current[0].contains(reading.metric.label) && current[1].contains(reading.value) && current[1].contains(reading.explanation) && current[1].contains(reading.source) && current[1].contains("20:42:05") && !current[1].contains("Stale"),
                reading.metric.label + " shows its measured value, explanation, source and time");
            expect(old[1].startsWith("Stale.") && old[1].contains("20:42:05"), reading.metric.label + " keeps its own time and says stale after refresh");
        }
        expect(lines.size() == 6 && lines.get(1).contains("Degraded") && lines.get(5).contains("Unavailable"), "all six metrics show a plain status word");
        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
