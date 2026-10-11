---
title: "Central round: the compiled full tier on HEAD cd59c9fa3, 2026-10-12"
type: research
status: evidence
created: 2026-10-12
---

# Central round, 2026-10-12

Lane `central-round`. Question: what is red on HEAD (cd59c9fa3: R1 slices 1-5, prove-to-model, the retire and loose-end
commits, H2-H5, P0, P1, P1a, S3) that was green at c53c9306e, the last full compiled tier
([revert-after.edn](../2026-10-11/revert-after.edn))?

**Answer: 125 tests green at c53c9306e are red alone on a fresh JVM at HEAD, in nine causes (nine tasks filed). 2 more
are bulk-run noise, 25 were deleted or renamed by intended changes, 133 are newly green.** The two largest causes
account for 85 of the 125: a write to a store with no declaration rows is refused for want of a projection (48), and
every defn's automatic test step errors on a nil database (37, seen live on default too).

## Procedure

- Isolated root `tmp/test-runs/central-round`, `bin/seon --root ... nuke --force` from the committed archive of
  cd59c9fa3 (ready 40.1 s, 62.6 s total), then `start`. Never default's JVM.
- The same request lane revert-verify made: `script/seon/interpreted_census.clj`'s batch form (AGENT root, the cluster's
  own execution, `seon.test/run` `:named`, `:seon.test/rerun? true`, declared-long included, 240 s check limit),
  25 symbols a batch. The 2,836 symbols are every `:seon.test/sym` in the store (the committed driver reads a list; the
  list came from one Datalog query on the booted root). The driver script is not committed: it only adds a relaunch
  on refusal and is session-local.
- Pass 1: 114 batches, 2,094 s (35 min), one JVM, no memory-pressure refusal in the 103 batches that ran. Batches
  103-113 (261 tests) were refused (see Findings), rerun in passes 2-4 on fresh JVMs (about 12 min).
- Every test green at c53c9306e and not green in the bulk run (127) was rerun one per request on a fresh nuked root:
  1,247 s for 127 requests, plus 243 s for the 74 a hung member had blocked.
- `bin/seon check --at REV` with 18 representative tests to bisect: only HEAD, 2b49056a8 and 990a441cd boot under
  HEAD's operator, and all three are red on all 18 (run 0f113d3b0445 at HEAD). c53c9306e, 2e06e5d38, 3ebf4a6ce,
  a7195675a, d281616fd, dc5e1d348 and 241b28434 do not boot (`seon.db/q ... got a vector` from
  `seon.sci.eval/interpret-source-namespaces!`; the baseline fails the same way, so it is the harness: HEAD's
  operator and Datahike pin against older code). So the cause of each class lies in (c53c9306e, 990a441cd] and
  is not narrowed to one commit by bisection; the suspects below come from reading the failure and `git log -S`.

## Counts

| | c53c9306e | cd59c9fa3 |
|---|---|---|
| tests | 2,776 | 2,836 |
| green | 2,365 | 2,346 |
| red | 232 | 246 |
| error | 106 | 238 |
| unknown | 67 | 0 |
| excluded (fixtures) | 6 | 6 |
| wall | 61.6 min | 35 min bulk, 20 min reruns, 21 min alone |

Transitions (before to after): green to green 2,213; green to red 35; green to error 92 (127 losses, 2 green alone);
red to red 194; red to error 20; red to green 16; error to error 97; error to green 2; unknown to green 33, to red 9, to
error 23. Memory: no refusal on the one JVM across 2,575 tests; old generation was not sampled.

## Losses: 127 green at c53c9306e, not green now

| Class | Count |
|---|---|
| deleted or renamed with an intended change (listed below, commit cited) | 25 (they are not in the 127: absent now) |
| intentionally red proof | 0 |
| bulk-run noise: red in the bulk run, green alone | 2 |
| real regression: red alone on a fresh JVM | 125 |

Intentionally red proofs: none of the 125 is the tagged proof of a task that is red on purpose; the two proofs the
revert round filed (`seon.context-test/the-outcome-recorded-at-the-end-equals-the-one-the-branch-shows`,
`seon.render.web-context-test/unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot`) are, respectively,
still red (2 pass 2 fail, task `seon.turn/a-landing-acquires-roots-world-when-root-has-no-branch-row`) and green now
(11 pass, so its web regression is gone).

### The nine causes

