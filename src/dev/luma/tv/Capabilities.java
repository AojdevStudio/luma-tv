package dev.luma.tv;

import java.util.ArrayList;
import java.util.List;

final class Capabilities {
    static final String LAST = "capability_last";

    interface Tv {
        boolean clockShown();
        boolean hasInputs();
        boolean widgetOn(String id);
        void showClock(boolean shown);
        void showWidget(String id, boolean on);
        void remember(String line);
        Commands.Registry registry();
        boolean openApp(String pkg);
        void selectTab(String id);
        Profiles profile();
        /** The layout Home shows instead of the owner's own, or null on Default. Widget changes edit Default only. */
        String homeLayout();
    }

    enum Capability {
        CLOCK("clock", null, Profiles.Content.SETTINGS, false), INPUTS("inputs", null, Profiles.Content.SETTINGS, true),
        APP("app", Commands.Kind.APP, null, false), TAB("tab", Commands.Kind.TAB, null, false),
        WIDGET("widget", Commands.Kind.WIDGET, Profiles.Content.SETTINGS, true), SCENE("scene", Commands.Kind.SCENE, Profiles.Content.HOUSE, false);
        final String id;
        final Commands.Kind kind;
        final Profiles.Content content;
        final boolean home;
        Capability(String id, Commands.Kind kind, Profiles.Content content, boolean home) { this.id = id; this.kind = kind; this.content = content; this.home = home; }
        static Capability of(String id) {
            for (Capability capability : values()) if (capability.id.equals(id)) return capability;
            return null;
        }
    }

    /** How a confirmed action ended. Only DONE is saved as the last result. */
    enum Status {
        DONE("Done"), NOT_RUN("Not run"), NOT_FINISHED("Not finished"), UNKNOWN("Unknown");
        final String label;
        Status(String label) { this.label = label; }
    }

    static final class Action {
        final Capability capability;
        final String id;
        final String label;
        final String name;
        final String preview;
        final boolean confirm;
        final String unavailable;
        private final boolean target;
        private Action(Capability capability, String id, String label, String name, String preview, String unavailable, boolean target) {
            this.capability = capability;
            this.id = id;
            this.label = label;
            this.name = name;
            this.preview = preview;
            this.confirm = true;
            this.unavailable = unavailable;
            this.target = target;
        }
        boolean available() { return unavailable == null; }
    }

    static final class Result {
        final Capability capability;
        final boolean done;
        final String message;
        final long at;
        private Result(Capability capability, boolean done, String message, long at) {
            this.capability = capability;
            this.done = done;
            this.message = message;
            this.at = at;
        }
        String line() { return "1\t" + capability.id + "\t" + at + "\t" + message; }
        static Result parse(String line) {
            if (line == null || line.isEmpty()) return null;
            String[] fields = line.split("\\t", 4);
            if (fields.length != 4 || !"1".equals(fields[0])) return null;
            Capability capability = Capability.of(fields[1]);
            if (capability == null) return null;
            long at;
            try { at = Long.parseLong(fields[2]); } catch (NumberFormatException error) { return null; }
            if (at < 0 || fields[3].isEmpty()) return null;
            return new Result(capability, true, fields[3], at);
        }
    }

    static List<Action> list(Tv tv) {
        ArrayList<Action> actions = new ArrayList<>();
        boolean clock = tv.clockShown();
        actions.add(new Action(Capability.CLOCK, "clock", "Clock", clock ? "Hide the clock" : "Show the clock",
            clock ? "Hides the clock in the top-right corner of every page." : "Shows the clock in the top-right corner of every page.",
            blocked(Capability.CLOCK, tv), !clock));
        if (!tv.hasInputs()) {
            actions.add(new Action(Capability.INPUTS, "inputs", "Inputs", "Show Inputs on Home",
                "Shows HDMI inputs on Home when the TV reports them.",
                "Luma cannot see an HDMI input service on this TV, so Inputs is unavailable.", true));
        } else {
            boolean inputs = tv.widgetOn("inputs");
            actions.add(new Action(Capability.INPUTS, "inputs", "Inputs", inputs ? "Hide Inputs from Home" : "Show Inputs on Home",
                inputs ? "Hides the Inputs widget from Home." : "Shows the Inputs widget on Home.", blocked(Capability.INPUTS, tv), !inputs));
        }
        return actions;
    }

