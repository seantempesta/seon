---
type: landing
status: complete
lane: fix-retired-shape-tests
created: 2026-09-24
commits: [041102a76, 3791dcfd2]
---

# Lane fix-retired-shape-tests (2026-09-24)

This lane updated test callers that still used shapes src has retired. It follows the census
`docs/research/agent-platform/red-baseline-census-2026-09-24.md` (341a94f23): the Class 3 my.plan-test residue and Class 5.
Only test files changed. No src edits. Net: +80 / −69 test lines across 15 files.

## What is still broken (read first)

- **my.plan-test is still red (21 errors), and the cause is now src.** Commit 041102a76 writes both fixture agents through
  `support/agent-tx`, which calls `seon.cluster.agent/creation-tx`. The namespace-row refusal is gone. Every member now refuses
  with `missing :seon.agent/branch` for `#:seon.agent{:id "alice"}`, even though `creation-tx` includes
  `[:db.fn/call #'seon.cluster.agent/branch-call "alice"]` (agent.clj:186-196). In JVM mode against default, `branch-call`
  returns `:cluster-default`, so the transaction function answers there. The fixture branch still refuses. This is the census's
  Class 3 "agent row without `:seon.agent/branch`" (27 reds, the same producer). **Candidate regression:** on a branch acquired
  by `with-database`, validation runs before `branch-call` adds the value, or the branch's `[:config :branch]` is absent. Owner:
  src/seon/cluster/agent.clj (held by harness-s1-attempt-release).
- **seon.sci.reader-test/source-round-trips-and-spans-partition-the-tree is still red.** The failing file is
  `src/seon/schema.clj`. After 104239f33 deleted the development helpers, the file ends with an orphan
  `;;; Development Helpers` banner and no form follows it. The reader attributes that trailing comment to the last form's span.
  The test's `source == trim(slice)` invariant then fails. The fix is in schema.clj (delete the orphan banner) or a ruling on
  EOF trailers. The file is held by fix-render-pair-selection. I did not edit it.
