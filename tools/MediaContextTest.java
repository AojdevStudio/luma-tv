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
            expect("Recap at tonight.".equals(MediaContext.watched("Plex", MediaContext.Data.LIVE, 0, 0, "Recap at WWW.example.invalid/?X-Plex-Token=scratch-secret tonight.").summaryText()), "uppercase WWW links and their tokens are removed from published summaries");
            expect("See and".equals(MediaContext.concise("See plex.tv/watch?X-Plex-Token=example and mailto:viewer@example.invalid?token=example")), "scheme-less provider links and mailto links are removed with their query values");
            for (String host : Arrays.asList("192.168.1.5:32400/library", "localhost:32400/", "plex.example.xn--p1ai/"))
                expect("Recap tonight.".equals(MediaContext.concise("Recap " + host + "?X-Plex-Token=scratch-secret tonight.")), "provider authority links are removed for " + host);
            for (String host : Arrays.asList("“plex.tv/watch", "[plex.tv/watch", "link:plex.tv/watch", "url=plex.tv/watch", "link:192.168.1.5:32400/library", "my_server.local/library", "bücher.example/library"))
                expect("Recap tonight.".equals(MediaContext.concise("Recap " + host + "?X-Plex-Token=scratch-secret tonight.")), "punctuated provider links are removed for " + host);
        });
        check("link rule", () -> {
            for (String link : Arrays.asList("//plex.tv/watch?token=scratch-secret", "//plex.tv", "//user@plex.tv", "Ready?plex.tv/watch?token=scratch-secret", "Ready#plex.tv/watch?token=scratch-secret", "Ready/plex.tv/watch?token=scratch-secret", "Readyplex.tv/watch?token=scratch-secret", "Ready..plex.tv/watch?token=scratch-secret"))
                expect("Recap tonight.".equals(MediaContext.concise("Recap " + link + " tonight.")), "links are scrubbed anywhere in a token for " + link);
            for (String glue : Arrays.asList("Ready", "Ready.", "Ready-", "Ready_"))
                for (String host : Arrays.asList("192.168.1.5", "192.168.1.5:32400", "localhost", "localhost:32400"))
                    expect("Recap tonight.".equals(MediaContext.concise("Recap " + glue + host + "/library?token=scratch-secret tonight.")), "a glued word does not hide the host in " + glue + host);
            for (String host : Arrays.asList("plex.tv", "192.168.1.5", "localhost", "[::1]", "plex.tv:32400"))
                for (String link : Arrays.asList("(//" + host + ")", "//" + host + ",", "//" + host + "-", "//" + host + "_"))
                    expect("Recap tonight.".equals(MediaContext.concise("Recap " + link + " tonight.")), "punctuation does not hide the host after // in " + link);
            for (String prose : Arrays.asList("8:30/7:30c", "Dr.", "U.S.", "Airs Sundays at 8:30/7:30c.", "Rating:8/10 on IMDb."))
                expect(prose.equals(MediaContext.concise(prose)), "the link rule preserves " + prose);
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
            expect("Wait...what? No.".equals(MediaContext.concise("Wait...what? No.")), "ordinary punctuation is not mistaken for a domain link");
            for (String prose : Arrays.asList("Set in L.A./Hollywood tonight.", "A U.S./Canada co-production.", "With Dr./Mrs. Smith tonight.", "Airs 9 a.m./p.m. daily.", "Filmed in D.C./Maryland.", "Use e.g./i.e. carefully.", "Pizza, pasta, etc.? Yes."))
                expect(prose.equals(MediaContext.concise(prose)), "abbreviation punctuation remains text for " + prose);
            String unspaced = String.join("", Collections.nCopies(8000, "a"));
            expect((unspaced.substring(0, MediaContext.SUMMARY_MAX - 1) + "…").equals(MediaContext.concise(unspaced)), "long unspaced metadata produces the bounded summary");
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
