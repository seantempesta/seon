---
name: clojure-testing
description: "Test Seon with the canonical Datahike fixture, real SCI evaluation, armed contracts, bounded event waits, and reproducible properties. Use for regressions, fixture diagnosis, and gate selection."
---

# Test the running contract

The binding gate is [turn PRD §10](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md).
A lane gates its owned changes with
`bin/test --paths <owned files> -- <subject namespaces>` and runs
`bin/test --paths <owned files> --platform` before reporting. This is the
owner's 2026-09-08 paths-only refinement; foreign working-tree edits are not
inputs to that snapshot. Never run `--all` or `--full` in a lane.

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
evolution, and history (`test/seon/test_support.clj:616`, `:649`).

Shared base construction runs on its own daemon thread using the system
classloader and the caller's explicitly carried projection. Failed attempts
return a typed diagnostic and retry on the next request; caller interruption
does not interrupt construction. `retrying-base` and `database-base` own
this lifecycle (`test/seon/test_support.clj:349`, `:400`). The canonical
regression is `failed-base-construction-retries-without-caller-interruption`
in `test/seon/test_support_test.clj:22`. Reload preserves the successful base;
never replace it merely to rerun a test.

When the runner supplies a published base, `create-base` clones and
reidentifies its file store before connecting the private tiered backend
(`test/seon/test_support.clj:220`). Frontend-only writes do not make a
shared backend immutable: Konserve's connect-time enumeration may migrate
and delete old-format files. The simultaneous-acquisition regression in
`test/seon/test_support_test.clj:31` verifies distinct stores, isolated
writes, cleanup, and unchanged published bytes.

Use `:seon.test-support/extra-schema` only for synthetic declarations
whose installation is part of the subject. Store-global tests may request
`:seon.test-support/fresh-store?`; the separate physical-store path
lives at `test/seon/test_support.clj:595`.

Hand the projection and environment explicitly as production does.
`run-database-body` supplies the fixture's projection state
(`test/seon/test_support.clj:572`). Never create a small schema roster
or mocked SCI context that misrepresents the production boundary.

Tests own no process-global mutation. Use an isolated database per
mutating property trial; pure trials may share an immutable database
value. A fixture's successful population does not prove live boot,
adoption, or browser behavior.

## Events and refusals

Use `seon.test-support/await-event!` for a channel, latch, future, or watched reference.
It uses the declared event backstop and throws evidence naming a missing
event (`test/seon/test_support.clj:405`). Wait for the actual required
terminal fact or completion, never quiescence or a tuned sleep.
For a reference, it installs the watch before deriving the current value and
removes it on every exit. Future failures preserve the publisher's original
exception instead of hiding it behind `ExecutionException`.
The future branch cancels timed-out work before reporting the missing event.

`seon.test-support/refusal-data` returns flat errors or deepest
exception data, distinguishing committed and unknown results
(`test/seon/test_support.clj:525`). Assert the specific refusal and
independently verify the database did not change. Checking only a throw
does not establish atomic refusal.

Acquire fixture resources in `with-open` scopes using
`seon.test-support/closeable` for values with a separate release function
(`test/seon/test_support.clj`, the adapter following the instrumentation fixture).
Clojure's nested `finally` expansion owns reverse cleanup even when setup or
another cleanup fails (`reference-code/clojure/src/clj/clojure/core.clj:3854`).
The recurring test injects failures after each acquisition count and at each
cleanup (`test/seon/test_support_test.clj`,
`fixture-resources-close-through-setup-and-cleanup-failures`).

## Properties and instrumentation

Derive inputs from fixed seeds. Do not read wall time or mint random
values inside a property body whose replay depends on that seed.
A mutating trial gets its own fixture; the invariant checker observes
written facts independently of the operation's return.

Use `seon.test-support/assert-check!` to retain full shrink evidence
and require both an actual true result and a positive trial count
(`test/seon/test_support.clj:541`). Its regression retains a failing
counterexample and rejects a successful zero-trial check.
Generator construction, generated-value validity, and meaningful domain
coverage are separate proofs. Malli overrides do not validate their own
output (`reference-code/malli/src/malli/generator.cljc:468`).

Run under the same contracts the cluster arms. Re-evaluating a Var
strips its wrapper; the instrumentation owner documents re-arming with
the supplied projection at `src/seon/instrument.clj:685`.
Do not weaken contracts to make a stale fixture pass.

Tests that deliberately change instrumentation use
`seon.test-support/preserving-instrumentation-state`
(`test/seon/test_support.clj`, the fixture following `seed-cluster!`). It
restores the entering callable roots and Malli function-schema registry on
both normal and exceptional exit. Malli's registry is the private atom at
`reference-code/malli/src/malli/core.cljc:3061`; the existing context fixture
uses this same shared owner. The platform regression removes real entering
wrappers, throws, and proves those exact wrappers and schemas return. A
runner re-arm is evidence of leaked test state, not a substitute for this
cleanup.

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
