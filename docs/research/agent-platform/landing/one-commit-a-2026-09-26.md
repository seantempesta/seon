---
type: landing
lane: one-commit-a-opus
date: 2026-09-26
status: A committed (1df3d5769), RESET NEEDED, unmeasured; B written and held uncommitted until A is measured
---

# Landing: one durable commit per form (A), then reply-when-applied (B) (2026-09-26)

**What existed.** A source form cost two durable commits: the run request (`submit-source!`,
`src/seon/cluster/agent.clj` 725–753 at 36a3714b4) written only so the agent's Flow graph could derive
the work after a payload-free wake, and the settlement (`settle-batch!`, `src/seon/turn.clj`). The
caller (`evaluate-source!`, 775–800) registered a `d/listen`, kept the run id in an atom and pulled
`closed-tx` on every report.

**The composition (A).** The run request is a value. `submit-source!` builds the run's open
(`turn/system-run-tx`, the writer's own function), its staged reply and a reply port, arms the agent
(`arm!`, idempotent) and offers the value to the armed graph's request port (`async/chan 1`: one waits,
a full port refuses busy). The turn proc takes it as an external in-port beside the episode conn
(`turn-step` init arity), applies the open to its one database value with `seon.db/with` (the writer's
refusals, e.g. `agent-already-running`, answer the caller), evaluates on that value (the
`evaluate-sources` "run as its settlement will leave it" cache is seeded with it), and `settle-batch!`
commits open + evaluations + close as ONE transaction (`with-submission` prepends the open; the
run-dependent writer calls after it see the run mid-transaction, `datahike/db/transaction.cljc:1171`).
The pass answers the reply port with its report; a pass that ends without settling closes the port.

## Broken / open first

1. **Measurement pending a restart.** Default was still pid 32075 at source 36a3714b4 when this lane
   committed A; the probe `tmp/one-commit-a/probe.clj` is ready (20 trivial forms through
   `evaluate-source!` on a branch's hosted agent, p50/p95, commits at answer). Numbers: see
   "Measurements"; rows marked pending were not run.
2. **Tests not run** (load cap; root runs them). Reds root should expect and how to read them:
   - `test/seon/program_test.clj:1352` drives a submitted run by hand (`next-agent-work` + `turn/turn`
     without a graph). Under A a submission exists only as a value on the armed graph's port, so that
     parity test must let the graph run it (`turn/virtual-turn!` now awaits the settled run through
     `evaluate-source!`). Not my file: root converts it or hands it to me.
   - `test/seon/context_blocks_fixture.clj` reads the busy refusal from `virtual-turn!`'s result: that
     still works because `virtual-turn!` now answers the pass's refusal.
3. **Semantic changes, stated.**
   - Crash: a form interrupted or crashed before its settlement leaves no fact; boot recovery closes
     nothing for it; its caller's reply closes (AGENTS amended by root, 356a86cba: "Recover facts, not
     execution").
   - A source run no longer spends an episode run: its open and close share one transaction, and
     `episode-runs` counts a `:call` run only when it closed after it opened. Source runs are now
     "same-transaction system turns" in `episode-runs`' own words. The submission pass therefore skips
     `episode-exhausted!`'s budget reads.
   - Two concurrent submissions to one agent: the second waits on the port (it used to be refused
     `agent-already-running`); a third is refused busy (`:seon.error/member :seon.agent/requests`).
   - The pass no longer re-derives `more-agent-work?` after its turn: it rewakes once and the next
     pass's single derivation finds the rest or reports idle, after the caller was answered
     (task 62c45543598c).
4. **B: blob reachability permit.** `blob/with-publication!` (`src/seon/blob.clj:296`) releases its
   permit when the applied write returns, before the commit is durable. A GC that runs in that window
   could reclaim a staged blob the pending settlement references. `blob.clj` is not my file; the fix is
   to hold the permit until the durable outcome (`durable-watch!` is the seam).

## B: reply once the settlement is applied (held, not committed)

Held in the working tree so a restart at HEAD measures A alone; committed only after A's numbers, and
deleted if it is not clearly faster (owner).

- **Datahike fork** `c6c0f80d` (pushed to origin; gitlink not yet bumped in seon): `transact!` takes an
  optional `applied` port; the processing loop puts the applied report (or the invocation's failure)
  on it right after `op-fn`, before the batch commits (`writer.cljc`, ~12 lines). Committed callback,
  listeners and terminal commit failure unchanged.
- **One general writer operation** `seon.db/transact-applied!` (`src/seon/db.clj`): the same validated
  path as `transact!` (`transact-through`, `transact-call`), answering the applied report; a
  `durable-watch!` virtual thread hands a failed commit to the caller's `durable-failed!`. Only an
  applied success is watched, so a refusal at application never reads as a storage failure. Not a
  writer-wide mode: every other caller keeps `transact!` and waits for durability.
- **One caller, opt-in at the site**: `settle-batch!`'s success commit. `settlement-lost!` names the lost
  run(s) and calls `seon.fault/fault!`, which cannot store (the writer is stopped), so it runs the agent's
  stop and throws. Refusal arms (`settle!`, `settle-batch-refusal!`) stay synchronous.
- **The agent's next form sees its previous writes**: the pass keeps the last applied value in its proc
  state (`:seon.turn.loop/applied`, stripped from the Flow report and the reply) and starts from the
  newer of it and the connection's committed value. No reader plumbing elsewhere (owner).
- **Other qualifying callers (not converted)**: writes nobody needs durable before the answer, e.g.
  profile samples and evaluation read evidence refreshes. Everything with consequences (merge,
  write-back, tasks, config, schema adoption, boot, faults, branches, messages to root) stays `transact!`.
- Regression `test/seon/db_applied_write_test.clj`: an ordinary write answers after its commit; an
  applied write answers with the writer's value and a later durable write commits after it.

## Evidence

- Regressions (`test/seon/cluster/evaluate_source_test.clj`, tasks d741e6812a31 62c45543598c):
  - *a run settles in one commit*: the run's `:seon.turn/id` datom, its evaluation's `:seon.eval/shown`
    and its `closed-tx` share one transaction, and the connection advanced by exactly one commit;
  - *the graph receives the request without a listener*: `evaluate-source!` holds no `d/listen`; the
    submission is taken off the armed graph's `:seon.agent/requests` port;
  - *a pass stopped before settlement leaves no half-written run*: a `(loop [] (recur))` submission,
    taken, then `disarm!`: its reply is answered or closed, no open run remains, no evaluation lacks a
    terminal fact.
  - `test/seon/cluster/host_test.clj`: one submission waits, the next is refused busy, retirement
    answers or closes every reply.
  - `test/seon/db_applied_write_test.clj` (B): an ordinary `transact!` answers after its commit; an
    applied write answers with the writer's value and a later durable write commits after it.
- Static checks before each commit: `seon.contracts-compile-test/check` over the changed files against
  the checkout's schema resources: no findings; both schema EDN files read; each changed namespace
  compiled in a throwaway namespace in default's JVM (removed after).

## Lines

A (1df3d5769): src +182 / −106 across `turn.clj` (+103/−32) and `cluster/agent.clj` (+79/−73), schemas
+31/−2, tests +72/−18. Deleted: the submission commit + wake, `evaluate-source!`'s listener, `closed?`
pull and run-id atom, the second `more-agent-work?` derivation per pass, two unused requires
(`datahike.api`, `seon.blob`) in agent.clj. Added machinery: the request port and its reply, the
submission prefix of the settlement, the proc's request dispatch.
