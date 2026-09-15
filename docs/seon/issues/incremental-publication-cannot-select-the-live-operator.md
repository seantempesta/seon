---
type: issue
status: open
severity: friction
tags: [issue, operator, mcp, wave/dev-mcp]
---

# Incremental publication cannot select the live operator

## Evidence — 2026-09-15

`bin/seon init --dev default --changed src/seon/render.clj src/seon/turn.clj test/seon/sci/eval_test.clj`
refused: `Incremental source publication requires a running operator JVM.`
Immediately afterwards `bin/seon status` reported default alive, PID 69622,
prepl 55914, generation `997f66f8-1102-4127-a9c3-833db941320d`.
MCP JVM evaluation on that same default answered and recorded test results.
An earlier publication in this session converged without replacing the JVM.

The refusal is the no-anchor branch at `script/seon/fresh_operator.clj:2496`;
`select-anchor` receives `reconciled-truth!` at lines 2469–2470. These observations
verify disagreement between operation eligibility and status, not its cause.
An initial malformed invocation repeated `--changed`; the corrected command
above reproduced the same refusal.

## Boundary

P1 does not stop or restart default to repair discovery. The next probe can
call the same owning `seon.cluster/refresh-source!` function through MCP with
the operator's root and changed paths. That is a separate invocation surface,
not proof that CLI discovery is repaired.

## Acceptance

On the live operator described by status, the canonical incremental publication
command selects that JVM, converges, and reports its source commit. If it cannot,
its refusal identifies the actual missing eligibility fact rather than implying
that no JVM is running.