- **Several edits are unproven** because hook publication was paused for load (see the commands below).
- **Default is contended.** Publication refused repeatedly ("source head changed", "Source changed during development
  adoption", and static-analysis refusals from another lane's `seon.flow/submit!!` removal). One test request answered
  "Node not found in storage" (address 6ab5924b…, from `seon.db/carried-projection` in `sci.eval/acquire!`). The existing issue
  is `docs/seon/issues/published-head-references-never-written-index-nodes.md`. The retry 20 s later answered normally.

## Evidence (bin/test-check default, before → after)

| namespace / test | before | after | change |
|---|---|---|---|
| seon.sci.reader-test | 6 red (5 symbols-as-strings + round-trip) | 1 red (round-trip, above) | 5 cleared |
| seon.data-shapes-test | 5 red (3 `record-attempt!` without max-tokens, evaluation-renderer, outside-message) | 0 assertion reds; 1 member error is worker-global drift of `seon.sci.eval/evaluate` from a concurrent reload (another lane) | 5 cleared |
| seon.turn-loop-test/attempt-settlement-updates-the-registered-model-gauges | error (max-tokens) | green | 1 cleared |
| seon.render.root-pull-test (as-of-revision…, unrelated-entity-change…) | 2 errors (`candidate-call-ids` arity) | green | 2 cleared |
| seon.error-result-test | 2 errors (no `:seon.error/declared-schema`) | green (hook gate run 201d3482903a, 2/2) | 2 cleared |
| seon.test.accretion-test/refused-install-feedback-is-complete-grouped-and-renderable | error (string `:seon.fn/sym`) | green (hook gate run c3a7f169eb73) | 1 cleared |
| my.plan-test | 22 errors (`my.edit/exact!` interpret, then ns row) | 21 errors (`:seon.agent/branch`, above) | ns-row cause removed; agent-branch cause remains |

That is 16 reds proven cleared. Unrun with the new code: schema-usage-guard (2 partition), error-test (1 partition),
render.call-test (1), flow-test turn-evaluation (1), problems-test (1), render-simplification broken-renderer (1),
source-reconciliation (1) and await-owner-census (1). That is 9 more expected. The partition fix was checked in the JVM:
`(schema/build-projection (assoc (schema.edn/packaged-forms) … [:map {:seon.db/attributes true :seon.program/partition :seon.data} …]))`
answers `:ok`, and without the partition it refuses. The render.call-test fix was checked in the JVM: `request-profile` with
`:seon.boot/cluster-name "absent-render-config"` answers `:seon.render/refused-member :seon.render/profile`,
`:seon.config/error-key :seon.config/cluster` and the config refusal's message.

## Retired shapes, each confirmed against the current owner

- Declaration identities are symbols. The `:seon.fn/sym` installed type is `:db.type/symbol`, `seon.sci.reader` line 293 emits
  a symbol, and the issue is `docs/seon/issues/reader-test-expects-declaration-symbols-as-strings.md` (the reader test part is
  resolved here). Files: reader_test, data_shapes (renderer-fn sym), accretion_test (gate-report), source_reconciliation_test,
  await_owner_census_test, problems_test (`:seon.instrument/fn`, a `:qualified-symbol`).
- The `:seon.ai/target` shape requires max-tokens, auth and timeout (seon.ai.edn :target). The test targets now come from the
  one assembly, `seon.ai/targets` (ai.clj:441), with the test's model substituted.
- The attempt keeps `:seon.ai/model` itself (2d1323fcd). `:seon.ai.attempt/model` no longer exists.
- `:seon.config.ai/retain-reasoning` admits only `true`, so absence means off.
- Identity-bearing entity schemas declare `:seon.program/partition` (schema.clj:1514-1528, f4c56ad12).
- `web/candidate-call-ids` takes `[calls database confirmed?]` (web.clj:1981).
- `seon.error/recording` needs `:seon.error/declared-schema` (seon.error.edn :commit-tx-request). The
  `:seon.error/fact` shape needs layer and operation.
- `seon.render/request-profile` needs the caller to name its cluster (render.clj:124, 91c9079a9).
  `config/effective` needs that cluster's row, so the flow test seeds it with `seed-cluster!`.
- `seon.render/renderer-failure` takes a complete `:seon.error/base`.
- Four queries picked any row from the inherited world: attempt by ordinal, evaluation by ordinal, message by id, and all
  attempts. They now select by the fixture's own identity: `turn/attempt-id`, `turn/receipt-identity` and the recipient edge.

No test was deleted. `accretion/gate-report` and its install gate are on the one-happy-path deletion list, but the src caller
is live (turn.clj:3258), so I fixed the shape and kept the coverage.

## Candidate regressions and out-of-scope findings (src not touched)

1. `creation-tx` / `branch-call` on fixture branches (above). This is the census Class 3 producer.
2. schema.clj ends with an orphan EOF banner that breaks the reader round-trip test (above).
3. `seon.instrument-test/a-sovereign-sci-fork-acquires-its-own-recorder`: `base-ctx` does not hold the agent's changed
   `seon.id/valid?` row (`::instrument/interpreted-original` is nil), and the fork's `:record` policy still throws. This is not
   a retired shape.
4. `seon.turn-loop-test/refused-generated-opening-captures-evidence-before-close`: `seon.db/basis-t` receives nil from
   context.clj:386. This is also in the census unclassified list.
5. Class 6 residue in files I touched: turn-loop `a-refused-phase-escalates…` sees 11 inherited `:seon.message/about`
   messages.
6. Tests in files held by harness-s1-attempt-release that use the same retired shapes. I only listed them:
   `seon.turn-test/attempt-model-identity-survives-descriptor-retraction` (hand target, turn_test.clj:57, and a query by
   ordinal) and `seon.turn-test/fault-unit-lists-agent-faults-newest-first-with-run-links` (declared-schema). The target
   script in this lane applies there unchanged: replace the two-key target with
   `(assoc (:seon.ai/primary (ai/targets (schema/handed-projection) (support/effective-config))) :seon.ai/model "…")`.
7. root_pull_test uses `with-redefs` over `db/pull` (at :390), against the law. That belongs to harness H6.

## Verification owed (root, in a quiet window, one at a time)

```
bin/test-check default --ns seon.schema-usage-guard-test
bin/test-check default --ns seon.error-test
bin/test-check default --ns seon.render.call-test
bin/test-check default --test seon.flow-test/turn-evaluation-completion-is-a-flat-diagnostic-value
bin/test-check default --test seon.problems-test/every-committed-error-fact-shape-is-projectable
bin/test-check default --test seon.render-simplification-test/broken-renderer-is-private-to-browser-and-loud-to-owner
bin/test-check default --ns seon.source-reconciliation-test
bin/test-check default --ns seon.await-owner-census-test
bin/test-check default --ns seon.data-shapes-test
bin/test-check default --ns my.plan-test
```

Expected: the named members turn green, except where they are blocked by the reds listed above (class 3 fixture rows in
schema-usage-guard, source-reconciliation and render-simplification). Timings under the contended JVM were 4–190 s per
request. The contention causes those numbers, so they measure nothing about these edits. No probe here was hot-path, so no
parent/change timing applies.

Released paths: test/my/plan_test.clj, and test/seon/{data_shapes,error_result,error,flow,problems,render_simplification,
schema_usage_guard,source_reconciliation,await_owner_census,turn_loop}_test.clj, test/seon/render/{call,root_pull}_test.clj,
test/seon/sci/reader_test.clj, test/seon/test/accretion_test.clj. No RESET NEEDED from this lane.