| Class | Tests | Symptom | Suspect commits (not bisected) | Task |
|---|---|---|---|---|
| C1 | 48 | `Fixture write was refused at the write: This operation requires a carried schema projection.` on a raw store | test-member world cut 4347f6cce, 1d2d57a62, b53cbeb38, a74fd3031; Datahike bumps c566e0401, d281616fd | `seon.db/a-write-to-a-store-without-declaration-rows-takes-the-projection-its-caller-hands` |
| C2 | 37 | a defn's test step answers `:seon.turn.loop/test-error`: `seon.db/q` gets a nil database in `seon.sci.eval/interpret-source-namespaces!`; `seon.test-support/reply` then cannot read the elided print (`{…2}`) | bdf7411b5 (`derive-overlay-ctx` sets `::custody {}`) with 4f81411fa (`context-database`), used by H4 3ebf4a6ce | `seon.sci.eval/a-changed-programs-overlay-reads-the-agents-database` |
| C3 | 7 | `seon.config/apply!` of an applied manifest is not converged (6-7 operations) | da356e6c8, 7d7da5865, 6989f4407, 241b28434 | `seon.config/a-reapplied-manifest-converges-with-zero-operations` |
| C4 | 11 | test runner semantics: seeds, redefined callees, reaching selection | 4347f6cce, 1d2d57a62, b53cbeb38, 24cc1db6d (may share C1/C2) | `seon.test/a-member-body-reads-the-world-its-seeds-prepared` |
| C5 | 6 | read evidence and cost accounting after an unrelated write | c566e0401, d281616fd, 0c3916000, bdf7411b5 | `seon.db/an-unrelated-entity-write-keeps-a-reads-evidence-current` |
| C6 | 1 | `Wrong number of args (3)` to `seon.operator/launch-form` (test stale against the new arity `[request port program launcher]`) | 05139fa1f | `seon.operator/the-move-test-calls-launch-form-by-its-current-arity` |
| C7 | 3 | a nil or foreign branch where a fork needs one (`registry/fork!` passes `:from` nil; `branch-database` given nil) | a7195675a (H5), 7267d54fb, c566e0401 | `seon.cluster.registry/fork-names-the-source-branch-its-callers-hold` |
| C8 | 2 | fixture-model runs grade red | consequence of C2 or R1 (2b49056a8, 57388f28b) | `seon.competency/a-launch-grades-each-run-green` |
| C9 | 10 | singletons not yet traced (world of a proc step, run terminals, namespace switch, problems, zoo, schema timing) | R1, H5 | `seon.cluster.agent/a-proc-step-runs-in-its-environments-world-again` |

Each task's proof is one tagged test (`:seon/tasks` on the representative deftest); the intent lists the others.
C4, C8 and C9 may be consequences of C1/C2: re-run them after those land.


## Findings (broken things first)

- Every defn on default now answers `:seon.turn.loop/test-error` in its test step (observed with `(in-ns 'scratch.centralround)
  (defn tiny ...)` on default at HEAD): C2. This is the live face of 37 red tests.
- `seon.test/run` refuses a whole batch that contains fixture material (`Explicit fixture material is not gate membership.`),
  so one fixture symbol in 25 loses 24 other results; the committed census driver does not filter them.
- A member that outlives the check limit (`seon.cluster.agent-test/restamp-recovery-test`,
  `seon.cluster.armed-test/booting-spends-no-model-call`) is recorded as a result whose `:seon.test/sym` is the result map
  itself (`{:seon.test.run/terminated? false ... :seon.test/sym ...}`); fed back as an identity it refuses every later
  batch of that namespace (`expected :seon.test/request (a namespaced symbol), got a map`). Same family as
  `seon.test/a-full-tier-on-one-jvm-retains-what-its-members-built`; not filed separately here.
- After the driver killed a JVM during a hung `seon.competency-oracle-test/t2-goes-red-to-green` (PREPL silent for 5 min,
  past the 240 s limit), 73 consecutive requests on the restarted JVM were refused with
  `No implementation of method: :-schema of protocol: #'datahike.db.interface/IDB found for class: clojure.lang.PersistentArrayMap`;
  the next fresh JVM answered. Not reproduced; recorded as evidence.
- `bin/seon check --at REV` cannot test a commit older than 990a441cd under HEAD's operator (above): the bisect tool is
  unusable across the H2/P1a Datahike pin change.

