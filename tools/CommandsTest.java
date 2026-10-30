package dev.luma.tv;

import java.util.ArrayList;
import java.util.Arrays;

public final class CommandsTest {
    static int failures;
    static void expect(boolean ok, String message) {
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + message);
    }
    static Commands.Target target(Commands.Kind kind, String id, String name, boolean available) {
        return new Commands.Target(kind, id, name, available);
    }
    static Commands.Registry registry() {
        return new Commands.Registry(Arrays.asList(
            target(Commands.Kind.APP, "com.plexapp.android", "Plex", true),
            target(Commands.Kind.APP, "test.absent", "Absent Player", false),
            target(Commands.Kind.TAB, "apps", "Apps", true),
            target(Commands.Kind.WIDGET, "weather", "Weather", true),
            target(Commands.Kind.WIDGET, "inputs", "Inputs", false),
            target(Commands.Kind.SCENE, "movie-night", "Movie Night", true),
            target(Commands.Kind.SCENE, "away", "Away", false)));
    }
    static void command(String text, Commands.Registry registry, Commands.Verb verb,
                        Commands.Kind kind, String id) {
        Commands.Result result = Commands.interpret(text, registry);
        expect(result.status == Commands.Status.READY && result.command != null
            && result.command.verb == verb && result.command.target.kind == kind
            && id.equals(result.command.target.id), text + " resolves its explicit target");
    }
    static void refused(String text, Commands.Registry registry, Commands.Status status) {
        Commands.Result result = Commands.interpret(text, registry);
        expect(result.status == status && result.command == null && !result.message.isEmpty(),
            String.valueOf(text) + " returns " + status + " without a command");
    }
    public static void main(String[] args) {
        Commands.Registry registry = registry();
        command("  OPEN   Plex  ", registry, Commands.Verb.LAUNCH_APP, Commands.Kind.APP, "com.plexapp.android");
        command("open app com.plexapp.android", registry, Commands.Verb.LAUNCH_APP, Commands.Kind.APP, "com.plexapp.android");
        command("switch to Apps", registry, Commands.Verb.SELECT_TAB, Commands.Kind.TAB, "apps");
        command("open Apps", registry, Commands.Verb.SELECT_TAB, Commands.Kind.TAB, "apps");
        command("show Weather", registry, Commands.Verb.SHOW_WIDGET, Commands.Kind.WIDGET, "weather");
        command("hide widget Weather", registry, Commands.Verb.HIDE_WIDGET, Commands.Kind.WIDGET, "weather");
        command("start scene Movie Night", registry, Commands.Verb.START_SCENE, Commands.Kind.SCENE, "movie-night");
        refused("play something", registry, Commands.Status.UNSUPPORTED);
        refused("open app Unknown Player", registry, Commands.Status.UNINSTALLED);
        refused("open Absent Player", registry, Commands.Status.UNINSTALLED);
        refused("show Inputs", registry, Commands.Status.UNAVAILABLE);
        refused("start Away", registry, Commands.Status.UNAVAILABLE);
        refused("start Unknown Scene", registry, Commands.Status.UNSUPPORTED);
        refused("open Pl", registry, Commands.Status.UNINSTALLED);
        refused("open Plex and start Movie Night", registry, Commands.Status.UNINSTALLED);
        refused("open Plex; reboot", registry, Commands.Status.UNSAFE);
        refused("open $(reboot)", registry, Commands.Status.UNSAFE);
        refused("open Plex && reboot", registry, Commands.Status.UNSAFE);
        refused("open Plex\nreboot", registry, Commands.Status.UNSAFE);
        refused("adb shell am start com.plexapp.android", registry, Commands.Status.UNSUPPORTED);
        refused(null, registry, Commands.Status.UNSUPPORTED);
        refused("   ", registry, Commands.Status.UNSUPPORTED);
        Commands.Registry duplicates = new Commands.Registry(Arrays.asList(
            target(Commands.Kind.APP, "test.first", "Player", true),
            target(Commands.Kind.APP, "test.second", " player ", false),
            target(Commands.Kind.SCENE, "first", "Evening", true),
            target(Commands.Kind.SCENE, "second", "Evening", true)));
        refused("open Player", duplicates, Commands.Status.AMBIGUOUS);
        refused("start Evening", duplicates, Commands.Status.AMBIGUOUS);
        Commands.Registry collision = new Commands.Registry(Arrays.asList(
            target(Commands.Kind.APP, "test.apps", "Apps", true),
            target(Commands.Kind.TAB, "apps", "Apps", true)));
        refused("open Apps", collision, Commands.Status.AMBIGUOUS);
        command("open app Apps", collision, Commands.Verb.LAUNCH_APP, Commands.Kind.APP, "test.apps");
        command("switch to tab Apps", collision, Commands.Verb.SELECT_TAB, Commands.Kind.TAB, "apps");
        Commands.Registry prefixedNames = new Commands.Registry(Arrays.asList(
            target(Commands.Kind.APP, "test.appstore", "App Store", true),
            target(Commands.Kind.TAB, "tab-switcher", "Tab Switcher", true),
            target(Commands.Kind.WIDGET, "gallery-id", "Widget Gallery", true),
            target(Commands.Kind.SCENE, "scene-one", "Scene 1", true)));
        command("open App Store", prefixedNames, Commands.Verb.LAUNCH_APP, Commands.Kind.APP, "test.appstore");
        command("switch to Tab Switcher", prefixedNames, Commands.Verb.SELECT_TAB, Commands.Kind.TAB, "tab-switcher");
        command("show Widget Gallery", prefixedNames, Commands.Verb.SHOW_WIDGET, Commands.Kind.WIDGET, "gallery-id");
        command("start Scene 1", prefixedNames, Commands.Verb.START_SCENE, Commands.Kind.SCENE, "scene-one");
        command("start Scene 1", new Commands.Registry(Arrays.asList(target(Commands.Kind.SCENE, "1", "Scene 1", true))),
            Commands.Verb.START_SCENE, Commands.Kind.SCENE, "1");
        refused("open App Store", new Commands.Registry(Arrays.asList(
            target(Commands.Kind.APP, "test.store", "Store", true),
            target(Commands.Kind.APP, "test.appstore", "App Store", true))), Commands.Status.AMBIGUOUS);
        ArrayList<Commands.Target> source = new ArrayList<>();
        source.add(target(Commands.Kind.APP, "test.player", "Player", true));
        Commands.Registry snapshot = new Commands.Registry(source);
        source.clear();
        command("open Player", snapshot, Commands.Verb.LAUNCH_APP, Commands.Kind.APP, "test.player");
        refused("open Plex", new Commands.Registry(source), Commands.Status.UNINSTALLED);
        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
