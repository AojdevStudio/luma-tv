---
name: verify-upnext-refresh
description: Model-check Up Next refresh ordering on the real UpNextWidget source. Use when a change touches UpNextWidget's refresh(), its loading or stale flags, show(), or WidgetBoard.IO.
---

# Verify Up Next refresh ordering

Home calls `UpNextWidget.refresh()` every time it comes back from an app. Refresh work reads Watch Next on `WidgetBoard.IO` and posts the result to the UI thread. If two reads overlap, the older one can finish last and Up Next shows rows that are already out of date (issue #38). This checker tests for that race within the bounds and pause points below.

## Run

From the repository root, with JDK 17 or newer:

```sh
java .agents/skills/verify-upnext-refresh/scripts/UpNextRefreshCheck.java --self-check
```

It needs no device, SDK, network, or secret. `--source <file>` checks another copy of `UpNextWidget.java`, for example an older commit:

```sh
git show 11f1b1b:src/dev/luma/tv/UpNextWidget.java > /tmp/UpNextWidget.old.java
java .agents/skills/verify-upnext-refresh/scripts/UpNextRefreshCheck.java --source /tmp/UpNextWidget.old.java
```

At 11f1b1b the three bounded ordering properties fail. CI requires all three historical counterexamples. It also replays f6e4375, where the ordering checks pass but a pending read waits behind uncached posters.

## How it works

The checker cuts the text between the `UpNextWidget` constructor and `load()` out of the source file, word for word. Today that text is the `loading` and `stale` fields and `refresh()`. It compiles that text beside small fakes that keep the real names: `Context`, `WidgetBoard.IO`, `load()`, `post()`, and `show()`. It does not use a hand-written copy of the refresh logic.

It explores each order of these modeled steps, with writes placed as described below. Each replay starts with a new widget.

| Step | Meaning |
|---|---|
| refresh | The UI thread calls `refresh()`. |
| write | An app writes a newer Watch Next row. The version goes up by one. |
| start | An IO worker takes the oldest queued task. If the task calls `load()`, it reads the current version and waits. |
| return | `load()` returns. The worker runs on until it calls `post()` or ends. |
| post | `post()` queues its runnable for the UI thread. The worker runs on until its next `post()` or its end. |
| ui | The UI thread runs the oldest posted runnable. `show()` records the version it draws. |

Workers run on Java threads. The explorer stops each worker at the entry of every `post()` and resumes it in a separate step. This lets a refresh or other enabled work run before the worker queues that callback.

A write only increments the version. The checker places a write just before a refresh or a worker start because those steps sample the version. Multiple writes between samples have the same ordering effect as one. The checker gives no result if `load()` runs outside a start step.

Each replay has a budget of 10 steps per refresh request in the bound. A refresh that starts and draws its own read takes five steps: refresh, start, return, post, and ui. A write shares a step with the refresh or start that follows it. If a schedule needs more steps than the budget allows, the checker stops exploration and prints the steps taken. This catches a rerun that requests itself forever.

## Properties

| Property | Plain meaning | Harm if broken |
|---|---|---|
| one_read_at_a_time | At most one Up Next read is queued or running. | Reads race, and they share state such as the old `lastUsed` map. |
| never_shows_older | Up Next never draws a version older than one it already drew. | The card jumps back to old progress or an old title. |
| newest_request_lands | Whenever all work has drained, the screen shows data at least as new as the data at the last refresh. The check runs at every drained point, not only at the end of a schedule. | Up Next stays stale until the next return to Home. |

Once a task enters `load()`, the checker compares its time in the queue and in the read with that of earlier reads. This detects overlap even if the first read returned before the second started. Other jobs can use the same queue.

The separate `pending_read_before_posters` replay uses two refreshes and one write. After the first read starts, an app writes a new version and Home requests a refresh. Drawing the first result submits two uncached poster jobs to the same queue. The checker starts queued jobs in order and requires the pending job to reach `load()` before either poster starts. A job that only queues another job has yet to start the read. If the replay cannot reach the scenario, it prints UNKNOWN with the steps it took.

## Read the result

- Exit 0 means every property held in the modeled schedules and the two-poster replay. With `--self-check`, every scratch mutant was also caught.
- Exit 1 means the checker found a counterexample. It prints the steps leading to each broken property. If the work budget ran out, it keeps counterexamples from the unfinished schedule and earlier schedules. Properties still awaiting a verdict are UNKNOWN.
- Exit 2 means no result. This includes an exhausted budget or an UNKNOWN poster replay with no counterexample. Invalid options, missing cut points, compile errors, a task that did not pause or end, and failed self-checks also give exit 2.

To tell a real bug from a checker error, read the counterexample against `UpNextWidget.java`. If every step is something the app can do, it is a real bug. If a step needs the fakes to act unlike Android (for example, `post()` running out of order), fix the checker and say so.

## Self-check

`--self-check` edits the cut text in memory and requires each edit to break its property. Each edit anchor must occur exactly once in the cut text:

| Mutant | Must break |
|---|---|
| Drop `if(loading){stale=true;return;}` | one_read_at_a_time and never_shows_older |
| Drop `if(stale){stale=false;refresh();}` | newest_request_lands |
| Set `loading=false` before `load()` | one_read_at_a_time |
| Move `loading=false` to the worker, after `load()` and before `post()` | never_shows_older |
| Clear `loading` on refresh when an atomic flag marks a worker waiting to post | never_shows_older |
| Queue a job that puts the pending read behind the posters | pending_read_before_posters |
| Ignore the first `refresh()` call | newest_request_lands |
| Drop the posted completion | newest_request_lands |
| Keep `stale` set after the rerun | no result from the work budget |

The self-check also adds an empty IO job beside one read and requires a pass. It combines the ignored first refresh with the stale rerun and requires the `newest_request_lands` counterexample to survive budget exhaustion.

If a later change renames these lines, update the mutant text in `scripts/UpNextRefreshCheck.java` so each mutant still breaks the same promise.

## Bounds and limits

- The default bound is 3 refresh requests, up to 2 Watch Next writes, 2 IO workers, and 30 steps per replay. `--refreshes` accepts 1 through 4. `--writes` accepts 0 through 3. The two-poster replay uses its own fixed bounds and is outside the schedule count. Use the default bounds for `--self-check`.
- With the refresh code at 7291c42, the checker passes 710 schedules at the default bound (longest 15 steps). At 4 refreshes and up to 3 writes, it passes 18,865 schedules (longest 20 steps).
- At the default bound, the checker explores 246,884 schedules for 11f1b1b and 710 for f6e4375. Both historical replays produce counterexamples.
- The fakes assume Android's `View.post()` runs posted work on the UI thread in the order posted. The model starts tasks in queue order on a fixed pool of 2 workers, the size of `WidgetBoard.IO` in `WidgetBoard.java`.
- The checker schedules at the listed pause points. It cannot see races that require a switch inside a step, such as a flag that changes and changes back before `load()`. The worker handoffs synchronize memory and assume the widget starts each replay with no shared mutable state from a prior replay.
- `load()` and `show()` are fakes. The check does not cover what `load()` reads, focus handling in `show()`, Promo mode, or the recent-app fallback.
- A widget that is detached keeps its posted work until it attaches again. The check does not model detach.
- The bounded schedules omit poster jobs. The separate queue test covers posters submitted by the current draw. Neither check bounds the time taken by work already in the pool.

## Coverage and sync

| Covered | Where |
|---|---|
| `loading`, `stale`, `refresh()` | `src/dev/luma/tv/UpNextWidget.java`, between the constructor and `load()` |
| `WidgetBoard.IO` pool size and order | `src/dev/luma/tv/WidgetBoard.java` |

Sync commit: `7291c42` (complete a failed read on the UI thread). The cut text hash at that commit is `3bd76a7a186477fa`, printed on the `region` line of every run.

When the hash changes, read the new cut text and confirm the fakes still match how Android and `WidgetBoard.IO` behave. Then run the check. Move the sync commit and hash forward only after that review. Never weaken a property, lower the bound, or drop a mutant to get a pass. For an intended change in behavior, use `/t3-pstack:maintain-formal-verification-skill`.

CI also injects a failed read into the production refresh method. It checks that the old rows stay visible and that either a pending request or a later request draws fresh rows. This is a directed regression outside the bounded schedule count.

CI runs this check through `.github/workflows/upnext-refresh.yml`.
