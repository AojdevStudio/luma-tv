# Layouts and modes

Home layouts (#10) and Guest and Child modes (#16). A layout changes which widgets, apps, scenes and wallpaper Home shows. A mode limits what Home, Apps and the panel show and open. Emulator acceptance is still pending for both.

## How to get to it (user POV)

- Layouts. Settings, then System, then Capabilities, then Layouts.
- Mode. The mode control in the tab bar, left of Home, on every page. It reads `Normal mode`, `Guest mode` or `Child mode`. Settings, Capabilities, Mode opens the same section.

## Emulator proof

Build and install the source APK on the `luma-tv` Android TV emulator. `adb shell dumpsys activity dev.luma.tv/.MainActivity` prints a `Luma home:` line with the mode, the layout name and the number of saved layouts. Use it beside the screenshots, not instead of them.

Layouts:

- Move through Default, Movies, Sports, News and Guests. Each preview lists widgets, apps and wallpaper, and Unavailable for parts the emulator lacks. Home behind the panel does not change.
- OK on Movies shows Confirm and Cancel. Cancel and Back leave Home and the `Luma home:` line unchanged.
- Confirm, then Close. Home shows the Movies widgets and apps. Left and Right turn the widget pages. Default stays listed.
- With Movies on, confirm Hide Weather from Ask or Actions. It must refuse, and Weather must stay on Home.
- Settings, Home widgets and Wallpaper show a notice that names Movies.
- Type a name, Save this Home, then Confirm. Force-stop Luma and reopen it. The saved layout is still listed and applies.
- Choose Default and confirm. The owner's Home returns unchanged.

Modes:

- Enter Guest through the mode control, then Confirm. Up Next, Now Playing, the greeting name, the Settings tab and Control are gone. The Home apps still open. Apps lists only the Home apps.
- With more than six unhidden apps, Guest Apps and requests must allow only the six installed apps actually shown on Home. Missing layout entries stay saved.
- In Guest, a Smart Home cover must refuse to open House Control unless that app appears on Home. Allowing the House widget does not approve its app.
- Start a widget page transition, then enter a restricted mode. Detached widgets must stay inactive after the delayed page callback runs.
- Enter Child with no approved apps. Home says no apps are approved, and Apps is empty.
- In Normal, approve one app under Mode, Child apps. Child Home then shows only that app and Weather.
- Force-stop and reopen Luma in Child mode. Home opens in Child mode with no Normal content first.
- From Guest and from Child, the mode control, then OK on Normal, returns to Normal at once. The owner's Home is unchanged.
- Opened from the mode control, Back moves from the mode rows to the Mode section, then closes the panel with focus back on the mode control. This holds in every mode.

## Gotchas

- Choosing or applying a layout never runs a scene. A scene runs only from its own preview after Confirm. See `capabilities.md`.
- A missing app shows its package, not a name. That is intended.
- Keep screenshots and dumps outside the repository. Crop or skip any frame that shows a personal weather location.
