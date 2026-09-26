---
type: landing
lane: one-commit-a-opus
date: 2026-09-26
status: measured on c029d48cb (pid 86089); do/defn contract fix in HEAD via 715c23669; RESET NEEDED for it
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

## Measured on c029d48cb (pid 86089: option 2, program change from report, shared node cache, P1)

Root probes (see below) ran on this JVM, but other lanes' tests were running in it too:
`seon.test-support/with-database` showed up in the same profile windows. Timings are in-JVM
from `evaluate-source!` entry to answer.

| probe | before | now |
|---|---|---|
| `(inc 1)`, 20 forms, branch hosted agent | 144 / 155 ms p50/p95 (8e4ac24c9) | 176 / 299, rerun 193 / 274 ms; one commit at each answer |
| a form that writes (`seon.db/transact!` of a note), 10 forms | refused: "Nothing found for entity id" | 201 / 223 ms p50/p95, 2 commits (its write + settlement), no errors |
| contracted defns on a fresh branch (first, then three) | 6,900 / 2,419 / 2,351 / 1,957 ms | 2,645 / 815 / 753 / 864 ms |

- `(inc 1)` is slower than on 8e4ac24c9 and **it is not explained**. Per form, the settlement's
  `transact-call` is now ~150 ms against 118. Option 2 removed the 10.7 ms with and added one
  `system-run-plan` (~2 ms). Five empty commits on a branch measured 56–127 ms against ~64 earlier, so
  disk contention from the concurrent test runs is the likely cause, not a code path. It needs a quiet
  re-run before a cause is named.
- Root's 448 ms first call of a new contracted function did not reproduce in isolation: 225 ms first
  refusal call, 125 and 115 ms after, most of it the settlement commit.
- **do/defn internal error** (`(do (defn smoke-inc ...) (smoke-inc "a"))`, signature cefa1c70ca31). Cause:
  `unadmitted-notice` (804440215) passed the failed form's `:seon.sci.eval/binding` maps to
  `program-value?`, whose contract required `:seon.sci.eval/intern` (+ `:seon.sci.eval/var`, which it never
  reads). The contract now declares `:seon.sci.eval/binding`. That hunk reached HEAD inside another lane's
  commit 715c23669, which committed `src/seon/sci/eval.clj` whole while it was held here. The regression is the
  existing `seon.sci.entry-gate-test/a-top-level-do-admits-each-child-before-the-next-runs`.

## HEAD does not boot (21:10Z), cause outside this lane

- Restart onto 111abe670 failed: "Source changed during analysis; clj-kondo cache entries changed again".
  This lane ran `clj-kondo --lint` on its files in the checkout at about that time. That writes the
  shared `.clj-kondo/.cache` the booting analyzer reads, so a concurrent lint is the likely cause (not
  proven). Lanes should not lint during a restart.
- Restart onto ab87961d5 failed: "Task indexing was refused. seon.program is refused: handle-mismatch"
  (`seon.issue/file-declarations`, issue.clj:597). Commit 2d047c99c (contract-cost landing) added
  `resources/seon/tasks/seon.program.edn` entry `:seon.task/id "0d8be4a76ab3"` for
  `a-defn-scans-every-projection-form-per-call`; its id is not the handle `task-handle` derives from
  (ns, name, kind). Fix: omit the `:id` (the reader assigns the handle) or write the derived one.

## Also landed, unmeasured

- **ab87961d5, task 361088de3d56:** the program-change check reads the settlement's own report
  (`install-evaluated-rows!` takes `:seon.db/transaction-report`; seven test callers pass theirs).
  Before, on 8e4ac24c9 with a fresh branch, contracted defns through `evaluate-source!` took
  **6,900 / 2,419 / 2,351 / 1,957 ms**; with-declarations ran 968 calls at 7.6 ms each on that branch
  (probe `one-commit-defn-probe-2026-09-26.clj`).
- **fc0ff40b9 (fork 67b00071), task 4bb788513862:** cause is that each Datahike connection built its own node
  cache (`persistent_set.cljc` `create-storage`), and a branch is its own connection, so every branch
  started cold. Ported upstream d2b9e525: one cache per physical store and threshold, reserved in the
  connection registry beside the shared write hooks and handed to storage under
  `datahike.index.interface/node-cache-key`. The fork's `connector_release_test` asserts two branch
  reservations share one cache.

## Option 2 landed (55b5b580f) — unmeasured until the restart

- **One mechanism at the writer.** Custody binds the run's open beside the receipt (`seon.db/*open*`);
  `stamp-receipt` puts it first in every write the evaluation makes, and the settlement carries it too.
  The new general `seon.db/absent-call` makes the writer apply it once, deciding on its own
  mid-transaction value. This covers all four sites (receipt, effect open, fault recording, context
  capture) when they write on the evaluation's custody. A write on another thread or outside custody is
  not covered.
