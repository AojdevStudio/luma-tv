---
name: verify-upnext-refresh
description: Model-check Up Next refresh ordering on the real UpNextWidget source. Use when a change touches UpNextWidget's refresh(), its loading or stale flags, show(), or WidgetBoard.IO.
---

# Verify Up Next refresh ordering

Home calls `UpNextWidget.refresh()` every time it comes back from an app. Each refresh reads Watch Next on `WidgetBoard.IO` and posts the result to the UI thread. If two reads overlap, the older one can finish last and Up Next shows rows that are already out of date (issue #38). This check proves, within a bound, that the current code cannot do that.

## Run

From the repository root, with JDK 17 or newer:

```sh
java .agents/skills/verify-upnext-refresh/scripts/UpNextRefreshCheck.java --self-check
```

It needs no device, SDK, network, or secret. It takes about 20 seconds. `--source <file>` checks another copy of `UpNextWidget.java`, for example an older commit:

```sh
git show 11f1b1b:src/dev/luma/tv/UpNextWidget.java > /tmp/UpNextWidget.old.java
java .agents/skills/verify-upnext-refresh/scripts/UpNextRefreshCheck.java --source /tmp/UpNextWidget.old.java
```

At 11f1b1b all three properties fail. CI replays that exact source and requires a counterexample for each property.

## How it works

The checker cuts the text between the `UpNextWidget` constructor and `load()` out of the source file, word for word. Today that text is the `loading` and `stale` fields and `refresh()`. It compiles that text beside small fakes that keep the real names: `Context`, `WidgetBoard.IO`, `load()`, `post()`, and `show()`. It does not use a hand-written copy of the refresh logic.

It then runs that code through every order of these steps and replays each order from a new widget:

| Step | Meaning |
|---|---|
| refresh | The UI thread calls `refresh()`. |
| write | An app writes a newer Watch Next row. The version goes up by one. |
| start | An IO worker takes the oldest queued task. `load()` reads the current version and then waits. |
| finish | A running task returns from `load()`, and its `post()` queues work for the UI thread. |
| ui | The UI thread runs the oldest posted runnable. `show()` records the version it draws. |

A write is placed only just before a refresh or a start, because nothing else reads the version. This removes orders that give the same states. Workers are real threads, so the code after `load()` runs on a worker as it does on the TV.

## Properties

| Property | Plain meaning | Harm if broken |
|---|---|---|
| one_read_at_a_time | At most one Up Next read is queued or running. | Reads race, and they share state such as the old `lastUsed` map. |
| never_shows_older | Up Next never draws a version older than one it already drew. | The card jumps back to old progress or an old title. |
| newest_request_lands | When all work ends, the screen shows data at least as new as the data at the last refresh. | Up Next stays stale until the next return to Home. |

## Read the result

- Exit 0: every property held on every order within the bound. With `--self-check`, every scratch mutant was also caught.
- Exit 1: a counterexample. The checker prints the shortest complete order it found for each broken property, in the code's words. Read it top to bottom: "task 2 returns and posts version 1" then "task 1 returns and posts version 0" means the reads finished in reverse order.
- Exit 2: no result. The source no longer has the cut points, the cut text does not compile against the fakes, a task never called `load()`, or a self-check mutant was missed. This is not a pass.

To tell a real bug from a checker error, read the counterexample against `UpNextWidget.java`. If every step is something the app can do, it is a real bug. If a step needs the fakes to act unlike Android (for example, `post()` running out of order), fix the checker and say so.

## Self-check

`--self-check` edits the cut text in memory and requires each edit to break its property:

| Mutant | Must break |
|---|---|
| Drop `if(loading){stale=true;return;}` | one_read_at_a_time and never_shows_older |
| Drop `if(stale){stale=false;refresh();}` | newest_request_lands |
| Set `loading=false` before `load()` | one_read_at_a_time |

If a later change renames these lines, update the mutant text in `scripts/UpNextRefreshCheck.java` so each mutant still breaks the same promise.

## Bounds and limits

- Default bound: 3 refresh requests, 2 Watch Next writes, 2 IO workers. `--refreshes` and `--writes` raise it. 4 refreshes and 3 writes also passed at the sync commit.
- A pass covers that bound only. It is not a proof for every number of refreshes.
- The fakes assume Android's `View.post()` runs posted work on the UI thread in the order posted, and that `WidgetBoard.IO` (`WidgetBoard.java`, a fixed pool of 2 threads) starts tasks in queue order. Fewer workers only remove orders. More workers do not matter while at most one read is in flight.
- `load()` and `show()` are fakes. The check does not cover what `load()` reads, focus handling in `show()`, Promo mode, or the recent-app fallback.
- A widget that is detached keeps its posted work until it attaches again. The check does not model detach.
- Other widgets and poster loads that share `WidgetBoard.IO` only delay starts, and every delay is already one of the explored orders.

## Coverage and sync

| Covered | Where |
|---|---|
| `loading`, `stale`, `refresh()` | `src/dev/luma/tv/UpNextWidget.java`, between the constructor and `load()` |
| `WidgetBoard.IO` pool size and order | `src/dev/luma/tv/WidgetBoard.java` |

Sync commit: `f6e4375` (fix: keep overlapping Up Next refreshes from drawing older rows). The cut text hash at that commit is `674104e092fdb145`, printed on the `region` line of every run.

When the hash changes, read the new cut text and confirm the fakes still match how Android and `WidgetBoard.IO` behave. Then run the check. Move the sync commit and hash forward only after that review. Never weaken a property, lower the bound, or drop a mutant to get a pass. For an intended change in behavior, use `/t3-pstack:maintain-formal-verification-skill`.

CI runs this check through `.github/workflows/upnext-refresh.yml`.
