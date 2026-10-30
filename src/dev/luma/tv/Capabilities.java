package dev.luma.tv;

import java.util.ArrayList;
import java.util.List;

/** The user-space allowlist for Luma actions. It has no Android dependency so offline tests can run it directly. */
final class Capabilities {
    static final String LAST = "capability_last";

    interface Tv {
        boolean clockShown();
        boolean hasInputs();
        boolean widgetOn(String id);
        void showClock(boolean shown);
        void showWidget(String id, boolean on);
        void remember(String line);
    }

    enum Capability {
        CLOCK("clock"), INPUTS("inputs");
        final String id;
        Capability(String id) { this.id = id; }
        static Capability of(String id) {
            for (Capability capability : values()) if (capability.id.equals(id)) return capability;
            return null;
        }
    }

    static final class Action {
        final Capability capability;
        final String name;
        final String preview;
        final boolean confirm;
        final String unavailable;
        private final boolean target;
        private Action(Capability capability, String name, String preview, boolean confirm, String unavailable, boolean target) {
            this.capability = capability;
            this.name = name;
            this.preview = preview;
            this.confirm = confirm;
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
        actions.add(new Action(Capability.CLOCK, clock ? "Hide the clock" : "Show the clock",
            clock ? "Hides the clock in the top-right corner of every page." : "Shows the clock in the top-right corner of every page.",
            true, null, !clock));
        if (!tv.hasInputs()) {
            actions.add(new Action(Capability.INPUTS, "Show Inputs on Home",
                "Shows HDMI inputs on Home when the TV reports them.", true,
                "Luma cannot see an HDMI input service on this TV, so Inputs is unavailable.", true));
        } else {
            boolean inputs = tv.widgetOn("inputs");
            actions.add(new Action(Capability.INPUTS, inputs ? "Hide Inputs from Home" : "Show Inputs on Home",
                inputs ? "Hides the Inputs widget from Home." : "Shows the Inputs widget on Home.", true, null, !inputs));
        }
        return actions;
    }

    static Result run(Action previewed, Tv tv, long now) {
        Action fresh = null;
        for (Action action : list(tv)) if (action.capability == previewed.capability) { fresh = action; break; }
        if (fresh == null || !fresh.available()) return new Result(previewed.capability, false,
            fresh == null ? "This capability is no longer available." : fresh.unavailable, now);
        if (previewed.capability == Capability.CLOCK) tv.showClock(previewed.target);
        else if (previewed.capability == Capability.INPUTS) tv.showWidget("inputs", previewed.target);
        String message = previewed.target ? (previewed.capability == Capability.CLOCK ? "Showed the clock" : "Showed Inputs on Home")
            : (previewed.capability == Capability.CLOCK ? "Hid the clock" : "Hid Inputs from Home");
        Result result = new Result(previewed.capability, true, message, now);
        tv.remember(result.line());
        return result;
    }
}
