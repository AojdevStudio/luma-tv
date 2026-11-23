package dev.luma.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Remote journeys through Luma's Home, Apps and Capabilities panel, inside an isolated copy of the app. Remote keys
 *  drive every step except two: focus jumps straight to a tab, and Done on the TV keyboard is sent as its editor
 *  action. The checks read only the screen and Luma's private preferences, and the suite uses Android APIs alone,
 *  never Luma classes, so it also runs against older builds.
 *
 *  A journey that needs a process restart ends the run with "E2E NEXT <step>". tools/e2e/run.sh then starts the
 *  suite again with -e step <step>, and Android starts a new Luma process for it. */
public final class JourneysE2E extends Instrumentation {
    static final String LUMA = "dev.luma.tv.e2e.target/dev.luma.tv.MainActivity";
    static final long WAIT_MS = 20_000;
    static final String REQUEST = "switch to tab Settings";

    interface Step { void run() throws Exception; }
    interface Check { boolean ok() throws Exception; }
    interface Match { boolean test(View view); }

    private final StringBuilder report = new StringBuilder();
    private int failures;
    private String step = "", next;
    private Activity luma;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null && arguments.getString("step") != null) step = arguments.getString("step");
        start();
    }

    @Override public void onStart() {
        super.onStart();
        line("step " + (step.isEmpty() ? "first" : step) + " pid " + Process.myPid());
        switch (step) {
            case "":
                state().edit().clear().commit();
                test("Ask Cancel keeps the request, Preview focus and last success", () -> askCancel(KeyEvent.KEYCODE_DPAD_CENTER));
                test("Ask Back keeps the request, Preview focus and last success", () -> askCancel(KeyEvent.KEYCODE_BACK));
                test("The clock row changes its action in the same panel", this::clockRow);
                test("Sports applies after changing the owner's Home order", this::applySports);
                next = "sports-restarted";
                break;
            case "sports-restarted":
                test("Sports stays on Home after a restart, and Default restores the owner's Home", this::restoreDefault);
                next = "default-restarted";
                break;
            case "default-restarted":
                test("Default keeps the owner's Home order after a restart", this::defaultKept);
                test("Guest Apps holds exactly the apps Guest Home shows", this::enterGuest);
                next = "guest-restarted";
                break;
            case "guest-restarted":
                test("Guest Home and Apps keep the same apps after a restart", this::guestKept);
                break;
            default:
                test("known step", () -> { throw new AssertionError("unknown step " + step); });
        }
        if (luma != null) { luma.finish(); waitForIdleSync(); }
        Bundle result = new Bundle();
        if (next != null) report.append("E2E NEXT ").append(next).append('\n');
        report.append(failures == 0 ? "E2E PASS\n" : "E2E FAIL " + failures + "\n");
        result.putString(REPORT_KEY_STREAMRESULT, report.toString());
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    // Journeys

    /** 15785ab. Preview a request, then leave it with Cancel or remote Back. */
    private void askCancel(int leave) {
        fresh();
        openCapabilities();
        moveTo(KeyEvent.KEYCODE_DPAD_RIGHT, "Hide the clock");
        confirm();
        String previous = "Last: Hid the clock";
        waitFor("a successful clock action", () -> shown("Done. Hid the clock") != null && shown(previous) != null);
        String historyKey = "capability_last";
        String success = prefs().getString(historyKey, "");
        if (success.isEmpty()) throw new AssertionError("the successful clock action was not saved");
        moveTo(KeyEvent.KEYCODE_DPAD_LEFT, "Actions");
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "Ask");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        if (!(focused() instanceof EditText)) throw new AssertionError("Right from Ask did not reach the request line: " + label(focused()));
        sendStringSync(REQUEST);
        waitForIdleSync();
        // Done on the TV keyboard sends this editor action. Gboard keeps D-pad keys while it is connected.
        EditText line = (EditText) focused();
        runOnMainSync(() -> line.onEditorAction(EditorInfo.IME_ACTION_DONE));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) waitFor("the keyboard to close", () -> !keyboardShown());
        waitFor("Confirm after Preview", () -> "Confirm".equals(label(focused())));
        if (leave == KeyEvent.KEYCODE_DPAD_CENTER) { key(KeyEvent.KEYCODE_DPAD_RIGHT); expectFocus("Cancel"); }
        key(leave);
        waitFor("Cancelled. Nothing ran.", () -> shown("Cancelled. Nothing ran.") != null);
        expectFocus("Preview");
        EditText field = (EditText) find(v -> v instanceof EditText);
        if (field == null) throw new AssertionError("Ask closed: the request line is gone");
        if (!REQUEST.equals(text(field))) throw new AssertionError("the request line holds '" + text(field) + "'");
        if (shown(previous) == null || !success.equals(prefs().getString(historyKey, ""))) {
            throw new AssertionError("Ask did not keep the successful clock result");
        }
    }

    /** 0024e3c to c413e3b. After a clock action runs, its row offers the opposite action and runs it. Each
     *  problem is listed, so a failing run names every one. */
    private void clockRow() throws ReflectiveOperationException {
        fresh();
        openCapabilities();
        // The panel before c413e3b opens on the action rows. Later panels open on the Actions section.
        moveTo(KeyEvent.KEYCODE_DPAD_RIGHT, "Hide the clock");
        ArrayList<String> problems = new ArrayList<>();
        confirm();
        if (!turns(false)) problems.add("Confirm left the clock on");
        if (shown("Done. Hid the clock") == null) problems.add("the result does not say Done. Hid the clock");
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        for (int i = 0; i < 3 && !text(focused()).endsWith(" the clock"); i++) key(KeyEvent.KEYCODE_DPAD_UP);
        View row = focused();
        if (!"Show the clock".equals(text(row))) throw new AssertionError("focus is on '" + text(row) + "', expected Show the clock");
        if (!"Show the clock".equals(description(row))) problems.add("the row reads Show the clock but is spoken as " + description(row));
        if (find(v -> !v.isFocusable() && "Show the clock".equals(text(v))) == null) problems.add("the row says Show the clock but the pane does not");
        if (find(v -> !v.isFocusable() && "Hide the clock".equals(text(v))) != null) problems.add("the pane still previews Hide the clock");
        confirm();
        if (!turns(true)) problems.add("confirming the same row left the clock off");
        if (shown("Done. Showed the clock") == null) problems.add("the result does not say Done. Showed the clock");
        if (!problems.isEmpty()) throw new AssertionError(String.join("; ", problems));
    }

    /** OK on the focused row, then OK on Confirm. */
    private void confirm() {
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Confirm", () -> "Confirm".equals(label(focused())));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    /** Whether the corner clock and its saved setting reach this state. */
    private boolean turns(boolean on) {
        try { waitFor("the clock " + (on ? "on" : "off"), () -> clockShown() == on && prefs().getBoolean("clock", !on) == on); return true; }
        catch (AssertionError error) { return false; }
    }

    /** Move a tile first through the app menu, then apply Sports. */
    private void applySports() {
        fresh();
        List<String> factory = tiles();
        if (factory.size() < 2) throw new AssertionError("changing order needs two Home tiles: " + factory);
        List<String> owner = new ArrayList<>(factory);
        owner.add(0, owner.remove(1));
        if (owner.equals(factory)) throw new AssertionError("the first two Home tiles share a label: " + factory);
        expectFocus(factory.get(0) + ". OK: open.");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        expectFocus(owner.get(0) + ". OK: open.");
        int ok = KeyEvent.KEYCODE_DPAD_CENTER;
        long down = SystemClock.uptimeMillis();
        sendKeySync(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, ok, 0));
        try {
            waitFor("the app's hold-OK menu", () -> shown("Move to first position") != null);
        } finally {
            sendKeySync(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, ok, 0));
        }
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "Move to first position");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        expectTiles("the owner's reordered Home", join(owner));
        openCapabilities();
        chooseLayout("Sports");
        List<String> sports = tiles();
        if (sports.equals(owner)) throw new AssertionError("Sports left Home as " + sports);
        line("Owner Home tiles " + owner);
        line("Sports Home tiles " + sports);
        state().edit().putString("owner", join(owner)).putString("sports", join(sports)).putInt("pid", Process.myPid()).commit();
    }

    private void restoreDefault() {
        restarted();
        launch();
        expectTiles("Home after restart", state().getString("sports", null));
        openCapabilities();
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "Layouts");
        if (shown("Sports   On Home") == null) throw new AssertionError("Layouts does not mark Sports on Home");
        key(KeyEvent.KEYCODE_BACK);
        key(KeyEvent.KEYCODE_BACK);
        openCapabilities();
        chooseLayout("Default");
        expectTiles("Home after Default", state().getString("owner", null));
        state().edit().putInt("pid", Process.myPid()).commit();
    }

    private void defaultKept() {
        restarted();
        launch();
        expectTiles("Home after restart", state().getString("owner", null));
        openCapabilities();
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "Layouts");
        if (shown("Default   On Home") == null) throw new AssertionError("Layouts does not mark Default on Home");
    }

    /** 80030c3 to 125a75a. With more than six apps, Guest Apps lists only the apps Guest Home shows. */
    private void enterGuest() {
        fresh();
        page("Apps");
        int all = tiles().size();
        if (all <= 6) throw new AssertionError("this check needs more than six apps, found " + all);
        page("Home");
        press(waitView("mode control", v -> v.isFocusable() && description(v).startsWith("Normal mode")));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "Guest mode");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Confirm", () -> "Confirm".equals(label(focused())));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitView("Guest mode control", v -> v.isFocusable() && description(v).startsWith("Guest mode"));
        waitForHome();
        List<String> home = tiles();
        if (home.size() != 6) throw new AssertionError("Guest Home shows " + home.size() + " apps: " + home);
        expectApps(home);
        state().edit().putString("guest", join(home)).putInt("pid", Process.myPid()).commit();
    }

    private void guestKept() {
        restarted();
        launch();
        waitView("Guest mode control", v -> v.isFocusable() && description(v).startsWith("Guest mode"));
        expectTiles("Guest Home after restart", state().getString("guest", null));
        expectApps(tiles());
    }

    // Steps a viewer takes

    /** Finishes Luma, clears its private preferences, and starts it again in Normal with the owner's Home. */
    private void fresh() {
        if (luma != null) { luma.finish(); waitForIdleSync(); SystemClock.sleep(500); }
        prefs().edit().clear().commit();
        launch();
    }

    private void launch() {
        luma = startActivitySync(new Intent(Intent.ACTION_MAIN).setComponent(ComponentName.unflattenFromString(LUMA))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
        waitForHome();
    }

    private void restarted() {
        int before = state().getInt("pid", -1);
        if (before < 0) throw new AssertionError("the step before this one did not finish");
        if (before == Process.myPid()) throw new AssertionError("Luma did not restart: pid " + before);
    }

    private void openCapabilities() {
        page("Settings");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "Capabilities");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("the Capabilities panel", () -> !label(focused()).startsWith("Capabilities"));
    }

    /** Opens Layouts, applies the named layout with Confirm, and closes the panel. */
    private void chooseLayout(String name) {
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, "Layouts");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, name);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Confirm", () -> "Confirm".equals(label(focused())));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Close", () -> "Close".equals(label(focused())));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("the panel to close", () -> find(v -> "Actions".equals(description(v))) == null);
        page("Home");
        waitForHome();
    }

    private void page(String tab) {
        press(waitView(tab + " tab", v -> v.isFocusable() && v instanceof TextView && tab.equals(text(v))));
        waitForIdleSync();
        SystemClock.sleep(600);
    }

    /** Presses the key until focus reaches a view whose label starts with this text. */
    private void moveTo(int keyCode, String target) {
        for (int i = 0; i < 16 && !label(focused()).startsWith(target); i++) key(keyCode);
        if (!label(focused()).startsWith(target)) throw new AssertionError("focus did not reach " + target + ", it is on " + label(focused()));
    }

    /** Moves focus straight to the view, as a pointer would, and presses OK once focus stays there. */
    private void press(View view) {
        waitFor("focus on " + label(view), () -> {
            runOnMainSync(view::requestFocus);
            waitForIdleSync();
            SystemClock.sleep(300);
            return view.isFocused();
        });
        key(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    private void key(int code) {
        sendKeyDownUpSync(code);
        waitForIdleSync();
        SystemClock.sleep(250);
        waitForIdleSync();
    }

    // What the screen shows

    private void waitForHome() {
        String date = new SimpleDateFormat("EEEE, MMMM d", Locale.ENGLISH).format(new Date());
        waitFor("the Home date and a loaded app row", () -> shown(date) != null &&
            (!tiles().isEmpty() || shown("Your home is empty. Find your apps in the Apps tab.") != null));
    }

    private void expectFocus(String label) {
        String now = label(focused());
        if (!now.startsWith(label)) throw new AssertionError("focus is on '" + now + "', expected " + label);
    }

    private void expectTiles(String what, String expected) {
        List<String> now = tiles();
        if (!join(now).equals(expected)) throw new AssertionError(what + " shows " + now + ", expected " + expected);
    }

    private void expectApps(List<String> home) {
        page("Apps");
        waitFor("Guest Apps page", () -> shown("The apps on Home.") != null);
        List<String> apps = tiles(), sortedHome = new ArrayList<>(home);
        Collections.sort(apps);
        Collections.sort(sortedHome);
        page("Home");
        if (!apps.equals(sortedHome)) throw new AssertionError("Guest Apps lists " + apps + " but Guest Home shows " + sortedHome);
    }

    /** The app tiles on the page shown now, by label, in screen order. */
    private List<String> tiles() {
        ArrayList<String> labels = new ArrayList<>();
        runOnMainSync(() -> collect(luma.getWindow().getDecorView(), labels));
        return labels;
    }

    private static void collect(View view, List<String> labels) {
        if (!view.isAttachedToWindow() || !view.isShown()) return;
        String d = description(view);
        int end = d.indexOf(". OK: open.");
        if (view.isFocusable() && end > 0) { labels.add(d.substring(0, end)); return; }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), labels);
    }

    private boolean clockShown() throws ReflectiveOperationException {
        Field field = luma.getClass().getDeclaredField("clock");
        field.setAccessible(true);
        View clock = (View) field.get(luma);
        AtomicReference<Boolean> shown = new AtomicReference<>();
        runOnMainSync(() -> shown.set(clock.isShown()));
        return shown.get();
    }

    private boolean keyboardShown() {
        AtomicReference<Boolean> shown = new AtomicReference<>();
        runOnMainSync(() -> shown.set(luma.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime())));
        return shown.get();
    }

    private View focused() {
        AtomicReference<View> found = new AtomicReference<>();
        runOnMainSync(() -> found.set(luma.getWindow().getDecorView().findFocus()));
        return found.get();
    }

    private View shown(String exact) { return find(v -> exact.equals(text(v))); }

    private View waitView(String what, Match match) {
        AtomicReference<View> found = new AtomicReference<>();
        waitFor(what, () -> { found.set(find(match)); return found.get() != null; });
        return found.get();
    }

    private View find(Match match) {
        AtomicReference<View> found = new AtomicReference<>();
        runOnMainSync(() -> found.set(walk(luma.getWindow().getDecorView(), match)));
        return found.get();
    }

    private static View walk(View view, Match match) {
        if (!view.isAttachedToWindow() || !view.isShown()) return null;
        if (match.test(view)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = walk(((ViewGroup) view).getChildAt(i), match);
            if (found != null) return found;
        }
        return null;
    }

    private static String label(View view) {
        if (view == null) return "";
        String d = description(view);
        return d.isEmpty() ? text(view) : d;
    }

    private static String description(View view) { return view.getContentDescription() == null ? "" : view.getContentDescription().toString(); }
    private static String text(View view) { return view instanceof TextView ? ((TextView) view).getText().toString() : ""; }
    private static String join(List<String> labels) { return String.join("\n", labels); }

    private SharedPreferences prefs() { return getTargetContext().getSharedPreferences("luma", 0); }
    private SharedPreferences state() { return getTargetContext().getSharedPreferences("journeys-e2e", 0); }

    private void test(String name, Step body) {
        try { body.run(); line("ok    " + name); }
        catch (Throwable error) { failures++; Log.e("LumaJourneysE2E", name, error); line("FAIL  " + name + ": " + error); }
    }

    private void line(String text) {
        Log.i("LumaJourneysE2E", text);
        report.append(text).append('\n');
    }

    private static void waitFor(String what, Check check) {
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        try {
            while (!check.ok()) {
                if (SystemClock.uptimeMillis() > end) throw new AssertionError("timed out waiting for " + what);
                SystemClock.sleep(200);
            }
        } catch (AssertionError error) { throw error; }
        catch (Exception error) { throw new IllegalStateException(what, error); }
    }
}
