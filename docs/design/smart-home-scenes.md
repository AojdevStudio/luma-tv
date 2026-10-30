# Smart-home scenes

Covers [#12](https://github.com/AojdevStudio/luma-tv/issues/12). Ossie picked design B, the control panel. Scenes come only from the House Control TV app installed on the TV. Luma invents no scene, effect, or result.

## Provider contract

House Control TV's `HomeService` answers Binder transactions from a Luma signed with a key it trusts. The source read for this design is `android/src/dev/housecontrol/tv/HomeService.java`, `HomeEngine.java`, `DemoHome.java` and `CloudMapper.java` in `AojdevStudio/house-control-tv` at version 2.7.

| Code | Request | Reply |
| --- | --- | --- |
| 1 | nothing | The home: `plan`, `state`, `revision`, `controlReady`, `connectionLabel`, and `demo: true` for the demo home. Or `{"error": ...}`. |
| 2 | scene id | `{"ok": true, ...}` after every device took its change. Or `{"error": ...}`. |
| 3 | 1 or 0 | Marks the Home widget visible or hidden. Luma's widget uses it to start and stop polling. |
| 4 | scene id | Code 2 on the isolated demo home. Refused when the demo home is off. |

- A plan scene is `{id, name, icon, lights: {"*": patch, deviceId: patch}, acs: {...}}`. House Control merges `*` with each device's own patch, for every light and air conditioner in the plan. Light patches set `on`, `brightness` (5 to 100 percent), `temp` (2200 to 6500 K) and `color`. Air conditioner patches set `on`, `mode`, `target` (inside the unit's `min` and `max`, 16 to 30 by default) and `fan`.
- `revision` changes when a new plan is imported. Each demo home has its own revision.
- `HomeService` polls devices while a client is bound without the activity action. It stops on the last unbind.

## Two shapes compared

| | A. Activity-owned link that binds on demand | B. One persistent connection lifted out of the Home widget |
| --- | --- | --- |
| Scenes without visiting Home | Yes | Yes |
| Execution survives Home rebuilds | Yes | Yes |
| House Control polling | Only while Luma asks, or while Home shows the house | Whenever Luma runs, because the widget's unbind no longer stops it |
| Widget change | Scene buttons call the panel. Its polling binding stays as it was. | The widget loses its own connection and its reconnect and error states move |
| Size | One new adapter class | Rewrites the widget's connection lifecycle |

A was chosen. B changes when House Control polls Smart Life, and it moves the widget's reconnect states for no gain. Both bindings use the same intent, so Android hands Luma one binder and `onBind` and `onUnbind` still run once.

## Data shape

- `HouseScenes` is plain Java. `read` turns a code 1 reply into a `Read`. That is either a `Home` (source, revision, why it cannot run now, scenes) or the reason there is none. A failed read is not proof that no scene exists, so the panel shows the reason, not "no scenes".
- `Scene` holds its id, name, icon, source, revision and its changes. A change is one sentence per distinct setting, such as `Desk lamp on.` A name used by two devices gets its room.
- `refusal(scene, read)` is the one staleness rule. A scene previewed against one read may run against another only when both come from the same home (demo or real), House Control can control devices, and the scene has the same name, revision and changes.
- `outcome` turns a code 2 or 4 reply into `DONE`, `NOT_FINISHED` (House Control's error, in its words) or `UNKNOWN`. `HouseLink` reports `NOT_RUN` when nothing was sent and `UNKNOWN` when the connection broke during the run.
- `HouseLink` is the Android adapter. It owns the binding, a worker thread and the JSON conversion. It answers on the main thread, and never after the activity is destroyed. It sends only codes 1, 2 and 4.

## One path for every scene

1. Home scene buttons, Scenes rows and Ask requests all become a `Capabilities.Action` from `Capabilities.scene`. The preview has three lines. `Home.` names the changes. `TV.` says whether the chosen app opens after the scene, or why it will not. `Source.` names the real home or the demo home.
2. A Home scene button opens the panel at Scenes, reads House Control, and asks for that scene. Cancel and Back run nothing. Closing the panel rebuilds Home and returns focus to the same button.
3. Confirm calls `Capabilities.run`. It checks the mode, reads House Control again, checks the preview against that read, and only then sends code 2 or 4. Results arrive through a callback.
4. After `DONE`, the scene's app opens only when it is installed, the mode allows it, and the panel that asked is still open. The result says which app opened, or why none did. Opening Plex says nothing about playback.
5. Only `DONE` is saved as the last result, in the existing version 1 line. A result that arrives after the panel closed, or after focus moved on, shows as a toast and touches no panel view.

## Modes

- Normal sees every section. Guest has Scenes and Mode, titled Scenes and mode, with no history line. Child has Mode alone, and every scene is refused.
- In Guest, a scene can run, but its app opens only when it is one of the six apps on Home. House Control itself still opens only from the Home cover, under the same rule.

## Not done here

- The House Control flight into the TV after a scene was removed. Scenes now run from the panel, and the panel covers Home, so the flight could no longer play. The chosen app opens straight from the panel instead, like the app action.
- House Control's error string does not say whether some devices changed. Luma reports it as Not finished with House Control's words and claims nothing more. A structured per-device result would need a change in House Control TV.
- Emulator and TCL proof are pending. See `.agents/skills/verify-luma/features/capabilities.md`.
