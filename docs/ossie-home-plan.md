# Make Luma feel like home

Luma already provides the app launcher and TV controls. Keep that structure and make five focused changes for Ossie's home TVs.

1. **Personal greeting and identity.** Use the existing name preference for the greeting. Try a small home identity in static mockups before changing app artwork or naming. Keep the MIT license, original author credit, and upstream links.
2. **Quiet dark appearance.** Start from Graphite. Use a true black background, white text, and one clear focus treatment. Plan a light mode toggle as a new setting after mock approval. Turn off Living wallpaper, Fluid motion, and Navigation sounds for the first comparison. Avoid decorative chrome.
3. **Useful apps first.** Put TiviMate first on the TCL. Include only installed apps that Ossie uses. Confirm the remaining order against the existing CleanYourTV setup. Keep all apps reachable from Apps and preserve their data.
4. **Fewer widgets.** Start with Inputs and Control. Compare a small weather widget with no weather widget. Keep Up Next and Now Playing optional. Up Next can show recent apps without Continue Watching access. Full Continue Watching and Now Playing need access approval. Leave their access controls untouched until Ossie approves them.
5. **House Control as an optional page.** Add the home model and a few scenes after the launcher trial. House Control TV already includes its renderer and accepts floor-plan JSON, so a separate house-control fork is unnecessary for a custom plan. Build House Control TV after Luma so it trusts this fork's signing certificate. Ask before creating any Home Assistant token, Hue application key, Smart Life session, or companion key.

## Order of work

First, compare several static mockups for the greeting, dark appearance, and app row. Implement only the selected design. Test remote focus, Home, app launches, and rollback on the TCL before considering another TV.

The TCL trial keeps CleanYourTV installed and enabled. Preserve its existing disabled-package list and streaming setup. The Xiaomi, living room Streamer, Shield, and church TVs are outside this trial.

## Build and restore constraints

Build Luma from source with JDK 17, Android platform 34, and build-tools 36.0.0, and Python 3. The app uses plain Java and Android views. No JavaScript package manager or Gradle is required.

Keep the local signing key for future updates. House Control TV checks Luma's package name and signing certificate. Its own source build can discover a sibling Luma signing key or use explicit trusted certificate fingerprints.

Capture HOME before installing a second launcher. Android can reset the preferred HOME to its chooser during installation. The fix for [issue 1](https://github.com/AojdevStudio/luma-tv/issues/1) makes `luma.sh install` save HOME before installation. Until that fix lands, write `original=<package/.Activity>` into `.local/home-<serial>.txt` before installing the APK. Use the device serial with non-alphanumeric characters removed. Keep an existing nonempty journal. Do not run restore without the journal on a TV with stock launchers already disabled.

Restore changes the selected launcher and leaves Luma installed. It does not restore app data, firmware, widget permissions, or TV settings. Keep a separate device snapshot and a rollback to the prior CleanYourTV HOME.
