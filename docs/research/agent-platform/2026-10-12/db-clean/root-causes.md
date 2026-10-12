---
title: "Root causes of default's nine open faults before the reset"
type: research
status: lane faults-rc, 2026-10-12, at HEAD 7ef4967db
---

# Root causes of default's nine open faults before the reset (lane faults-rc, 2026-10-12)

Input: `faults-before-reset.edn` (default at b14dc2b3f, 9 open faults). Producers read at HEAD 7ef4967db (and
d903fbc3d, which touched none of them). Probes: SCI on lane faults-rc's own branch `:agent-b6da8833aa81`, JVM-mode
reads as root (read-only), and `bin/seon check` throwaway JVMs (HEAD plus the named paths). Unverified claims are
marked **unverified**.

| # | Operation (count) | Disposition |
|---|---|---|
| 1 | `seon.sci.eval/acquisition-refusal` (20) | live at HEAD; cause located; filed `seon.test/a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record` (no proof yet) |
| 2 | `seon.cluster.agent/fork-attempt!` (6) | deleted by phase M10 (the predecessor clause goes; successors fork main) |
| 3 | `seon.cluster.registry/retire-branch!` (3) | live; already filed `seon.cluster.registry/retiring-a-branch-records-connection-has-been-released`, intent revised to the traced cause (no proof yet) |
| 4 | `seon.db/transact!` from `seon.merge/merge!` (2) | **fixed** in this lane's commit: `seon.merge/squash-merge` merges only cluster entities |
| 5 | `seon.test/run` world-refusal (1) | already filed `seon.schema/registering-a-schema-refuses-a-redefined-core-contract-the-defn-admitted` (chain-4 J5; not `two-tests-never-share-a-test-world`) |
| 6 | `my.agent/retire-branch!` from `seon.sci.eval/lazy-root` (1) | an agent's own call refused by contract, recorded as Seon's; not reproduced at HEAD (same form is delivered, not recorded); blame through `lazy-root` unverified |
| 7 | `seon.db/pull` from `seon.schema/direct-reference-keys-in` (1) | live, reproduced at HEAD on the lane branch; filed `seon.schema/a-schema-naming-a-branch-predicate-breaks-the-test-step` (no proof yet) |
| 8 | `seon.error.refusal/diagnostic` (1) | **fixed** in this lane's commit: `seon.test/run` states no nil message |
| 9 | `seon.sci.eval/install-row!` (1) | not reproduced; the recorded error names no row; no task |

## 1. `seon.sci.eval/acquisition-refusal` ×20, boot 10:10:32Z

**Producer.** `seon.sci.eval/install-function-contract!` → `instrumentation-config` → `database-effective-config`,
which queries `[_ :seon.config/cluster ?c]` on the database handed to the install and refuses when there is none.
The rows were the ten functions of `seon.cluster.squash` (e.g. `attribute-tx`), twice.

**Which database.** The published-source database (a `:current-src` commit), which never holds a cluster's
configuration. It is handed to installs only when `seon.sci.eval/derive-program-context` derives a *loaded* program
(`derive-base-ctx loaded`) whose functions have no compiled Var in the JVM, so they are interpreted against `loaded`.
At b14dc2b3f the JVM had no `seon.cluster.squash` (M6, 7c3077b9e, renamed it `seon.merge`), so the loaded program
that held it was a commit published before M6, i.e. a stale adoption record.

