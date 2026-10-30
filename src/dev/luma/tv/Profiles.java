package dev.luma.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The active mode (Normal, Guest or Child) and the policy Home, Apps, the capability center and personal media ask
 *  before they show or run anything. It is a launcher presentation policy, not an account or an OS sandbox.
 *  Entering Normal is never gated, so the remote can always return. */
final class Profiles {
    static final String KEY = "profile";
    static final Profiles DEFAULT = new Profiles(Mode.NORMAL, Collections.<String>emptyList());

    enum Content {
        /** Weather, time and other information that says nothing about the household. */
        INFO,
        /** The owner's watch history, what is playing and the greeting name. */
        PERSONAL,
        /** Settings, the Control widget, app menus and anything that changes Luma or the TV. */
        SETTINGS,
        /** House Control scenes and household state. */
        HOUSE,
        /** HDMI inputs and live TV. */
        INPUTS
    }

    enum Mode {
        NORMAL("normal", "Normal", EnumSet.allOf(Content.class)),
        GUEST("guest", "Guest", EnumSet.of(Content.INFO, Content.HOUSE, Content.INPUTS)),
        CHILD("child", "Child", EnumSet.of(Content.INFO));
        final String id;
        final String name;
        private final Set<Content> allowed;
        Mode(String id, String name, Set<Content> allowed) { this.id = id; this.name = name; this.allowed = allowed; }
        static Mode of(String id) {
            for (Mode mode : values()) if (mode.id.equals(id)) return mode;
            return null;
        }
    }

    final Mode mode;
    final List<String> childApps;

    private Profiles(Mode mode, List<String> childApps) {
        this.mode = mode;
        this.childApps = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(childApps)));
    }

    Profiles enter(Mode next) { return new Profiles(next, childApps); }
    Profiles approve(List<String> apps) { return mode == Mode.NORMAL ? new Profiles(mode, apps) : this; }

    boolean allows(Content content) { return mode.allowed.contains(content); }
    List<String> home(List<String> homeApps) { return mode == Mode.CHILD ? childApps : homeApps; }
    /** Whether an app may be listed or opened from anywhere in Luma. Guest keeps the apps on Home. */
    boolean allowsApp(String pkg, boolean onHome) {
        switch (mode) {
            case NORMAL: return true;
            case GUEST: return onHome;
            default: return childApps.contains(pkg);
        }
    }
    boolean allowsWidget(String id) {
        Content content = widget(id);
        return content == null ? mode == Mode.NORMAL : allows(content);
    }
    boolean noApps() { return mode == Mode.CHILD && childApps.isEmpty(); }

    static Content widget(String id) {
        switch (id) {
            case "upnext": case "music": return Content.PERSONAL;
            case "control": return Content.SETTINGS;
            case "house": return Content.HOUSE;
            case "inputs": return Content.INPUTS;
            case "weather": return Content.INFO;
            default: return null;
        }
    }

    String line() { return "1\t" + mode.id + "\t" + String.join(",", childApps); }
    static Profiles parse(String line) {
        if (line == null || line.isEmpty()) return null;
        String[] fields = line.split("\t", -1);
        if (fields.length != 3 || !"1".equals(fields[0])) return null;
        Mode mode = Mode.of(fields[1]);
        if (mode == null) return null;
        List<String> apps = fields[2].isEmpty() ? Collections.<String>emptyList() : Arrays.asList(fields[2].split(",", -1));
        for (String pkg : apps) if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) return null;
        if (new LinkedHashSet<>(apps).size() != apps.size()) return null;
        return new Profiles(mode, apps);
    }
}
