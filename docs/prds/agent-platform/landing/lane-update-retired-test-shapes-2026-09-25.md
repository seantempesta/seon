---
type: landing
status: complete
lane: update-retired-test-shapes
created: 2026-09-25
---

# Lane update-retired-test-shapes: tests now read the flat shapes the current owners write

Existing state: census #3 (aab55ddd3, `docs/research/agent-platform/red-baseline-census-2026-09-25b.md`)
classes G (27) and H (25). I re-read every red from default: `(@#'seon.problems/failed-tests …)`
returned 383 entries in 314 ms, saved to `tmp/update-retired-test-shapes/reds.edn`. For each
candidate I checked the current producer before changing any test. One babashka rewrite-clj
script made all the changes: `tmp/update-retired-test-shapes/rewrite.clj`. An edit that does not
match exactly one node inside its named deftest aborts the whole run. No src changed.

## Broken or not done (first)

- **Nothing was executed.** Under the LOAD RULE I ran no test requests and loaded no namespace.
  The only checks were clj-kondo (0 errors across the 10 files) and a scripted dry-run diff. Every
  symbol below is still unverified until root reruns it.
- **The census misfiled several G/H reds.** Their current failures come from other classes, so a
  test edit would bend them:
  - `my.plan-api-test/plan-writes-use-one-request-map-and-return-the-changed-item`,
    `my.plan-api-test/item-and-plan-agree-on-state-depth-and-parent` and
    `my.program-test/repl-documentation-and-supplied-database-use-the-canonical-population`.
    The request maps already have the current shape (src/my/plan.clj:122). The refusal is
    "missing :seon.db/connection / :seon.db/db". Elision does not supply the argument, which is
    J (fix-sci-zero-arg-and-names).
  - `seon.cluster.agent-test/install-gate-failure-closes-with-a-durable-diagnostic` and the
    turn-loop phase-failed tests. `:seon.turn.loop/phase-failed` is not retired: turn.clj:3410
    still writes it and seon.turn.loop.edn:95 still declares it. The nil comes from another path,
    so these need a probe.
  - `seon.config-test/apply-converts-a-flat-reconcile-error-to-a-refusal`,
    `seon.render-simplification-test/distance-spends-…` and `…/overlapping-contracts-…`.
    `:seon.render/candidates` and `::db/dependency-data` are still nested under `:seon.error/data`
    at render.clj:343 and db.clj:175/221. Those reds are M/K, not G.
  - `seon.bootstrap-test/absent-intent-budget-refuses-loudly`. bootstrap.clj:389-396 still writes
    both members at the top level, so this needs a probe.
- **Proposed edits I did not make, because another lane holds the file:**
  - test/seon/config_test.clj is held by fix-src-singles-prompt-web-mcp-config (config manifest).
    - `shipped-initialization-admission-is-registry-derived` and
      `explicit-absence-is-a-decision-never-a-stored-value`: replace `(::config/key data)` with
      `(::config/error-key data)` (config.clj:296, :310, :487).
    - `compiler-ignores-extra-keys-and-refuses-invalid-declared-values`: replace
      `(get-in data [:seon.error/data :seon.instrument/problem-paths])` with
      `(:seon.instrument/problem-paths data)` (instrument.clj:381 merges it top-level, :399).
    - `apply-converts-…`: config.clj:720 now rethrows the transaction's flat error unchanged, so
      the three assertions become `(is (= flat-error result))` (removed by 3c0b47dfe).
  - test/seon/cluster/mcp_test.clj is held by the same lane (MCP window):
    `runtime-observation-counts-problems-without-embedding-facts` and
    `runtime-observation-shows-unavailable-problems-with-their-cause` put the keyword
    `::connection` in the instance. cluster.clj:703 (`replaced-roots`) and :685 now call
    `db/db` on it. The instance should carry no connection (each read is guarded by
    `(when connection …)`); a keyword is a retired stand-in.
  - Files held by fixture-agent-rows (A-class agent rows in the same file):
    test/seon/turn_test.clj (`settle!`, `open-turn`, `record-attempt!`,
    `fault-unit-lists-…` declared-schema), test/seon/turn_loop_test.clj (declared-schema
    escalation), test/seon/render/entity_pairs_test.clj (pair evidence shape),
    test/seon/fn_test.clj (`analyzer-edges-…` nested member) and test/seon/cluster/turn_test.clj.
  - Files owned by src lanes: test/seon/db_test.clj, test/seon/schema_test.clj,
    test/seon/call_preparation_test.clj and test/seon/render/web_test.clj.
- **Left for probes:**
  - test/my/note_test.clj `forget-…`: note.clj:220 writes `:my.note/not-found` as the id, not
    `true`, and writes no `:seon.error/offending`. Its fixture first fails with C
    (`:seon.message/to`), so I could not confirm the shape the writer returns.
  - `seon.error-test/error-declared-schemas-persist-…`, `seon.flow-test/fault-committer-…` and
    `seon.cluster.listener-failure-test/…`: none has a verified retired member.

## Changed (10 files, +29 −28)

