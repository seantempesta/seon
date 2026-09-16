---
type: issue
status: open
severity: blocker
tags: [issue, test, database]
---

# Transcript and turn fixtures retain deleted storage and turn shapes

## Problem

Fixing optional-identity write selection exposes test-local seeds that still
write deleted result-edn storage or incomplete turn entities. These local
seed helpers ignore flat db/transact! refusals, then assert over absent data.
The canonical test-support setup helpers now stop loudly (`20d30a0bd`);
test-local helpers need the same result-checking discipline.

## Evidence

2026-09-16 on default pid 7595, after the admission fix:
seon.render.transcript-test/seed-populated-history! returns
:seon.db/invalid-write at [7 :seon.cluster.eval/result-edn],
“expected an installed attribute, got an undeclared attribute”.
The old refusal at the problems identity is gone.

seon.render.transcript-test/admitted-top-level-string-is-terminal-text
resolves deleted bounded-result (ff9507c1b) and produces 0/0/3.
seon.turn-loop-test/attempt-settlement-updates-the-registered-model-gauges
seeds {:seon.turn/id "gauge-run"} without required fields and remains 0/4/0.
a-refused-terminal-commit-still-closes-the-run remains 1/1/0 because its
closed-at helper expects an instant where closed-tx is a ref.
kill-positions-per-agent-test remains 1/5/1; commit-run! lacks runtime/turns
membership required by open-for-agent (ae0e54841). Its additional error
must be verified independently.

## Owner

The Opus transcript/turn fixture fix assignment. No transcript or turn
production/test file was modified by write-validation-class.

## Acceptance

Read every seed transaction result. Use stored shown text and current
evaluation identities, create turn links through the owning constructor,
and assert closed-tx as a ref. Preserve wanted behavior; do not weaken
admission to restore deleted schemas.

The exact 18 transcript and three turn test names, counts and verification
boundaries are in [the dated handoff](../../prds/steward-platform/research/write-validation-class-2026-09-16.md).
Six transcript tests pass; the other twelve and three turn tests remain
to verify/fix. This note does not attribute all remaining reds to one cause.