Limits: causes are attributed from failures and history, not bisected; the 2 noise tests were checked alone once;
classes C4, C8, C9 were not separated from C1/C2; memory was not sampled; the census driver script is not committed.

## Appendix

Test names drop the `seon.` prefix. "alone" is the status on a fresh JVM, one test per request.

### C1 (48): task `seon.db/a-write-to-a-store-without-declaration-rows-takes-the-projection-its-caller-hands`

- `blob-test/binary-content-round-trips-on-both-sides-of-the-inline-threshold` (alone: error)
- `blob-test/interrupted-staging-leaves-the-store-unchanged` (alone: error)
- `blob-test/large-binary-write-and-small-chunk-have-bounded-allocation` (alone: error)
- `blob-test/published-blob-is-compressed-and-deferred` (alone: error)
- `cluster.publication-adoption-test/freshly-forked-host-already-has-its-published-source` (alone: error)
- `cluster.publication-declared-schema-test/publication-admits-optional-in-memory-offending-members` (alone: error)
- `cluster.registry-test/a-concurrent-create-wave-loses-nothing` (alone: error)
- `cluster.registry-test/a-reused-branch-name-never-takes-the-retired-forks-merged-base` (alone: error)
- `cluster.registry-test/an-idle-branch-keeps-the-commit-it-forked-from-through-collection` (alone: error)
- `cluster.registry-test/an-unwritten-cluster-retires-beside-its-unwritten-sibling` (alone: error)
- `cluster.registry-test/blob-lifetime-follows-schema-derived-history-reachability` (alone: error)
- `cluster.registry-test/boot-retires-only-branches-no-fact-claims` (alone: error)
- `cluster.registry-test/dry-run-enumerates-candidates-without-deleting` (alone: error)
- `cluster.registry-test/ensure-cluster-branches-from-the-source-commit-and-is-idempotent` (alone: error)
- `cluster.registry-test/failed-forced-refork-keeps-the-prior-branch-reachable` (alone: error)
- `cluster.registry-test/implicit-retention-requires-declared-cluster-windows` (alone: error)
- `cluster.registry-test/non-temporal-collection-marks-current-blob-references` (alone: error)
- `cluster.registry-test/refusals-name-their-rule` (alone: error)
- `cluster.registry-test/reset-returns-a-cluster-to-source-state` (alone: error)
- `cluster.registry-test/retiring-one-cluster-reclaims-only-its-own-tail` (alone: error)
- `cluster.registry-test/the-plain-collection-keeps-held-values-adopted-commits-and-concurrent-writes` (alone: error)
- `cluster.registry-test/two-clusters-write-independently` (alone: error)
- `cluster.source-nochange-test/unchanged-digest-keeps-the-published-commit-without-a-transaction` (alone: error)
- `cluster.store-test/a-branch-reads-the-index-nodes-its-store-already-holds` (alone: error)
- `cluster.store-test/a-jvm-hosts-independent-stores` (alone: error)
- `cluster.store-test/a-small-commit-reports-its-milliseconds-files-and-head` (alone: error)
- `cluster.store-test/an-error-inside-one-store-write-fails-the-commit-and-keeps-the-prior-head` (alone: error)
- `cluster.store-test/branch-connections-inherit-the-root-history-representation` (alone: error)
- `cluster.store-test/creation-never-deletes-a-complete-store-or-an-undeclared-root` (alone: error)
- `cluster.store-test/genesis-window-repairs-by-recreate` (alone: error)
- `cluster.store-test/open-write-release-reopen-preserves-data` (alone: error)
- `cluster.store-test/temporal-stores-answer-all-three-database-views` (alone: error)
- `cluster.store-test/transact-normalizes-only-jdk-integers` (alone: error)
- `db-test/a-store-without-program-schema-admits-its-writes` (alone: error)
- `schema.admission-test/source-publication-records-core-on-every-entity` (alone: error)
- `sci.eval-instrumentation-test/an-instrumented-dev-cluster-builds-an-attempt-ready-prompt` (alone: error)
- `shell.jvm-test/a-normal-exit-reaps-the-descendant-it-left-behind` (alone: error)
- `shell.jvm-test/an-evaluations-deadline-reaps-the-child-it-admitted` (alone: error)
- `shell.jvm-test/argv-stdin-and-nonzero-exit-remain-process-evidence` (alone: error)
- `shell.jvm-test/binary-output-is-byte-exact-on-both-sides-of-the-inline-ceiling` (alone: error)
- `shell.jvm-test/child-environment-is-complete-and-declared-overrides-win` (alone: error)
- `shell.jvm-test/cwd-outside-roots-refuses-before-process-start` (alone: error)
- `web.jvm-test/a-body-between-the-old-ceilings-is-a-blob` (alone: error)
- `web.jvm-test/oversized-bodies-spill-byte-exactly-through-the-blob-tier` (alone: error)
- `web.jvm-test/public-fetch-settles-text-and-binary-body-representations` (alone: error)
- `web.jvm-test/public-search-settles-one-receipt-with-provider-credits` (alone: error)
- `web.jvm-test/redirects-are-bounded-recorded-and-extracted-from-raw-bytes` (alone: error)
- `web.jvm-test/response-size-ceiling-refuses-a-chunked-body` (alone: error)

