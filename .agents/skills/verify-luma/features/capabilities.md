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
- An absent app must report that no installed app matches. Do not treat a request for Plex as evidence that Plex is installed.
- Scenes is hidden when House Control is absent. Scene execution remains unavailable until its adapter is implemented.
- Open Health with the D-pad. Check all six metrics, source labels, and measurement times.
- Refresh must mark old readings stale immediately, keep their original times, and replace them with a fresh report.
- Done or Back returns to the Health section. Back again closes the panel and returns to the Settings row.
- Toggle Dark mode and inspect a true-black panel with white primary text. Toggle back and inspect the light panel.
- Restart with a pending request. Nothing runs, and the last successful result remains.

The offline action compiles the capability check with its Commands dependency. It also runs command, voice-boundary, Health snapshot, and panel-state tests without Android or a microphone. The voice entry UI is not implemented by this slice.

## TCL proof

The final TCL pass belongs to the integration ticket. Use the normal `verify.py safe` source and device checks, then drive Settings → Capabilities, capture the panel and the resulting Home state, and inspect both screenshots. A successful build alone does not prove the interaction.
