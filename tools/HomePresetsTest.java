package dev.luma.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HomePresetsTest {
    static int failures;
    static final String PLEX = "com.plexapp.android", NETFLIX = "com.netflix.ninja", YOUTUBE = "com.google.android.youtube.tv";
    static final class FakeTv implements HomePresets.Tv {
        final Set<String> apps = new HashSet<>(), widgets = new HashSet<>(), scenes = new HashSet<>();
        public boolean hasApp(String pkg) { return apps.contains(pkg); }
        public boolean hasWidget(String id) { return widgets.contains(id); }
        public boolean hasScene(String id) { return scenes.contains(id); }
    }
    static void expect(boolean ok, String message) {
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + message);
    }
    static List<String> list(String... ids) { return Arrays.asList(ids); }
    static HomePresets.Selection named(HomePresets presets, String name) {
        for (HomePresets.Selection choice : presets.choices()) if (choice.name().equals(name)) return choice;
        return null;
    }
    static boolean lacks(HomePresets.Proposal proposal, HomePresets.Part part, String id) {
        for (HomePresets.Missing missing : proposal.missing) if (missing.part == part && missing.id.equals(id)) return true;
        return false;
    }
    public static void main(String[] args) {
        Set<String> sourceWidgets = new HashSet<>(list("house", "upnext", "weather", "inputs", "control", "music"));
        HomePresets.Layout owner = new HomePresets.Layout(2, list("weather", "upnext"), list(YOUTUBE, NETFLIX), list());
        FakeTv tv = new FakeTv();
        tv.apps.addAll(list(PLEX, YOUTUBE, "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom", "com.spotify.tv.android", "com.google.android.youtube.tvmusic"));
        tv.widgets.addAll(sourceWidgets);
        tv.scenes.add("movie");
        HomePresets presets = HomePresets.EMPTY;

        List<String> names = new ArrayList<>();
        for (HomePresets.Selection choice : presets.choices()) names.add(choice.name());
        expect(names.equals(list("Default", "Movies", "Sports", "News", "Guests")), "offers Default and the four activity layouts");
        for (HomePresets.Selection choice : presets.choices()) {
            HomePresets.Layout layout = presets.preview(choice, owner, tv).layout;
            if (choice.equals(HomePresets.Selection.DEFAULT)) continue;
            expect(!layout.apps.contains(NETFLIX) && !layout.apps.isEmpty(), choice.name() + " proposes apps without Netflix");
            expect(sourceWidgets.containsAll(layout.widgets) && !layout.widgets.isEmpty(), choice.name() + " uses existing widgets only");
            expect(layout.wallpaper == owner.wallpaper, choice.name() + " keeps the current wallpaper");
        }

        HomePresets.Proposal movies = presets.preview(named(presets, "Movies"), owner, tv);
        expect(movies.layout.apps.get(0).equals(PLEX) && movies.layout.scenes.equals(list("movie")) && movies.missing.isEmpty(),
            "Movies leads with Plex and the Movie scene, complete when all is installed");
        expect(presets.current(owner).equals(owner) && presets.active.equals(HomePresets.Selection.DEFAULT), "previewing leaves Home unchanged");
        HomePresets applied = presets.apply(movies);
        expect(applied.current(owner).equals(movies.layout) && applied.active.name().equals("Movies"), "applying Movies changes Home");
        HomePresets reloaded = HomePresets.parse(applied.line());
        expect(reloaded != null && reloaded.current(owner).equals(movies.layout), "applied layout survives restart");
        HomePresets restored = reloaded.apply(reloaded.preview(HomePresets.Selection.DEFAULT, owner, tv));
        expect(restored.current(owner).equals(owner), "choosing Default after restart restores the owner's Home");

        HomePresets.Layout mine = new HomePresets.Layout(5, list("music", "weather"), list(PLEX, YOUTUBE), list("movie"));
        HomePresets saved = restored.save("  Game night ", mine);
        expect(saved != null && saved.current(owner).equals(owner), "saving a layout does not apply it");
        HomePresets afterRestart = HomePresets.parse(saved.line());
        HomePresets.Selection game = afterRestart == null ? null : named(afterRestart, "Game night");
        expect(game != null && afterRestart.preview(game, owner, tv).layout.equals(mine), "custom layout keeps its name and contents across restart");
        HomePresets gameOn = HomePresets.parse(afterRestart.apply(afterRestart.preview(game, owner, tv)).line());
        expect(gameOn != null && gameOn.current(owner).equals(mine), "applied custom layout survives restart");
        HomePresets replaced = gameOn.save("Game night", owner);
        expect(replaced.choices().size() == gameOn.choices().size() && replaced.current(owner).equals(owner), "saving over a name replaces that layout");
        expect(saved.save(" ", mine) == null && saved.save("movies", mine) == null && saved.save("a\tb", mine) == null,
            "blank, reserved and control-character names are refused");
        expect(saved.save("Odd", new HomePresets.Layout(1, list("weather"), list(YOUTUBE), list("a,b"))) == null, "unstorable scene ids are refused");

        FakeTv bare = new FakeTv();
        bare.apps.add(YOUTUBE);
        bare.widgets.addAll(list("upnext", "weather", "music", "control"));
        HomePresets.Proposal partial = presets.preview(named(presets, "Movies"), owner, bare);
        expect(lacks(partial, HomePresets.Part.APP, PLEX) && lacks(partial, HomePresets.Part.WIDGET, "house") && lacks(partial, HomePresets.Part.SCENE, "movie"),
            "reports missing Plex, Smart Home and the Movie scene");
        expect(!lacks(partial, HomePresets.Part.WIDGET, "upnext"), "installed parts are not reported missing");
        expect(partial.layout.equals(movies.layout) && presets.apply(partial).current(owner).equals(movies.layout), "a partial layout keeps its missing parts and still applies");

        expect(HomePresets.parse(null) == null && HomePresets.parse("") == null, "absent saved presets are not parsed");
        expect(HomePresets.parse("2\tdefault\t") == null, "unknown version is rejected");
        expect(HomePresets.parse("1\tbogus\t") == null, "unknown active layout is rejected");
        expect(HomePresets.parse("1\tcustom\tGone") == null, "active custom layout must exist");
        expect(HomePresets.parse("1\tdefault\t\nA\t-1\tweather\t\t") == null, "negative wallpaper is rejected");
        expect(HomePresets.parse("1\tdefault\t\nA\t1\tweather\t\t\nA\t1\tweather\t\t") == null, "duplicate names are rejected");
        expect(HomePresets.parse("1\tdefault\t\nA\t1\tweather,weather\t\t") == null, "duplicate widgets are rejected");
        expect(HomePresets.parse("1\tdefault\t\nA\tone\tweather\t\t") == null, "damaged wallpaper is rejected");
        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
