package dev.luma.tv;

import java.util.List;

public final class CapabilitiesTest {
    static int failures;
    static final class FakeTv implements Capabilities.Tv {
        boolean clock = true;
        boolean inputs;
        boolean inputsOn;
        String saved;
        public boolean clockShown() { return clock; }
        public boolean hasInputs() { return inputs; }
        public boolean widgetOn(String id) { return inputsOn; }
        public void showClock(boolean shown) { clock = shown; }
        public void showWidget(String id, boolean on) { inputsOn = on; }
        public void remember(String line) { saved = line; }
    }
    static void expect(boolean ok, String message) {
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + message);
    }
    static Capabilities.Action find(List<Capabilities.Action> actions, Capabilities.Capability capability) {
        for (Capabilities.Action action : actions) if (action.capability == capability) return action;
        return null;
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
        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