### C2 (37): task `seon.sci.eval/a-changed-programs-overlay-reads-the-agents-database`

- `my.test-check-test/a-check-naming-a-definition-of-its-own-request-runs-it-after-recording` (alone: error)
- `armed-calls-test/a-quoted-alias-is-refused-at-its-own-form-and-the-rest-stands` (alone: error)
- `branch-schema-test/a-branch-forked-before-an-adoption-records-its-test-runs` (alone: error)
- `cluster.merge-test/a-function-and-its-schema-land-as-one-unit` (alone: error)
- `cluster.turn-test/a-budget-spent-acquiring-the-first-world-leaves-the-members-pending` (alone: error)
- `cluster.turn-test/a-chat-model-request-carries-its-forms-as-assistant-messages` (alone: error)
- `cluster.turn-test/a-defn-breaking-a-reaching-test-shows-it-red-on-the-next-call` (alone: error)
- `cluster.turn-test/a-defn-reaching-a-proof-records-its-result-without-a-check-call` (alone: error)
- `cluster.turn-test/a-model-request-is-help-own-exchanges-and-the-block-last` (alone: error)
- `cluster.turn-test/a-newer-change-supersedes-queued-tests` (alone: error)
- `cluster.turn-test/a-proof-left-for-the-next-pass-shows-pending-in-the-block` (alone: error)
- `cluster.turn-test/tests-past-the-step-bound-continue-on-the-next-pass` (alone: error)
- `cluster.turn-test/the-tests-block-shows-what-the-request-executed-and-nothing-after` (alone: error)
- `competency-oracle-test/t1-goes-red-to-green` (alone: error)
- `competency-oracle-test/t2-goes-red-to-green` (alone: error)
- `competency-oracle-test/t3-goes-red-to-green` (alone: error)
- `competency-oracle-test/t4-goes-red-to-green` (alone: error)
- `competency-oracle-test/t5-goes-red-to-green` (alone: error)
- `context-test/the-block-shows-the-agents-cost-against-main` (alone: error)
- `repl-differential-p1-test/a-surviving-referrer-does-not-change-the-value` (alone: error)
- `repl-differential-p1-test/removing-a-namespace-stores-the-final-state` (alone: error)
- `repl-differential-p2-test/sci-owns-the-reader-policy` (alone: error)
- `repl-differential-test/a-require-closing-a-namespace-cycle-is-refused-and-the-agent-still-answers` (alone: error)
- `repl-differential-test/an-inherited-caller-sees-a-same-pass-redefinition` (alone: error)
- `repl-differential-test/an-inherited-test-sees-the-branch-redefinition` (alone: error)
- `repl-differential-test/an-inherited-test-sees-the-branch-redefinition-of-a-contracted-callee` (alone: error)
- `repl-differential-test/another-writer-on-the-cluster-head-neither-hides-nor-freezes-a-callee` (alone: error)
- `repl-differential-test/plain-sci-rows-answer-as-plain-sci` (alone: error)
- `request-cost-test/a-function-defined-and-called-in-one-request-is-compared-and-stored` (alone: error)
- `request-cost-test/a-reply-carries-its-request-cost` (alone: error)
- `sci.context-reads-test/a-host-thread-reads-the-current-definition` (alone: error)
- `turn-continue-test/a-done-agent-opens-no-work-turn` (alone: error)
- `turn-continue-test/a-green-end-lands-its-merge-without-root` (alone: error)
- `turn-continue-test/a-task-end-is-its-message-and-the-agents-close-in-one-transaction` (alone: error)
- `turn-continue-test/a-task-that-ends-green-sends-root-one-merge-request` (alone: error)
- `turn-test/a-replys-transactions-are-its-durable-commits` (alone: error)
- `uncontracted-warning-test/an-uncontracted-defn-reply-warns-with-the-growing-count` (alone: error)

