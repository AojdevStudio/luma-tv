package dev.luma.tv;

import java.util.Arrays;
import java.util.Collections;

public final class MediaContextTest {
    static int failures;
    static final long MIN = 60_000L;
    static void expect(boolean ok, String message) {
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + message);
    }
    static void check(String name, Runnable test) {
        try { test.run(); } catch (RuntimeException error) { expect(false, name + " threw " + error); }
    }
    public static void main(String[] args) {
        check("progress", () -> {
            MediaContext movie = MediaContext.watched("Plex", MediaContext.Data.LIVE, 53 * MIN, 125 * MIN, null);
            expect("1 h 12 m left".equals(movie.progressText()), "known position and duration show the time left");
            expect(Math.abs(movie.progress - 53f / 125f) < .001f, "progress is the watched fraction");
            MediaContext unknown = MediaContext.watched("Plex", MediaContext.Data.LIVE, 0, 0, null);
            expect(unknown.progress < 0 && "Progress unavailable".equals(unknown.progressText()), "missing progress says unavailable, never zero");
            expect("Progress unavailable".equals(MediaContext.watched("Plex", MediaContext.Data.LIVE, 10 * MIN, 0, null).progressText()), "a position without a duration is not progress");
            expect("18 m left".equals(MediaContext.watched("YouTube", MediaContext.Data.LIVE, 42 * MIN, 60 * MIN, null).progressText()), "under an hour shows minutes only");
        });
        check("summary", () -> {
            MediaContext shared = MediaContext.watched("Plex", MediaContext.Data.LIVE, 0, 0, "  A heist  goes\nwrong. ");
            expect("A heist goes wrong.".equals(shared.summaryText()), "a shared description is shown as one clean line");
            MediaContext none = MediaContext.watched("Plex", MediaContext.Data.LIVE, 0, 0, null);
            expect(none.summary == null && "Summary unavailable. Plex shared no description.".equals(none.summaryText()), "no description shows an explicit unavailable state");
            expect(MediaContext.watched("Plex", MediaContext.Data.LIVE, 0, 0, "   ").summary == null, "a blank description is unavailable");
        });
        check("privacy", () -> {
            String leaked = MediaContext.concise("Resume at https://plex.example/library?X-Plex-Token=abc123 tonight. Or www.example.com/x");
            expect(leaked != null && !leaked.contains("://") && !leaked.contains("abc123") && !leaked.contains("www."), "links and their tokens never reach the summary");
            expect(MediaContext.concise("https://plex.example/?X-Plex-Token=abc123") == null, "a description that is only a link is unavailable");
            expect("See and".equals(MediaContext.concise("See plex.tv/watch?X-Plex-Token=example and mailto:viewer@example.invalid?token=example")), "scheme-less provider links and mailto links are removed with their query values");
        });
        check("concise", () -> {
            String longText = "First sentence is here. " + String.join(" ", Collections.nCopies(60, "word")) + ".";
            String cut = MediaContext.concise(longText);
            expect(cut.length() <= MediaContext.SUMMARY_MAX, "a long description is cut to the summary limit");
            expect(cut.startsWith("First sentence is here. word") && cut.endsWith("…"), "a long description keeps useful context after its first sentence");
            for (String prefix : Arrays.asList("Dr. Who", "S2 E4. The crew")) {
                String abbreviated = MediaContext.concise(prefix + " " + String.join(" ", Collections.nCopies(60, "word")));
                expect(abbreviated.startsWith(prefix + " word") && abbreviated.endsWith("…"), "a long summary keeps context after " + prefix);
            }
            String run = MediaContext.concise(String.join(" ", Collections.nCopies(60, "word")));
            expect(run.length() <= MediaContext.SUMMARY_MAX && run.endsWith("…") && !run.contains("wor…"), "a long run-on description ends at a whole word");
        });
        check("source", () -> {
            expect("Plex · Demo".equals(MediaContext.watched("Plex", MediaContext.Data.DEMO, 0, 0, null).label()), "the label names the source and demo state");
            expect("Spotify · Live".equals(MediaContext.watched("Spotify", MediaContext.Data.LIVE, 0, 0, null).label()), "the label names the source and live state");
            expect("Unknown app · Live".equals(MediaContext.watched("", MediaContext.Data.LIVE, 0, 0, null).label()), "a session from an unlisted app is not given a made-up name");
        });
        check("tag", () -> {
            expect("Plex, demo data".equals(MediaContext.tag(MediaContext.Data.DEMO, Arrays.asList("Plex", "Plex"))), "one source names it with the data state");
            expect("Live data".equals(MediaContext.tag(MediaContext.Data.LIVE, Arrays.asList("Plex", "YouTube"))), "mixed sources still say live");
            expect("".equals(MediaContext.tag(MediaContext.Data.LIVE, Collections.<String>emptyList())), "no items, no tag");
        });
        check("demo", () -> {
            expect(MediaContext.DEMO.length == 3, "three demo items, as in design B");
            String[] left = {"1 h 12 m left", "18 m left", "40 m left"};
            for (int i = 0; i < MediaContext.DEMO.length; i++) {
                MediaContext.Demo d = MediaContext.DEMO[i];
                expect(d.title.startsWith("Demo ") && d.context.data == MediaContext.Data.DEMO && "Plex".equals(d.context.source), "demo item " + i + " is a labelled Plex fixture");
                expect(left[i].equals(d.context.progressText()), "demo item " + i + " keeps the mock's time left");
            }
            expect(MediaContext.DEMO[0].context.summary != null && MediaContext.DEMO[1].context.summary == null, "the demo shows both a shared summary and the unavailable state");
        });
        if (failures > 0) { System.out.println(failures + " failed"); System.exit(1); }
        System.out.println("all media context checks passed");
    }
}
