---
name: clojure-testing
type: skill
status: active
description: "Test Seon with the canonical Datahike fixture, real SCI evaluation, armed contracts, bounded event waits, and reproducible properties. Use for regressions, fixture diagnosis, and gate selection."
---

# Test the running contract

The binding gate is [turn PRD §10](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md).
A lane runs bare `bin/test`, its subject's explicit namespaces, and
`bin/test --platform`. Never run `--all` or `--full` in a lane.

## Select and read the real gate

`bin/test` creates an isolated operator root and reports the suite
verdict as its exit code (`bin/test:1`). Bare selection follows
program-graph calls from changed definitions; explicit namespaces run
complete. The reachability owner is
`src/seon/test/selection.clj:134`, following call and test-subject edges.

Run the selected namespaces together. Read counts and the complete
failure category: worker exchange, launch, confirmation, and test
assertion failures make different claims. A green exit with zero tests
is not a proof of the subject. Do not attribute a failure to another
lane until its exact boundary is established.

If concurrent edits block the gate, preserve them. Follow the assignment's
specific worktree or stop rule. Never resume or alter another lane's
session. Commit only the owned files; end owned background shells before
reporting. These are §10 operational rules, not runner implementation claims.

## Canonical fixture

`seon.test-support/with-database` ordinarily opens an isolated branch
of the canonical in-memory base. It does not rebuild the whole source
population per call. Each branch has its own connection, datoms, schema
evolution, and history (`test/seon/test_support.clj:553`, `:587`).

Use `:seon.test-support/extra-schema` only for synthetic declarations
whose installation is part of the subject. Store-global tests may request
`:seon.test-support/fresh-store?`; the separate physical-store path
lives at `test/seon/test_support.clj:532`.

Hand the projection and environment explicitly as production does.
`run-database-body` supplies the fixture's projection state
(`test/seon/test_support.clj:509`). Never create a small schema roster
or mocked SCI context that misrepresents the production boundary.

Tests own no process-global mutation. Use an isolated database per
mutating property trial; pure trials may share an immutable database
value. A fixture's successful population does not prove live boot,
adoption, or browser behavior.

## Events and refusals

Use `seon.test-support/await-event!` for a channel, latch, or future.
It uses the declared event backstop and throws evidence naming a missing
event (`test/seon/test_support.clj:361`). Wait for the actual required
terminal fact or completion, never quiescence or a tuned sleep.

`seon.test-support/refusal-data` returns flat errors or deepest
exception data, distinguishing committed and unknown results
(`test/seon/test_support.clj:463`). Assert the specific refusal and
independently verify the database did not change. Checking only a throw
does not establish atomic refusal.

## Properties and instrumentation

Derive inputs from fixed seeds. Do not read wall time or mint random
values inside a property body whose replay depends on that seed.
A mutating trial gets its own fixture; the invariant checker observes
written facts independently of the operation's return.

Use `seon.test-support/assert-check!` to retain full shrink evidence
and assert an actual true result
(`test/seon/test_support.clj:479`).
Generator construction, generated-value validity, and meaningful domain
coverage are separate proofs. Malli overrides do not validate their own
output (`reference-code/malli/src/malli/generator.cljc:468`).

Run under the same contracts the cluster arms. Re-evaluating a Var
strips its wrapper; the instrumentation owner documents re-arming with
the supplied projection at `src/seon/instrument.clj:685`.
Do not weaken contracts to make a stale fixture pass.

## Turn and context regressions — target

PRD §12–§15 requires virtual replies through the ordinary per-agent
proc, without paid model calls. Prove the behavior rather than the
deleted implementation:

- Three transactions per turn; source stored before execution;
  interrupted work closes at boot and never replays.
- One persistent SCI context per agent; an atom retains identity across
  turns, private values stay out of other agents and the base, and a new
  third agent sees neither agent's private layer.
- Installed program changes reach existing contexts as base diffs.
- System opening and changed reads append evaluations. The previous
  prompt is a byte-identical prefix, including after restart from shown
  text.
- Generated and agent-written reads refresh from latest evidence and
  since changes, including empty reads and retractions. Unchanged reads
  do not append; writes and effects never rerun.
- Restart loses actual objects while inspection retains shown text.
  Compaction wipes evaluations and regenerates the opening.
- History uses the evaluation schema's pair through the walk; no second
  formatter or clipping pass rewrites shown text.

Keep one regression per failure class. Test identity derivation through
the existing `seon.id/evaluation` owner (`src/seon/id.clj:49`);
do not hand-build a second id scheme in the fixture.