### C3 (7): task `seon.config/a-reapplied-manifest-converges-with-zero-operations`

- `config-test/apply-compiles-once-and-round-trips-through-database-facts` (alone: red)
- `config-test/config-writer-submits-only-the-fact-difference` (alone: red)
- `config-test/converged-apply-uses-carried-projection-and-remains-exact` (alone: red)
- `config-test/initialization-entities-apply-query-and-converge-with-the-config-entity` (alone: red)
- `index-reconcile-test/indexing-an-existing-population-uses-one-writer` (alone: red)
- `reconcile-test/first-apply-writes-and-reapply-is-zero-writes` (alone: red)
- `reconcile-test/planning-pulls-only-the-provenance-managed-slice` (alone: red)

### C4 (11): task `seon.test/a-member-body-reads-the-world-its-seeds-prepared`

- `repl-differential-test/a-file-proof-aliasing-its-subject-runs-green-on-the-branch` (alone: red)
- `repl-differential-test/an-inherited-file-caller-sees-the-branch-redefinition-of-its-helper` (alone: red)
- `sci.context-reads-test/a-branch-redefinition-of-a-loaded-test-runs-under-its-own-bound` (alone: red)
- `sci.eval-test/absent-program-function-is-reported-before-namespace-lookup` (alone: red)
- `sci.lazy-member-test/a-value-definition-reaching-a-changed-function-is-its-value` (alone: red)
- `test-reaching-test/a-reaching-request-selects-what-changed-since-its-previous-run` (alone: red)
- `test.redefined-callee-test/a-red-test-reruns-green-after-its-callee-is-redefined` (alone: red)
- `test.seed-preparation-test/a-host-body-and-its-held-handle-read-the-prepared-member` (alone: red)
- `test.seed-preparation-test/a-refused-seed-prevents-body-entry` (alone: red)
- `test.seed-preparation-test/a-seed-that-redefines-its-own-test-refuses-by-name` (alone: red)
- `test.seed-preparation-test/seeds-prepare-the-member-world-in-order-before-the-body` (alone: error)

### C5 (6): task `seon.db/an-unrelated-entity-write-keeps-a-reads-evidence-current`

- `db-test/a-definition-write-keys-its-projection-by-its-own-datoms` (alone: red)
- `eval-alloc-test/a-trivial-form-makes-fewer-armed-calls-than-its-context-holds-vars` (alone: red)
- `render.root-pull-test/another-entity-schema-cannot-add-a-reverse-read-to-this-root` (alone: red)
- `render.root-pull-test/unrelated-entity-change-keeps-root-read-current` (alone: red)
- `request-cost-test/a-redefinitions-reply-accounts-for-the-window` (alone: red)
- `request-cost-test/a-reply-shows-its-live-cost-against-main-at-once` (alone: red)

### C6 (1): task `seon.operator/the-move-test-calls-launch-form-by-its-current-arity`

- `operator-move-test/a-move-states-branch-heads-before-its-boot-writes` (alone: error)

### C7 (3): task `seon.cluster.registry/fork-names-the-source-branch-its-callers-hold`

- `my.agent-test/retiring-a-branch-this-process-still-connects-is-refused-as-a-value` (alone: error)
- `mcp-entrance-test/an-agent-runs-its-own-test-on-its-own-branch` (alone: error)
- `registry-isolation-test/two-clusters-own-their-contracts-and-share-stable-host-wrappers` (alone: error)

### C8 (2): task `seon.competency/a-launch-grades-each-run-green`

- `competency-grade-test/a-fixture-model-run-grades-green` (alone: red)
- `competency-grade-test/a-launch-grades-each-run` (alone: red)

### C9 (10): task `seon.cluster.agent/a-proc-step-runs-in-its-environments-world-again`

