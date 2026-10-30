package dev.luma.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
        /** House Control as Luma last read it, or null before the first read. */
        HouseScenes.Read house();
        /** Reads House Control again, so house() is fresh, then calls done on the main thread. */
        void readHouse(Runnable done);
        /** Sends the scene to House Control and answers on the main thread. Nothing checks the preview here. */
        void runScene(HouseScenes.Scene scene, Consumer<HouseScenes.Outcome> done);
        /** The app chosen in Settings, Scene apps, to open after this scene, or "" for none. */
        String sceneApp(String scene);
        /** The panel that confirmed the action is still open, so a scene may still open its app. */
        boolean waiting();
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
        /** The scene as previewed, or null for other capabilities and for a scene House Control no longer lists. */
        final HouseScenes.Scene scene;
        private final boolean target;
        private Action(Capability capability, String id, String label, String name, String preview, String unavailable, boolean target) {
            this(capability, id, label, name, preview, unavailable, target, null);
        }
        private Action(Capability capability, String id, String label, String name, String preview, String unavailable, boolean target, HouseScenes.Scene scene) {
            this.capability = capability;
            this.id = id;
            this.label = label;
            this.name = name;
            this.preview = preview;
            this.confirm = true;
            this.unavailable = unavailable;
            this.target = target;
            this.scene = scene;
        }
        boolean available() { return unavailable == null; }
    }

    static final class Result {
        final Capability capability;
        final Status status;
        final String message;
        final long at;
        private Result(Capability capability, Status status, String message, long at) {
            this.capability = capability;
            this.status = status;
            this.message = message;
            this.at = at;
        }
        boolean done() { return status == Status.DONE; }
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
            return new Result(capability, Status.DONE, fields[3], at);
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

    /** Every scene in House Control's last read, in its order. Empty when the read failed or has none. */
    static List<Action> scenes(Tv tv) {
        ArrayList<Action> actions = new ArrayList<>();
        HouseScenes.Read read = tv.house();
        if (read != null && read.home != null) for (HouseScenes.Scene scene : read.home.scenes) actions.add(scene(scene, tv));
        return actions;
    }

    static Action of(Commands.Command command, Tv tv) {
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
            default:
                HouseScenes.Read read = tv.house();
                HouseScenes.Scene scene = read == null || read.home == null ? null : read.home.scene(t.id);
                if (scene != null) return scene(scene, tv);
                String gone = t.name + " is no longer in House Control.";
                return new Action(Capability.SCENE, t.id, t.name, "Start " + t.name, gone, gone, true);
        }
    }

    /** The preview names what House Control changes, what the TV does after, and which home it acts on. */
    static Action scene(HouseScenes.Scene scene, Tv tv) {
        String unavailable = blocked(Capability.SCENE, tv);
        if (unavailable == null) unavailable = HouseScenes.refusal(scene, tv.house());
        ArrayList<String> changes = new ArrayList<>(scene.changes.subList(0, Math.min(4, scene.changes.size())));
        if (scene.changes.size() > 4) changes.add("And " + (scene.changes.size() - 4) + " more changes.");
        String pkg = tv.sceneApp(scene.id);
        Commands.Target app = app(pkg, tv);
        String after = pkg.isEmpty() ? "Stays on Luma."
            : app == null ? pkg + " is chosen to open after this scene, but it is not installed. The TV stays on Luma."
            : !app.available ? tv.profile().mode.name + " mode does not open " + app.name + ". The TV stays on Luma."
            : "Opens " + app.name + " after the scene finishes.";
        String preview = "Home. " + (changes.isEmpty() ? "This scene changes no lights or air conditioners." : String.join(" ", changes))
            + "\nTV. " + after + "\nSource. " + scene.source.label;
        return new Action(Capability.SCENE, scene.id, scene.name, "Start " + scene.name, preview, unavailable, true, scene);
    }

    /** Answers once through done. Settings, apps and tabs answer at once. A scene answers after House Control does. */
    static void run(Action previewed, Tv tv, long now, Consumer<Result> done) {
        String refused = refusal(previewed, tv);
        if (refused != null) { done.accept(new Result(previewed.capability, Status.NOT_RUN, refused, now)); return; }
        if (previewed.capability == Capability.SCENE) {
            // The cached read may be old. Read again, then check the preview and the mode against that read before sending.
            tv.readHouse(() -> {
                String stale = refusal(previewed, tv);
                if (stale != null) { done.accept(new Result(Capability.SCENE, Status.NOT_RUN, stale, now)); return; }
                tv.runScene(previewed.scene, outcome -> finish(new Result(Capability.SCENE, outcome.status,
                    outcome.status == Status.DONE ? opened(outcome.message, tv.sceneApp(previewed.id), tv) : outcome.message, now), tv, done));
            });
            return;
        }
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
                if (!tv.openApp(previewed.id)) {
                    done.accept(new Result(previewed.capability, Status.NOT_RUN, "Android could not open " + previewed.label + ". It may be updating.", now));
                    return;
                }
                message = "Opened " + previewed.label;
                break;
            case TAB:
                tv.selectTab(previewed.id);
                message = "Switched to " + previewed.label;
                break;
            default:
                throw new AssertionError(previewed.capability);
        }
        finish(new Result(previewed.capability, Status.DONE, message, now), tv, done);
    }

    private static void finish(Result result, Tv tv, Consumer<Result> done) {
        if (result.done()) tv.remember(result.line());
        done.accept(result);
    }

    /** A started scene opens its app only when the mode allows it and the panel that asked is still open. */
    private static String opened(String started, String pkg, Tv tv) {
        if (pkg.isEmpty()) return started;
        Commands.Target app = app(pkg, tv);
        if (app == null) return started + ". " + pkg + " is not installed, so no app opened";
        if (!app.available) return started + ". " + tv.profile().mode.name + " mode does not open " + app.name;
        if (!tv.waiting()) return started + ". The panel closed first, so " + app.name + " did not open";
        return started + (tv.openApp(pkg) ? ". Opened " + app.name : ". Android could not open " + app.name);
    }

    /** The installed app as the registry lists it now, or null. */
    private static Commands.Target app(String pkg, Tv tv) {
        for (Commands.Target target : tv.registry().targets) if (target.kind == Commands.Kind.APP && target.id.equals(pkg)) return target;
        return null;
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
        String blocked = blocked(previewed.capability, tv);
        if (blocked != null) return blocked;
        if (previewed.capability == Capability.SCENE) return previewed.scene == null ? previewed.unavailable : HouseScenes.refusal(previewed.scene, tv.house());
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
