---
type: landing
lane: one-commit-a-opus
date: 2026-09-26
status: A kept (1df3d5769 + fix c672188e6); B measured faster but lost the value at the MCP entrance, deleted whole (de67fdd1a); RESET NEEDED onto de67fdd1a
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
4. **After the restart onto de67fdd1a:** A's own answer time is unmeasured on its own build; the durable
   column above (133.9 / 145.5 ms p50/p95) is where A answers on the same code. The probe re-runs it.

## B: reply once the settlement is applied — measured, then deleted (de67fdd1a)

Measured on default pid 70849 (a34078273), probe above, 20 trivial forms on branch `one-commit-a`:

| answer point | p50 ms | p95 ms |
|---|---:|---:|
| B: settlement applied | 40.0 | 46.1 |
| run durable in the branch's committed value (= where A answers, same JVM and code) | 133.9 | 145.5 |
| before this lane (two commits, perf-eval-overhead landing, in-JVM) | 155 | |

(A first probe run's durable column read 137/164 ms because the probe's own poll ran 36k queries; the
probe now queries only when the connection's value moves.)

**Why it was deleted.** Every MCP SCI evaluation answered `"seon.dev.mcp/events":[]`, e.g. `(inc 1)` on a
fresh branch and as a root run. `evaluate-source!` answered once the settlement was applied, but the
bridge (`script/seon/dev/mcp.clj` `run-form`) reads the saved response from the connection, which had not
committed yet. The agent's own observation is a reader of the committed value, so B broke the total REPL
path. Keeping it would have meant handing the applied value to every observation of the agent's own
result (the bridge, tests that read the connection after the answer, the next context), which is the
pending-value plumbing the owner ruled out. Deleted whole: `seon.db/transact-applied!`, `durable-watch!`,
`transact-through`, `:seon.db/durable`, `settlement-lost!`, the pass's applied state, the blob permit hold,
`db_applied_write_test`, and the Datahike seam (fork revert `df4bcddb`, pushed; gitlink points at it).
Regression: `evaluate_source_test` asserts `(inc 1)` answers `"2"` read from the connection at the answer.

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
- Static checks before each commit: `seon.contracts-compile-test/check` over the changed files against
  the checkout's schema resources: no findings; both schema EDN files read; each changed namespace
  compiled in a throwaway namespace in default's JVM (removed after).

## Lines

A (1df3d5769): src +182 / −106 across `turn.clj` (+103/−32) and `cluster/agent.clj` (+79/−73), schemas
+31/−2, tests +72/−18. Deleted: the submission commit + wake, `evaluate-source!`'s listener, `closed?`
pull and run-id atom, the second `more-agent-work?` derivation per pass, two unused requires
(`datahike.api`, `seon.blob`) in agent.clj. Added machinery: the request port and its reply, the
submission prefix of the settlement, the proc's request dispatch.
