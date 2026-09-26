---
type: landing
lane: one-commit-a-opus
date: 2026-09-26
status: A (1df3d5769 + fix c672188e6) and B (fcee85161) committed; RESET NEEDED onto fcee85161 to measure the fix and B
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

1. **A as first committed was a 5.5x regression.** Measured on default (pid 59671, e405f51be), probe
   `docs/research/agent-platform/landing/one-commit-probe-2026-09-26.clj` (20 trivial forms through
   `evaluate-source!` on branch `one-commit-a`'s hosted agent): **p50 856 ms, p95 1028 ms** (min 701), one
   commit at every answer (the one-commit property held). Cause, measured: `evaluate-sources` read the
   `seon.db/with` value holding the run's open; a with'd value has no program identity
   (`seon.sci.eval/program-identity` → nil), so `base-ctx` missed its cache and re-derived the SCI base on
   every form: **241 ms per derivation against 8.6 ms cached** (same JVM, same probe form); the profile
   showed `derive-base-ctx` 645 ms over 3 forms. Fix c672188e6: evaluation reads the committed value; the
   open serves only the pass's fold reads and the writer's refusal; a submitted run mints its handles as a
   written run (`:seon.turn/write?`) and binds at the one site in `evaluate-sources` (system-turn's
   post-commit bind loop deleted). **Unmeasured until the restart.**
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
4. **B's blob permit gap: fixed in fcee85161.** `seon.blob/with-publication!` holds its reachability
   permit until an applied write's pending commit (`:seon.db/durable` on the applied report) resolves;
   regression `a-blob-permit-is-held-until-an-applied-write-commits` (a sweep is not admitted while the
   commit is pending, and is once it resolves).
5. **B's size.** fcee85161 is +174/−49 across db.clj (mostly the `transact-through` extraction, a move),
   blob.clj, turn.clj and two schema files: over the ~100-line presumption. It stays only if measured
   clearly faster than A; otherwise the next slice deletes it whole (fork seam included).

## B: reply once the settlement is applied (fcee85161)

Kept only if clearly faster than A (owner). One restart measures both: the probe's `:ms` is B's answer
and `:durable-ms` is when the branch's committed value holds the run, i.e. when A would have answered on
the same JVM and code.

- **Datahike fork** `c6c0f80d` (pushed to origin; gitlink bumped in fcee85161): `transact!` takes an
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
- **The applied report carries its pending commit** as `:seon.db/durable` (declared in `seon.db.edn`).
- **The agent's next form sees its previous writes**: the pass keeps the last applied value in its proc
  state (`:seon.turn.loop/applied`, stripped from the Flow report and the reply) and starts from the
  newer of it and the connection's committed value. No reader plumbing elsewhere (owner).
- **Other qualifying callers (not converted)**: writes nobody needs durable before the answer, e.g.
  profile samples and evaluation read evidence refreshes. Everything with consequences (merge,
  write-back, tasks, config, schema adoption, boot, faults, branches, messages to root) stays `transact!`.
- Regressions `test/seon/db_applied_write_test.clj`: an ordinary write answers after its commit; an
  applied write answers with the writer's value, its `:seon.db/durable` resolves to the committed report,
  and a later durable write commits after it; the blob permit is held until the commit resolves.

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
