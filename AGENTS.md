<!-- pr-kit:start -->
## Pull requests

- Open every PR with `.claude/skills/open-pr/SKILL.md` and land it with `.claude/skills/land-pr/SKILL.md`. Read the full skill before use.
- The PR body has five fixed sections: Problem, Change, Proof, Review, Merge danger. Merge danger names the door and the blast radius.
- The open-pr gate and the `pr-kit` CI check refuse a body that breaks the contract. Fix the body. Never route around either.
<!-- pr-kit:end -->

## Verification

- For source builds and TCL app checks, use `.agents/skills/verify-luma/SKILL.md`.
- For `luma.sh` state and journal changes, use `.agents/skills/verify-luma-state/SKILL.md`. Report counterexamples and UNKNOWN results without relabeling them as passes.

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for `AojdevStudio/luma-tv`. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the canonical labels `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, and `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repo. See `docs/agents/domain.md`.
