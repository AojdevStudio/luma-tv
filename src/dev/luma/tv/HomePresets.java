package dev.luma.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Home layouts. Each one chooses a wallpaper, Home widgets, Home apps and scene shortcuts together. The owner's own Home stays in
 *  its existing settings and is never written here, so choosing Default always brings it back. A layout is previewed
 *  before it applies, and parts the TV lacks are reported, not dropped. */
final class HomePresets {
    static final String KEY = "home_presets";
    static final String PLEX = "com.plexapp.android";
    static final HomePresets EMPTY = new HomePresets(Selection.DEFAULT, new LinkedHashMap<String, Layout>());

    interface Tv {
        boolean hasApp(String pkg);
        /** false for Smart Home without House Control, and Inputs on a TV without inputs. */
        boolean hasWidget(String id);
        /** false for every scene when House Control is absent. */
        boolean hasScene(String id);
    }

    static final class Layout {
        final int wallpaper;
        final List<String> widgets;
        final List<String> apps;
        final List<String> scenes;
        Layout(int wallpaper, List<String> widgets, List<String> apps, List<String> scenes) {
            this.wallpaper = wallpaper;
            this.widgets = Collections.unmodifiableList(new ArrayList<>(widgets));
            this.apps = Collections.unmodifiableList(new ArrayList<>(apps));
            this.scenes = Collections.unmodifiableList(new ArrayList<>(scenes));
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Layout)) return false;
            Layout layout = (Layout) other;
            return wallpaper == layout.wallpaper && widgets.equals(layout.widgets) && apps.equals(layout.apps) && scenes.equals(layout.scenes);
        }
        @Override public int hashCode() { return Objects.hash(wallpaper, widgets, apps, scenes); }
    }

    enum BuiltIn {
        MOVIES("movies", "Movies", new String[]{"house", "upnext", "music"},
            new String[]{PLEX, "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom"}, new String[]{"movie"}),
        SPORTS("sports", "Sports", new String[]{"upnext", "inputs", "weather"},
            new String[]{"com.google.android.youtube.tv", PLEX}, new String[0]),
        NEWS("news", "News", new String[]{"weather", "upnext"},
            new String[]{"com.google.android.youtube.tv"}, new String[0]),
        GUESTS("guests", "Guests", new String[]{"weather", "house"},
            new String[]{"com.google.android.youtube.tv", PLEX, "com.google.android.youtube.tvmusic"}, new String[0]);
        final String id;
        final String name;
        private final String[] widgets;
        private final String[] apps;
        private final String[] scenes;
        BuiltIn(String id, String name, String[] widgets, String[] apps, String[] scenes) {
            this.id = id;
            this.name = name;
            this.widgets = widgets;
            this.apps = apps;
            this.scenes = scenes;
        }
        Layout layout(Layout owner) { return new Layout(owner.wallpaper, Arrays.asList(widgets), Arrays.asList(apps), Arrays.asList(scenes)); }
    }

    static final class Selection {
        static final Selection DEFAULT = new Selection(null, null);
        final BuiltIn builtIn;
        final String custom;
        private Selection(BuiltIn builtIn, String custom) { this.builtIn = builtIn; this.custom = custom; }
        String name() { return builtIn != null ? builtIn.name : custom != null ? custom : "Default"; }
        private String id() { return builtIn != null ? builtIn.id : custom != null ? "custom" : "default"; }
        @Override public boolean equals(Object other) {
            return other instanceof Selection && builtIn == ((Selection) other).builtIn && Objects.equals(custom, ((Selection) other).custom);
        }
        @Override public int hashCode() { return Objects.hash(builtIn, custom); }
    }

    enum Part { APP, WIDGET, SCENE }

    static final class Missing {
        final Part part;
        final String id;
        private Missing(Part part, String id) { this.part = part; this.id = id; }
    }

    static final class Proposal {
        final Selection source;
        final Layout layout;
        final List<Missing> missing;
        private Proposal(Selection source, Layout layout, List<Missing> missing) {
            this.source = source;
            this.layout = layout;
            this.missing = Collections.unmodifiableList(missing);
        }
    }

    final Selection active;
    private final LinkedHashMap<String, Layout> custom;

    private HomePresets(Selection active, LinkedHashMap<String, Layout> custom) {
        this.active = active;
        this.custom = custom;
    }

    /** Default first, then the built-in layouts, then saved ones in the order they were first saved. */
    List<Selection> choices() {
        ArrayList<Selection> choices = new ArrayList<>();
        choices.add(Selection.DEFAULT);
        for (BuiltIn builtIn : BuiltIn.values()) choices.add(new Selection(builtIn, null));
        for (String name : custom.keySet()) choices.add(new Selection(null, name));
        return choices;
    }

    Layout current(Layout owner) { return layout(active, owner); }

    Proposal preview(Selection choice, Layout owner, Tv tv) {
        Layout layout = layout(choice, owner);
        ArrayList<Missing> missing = new ArrayList<>();
        for (String id : layout.widgets) if (!tv.hasWidget(id)) missing.add(new Missing(Part.WIDGET, id));
        for (String pkg : layout.apps) if (!tv.hasApp(pkg)) missing.add(new Missing(Part.APP, pkg));
        for (String id : layout.scenes) if (!tv.hasScene(id)) missing.add(new Missing(Part.SCENE, id));
        return new Proposal(choice, layout, missing);
    }

    /** Makes a previewed layout the Home. A saved layout replaced since the preview applies as it is now. */
    HomePresets apply(Proposal proposal) {
        if (proposal.source.custom != null && !custom.containsKey(proposal.source.custom)) return this;
        return new HomePresets(proposal.source, custom);
    }

    /** Saves a layout under a local name, replacing one with that name. null when the name or an id cannot be stored. */
    HomePresets save(String rawName, Layout layout) {
        String name = name(rawName);
        if (name == null || !storable(layout.widgets) || !storable(layout.apps) || !storable(layout.scenes) || layout.wallpaper < 0) return null;
        LinkedHashMap<String, Layout> next = new LinkedHashMap<>(custom);
        next.put(name, layout);
        return new HomePresets(active, next);
    }

    private Layout layout(Selection choice, Layout owner) {
        if (choice.builtIn != null) return choice.builtIn.layout(owner);
        return choice.custom != null ? custom.get(choice.custom) : owner;
    }

    /** A trimmed name with no control characters that is not Default or a built-in layout's name; otherwise null. */
    static String name(String raw) {
        String name = raw.trim();
        if (name.isEmpty() || name.equalsIgnoreCase(Selection.DEFAULT.name())) return null;
        for (BuiltIn builtIn : BuiltIn.values()) if (name.equalsIgnoreCase(builtIn.name)) return null;
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) return null;
        return name;
    }

    private static boolean storable(List<String> ids) {
        for (String id : ids) if (!id.matches("[A-Za-z0-9_.-]+")) return false;
        return new LinkedHashSet<>(ids).size() == ids.size();
    }

    private static List<String> ids(String field) {
        List<String> ids = field.isEmpty() ? Collections.<String>emptyList() : Arrays.asList(field.split(",", -1));
        return storable(ids) ? ids : null;
    }

    String line() {
        StringBuilder text = new StringBuilder("1\t").append(active.id()).append('\t').append(active.custom == null ? "" : active.custom);
        for (Map.Entry<String, Layout> entry : custom.entrySet()) {
            Layout layout = entry.getValue();
            text.append('\n').append(entry.getKey()).append('\t').append(layout.wallpaper)
                .append('\t').append(String.join(",", layout.widgets))
                .append('\t').append(String.join(",", layout.apps))
                .append('\t').append(String.join(",", layout.scenes));
        }
        return text.toString();
    }

    static HomePresets parse(String text) {
        if (text == null || text.isEmpty()) return null;
        String[] lines = text.split("\n", -1);
        String[] head = lines[0].split("\t", -1);
        if (head.length != 3 || !"1".equals(head[0])) return null;
        LinkedHashMap<String, Layout> custom = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split("\t", -1);
            if (fields.length != 5 || !fields[0].equals(name(fields[0])) || custom.containsKey(fields[0])) return null;
            int wallpaper;
            try { wallpaper = Integer.parseInt(fields[1]); } catch (NumberFormatException error) { return null; }
            List<String> widgets = ids(fields[2]), apps = ids(fields[3]), scenes = ids(fields[4]);
            if (wallpaper < 0 || widgets == null || apps == null || scenes == null) return null;
            custom.put(fields[0], new Layout(wallpaper, widgets, apps, scenes));
        }
        Selection active = null;
        if (head[1].equals("custom")) active = custom.containsKey(head[2]) ? new Selection(null, head[2]) : null;
        else if (head[2].isEmpty()) for (Selection choice : EMPTY.choices()) if (choice.id().equals(head[1])) active = choice;
        return active == null ? null : new HomePresets(active, custom);
    }
}
