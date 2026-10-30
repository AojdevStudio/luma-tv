package dev.luma.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class Commands {
    enum Kind { APP, TAB, WIDGET, SCENE }
    enum Verb { LAUNCH_APP, SELECT_TAB, SHOW_WIDGET, HIDE_WIDGET, START_SCENE }
    enum Status { READY, UNSUPPORTED, AMBIGUOUS, UNINSTALLED, UNAVAILABLE, UNSAFE }

    static final class Target {
        final Kind kind;
        final String id;
        final String name;
        final boolean available;
        private final String nameKey;
        private final String idKey;
        Target(Kind kind, String id, String name, boolean available) {
            this.kind = Objects.requireNonNull(kind);
            this.id = Objects.requireNonNull(id);
            this.name = Objects.requireNonNull(name);
            this.available = available;
            this.nameKey = normalize(name);
            this.idKey = normalize(id);
            if (nameKey.isEmpty() || idKey.isEmpty()) throw new IllegalArgumentException("A target needs an id and name.");
        }
    }

    static final class Registry {
        final List<Target> targets;
        Registry(List<Target> targets) {
            ArrayList<Target> snapshot = new ArrayList<>(targets);
            for (Target target : snapshot) Objects.requireNonNull(target);
            this.targets = Collections.unmodifiableList(snapshot);
        }
    }

    static final class Command {
        final Verb verb;
        final Target target;
        private Command(Verb verb, Target target) {
            this.verb = verb;
            this.target = target;
        }
    }

    static final class Result {
        final Status status;
        final Command command;
        final String message;
        private Result(Command command) {
            this.status = Status.READY;
            this.command = command;
            this.message = "";
        }
        private Result(Status status, String message) {
            this.status = status;
            this.command = null;
            this.message = message;
        }
    }

    private static final class Rule {
        final String prefix;
        final Kind kind;
        final Verb verb;
        Rule(String prefix, Kind kind, Verb verb) {
            this.prefix = prefix;
            this.kind = kind;
            this.verb = verb;
        }
    }

    private static final Rule[] RULES = {
        new Rule("open app ", Kind.APP, Verb.LAUNCH_APP),
        new Rule("open ", Kind.APP, Verb.LAUNCH_APP),
        new Rule("open ", Kind.TAB, Verb.SELECT_TAB),
        new Rule("switch to tab ", Kind.TAB, Verb.SELECT_TAB),
        new Rule("switch to ", Kind.TAB, Verb.SELECT_TAB),
        new Rule("go to ", Kind.TAB, Verb.SELECT_TAB),
        new Rule("show widget ", Kind.WIDGET, Verb.SHOW_WIDGET),
        new Rule("show ", Kind.WIDGET, Verb.SHOW_WIDGET),
        new Rule("hide widget ", Kind.WIDGET, Verb.HIDE_WIDGET),
        new Rule("hide ", Kind.WIDGET, Verb.HIDE_WIDGET),
        new Rule("start scene ", Kind.SCENE, Verb.START_SCENE),
        new Rule("start ", Kind.SCENE, Verb.START_SCENE)
    };

    static Result interpret(String request, Registry registry) {
        if (request == null) return new Result(Status.UNSUPPORTED, "Enter a short TV request.");
        if (request.contains("&&")) return new Result(Status.UNSAFE, "Shell commands and chained actions are not supported.");
        for (int i = 0; i < request.length(); i++) {
            char c = request.charAt(i);
            if (Character.isISOControl(c) || ";|`$<>\\".indexOf(c) >= 0)
                return new Result(Status.UNSAFE, "Shell commands and chained actions are not supported.");
        }
        String text = normalize(request);
        Command matched = null;
        boolean appRequest = false;
        boolean supported = false;
        for (Rule rule : RULES) {
            if (!text.startsWith(rule.prefix)) continue;
            supported = true;
            String name = text.substring(rule.prefix.length());
            appRequest |= rule.kind == Kind.APP;
            for (Target target : registry.targets) {
                if (target.kind != rule.kind || !(target.nameKey.equals(name) || target.idKey.equals(name))) continue;
                if (matched != null && (matched.target != target || matched.verb != rule.verb))
                    return new Result(Status.AMBIGUOUS, "More than one match. Use the TV controls to choose, or try a more specific request.");
                matched = new Command(rule.verb, target);
            }
        }
        if (!supported) return new Result(Status.UNSUPPORTED, "Try opening an app, switching tabs, showing a widget, or starting a scene.");
        if (matched == null) return appRequest
            ? new Result(Status.UNINSTALLED, "No installed app or registered tab matches that name.")
            : new Result(Status.UNSUPPORTED, "No registered target matches that request.");
        if (!matched.target.available) return matched.target.kind == Kind.APP
            ? new Result(Status.UNINSTALLED, "This app is not installed or cannot be opened on this TV.")
            : new Result(Status.UNAVAILABLE, "This target is unavailable on this TV.");
        return new Result(matched);
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
