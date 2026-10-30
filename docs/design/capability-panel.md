# Capabilities panel

Covers [#9](https://github.com/AojdevStudio/luma-tv/issues/9) and [#14](https://github.com/AojdevStudio/luma-tv/issues/14). Ossie picked design B, Control panel, in the capability mocks. Settings, then System, then Capabilities opens it.

## Layout

- Sections on the left. Items in the middle. One shared preview on the right with Confirm and Cancel, then the result.
- Sections are Actions, Ask, Scenes, and Health. Scenes appears only when House Control is installed and turned on. Layouts, Media, Ambient, and Mode are not built, so they have no section.
- Light by default. Dark mode is a row under the sections. It uses a true black panel with white primary text and is saved on the TV.
- Focus is inverted. The open section keeps a darker fill while focus is in its items. Nothing animates while the panel is idle.

## One path for every action

- `Capabilities.Action` is the only thing the preview shows and the only thing `Capabilities.run` accepts.
- Actions rows come from `Capabilities.list`, as before. Ask turns text into an action with `Commands.interpret`, then `Capabilities.of`. Scenes rows come from `Capabilities.scene`.
- `MainActivity.commandRegistry()` builds the registry each time a request is interpreted. It holds the launchable apps, the Home, Apps, and Settings tabs, the Home widgets with their availability, and the scenes House Control last sent to Home.
- `Capabilities.run` asks the TV for a fresh registry right before it acts. An app, widget, or tab that is gone or unavailable is refused with a reason, and nothing changes. An app Android cannot open is reported as not opened.
- Only successful results are saved, in the version 1 line format. The new capability ids are `app`, `tab`, `widget`, and `scene`. Saved `clock` and `inputs` results from the earlier build still parse.
- There is no intent, shell, or package path outside these four kinds.

## Scenes

House Control's scenes are listed and understood by Ask, but none can start from this panel yet. Each previews as unavailable and points to the scene buttons on Home. The House Control scene ticket owns a safe adapter. Until then, no scene result is ever reported as done.

## Health

- Opening the Health section measures with `HealthReader` on its own background thread.
- All six readings show their status word, value, explanation, source, and measurement time.
- Refresh marks the shown readings stale at once and keeps their old times. `CapabilityPanel.Health` drops any answer from an older refresh, and any answer after the panel closes.
- Refresh and Done are reachable with the D-pad. Done and Back return focus to the Health section row. A second Back closes the panel and returns to the Capabilities row in Settings.

## D-pad

`CapabilityCenter.relink()` sets every Left, Right, Up, and Down target after each change. Back cancels a pending action first, then moves from the items to their section, then closes the panel.

## Checks

`tools/CapabilitiesTest.java` covers the executor and the saved result. `tools/CapabilityPanelTest.java` covers the sections, the Health refresh rules, and the Health row text. Emulator proof of the D-pad path is still pending.
