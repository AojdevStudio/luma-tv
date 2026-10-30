# Home layouts and Guest and Child modes

This note covers the domain code for issue #10 (Home layout presets) and issue #16 (Guest and Child modes). `HomePresets.java` and `Profiles.java` are plain Java with no Android imports. `tools/HomePresetsTest.java` and `tools/ProfilesTest.java` check them offline. The Android screens are separate work. Both issues stay open until the emulator and TCL proof exists.

## Home layouts

A layout is one wallpaper, an ordered list of Home widget IDs, an ordered list of Home app packages, and an ordered list of scene shortcut IDs.

### Shapes compared

| Shape | How Apply works | How Default comes back | Cost |
| --- | --- | --- | --- |
| Overlay (chosen) | Saves which layout is active under `home_presets`. Home renders that layout. | Choose Default. The owner's keys were never written. | One key. No copy of the owner's Home to go stale. |
| Snapshot and write | Copies the owner's Home, then writes the layout into `widget_order`, `widget_off`, `order`, `hidden` and `wallpaper`. | Write the copy back. | Two copies of the owner's Home. Settings edits made while a layout is active change the wrong one. |

The overlay is smaller and keeps the owner's default safe by construction. The owner's Home is still described by the existing keys. The integration code reads it into a `Layout` and passes it in as `owner`.

### Built-in layouts

Built-in layouts use only the existing widget IDs. They keep the owner's current wallpaper and add no new wallpaper. Plex (`com.plexapp.android`) replaces Netflix. The other packages already appear in the launcher's first-app list.

| Layout | Widgets | Apps | Scenes |
| --- | --- | --- | --- |
| Movies | house, upnext, music | Plex, Disney+, Prime Video | movie |
| Sports | upnext, inputs, weather | YouTube, Plex | none |
| News | weather, upnext | YouTube | none |
| Guests | weather, house | YouTube, Plex, YouTube Music | none |

`movie` is the only scene ID the source already uses. The Guests layout only arranges Home. Guest mode is what hides content.

### API

- `HomePresets.parse(prefs.getString(KEY, null))` returns `null` when the value is absent, damaged or from another version. Use `HomePresets.EMPTY` then. Default is active and there are no saved layouts.
- `choices()` lists Default, the four built-ins, then saved layouts.
- `preview(choice, owner, tv)` returns a `Proposal` and changes nothing. `proposal.missing` lists each app, widget or scene the TV lacks. The layout keeps those parts, so they come back once installed.
- `apply(proposal)` returns the new state. Applying Default restores the owner's Home.
- `save(name, layout)` stores a layout under a local name, replacing one with the same name. It does not apply it. It returns `null` for a blank name, a name that matches Default or a built-in, a control character, or an ID that cannot be stored.
- `current(owner)` is the Home to render now. `line()` is the value to persist.

### Integration through confirmed actions

1. Build `owner` from the existing keys: the wallpaper index, the enabled widgets in `WidgetBoard.order` order, and the Home apps that `visibleApps(false)` returns. Owner scenes are empty.
2. The picker calls `preview`. Home renders `proposal.layout` in memory only. Missing parts show as unavailable. With House Control absent, design B hides the Smart Home section rather than showing an empty one.
3. Back cancels the preview and writes nothing. Confirm writes `apply(proposal).line()` to `KEY`. A scene shortcut is never run by preview or apply.
4. An empty `scenes` list means the House widget shows House Control's own scene list unchanged.
5. A saved wallpaper index outside the launcher's list goes through the existing clamp in `wallpaperStyle()`.

## Guest and Child modes

`Profiles` holds the mode and Child's approved apps. Every surface asks it before it shows or runs an item.

| Content | Examples | Normal | Guest | Child |
| --- | --- | --- | --- | --- |
| `INFO` | weather, time | yes | yes | yes |
| `PERSONAL` | Up Next, Now Playing, greeting name, calendar | yes | no | no |
| `SETTINGS` | Settings tab, Control widget, app menus, layout picker, capability actions that change Luma | yes | no | no |
| `HOUSE` | Smart Home scenes and household state | yes | yes | no |
| `INPUTS` | HDMI inputs, live TV | yes | yes | no |

Apps follow one rule, `allowsApp(pkg, onHome)`:

- Normal allows every app.
- Guest allows the apps on the current Home.
- Child allows only the approved apps. An empty approved list approves nothing, and `noApps()` reports that state so Home can say so.

Only Normal can change the approved list. A widget the policy does not know shows only in Normal.

### API

- `Profiles.parse(prefs.getString(KEY, null))` returns `null` when the value is absent, damaged or from another version. Use `Profiles.DEFAULT` (Normal, nothing approved) then. Normal is always one press away, so this fallback loses no access.
- `enter(mode)` switches modes and keeps the approved list. `approve(apps)` sets the list from Normal and does nothing elsewhere.
- `home(homeApps)` returns the apps Home shows. `allowsWidget(id)`, `allows(content)` and `allowsApp(pkg, onHome)` answer every other surface. `line()` is the value to persist.

### Integration through confirmed actions

1. Read the mode before the first Home render, so restricted modes never flash Normal content.
2. Home widgets are `current(owner).widgets` filtered by `allowsWidget`. Home apps are `home(current(owner).apps)`. The Apps tab lists installed apps that pass `allowsApp`.
3. Each capability names one `Content`. The capability center lists only allowed ones, and `run` checks `allows` again at execution, so a stale preview cannot run after a mode change. `launch()` checks `allowsApp` before it starts an app.
4. The mode control is visible in every mode and sits outside Settings. Entering Guest or Child goes through the confirmation path. Returning to Normal is never gated.
5. This is a launcher presentation policy. It adds no PIN, account or OS sandbox, and it does not restrict what happens inside an approved app.
