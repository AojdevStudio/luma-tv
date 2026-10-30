# Home layouts and Guest and Child modes

This note covers the domain code for issue #10 (Home layout presets) and issue #16 (Guest and Child modes). `HomePresets.java` and `Profiles.java` are plain Java with no Android imports. `tools/HomePresetsTest.java` and `tools/ProfilesTest.java` check them offline. `MainActivity` wires them into Home, the Apps tab and the Capabilities panel, as design B. Both issues stay open until the emulator and TCL proof exists.

## Where the controls are

- Settings, Capabilities, Layouts lists Default, Movies, Sports, News, Guests and the saved layouts. Under them, a name field and Save this Home save the Home shown now.
- The mode control sits in the tab bar, left of Home, on every page and in every mode. It names the mode, for example `Guest mode`. OK opens the panel at Mode with Normal focused.
- Settings, Capabilities, Mode lists Normal, Guest and Child. In Normal it also lists every installed app under Child apps. OK on an app approves it for Child mode or removes it, and saves at once, like the Home widgets switches.
- The panel is light by default. Its Dark mode row gives a true black panel with white text.

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

Movies selects the existing `movie` scene. The Guests layout only arranges Home. Guest mode is what hides content.

### API

- `HomePresets.parse(prefs.getString(KEY, null))` returns `null` when the value is absent, damaged or from another version. Use `HomePresets.EMPTY` then. Default is active and there are no saved layouts.
- `choices()` lists Default, the four built-ins, then saved layouts.
- `preview(choice, owner, tv)` returns a `Proposal` and changes nothing. `proposal.missing` lists each app, widget or scene the TV lacks. The layout keeps those parts, so they come back once installed.
- `apply(proposal)` returns the new state. Applying Default restores the owner's Home.
- `save(name, layout)` returns a record with the layout saved under a local name, replacing one with the same name. The active choice stays the same. If that choice names the replaced layout, persisting the returned record changes Home immediately. Preview and confirm Save before persisting it. The method returns `null` for a blank name, a name that matches Default or a built-in, a control character, or an ID that cannot be stored.
- `current(owner)` is the Home to render now. `line()` is the value to persist.

### Integration through confirmed actions

1. Build `owner` from the existing keys: the wallpaper index, the enabled widgets in `WidgetBoard.order` order, and the Home apps that `visibleApps(false)` returns. Owner scenes are empty.
2. Focus on a layout calls `preview` and shows its widgets, apps, scenes and wallpaper in the panel's preview pane. Home stays as it is. `CapabilityPanel.layout` lists every missing part under Unavailable. A missing app shows its package, because the TV has no name for it. With House Control absent, design B hides the Smart Home section rather than showing an empty one.
3. OK asks first. Confirm writes `apply(proposal).line()` to `KEY`. Cancel and Back write nothing. A scene shortcut is never run by preview or apply.
4. Home shows `current(owner)`. Its widgets go to `WidgetBoard.pages`, its apps to the favourites, its wallpaper to `wallpaperStyle()`, and its scenes to the House widget. An empty `scenes` list means the House widget shows House Control's own scene list unchanged.
5. A saved wallpaper index outside the launcher's list goes through the existing clamp in `wallpaperStyle()`.
6. Save previews the Home shown now with the same pane and asks first. The note says when the name replaces a saved layout, and when Home would change at once.

### Changes while a layout is on Home

Settings edits only the owner's Default. While another layout is on Home, an edit there would not show, so Luma refuses it and says why. This is the simplest reading that keeps Default safe and never reports a change Home does not show.

- Show and Hide for a widget, from Actions or Ask, and the Inputs action are refused. `Capabilities.run` checks again right before it acts. The clock is not part of a layout and still changes.
- Home widgets, Wallpaper, and the app menu's Organize show a notice that names the layout and points to Default in Layouts.
- Choosing Default brings the owner's Home back unchanged.

## Guest and Child modes

`Profiles` holds the mode and Child's approved apps. Every surface asks it before it shows or runs an item.

| Content | Examples | Normal | Guest | Child |
| --- | --- | --- | --- | --- |
| `INFO` | weather, time | yes | yes | yes |
| `PERSONAL` | Up Next, Now Playing, greeting name | yes | no | no |
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
- `home(homeApps)` filters the proposed Home apps by mode. `allowsWidget(id)`, `allows(content)` and `allowsApp(pkg, onHome)` answer every other surface. `line()` is the value to persist.

### Integration through confirmed actions

1. Read the mode before the first Home render, so restricted modes never flash Normal content.
2. Home widgets are `current(owner).widgets` filtered by `allowsWidget`. `MainActivity.homeApps()` takes the first six installed apps from `home(current(owner).apps)`. Rendering, Guest policy and requests use that same list. Missing layout entries remain saved.
3. Each capability names one `Content`. Outside Normal the panel opens Mode alone, except that Guest also keeps Scenes when House Control is installed. `run` checks `allows` again at execution, so a stale preview cannot run after a mode change. The command registry marks apps the mode refuses, and the Settings tab outside Normal, as unavailable.
4. `launch()` checks `allowsApp` before it starts an app. Every app open from a tile, a widget, a scene or a capability goes through it. A widget that starts any other intent goes through the same check outside Normal, and an intent Luma cannot resolve stays shut. A confirmed scene that would open a refused app still runs, opens nothing, and says why.
5. Guest and Child hide the greeting name, the Settings tab and the app menu. Holding OK on an app opens it. Home rebuilds on every mode change, so widgets the mode hides are detached and stop their work.
6. The mode control is visible in every mode and sits outside Settings. Entering Guest or Child goes through the confirmation path. Returning to Normal is one OK on Normal, with no confirmation and no PIN.
7. This is a launcher presentation policy. It adds no PIN, account or OS sandbox, and it does not restrict what happens inside an approved app.
