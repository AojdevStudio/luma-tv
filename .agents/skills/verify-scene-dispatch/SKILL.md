---
name: verify-scene-dispatch
description: Model-check how Luma sends a confirmed House Control scene and where its result goes. Use when a change touches HouseLink, HouseScenes.outcome or refusal, Capabilities.run for scenes, or runAction and the Back, close, mode and pause paths in MainActivity's capability panel.
---

# Verify scene dispatch

A confirmed scene changes real lights and air conditioners. Its order of steps crosses three threads: Luma's main thread, the HouseLink worker, and House Control. Tests check the orders someone wrote down. This check tries every order of one Confirm against plan imports, a second read, Back, close, Child mode, leaving Luma and returning.

## Run

From the repository root, with Java 17 or newer on `PATH`:

```sh
bash .agents/skills/verify-scene-dispatch/scripts/units.sh
bash .agents/skills/verify-scene-dispatch/scripts/check.sh
```

`units.sh` runs `HouseScenesTest` and `CapabilitiesTest` with plain `javac`. `check.sh` runs TLC 1.8.0 on [model/SceneDispatch.tla](model/SceneDispatch.tla). It downloads `tla2tools.jar` to `~/.cache/tla/` once and refuses a jar with another SHA-256. Set `TLA2TOOLS_JAR` to use another copy of the same file. The check takes about a minute. The `scene-checks` workflow runs both on every PR.

## What it proves

`check.sh` exits 0 only when every line prints `ok`.

| Line | Meaning |
|---|---|
| `code` | With the flags set as the code stands, these hold in every reachable state: `NoChildHandOff`, `ResultInOwnPane`, `ResultInOwnPaneAfterSend`, `AtMostOneSend`, `TypeOK`. |
| `finding-<property>` | An open defect. TLC must still find its counterexample. The trace below the line is the shortest path to it. |
| `before-6aa4885` | Real past failure. Without the check at the worker handoff, Luma hands a queued scene to the worker while Child mode is active, and House Control runs it. |
| `control-<property>` | Two refusal paths must stay reachable. `Take` records when it refuses the queued scene in Child mode. House Control can also refuse a request before it runs anything. |
| `before-fc47a32` | Real past failure. The trace shows the worker taking the scene, House Control running it, the viewer moving from the pane that started it to another scene, and the late reply replacing that scene's Confirm. |
| `provider-gap` | Outside Luma. Code 2 carries only the scene id, so a plan import between Luma's last check and the transaction changes what runs. Only a House Control change can close it. |

| Property | Harm when it breaks |
|---|---|
| `NoChildHandOff` | House Control runs a scene that Luma handed to the worker while Child mode was active. |
| `ResultInOwnPane` | A late result covers the Confirm of the scene the user is looking at. |
| `AtMostOneSend` | One Confirm changes the home twice. |
| `ResultInOwnPaneAfterSend` | A late result covers another scene's Confirm after the viewer moved from the owning pane between provider execution and the reply callback. |
| `NoLostResult` | A failed or unknown result disappears, so the user thinks nothing went wrong. Issue #26. |
| `NoLostFailure` | Issue #26. The viewer moves focus or closes the panel, then leaves Luma. Luma drops the late error result for the scene House Control ran. |
| `NoLostReply` | The same path for a lost reply. Luma drops the Unknown result while away. |
| `NoStaleStarting` | The pane says `Starting` after the run ended. Issue #26. |
| `RanPlanReported` | House Control ran a plan imported after the preview, and the result does not say so. |
| `NotRunMeansNothingRan` | Luma says Not run for a scene House Control already ran, because its reply could not be read. |
| `RanAsConfirmed` | Needs House Control to refuse a scene whose revision changed. Not provable inside Luma. |

## Read a result

Read every counterexample with the pstack `/statechart` skill. It tells the shortest path in plain words and draws the states for a pass. The action names in a trace match the table below, so each step points at code.

A counterexample on `code` means the change broke a property the code kept. Check the trace against the code before you change the model. If the code really does that, it is a bug: fix the code. If the model does something the code cannot, fix the model and say which line of code proves it. Never drop an action, shrink `MaxRev`, or weaken a property to get a pass.

An `error` result means TLC did not finish, such as a parse error or a missing Java. It is not a pass.

## Coverage

Sync commit: `f90c906`. The model was checked against the code at that commit.

| Model action | Code |
|---|---|
| `Confirm` | `MainActivity.CapabilityCenter.runAction`, `Capabilities.run` first refusal, `MainActivity.readHouse` |
| `OtherRead` | `CapabilityCenter.loadScenes` |
| `Bind`, `Unbind` | `HouseLink.connect`, `connection.onServiceConnected`, `release` after `IDLE_MS` |
| `Take` | `HouseLink.next` and the refusal in `HouseLink.request` |
| `Transact` | `HouseLink.fetch`, `HouseLink.send`, `HouseLink.transact`. House Control `HomeService.onTransact` codes 1, 2, 4 and `HomeEngine.action` |
| `AnswerRead` | the worker's `main.post` in `HouseLink.request`, `MainActivity.readHouse`, `Capabilities.run` calling `runScene` |
| `AnswerScene` | `HouseScenes.outcome`, `HouseScenes.unknown`, `Capabilities.finish`, the callback in `runAction` |
| `Deliver` | the callback in `runAction`: `shows`, the Toast, `Capabilities.Away` |
| `Import` | House Control `HomeEngine.importHome` and `setDemo` |
| `Back`, `Close`, `MoveOn` | `MainActivity.onBackPressed`, `CapabilityCenter.back`, `onFocus`, `dropPending`, `closeOverlay` |
| `ChildMode` | `CapabilityCenter.select` opens Mode. Confirm calls `enterMode`, which clears `capabilityOverlay` and closes the panel. |
| `Leave`, `Return` | `MainActivity.onPause`, `onResume` |

Viewer input needs Luma on screen and the panel open. Queued work, transactions, and their reply callbacks can continue while Luma is away.

| Flag | Value at the sync commit | Code that sets it |
|---|---|---|
| `CheckAtTake` | TRUE | `HouseLink.run` passes the refusal that `request` asks at the take |
| `PaneOwned` | TRUE | `CapabilityCenter.shows` compares `paneChanges` |
| `KeepUnseen` | FALSE | `runAction` drops a result when the pane moved on and Luma is off screen |
| `ReplyPlan` | FALSE | `HouseScenes.outcome` ignores the plan revision in the code 2 reply |
| `Unreadable` | FALSE | `HouseLink.send` turns every `RuntimeException` into Not run |

When a change touches a row above, read the model action against the new code. Move the sync commit only after the model describes the code at the new commit.

## When a fix lands

1. Set its flag in `code=` in `scripts/check.sh` and in the flag table.
2. Move its property from `findings=` to `holds=`.
3. Run `check.sh`. It must print `ok` for `code`. Then set the flag back to its old value once, by hand, and confirm the property breaks. Restore it.
4. Update the sync commit.

Use `/t3-pstack:maintain-formal-verification-skill` for larger changes.

## Scope

- One Confirm of one scene, one extra read, and at most one plan import. More reads or imports add no new orders to the steps checked here.
- The binding always connects. A bind that fails or times out (`HouseLink.giveUp`) is not modeled.
- The House Control side is one atomic step per transaction, which matches `actionLock` in `HomeEngine.action` and `importHome`.
- Process death, a second Confirm before the first answers, and the Home widget's own binding are not modeled.
- A pass covers this model. It is not a proof about a real TV, real devices or House Control's device transports.
