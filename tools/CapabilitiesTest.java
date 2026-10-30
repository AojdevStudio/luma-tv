package dev.luma.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public final class CapabilitiesTest {
    static int failures;
    static final class FakeTv implements Capabilities.Tv {
        boolean clock = true;
        boolean inputs;
        boolean inputsOn;
        boolean launches = true;
        final HashSet<String> off = new HashSet<>();
        final ArrayList<Commands.Target> targets = new ArrayList<>();
        String saved, opened, tab;
        public boolean clockShown() { return clock; }
        public boolean hasInputs() { return inputs; }
        public boolean widgetOn(String id) { return id.equals("inputs") ? inputsOn : !off.contains(id); }
        public void showClock(boolean shown) { clock = shown; }
        public void showWidget(String id, boolean on) { if (id.equals("inputs")) inputsOn = on; else if (on) off.remove(id); else off.add(id); }
        public void remember(String line) { saved = line; }
        public Commands.Registry registry() { return new Commands.Registry(targets); }
        public boolean openApp(String pkg) { if (launches) opened = pkg; return launches; }
        public void selectTab(String id) { tab = id; }
    }
    static void expect(boolean ok, String message) {
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + message);
    }
    static Capabilities.Action find(List<Capabilities.Action> actions, Capabilities.Capability capability) {
        for (Capabilities.Action action : actions) if (action.capability == capability) return action;
        return null;
    }
    static FakeTv living() {
        FakeTv tv = new FakeTv();
        tv.targets.addAll(Arrays.asList(
            new Commands.Target(Commands.Kind.APP, "com.plexapp.android", "Plex", true),
            new Commands.Target(Commands.Kind.TAB, "apps", "Apps", true),
            new Commands.Target(Commands.Kind.WIDGET, "weather", "Weather", true),
            new Commands.Target(Commands.Kind.WIDGET, "inputs", "Inputs", true),
            new Commands.Target(Commands.Kind.SCENE, "movie", "Movie", true)));
        return tv;
    }
    static Capabilities.Action ask(String request, FakeTv tv) {
        Commands.Result interpreted = Commands.interpret(request, tv.registry());
        return interpreted.status == Commands.Status.READY ? Capabilities.of(interpreted.command) : null;
    }
    static void replace(FakeTv tv, String id, Commands.Target with) {
        for (int i = 0; i < tv.targets.size(); i++) if (tv.targets.get(i).id.equals(id)) {
            if (with == null) tv.targets.remove(i); else tv.targets.set(i, with);
            return;
        }
    }
    public static void main(String[] args) {
        FakeTv tv = new FakeTv();
        List<Capabilities.Action> actions = Capabilities.list(tv);
        expect(actions.size() == 2, "lists clock and inputs capabilities");
        Capabilities.Action hide = find(actions, Capabilities.Capability.CLOCK);
        expect(hide != null && hide.confirm && hide.available(), "clock action previews and asks first");
        Capabilities.Result done = Capabilities.run(hide, tv, 1L);
        expect(done.done && !tv.clock && "Hid the clock".equals(done.message), "confirming hide changes the clock");
        expect(Capabilities.Result.parse(tv.saved) != null && "Hid the clock".equals(Capabilities.Result.parse(tv.saved).message), "successful result round-trips");
        Capabilities.run(hide, tv, 2L);
        expect(!tv.clock, "running one preview twice stays idempotent");
        tv.inputs = true;
        Capabilities.Action showInputs = find(Capabilities.list(tv), Capabilities.Capability.INPUTS);
        Capabilities.Result shown = Capabilities.run(showInputs, tv, 3L);
        expect(shown.done && tv.inputsOn, "confirmed inputs action enables the widget");
        Capabilities.Action hideInputs = find(Capabilities.list(tv), Capabilities.Capability.INPUTS);
        Capabilities.run(hideInputs, tv, 4L);
        expect(!tv.inputsOn, "running the inputs action again hides the widget");
        tv.inputs = false;
        Capabilities.Action unavailable = find(Capabilities.list(tv), Capabilities.Capability.INPUTS);
        Capabilities.Result refused = Capabilities.run(unavailable, tv, 5L);
        expect(!refused.done && !tv.inputsOn, "unavailable inputs leave state unchanged");
        expect(tv.saved != null && Capabilities.Result.parse(tv.saved).at == 4L, "unavailable result does not replace last success");
        expect(Capabilities.Result.parse("2\tclock\t1\tbad") == null, "unknown result version is rejected");
        expect(Capabilities.Result.parse("1\tclock\tbad\tbad") == null, "damaged result time is rejected");

        FakeTv home = living();
        Capabilities.Action plex = ask("open Plex", home);
        expect(plex != null && plex.capability == Capabilities.Capability.APP && "com.plexapp.android".equals(plex.id)
            && "Open Plex".equals(plex.name) && !plex.preview.isEmpty() && plex.available(), "a typed request becomes a visible app preview for its registered package");
        expect(home.opened == null && home.saved == null, "interpreting a request runs nothing");
        Capabilities.Result opened = Capabilities.run(plex, home, 10L);
        expect(opened.done && "com.plexapp.android".equals(home.opened) && "Opened Plex".equals(opened.message), "confirming opens exactly the previewed app");
        Capabilities.Result saved = Capabilities.Result.parse(home.saved);
        expect(saved != null && saved.capability == Capabilities.Capability.APP && saved.at == 10L && "Opened Plex".equals(saved.message), "an app result is saved as a typed versioned record");

        FakeTv gone = living();
        Capabilities.Action stale = ask("open Plex", gone);
        replace(gone, "com.plexapp.android", null);
        Capabilities.Result missing = Capabilities.run(stale, gone, 11L);
        expect(!missing.done && gone.opened == null && gone.saved == null, "an app removed after preview does not open");

        FakeTv broken = living();
        broken.launches = false;
        Capabilities.Result failed = Capabilities.run(ask("open Plex", broken), broken, 12L);
        expect(!failed.done && broken.saved == null && !failed.message.isEmpty(), "an app Android cannot open is not reported as opened");

        FakeTv tabs = living();
        Capabilities.Action apps = ask("switch to Apps", tabs);
        Capabilities.Result switched = Capabilities.run(apps, tabs, 13L);
        expect(apps.capability == Capabilities.Capability.TAB && "apps".equals(tabs.tab) && "Switched to Apps".equals(switched.message), "a tab request selects the registered tab");

        FakeTv widgets = living();
        Capabilities.Action hideWeather = ask("hide Weather", widgets);
        Capabilities.run(hideWeather, widgets, 14L);
        Capabilities.Result again = Capabilities.run(hideWeather, widgets, 15L);
        expect(!widgets.widgetOn("weather") && again.done && "Hid Weather from Home".equals(again.message), "a widget request sets a fixed value, so confirming twice keeps Weather hidden");
        Capabilities.Action showInputsLater = ask("show Inputs", widgets);
        replace(widgets, "inputs", new Commands.Target(Commands.Kind.WIDGET, "inputs", "Inputs", false));
        Capabilities.Result noInputs = Capabilities.run(showInputsLater, widgets, 16L);
        expect(!noInputs.done && !widgets.inputsOn, "a widget that became unavailable after preview stays unchanged");

        FakeTv scenes = living();
        Capabilities.Action movie = ask("start Movie", scenes);
        Capabilities.Result notStarted = Capabilities.run(movie, scenes, 17L);
        expect(movie != null && !movie.available() && !notStarted.done && scenes.saved == null, "a scene never reports success without a scene adapter");

        expect(Capabilities.Result.parse("1\tclock\t7\tHid the clock").capability == Capabilities.Capability.CLOCK, "a saved clock result from the earlier build still parses");
        expect(Capabilities.Result.parse("1\tlauncher\t7\tbad") == null, "an unknown capability id is rejected");
        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