- `cluster.agent-test/a-run-defining-with-an-alias-its-own-ns-form-required-settles` (alone: red)
- `cluster.agent-test/every-run-reaches-one-terminal-and-the-next-run-works` (alone: red)
- `cluster.host-test/a-namespace-switch-declares-nothing` (alone: red)
- `context-test/a-red-proof-shows-every-failing-assertion-with-its-claim-as-text` (alone: error)
- `flow-world-test/a-proc-step-runs-in-its-environments-world` (alone: red)
- `problems-test/an-error-two-agents-hit-is-one-problem-with-two-agents` (alone: red)
- `schema-test/an-incremental-projection-equals-its-full-build` (alone: red)
- `schema.datahike-test/canonical-population-native-parity` (alone: red)
- `sci.branch-execution-test/agent-branches-and-a-settings-write-share-the-cluster-base` (alone: error)
- `value-zoo-test/every-zoo-value-comes-back-through-both-entrants-and-the-page` (alone: red)

### Bulk-run noise (2): red in the bulk run, green alone

- `program-test/equal-schema-forms-share-one-program-attribute-derivation`
- `test-support-test/a-canonical-database-is-the-production-source-population`

## Deleted or renamed with an intended change (25)

- `my.message-test/the-surface-is-exactly-five-functions`
- `my.turn-test/the-lifecycle-surface-has-two-actions-and-its-own-presentation`
- `cluster.agent-test/a-changed-core-entity-refuses-by-name-and-every-agent-still-arms`
- `cluster.agent-test/concurrent-arm-and-release-never-deadlock`
- `cluster.agent-test/disarm-drops-the-route-before-closing-it`
- `cluster.host-test/a-message-to-a-stopped-admitted-agent-hosts-it-and-its-armer-takes-it`
- `cluster.merge-test/a-green-proposal-lands-on-main-and-disk-in-one-call`
- `cluster.merge-test/an-end-message-naming-a-merge-task-is-not-a-proposal`
- `cluster.wake-test/an-unrouted-recipient-reaches-the-armer`
- `loop-proof-test/concurrent-arms-share-one-graph`
- `sci.shown-text-test/a-task-steps-result-shows-data-instead-of-its-block-render-source`
- `task-ingestion-test/a-proof-run-that-errors-refuses-assignment-by-name`
- `task-ingestion-test/assignment-requires-a-non-green-proof`
- `task-ingestion-test/right-after-assignment-the-agents-block-shows-each-proof-failing`
- `task-ingestion-test/the-agents-own-run-of-a-proof-matches-the-assignment-run`
- `task-queue-test/the-queue-is-the-tasks-without-a-resolution`
- `task-steps-test/a-step-on-an-unknown-task-is-a-named-error`
- `task-steps-test/a-step-write-never-revises-the-task`
- `task-steps-test/done-completes-the-current-step-and-advances`
- `task-steps-test/planning-with-no-task-is-refused-for-want-of-a-proof`
- `task-steps-test/planning-writes-steps-on-the-held-task`
- `task-steps-test/reads-and-completion-with-no-task-are-named-errors`
- `task-steps-test/step-appends-and-is-current-when-none-is`
- `task-steps-test/the-task-render-shows-the-current-step`
- `turn-continue-test/an-end-proposes-once-whatever-interrupted-it`

## Newly green (133)

- New tests, green: 82.
- Not green at c53c9306e, green now: 51 ({:unknown 33, :red 16, :error 2}).

  - was red: `cluster.merge-test/a-test-never-run-on-the-target-is-not-blamed-on-the-merge`
  - was error: `dev.dependency-cache-test/discovery-compiles-a-namespace-before-the-extension-it-tail-requires`
  - was red: `render.ns-test/task-and-schema-entities-render-as-graph-nodes`
  - was red: `render.web-context-test/unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot`
  - was red: `sci.branch-execution-test/a-branch-forked-before-a-platform-change-binds-the-loaded-compiled-var`
  - was red: `sci.branch-execution-test/a-docstring-only-change-keeps-the-compiled-var`
  - was red: `sci.branch-execution-test/an-unchanged-host-bound-caller-of-a-changed-definition-runs-as-compiled`
  - was red: `sci.branch-execution-test/interpreted-callers-resolve-unchanged-names-to-compiled-vars`
  - was red: `sci.eval-test/a-branch-change-leaves-its-compiled-callers-compiled`
  - was red: `sci.eval-test/an-instrumented-multi-arity-miss-reads-like-clojure`
  - was red: `sci.eval-test/database-provenance-regenerates-the-base-and-reverts-for-retained-forks`
  - was error: `test-failure-facts-test/a-recorded-test-renders-its-sites-and-changed-dependencies`
  - was red: `test-failure-facts-test/failures-render-their-site-and-claim`
  - was red: `test-failure-facts-test/what-made-it-red-names-changed-functions`
  - was red: `test.evaluator-evidence-test/a-compiled-green-is-not-reused-by-an-interpreted-request`
  - was red: `turn-backstop-test/a-thrown-step-cancels-its-completion-backstop`
  - was red: `turn-continue-test/a-green-proof-with-reached-tests-only-unverified-or-red-on-main-ends-done`
  - was red: `turn.batch-test/a-removed-namespace-the-agent-stays-in-leaves-diff-answering`

