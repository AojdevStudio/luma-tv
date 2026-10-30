package dev.luma.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** House Control's scenes as Luma reads them from its HomeService over Binder. Code 1 replies with the home, code 2 runs a
 *  scene on the real home and code 4 on House Control's isolated demo home. HouseLink hands this class each reply as
 *  maps, lists and values, so it runs without Android. A scene's changes come from the plan it was read with: House
 *  Control merges the scene's "*" patch with each device's own patch, for every light and air conditioner in the plan. */
final class HouseScenes {
    enum Source {
        LIVE("House Control TV, your home."), DEMO("House Control TV, demo home. No real devices change.");
        final String label;
        Source(String label) { this.label = label; }
    }

    static final class Scene {
        final String id, name, icon, revision;
        final Source source;
        /** One sentence per distinct setting, lights first, such as "Desk lamp on." Empty when it changes nothing. */
        final List<String> changes;
        /** Changes to plan devices House Control has not linked to a real device. It only simulates those. */
        final List<String> simulated;
        private Scene(String id, String name, String icon, Source source, String revision, List<String> changes, List<String> simulated) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.source = source;
            this.revision = revision;
            this.changes = Collections.unmodifiableList(changes);
            this.simulated = Collections.unmodifiableList(simulated);
        }
        /** Same scene from the same plan with the same changes, so its preview still says what will happen. */
        boolean same(Scene other) {
            return id.equals(other.id) && name.equals(other.name) && source == other.source && revision.equals(other.revision)
                && changes.equals(other.changes) && simulated.equals(other.simulated);
        }
    }

    static final class Home {
        final Source source;
        final String revision;
        /** Why House Control cannot run scenes now, or null. */
        final String blocked;
        final List<Scene> scenes;
        private Home(Source source, String revision, String blocked, List<Scene> scenes) {
            this.source = source;
            this.revision = revision;
            this.blocked = blocked;
            this.scenes = Collections.unmodifiableList(scenes);
        }
        Scene scene(String id) {
            for (Scene scene : scenes) if (scene.id.equals(id)) return scene;
            return null;
        }
    }

    /** One read: the home, or why there is none. A failed read says nothing about which scenes exist. */
    static final class Read {
        final Home home;
        final String problem;
        private Read(Home home, String problem) {
            this.home = home;
            this.problem = problem;
        }
        static Read failed(String problem) { return new Read(null, problem); }
    }

    static final class Outcome {
        final Capabilities.Status status;
        final String message;
        private Outcome(Capabilities.Status status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    static Read read(Object reply) {
        Map<String, Object> home = map(reply);
        if (home == null) return Read.failed("House Control sent a reply Luma cannot read.");
        String error = text(home.get("error"));
        if (!error.isEmpty()) return Read.failed(error);
        Map<String, Object> plan = map(home.get("plan"));
        if (plan == null) return Read.failed("House Control sent no home plan. Open House Control to finish setup.");
        Source source = Boolean.TRUE.equals(home.get("demo")) ? Source.DEMO : Source.LIVE;
        String revision = text(home.get("revision")), label = text(home.get("connectionLabel"));
        String blocked = Boolean.TRUE.equals(home.get("controlReady")) ? null
            : "House Control is not ready. " + (label.isEmpty() ? "Open House Control to connect your devices." : label + ".");
        Map<String, String> names = names(plan);
        // The demo home marks every device linked:false and its source already says no real device changes.
        Predicate<Map<String, Object>> simulated = device -> source == Source.LIVE && Boolean.FALSE.equals(device.get("linked"));
        ArrayList<Scene> scenes = new ArrayList<>();
        for (Object item : list(plan.get("scenes"))) {
            Map<String, Object> scene = map(item);
            String id = scene == null ? "" : text(scene.get("id"));
            boolean repeated = false;
            for (Scene earlier : scenes) repeated |= earlier.id.equals(id);
            if (id.isEmpty() || repeated) continue;
            String name = text(scene.get("name"));
            scenes.add(new Scene(id, name.isEmpty() ? id : name, text(scene.get("icon")), source, revision,
                changes(scene, plan, names, simulated.negate()), changes(scene, plan, names, simulated)));
        }
        return new Read(new Home(source, revision, blocked, scenes), null);
    }

    /** Why a previewed scene may not run against this read, or null. */
    static String refusal(Scene previewed, Read now) {
        if (now == null) return "House Control has not answered yet.";
        if (now.problem != null) return now.problem;
        if (now.home.source != previewed.source) return "House Control switched between its demo home and your home. Choose " + previewed.name + " again.";
        if (now.home.blocked != null) return now.home.blocked;
        Scene current = now.home.scene(previewed.id);
        if (current == null) return previewed.name + " is no longer in House Control.";
        return current.same(previewed) ? null : "House Control changed since this preview. Choose " + previewed.name + " again.";
    }

    /** The reply to code 2 or 4. House Control answers ok, or an error that does not say whether some devices changed. */
    static Outcome outcome(Scene scene, Object reply) {
        Map<String, Object> result = map(reply);
        if (result == null) return unknown(scene);
        if (Boolean.TRUE.equals(result.get("ok")))
            return new Outcome(Capabilities.Status.DONE, Boolean.TRUE.equals(result.get("demo")) ? "Started " + scene.name + " on the demo home"
                : scene.simulated.isEmpty() ? "Started " + scene.name
                : scene.changes.isEmpty() ? "Simulated " + scene.name + ". No real device changed"
                : "Started " + scene.name + ". Some changes were only simulated");
        String error = text(result.get("error"));
        return error.isEmpty() ? unknown(scene) : new Outcome(Capabilities.Status.NOT_FINISHED, "House Control could not finish " + scene.name + ". " + error);
    }
    static Outcome unknown(Scene scene) {
        return new Outcome(Capabilities.Status.UNKNOWN, "Luma lost House Control while " + scene.name + " was starting. Check your home before you try again.");
    }
    static Outcome notRun(String why) { return new Outcome(Capabilities.Status.NOT_RUN, why); }

    /** Device names as the preview shows them. A name used more than once gets its room, such as "Ceiling in Hall". */
    private static Map<String, String> names(Map<String, Object> plan) {
        HashMap<String, String> rooms = new HashMap<>();
        for (Object item : list(plan.get("rooms"))) {
            Map<String, Object> room = map(item);
            if (room != null) rooms.put(text(room.get("id")), text(room.get("name")));
        }
        HashMap<String, Integer> uses = new HashMap<>();
        ArrayList<Map<String, Object>> devices = new ArrayList<>();
        for (String section : new String[]{"lights", "acs"}) for (Object item : list(plan.get(section))) {
            Map<String, Object> device = map(item);
            if (device == null) continue;
            devices.add(device);
            uses.merge(text(device.get("name")), 1, Integer::sum);
        }
        HashMap<String, String> names = new HashMap<>();
        for (Map<String, Object> device : devices) {
            String id = text(device.get("id")), name = text(device.get("name")), room = text(device.get("room"));
            String shown = name.isEmpty() ? id : name;
            if (uses.get(name) > 1) shown += " in " + (rooms.containsKey(room) && !rooms.get(room).isEmpty() ? rooms.get(room) : room);
            names.put(id, shown);
        }
        return names;
    }

    private static List<String> changes(Map<String, Object> scene, Map<String, Object> plan, Map<String, String> names, Predicate<Map<String, Object>> devices) {
        ArrayList<String> lines = new ArrayList<>();
        for (String section : new String[]{"lights", "acs"}) {
            Map<String, Object> patches = map(scene.get(section));
            if (patches == null) continue;
            LinkedHashMap<String, List<String>> groups = new LinkedHashMap<>();
            for (Object item : list(plan.get(section))) {
                Map<String, Object> device = map(item);
                String id = device == null ? "" : text(device.get("id"));
                if (id.isEmpty() || !devices.test(device)) continue;
                LinkedHashMap<String, Object> patch = new LinkedHashMap<>();
                Map<String, Object> all = map(patches.get("*")), own = map(patches.get(id));
                if (all != null) patch.putAll(all);
                if (own != null) patch.putAll(own);
                String setting = section.equals("lights") ? light(patch) : ac(patch, device);
                if (setting != null) groups.computeIfAbsent(setting, key -> new ArrayList<>()).add(names.get(id));
            }
            for (Map.Entry<String, List<String>> group : groups.entrySet()) lines.add(join(group.getValue()) + " " + group.getKey() + ".");
        }
        return lines;
    }

    /** What House Control sets on a light, with its limits: brightness 5 to 100 percent, 2200 to 6500 K. */
    private static String light(Map<String, Object> patch) {
        if (Boolean.FALSE.equals(patch.get("on"))) return "off";
        ArrayList<String> parts = new ArrayList<>();
        Double brightness = number(patch.get("brightness")), temp = number(patch.get("temp"));
        if (brightness != null) parts.add(Math.round(clamp(brightness, .05, 1) * 100) + "%");
        if (temp != null) parts.add(Math.round(clamp(temp, 2200, 6500)) + " K");
        if (patch.containsKey("color")) parts.add(patch.get("color") == null ? "white" : "colour " + text(patch.get("color")));
        return setting(patch, parts);
    }

    /** What House Control sets on an air conditioner. The target stays inside the unit's range, 16 to 30 by default. */
    private static String ac(Map<String, Object> patch, Map<String, Object> device) {
        if (Boolean.FALSE.equals(patch.get("on"))) return "off";
        ArrayList<String> parts = new ArrayList<>();
        Double target = number(patch.get("target")), min = number(device.get("min")), max = number(device.get("max"));
        if (!text(patch.get("mode")).isEmpty()) parts.add(text(patch.get("mode")));
        if (target != null) parts.add(Math.round(clamp(target, min == null ? 16 : min, max == null ? 30 : max)) + "°");
        if (!text(patch.get("fan")).isEmpty()) parts.add("fan " + text(patch.get("fan")));
        return setting(patch, parts);
    }

    private static String setting(Map<String, Object> patch, List<String> parts) {
        String detail = String.join(", ", parts);
        if (Boolean.TRUE.equals(patch.get("on"))) return detail.isEmpty() ? "on" : "on, " + detail;
        return detail.isEmpty() ? null : "set to " + detail;
    }

    private static String join(List<String> names) {
        if (names.size() > 3) return String.join(", ", names.subList(0, 3)) + " and " + (names.size() - 3) + " more";
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static Double number(Object value) {
        return value instanceof Number && Double.isFinite(((Number) value).doubleValue()) ? ((Number) value).doubleValue() : null;
    }
    private static String text(Object value) { return value instanceof String ? ((String) value).trim() : ""; }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return value instanceof Map ? (Map<String, Object>) value : null; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return value instanceof List ? (List<Object>) value : Collections.emptyList(); }
}