Files first (all mine, committed with `--only`):
- test/my/plan_test.clj
- test/my/message_test.clj
- test/seon/issue_test.clj
- test/seon/render/page_review_test.clj
- test/seon/reset_edges_test.clj
- test/seon/shell/jvm_test.clj
- test/seon/schedule_test.clj
- test/seon/cluster/wake_test.clj
- test/seon/turn_boot_test.clj
- test/seon/cluster_test.clj

G. Each test now reads the flat member its owner writes. Each item names the owner, then the test.
- plan.clj:899, :911, :986, :1004, :1023 and :1213 (86c3a73d4). The boolean kinds became
  distinguishing values: `unowned-item-id`, `unusable-current-id`,
  `duplicate-sibling-position`, `duplicate-item-id` and `cycle-item-id`.
  - `my.plan-test/compact-reads-and-writes-preserve-ownership-and-dependencies`
  - `my.plan-test/whole-tree-reconciliation-refuses-incoherent-documents`
- cluster/message.clj:216-219 (f8861b66e). `delivery` writes `:seon.message/unknown-recipient`
  with the recipient id and names itself as operation.
  - `my.message-test/send-preserves-routing-until-turn-settlement`
- issue.clj:1095-1101 writes no `:seon.error/member`; the flat `:seon.error/expected` is asserted.
  - `seon.issue-test/issue-worker-creation-is-atomic`
- eval.clj:35-41 writes a flat `:seon.error/member` and `:seon.agent/no-such-agent`.
  - `seon.render.page-review-test/turn-headers-and-all-evaluations-have-one-place-each`
- db.clj:4040-4050 puts `:seon.program/referrers` at the top level (c94e88a40).
  - `seon.reset-edges-test/named-edges-refuse-deletion-until-the-final-callers-are-repaired`
  - `seon.reset-edges-test/removing-a-name-from-a-surviving-entity-refuses-its-callers`
  - `seon.reset-edges-test/namespace-and-schema-retractions-return-every-surviving-value-referrer`
  - `seon.reset-edges-test/sci-deletion-is-a-flat-refusal-with-no-committed-prefix`
  - `seon.reset-edges-test/a-declared-capability-handler-is-a-live-symbol-obligation`
- await.clj:40-43 moves the requested member to `:seon.await/requested-member`.
  - `seon.shell.jvm-test/capture-task-completion-is-bounded-and-interrupts-only-that-task`
- instrument.clj:395 names the function `:seon.instrument/fn`; `contract-violated` is retired.
  - `seon.schedule-test/firing-requires-the-callers-cluster-execution-context`

H. Each test now uses the current contract.
- `:seon.error/declared-schema` is required at `seon.error/recording` and `commit-tx`
  (error.clj:510-521, a86e93e21/373820ffd). The chosen schemas were probed read-only on default:
  `seon.error/recording` and `commit-tx` prepared their tx-data without refusing.
  - `seon.reset-edges-test/recording-a-fault-does-not-mint-a-program-identity` uses
    `:seon.error/normalization-error`.
  - `seon.cluster.wake-test/a-fault-wakes-the-steward-of-the-failing-functions-namespace` uses
    `:seon.error/normalization-error`. Its instrumentation evidence moved from `:seon.error/data`
    to the top level, where `contract-violation-data` reads it (error.clj:376-381). The probe
    showed the fact then carries `:seon.instrument/fn my.agents.agent-a/broken`; the old nested
    source produced a fact without it.
  - `seon.turn-boot-test/root-opening-with-diagnostic-errors-does-not-park` uses
    `:seon.fn/namespace-unresolvable-error` (seon.fn.edn:6).
- `seon.cluster/commit-fault!` moved unchanged to `seon.fault/record!` (83bac0e78, fault.clj:33).
  The subject survives, so the test was updated, not deleted.
  - `seon.cluster-test/a-core-fault-commits-with-the-dial-its-cluster-config-carries`

## Root runs (serially, quiet window)

```
bin/test-check default --ns my.plan-test
bin/test-check default --test my.message-test/send-preserves-routing-until-turn-settlement
bin/test-check default --test seon.issue-test/issue-worker-creation-is-atomic
bin/test-check default --test seon.render.page-review-test/turn-headers-and-all-evaluations-have-one-place-each
bin/test-check default --ns seon.reset-edges-test
bin/test-check default --test seon.shell.jvm-test/capture-task-completion-is-bounded-and-interrupts-only-that-task
bin/test-check default --test seon.schedule-test/firing-requires-the-callers-cluster-execution-context
bin/test-check default --test seon.cluster.wake-test/a-fault-wakes-the-steward-of-the-failing-functions-namespace
bin/test-check default --test seon.turn-boot-test/root-opening-with-diagnostic-errors-does-not-park
bin/test-check default --test seon.cluster-test/a-core-fault-commits-with-the-dial-its-cluster-config-carries
```

Some of these tests also carry reds from other classes and may stay red after this change:
page-review's Turns (0) fixture and issue-worker's later branch writes.

## Numbers

- Tests: +29 −28 lines (net +1). Source: 0.
- Read-only probes: `failed-tests` read 314 ms (the dump took 1,994 ms, mostly pprint to disk).
  The three `recording`/`commit-tx` probes took 9–11 ms each.
- RESET NEEDED: no.
