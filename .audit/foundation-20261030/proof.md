# Foundation correction proof

This run supersedes the October 27 no-flags claim. Issue 8 was reopened.
The original foundation remains commit `0024e3c`.

## Clock actions

The installed foundation reproduced the bug. The row said Hide the clock while
the confirmation pane said Show the clock. Confirming again reported Showed the
clock and kept the clock visible. See `before-confirm.png`, `before-result.png`,
and `before-result.txt`.

The corrected APK passed this remote path on Android 14, emulator-5554.

1. Open Settings, scroll to System, and open Capabilities.
2. Select Hide the clock and confirm. `after-hide.png` and `after-hide.txt` show
   Done. Hid the clock, the Show the clock row, and no corner clock.
3. Press Left, Up, and Select. `after-show-confirm.png` shows the same panel
   offering Show the clock with Confirm and Cancel.
4. Confirm. `after-show.png` and `after-show.txt` show Done. Showed the clock,
   the Hide the clock row, and the visible corner clock.
5. Press Left, Up, Select, and Back. `after-cancel.png` and `after-cancel.txt`
   retain the visible clock and Last. Showed the clock.
6. Relaunch through the device command with `open dev.luma.tv --relaunch`.
   `pid-before.txt` holds 31336 and `pid-after.txt` holds 32173.
   `before-state.txt` and `after-state.txt` hold the same clock flag and saved
   result, with the new result timestamp 1793373053254.
   `relaunch-home.png` shows the clock on Home after that restart.

The device command came from T3 device_open for local, emulator-5554. All UI
actions and screenshots used that command. ADB read the process IDs and dumpsys.
Only the capability state excerpt is included here. Raw dumps remain private.

The walkthrough also found that focus movement erased the Done status. The
correction updates the result pane after moving focus to Close. The final
screenshots above include that correction.

## Weather boundary

The old setter accepted Double.parseDouble results without coordinate limits.
The independent review reproduced saved NaN and out-of-range coordinates.
WeatherLocationTest now rejects missing city, nonfinite coordinates, and values
outside latitude -90 to 90 and longitude -180 to 180. It accepts valid bounds.
All 10 checks passed with javac --release 8.

The installed correction received TestCity, NaN, 0 through the existing
luma.weather.location intent. After relaunch, `invalid-weather-reset.txt`
shows the automatic Central fallback and its time-zone unavailable message.
The emulator's prior saved location was restored afterward.
The physical TCL was not changed by this test.

Promo now takes precedence over a saved location, matching the existing Miami
demo contract. That ordering was checked in source and built, but no fresh promo
walkthrough ran in this correction.

## Checks and remaining gates

The APK build passed. See `build.log`.
DevicesTest, 11 CapabilitiesTest checks, 10 WeatherLocationTest checks, 8 launcher
tests, and 12 verifier tests passed. The verifier expectation first failed on
the missing weather_location check. See `verifier-red.log`.

The weather unit check was first run after its implementation. No unit-test red
run is claimed for that class. The earlier review reproduced the old setter.
The clock before and after proof used the actual Android panel.

The first independent reviewer confirmed these test results and the callback
correction. The review found stale Settings and README wording, a missing
Java 8 verifier flag, and saved location text in public evidence. Those findings
were corrected. Public Home screenshots and the Home text snapshot now mark
the saved location as redacted. The original images remain private. The panel
screenshots are unchanged.

The unpublished foundation commits were combined after sanitizing their
evidence, so the saved location does not enter public Git history.
Exact-head review, PR CI, and review-bot approval remain pending.
Physical TCL integration remains ticket 17.

## Second review corrections

The second review found that a cold launch ignored luma.weather.location.
The production-callback scratch reproduction failed before the correction with
`cold weather_city=<absent>` and `cold launch ignored luma.weather.location`.
Both initial creation and onNewIntent now consume the same weather-setting
handler. The same reproduction then passed with Review city on both paths.
This reproduction uses production callback bodies with Android setup stubbed.
It does not replace a device walkthrough.

WeatherLocationTest now has 11 checks, including nonfinite longitude.
Removing the longitude finite check from a scratch copy now fails with
`nonfinite longitude is rejected`.
The invisible-city check failed before the parser correction, then passed.
No device proof for the cold-launch correction is claimed here yet.
