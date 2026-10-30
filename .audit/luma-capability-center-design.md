# Safe capability center design

## Grounding

Luma keeps most Android TV UI in `MainActivity`. Settings rows use `option()`, overlays use `openOverlay()`, and `WidgetEditor` is the closest focus and preview pattern. Preferences use the `luma` store and `apply()`. Offline checks compile plain Java without Android classes.

## Candidate A

Use a pure `Capabilities` module with a `Tv` effect interface. The module owns the allowlist, availability, preview text, idempotent target values, run result, and saved result parser. `MainActivity` supplies the Android adapter and a `CapabilityCenter` overlay. This keeps the trust boundary in one interface and makes the core testable without Android.

## Candidate B

Use a reducer with `CapabilityState`, `CapabilityEvent`, and `CapabilityEffect` values. The overlay dispatches focus, preview, confirm, cancel, and refresh events. A separate effect interpreter applies Android changes. This makes dialog state explicit, but it adds a reducer and effect queue before Luma has a second caller.

## Synthesis

Choose Candidate A for #8. It gives later tickets one stable boundary with two calls, `list` and `run`, while keeping the UI state local to the existing overlay pattern. Borrow Candidate B's explicit preview and confirmation states in the overlay rather than introducing a general reducer now. Changes set fixed values instead of toggling, and `run` checks availability again before writing.

## Data shape

`Capabilities.Tv` names the only readable and writable effects. `Capabilities.Action` contains a stable id, label, preview, confirmation flag, unavailable reason, and fixed target. `Capabilities.Result` contains capability id, done state, message, and timestamp. Successful results round-trip through a versioned tab-separated string. Invalid data parses as absent.

## Acceptance seam

The highest seam is the user-visible Settings to Capabilities path. Offline tests prove policy, idempotence, unavailable behavior, and persistence parsing. The emulator proves D-pad focus, preview, confirmation, cancellation, restart persistence, and screenshots. TCL proof belongs to #17.