    static Action of(Commands.Command command) {
        Commands.Target t = command.target;
        switch (command.verb) {
            case LAUNCH_APP: return new Action(Capability.APP, t.id, t.name, "Open " + t.name,
                "Leaves Luma and opens " + t.name + ". Back in " + t.name + " returns here.", null, true);
            case SELECT_TAB: return new Action(Capability.TAB, t.id, t.name, "Switch to " + t.name,
                "Switches Luma to the " + t.name + " page. Close this panel to see it.", null, true);
            case SHOW_WIDGET: return new Action(Capability.WIDGET, t.id, t.name, "Show " + t.name + " on Home",
                "Shows the " + t.name + " widget on Home.", null, true);
            case HIDE_WIDGET: return new Action(Capability.WIDGET, t.id, t.name, "Hide " + t.name + " from Home",
                "Hides the " + t.name + " widget from Home.", null, false);
            default: return scene(t);
        }
    }

    static Action scene(Commands.Target scene) {
        return new Action(Capability.SCENE, scene.id, scene.name, "Start " + scene.name,
            "House Control runs the " + scene.name + " scene.", Commands.NO_SCENES, true);
    }

    static Result run(Action previewed, Tv tv, long now) {
        String refused = refusal(previewed, tv);
        if (refused != null) return new Result(previewed.capability, false, refused, now);
        String message;
        switch (previewed.capability) {
            case CLOCK:
                tv.showClock(previewed.target);
                message = previewed.target ? "Showed the clock" : "Hid the clock";
                break;
            case INPUTS:
                tv.showWidget("inputs", previewed.target);
                message = previewed.target ? "Showed Inputs on Home" : "Hid Inputs from Home";
                break;
            case WIDGET:
                tv.showWidget(previewed.id, previewed.target);
                message = previewed.target ? "Showed " + previewed.label + " on Home" : "Hid " + previewed.label + " from Home";
                break;
            case APP:
                if (!tv.openApp(previewed.id)) return new Result(previewed.capability, false, "Android could not open " + previewed.label + ". It may be updating.", now);
                message = "Opened " + previewed.label;
                break;
            case TAB:
                tv.selectTab(previewed.id);
                message = "Switched to " + previewed.label;
                break;
            default:
                throw new AssertionError(previewed.capability);
        }
        Result result = new Result(previewed.capability, true, message, now);
        tv.remember(result.line());
        return result;
    }

    private static String blocked(Capability capability, Tv tv) {
        Profiles profile = tv.profile();
        if (capability.content != null && !profile.allows(capability.content)) return profile.mode.name + " mode does not allow this. Return to Normal mode first.";
        String layout = tv.homeLayout();
        if (capability.home && layout != null) return "Home shows the " + layout + " layout. Choose Default in Layouts to change Home widgets.";
        return null;
    }

    /** Why a previewed action may not run now, or null. */
    static String refusal(Action previewed, Tv tv) {
        if (previewed.capability == Capability.SCENE) return Commands.NO_SCENES;
        String blocked = blocked(previewed.capability, tv);
        if (blocked != null) return blocked;
        if (previewed.capability.kind == null) {
            for (Action action : list(tv)) if (action.capability == previewed.capability) return action.unavailable;
            return "This capability is no longer available.";
        }
        for (Commands.Target target : tv.registry().targets) {
            if (target.kind != previewed.capability.kind || !target.id.equals(previewed.id)) continue;
            return target.available ? null : previewed.label + " is unavailable on this TV right now.";
        }
        return previewed.capability == Capability.APP ? previewed.label + " is no longer installed." : previewed.label + " is no longer available.";
    }
}
