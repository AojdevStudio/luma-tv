# Capabilities

The Capabilities panel is a user-space action center. It lists the actions Luma allows, explains the effect before it runs, asks for confirmation, and keeps the last successful result on the TV.

## How to get to it

- Open Luma Settings.
- Scroll to System → Capabilities.
- Open the panel with OK.

## Emulator proof

Build and install the source APK on the `luma-tv` Android TV emulator. Confirm these visible outcomes:

- Actions lists Hide or Show the clock and Show Inputs on Home.
- The focused action shows a preview and the text `Asks before it runs.`.
- OK opens Confirm and Cancel. Back cancels without changing state.
- Confirming the clock action hides or shows the clock and reports `Done.`.
- Move left from Close to the same clock row. Its preview must match its new label.
- Confirm again without closing the panel. The clock changes back and the result reports the opposite action.
- Cancel a third preview. Clock visibility and the last result stay unchanged.
- The last result appears after closing and reopening the panel.
- Inputs reports an unavailable reason when the emulator has no HDMI input service.
- Reopening Luma keeps the clock state and the last result.

## Typed requests and Health

- From the Actions section, move Down to Ask, then Right to the request line. OK opens text entry.
- Submit a supported request for an installed app, a tab, or a widget. Check the interpreted target before Confirm.
- Cancel or Back must leave the target unchanged. Correct an unsupported request with Edit, then cancel or confirm it.
- Preview `switch to tab Settings`, then Cancel. Ask must stay open with the same text and Preview focused. Repeat with remote Back. Both paths must show `Cancelled. Nothing ran.` and preserve the last result.
- An absent app must report that no installed app matches. Do not treat a request for Plex as evidence that Plex is installed.
- Scenes is hidden when House Control is absent. See Smart-home scenes below.
- Open Health with the D-pad. Move through all six metrics in the middle column and inspect each selected metric's details, source label, and measurement time in the right column.
- Refresh must mark old readings stale immediately, keep their original times, and replace them with a fresh report.
- The Back button or remote Back returns to the Health section. Back again closes the panel and returns to the Settings row.
- Toggle Dark mode and inspect a true-black panel with white primary text. Toggle back and inspect the light panel.
- Restart with a pending request. Nothing runs, and the last successful result remains.

The offline action compiles the capability check with its Commands dependency. It also runs command, voice-boundary, Health snapshot, and panel-state tests without Android or a microphone. The voice entry UI is not implemented by this slice.

## Smart-home scenes

Use House Control TV's isolated demo home for every confirmed run: *Sync & connect → Explore demo home* in House Control. Its scenes reach no real device. Do not confirm a scene on the real home without Ossie's word, and accept no permission prompt. `adb shell dumpsys activity dev.luma.tv/.MainActivity` prints a `Luma scenes:` line with the read state, the source (`live` or `demo`), the scene count and readiness, and no names.

- Without House Control, Home shows no scene buttons and the panel has no Scenes section.
- With House Control installed and the demo home on, open Settings, Capabilities, Scenes without visiting Home first. Every demo scene is listed, also beyond five. Each preview has `Home.` changes, a `TV.` line and `Source. House Control TV, demo home. No real devices change.`
- Movie's `TV.` line names Plex when Plex is installed, or the Plex package when it is not.
- OK shows Confirm and Cancel. Cancel and remote Back say `Cancelled. Nothing ran.` and the demo model on Home stays the same.
- Confirm shows `Starting…`, then `Done. Started <scene> on the demo home`. Close returns to Settings. The last result line shows the scene.
- On Home, focus a scene button. The greeting says `OK shows what it changes`. OK opens the panel at that scene with Confirm focused. Back three times returns to Home with focus on the same button. Confirm runs it, and the 3D home follows within a few seconds.
- Ask `start Movie`. It shows the same preview as the Movie row.
- With the demo home off and House Control not connected, rows are dimmed and the preview gives House Control's reason. Force-stop House Control while Scenes is open, then choose Scenes again. It must say `House Control did not answer` or House Control's own error, not that there are no scenes.
- Switch the demo home off after a preview, then Confirm. It must refuse, and nothing changes.
- Preview Movie, then switch the demo home off and on again in House Control, which gives it a new revision. Confirm must refuse with `Choose Movie again`. OK on the same Movie row must read House Control again and show a fresh preview with Confirm. Nothing runs until Confirm.
- With the demo home off and House Control not ready, OK on a dimmed row shows the reason. Turn the demo home on, return to Luma and press OK on the same row. It must offer Confirm without leaving Scenes.
- Confirm Movie, then move to Bright and press OK before Movie's result arrives. Bright's preview and Confirm must stay, Movie's result shows as a toast, and Plex does not open. The demo home answers fast, so this timing may not be reachable on the emulator. Record it as not reached rather than passed.
- On a real-home plan with a device that is not linked, the preview lists that device on a `Simulated.` line. Preview only. Do not confirm on the real home without Ossie's word.
- Guest: the panel is titled Scenes and mode, has no history line, and runs a demo scene. Plex opens only when it is one of the apps on Home. Otherwise the result says Guest mode does not open it. Child shows no scene buttons and no Scenes section.

## TCL proof

The final TCL pass belongs to the integration ticket. Use the normal `verify.py safe` source and device checks, then drive Settings → Capabilities, capture the panel and the resulting Home state, and inspect both screenshots. A successful build alone does not prove the interaction.
