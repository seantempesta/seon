---
type: issue
status: resolved
severity: friction
tags: [issue, testing, database, boot, error]
---

# Contracts that require a LIVE connection are called with a released one

## Problem

Turning `seon.instrument/apply!` on for a whole JVM (it was briefly
wired into `seon.cluster/start!` while step 4 was being built) surfaced
a family of real contract violations that share one shape: a function
whose declared input requires `:seon.store/branch-connection` — which
means a LIVE Datahike connection — is called during teardown with a
connection that has already been released.

Eight of them are `seon.cluster/stop!`, nine are
`seon.cluster.store/transact!`, two are
`seon.cluster.store/release-store!`.

This is not the instrumentation being fussy. Either the caller should
not be calling with a spent connection, or the contract is wrong about
what the function accepts — and which one it is has to be decided PER
SITE, because the two answers have different consequences:

- `stop!` was decided already and fixed in the same unit: its docstring
  promises idempotence, so its second call necessarily receives a
  released connection. The instance now holds a
  `:seon.store/connection` (an object, live or not) and liveness is
  required only where work is done through it. The remaining `stop!`
  violations are its INNER calls, not its argument.
- `transact!` genuinely needs a live connection — it is about to write.
  Being called with a released one is the documented stop-during-turn
  race (`seon.cluster/disarm-loop!`: flow does not join the proc's
  thread, so a transaction already dispatched at stop time is lost
  exactly like a kill). The question is whether the LOOP should be
  refusing to start a transaction it cannot finish, which is a design
  question about proc shutdown rather than a schema question.
- `release-store!` needs the same decision as `stop!`.

## Evidence

Reproduce by calling
`(seon.instrument/apply! {:seon.config/on-core-error :panic})` at the
top of a test run, or by re-adding the `apply!` call to
`seon.cluster/stack-tower!`, then running `bin/test`. The violations
name themselves: each is a `:seon.instrument/contract-violated` error
value carrying the function, the offending schema, and the bounded
arguments.

Two further violations found the same way were deliberately-malformed
test inputs and were fixed at the caller rather than deferred: a
`:claim-epoch 0` in the run model property (0 is not an epoch, so the
call asked a malformed question where a wrong one was intended) and an
empty form source in the eval property (a form source is
`[:string {:min 1}]` at the attribute, so the input is unrepresentable
by construction).

Four were genuine contract-vs-reality mismatches and are fixed:
`seon.schema/projection-delta` (a `:catn` binding two arguments to one
name, so the contract had never compiled), `seon.cluster/root-executors`
(an inlined `[:fn sym]`, which malli cannot resolve from a
`:malli/schema`, so that contract had never compiled either),
`:seon.cluster.loop/cluster` (flow adds `::flow/pid` to a proc's args
map — `flow/impl.clj:155` — and the closed shape did not admit it), and
`:seon.cluster.loop/turn-report` (an `:open` turn reports before a run
exists, so the run id cannot be required).

## Owner

`seon.cluster` (stop/release) and `seon.cluster.loop` (the shutdown
race), with `seon.cluster.store` as the contract owner.

## Acceptance

- Each of the three functions has a decision recorded: contract widened
  (with the reason liveness is not required) or caller fixed (with the
  reason it should not be calling).
- With `apply!` active for a whole `bin/test` run, the gate is green —
  which is the real acceptance, because it means no contract in the
  tree is lying about its own live inputs.
- The decision on the loop's shutdown race is written down wherever it
  lands: today it is documented as a kill row in
  `seon.cluster/disarm-loop!`, and that is honest but not yet proven by
  a test.

Resolved by the orderly-stop sequence `852ef9759`, `af200d5d6`, and
`2e372027d`: current disarm joins the active agent and both cluster-graph procs
before releasing the branch connection.
