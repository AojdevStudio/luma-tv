---
name: verify-luma-state
description: Verify the luma.sh launcher journal and restore invariants.
---

# Verify the launcher state machine

Use this skill when `luma.sh` changes its install, home, restore, journal, or error paths. Run from the repository root. Every execution is offline. The helper replaces ADB with a fixture that accepts only the serial `OFFLINE`. It uses no TV, network, SDK, signing key, or credential.

## Run

```sh
python3 .agents/skills/verify-luma-state/scripts/verify.py --self-check --output "$HOME/Backups/luma-verification/state-$(date -u +%Y%m%dT%H%M%SZ)"
```

The standard-library Python checker executes the real shell script over generated Android outcomes. It observes shell commands, journal changes, and ADB entry and return boundaries. It replays every outcome without instrumentation and requires the same output, exit code, Android state, and journal bytes. It audits the choice tree for omitted outcomes.

This is an explicit-state model checker, not a hand-written replacement for the journal logic. No theorem prover or unbounded shell proof is claimed. The confirmed toolchain was Python 3.14.7 and the mini's `/bin/bash`. Each report records the exact runtime versions and tooling hashes.

## Properties

| Check | Required behavior |
|---|---|
| no_luma_original | The saved original is never Luma. |
| no_empty_original | The saved original is never empty. |
| restore_returns_original | A restore that returns success selects the recorded original and enables the recorded launcher. |
| restore_failure_not_success | A failed Android restore command returns an error and retains the journal. |
| baseline_immutable | Home and install preserve the first recorded original until restore. |

An incorrect baseline can strand the user in the wrong launcher. A discarded journal removes the information needed to retry restore.

## Read the result

Read `summary.txt` first, then the matching property in `report.json` and its edges in `traces.jsonl`.

- Exit 0 proves every property only within the declared domain, after a complete fixed point.
- Exit 1 gives a concrete counterexample. The checker stops after that breadth-first layer. Other properties are UNKNOWN unless the complete fixed point was reached.
- Exit 2 means no verification result, for example a stale source hash, unsupported command, recorder mismatch, timeout, or enumeration cap.

A counterexample lists the shortest sequence by operator-action count. It is not necessarily shortest by shell-command count. Follow its source lines and actual journal snapshots. Never turn UNKNOWN into PASS.

`--self-check` also changes real scratch copies of `luma.sh` to break each property. Each mutant must fail and its paired scratch control must pass that property. These checks test the checker. The scratch control is not the product and is not a proof that the checkout passes.

## Scope and assumptions

Read [model/domain.json](model/domain.json) before interpreting a proof. The domain has stock, Luma, chooser, and empty HOME values, one stock launcher, and every declared adaptive Android write outcome. There is one operator. Reads and local file operations succeed. Crashes, power loss, other writers, additional stock launchers, and physical remote behavior are excluded.

The search has no successful depth cutoff. A counterexample refutes a property immediately. A passing property requires the worklist to empty. An enumeration cap is an error. It means no counterexample was found up to the explored distance, not a pass. Journal bytes remain part of state identity, so an endlessly growing journal prevents a complete fixed-point proof.

The full shell invocation is not modeled as one atomic write. The observer records internal command boundaries. It cannot observe individual filesystem bytes or admit concurrent invocations.

## Keep source and coverage synchronized

[model/coverage.json](model/coverage.json) is the source of truth for the checked source SHA, function spans, and property lines. Its sync commit is `14b526d2104c47c0ead57ee54a6b851e6aa9e8c8`, the restore-fix branch before this skills change.

When the source hash changes, inspect the new code, fake Android semantics, finite domain, and mutation anchors. Update that record only after the correspondence is confirmed. Then rerun the command. Move the sync commit only after the review. Do not update a hash merely to silence the guard, remove outcomes, cap away a counterexample, or weaken a property to obtain PASS.

For an intended behavior change, use `/t3-pstack:maintain-formal-verification-skill` to update the coverage and properties together. File a separate product issue for a counterexample outside the current authorized fix.

## Evidence and cleanup

The output directory contains the exact checked source, source and tooling hashes, runtime versions, domain, coverage record, graph, boundary traces, summary, and scratch mutation patches. These are synthetic offline artifacts and contain no live TV state. Output survives automatic fixture cleanup.

The helper removes only its temporary fixtures. It does not edit the product, a live journal, or any key. The schema for report, graph, trace, and mutation files is [model/artifacts.schema.json](model/artifacts.schema.json).

## Helpers

`scripts/verify.py --help` shows the source, output, worker-count, and self-check options. `scripts/fake-adb.bash` defines Android outcomes. `scripts/observe.bash` records boundaries. Review both when the covered shell commands change.
