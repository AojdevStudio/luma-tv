package dev.luma.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract.WatchNextPrograms;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Drives Luma's Up Next card against the real Android TV provider. The fixture app owns the Watch Next row, the
 *  player it opens, and the screens the test expects Android to show.
 *  Run it with tools/e2e/run.sh. It prints one ok or FAIL line per test and ends with E2E PASS or E2E FAIL. */
public final class MediaE2E extends Instrumentation {
    static final String FIXTURE = "dev.luma.tv.fixture";
    static final String PLAYER = FIXTURE + "/.Player", PLAYER_NEXT = FIXTURE + "/.PlayerNext", HIDDEN = FIXTURE + "/.Hidden", APP = FIXTURE + "/.Main";
    static final String LUMA = "dev.luma.tv.e2e.target/dev.luma.tv.MainActivity";
    static final long WAIT_MS = 20_000;

    interface Step { void run() throws Exception; }

    private final StringBuilder report = new StringBuilder();
    private int failures;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        test("Up Next shows the provider row and its progress", () -> {
            seed("Fixture-Shown", 30_000, PLAYER, "Fixture-Shown", 30_000, PLAYER);
            Activity luma = launchLuma(null);
            expectBar(card(luma, "Fixture-Shown"), .5f);
        });
        test("A valid provider link opens the programme", () -> {
            seed("Fixture-Link", 30_000, PLAYER, "Fixture-Link", 30_000, PLAYER);
            Activity luma = launchLuma(null);
            press(card(luma, "Fixture-Link"));
            waitResumed(PLAYER);
        });
        test("Child mode hides Up Next and refuses an unapproved provider link", () -> {
            seed("Fixture-Child", 30_000, PLAYER, "Fixture-Child", 30_000, PLAYER);
            card(launchLuma(null), "Fixture-Child");
            Activity luma = launchLuma(Profiles.DEFAULT.enter(Profiles.Mode.CHILD).line());
            waitFor("Child mode chip", () -> find(luma, v -> "Child mode".equals(text(v))) != null);
            if (find(luma, v -> "Up Next".equals(text(v))) != null) throw new AssertionError("Child mode shows Up Next");
            if (find(luma, v -> description(v).startsWith("Fixture-Child")) != null) throw new AssertionError("Child mode shows the owner's Watch Next row");
            Object host = field(luma, "boardHost");
            runOnMainSync(() -> {
                try { host.getClass().getMethod("start", Intent.class).invoke(host, new Intent().setComponent(ComponentName.unflattenFromString(PLAYER)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            });
            SystemClock.sleep(3_000);
            if (!resumed().equals(LUMA)) throw new AssertionError("Child mode opened an unapproved provider link: " + resumed());
        });
        Bundle result = new Bundle();
        report.append(failures == 0 ? "E2E PASS\n" : "E2E FAIL " + failures + "\n");
        result.putString(REPORT_KEY_STREAMRESULT, report.toString());
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void test(String name, Step step) {
        try { step.run(); line("ok    " + name); }
        catch (Throwable error) { failures++; Log.e("LumaMediaE2E", name, error); line("FAIL  " + name + ": " + error); }
    }

    private void line(String text) {
        Log.i("LumaMediaE2E", text);
        report.append(text).append('\n');
    }

    /** Replaces the fixture's Watch Next row and stores what its player saves when Luma opens it. Waits until the
     *  provider returns the row to Luma. */
    private void seed(String title, long position, String link, String nextTitle, long nextPosition, String nextLink) {
        getContext().startActivity(new Intent().setComponent(ComponentName.unflattenFromString(PLAYER))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("clear", true).putExtra("finish", true)
            .putExtra("title", title).putExtra("position", position).putExtra("link", uri(link))
            .putExtra("next_title", nextTitle).putExtra("next_position", nextPosition).putExtra("next_link", uri(nextLink)));
        waitFor("provider row " + title, () -> {
            try (Cursor rows = getTargetContext().getContentResolver().query(WatchNextPrograms.CONTENT_URI, new String[]{WatchNextPrograms.COLUMN_TITLE}, null, null, null)) {
                if (rows == null) return false;
                while (rows.moveToNext()) if (title.equals(rows.getString(0))) return true;
                return false;
            }
        });
        waitFor("fixture seed screen to close", () -> !resumed().equals(PLAYER));
    }

    private static String uri(String component) {
        return new Intent().setComponent(ComponentName.unflattenFromString(component)).toUri(Intent.URI_INTENT_SCHEME);
    }

    /** Starts a fresh Luma in Normal mode, or with the given saved profile line. */
    private Activity launchLuma(String profile) {
        getTargetContext().getSharedPreferences("luma", 0).edit().putString(Profiles.KEY, profile).commit();
        Activity luma = startActivitySync(new Intent(Intent.ACTION_MAIN).setComponent(ComponentName.unflattenFromString(LUMA))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
        return luma;
    }

    /** The Up Next card whose title is exactly this one. */
    private View card(Activity luma, String title) {
        AtomicReference<View> found = new AtomicReference<>();
        waitFor("Up Next card " + title, () -> {
            found.set(find(luma, v -> v.isFocusable() && description(v).startsWith(title + " · ")));
            return found.get() != null;
        });
        return found.get();
    }

    /** Focuses the card and presses OK on the remote, as a viewer would. */
    private void press(View card) {
        runOnMainSync(card::requestFocus);
        waitForIdleSync();
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    /** Presses Back on the remote in whatever app is in front, then waits for Luma to resume. */
    private void back() {
        shell("input keyevent KEYCODE_BACK");
        waitResumed(LUMA);
        waitForIdleSync();
    }

    /** The drawn length of the card's progress bar as a share of its track. */
    private void expectBar(View card, float expected) {
        AtomicReference<Float> share = new AtomicReference<>(-1f);
        runOnMainSync(() -> {
            ViewGroup face = (ViewGroup) ((ViewGroup) card).getChildAt(0);
            ArrayList<View> bars = new ArrayList<>();
            for (int i = 0; i < face.getChildCount(); i++) {
                View child = face.getChildAt(i);
                if (child.getClass() == View.class && child.getHeight() > 0 && child.getHeight() * 10 < face.getHeight()) bars.add(child);
            }
            if (bars.size() == 2) share.set(Math.min(bars.get(0).getWidth(), bars.get(1).getWidth()) / (float) Math.max(bars.get(0).getWidth(), bars.get(1).getWidth()));
        });
        if (Math.abs(share.get() - expected) > .02f) throw new AssertionError("progress bar shows " + share.get() + ", expected " + expected);
    }

    private void waitResumed(String component) {
        waitFor(component + " in front (now " + resumed() + ")", () -> resumed().equals(component));
    }

    /** The activity Android has in front, from the activity manager. */
    private String resumed() {
        for (String row : shell("dumpsys activity activities").split("\n"))
            if (row.contains("topResumedActivity=")) {
                for (String token : row.trim().split("\\s+")) {
                    ComponentName activity = ComponentName.unflattenFromString(token);
                    if (activity != null) return activity.flattenToShortString();
                }
            }
        return "";
    }

    private String shell(String command) {
        StringBuilder out = new StringBuilder();
        try (ParcelFileDescriptor pipe = getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).executeShellCommand(command);
             BufferedReader reader = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pipe)))) {
            for (String row; (row = reader.readLine()) != null; ) out.append(row).append('\n');
        } catch (java.io.IOException error) { throw new IllegalStateException(command, error); }
        return out.toString();
    }

    interface Check { boolean ok() throws Exception; }
    interface Match { boolean test(View view); }

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

    private View find(Activity activity, Match match) {
        AtomicReference<View> found = new AtomicReference<>();
        runOnMainSync(() -> found.set(walk(activity.getWindow().getDecorView(), match)));
        return found.get();
    }

    private static View walk(View view, Match match) {
        if (view.isAttachedToWindow() && view.isShown() && match.test(view)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = walk(((ViewGroup) view).getChildAt(i), match);
            if (found != null) return found;
        }
        return null;
    }

    private static String description(View view) { return view.getContentDescription() == null ? "" : view.getContentDescription().toString(); }
    private static String text(View view) { return view instanceof android.widget.TextView ? ((android.widget.TextView) view).getText().toString() : ""; }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