## Round 2: HEAD e0bd37443, after the three fixes (e1c424db5 overlay nil database, c7dc2c3b6 fork source, e0bd37443 projection on the writer thread)

Same request and procedure, on a fresh isolated root (`tmp/test-runs/central-round2`, nuked from the archive of e0bd37443),
now with the committed driver `script/seon/central_round.clj` (it replaces the session-local scripts: it lists every stored
test less the two fixture namespaces, runs batches of 25 as root, waits for the cluster handle and retries a refused batch once).
2,830 tests ran (the 6 fixture symbols are not run). Bulk pass 114 batches, 63.3 min on one JVM, 5 batches at the 240 s limit,
no memory refusal; 71 unrun tests rerun in batches of 5 (14 min), 22 still unknown rerun one per request, then the 27 tests
below that stayed non-green rerun alone to confirm.

| | c53c9306e | cd59c9fa3 | e0bd37443 |
|---|---|---|---|
| tests run | 2,770 | 2,830 | 2,830 |
| green | 2,365 | 2,346 | 2,456 |
| red | 232 | 246 | 254 |
| error | 106 | 238 | 120 |
| unknown | 67 | 0 | 0 |

Against cd59c9fa3: green to green 2,342; error to green 94; red to green 20; error to red 27; error to error 117; red to red 223;
red to error 3; green to red 4 (all four are new, below).

### The 125 regressions per cause: 102 gone, 23 remain

