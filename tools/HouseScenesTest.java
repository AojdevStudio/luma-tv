package dev.luma.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HouseScenesTest {
    static int failures;
    static void expect(boolean ok, String message) {
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + message);
    }
    static Map<String, Object> map(Object... pairs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
    static List<Object> list(Object... items) { return new ArrayList<>(Arrays.asList(items)); }
    static Map<String, Object> light(String id, String name, String room) { return map("id", id, "name", name, "room", room, "linked", true); }
    static Map<String, Object> scene(String id, String name, Object... sections) {
        Map<String, Object> scene = map("id", id, "name", name, "icon", "film");
        for (int i = 0; i < sections.length; i += 2) scene.put((String) sections[i], sections[i + 1]);
        return scene;
    }
    /** HomeService code 1 as House Control's HomeEngine.home() writes it: the demo loft's rooms and devices. */
    static Map<String, Object> home(String revision, boolean ready, Object scenes) {
        Map<String, Object> plan = map("name", "Loft",
            "rooms", list(map("id", "main", "name", "Studio"), map("id", "bath", "name", "Bathroom"), map("id", "hall", "name", "Hall")),
            "lights", list(light("main.ceiling", "Ceiling", "main"), light("main.bed", "Bedside", "main"), light("main.desk", "Desk lamp", "main"),
                light("main.shelf", "Shelf glow", "main"), light("bath.ceiling", "Ceiling", "bath"), light("hall.ceiling", "Ceiling", "hall"),
                light("main.bed.left", "Left bedside lamp", "main"), light("main.tv", "TV ambient light", "main")),
            "acs", list(map("id", "main.ac", "name", "Studio AC", "room", "main", "min", 18, "max", 28)),
            "scenes", scenes);
        return map("version", 2, "mode", "standalone", "plan", plan, "state", map("lights", map(), "acs", map(), "scene", null),
            "revision", revision, "controlReady", ready, "connectionLabel", ready ? "TV · Smart Life connected" : "Connect Smart Life on TV",
            "lastPoll", 1L, "cloudError", "", "source", "This TV");
    }
    static List<Object> loftScenes() {
        return list(
            scene("bright", "Bright", "lights", map("*", map("on", true))),
            scene("movie", "Movie", "lights", map("*", map("on", false), "main.tv", map("on", true))),
            scene("work", "Work", "lights", map("*", map("on", false), "main.desk", map("on", true),
                "main.ceiling", map("on", true, "brightness", 0.6, "temp", 4500), "main.shelf", map("on", true, "brightness", 0.01, "temp", 9000, "color", "#ff5fc4"))),
            scene("cool", "Cool down", "acs", map("main.ac", map("on", true, "mode", "cool", "target", 35, "fan", "auto"))),
            scene("away", "Away", "lights", map("*", map("on", false)), "acs", map("*", map("on", false))),
            scene("garden", "Garden", "lights", map("garden.path", map("on", true))),
            scene("", "No id"), scene("movie", "Movie again"), map("id", "night"));
    }
    /** A real home with one plan device House Control has not linked, so it only simulates that lamp. */
    static Map<String, Object> partlyLinked() {
        return map("controlReady", true, "revision", "r1", "plan", map(
            "lights", list(light("ceiling", "Ceiling", "main"), map("id", "desk", "name", "Desk lamp", "room", "main", "linked", false)),
            "scenes", list(scene("night", "Night", "lights", map("*", map("on", false))), scene("desk", "Desk only", "lights", map("desk", map("on", true))))));
    }
    public static void main(String[] args) {
        HouseScenes.Read live = HouseScenes.read(home("r1", true, loftScenes()));
        expect(live.problem == null && live.home != null && live.home.source == HouseScenes.Source.LIVE && "r1".equals(live.home.revision)
            && live.home.blocked == null, "a ready live reply reads as your home at its plan revision");
        List<String> ids = new ArrayList<>();
        for (HouseScenes.Scene scene : live.home.scenes) ids.add(scene.id);
        expect(ids.equals(Arrays.asList("bright", "movie", "work", "cool", "away", "garden", "night")),
            "every listed scene is kept in House Control's order, beyond five, without blank or repeated ids");
        expect("night".equals(live.home.scene("night").name), "a scene without a name shows its id");
        expect("Movie".equals(live.home.scene("movie").name) && "film".equals(live.home.scene("movie").icon), "the first scene with a repeated id wins");
        expect(live.home.scene("movie").changes.equals(Arrays.asList(
            "Ceiling in Studio, Bedside, Desk lamp and 4 more off.", "TV ambient light on.")),
            "Movie names the lights it turns off and the light it turns on, with rooms for repeated names");
        expect(live.home.scene("work").changes.equals(Arrays.asList(
            "Ceiling in Studio on, 60%, 4500 K.", "Bedside, Ceiling in Bathroom, Ceiling in Hall and 2 more off.", "Desk lamp on.", "Shelf glow on, 5%, 6500 K, colour #ff5fc4.")),
            "light changes use House Control's limits for brightness and colour temperature");
        expect(live.home.scene("cool").changes.equals(Arrays.asList("Studio AC on, cool, 28°, fan auto.")),
            "an air conditioner target stays inside that unit's range");
        expect(live.home.scene("away").changes.size() == 2 && live.home.scene("away").changes.get(1).equals("Studio AC off."),
            "a scene names lights and air conditioners separately");
        expect(live.home.scene("garden").changes.isEmpty(), "a scene for a device the plan lacks changes nothing");

        HouseScenes.Read demo = HouseScenes.read(map("mode", "demo", "demo", true, "controlReady", true, "revision", "demo-1",
            "plan", map("lights", list(light("main.tv", "TV ambient light", "main")), "scenes", list(scene("movie", "Movie", "lights", map("*", map("on", true)))))));
        expect(demo.home.source == HouseScenes.Source.DEMO && demo.home.scenes.size() == 1, "House Control's demo home reads as the demo home");

        HouseScenes.Read waiting = HouseScenes.read(home("r1", false, loftScenes()));
        expect(waiting.home.scenes.size() == 7 && "House Control is not ready. Connect Smart Life on TV.".equals(waiting.home.blocked),
            "a home that cannot control devices still lists its scenes and says why they cannot run");
        HouseScenes.Read noPlan = HouseScenes.read(map("error", "Import your home from Mac once, or upload a floor plan from your phone."));
        expect(noPlan.home == null && noPlan.problem.startsWith("Import your home"), "House Control's own error is the reason, not an empty scene list");
        expect(HouseScenes.read("not a home").home == null && !HouseScenes.read("not a home").problem.isEmpty(), "an unreadable reply has a reason");
        expect(HouseScenes.read(home("r1", true, null)).home.scenes.isEmpty(), "a plan without scenes reads as no scenes");

        HouseScenes.Scene movie = live.home.scene("movie");
        expect(HouseScenes.refusal(movie, live) == null, "the scene may run while House Control still matches its preview");
        expect(HouseScenes.refusal(movie, HouseScenes.read(home("r2", true, loftScenes()))).contains("changed since this preview"),
            "a new plan revision makes the preview stale");
        List<Object> withoutMovie = loftScenes();
        withoutMovie.removeIf(scene -> "movie".equals(((Map<?, ?>) scene).get("id")));
        expect(HouseScenes.refusal(movie, HouseScenes.read(home("r1", true, withoutMovie))).equals("Movie is no longer in House Control."),
            "a removed scene is refused by name");
        expect(HouseScenes.refusal(movie, demo).contains("demo"), "a live preview never runs on the demo home");
        expect(HouseScenes.refusal(movie, waiting).equals(waiting.home.blocked), "a home that lost its devices refuses with its reason");
        expect(HouseScenes.refusal(movie, noPlan).equals(noPlan.problem), "a failed read refuses with its reason");
        expect(HouseScenes.refusal(movie, null) != null, "no read at all refuses");

        HouseScenes.Outcome done = HouseScenes.outcome(movie, map("ok", true, "direct", true, "home", home("r1", true, loftScenes())));
        expect(done.status == Capabilities.Status.DONE && "Started Movie".equals(done.message), "ok from code 2 is a started scene");
        HouseScenes.Outcome demoDone = HouseScenes.outcome(demo.home.scene("movie"), map("ok", true, "demo", true, "direct", false));
        expect(demoDone.status == Capabilities.Status.DONE && "Started Movie on the demo home".equals(demoDone.message), "a demo run says it ran on the demo home");
        HouseScenes.Home partly = HouseScenes.read(partlyLinked()).home;
        expect("Simulated Desk only. No real device changed".equals(HouseScenes.outcome(partly.scene("desk"), map("ok", true)).message)
            && "Started Night. Some changes were only simulated".equals(HouseScenes.outcome(partly.scene("night"), map("ok", true)).message),
            "ok for a scene that touches devices House Control only simulates says so");
        HouseScenes.Outcome failed = HouseScenes.outcome(movie, map("error", "Ceiling: device offline"));
        expect(failed.status == Capabilities.Status.NOT_FINISHED && failed.message.contains("Ceiling: device offline") && !failed.message.startsWith("Started"),
            "House Control's error is reported as not finished, with its words");
        expect(HouseScenes.outcome(movie, map("direct", true)).status == Capabilities.Status.UNKNOWN, "a reply without ok or error is unknown");
        expect(HouseScenes.unknown(movie).status == Capabilities.Status.UNKNOWN && HouseScenes.unknown(movie).message.contains("Movie"),
            "a lost connection mid-run is unknown, not failed or done");
        expect(HouseScenes.notRun("Update House Control.").status == Capabilities.Status.NOT_RUN, "a refusal before sending is not run");
        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
