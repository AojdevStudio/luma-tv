# Capabilities

The Capabilities panel is a user-space action center. It lists the actions Luma allows, explains the effect before it runs, asks for confirmation, and keeps the last successful result on the TV.

## How to get to it

- Open Luma Settings.
- Scroll to System → Capabilities.
- Open the panel with OK.

## Emulator proof

Build and install the source APK on the `luma-tv` Android TV emulator. Confirm these visible outcomes:

- The panel lists Hide or Show the clock and Show Inputs on Home.
- The focused action shows a preview and the text `Asks before it runs.`.
- OK opens Confirm and Cancel. Back cancels without changing state.
- Confirming the clock action hides or shows the clock and reports `Done.`.
- Move left and up from Close to the same clock row. Its preview must match its new label.
- Confirm again without closing the panel. The clock changes back and the result reports the opposite action.
- Cancel a third preview. Clock visibility and the last result stay unchanged.
- The last result appears after closing and reopening the panel.
- Inputs reports an unavailable reason when the emulator has no HDMI input service.
- Reopening Luma keeps the clock state and the last result.

The offline proof compiles `Capabilities.java` with `tools/CapabilitiesTest.java` and runs the test without Android or a device.

## TCL proof

The final TCL pass belongs to the integration ticket. Use the normal `verify.py safe` source and device checks, then drive Settings → Capabilities, capture the panel and the resulting Home state, and inspect both screenshots. A successful build alone does not prove the interaction.