**Where the stale record comes from.** `seon.sci.eval/loaded-program` of a database reads *that database's* adoption
record (`loaded-commit-id`: the cluster entity's `:seon.source/commit-id`). Main's record is moved at boot before any
flow starts (`seon.cluster.boot/stand-cluster-runtime!` calls `name-loaded-program!`, and every boot context carries
`:seon.sci.eval/loaded-connection`, so `loaded-source` reads main). One path at HEAD does not: `seon.test`'s
`clean-request-handle` builds a test world's source with the one-argument `(sci.eval/base-ctx candidate)`, so
`program-context` takes `(loaded-program candidate)`: the record the agent branch copied when it forked. Every
branch forked before the restart (the attempt branches of chain 3/4 were open at 10:10) therefore built its test
world on the pre-M6 commit. **Unverified:** that the 20 occurrences came through this call site rather than another
holder without `::loaded-connection`; it is the only one-arity `base-ctx` caller in `src`.

**Can it recur at HEAD after a restart over a store with agent branches?** Yes, by reading the code: any restart
onto a commit that deletes or renames a contracted function, with branches forked before it still open, refuses
each such function's contract install when one of those branches runs its test step. The M5 lifecycle deletes a
branch only once its assignment has an outcome (`seon.merge/next-actions`' `:delete-branch`), so open assignments
survive the restart. Even with nothing deleted, the world judges the branch against the old platform source, against
"a branch never changes the platform". fresh-boot.md did not see it because a nuke has no old branches and its
restarts kept one commit.

**Disposition.** Filed `seon.test/a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record`:
pass `seon.sci.eval/loaded-database` of the execution's context to `base-ctx`. Not fixed here: its proof needs a test
store whose main names a newer published commit than a branch's record, which is more than fifteen minutes.

**Root.** Before the next `start --head` that renames or deletes a contracted function, expect this refusal on
every pre-restart branch that runs a test step; today the store holds one agent branch and lane faults-rc's.

## 2. `seon.cluster.agent/fork-attempt!` ×6 (`seon.flow/exception-error`), 08:28Z

**Producer.** `seon.cluster.agent/fork-attempt!` (still at HEAD: "The attempt's predecessor branch is gone, so its
fork point cannot be read."), reached from `apply-plan!` in a `db<->flow` pass that root drove by hand from its JVM
(`user/chain3-pass!`). A successor's fork point names its predecessor's end branch (`:attempt-02110a533e26`), which
had been deleted.

**Disposition.** Deleted by phase **M10** of hybrid-done-and-merge (§5 row 1 "`fork-attempt!`'s predecessor clause",
§6 M10: successors fork main after their needs merge, C1). Until M10 it recurs whenever a successor is planned after
its predecessor's branch is deleted. No fix here.

## 3. `seon.cluster.registry/retire-branch!` ×3 ("Connection has been released."), 08:59Z

**Producer.** `datahike.api/delete-branch!` inside `retire-branch!`, called by `seon.cluster.agent/release-context!`
from `seon.test/member-result`'s `release!`, from the virtual thread `seon.test/bounded-result` starts
(`watch-exit!`) when a member's body outlives its bound.

**Cause** (traced at HEAD, not reproduced). `seon.test/run-request`'s `finally` releases the request context and then
the request's overlay store (`seon.cluster.store/release-store!`) when the request ends, but a member that outlived
the bound is released only later by its watcher, after its body exits. Its branch lives on that overlay, so the
delete meets a released connection. The request does not retain the resource its live member still owns.

**Disposition.** Already filed as `seon.cluster.registry/retiring-a-branch-records-connection-has-been-released`
(same signature 759d40be224b, 2026-10-10). Its intent asked retirement to "answer that the connection is gone",
which is a fence; revised in place to the cause: release the overlay after the watched members exit. No proof yet
(it needs a member body that outlives a short `:seon.test/check-time-limit-ms`, then awaits its exit).

## 4. `seon.db/transact!` from `seon.merge/merge!` ("unowned-entity"), 10:10:29Z — fixed

**Producer.** `seon.merge/squash-merge`, inside `merge-tx` in main's writer, for attempt `4438ded31ef2` (chain-4
attempt 3) in the first live pass of the M5/M6 lifecycle (`apply-lifecycle!` → `merge!`).

**Cause.** The merge partition is declared per entity schema (`:seon.program/partition :seon.cluster` on
`:seon.error.occurrence/occurrence`, `:seon.task/task`, `:seon.task/assignment-row`,
`:seon.task/merge-request-row`), but `squash-merge` chose its coordinates per attribute: every entity that changed
any attribute those schemas declare (`seon.program/cluster-attributes`, 78 attributes). Attempt 3's branch held a
recorded refused settlement; its occurrence carries an identity-less `:seon.db.write/attempt` component whose
`:seon.db.write.attempt/operations` child holds `:seon.instrument/actual` and `:seon.error/capped?`, which the
occurrence schema also declares. So the merge copied those two datoms of the child onto main without its owning
edge (`:seon.db.write/attempt` is not a cluster attribute), and the writer's owned-value validation refused the
whole transaction: entity 292095, value `{:seon.error/capped? false, :seon.instrument/actual "..."}`.

**Fix.** `squash-merge` keeps a coordinate only when its entity holds, on the branch or at the base, the identity of
a cluster entity schema: the cluster attributes that are identity attributes, live on default
`#{:seon.error.occurrence/id :seon.task/assignment :seon.task/id :seon.task/merge-request}`
(`seon.merge/cluster-entity?`). The occurrence merges; the refused write's evidence stays on the branch.

**Regression.** `seon.merge-test/a-refused-write-recorded-on-the-branch-merges-as-its-occurrence-alone`: red on HEAD
(check run a05413f07edb: `checked-value` refused, "Complete component validation refused: unowned-entity", the
fault's own refusal), green with the fix; the whole `seon.merge-test` namespace green (run aca794f8a4f3, 19 tests,
145 assertions, 6.9 s). Not checked here: an eid collision between branch-born entities and main's. Datahike's
`d/with` on two values of one base hands out equal eids (`[399549 399549]` on the lane branch), but the store has one
eid counter (`seon.merge-test/a-branch-born-entity-keeps-its-eid-on-main`, P1a), so real branch writes cannot collide.

**Root.** The automatic merge needs default restarted on the commit (`start --head`). Attempt branches still
holding recorded write refusals merge cleanly after it.

## 5. `seon.test/run` world-refusal ×1, 09:59Z

**Producer.** `seon.test/world-refusal` from `clean-request-handle`: the test world's program overlay (114 entries)
was refused because `seon.render.web/stop!` "has a bare nilable return".

**Cause.** Chain experiment 4's J5: on attempt `69691a73d375`'s branch `stop!` was redefined with its shipped
contract and a schema was registered after it; registration re-walks the referencing contract as agent-authored.

**Disposition.** Already filed: `seon.schema/registering-a-schema-refuses-a-redefined-core-contract-the-defn-admitted`
(its own note: no proof yet). Not `seon.test/two-tests-never-share-a-test-world`, which is a checkpoint-name
collision between two tests of one cluster name.

## 6. `my.agent/retire-branch!` contract refusal from `seon.sci.eval/lazy-root` ×1, 06:50Z

**Producer.** The instrumented input check of `my.agent/retire-branch!`: an agent called it with the keyword
`:agent-8f004cfa2806` instead of `{:seon.store/branch ...}`.

**Cause.** An agent's own mistake. It was recorded as Seon's error because the producer was attributed to the
calling frame `seon.sci.eval/lazy-root`, the trampoline that roots a lazily interpreted closure member on its first
call, rather than to the agent's form.

**Disposition.** At HEAD, `(my.agent/retire-branch! :not-a-branch)` on the lane branch is delivered (with its
`:seon.error/example`) and recorded nowhere: `seon.problems/open-faults` of main stays `[]`. Not reproduced through a
lazily rooted member. **Unverified:** that blame through `lazy-root` still records an agent mistake at HEAD; no task
until it recurs with a reproduction. Note that the MCP tool description and the repl skill say branches are managed
"by name", which invites exactly this call.

## 7. `seon.db/pull` refusal from `seon.schema/direct-reference-keys-in` ×1, 10:10:06Z

**Producer.** Root's `seon.cluster.boot/stop!` → `release-context!` → `stop-graph!` → `record-interruption!` →
`seon.turn/open-for-agent` → `seon.db/pull` on chain-4 attempt 1's branch (`ee6cfaeb5c0b`), whose projection refused
`:malli.core/invalid-schema` for the symbol `seon.render.web/executor?`.

**Cause.** Chain-4 J1: attempt 1 registered `:seon.render.web/server` with a member whose schema is a bare symbol.
**Reproduced at HEAD** on the lane branch: `(defn nope? ...)` then
`(seon.schema/register! :faults-rc.scratch/thing '[:map [:faults-rc.scratch/x faults-rc.scratch/nope?]])` is
admitted; from the next eval request on, every test step on that branch refuses `:malli.core/invalid-schema` naming
`faults-rc.scratch/nope?` (`seon.test/definition-digests` → `seon.db/q` → `carried-projection` →
`direct-reference-keys-in`), still after the schema was unregistered and the namespace removed, while reads of the
branch head answer. A symbol never defined is refused by `register!` (`:seon.schema.edn/unresolved-reference`), and
so is one bound only by a plain `def`; only a program function is admitted. The lane branch's test step stayed
broken for the rest of the session.

**Disposition.** Filed `seon.schema/a-schema-naming-a-branch-predicate-breaks-the-test-step`. A
`with-running-agent` proof (register, then defn plus deftest) stayed green in a check JVM, so the refusing
projection is the one built for the reaching platform tests' world; the proof is not written.

## 8. `seon.error.refusal/diagnostic` contract refusal from `seon.test/run` ×1, 09:54Z — fixed

**Producer.** `seon.test/run`'s entrant catch passed `:seon.error/message (ex-message failure)` to
`seon.error.refusal/diagnostic`; for an exception with no message that is a present nil, which the diagnostic's
contract refuses, so the entrant's own catch threw and the request's real failure was lost (chain-4 G11: root
retired a probe branch during a test run).

**Fix.** The catch states the message only when the exception has one; `diagnostic` already takes the root cause's
message when the observation states none, and records the exception class and chain.

**Regression.** `seon.branch-schema-check-test/a-request-that-throws-answers-an-unknown-error-with-its-cause` gains
the message-less case (`IllegalStateException` from the injected writer): red on HEAD (check run 49fe15907982, the
fault's exact refusal), green with the fix.

## 9. `seon.sci.eval/install-row!` "Unable to resolve symbol: profile/measure" ×1, 04:32Z

**Producer.** SCI analysis inside `install-row!`, from `reinterpret-reach!` in `install-evaluated-rows!`, on lane
cost-acquire's branch `:agent-95bbf985bb25`: a caller or test of a changed definition was re-interpreted in a context
namespace that lacked the alias `profile`.

**Disposition.** Not reproduced. The recorded error names neither the row nor its namespace, so the caller cannot be
found; in this session `seon.merge`'s reach (aliases `set`, `d`, `program`, ...) was re-interpreted on the lane branch
without error. No task: the evidence does not name a producer row. The recorded error omitting the row it was
installing is itself a context defect of `install-row!`'s normalization (the acquisition refusal of fault 1 names its
row; this path does not).

## What root must do

- **Restart needed (no reset):** `start --head` onto this lane's commit to load both fixes; fault 4 blocks the first
  automatic merge until then.
- Expect fault 1 on any later restart that renames or deletes a contracted function while pre-restart branches are
  open.
- Lane faults-rc's branch `:agent-b6da8833aa81` is retired at the end of this lane; its test step was broken by the
  fault-7 reproduction.
</content>
</invoke>
