# Capabilities panel

Covers [#9](https://github.com/AojdevStudio/luma-tv/issues/9) and [#14](https://github.com/AojdevStudio/luma-tv/issues/14). Ossie picked design B, Control panel, in the capability mocks. Settings, then System, then Capabilities opens it.

## Layout

- Sections on the left. Items in the middle. One shared preview on the right with Confirm and Cancel, then the result.
- Sections are Actions, Ask, Layouts, Scenes, Health, and Mode. Scenes appears only when House Control is installed and turned on. Media and Ambient are not built, so they have no section. Layouts and Mode are described in [presets-and-profiles.md](presets-and-profiles.md).
- Child mode opens the panel at Mode alone, titled Mode. Guest mode shows Scenes and Mode, titled Scenes and mode, when House Control is installed, and Mode alone otherwise. Neither has a history line or a Dark mode row. The mode control in the tab bar opens it from any page.
- Light by default. Dark mode is a row under the sections. It uses a true black panel with white primary text and is saved on the TV.
- Focus is inverted. The open section keeps a darker fill while focus is in its items. Nothing animates while the panel is idle.

## One path for every action

- `Capabilities.Action` is the only thing the preview shows for Actions, Ask and Scenes, and the only thing `Capabilities.run` accepts. Layouts and Mode use the same pane and the same Confirm and Cancel. The panel holds one pending change, and Confirm runs it.
- The preview asks `Capabilities.refusal` before it offers Confirm, and `run` asks again before it acts. Outside Normal every settings capability is refused. While a layout other than Default is on Home, widget Show and Hide and the Inputs action are refused, because they edit Default.
- Actions rows come from `Capabilities.list`, as before. Ask turns text into an action with `Commands.interpret`, then `Capabilities.of`. Scenes rows come from `Capabilities.scenes`. A Home scene button opens the panel at that scene's preview.
- `MainActivity.commandRegistry()` builds the registry each time a request is interpreted. It holds the launchable apps, the Home, Apps, and Settings tabs, the Home widgets with their availability, and the scenes in House Control's latest reply.
- `Capabilities.run` asks the TV for a fresh registry right before it acts. An app, widget, or tab that is gone or unavailable is refused with a reason, and nothing changes. An app Android cannot open is reported as not opened.
- `run` answers once through a callback. Settings, apps and tabs answer at once. A scene answers after House Control does. The result is Done, Not run, Not finished or Unknown.
- Only Done results are saved, in the version 1 line format. The new capability ids are `app`, `tab`, `widget`, and `scene`. Saved `clock` and `inputs` results from the earlier build still parse.
- There is no intent, shell, or package path outside these four kinds.

## Scenes

Scenes reads House Control afresh each time it is chosen, and the panel reads it once as it opens so Ask knows the scene names. The preview names the light and air conditioner changes, the app that opens after, and the real or demo home. Confirm reads House Control again and runs the scene only if the preview still holds. [smart-home-scenes.md](smart-home-scenes.md) has the provider contract and the rules.

## Health

- The first Health visit in a panel session measures with `HealthReader` on its own background thread. Later visits keep that report and its measurement times. Refresh measures again.
- The middle column lists all six metrics with their status. Selecting one shows its value, explanation, source, and measurement time in the right column.
- Refresh marks the shown readings stale at once and keeps their old times. `CapabilityPanel.Health` drops any answer from an older refresh, and any answer after the panel closes.
- Refresh and Back are reachable with the D-pad. The Back button and remote Back return focus to the Health section row. A second Back closes the panel and returns to the Capabilities row in Settings.

## D-pad

`CapabilityCenter.relink()` sets every Left, Right, Up, and Down target after each change. Back cancels a pending action first, then moves from the items to their section, then closes the panel.

## Checks

`tools/CapabilitiesTest.java` covers the executor, the saved result, the mode and layout refusals, and every scene outcome. `tools/HouseScenesTest.java` covers reading House Control's replies, the scene changes, the staleness rule and the run outcomes. `tools/CapabilityPanelTest.java` covers the sections per mode, the Health refresh rules, the Health row text, the layout preview with its unavailable parts, and the Child preview with no approved apps. Emulator proof of the D-pad path is still pending.
