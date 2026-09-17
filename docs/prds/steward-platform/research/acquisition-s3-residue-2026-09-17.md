---
type: research
status: in progress
created: 2026-09-17
tags: [sci, acquisition, test, retraction]
---

# S3 cold-gate residue

Continuation of approved `684f185f8`; AGENTS documentation landed in
`e706884cd`. Evidence: `tmp/orchestrator/gate-results/batch-117.log`,
`batch-118.log`, retained cold root `tmp/test-runs/run.Lqwm0h`.

The original lane read AGENTS sections 0–7, the program-facts runtime PRD
and the REPL-native retraction/refactoring research end to end. This
continuation reread the owning declaration, read-evidence and turn-bound
seams, the applicable skills, current git status and the last four hours of
history before changing the previously reviewed implementation.

The new default PID observed through `bin/seon status` is 94566. Adoption
was still in progress at entry; no live evaluation has been made yet.
The lane does not start, stop, refork, or adopt default.

## Findings and governing rulings

- `turn/row-tx` deletes by exact replacement with an identity-only row.
  The final-report validator correctly refuses its missing admission.
  Program-facts PRD section 1f G1–G3 supersedes ruling 47: delete with
  `retractEntity`, leave history to temporal queries, and remove tombstone
  expectations. The writer and three named regression expectations are
  changed together. The proposed retirement note is not the binding PRD.
- `episode-runs` counts pre-provider closed ordinary calls only for issue
  agents. An ordinary prompt refusal therefore cannot reach its turn bound.
  The turn PRD sections 1a/3 bound turns after an outside wake, including
  failures before a reply; the unanswered wake remains unanswered. Count
  closed ordinary calls for both agent classes, excluding same-transaction
  system turns and generated openings as before.
- The install-gate diagnostic is recorded through `error/recording` and
  `commit-call`; the exact message is `:seon.error.occurrence/message`,
  reached through the fault's occurrence ref. The regression queried the
  superseded top-level message. Section 15 retains shown refusal text on
  the started evaluation; the assertion now reads its typed refusal rather
  than expecting absent shown text. No production error behavior is changed.

- Documentation consulted `program/overrides` even for a current core row.
  That query includes history, whose revision is deliberately uncacheable in
  `seon.db`. Consequently unrelated writes marked ordinary documentation
  changed. The documentation selector now carries admission provenance and
  consults the existing override query only for agent admission. The rereads
  expectation remains unchanged: an unrelated order amount cannot change
  core function documentation. This follows values-carry-their-world and
  the turn PRD's read-evidence rule; it does not weaken temporal evidence.

On resumption, evaluator failure-text and guardrails edits had landed;
`src/seon/sci/eval.clj` was clean and available. Adoption-margin held
`src/seon/program.cljc` by assignment, so the documentation repair avoided it.
After adoption-margin landed `be9c90e2f`, a fresh diff showed only this lane's
pre-park `overridden?` draft there. That unused draft was removed explicitly;
the file now equals HEAD and is excluded from the commit. Operator, state, fresh_operator,
cluster, source and schema edits belong to other lanes and are excluded.

## Iteration evidence

`tmp/s3-residue-baseline.log` used HEAD `19251d646` plus selected paths before
the corrections. It reproduced the prompt-refusal terminal timeout and all
three install-gate assertions. The unchanged wake-routing property passed
12 trials (seed 2026072819) in 95.912 seconds. The process was interrupted
after the agent namespace; it provides no complete suite tally.

`tmp/s3-residue-corrected.log` uses HEAD `a5516ca62` plus the owned paths and
runs the agent, cluster turn and turn namespaces.
The prompt-refusal and install-gate cases passed; unchanged wake routing
passed its 12 trials in 67.286 seconds. Its cold timeout is not reproduced
by these two canonical runs. The observation boundary is the agent routing
fixture's terminal wait (`agent_test.clj:271`, `test_support.clj` bounded
event wait), not demonstrated acquisition failure. No bound or property
expectation was relaxed.

Completed first corrected run: **111 tests, 1,013 assertions, 2 failures,
0 errors**. All requested agent and deletion cases passed. One additional
test still expected a tombstone (`ns-unmap-retracts-the-owned-function-after-the-terminal-commit`);
it now follows the same G1 ruling, and the unused tombstone helper is removed.
The other failure is the existing timing issue below.

The wider run also re-observed the known installation performance issue:
`delimiter-repair-is-span-local-and-precedes-intent` measured 472.133 ms at
`seon.turn/gate-function-install`, versus its unchanged 300 ms assertion.
Its semantic checks passed. This is recorded under the existing
[bookkeeping issue](../../../seon/issues/turn-bookkeeping-exceeds-recorded-regression-bound.md),
whose owner is the turn installation path. No timing expectation changed.

The next fast invocation was refused before any JVM launched:
`No published program graph matches HEAD 56b8a1cd8361102db0983234d88573ca3105bd12;
orchestrator must run: bin/test --prepare-head-base`.
At that point `bin/test`, `src/seon/test/cache.clj` and
`src/seon/test/selection.clj` carried another lane's uncommitted edits.
The authorized fallback uses `git worktree add --detach tmp/s3-residue-wt HEAD`,
links the existing reference-code directory and applies only this lane's five
source/test diffs. The committed launcher there runs the same fast snapshot.
No cold gate, preparation command, foreign edit or environment override was used.

Final command (from that worktree), output `tmp/s3-residue-final-worktree.log`
in the main checkout:

```sh
timeout 2400 bin/test-fast --paths src/seon/sci/eval.clj src/seon/turn.clj \
  test/seon/cluster/agent_test.clj test/seon/cluster/turn_test.clj \
  test/seon/turn_test.clj -- seon.rereads-test seon.sci.documentation-test \
  seon.sci.eval-test seon.cluster.turn-test
```

Completed final run: **143 tests, 980 assertions, 1 failure, 0 errors**.
The failure is only the existing installation timing assertion: 427.760417 ms
versus 300 ms. The two rereads tests, nine documentation tests and 73 evaluator
tests passed, including the canonical provenance/regeneration/reversion proof.
All deletion cases and accepted-override-next-turn passed in the 59 cluster
turn tests. Together with the prior run, the requested residue cases pass;
the wake-routing cold timeout remains unreproduced across 24 fixed-seed trials,
not asserted impossible. Cold and platform proof remain owed by the orchestrator.

Final `bin/seon status` still reports PID 94566 alive, PREPL 57408 and DRIFT `-`.
That command reports process/advertisement consistency, not source adoption
convergence (`script/seon/fresh_operator.clj:2894`). It therefore does not
establish the owner's prerequisite for live observation. No MCP evaluation,
restart, reset, refork, explicit publication or adoption was performed.
Fresh-cluster regeneration after-numbers remain pending that prerequisite;
the earlier PID 33583 timings are not relabelled as fresh-cluster evidence.

The temporary worktree and its reference-code symlink are removed after the
runner exits; the main-checkout iteration logs remain at the paths above.
Source lint reported warnings but no blocking errors. Markdown hooks reported
31 existing stale gitlink references in the documentation audit, outside this
slice; no documentation-audit files were changed.

Baseline iteration uses one `timeout 2400 bin/test-fast --paths` process,
no SEON_TEST overrides, and waits for the shared slot. Cold/platform gates
remain the orchestrator's responsibility.
