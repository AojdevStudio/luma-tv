package dev.luma.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class CapabilitiesTest {
    static int failures;
    static final class FakeTv implements Capabilities.Tv {
        boolean clock = true;
        boolean inputs;
        boolean inputsOn;
        boolean launches = true;
        final HashSet<String> off = new HashSet<>();
        final ArrayList<Commands.Target> targets = new ArrayList<>();
        Profiles profile = Profiles.DEFAULT;
        String saved, opened, tab, layout;
        /** House Control as last read, the read that readHouse fetches next, and the reply to the next scene run. */
        HouseScenes.Read house, fresh;
        Object reply = HouseScenesTest.map("ok", true, "direct", true);
        boolean lost, queued;
        Capabilities.Away away;
        String ran;
        Runnable held;
        final HashMap<String, String> sceneApps = new HashMap<>();
        public boolean clockShown() { return clock; }
        public boolean hasInputs() { return inputs; }
        public boolean widgetOn(String id) { return id.equals("inputs") ? inputsOn : !off.contains(id); }
        public void showClock(boolean shown) { clock = shown; }
        public void showWidget(String id, boolean on) { if (id.equals("inputs")) inputsOn = on; else if (on) off.remove(id); else off.add(id); }
        public void remember(String line) { saved = line; }
        public Commands.Registry registry() { return new Commands.Registry(targets); }
        public boolean openApp(String pkg) { if (launches) opened = pkg; return launches; }
        public void selectTab(String id) { tab = id; }
        public Profiles profile() { return profile; }
        public String homeLayout() { return layout; }
        public HouseScenes.Read house() { return house; }
        public void readHouse(Runnable done) { if (fresh != null) house = fresh; done.run(); }
        public void runScene(HouseScenes.Scene scene, Supplier<String> refusal, Consumer<HouseScenes.Outcome> done) {
            Runnable send = () -> {
                String why = refusal.get();
                if (why != null) { done.accept(HouseScenes.notRun(why)); return; }
                ran = scene.id;
                done.accept(lost ? HouseScenes.unknown(scene) : HouseScenes.outcome(scene, reply));
            };
            if (queued) held = send; else send.run();
        }
        public String sceneApp(String scene) { return sceneApps.getOrDefault(scene, ""); }
        public Capabilities.Away away() { return away; }
    }
    static Capabilities.Result run(Capabilities.Action action, FakeTv tv, long now) {
        ArrayList<Capabilities.Result> results = new ArrayList<>();
        Capabilities.run(action, tv, now, results::add);
        expect(results.size() == 1, action.name + " answers exactly once");
        return results.get(0);
    }
    static FakeTv house(boolean ready) {
        FakeTv tv = living();
        tv.house = HouseScenes.read(HouseScenesTest.home("r1", ready, HouseScenesTest.loftScenes()));
        tv.sceneApps.put("movie", "com.plexapp.android");
        return tv;
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
        return interpreted.status == Commands.Status.READY ? Capabilities.of(interpreted.command, tv) : null;
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
        Capabilities.Result done = run(hide, tv, 1L);
        expect(done.done() && !tv.clock && "Hid the clock".equals(done.message), "confirming hide changes the clock");
        expect(Capabilities.Result.parse(tv.saved) != null && "Hid the clock".equals(Capabilities.Result.parse(tv.saved).message), "successful result round-trips");
        run(hide, tv, 2L);
        expect(!tv.clock, "running one preview twice stays idempotent");
        tv.inputs = true;
        Capabilities.Action showInputs = find(Capabilities.list(tv), Capabilities.Capability.INPUTS);
        Capabilities.Result shown = run(showInputs, tv, 3L);
        expect(shown.done() && tv.inputsOn, "confirmed inputs action enables the widget");
        Capabilities.Action hideInputs = find(Capabilities.list(tv), Capabilities.Capability.INPUTS);
        run(hideInputs, tv, 4L);
        expect(!tv.inputsOn, "running the inputs action again hides the widget");
        tv.inputs = false;
        Capabilities.Action unavailable = find(Capabilities.list(tv), Capabilities.Capability.INPUTS);
        Capabilities.Result refused = run(unavailable, tv, 5L);
        expect(!refused.done() && !tv.inputsOn, "unavailable inputs leave state unchanged");
        expect(tv.saved != null && Capabilities.Result.parse(tv.saved).at == 4L, "unavailable result does not replace last success");
        expect(Capabilities.Result.parse("2\tclock\t1\tbad") == null, "unknown result version is rejected");
        expect(Capabilities.Result.parse("1\tclock\tbad\tbad") == null, "damaged result time is rejected");

        FakeTv home = living();
        Capabilities.Action plex = ask("open Plex", home);
        expect(plex != null && plex.capability == Capabilities.Capability.APP && "com.plexapp.android".equals(plex.id)
            && "Open Plex".equals(plex.name) && !plex.preview.isEmpty() && plex.available(), "a typed request becomes a visible app preview for its registered package");
        expect(home.opened == null && home.saved == null, "interpreting a request runs nothing");
        Capabilities.Result opened = run(plex, home, 10L);
        expect(opened.done() && "com.plexapp.android".equals(home.opened) && "Opened Plex".equals(opened.message), "confirming opens exactly the previewed app");
        Capabilities.Result saved = Capabilities.Result.parse(home.saved);
        expect(saved != null && saved.capability == Capabilities.Capability.APP && saved.at == 10L && "Opened Plex".equals(saved.message), "an app result is saved as a typed versioned record");

        FakeTv gone = living();
        Capabilities.Action stale = ask("open Plex", gone);
        replace(gone, "com.plexapp.android", null);
        Capabilities.Result missing = run(stale, gone, 11L);
        expect(!missing.done() && gone.opened == null && gone.saved == null, "an app removed after preview does not open");

        FakeTv broken = living();
        broken.launches = false;
        Capabilities.Result failed = run(ask("open Plex", broken), broken, 12L);
        expect(!failed.done() && broken.saved == null && !failed.message.isEmpty(), "an app Android cannot open is not reported as opened");

        FakeTv tabs = living();
        Capabilities.Action apps = ask("switch to Apps", tabs);
        Capabilities.Result switched = run(apps, tabs, 13L);
        expect(apps.capability == Capabilities.Capability.TAB && "apps".equals(tabs.tab) && "Switched to Apps".equals(switched.message), "a tab request selects the registered tab");

        FakeTv widgets = living();
        Capabilities.Action hideWeather = ask("hide Weather", widgets);
        run(hideWeather, widgets, 14L);
        Capabilities.Result again = run(hideWeather, widgets, 15L);
        expect(!widgets.widgetOn("weather") && again.done() && "Hid Weather from Home".equals(again.message), "a widget request sets a fixed value, so confirming twice keeps Weather hidden");
        Capabilities.Action showInputsLater = ask("show Inputs", widgets);
        replace(widgets, "inputs", new Commands.Target(Commands.Kind.WIDGET, "inputs", "Inputs", false));
        Capabilities.Result noInputs = run(showInputsLater, widgets, 16L);
        expect(!noInputs.done() && !widgets.inputsOn, "a widget that became unavailable after preview stays unchanged");

        FakeTv scenes = house(true);
        List<Capabilities.Action> listed = Capabilities.scenes(scenes);
        Capabilities.Action movie = listed.get(1);
        expect(listed.size() == 7 && "Start Movie".equals(movie.name) && movie.available(), "Scenes lists every scene House Control reads, all able to run");
        expect(movie.preview.equals("Home. Ceiling in Studio, Bedside, Desk lamp and 4 more off. TV ambient light on.\nTV. Opens Plex after the scene finishes.\nSource. House Control TV, your home."),
            "the Movie preview names its light changes, Plex and the real home");
        Capabilities.Action asked = ask("start Movie", scenes);
        expect(asked != null && asked.preview.equals(movie.preview) && scenes.ran == null && scenes.saved == null && scenes.opened == null,
            "a typed scene request shows the same preview and runs nothing");
        Capabilities.Result started = run(movie, scenes, 17L);
        expect(started.status == Capabilities.Status.DONE && "movie".equals(scenes.ran) && "com.plexapp.android".equals(scenes.opened)
            && "Started Movie. Opened Plex".equals(started.message), "confirming runs exactly that scene, then opens Plex");
        expect(Capabilities.Result.parse(scenes.saved).capability == Capabilities.Capability.SCENE && "Started Movie. Opened Plex".equals(Capabilities.Result.parse(scenes.saved).message),
            "a started scene is saved as the last result");
        expect(listed.get(0).preview.contains("TV. Stays on Luma."), "a scene without an app keeps the TV on Luma");

        FakeTv failing = house(true);
        failing.reply = HouseScenesTest.map("error", "Ceiling: device offline");
        Capabilities.Result unfinished = run(Capabilities.scenes(failing).get(1), failing, 18L);
        expect(unfinished.status == Capabilities.Status.NOT_FINISHED && unfinished.message.contains("device offline") && failing.opened == null && failing.saved == null,
            "House Control's error is reported, opens no app and replaces no saved result");
        FakeTv lost = house(true);
        lost.lost = true;
        Capabilities.Result unknown = run(Capabilities.scenes(lost).get(1), lost, 19L);
        expect(unknown.status == Capabilities.Status.UNKNOWN && lost.opened == null && lost.saved == null, "a run whose answer was lost is unknown and opens no app");

        FakeTv offline = house(false);
        Capabilities.Action blocked = Capabilities.scenes(offline).get(1);
        Capabilities.Result notReady = run(blocked, offline, 20L);
        expect(!blocked.available() && blocked.unavailable.contains("Connect Smart Life") && notReady.status == Capabilities.Status.NOT_RUN && offline.ran == null,
            "a home that cannot control devices lists the scene as unavailable and sends nothing");
        FakeTv changed = house(true);
        Capabilities.Action before = Capabilities.scenes(changed).get(1);
        changed.fresh = HouseScenes.read(HouseScenesTest.home("r2", true, HouseScenesTest.loftScenes()));
        Capabilities.Result outdated = run(before, changed, 21L);
        expect(outdated.status == Capabilities.Status.NOT_RUN && outdated.message.contains("changed since this preview") && changed.ran == null,
            "a plan that changed after the preview is read again before running, and nothing runs");
        FakeTv silent = house(true);
        Capabilities.Action vanished = Capabilities.scenes(silent).get(1);
        silent.fresh = HouseScenes.Read.failed("House Control did not answer. Open House Control to reconnect.");
        expect(run(vanished, silent, 22L).message.contains("did not answer") && silent.ran == null, "House Control that stopped answering runs nothing");

        FakeTv child = house(true);
        Capabilities.Action childMovie = Capabilities.scenes(child).get(1);
        child.profile = Profiles.DEFAULT.enter(Profiles.Mode.CHILD);
        expect(!Capabilities.scenes(child).get(1).available() && run(childMovie, child, 23L).status == Capabilities.Status.NOT_RUN && child.ran == null,
            "Child mode runs no scene, also one previewed in Normal");
        FakeTv queue = house(true);
        queue.queued = true;
        ArrayList<Capabilities.Result> afterQueue = new ArrayList<>();
        Capabilities.run(Capabilities.scenes(queue).get(1), queue, 23L, afterQueue::add);
        queue.profile = Profiles.DEFAULT.enter(Profiles.Mode.CHILD);
        queue.held.run();
        expect(afterQueue.size() == 1 && afterQueue.get(0).status == Capabilities.Status.NOT_RUN && queue.ran == null && queue.saved == null,
            "a scene still waiting to be sent when Child mode starts is not sent");
        FakeTv visitor = house(true);
        visitor.profile = Profiles.DEFAULT.enter(Profiles.Mode.GUEST);
        replace(visitor, "com.plexapp.android", new Commands.Target(Commands.Kind.APP, "com.plexapp.android", "Plex", false));
        Capabilities.Action guestMovie = Capabilities.scenes(visitor).get(1);
        Capabilities.Result guestRun = run(guestMovie, visitor, 24L);
        expect(guestMovie.preview.contains("Guest mode does not open Plex") && guestRun.done() && "movie".equals(visitor.ran) && visitor.opened == null
            && guestRun.message.equals("Started Movie. Guest mode does not open Plex"), "Guest runs the scene but not an app it hides");
        FakeTv closed = house(true);
        closed.away = Capabilities.Away.CLOSED;
        Capabilities.Result late = run(Capabilities.scenes(closed).get(1), closed, 25L);
        expect(late.done() && closed.opened == null && late.message.equals("Started Movie. The panel closed first, so Plex did not open"), "a result after the panel closed opens no app");
        FakeTv paused = house(true);
        paused.away = Capabilities.Away.OFF_SCREEN;
        Capabilities.Result unseen = run(Capabilities.scenes(paused).get(1), paused, 25L);
        expect(unseen.done() && paused.opened == null && unseen.message.equals("Started Movie. Luma was not on screen, so Plex did not open"),
            "a result while Luma is off screen opens no app and says so, not that the panel closed");
        FakeTv uninstalled = house(true);
        replace(uninstalled, "com.plexapp.android", null);
        Capabilities.Action noPlex = Capabilities.scenes(uninstalled).get(1);
        Capabilities.Result withoutPlex = run(noPlex, uninstalled, 26L);
        expect(noPlex.preview.contains("com.plexapp.android is chosen") && withoutPlex.message.equals("Started Movie. com.plexapp.android is not installed, so no app opened"),
            "an app that is not installed is named by its package and stays shut");

        FakeTv demo = house(true);
        demo.house = HouseScenes.read(HouseScenesTest.map("demo", true, "controlReady", true, "revision", "demo-1",
            "plan", HouseScenesTest.map("lights", HouseScenesTest.list(HouseScenesTest.map("id", "main.tv", "name", "TV ambient light", "room", "main", "linked", false)),
                "scenes", HouseScenesTest.list(HouseScenesTest.scene("movie", "Movie", "lights", HouseScenesTest.map("*", HouseScenesTest.map("on", true)))))));
        demo.reply = HouseScenesTest.map("ok", true, "demo", true);
        Capabilities.Action demoMovie = Capabilities.scenes(demo).get(0);
        Capabilities.Result demoRun = run(demoMovie, demo, 27L);
        expect(demoMovie.preview.startsWith("Home. TV ambient light on.\nTV.") && demoMovie.preview.endsWith("demo home. No real devices change.")
            && demoRun.message.startsWith("Started Movie on the demo home"), "the demo home is labelled by its source in the preview and the result");
        FakeTv partly = house(true);
        partly.house = HouseScenes.read(HouseScenesTest.partlyLinked());
        List<Capabilities.Action> simulated = Capabilities.scenes(partly);
        expect(simulated.get(0).preview.startsWith("Home. Ceiling off.\nSimulated. Desk lamp off. These are not linked to real devices.\nTV.")
            && simulated.get(1).preview.startsWith("Home. No real device changes.\nSimulated. Desk lamp on. These are not linked to real devices.\nTV."),
            "a preview keeps devices House Control only simulates apart from real changes");

        FakeTv movies = living();
        movies.layout = "Movies";
        Capabilities.Result overridden = run(ask("hide Weather", movies), movies, 18L);
        expect(!overridden.done() && movies.widgetOn("weather") && movies.saved == null && overridden.message.contains("Movies"),
            "Hide Weather is refused while the Movies layout is on Home, and Weather stays");
        movies.inputs = true;
        Capabilities.Action inputsUnderLayout = find(Capabilities.list(movies), Capabilities.Capability.INPUTS);
        expect(!inputsUnderLayout.available() && !run(inputsUnderLayout, movies, 19L).done() && !movies.inputsOn,
            "Inputs cannot be shown on Home while a layout replaces it");
        expect(run(find(Capabilities.list(movies), Capabilities.Capability.CLOCK), movies, 20L).done() && !movies.clock,
            "the clock still changes while a layout is on Home");
        movies.layout = null;
        expect(run(ask("hide Weather", movies), movies, 21L).done() && !movies.widgetOn("weather"), "back on Default, Hide Weather runs");

        FakeTv guest = living();
        Capabilities.Action clockBefore = find(Capabilities.list(guest), Capabilities.Capability.CLOCK);
        Capabilities.Action weatherBefore = ask("hide Weather", guest);
        guest.profile = Profiles.DEFAULT.enter(Profiles.Mode.GUEST);
        expect(!run(clockBefore, guest, 22L).done() && guest.clock && !run(weatherBefore, guest, 23L).done()
            && guest.widgetOn("weather") && guest.saved == null, "an action previewed in Normal is refused after Guest mode starts");
        expect(!find(Capabilities.list(guest), Capabilities.Capability.CLOCK).available(), "Guest mode lists Luma settings as unavailable");
        guest.profile = guest.profile.enter(Profiles.Mode.NORMAL);
        expect(run(clockBefore, guest, 24L).done() && !guest.clock, "back in Normal the same action runs");

        expect(Capabilities.Result.parse("1\tclock\t7\tHid the clock").capability == Capabilities.Capability.CLOCK, "a saved clock result from the earlier build still parses");
        expect(Capabilities.Result.parse("1\tlauncher\t7\tbad") == null, "an unknown capability id is rejected");
        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
