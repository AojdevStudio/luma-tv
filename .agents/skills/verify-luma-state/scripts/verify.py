#!/usr/bin/env python3
"""Exhaustive explicit-state checking of real luma.sh runs in a finite Android domain.

No TLA+ abstraction is used. Breadth-first search reaches a fixed point over
quiescent states, or stops on a conclusive counterexample. Each edge expands every adaptive Android outcome tape by
executing the source. Shell DEBUG, ADB, and EXIT observations expose the edge's
internal main-shell command and journal boundaries. Only complete invocations can start
the next operator action. This is a bounded proof, never a full shell proof.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
import difflib
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
MODEL = HERE.parent / "model"
LUMA = "dev.luma.tv/.MainActivity"
STOCK = "com.google.android.tvlauncher/.Home"
CHOOSER = "android/.ResolverActivity"
COMPONENTS = {"S": STOCK, "L": LUMA, "C": CHOOSER, "E": ""}
PROPERTY_TEXT = {
    "no_luma_original": "Luma is never saved as its own original Home.",
    "no_empty_original": "An empty Home is never saved as the original Home.",
    "restore_returns_original": "A restore that exits zero returns to the recorded original Home and enables recorded launchers.",
    "restore_failure_not_success": "A failed Android restore command is reported by a nonzero exit and retains the journal for retry.",
    "baseline_immutable": "Home and install keep the first recorded original unchanged until restore.",
}


class VerificationError(Exception):
    """An incomplete enumeration or unsupported source cannot establish a result."""


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


@dataclass(frozen=True, order=True)
class State:
    home: str
    installed: bool
    stock_enabled: bool
    journal: str | None

    def data(self) -> dict[str, object]:
        return {"home": COMPONENTS[self.home], "luma_installed": self.installed,
                "stock_enabled": self.stock_enabled, "journal": self.journal}


@dataclass(frozen=True)
class Event:
    phase: str
    line: int
    result: int | None
    state: State
    command: str
    caller_lines: tuple[int, ...] = ()

    def data(self) -> dict[str, object]:
        return {"phase": self.phase, "source_line": self.line or None,
                "exit_code": self.result, "state": self.state.data(), "command": self.command,
                "caller_lines": list(self.caller_lines)}


@dataclass(frozen=True)
class Choice:
    operation: str
    selected: str
    options: tuple[str, ...]


@dataclass(frozen=True)
class Run:
    action: str
    start: State
    end: State
    exit_code: int
    choices: tuple[Choice, ...]
    events: tuple[Event, ...]
    stdout: str
    stderr: str

    def data(self) -> dict[str, object]:
        return {"action": self.action, "start": self.start.data(), "end": self.end.data(),
                "exit_code": self.exit_code,
                "choices": [{"operation": c.operation, "selected": c.selected,
                             "options": list(c.options)} for c in self.choices],
                "events": [e.data() for e in self.events],
                "stdout": self.stdout, "stderr": self.stderr}


@dataclass
class OutcomeNode:
    operation: str | None = None
    options: tuple[str, ...] = ()
    children: dict[str, OutcomeNode] = field(default_factory=dict)
    terminal: bool = False


def audit_outcome_tree(runs: list[Run]) -> None:
    """Independently reconstruct the observed choice tree and reject gaps."""
    root = OutcomeNode()
    for run in runs:
        node = root
        for choice in run.choices:
            if node.terminal:
                raise VerificationError("A completed outcome tape unexpectedly has descendants.")
            if node.operation is None:
                node.operation, node.options = choice.operation, choice.options
            if (node.operation, node.options) != (choice.operation, choice.options):
                raise VerificationError("A choice prefix does not determine its next external operation.")
            node = node.children.setdefault(choice.selected, OutcomeNode())
        if node.terminal or node.children:
            raise VerificationError("Duplicate or overlapping outcome tapes.")
        node.terminal = True
    pending = [root]
    while pending:
        node = pending.pop()
        if node.terminal:
            if node.operation is not None or node.children:
                raise VerificationError("Malformed terminal choice node.")
        elif set(node.children) != set(node.options) or not node.options:
            raise VerificationError("Outcome enumeration omitted an Android branch. No fixed-point result.")
        pending.extend(node.children.values())


def original(journal: str | None) -> str | None:
    if journal is None:
        return None
    return next((line.removeprefix("original=") for line in journal.splitlines()
                 if line.startswith("original=")), None)


def parse_events(path: Path) -> tuple[Event, ...]:
    events: list[Event] = []
    for row in path.read_text().splitlines():
        fields = row.split("\t", 8)
        if len(fields) != 9:
            raise VerificationError("Malformed boundary record.")
        phase, line, result, home, installed, enabled, journal, callers, command = fields
        if phase not in {"shell_before", "shell_exit", "adb_before", "adb_after"}:
            raise VerificationError("Unknown boundary phase.")
        if home not in COMPONENTS or installed not in {"0", "1"} or enabled not in {"0", "1"}:
            raise VerificationError("Android state escaped the declared finite domain.")
        state = State(home, installed == "1", enabled == "1",
                      None if journal == "-" else journal.replace(";", "\n"))
        caller_lines = tuple(int(value) for value in callers.split() if int(value) > 1)
        source_line = int(line)
        if phase.startswith("adb_"):
            transport = next((event for event in reversed(events)
                              if event.phase == "shell_before"), None)
            if transport is not None:
                source_line, caller_lines = transport.line, transport.caller_lines
        events.append(Event(phase, source_line, None if result == "-" else int(result), state, command, caller_lines))
    if not events or events[-1].phase != "shell_exit":
        raise VerificationError("The real shell did not record its exit boundary.")
    return tuple(events)


class Executor:
    def __init__(self, source: str):
        self.source = source
        domain = read_object(MODEL / "domain.json")
        choices = domain.get("android_outcomes")
        if not isinstance(choices, dict):
            raise VerificationError("Domain must specify Android outcomes.")
        self.options: dict[str, tuple[str, ...]] = {}
        for operation, values in choices.items():
            if not isinstance(operation, str) or not isinstance(values, list) or not all(isinstance(value, str) for value in values):
                raise VerificationError("Android outcome domain has invalid types.")
            self.options[operation] = tuple(values)

    def outcomes(self, state: State, action: str) -> list[Run]:
        """Enumerate all leaves of the adaptive finite external-choice tree.

        A run takes a supplied prefix and chooses the first option at each new
        decision. Every other option becomes a pending prefix. Induction on
        the finite decision tree proves this visits every leaf exactly once.
        The source has a four-iteration loop. A safety cap is an error, not a
        truncated pass. Prefix mismatches and unknown commands also fail shut.
        """
        plans: list[tuple[tuple[str, str], ...]] = [()]
        runs: list[Run] = []
        with tempfile.TemporaryDirectory(prefix="luma-state-fixture-", dir="/tmp") as name:
            root = Path(name)
            os.chmod(root, 0o700)
            (root / "bin").mkdir()
            (root / "dist").mkdir()
            (root / ".local").mkdir()
            (root / "dist/luma-tv.apk").write_text("OFFLINE PLACEHOLDER. NEVER INSTALLED.\n")
            (root / "luma.sh").write_text(self.source)
            (root / "build.sh").write_text("#!/bin/bash\nexit 98\n")
            shutil.copyfile(HERE / "fake-adb.bash", root / "bin/adb")
            os.chmod(root / "bin/adb", 0o700)
            # No inherited PATH, HOME, SDK, device, credentials, or shell hooks.
            # Only these local text tools and fake adb are executable by name.
            for tool in ("bash", "dirname", "tr", "tail", "mkdir", "grep", "sed", "head", "rm", "mv"):
                executable = shutil.which(tool, path="/usr/bin:/bin")
                if executable is None:
                    raise VerificationError(f"Required local text tool is missing: {tool}")
                (root / "bin" / tool).symlink_to(executable)
            environment = {
                "PATH": str(root / "bin"), "LC_ALL": "C", "LANG": "C",
                "LUMA_OBSERVER": str(HERE / "observe.bash"),
                "LUMA_FIXTURE": str(root),
            }
            while plans:
                if len(runs) >= 4096:
                    raise VerificationError("More than 4096 outcome leaves in one action. No fixed-point claim.")
                plan = plans.pop()
                for filename in ("cursor", "choices.tsv", "error", "events.tsv"):
                    (root / filename).unlink(missing_ok=True)
                journal = root / ".local/home-OFFLINE.txt"
                if state.journal is None:
                    journal.unlink(missing_ok=True)
                else:
                    journal.write_text(state.journal)
                (root / "android.env").write_text(
                    f"home={state.home}\ninstalled={int(state.installed)}\nstock_enabled={int(state.stock_enabled)}\n")
                (root / "plan.tsv").write_text("".join(f"{op}\t{value}\n" for op, value in plan))
                completed = subprocess.run(
                    ["/bin/bash", "-c", 'source "$LUMA_OBSERVER"; source "$LUMA_FIXTURE/luma.sh" "$@"',
                     str(root / "luma.sh"), "-s", "OFFLINE", action],
                    cwd=root, env=environment, capture_output=True, text=True, timeout=10,
                )
                if (root / "error").exists():
                    raise VerificationError((root / "error").read_text().strip())
                if completed.returncode not in {0, 1}:
                    raise VerificationError(f"Unexpected shell exit {completed.returncode}: {completed.stderr.strip()}")
                events = parse_events(root / "events.tsv")
                if events[-1].result != completed.returncode:
                    raise VerificationError("Recorded exit differs from actual process exit.")
                if events[-1].state.journal != (journal.read_text() if journal.exists() else None):
                    raise VerificationError("Boundary recorder disagrees with the actual journal file.")
                choices: list[Choice] = []
                choices_path = root / "choices.tsv"
                for row in choices_path.read_text().splitlines() if choices_path.exists() else ():
                    op, selected, options = row.split("\t")
                    option_list = tuple(options.split())
                    category = "set_home" if op.startswith("set_home_") else op
                    if option_list != self.options.get(category):
                        raise VerificationError("Fake ADB outcomes disagree with model/domain.json.")
                    choices.append(Choice(op, selected, option_list))
                if len(choices) < len(plan):
                    raise VerificationError("An outcome tape was not fully consumed.")
                for index in range(len(plan), len(choices)):
                    choice = choices[index]
                    prefix = tuple((c.operation, c.selected) for c in choices[:index])
                    for alternative in reversed(choice.options[1:]):
                        plans.append(prefix + ((choice.operation, alternative),))
                normalized = lambda text: text.replace(str(root), "<fixture>")
                # Replay this complete tape through a normal, uninstrumented
                # bash invocation. The observer and source wrapper must not
                # alter exit, output, Android writes, or actual journal bytes.
                expected_journal = journal.read_text() if journal.exists() else None
                expected_android = (root / "android.env").read_text()
                expected_choices = choices_path.read_text() if choices_path.exists() else ""
                for filename in ("cursor", "choices.tsv", "error", "events.tsv"):
                    (root / filename).unlink(missing_ok=True)
                if state.journal is None:
                    journal.unlink(missing_ok=True)
                else:
                    journal.write_text(state.journal)
                (root / "android.env").write_text(
                    f"home={state.home}\ninstalled={int(state.installed)}\nstock_enabled={int(state.stock_enabled)}\n")
                (root / "plan.tsv").write_text("".join(f"{c.operation}\t{c.selected}\n" for c in choices))
                direct = subprocess.run(
                    ["/bin/bash", str(root / "luma.sh"), "-s", "OFFLINE", action],
                    cwd=root, env=environment, capture_output=True, text=True, timeout=10,
                )
                if (root / "error").exists():
                    raise VerificationError((root / "error").read_text().strip())
                actual_choices = choices_path.read_text() if choices_path.exists() else ""
                actual_journal = journal.read_text() if journal.exists() else None
                if (direct.returncode, direct.stdout, direct.stderr, actual_journal,
                        (root / "android.env").read_text(), actual_choices) != (
                        completed.returncode, completed.stdout, completed.stderr, expected_journal,
                        expected_android, expected_choices):
                    raise VerificationError("Instrumented and normal source execution disagree. No verification result.")
                runs.append(Run(action, state, events[-1].state, completed.returncode,
                                tuple(choices), events, normalized(completed.stdout), normalized(completed.stderr)))
        return runs


def violations(run: Run) -> dict[str, Event]:
    result: dict[str, Event] = {}
    baseline = original(run.start.journal)
    for event in run.events:
        saved = original(event.state.journal)
        if saved == LUMA:
            result.setdefault("no_luma_original", event)
        if saved == "":
            result.setdefault("no_empty_original", event)
        if run.action != "restore":
            if baseline is not None and saved != baseline:
                result.setdefault("baseline_immutable", event)
            if baseline is None and saved is not None:
                baseline = saved
    if run.action == "restore":
        saved = original(run.start.journal)
        disabled = run.start.journal is not None and "disabled=com.google.android.tvlauncher\n" in run.start.journal
        if saved is not None and run.exit_code == 0:
            if COMPONENTS[run.end.home] != saved or (disabled and not run.end.stock_enabled):
                result["restore_returns_original"] = run.events[-1]
        failure = next((event for event in run.events if event.phase == "adb_after" and event.result != 0
                        and (" pm enable " in event.command or " set-home-activity " in event.command)), None)
        if failure is not None and (run.exit_code == 0 or run.end.journal != run.start.journal):
            result["restore_failure_not_success"] = failure
    return result


def seeds() -> list[State]:
    return sorted(State(home, installed, True, None) for home in COMPONENTS
                  for installed in (False, True) if home != "L" or installed)


def state_key(state: State) -> tuple[str, bool, bool, str]:
    return (state.home, state.installed, state.stock_enabled, state.journal or "")


def story(run: Run) -> str:
    names = {"S": "the stock launcher", "L": "Luma", "C": "the Android chooser", "E": "an empty Home"}
    words = [f"Run {run.action} while Home is {names[run.start.home]}."]
    for choice in run.choices:
        if choice.operation.startswith("set_home_"):
            target = choice.operation.removeprefix("set_home_")
            unavailable = any(event.phase == "adb_after" and event.command.endswith("outcome=unavailable")
                              and f"set-home-activity --user 0 {COMPONENTS[target]}" in event.command
                              for event in run.events)
            if choice.selected == "fail" or unavailable:
                words.append(f"Android returns an error when asked to use {names[target]} as Home.")
            elif choice.selected == "keep":
                words.append(f"Android returns success but keeps Home unchanged when asked to use {names[target]}.")
            else:
                words.append(f"Android sets Home to {names[target]}.")
        elif choice.operation in {"enable", "disable"}:
            if choice.selected == "fail":
                words.append(f"Android fails to {choice.operation} the stock launcher.")
            else:
                verb = "enables" if choice.operation == "enable" else "disables"
                words.append(f"Android {verb} the stock launcher.")
        elif choice.operation == "install":
            if choice.selected == "fail":
                words.append("Android rejects the install.")
            elif choice.selected == "preserve":
                words.append("Android installs Luma and keeps Home unchanged.")
            else:
                reset = {"stock": "S", "chooser": "C", "luma": "L", "empty": "E"}[choice.selected]
                if reset == "S" and not run.start.stock_enabled:
                    reset = "C"
                words.append(f"Android installs Luma and resets Home to {names[reset]}.")
    words.append(f"The script exits {run.exit_code}. Home ends at {names[run.end.home]}.")
    saved = original(run.end.journal)
    if run.end.journal is None:
        words.append("The journal is absent.")
    elif saved is not None:
        label = next((names[key] for key, component in COMPONENTS.items() if component == saved), saved)
        words.append(f"The journal records {label} as the original.")
    return " ".join(words)


def check(source: str, output: Path, workers: int) -> dict[str, object]:
    executor = Executor(source)
    initial = seeds()
    paths: dict[State, tuple[int, ...]] = {state: () for state in initial}
    frontier = initial
    first: dict[str, dict[str, object]] = {}
    all_edges: list[dict[str, object]] = []
    boundary_states: set[State] = set()
    boundary_steps = 0
    shell_runs = 0
    expanded: set[tuple[State, str]] = set()
    depth = 0
    with (output / "traces.jsonl").open("w") as traces, ThreadPoolExecutor(max_workers=workers) as pool:
        while frontier:
            tasks = [(state, action) for state in sorted(frontier, key=state_key)
                     for action in ("home", "install", "restore")]
            next_frontier: set[State] = set()
            # map preserves deterministic breadth-first order despite parallel execution.
            for (state, action), runs in zip(tasks, pool.map(lambda task: executor.outcomes(*task), tasks), strict=True):
                audit_outcome_tree(runs)
                if (state, action) in expanded:
                    raise VerificationError("A state and action was expanded twice.")
                expanded.add((state, action))
                for run in runs:
                    edge_id = len(all_edges)
                    chain = paths[state] + (edge_id,)
                    found = violations(run)
                    record = run.data()
                    record["schema_version"] = 1
                    record["edge_id"] = edge_id
                    record["violations"] = sorted(found)
                    traces.write(json.dumps(record, sort_keys=True) + "\n")
                    all_edges.append({"edge_id": edge_id, "action": run.action,
                                      "start": state.data(), "end": run.end.data(), "exit_code": run.exit_code,
                                      "outcomes": [f"{c.operation}={c.selected}" for c in run.choices],
                                      "words": story(run)})
                    boundary_states.update(event.state for event in run.events)
                    boundary_steps += max(0, len(run.events) - 1)
                    shell_runs += 1
                    for prop, event in found.items():
                        if prop not in first:
                            first[prop] = {"edge_ids": list(chain), "operator_actions": len(chain),
                                           "first_boundary": event.data(),
                                           "words": [str(all_edges[index]["words"]) for index in chain]}
                    if run.end not in paths:
                        paths[run.end] = chain
                        next_frontier.add(run.end)
                        if len(paths) > 2048:
                            raise VerificationError("More than 2048 quiescent states. No fixed-point claim.")
            frontier = sorted(next_frontier, key=state_key)
            depth += 1
            print(f"Explored operator distance {depth - 1}. States {len(paths)}. Outcome traces {shell_runs}.", flush=True)
            if first:
                break
    fixed_point = not frontier
    if fixed_point and expanded != {(state, action) for state in paths for action in ("home", "install", "restore")}:
        raise VerificationError("A reachable state and action was not expanded. No fixed-point result.")
    write_json(output / "graph.json", {"schema_version": 1,
               "complete": fixed_point, "initial_states": [state.data() for state in initial],
               "states": [state.data() for state in sorted(paths, key=state_key)], "edges": all_edges})
    return {"fixed_point": fixed_point, "terminated_on_counterexample": bool(first), "initial_states": len(initial), "quiescent_states": len(paths),
            "operation_transitions": len(all_edges), "boundary_snapshot_states": len(boundary_states),
            "boundary_transition_occurrences": boundary_steps,
            "instrumented_source_executions": shell_runs, "normal_source_executions": shell_runs,
            "source_executions": shell_runs * 2, "observer_equivalence": "pass_for_every_outcome_trace",
            "choice_tree_audit": "pass_for_every_expanded_state_action", "expanded_state_actions": len(expanded),
            "maximum_shortest_operator_distance": max(map(len, paths.values())),
            "properties": {prop: {"statement": text, "status": "fail" if prop in first else "pass" if fixed_point else "unknown",
                                   "exit_code": 1 if prop in first else 0 if fixed_point else 2,
                                   "counterexample": first.get(prop)} for prop, text in PROPERTY_TEXT.items()}}


def replace_once(source: str, old: str, new: str) -> str:
    if source.count(old) != 1:
        raise VerificationError(f"Mutation anchor must occur once: {old!r}")
    return source.replace(old, new, 1)


def self_check(source: str, output: Path) -> dict[str, object]:
    """Differential mutation checks, on actual source copies, never product edits.

    The control is explicitly a scratch repair, not a claim about this repo.
    Each witness enumerates every outcome at each step of the stated sequence.
    The target property must pass on the control and fail on its single mutant.
    This prevents the already-red upstream source from making a mutation test
    pass vacuously. These are tooling checks, separate from fixed-point proof.
    """
    guard = '        [ "$original" != "$LUMA" ] || die "offline control: Luma original"\n'
    control = replace_once(source, '        original="$(home_now)"\n',
                           '        original="$(home_now)"\n' + guard)
    (output / "scratch-control.patch").write_text("".join(difflib.unified_diff(
        source.splitlines(keepends=True), control.splitlines(keepends=True),
        fromfile="checked-luma.sh", tofile="scratch-control-luma.sh")))
    mutations: dict[str, tuple[str, State, tuple[str, ...]]] = {
        "no_luma_original": (replace_once(control, guard, ''),
                             State("L", True, True, None), ("home",)),
        "no_empty_original": (replace_once(control, '        [ -n "$original" ] || die "could not read the TV\'s home screen"\n', ''),
                              State("E", True, True, None), ("home",)),
        "restore_returns_original": (replace_once(control, '        [ "$(home_now)" = "$original" ] || die "the TV did not select $original; restore journal kept"\n', ''),
                                     State("S", True, True, None), ("home", "restore")),
        "restore_failure_not_success": (replace_once(control,
            'tv cmd package set-home-activity --user 0 "$original" >/dev/null || die "could not restore $original; restore journal kept"',
            'tv cmd package set-home-activity --user 0 "$original" >/dev/null || true'),
            State("S", True, True, None), ("home", "restore")),
        "baseline_immutable": (replace_once(control, 'if [ ! -s "$state" ]; then\n', 'if true; then\n'),
                               State("S", True, True, None), ("home", "install", "home")),
    }
    results: dict[str, object] = {}
    for prop, (mutant, initial, actions) in mutations.items():
        (output / f"mutation-{prop}.patch").write_text("".join(difflib.unified_diff(
            control.splitlines(keepends=True), mutant.splitlines(keepends=True),
            fromfile="scratch-control-luma.sh", tofile=f"scratch-mutant-{prop}-luma.sh")))
        counts: dict[str, int] = {}
        witnesses: dict[str, object] = {}
        for label, text in (("control", control), ("mutant", mutant)):
            executor = Executor(text)
            frontier: dict[State, tuple[Run, ...]] = {initial: ()}
            count = 0
            witness: tuple[Run, ...] | None = None
            for action in actions:
                following: dict[State, tuple[Run, ...]] = {}
                for state, path in sorted(frontier.items(), key=lambda item: state_key(item[0])):
                    for run in executor.outcomes(state, action):
                        count += 1
                        chain = path + (run,)
                        if prop in violations(run) and witness is None:
                            witness = chain
                        following.setdefault(run.end, chain)
                frontier = following
            counts[label] = count
            if label == "control" and witness is not None:
                raise VerificationError(f"Mutation control already violates {prop}.")
            if label == "mutant" and witness is None:
                raise VerificationError(f"Actual-source mutation was not detected: {prop}.")
            if witness is not None:
                witnesses[label] = [run.data() for run in witness]
        results[prop] = {"status": "pass", "control_sha256": digest(control.encode()),
                         "mutant_sha256": digest(mutant.encode()), "outcome_traces": counts,
                         "source_executions": {label: count * 2 for label, count in counts.items()},
                         "operator_sequence": list(actions), "witness": witnesses.get("mutant")}
        print(f"Mutation caught. {prop}.", flush=True)
    write_json(output / "mutations.json", {"schema_version": 1, "checks": results})
    return {"status": "pass", "properties_caught": sorted(results),
            "method": "Differential actual-source scratch mutations. Exhaustive outcomes within each stated witness sequence. Not a full fixed-point proof of the scratch control."}


def read_object(path: Path) -> dict[str, object]:
    value: object = json.loads(path.read_text())
    if not isinstance(value, dict) or not all(isinstance(key, str) for key in value):
        raise VerificationError(f"Expected a JSON object: {path.name}")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=HERE.parents[3] / "luma.sh")
    parser.add_argument("--output", type=Path, help="New persistent directory. Default: private /tmp/luma-state-results-*.")
    parser.add_argument("--workers", type=int, default=4, choices=range(1, 9), metavar="1..8")
    parser.add_argument("--self-check", action="store_true", help="Also catch five mutations in real source copies.")
    args = parser.parse_args()
    try:
        if args.output:
            args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
            output = args.output.resolve()
        else:
            output = Path(tempfile.mkdtemp(prefix="luma-state-results-", dir="/tmp"))
    except OSError as error:
        print(f"ERROR. Cannot create a new artifact directory. {error}", file=sys.stderr)
        return 2
    print(f"Artifacts: {output}", flush=True)
    try:
        source_bytes = args.source.read_bytes()
        coverage = read_object(MODEL / "coverage.json")
        if digest(source_bytes) != coverage.get("source_sha256"):
            raise VerificationError("luma.sh hash differs from model/coverage.json. Review source boundaries, Android domain, and mutation anchors before updating the coverage record.")
        domain = read_object(MODEL / "domain.json")
        if domain.get("schema_version") != 1 or domain.get("home_domain") != COMPONENTS or domain.get("actions") != ["home", "install", "restore"]:
            raise VerificationError("Declared state and action domain disagrees with the checker.")
        if domain.get("safety_caps") != {"quiescent_states": 2048, "outcome_leaves_per_state_action": 4096, "seconds_per_source_execution": 10}:
            raise VerificationError("Declared enumeration caps disagree with the checker.")
        write_json(output / "domain.json", domain)
        write_json(output / "coverage.json", coverage)
        tooling_paths = sorted([HERE / "verify.py", HERE / "observe.bash", HERE / "fake-adb.bash", *MODEL.glob("*.json")])
        tooling_hashes = {str(path.relative_to(HERE.parent)): digest(path.read_bytes()) for path in tooling_paths}
        # Preserve the exact checked source, independent of later branch changes.
        (output / "luma.sh.checked").write_bytes(source_bytes)
        source = source_bytes.decode()
        result = check(source, output, args.workers)
        mutation_result: dict[str, object] = {"status": "not_requested"}
        if args.self_check:
            mutation_result = self_check(source, output)
        if args.source.read_bytes() != source_bytes:
            raise VerificationError("Product source changed during verification. Rerun on a stable checkout.")
        if tooling_hashes != {str(path.relative_to(HERE.parent)): digest(path.read_bytes()) for path in tooling_paths}:
            raise VerificationError("Verification tooling changed during the run. Rerun with stable tooling.")
        properties = result["properties"]
        if not isinstance(properties, dict):
            raise VerificationError("Internal result schema error.")
        failed = [name for name, value in properties.items() if isinstance(value, dict) and value.get("status") == "fail"]
        exit_code = 1 if failed else 0
        report = {"schema_version": 1, "status": "fail" if failed else "pass", "exit_code": exit_code,
                  "method": "actual-source finite explicit-state fixed-point model checking",
                  "source_sha256": digest(source_bytes), "coverage": coverage, "domain": domain,
                  "toolchain": {"python": sys.version.split()[0], "bash": subprocess.run(
                      ["/bin/bash", "--version"], capture_output=True, text=True, check=True).stdout.splitlines()[0]},
                  "tooling_sha256": tooling_hashes,
                  "verification": result, "mutation_self_check": mutation_result}
        write_json(output / "report.json", report)
        search_result = "Fixed point reached." if result["fixed_point"] else "Stopped on a counterexample. Remaining properties are unproven."
        lines = [f"Overall {'FAIL' if failed else 'PASS'}. Exit {exit_code}.",
                 f"{search_result} Discovered {result['quiescent_states']} quiescent states. Executed {result['operation_transitions']} operation outcomes.",
                 f"Observed {result['boundary_transition_occurrences']} internal boundary transitions."]
        for name, value in properties.items():
            if not isinstance(value, dict):
                raise VerificationError("Invalid property result.")
            lines.append(f"{str(value['status']).upper()} {name}. {value['statement']}")
            counterexample = value.get("counterexample")
            if isinstance(counterexample, dict):
                lines.append(f"Shortest counterexample by operator actions. {counterexample['operator_actions']}.")
                words = counterexample["words"]
                if isinstance(words, list):
                    lines.extend(f"  {index + 1}. {word}" for index, word in enumerate(words))
                event = counterexample["first_boundary"]
                if isinstance(event, dict):
                    lines.append(f"  Evidence. Source line {event['source_line']}. {event['command']}.")
        lines.append(f"Mutation self-check. {mutation_result['status']}.")
        (output / "summary.txt").write_text("\n".join(lines) + "\n")
        print("\n".join(lines), flush=True)
        print(f"Artifacts: {output}", flush=True)
        return exit_code
    except (OSError, ValueError, TypeError, KeyError, VerificationError, subprocess.SubprocessError, KeyboardInterrupt) as error:
        write_json(output / "report.json", {"schema_version": 1, "status": "error", "exit_code": 2,
                                           "error": str(error), "fixed_point": False})
        print(f"ERROR. Verification incomplete. {error}", file=sys.stderr, flush=True)
        return 2


if __name__ == "__main__":
    sys.exit(main())
