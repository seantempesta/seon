---
type: issue
status: open
severity: friction
tags: [issue, prompt, test, turn]
---

# Prompt tests retain incompatible turn fixtures

On 2026-09-14, a HEAD-only paths snapshot at `66c8960e7` reproduced
`seon.cluster.prompt-test/a-held-run-without-a-trigger-refuses` returning no
expected `:seon.cluster.prompt/no-trigger` rule, and
`later-evaluations-preserve-the-opening-history` failing during fixture setup
with `:seon.turn/agent-already-running`. Neither failure exercised the debug
lane's historical acquisition edit. Evidence:
`tmp/debug-product/prompt-head-baseline.log` (the remaining run was interrupted
by TERM when a new owner message arrived; no complete green/red tally claimed).

The latter test also expects a named earlier turn's prompt to gain later
evaluations, contradicting the historical prompt requirement. Repair these
fixtures through real turn transitions, and assert immutable earlier prompts
alongside growth at a newly opened turn. The debug loop regression already
compares each reply turn against its opening fold on the canonical fixture.

Acceptance: the complete `seon.cluster.prompt-test` namespace passes under
armed contracts, with no mocked acquisition in the history regression.