- **The speculative open is deleted.** The pass asks the writer's decision once (`system-run-plan`, split
  out of `system-run-call`) for a refusal and the starting namespace. It folds the submission's own sources.
  A reply large enough to stage a blob opens its run in its own commit.
- **Proven on the live writer path** with `seon.db/with` on default's value: open + note write + receipt, applied
  twice, gives one run and the receipt resolves to the evaluation the open wrote.
- Regressions: `evaluate_source_test` (a form's `seon.db/transact!` names its evaluation; a form whose
  renderer throws still answers) and `settlement_receipt_provenance_test` (the first write opens the run; a
  second write and the settlement skip it). The fault-case regression asserts the answer, not a recorded
  fault: I did not find a form-level trigger of a run-attributed core fault.
- src +111/−45 (turn.clj +75/−30 from the plan split and submission fold, db.clj +27/−6).

**Next, prepared but not committed:** task 361088de3d56 (the program-change check scans history). The patch
`tmp/one-commit-a/program-change-from-report.patch` derives the changed entities from the settlement's own
report and treats a value acquired before another commit as not covered. Seven test callers of
`install-evaluated-rows!` pass no report and would stop advancing the snapshot; `test_test.clj:68`
would then read a program mismatch. Those callers need converting, and the files are not mine.
Task 4bb788513862 (a branch's first write misses the writer's caches) is not started.

## Broken / open first — STOP: A breaks writes made during a form (task c9e0cde3edb3)

Under A a source run's rows (the run and its evaluations) exist only from its settlement, but a form's own
durable writes during evaluation reference them:

| write during a form | reference | file:line |
|---|---|---|
| every write in custody (`stamp-receipt`) | `:seon.db/receipt [:seon.cluster.eval/id …]` | src/seon/sci/eval.clj:4397, src/seon/db.clj:3221 |
| a declared effect's durable open | `:seon.effect/run [:seon.turn/id …]` | src/seon/effect.clj:820 |
| a fault recorded during the form | `:seon.error/run [:seon.turn/id …]` | src/seon/error.clj:672 (also :1443, :1903) |
| a context capture | `:seon.context.capture/run [:seon.turn/id …]` | src/seon/context.clj:393 |

Each refuses "Nothing found for entity id"; a refused fault recording is a storage failure, which panics.
Only the first was reported (merge-cut1-opus); the other three are read from source, not reproduced.

Options (simplest viable constraint first):

1. **Restore the submission commit (two commits per form).** Delete A's request-as-value path. Guarantee:
   every reference resolves, as before. Cost: ~−150 lines back to 36a3714b4's shape. Sacrifice: +one
   commit per form (the settlement commit alone now measures ~118 ms in `transact-call`, so ~250 ms per
   form).
2. **Recommended: the form's first durable write carries the run's open.** Custody already stamps every
   write (`stamp-receipt`); it also prepends the pending submission's open (`system-run-tx`, which
   writes the run and its evaluation rows), wrapped in a writer call that is a no-op once the run exists;
   the settlement's open is wrapped the same way. Guarantee: every reference resolves; a form that writes
   nothing still costs one commit, a form that writes pays nothing extra (its write commits anyway).
   Cost: ~+40 lines (db.clj stamp, one writer fn, the staged reply blob published with that first write).
   Sacrifice: a form that wrote and then crashed leaves an open run, which boot recovery closes.
3. **Provenance as values at every site.** Receipt as the evaluation id string (patch ready:
   `tmp/one-commit-a/receipt-as-value.patch`, +39/−48, new `:seon.db/evaluation`), and the same for effect,
   error and capture run refs. Cost: four schema accretions and every reader's join. Sacrifice: ref
   integrity for provenance.

**Numbers on 8e4ac24c9 (pid 75849; A with the base-context fix, B deleted).** 20 trivial forms on a branch's
hosted agent: **p50 144.1 ms, p95 155.5 ms**, one commit at each answer. Per form: `transact-call` 118 ms
(the settlement commit, including writer-side transaction functions' queries: `seon.db/q` ×55 and
`with-declarations` ×196 per form), `seon.db/with` (the pass's open) 10.7 ms, `next-agent-work` 6 ms,
`fork-for-turn` 5.7 ms, `evaluate-sources` 3.9 ms.

**Owner question 2 (the with'd open).** It costs 10.7 ms of 144 per form. The plain path (evaluate on the
committed value; the settlement carries the open; the writer refuses there) needs, in the pass, the busy
check, the starting-namespace rule (`system-run-call`'s requested / previous-ending / assigned) and the
source folding that the with'd value gives from the writer's own functions. That is more code for about
9 ms, so it stays for now. Under option 2 it goes: the pass would read the run the first write created, or
build the fold from the submission.

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
