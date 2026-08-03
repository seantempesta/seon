---
type: issue
status: resolved
severity: blocker
tags: [issue, repl, agent, sci]
---

# A comment-only plan form yields a failed receipt where a REPL shows nothing

## Evidence

[repl-display-conventions-2026-08-03.md](../../../prds/sci-execution-runtime/research/repl-display-conventions-2026-08-03.md)
divergence 8: `seon.cluster.reply/plan-sources` emitted comment-only plan
forms (trailing/pure prose), and `seon.sci.eval/one-event` threw
"Evaluation requires exactly one reader event." on ZERO events — so pure
prose in an agent reply became a FAILED receipt. Clojure's own model
(measured, `clojure.main` + prepl): a comment produces NOTHING — no event,
no nil, no output. The old `reply.clj` docstring incorrectly claimed SCI
read comment-only text as nil.

## Expected behavior

A comment-only plan form is recorded (the prose is durable input) and
produces no evaluation, no result entry, and no failure — matching the
real-REPL contract the owner ruled (2026-08-03: strict REPL fidelity;
comments are input, never output).

## Resolution

Resolved in agent-bootstrap slice 1. `seon.cluster.work` now asks the
reader whether a durable source contains an evaluable event before
claiming work. A zero-event comment form remains session input, consumes
no receipt ordinal, and cannot create a failed receipt. The
`seon.cluster.reply/plan-sources` docstring now states that contract.

The focused regression proves a comment-only first source is durable,
the next real form is claimed as ordinal 1, the run then closes, and no
receipt exists for ordinal 0. Reply planning separately proves pure
prose becomes a zero-event source, while transcript rendering proves
leading comments are visible after the namespace prompt as input.
