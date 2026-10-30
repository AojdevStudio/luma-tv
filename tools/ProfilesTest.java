package dev.luma.tv;

import java.util.Arrays;
import java.util.List;

public final class ProfilesTest {
    static int failures;
    static final String YOUTUBE = "com.google.android.youtube.tv", PLEX = "com.plexapp.android", KIDS = "com.example.kids";
    static void expect(boolean ok, String message) {
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + message);
    }
    static List<String> list(String... ids) { return Arrays.asList(ids); }
    public static void main(String[] args) {
        Profiles normal = Profiles.DEFAULT;
        List<String> home = list(YOUTUBE, PLEX);
        expect(normal.mode == Profiles.Mode.NORMAL && normal.home(home).equals(home) && normal.allowsApp(KIDS, false), "Normal shows Home and every app");
        expect(normal.allowsWidget("upnext") && normal.allowsWidget("control") && normal.allows(Profiles.Content.SETTINGS), "Normal keeps personal media and settings");

        Profiles guest = normal.enter(Profiles.Mode.GUEST);
        expect(!guest.allowsWidget("upnext") && !guest.allowsWidget("music") && !guest.allows(Profiles.Content.PERSONAL), "Guest hides personal media");
        expect(!guest.allowsWidget("control") && !guest.allows(Profiles.Content.SETTINGS), "Guest hides settings");
        expect(guest.home(home).equals(home) && guest.allowsApp(PLEX, true) && !guest.allowsApp(KIDS, false), "Guest keeps the Home apps usable and no others");
        expect(guest.allowsWidget("weather") && guest.allowsWidget("house") && guest.allowsWidget("inputs"), "Guest keeps weather, scenes and inputs");
        expect(!guest.allowsWidget("future"), "an unknown widget stays hidden outside Normal");
        expect(normal.allowsWidget("future"), "an unknown widget stays visible in Normal");

        Profiles child = normal.enter(Profiles.Mode.CHILD);
        expect(child.noApps() && child.home(home).isEmpty() && !child.allowsApp(YOUTUBE, true), "Child with no approved apps approves nothing");
        Profiles emptyAfterRestart = Profiles.parse(child.line());
        expect(emptyAfterRestart != null && emptyAfterRestart.noApps(), "an empty approval survives restart as empty");
        expect(child.approve(list(YOUTUBE)).home(home).isEmpty(), "Child cannot approve apps for itself");

        Profiles configured = normal.approve(list(KIDS, YOUTUBE)).enter(Profiles.Mode.CHILD);
        expect(!configured.noApps() && configured.home(home).equals(list(KIDS, YOUTUBE)), "Child Home shows only approved apps, in order");
        expect(configured.allowsApp(KIDS, false) && !configured.allowsApp(PLEX, true), "Child opens only approved apps");
        expect(configured.allows(Profiles.Content.INFO) && !configured.allows(Profiles.Content.HOUSE) && !configured.allows(Profiles.Content.INPUTS)
            && !configured.allows(Profiles.Content.SETTINGS) && !configured.allows(Profiles.Content.PERSONAL), "Child allows only safe information");
        expect(configured.allowsWidget("weather") && !configured.allowsWidget("house") && !configured.allowsWidget("upnext"), "Child Home keeps only the weather widget");

        Profiles restarted = Profiles.parse(configured.line());
        expect(restarted != null && restarted.mode == Profiles.Mode.CHILD && restarted.home(home).equals(list(KIDS, YOUTUBE)), "mode and approved apps survive restart");
        Profiles back = restarted.enter(Profiles.Mode.NORMAL);
        expect(back.allows(Profiles.Content.SETTINGS) && back.allowsWidget("upnext") && back.home(home).equals(home), "leaving Child restores Normal Home");
        expect(back.enter(Profiles.Mode.CHILD).home(home).equals(list(KIDS, YOUTUBE)), "the approved list is kept for the next Child session");
        expect(guest.enter(Profiles.Mode.NORMAL).allowsWidget("music"), "leaving Guest restores personal media");

        expect(Profiles.parse(null) == null && Profiles.parse("") == null, "absent saved mode is not parsed");
        expect(Profiles.parse("2\tnormal\t") == null, "unknown version is rejected");
        expect(Profiles.parse("1\tparent\t") == null, "unknown mode is rejected");
        expect(Profiles.parse("1\tchild\tcom.a.b,com.a.b") == null, "duplicate approved apps are rejected");
        expect(Profiles.parse("1\tchild\tnot a package") == null, "damaged package is rejected");
        expect(Profiles.parse("1\tchild") == null, "missing field is rejected");
        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
