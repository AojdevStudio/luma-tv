package dev.luma.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.media.tv.TvContract.WatchNextPrograms;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
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
            expectCard(luma, "Fixture-Shown · 1 m left · Fixture", .5f);
        });
        test("A poster becomes visible after a failed read at the same address", () -> {
            File file = File.createTempFile("upnext-poster", ".png", getTargetContext().getCacheDir());
            try {
                seed("Fixture-Poster", 30_000, PLAYER, "Fixture-Poster", 30_000, PLAYER, Uri.fromFile(file).toString());
                Activity luma = launchLuma(null);
                expectCard(luma, "Fixture-Poster · 1 m left · Fixture", .5f);
                settle();
                Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.MAGENTA);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new AssertionError("Could not write poster");
                }
                UpNextWidget widget = (UpNextWidget) find(luma, v -> v instanceof UpNextWidget);
                runOnMainSync(widget::refresh);
                waitFor("the fixture poster to appear", () -> find(luma, v -> {
                    if (!(v instanceof ImageView) || !(((ImageView) v).getDrawable() instanceof BitmapDrawable)) return false;
                    Bitmap shown = ((BitmapDrawable) ((ImageView) v).getDrawable()).getBitmap();
                    return shown.getWidth() == 8 && shown.getPixel(0, 0) == Color.MAGENTA;
                }) != null);
            } finally {
                if (!file.delete()) throw new AssertionError("Could not remove the test poster");
            }
        });
        test("A valid provider link opens the programme", () -> {
            seed("Fixture-Link", 30_000, PLAYER, "Fixture-Link", 30_000, PLAYER);
            Activity luma = launchLuma(null);
            press(expectCard(luma, "Fixture-Link · 1 m left · Fixture", .5f));
            waitResumed(PLAYER);
        });
        test("An OK press held across an unchanged refresh opens the programme", () -> {
            seed("Fixture-Held", 30_000, PLAYER, "Fixture-Held", 30_000, PLAYER);
            Activity luma = launchLuma(null);
            View card = expectCard(luma, "Fixture-Held · 1 m left · Fixture", .5f);
            runOnMainSync(card::requestFocus);
            waitForIdleSync();
            long down = SystemClock.uptimeMillis();
            sendKeySync(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            View upNext = find(luma, v -> v instanceof UpNextWidget);
            runOnMainSync(((UpNextWidget) upNext)::refresh);
            settle();
            expectCard(luma, "Fixture-Held · 1 m left · Fixture", .5f);
            sendKeySync(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            waitResumed(PLAYER);
        });
        test("Child mode hides Up Next and refuses an unapproved provider link", () -> {
            seed("Fixture-Child", 30_000, PLAYER, "Fixture-Child", 30_000, PLAYER);
            expectCard(launchLuma(null), "Fixture-Child · 1 m left · Fixture", .5f);
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
        test("Returning from playback shows progress saved in the same minute", () -> {
            seed("Fixture-Progress", 1_000, PLAYER, "Fixture-Progress", 50_000, PLAYER);
            Activity luma = launchLuma(null);
            press(expectCard(luma, "Fixture-Progress · 1 m left · Fixture", .03f));
            waitResumed(PLAYER);
            back();
            expectCard(luma, "Fixture-Progress · 1 m left · Fixture", 50f / 60f);
        });
        test("Returning from playback shows a new title with the same time left", () -> {
            seed("Fixture-Title", 30_000, PLAYER, "Fixture-Retitled", 30_000, PLAYER);
            Activity luma = launchLuma(null);
            View card = expectCard(luma, "Fixture-Title · 1 m left · Fixture", .5f);
            runOnMainSync(card::requestFocus);
            View heading = (View) field(luma, "heroTitle");
            waitFor("old title in the Home heading", () -> find(luma, v -> v == heading && text(v).equals("Fixture-Title")) != null);
            press(card);
            waitResumed(PLAYER);
            back();
            View updated = expectCard(luma, "Fixture-Retitled · 1 m left · Fixture", .5f);
            waitFor("new title on the card", () -> find(luma, v -> v != heading && text(v).equals("Fixture-Retitled") && v.getParent() == updated) != null);
            waitFor("new title in the Home heading", () -> find(luma, v -> v == heading && text(v).equals("Fixture-Retitled")) != null);
        });
        test("Returning from playback opens the provider's new link", () -> {
            seed("Fixture-Relink", 30_000, PLAYER, "Fixture-Relink", 30_000, PLAYER_NEXT);
            Activity luma = launchLuma(null);
            press(expectCard(luma, "Fixture-Relink · 1 m left · Fixture", .5f));
            waitResumed(PLAYER);
            back();
            // Only the link changed, so nothing visible marks the reload: wait for it to finish, then check the card.
            settle();
            press(expectCard(luma, "Fixture-Relink · 1 m left · Fixture", .5f));
            waitResumed(PLAYER_NEXT);
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
        seed(title, position, link, nextTitle, nextPosition, nextLink, null);
    }

    private void seed(String title, long position, String link, String nextTitle, long nextPosition, String nextLink, String poster) {
        getContext().startActivity(new Intent().setComponent(ComponentName.unflattenFromString(PLAYER))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("poster", poster).putExtra("clear", true).putExtra("finish", true)
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
        ArrayList<String> hiddenWidgets = new ArrayList<>(Arrays.asList(WidgetBoard.IDS));
        hiddenWidgets.remove("upnext");
        if (!getTargetContext().getSharedPreferences("luma", Context.MODE_PRIVATE).edit()
                .remove(HomePresets.KEY)
                .putString("widget_off", String.join(",", hiddenWidgets))
                .putString(Profiles.KEY, profile)
                .commit()) {
            throw new AssertionError("Could not save the test Home layout and profile");
        }
        Activity luma = startActivitySync(new Intent(Intent.ACTION_MAIN).setComponent(ComponentName.unflattenFromString(LUMA))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
        return luma;
    }

    /** Waits until an Up Next card reads exactly this description (title, time left, app) and draws this share of its
     *  progress bar, and returns it. */
    private View expectCard(Activity luma, String description, float progress) {
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        String seen;
        do {
            View card = find(luma, v -> v.isLaidOut() && v.isFocusable() && description.equals(description(v)));
            if (card == null) seen = "no card";
            else {
                float share = bar(card);
                if (Math.abs(share - progress) <= .02f) return card;
                seen = "a progress bar of " + share;
            }
            SystemClock.sleep(200);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("expected Up Next card '" + description + "' with a progress bar of " + progress + ", saw " + seen);
    }

    /** Waits until Up Next's background reads finish and Luma has drawn their results. */
    private void settle() {
        java.util.concurrent.ThreadPoolExecutor io = (java.util.concurrent.ThreadPoolExecutor) WidgetBoard.IO;
        waitFor("Up Next reads to finish", () -> io.getCompletedTaskCount() == io.getTaskCount());
        waitForIdleSync();
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

    /** The drawn length of the card's progress bar as a share of its track, or -1 when it shows no bar. */
    private float bar(View card) {
        int[] widths = new int[2];
        runOnMainSync(() -> {
            ViewGroup face = (ViewGroup) ((ViewGroup) card).getChildAt(0);
            ArrayList<View> bars = new ArrayList<>();
            for (int i = 0; i < face.getChildCount(); i++) {
                View child = face.getChildAt(i);
                if (child.getClass() == View.class && child.isShown() && child.getHeight() > 0 && child.getHeight() * 10 < face.getHeight()) bars.add(child);
            }
            if (bars.size() == 2) {
                // Up Next adds the track first and draws the fill over it.
                widths[0] = bars.get(0).getWidth();
                widths[1] = bars.get(1).getWidth();
            }
        });
        return widths[0] > 0 && widths[1] > 0 ? widths[1] / (float) widths[0] : -1;
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