| Cause | Before | Remaining (red alone) | Task |
|---|---|---|---|
| C1 projection on a raw store | 48 | 0 | retired (fixed by e0bd37443) |
| C2 overlay nil database | 37 | 2 (`cluster.turn-test/the-tests-block-shows-what-the-request-executed-and-nothing-after`, `tests-past-the-step-bound-continue-on-the-next-pass`; both about the tests block's `:seon.test/selection` error) | `seon.sci.eval/a-changed-programs-overlay-reads-the-agents-database`, refined, proof moved to the first |
| C3 reconcile does not converge | 7 | 7 (unchanged: 3-6 operations, a second transaction) | `seon.config/a-reapplied-manifest-converges-with-zero-operations`, refined |
| C4 test runner semantics | 11 | 6 (seeds now fail with `This write names branch ..., not the writing cluster's branch`; `(db/db)` in a host body; a NullPointerException; reaching selection; `absent-program-function` names `loaded-database`) | `seon.test/a-member-body-reads-the-world-its-seeds-prepared`, refined |
| C5 read evidence and cost | 6 | 3 (`render.root-pull-test` x2, `eval-alloc-test`: 10,043 calls against 8,434 Vars) | `seon.db/an-unrelated-entity-write-keeps-a-reads-evidence-current`, refined |
| C6 launch-form arity | 1 | 1 (unchanged) | `seon.operator/the-move-test-calls-launch-form-by-its-current-arity`, refined |
| C7 nil or foreign branch in a fork | 3 | 0 | retired (fixed by c7dc2c3b6) |
| C8 competency grade | 2 | 1 (`a-launch-grades-each-run`) | `seon.competency/a-launch-grades-each-run-green`, refined |
| C9 singletons | 10 | 3 (`flow-world-test`, `schema-test`, `schema.datahike-test`) | `seon.cluster.agent/a-proc-step-runs-in-its-environments-world-again`, refined |

C1 and C7 are retired: their tasks are deleted and their proof tags removed (`seon.cluster.store-test/open-write-release-reopen-preserves-data`,
`seon.registry-isolation-test/two-clusters-own-their-contracts-and-share-stable-host-wrappers`). The C2 proof tag moved to a test that is still red.

### New regressions (green at cd59c9fa3, red alone at e0bd37443): 4

- `cluster.squash-test/one-attempts-copy-stays-within-its-cost-bound`: 13.4 ms and 36.6 MB against 10 ms and 5 MB.
- `start-flow-benchmark-test/commit-to-paused-flow-costs`: cold branch 8.2 MB against 4 MB, cold sci 1,399 ms against 1,300 ms.
  Both are filed as `seon.db/a-write-keeps-its-cost-after-the-projection-hand-off` (suspects: the three fixes; the probes should be timed on cd59c9fa3 and each).
- `config-test/config-loads-the-packaged-population-in-a-fresh-jvm`: 9.9 s against a 5 s bound (the same test was red at c53c9306e; timing, possibly cold JVM).
- `turn.batch-test/a-removed-namespace-the-agent-stays-in-leaves-diff-answering`: the reply is an error (it was also red at c53c9306e).
  Both are added to the C9 task.

Against revert-after.edn (c53c9306e), nothing else green then is red now outside the 125 and these four. The 2 bulk-noise tests are green. Not
re-checked: tests that were red or error at c53c9306e and still are (about 340); they are not classified here.

## Round 3: HEAD 2319152dc, after the fixes for all nine causes

Same driver (`script/seon/central_round.clj`) on a fresh nuked root (`tmp/test-runs/central-round3`). 2,832 tests ran (the stored
population grew by two since round 2). Bulk pass 114 batches, 61.2 min, 5 batches at the 240 s limit, no memory refusal;
66 unrun rerun in batches of 5, 26 still unknown rerun one per request, then every test that was not green and
could be a regression (16) rerun alone.

| | c53c9306e | cd59c9fa3 | e0bd37443 | 2319152dc |
|---|---|---|---|---|
| tests run | 2,770 | 2,830 | 2,830 | 2,832 |
| green | 2,365 | 2,346 | 2,456 | 2,486 |
| red | 232 | 246 | 254 | 229 |
| error | 106 | 238 | 120 | 117 |

Against round 2: green to green 2,455; red to green 25; error to green 4; error to error 115; red to red 228; green to error 1;
red to error 1; error to red 1.

**All 125 round-1 regressions are green** (C1-C9 each 0 remaining; C4's last test and the two C2/C3 stragglers are green alone), and the
2 noise tests stay green. Against revert-after.edn nothing that was green then is red now except the one new regression below
and the config-load timing.

### New or still open

- New, green at c53c9306e and round 2, error alone: `seon.db-test/a-retained-branch-reads-with-the-jvm-builder-not-its-recorded-program`
  (`The stored definition digest of [:seon.fn/sym seon.schema/shape-entity-in] is not the one its definition produces`; `shape-entity-in`
  changed in d02a78686). Task `seon.db/a-retained-branch-fixture-writes-the-digest-its-definition-produces`.
- `seon.config-test/config-loads-the-packaged-population-in-a-fresh-jvm`: 9.2 s against 5 s (round 2: 9.9 s). Task
  `seon.config/the-packaged-population-loads-in-a-fresh-jvm-within-its-bound`.
- `seon.start-flow-benchmark-test/commit-to-paused-flow-costs` still red (world calls 85 against 50, cold sci 1,346 ms against 1,300 ms);
  it belongs to the existing task `seon.flow/cold-paused-graph-construction-keeps-its-cost-bound`.
- Red in rounds 2 and 3 and not in the 125 (tests added since c53c9306e or already red there), unchanged: seon.cluster.agent-test (3 errors),
  seon.cluster.armed-test (2), seon.cluster.agent-arming-test, seon.my.test-check-test/a-reused-check-answers-a-run-id-it-recorded,
  seon.error-test/a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas, seon.sci.acquisition-cost-test (2),
  and about 340 tests red or error at c53c9306e. These are not classified here.

### Tasks

Retired (deleted, every proof tag removed from the tests, 15 files): `seon.sci.eval/a-changed-programs-overlay-reads-the-agents-database`,
`seon.config/a-reapplied-manifest-converges-with-zero-operations`, `seon.test/a-member-body-reads-the-world-its-seeds-prepared`,
`seon.operator/the-move-test-calls-launch-form-by-its-current-arity`, `seon.competency/a-launch-grades-each-run-green`,
`seon.cluster.agent/a-proc-step-runs-in-its-environments-world-again`, `seon.db/an-unrelated-entity-write-keeps-a-reads-evidence-current`,
`seon.db/a-write-keeps-its-cost-after-the-projection-hand-off`. Filed: the two above.
