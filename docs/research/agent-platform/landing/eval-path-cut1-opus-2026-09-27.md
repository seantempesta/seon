---
type: landing
lane: one-commit-a-opus (eval-path cut 1 = root-cause audit M4, review §7 row 1)
date: 2026-09-27
status: implemented, static checks green, observation proven at the REPL; tests for root; RESET NEEDED
---

# Eval-path cut 1: the reply carries the settlement's own value

## Design

**What existed.**
- `seon.turn/resume-turn` settled a run and discarded the settlement's transaction report. The pass
  reported only `{outcome, forms-run, refusal}`.
- `seon.cluster.agent/evaluate-source!` answered `{:seon.turn/id}`.
- The MCP bridge (`script/seon/dev/mcp.clj` `run-form`) then read `@connection` to rebuild the receipts
  and responses. It rebuilt the run's outcome from a later observation: a value that could hold later
  commits, or not yet hold the run (task `branch-eval-answers-no-events`).
- `evaluate-source!` also re-read the connection to tell a refused, unopened run apart.

**The composition.** The settlement's `:db-after` is the run's basis. It travels as a value down one chain:
1. The producer, `resume-turn`, puts `:seon.turn.loop/basis` on every exit. Success and refused
   settlements take it from `settle-batch!`'s outcome; phase failures take it from `settle!`'s outcome
   (`settled`).
2. The pass, `step`, answers the reply port with it. It is removed from the Flow report, an observation
   channel that should not hold database values.
3. The entrance, `evaluate-source!`, returns `{:seon.turn/id :seon.db/db basis}`, and decides the
   refused-unopened case on that basis, not on the connection.
4. The view, `seon.cluster.agent/run-observation`, reads the run's receipts and responses on that value.
   The bridge's `run-form` calls it and never dereferences the connection.

Evaluation semantics are unchanged.

**What disappears.** The bridge's connection read and its inline history/receipt reconstruction
(~14 lines move into `run-observation`, callable from tests). `evaluate-source!`'s connection read,
including its released-connection special case.

**Cost.** None added. A reference to a value the settlement already produced.

**Not in this cut.**
- B (reply-when-applied) is the owner's decision after this lands.
- `host_test/a-refused-settlement…` is a different seam: the batch refusal keeps each form's shown
  value, while the test expects the deleting form's own refusal. That belongs to the outcome
  classification cut (row 2).

## Evidence

- On default (b32060df4, branch `oc-cut1`), the committed `run-observation` read a real two-form run.
  Ordinal 0 showed `"2"`. Ordinal 1 was the thrown `own` error. Both carried the response text the agent's
  context reads.
- Static checks: `turn.clj` and `cluster/agent.clj` compile in throwaway namespaces;
  `seon.contracts-compile-test/check` over turn, agent and evaluate_source_test found nothing; both
  schema files read; clj-kondo (no cache) found no errors.
- Regressions, in `seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent`:
  - a successful value, a returned error value and a refused settlement (surviving referrers) each give
    the right run/ordinal observation on the reply's own value;
  - a later commit moves the connection but not the reply's basis, and the observation stays the same.
- Producer-to-MCP propagation runs through the real `run-form` in
  `seon.dev.mcp-bridge-test/a-lane-owns-its-branch-create-define-call-retire`:
  real `evaluate-source!`, then `run-observation`. No fabricated report.

## Root runs (after the restart)

- `seon.cluster.evaluate-source-test --include-long`
- `seon.dev.mcp-bridge-test/a-lane-owns-its-branch-create-define-call-retire`
- `seon.cluster.host-test`
- `seon.turn-test`
- `seon.turn-loop-test`
