# TV health snapshot

Prepares [#14](https://github.com/AojdevStudio/luma-tv/issues/14). This adds the model and the Android reader. The Health screen is not built yet. It waits on Ossie's design pick.

## Shape

- `HealthSnapshot` is plain Java with no Android classes. It owns the four statuses, the six metrics, and the rules that turn an Android signal into a status. `tools/HealthSnapshotTest.java` checks those rules on a computer.
- `HealthReader.read(Context)` measures every metric again on each call and returns a new, unchangeable `HealthSnapshot`. It keeps no cache.
- `Metric` is the only list of checks. The reader loops over it, so a new metric cannot be left out of a snapshot.
- Each `Reading` has a status, a display value, a plain explanation, its source, and the time it was measured. The snapshot also keeps the time the check started.

An earlier shape had the reader fill one struct of raw values and classify them all at the end. It lost because the struct repeated the metric list, and a null field could mean "failed" or "absent".

## Statuses

| Status | Meaning |
|---|---|
| Healthy | An Android signal says this is fine. |
| Degraded | An Android signal says there is a problem. |
| Unavailable | Luma could not measure it, or an optional part is absent. |
| Unknown | Luma has a value, but Android gives no signal to judge it by. |

Luma never sets its own threshold.

| Metric | Android source | Rule |
|---|---|---|
| Memory | `ActivityManager.getMemoryInfo` | `lowMemory` sets degraded. Otherwise healthy. |
| Storage | `StatFs` on Luma's data folder for the value, the sticky `ACTION_DEVICE_STORAGE_LOW` broadcast for the status | The broadcast sets degraded. Otherwise healthy. Free space alone never sets the status. |
| Network | `ConnectivityManager` active network | Healthy only when Android validated internet access. No network, or no validation, is degraded. |
| Home app | `PackageManager.resolveActivity` for `CATEGORY_HOME` | Luma is healthy. Another app or the chooser is degraded. No answer is unavailable. |
| Luma | Luma's own package details | Always unknown. The installed version is not "latest". |
| House Control | `PackageManager.getApplicationInfo("dev.housecontrol.tv")` | Missing or turned off is unavailable. Installed is unknown, because installed does not prove it can reach the home. |

## Boundaries

- No root, no shell, no new permission. The reader uses `ACCESS_NETWORK_STATE` and the existing `<queries>` entries for HOME and House Control.
- Any `RuntimeException` or missing package from Android becomes an unavailable reading for that metric alone. The cause stays out of the report.
- The report holds no Wi-Fi name, address, account, or other private detail.

## Left for the Health screen

- Call `HealthReader.read` off the main thread. Package and storage queries can block.
- Add Settings, then System, then Health, with Refresh and Done focusable, and Back returning to the row that opened it.
- On Refresh, mark the shown snapshot as not current at once. Drop a result whose refresh is older than the latest one, or whose screen has closed. A request counter in the screen does this. `HealthSnapshot.at` can repeat within one millisecond.
- Show each reading's `at` and `source`. A kept older reading shows its own time and a stale label.
- Prove the D-pad path and the unavailable states on the emulator, then on the TCL with `verify-luma`.
