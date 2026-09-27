---
type: research
status: red triage evidence; fixes in f7a664c2f and 127c52503
created: 2026-09-27
branch: refactor/agent-platform
source-pin: r6 at cd4ca3d39; probes at e999fdd1b
---
# Red triage r6 (2026-09-27)

Input: `tmp/orchestrator/tests/r6/*.out`, run at cd4ca3d39 with `--include-long`
(r5, `tmp/orchestrator/tests/r5`, ran at 441988b49 WITHOUT `--include-long`, so
every long test below ran for the first time in r6). Probes ran on default after
its restart at e999fdd1b, on isolated branches acquired through
`seon.cluster.agent/acquire-context!` and released in `finally`; fixture bodies
ran through `seon.test-support/with-database` with `seon.test/*member*` bound to
that isolated handle. No Var was redefined in default.

## Evidence by defect

### E1 — the agent opening no longer reads its inbox (89629b13c)

`seon.db-test/system-turn-renders-a-changed-read-with-value-diff` reproduced at
e999fdd1b: `turn/system-turn` for an agent with one message produces seven opening
forms — `(help)`, the namespace pull, `(seon.plan/plan {})`, settings, notes,
the runtime pull, `(seon.error/for-agent ...)` — and no inbox read, so a changed
message is never a changed read (preview: every form `:unchanged`). The walk unit
for the agent's `:seon.message/_to` concern renders `"[:seon.agent/id \"a2-reader\"]"`.
Cause: 89629b13c made `seon.cluster.message/render-inbox-ai`'s recipient-reference
branch `(pr-str reference)` (message.clj:504) and its return contract `:string`;
before, that branch returned the `(seon.db/pull '[{:seon.message/_to ...}] recipient)`
read, and `seon.render/source-producer?` reads source intent from a
`:seon.render/source` return. r5's green for this test was reuse (db-test
`reused 1`), not an execution. Also the likely cause of
`seon.turn-test/virtual-turns-use-the-proc-and-compaction-is-agent-scoped`
(changed reads lack `seon.db/pull`).

### E2 — a report's `:db-before` names a stale commit (merge base)

`seon.cluster.merge-test/a-candidate-merges-only-its-tested-green-program`:
`accept-merge!` answered `:seon.source/base-commit-id` `…6ab99577…` where the head
was `…6ab99581…`. Three writes on one isolated branch connection:

```
{:r1-after  #uuid "6ab998fb-7aa9-…" :r2-before #uuid "6ab998b8-a063-…"
 :r2-after  #uuid "6ab998fb-9f3d-…" :r3-before #uuid "6ab998b8-a063-…"}
```

Every `:db-before` names the branch point. The Datahike fork's transaction loop
recurs on the previous report's uncommitted `:db-after`
(`reference-code/datahike/src/datahike/writer.cljc:210`); the commit loop attaches
the commit id to the stored value only (`:255-272`). `@connection` is correct.
Producer: `src/seon/cluster/source.clj:1019`
`(db/commit-id (:db-before report))`; the one caller of that shape.

### E3 — a lowered `not` joined unbound variables (fixed, f7a664c2f)

`seon.db/q '[:find ?r :where [_ :seon.ns/requires ?r] (not [?t :seon.ns/name ?r])]`
threw `NullPointerException` from `datahike.query.execute/post-filter-not-joins`
at e999fdd1b; raw `d/q` answered 113 rows. `lower-clause` wrote
`(not-join [?t ?r] ...)`. Fixed to join only variables bound outside the `not`.
The panic itself — `seon.db/q` letting a dependency throw escape instead of
answering an error value — remains (task E3b).

### E4 — a green re-record after a red one is refused (vector cells)

`seon.test-failure-facts-test/recorded-reach-belongs-to-the-tested-value-and-is-replaced`
reproduced at e999fdd1b: `commit-results!` with one failure, then with zero,
answers `vector-element-swept ... :seon.db/attribute :seon.test/failures`. The
runner retracts the old failure entity (`[:db.fn/retractEntity (:db/id old)]`,
`src/seon/test/runner.clj` HEAD:1374) while the `:seon.test/failures` cell naming
it stays. Also the cause of `what-made-it-red-names-changed-functions` (no
recorded pass count).

### E5 — first population into a fresh store refuses carried-projection

`seon.fn-test/fresh-population-flattens-with-its-supplied-declarations` (long; first
run) and `seon.cluster.source-test/flat-scratch-write-refusal-retires-the-candidate`,
`incremental-publication-does-not-change-an-existing-cluster` (errored in r5 too):
`seon.fn/index!` population phase refused "This operation requires a carried
schema projection" (`seon.db/construction-projection`, db.clj:1496): the value the
writer judges holds no declaration rows and carries no construction projection
metadata. Not reproduced here (a fresh store is a platform subject); a cold
nuke/boot of HEAD should be checked by root.

### E6 — `pull-selector?` compiles the whole selector on every armed check

`seon.sci.eval-test/public-walk-is-callable-through-an-agent-sci-eval` counted 10
`compile-pull-plan` calls on the root selector with ONE acquired plan. The plan
is reused; the counted compiles are contract checks: `seon.schema/pull-selector?`
(schema.clj:1461) compiles its argument, and 18ce1b1a4 added armed
`:seon.schema/pull-selector` slots on the pull path (`vector-pull`,
`physical-selector`). Measured on default: the root selector (15,060 nodes) costs
0.68 ms per `compile-pull-plan` and 0.69 ms per `pull-selector?`;
`projection-with-pulled-form-in` answers a cache hit in 2.9-3.1 ms (the
`id/id [schema-key selector]` key digests the selector each call).

### Not reproduced at e999fdd1b

`seon.db-test/equal-declaration-content-shares-one-projection-across-values`: two
nested `with-database` branches and `d/with []` all read the identical projection
(branch read 0.012 ms). r6's JVM held 6.5-8.3 GB old gen (memory-pressure refusals
in cluster.turn-test twice, render.value-test refused); the bounded projection
cache may have evicted. Existing task: `memory-pressure-crossed-during-test-rounds`
(`resources/seon/tasks/seon.sci.kernel.edn`).
