---
type: issue
status: open
severity: blocker
tags: [issue, repl, agent, sci]
---

# A comment-only plan form yields a failed receipt where a REPL shows nothing

## Evidence

[repl-display-conventions-2026-08-03.md](../../prds/sci-execution-runtime/research/repl-display-conventions-2026-08-03.md)
divergence 8: `seon.cluster.reply/plan-sources` emits comment-only plan
forms (trailing/pure prose), and `seon.sci.eval/one-event` throws
"Evaluation requires exactly one reader event." on ZERO events — so pure
prose in an agent reply becomes a FAILED receipt. Clojure's own model
(measured, `clojure.main` + prepl): a comment produces NOTHING — no event,
no nil, no output. `reply.clj:30-32`'s docstring claims "SCI reads that as
nil", which `one-event` contradicts — docstring or code is wrong; the
research reproduced `reader/read` returning `[]` on comment-only text
against the live default.

## Expected behavior

A comment-only plan form is recorded (the prose is durable input) and
produces no evaluation, no result entry, and no failure — matching the
real-REPL contract the owner ruled (2026-08-03: strict REPL fidelity;
comments are input, never output). Fix at the one owner (the eval path's
zero-event case or the plan shape), correct the lying docstring in the
same commit, and add the regression: a pure-prose reply yields a run with
zero failed receipts and the prose visible as input in the session.
