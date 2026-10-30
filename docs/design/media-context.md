# Media context on Home

Prepares [#11](https://github.com/AojdevStudio/luma-tv/issues/11). Up Next and Now Playing now say where an item comes from, how far in it is, whether the data is live or demo, and give a summary only when the app shared one. The look follows design B in the capability mockup: an `Up Next  Plex, demo data` caption, a time-left line, and a progress bar.

## Shape

- `MediaContext` is plain Java with no Android classes. It holds the source app, `Data` (`LIVE` or `DEMO`), progress, time left, total time, and an optional summary. `tools/MediaContextTest.java` checks it on a computer.
- `MediaContext.watched(source, data, position, duration, description)` is the one way in for real data. A position or duration of zero or less means the app shared no progress, so the item says `Progress unavailable`. It never shows zero.
- `MediaContext.DEMO` holds the three demo items from design B (`Demo movie`, `Demo series, S2 E4`, `Demo show, S1 E1`) with the mockup's time left and bar widths. Only the movie has a summary, so the demo shows both states.

## Where the text comes from

| Surface | Source | Summary | Progress |
|---|---|---|---|
| Up Next, live | Android TV Watch Next row | `short_description` column | `last_playback_position_millis` and `duration_millis` |
| Up Next, demo | `MediaContext.DEMO` | Fixture text, labelled demo | Fixture values |
| Now Playing, live | Active media session | `METADATA_KEY_DISPLAY_DESCRIPTION` | Session position and `METADATA_KEY_DURATION` |
| Now Playing, demo | `Promo` song | None, so it shows unavailable | `Promo` clock |

When no description is shared, the line reads `Summary unavailable. <App> shared no description.`

## Privacy

- Summaries come only from metadata the app published for display. `MediaListener` still reads no notifications.
- `MediaContext.concise` removes scheme URLs, `www.` links, `mailto:` links and bare domain paths with their query values. It collapses whitespace and cuts long text at a word within 140 characters. An abbreviation or episode tag does not end the summary.
- The model holds no intent, link, or package token. Up Next keeps the Watch Next intent on its own `Item`, as before.

## Resume

OK goes through the same boundary as before. A Watch Next row with an intent calls `Host.start`. Anything else calls `Host.launch(pkg)`. Demo items use Plex's package, so on a TV without Plex the launcher says `This app is not installed.` Luma never claims playback resumed. The provider app owns that.

## Integration

- Home gives `upnext:` and `music:` descriptions two lines across the header. Other Home descriptions keep their original size.
- Failed provider links cannot currently fall back from `Host.start` to `Host.launch`. This existing boundary defect is tracked in [#22](https://github.com/AojdevStudio/luma-tv/issues/22).
- Guest and Child modes hide the `upnext` and `music` widgets through the shared mode policy. Media holds no second mode policy.
- The offline verifier runs `MediaContextTest` beside the Health and panel tests.
