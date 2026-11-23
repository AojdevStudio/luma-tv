#!/usr/bin/env bash
# Model-check Luma's scene dispatch with TLC. Exit 0 only when every expectation below holds.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
jar=${TLA2TOOLS_JAR:-$HOME/.cache/tla/tla2tools-1.8.0.jar}
[[ $jar = /* ]] || jar="$PWD/$jar"
sum=fdffef247f563ef6edbfd44958d2a82db79e7564e07cb673262d896353fa6713
if [ ! -f "$jar" ]; then
  mkdir -p "$(dirname "$jar")"
  curl -sSfL -o "$jar" https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar
fi
echo "$sum  $jar" | sha256sum -c --quiet -
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$here/model/SceneDispatch.tla" "$work/"

# The flags as the code stands at the sync commit in SKILL.md.
code="CheckAtTake=TRUE PaneOwned=TRUE KeepUnseen=FALSE ReplyPlan=FALSE Unreadable=FALSE"
# Properties the code keeps. TLC must find no counterexample.
holds="TypeOK NoChildHandOff ResultInOwnPane ResultInOwnPaneAfterSend AtMostOneSend"
# Open defects. Each must still show its counterexample until its fix moves it into holds.
findings="NoLostFailure NoLostReply NoStaleStarting RanPlanReported NotRunMeansNothingRan"

failed=0
# tlc <name> <flags> <invariants>: runs TLC and prints the first violated invariant, or "none".
tlc() {
  local name=$1 flags=$2 invariants=$3 dir="$work/$1"
  mkdir -p "$dir"
  cp "$work/SceneDispatch.tla" "$dir/"
  {
    echo "SPECIFICATION Spec"
    echo "CONSTANTS"
    for pair in $flags; do echo "  ${pair%%=*} = ${pair#*=}"; done
    echo "  MaxRev = 1"
    for inv in $invariants; do echo "INVARIANT $inv"; done
  } > "$dir/SceneDispatch.cfg"
  (cd "$dir" && java -XX:+UseParallelGC -cp "$jar" tlc2.TLC -deadlock -workers auto -cleanup SceneDispatch > tlc.out 2>&1) || true
  if grep -q "Model checking completed. No error has been found." "$dir/tlc.out"; then echo none
  else
    local broken
    broken=$(sed -n 's/^Error: Invariant \([A-Za-z]*\) is violated.*/\1/p' "$dir/tlc.out" | head -1)
    echo "${broken:-error}"
  fi
}
# expect <name> <flags> <invariants> <wanted>: wanted is "none" for a pass, or the invariant that must break.
expect() {
  local got
  got=$(tlc "$1" "$2" "$3")
  if [ "$got" = "$4" ]; then echo "ok    $1: ${4/none/no counterexample}"
  else
    echo "FAIL  $1: wanted ${4/none/no counterexample}, got $got"
    cat "$work/$1/tlc.out"
    failed=1
  fi
}
trace() { grep -o '^State [0-9]*: <[A-Za-z]*' "$work/$1/tlc.out" | sed 's/^State \([0-9]*\): </  \1. /'; }

expect code "$code" "$holds" none
for property in $findings; do
  expect "finding-$property" "$code" "$property" "$property"
  trace "finding-$property"
done
# Controls. Each refusal path must stay reachable with the flags as the code stands.
for control in NeverQueuedChildRefusal NeverRefusedBeforeRun; do
  expect "control-$control" "$code" "$control" "$control"
  trace "control-$control"
done
# Past failures. Turning off the fix must bring back the counterexample.
expect before-6aa4885 "${code/CheckAtTake=TRUE/CheckAtTake=FALSE}" NoChildHandOff NoChildHandOff
trace before-6aa4885
# The trace must send the scene, move to another pane and then deliver the late reply.
expect before-fc47a32 "${code/PaneOwned=TRUE/PaneOwned=FALSE}" ResultInOwnPaneAfterSend ResultInOwnPaneAfterSend
trace before-fc47a32
# Outside Luma: with every Luma fix on, House Control can still run a plan imported after Luma's last check.
expect provider-gap "CheckAtTake=TRUE PaneOwned=TRUE KeepUnseen=TRUE ReplyPlan=TRUE Unreadable=TRUE" RanAsConfirmed RanAsConfirmed
trace provider-gap
exit $failed
