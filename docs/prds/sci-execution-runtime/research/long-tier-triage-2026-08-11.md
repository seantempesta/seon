---
type: research
status: complete
tags: [testing, performance]
---

# Long-tier triage — 2026-08-11

## Scope and method

This lane read
[`base-reuse-measurement-2026-08-11.md`](base-reuse-measurement-2026-08-11.md)
and
[`parallel-runner-measurement-2026-08-10.md`](parallel-runner-measurement-2026-08-10.md)
end to end before changing tests. It also read the complete
data-oriented-Clojure, Clojure-testing, and Datahike skills and Datahike's
selected `versioning.cljc` branch implementation.

The dependency ledger is:

- Datahike `10540578248eaa686c1f88a7fe57644ee4c9f993`, especially
  `reference-code/datahike/src/datahike/versioning.cljc`'s copy-on-write
  `branch!`;
- the existing clone/reidentify fixture in `test/seon/test_support.clj` and
  `src/seon/cluster/export.clj`;
- Malli's selected `-filter-var` instrumentation seam in
  `reference-code/malli/src/malli/instrument.clj`, already exercised by
  `test/seon/dev/fresh_operator_test.clj`; and
- the parallel runner's per-task monotonic interval in
  `src/seon/test/runner.clj`.

The census is every `worker=pool-*` task above 45 seconds in
`tmp/full-gate-base-reuse-final-2026-08-11.log`; isolated confirmations and
the unresolved serial REPL-parity task are excluded. That produces 47 tests.
Phase attribution combines the runner interval with an assertion-by-assertion
source bracket. Repeated setup classes were then falsified by replacing only
the setup and rerunning through the same parallel runner. A fixed test's
before/after delta is therefore measured setup cost; an unchanged test whose
body is entirely the named boundary is proof cost, not an inferred wait.

## Per-test verdicts

| Test | Pool | Measured phase attribution | Verdict and action |
|---|---:|---|---|
| `operator-test/public-contracts-refuse-invalid-input-and-output` | 250.580 s | Whole-registry Malli collection/instrumentation; focused 157.506 s, exact-Var instrumentation 11.964 s | **REDUCIBLE, fixed.** Instrument only `operator/start!`, the asserted boundary. |
| `sci.eval-instrumentation-test/an-instrumented-dev-cluster-completes-one-agent-turn` | 233.603 s | Published-root clone + real boot + whole-image instrumentation + settled turn | **IRREDUCIBLE-FOR-NOW.** Whole-image instrumentation is the proof; long. |
| `fresh-operator-export-test/export-verb-produces-an-openable-queryable-store` | 200.542 s | Real start JVM + export JVM + copy/reidentify + reopen/query | **IRREDUCIBLE-FOR-NOW.** Cross-process export is the proof; long. |
| `boot-test/explicit-refork-destroys-the-old-branch-and-forks-current-source` | 192.600 s | Complete publication + real start + destructive composed refork + replacement read-back | **IRREDUCIBLE-FOR-NOW.** Complete-publication/refork proof; long. |
| `boot-test/incremental-source-refresh-publishes-without-touching-existing-clusters` | 186.733 s | Complete incremental publication dominates; live old cluster and later fork are both read back | **IRREDUCIBLE-FOR-NOW.** Publication sovereignty proof; long. |
| `bootstrap-drive-test/one-fake-o1-drive-grades-on-its-ending-commit` | 171.859 s | Real graph bootstrap + objective/fork drive + ending-commit grade | **IRREDUCIBLE-FOR-NOW.** Full graph drive is the proof; long. |
| `boot-test/refork-does-not-collide-with-the-store-its-caller-already-holds` | 158.206 s | Published store + real child-JVM operator refork + collision/read-back | **IRREDUCIBLE-FOR-NOW.** Child/store ownership interaction; long. |
| `shell.jvm-test/binary-output-is-byte-exact-on-both-sides-of-the-inline-ceiling` | 149.164 s | Fresh complete file-store population; boundary proof 0.817 s after clone reuse | **REDUCIBLE, fixed.** Private clone of published base. |
| `shell.jvm-test/stdout-and-stderr-drain-concurrently-without-loss` | 146.478 s | Fresh complete file-store population; 2 MiB dual drain proof 0.969 s after fix | **REDUCIBLE, fixed.** |
| `shell.jvm-test/argv-stdin-and-nonzero-exit-remain-process-evidence` | 144.346 s | Fresh complete file-store population; argv/stdin/exit proof 0.835 s after fix | **REDUCIBLE, fixed.** |
| `shell.jvm-test/cwd-outside-roots-refuses-before-process-start` | 143.498 s | Fresh complete file-store population; refusal proof 0.712 s after fix | **REDUCIBLE, fixed.** |
| `shell.jvm-test/time-limit-reaps-the-process-tree-and-marks-the-receipt-interrupted` | 137.627 s | Fresh complete file-store population; real timeout/tree reap 2.893 s after fix | **REDUCIBLE, fixed.** |
| `concurrency-independence-test/n-agents-fold-independently-on-one-live-cluster` | 136.721 s | One real boot followed by 5- and 10-agent concurrent folds and receipt census | **IRREDUCIBLE-FOR-NOW.** Concurrency scale is the proof; namespace long. |
| `shell.jvm-test/child-environment-is-complete-and-declared-overrides-win` | 135.276 s | Fresh complete file-store population; real environment proof 0.816 s after fix | **REDUCIBLE, fixed.** |
| `background-blob-test/background-binary-results-remain-exact-across-the-inline-threshold` | 134.949 s | Fresh complete file-store population; launcher/blob proof 2.259 s after fix | **REDUCIBLE, fixed.** |
| `boot-test/operator-root-history-policy-is-creation-fixed` | 133.791 s | Two fresh physical-store policies plus real start/reopen checks | **IRREDUCIBLE-FOR-NOW.** Creation-fixed physical policy is the proof; long. |
| `web.jvm-test/oversized-bodies-spill-byte-exactly-through-the-blob-tier` | 133.223 s | Fresh complete file-store population; HTTP/blob proof 0.856 s after fix | **REDUCIBLE, fixed.** |
| `web.jvm-test/public-search-settles-one-receipt-with-provider-credits` | 132.591 s | Fresh complete file-store population; HTTP/effect receipt proof 1.647 s after fix | **REDUCIBLE, fixed.** |
| `shell.jvm-test/an-evaluations-deadline-reaps-the-child-it-admitted` | 132.330 s | Fresh complete file-store population; eval deadline/tree reap 1.713 s after fix | **REDUCIBLE, fixed.** |
| `web.jvm-test/redirects-are-bounded-recorded-and-extracted-from-raw-bytes` | 129.190 s | Fresh complete file-store population; redirect/extraction proof 0.793 s after fix | **REDUCIBLE, fixed.** |
| `web.jvm-test/response-size-ceiling-refuses-a-chunked-body` | 128.106 s | Fresh complete file-store population; chunked refusal 0.717 s after fix | **REDUCIBLE, fixed.** |
| `web.jvm-test/search-projects-the-live-serper-shape-and-blobs-the-raw-response` | 126.154 s | Fresh complete file-store population; request/projection/raw-blob proof 0.715 s after fix | **REDUCIBLE, fixed.** |
| `blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings` | 120.752 s | Complete file-store population; clone/current-src setup leaves 11.947 s of real two-sided GC/publication ordering | **REDUCIBLE, fixed.** |
| `boot-test/partial-clusters-refuse-and-fresh-clusters-are-current` | 114.810 s | Real boot + program corruption/refusal + fresh-cluster read-back | **IRREDUCIBLE-FOR-NOW.** Corrupt/live boundary is the proof; long. |
| `fresh-operator-test/populated-stopped-cluster-reopens-after-full-operator-restart` | 114.689 s | Init/fork + two real JVM identities + stop/restart + populated read-back | **IRREDUCIBLE-FOR-NOW.** Dual-JVM persistence proof; long. |
| `web.jvm-test/timeout-and-dead-host-fail-flat` | 114.636 s | Fresh complete file-store population; real timeout/dead-host proof 0.854 s after fix | **REDUCIBLE, fixed.** |
| `fresh-operator-test/init-owns-current-source-and-dormant-cluster-lifecycle` | 110.837 s | Complete publication JVM + fork/refork + real start/readiness + store read-back | **IRREDUCIBLE-FOR-NOW.** Complete operator lifecycle; long. |
| `fresh-operator-test/live-init-reloads-schema-runtime-and-moved-predicate-owners-before-admission` | 110.095 s | Init/start JVM + live damage + republication/reload + admission | **IRREDUCIBLE-FOR-NOW.** Fresh-process reload proof; long. |
| `cluster.curate-test/proof-acceptance-and-atomic-adopt-curate-one-messy-span` | 96.020 s | Complete source publication was waste; published clone leaves 18.383 s for boot + failed/clean/crash proofs + adopt | **REDUCIBLE, fixed.** |
| `fresh-operator-test/source-less-root-reset-republishes-and-reforks-default` | 94.568 s | Real destructive reset + complete republication + default refork | **IRREDUCIBLE-FOR-NOW.** Source-less recovery proof; long. |
| `print-test/p-total-generated-grammar-emits-and-readable-faces-round-trip` | 94.303 s | 200 generated validation + text/Hiccup emission + EDN read-back trials; no setup phase | **IRREDUCIBLE-FOR-NOW.** Cost is the totality proof; long. |
| `print-test/p-tee-generated-grammar-cannot-disagree` | 93.195 s | 200 generated text/Hiccup lexical-equivalence trials; no setup phase | **IRREDUCIBLE-FOR-NOW.** Cost is the tee proof; long. |
| `fresh-operator-test/fresh-process-loads-schema-before-every-operator-instrumentation` | 93.065 s | Complete init + fresh-process whole-image instrumentation + readiness | **IRREDUCIBLE-FOR-NOW.** Process/instrumentation ordering proof; long. |
| `fresh-operator-test/forced-reset-clears-an-exact-dead-process-record` | 92.850 s | Init/start JVM + SIGKILL + exact record fence + reset/republication | **IRREDUCIBLE-FOR-NOW.** Crash/recovery proof; long. |
| `fresh-operator-test/isolated-cached-boot-reports-refusal-then-reaches-readiness` | 90.430 s | Refused boot + complete init + cached real-JVM boot + phase census | **IRREDUCIBLE-FOR-NOW.** Refusal/readiness protocol proof; long. |
| `cluster.work-test/situation-totality-property` | 85.506 s | 200 fresh physical databases; canonical-base branches reduce the same 200 trials to 3.025 s | **REDUCIBLE, fixed.** |
| `sci.desk-test/desk-survives-kill-9-and-explicit-clear` | 80.213 s | Settle defs + SIGKILL writer JVM + restart/restore + explicit clear | **IRREDUCIBLE-FOR-NOW.** Crash persistence is the proof; long. |
| `cluster.program-restart-test/an-agent-definition-survives-restart-and-another-agent-calls-it` | 67.758 s | Real stop/restart + second-agent program acquisition/call | **IRREDUCIBLE-FOR-NOW.** Cross-restart program proof; namespace long. |
| `render.transcript-test/every-generated-history-is-ordered-total-and-token-bounded` | 66.666 s | 40 fresh branches, generated histories, and dual AI/HTML render/budget checks | **IRREDUCIBLE-FOR-NOW.** Cost is the generated render proof; long. |
| `boot-test/a-dead-holders-run-is-unclaimed-by-the-time-start-returns` | 60.475 s | Real boot + dead-holder wreckage + restart recovery + custody read-back | **IRREDUCIBLE-FOR-NOW.** Recovery boundary; long. |
| `dev.edit-feedback-test/split-schema-edits-run-admission-before-publication` | 59.518 s | Real hook subprocesses + schema admission before publication | **IRREDUCIBLE-FOR-NOW.** Process-boundary hook proof; long. |
| `fresh-operator-test/add-refreshes-a-genuinely-stale-wrapper-before-current-start` | 53.876 s | Real Malli collection/instrumentation refresh around stale/current start schemas | **IRREDUCIBLE-FOR-NOW.** Wrapper refresh is the proof; long. |
| `boot-test/selected-config-repairs-locked-state-before-consumers-arm` | 53.139 s | Real boot + locked-state repair + restart + pre-arm facts | **IRREDUCIBLE-FOR-NOW.** Boot ordering proof; long. |
| `boot-test/same-jvm-same-name-restart-releases-the-registered-prepl` | 49.939 s | Real start/stop/start under one registered prepl name | **IRREDUCIBLE-FOR-NOW.** Generation/release interaction; long. |
| `cluster.armed-test/an-escaped-throwable-becomes-a-fact-and-a-message` | 48.642 s | Real armed boot + injected core fault + fault fact/message delivery | **IRREDUCIBLE-FOR-NOW.** Live fault route; namespace long. |
| `boot-test/repl-is-live-after-the-boot-tower` | 47.230 s | Published clone + real boot tower + live prepl call + stop | **IRREDUCIBLE-FOR-NOW.** External prepl boundary; long. |
| `boot-test/the-tower-stands-in-one-start` | 46.987 s | Complete real boot tower + sibling acquisition + independent config reads | **IRREDUCIBLE-FOR-NOW.** Composed boot proof; long. |

## Bounded fixes

One fixture now clones/reidentifies the runner's immutable published file
store, branches from `:current-src`, and passes only the private connection to
the test. Each shell, web, and background/blob test still owns a distinct
physical blob keyspace and Datahike writer; no live connection or blob store is
shared. This removes 15 complete populations without weakening process,
socket, timeout, signal, or blob assertions.

The work totality property now uses the canonical in-memory base's private
branch per trial. The pure model still gets 200 distinct mutable database
histories; physical database creation and schema installation are no longer
repeated. The operator contract regression uses Malli's exact-Var filter, so
it still asserts the production violation reporter at the exact public Var
without wrapping the rest of the process.

Focused proof results:

- shell + web + background: 14 tests / 76 assertions / zero failures/errors;
- operator: 19 / 104 / zero;
- work: 11 / 139 / zero;
- blob publication + curation: 2 / 104 / zero.

The first complete-tier attempt then exposed a separate runner waste: every
worker checkout symlinked the same writable `.clj-kondo` cache. A losing
worker could poison its delayed canonical database base and make every later
database test fail immediately. `bin/test` now gives each worker a
copy-on-write private `.clj-kondo`; the structural regression proves it is a
directory, not a symlink. Clean isolated confirmations also run with bounded
parallelism while preserving one fresh JVM per failed task and original
attribution order. The maintained issue is
[`parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md`](../../../seon/issues/parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md).

## Ranked make-them-faster queue

1. **Complete publication/indexing.** Three boot tests spend 158–193 s and
   several fresh-operator tests spend 90–111 s around a real publication.
   Profile and remove the declarations/program-row transaction amplification
   already recorded in
   [`complete-publication-takes-seventy-seconds.md`](../../../seon/issues/complete-publication-takes-seventy-seconds.md).
2. **Whole-image instrumentation.** The live instrumented-turn test is 233.603
   s; the exact-Var regression proved collection scope is a major lever.
   Measure collection, compilation, wrapping, boot, and the turn separately,
   then cache only immutable compiled contracts without weakening the
   whole-image assertion.
3. **Bootstrap drive.** The 171.859 s task is one graph drive. Instrument
   bootstrap, objective, fork, held-out call, and grading; preserve the ending
   commit assertion while reusing any immutable opening basis.
4. **Export.** The 200.542 s proof crosses start/export JVMs and copy/reidentify.
   Time start, filesystem clone, reidentify graph walk, reopen, and query;
   optimize the slow owning phase, not the cross-process assertion.
5. **Generated print and transcript proofs.** Print costs 93–94 s per 200
   trials and transcript costs 66.666 s per 40 database/render trials. Profile
   generator construction, emission, EDN read-back, database transaction,
   query, and dual projection; retain seeds and domain partitions.
6. **Real boot/fresh-operator remainder.** The 47–137 s tests are honest
   restart, SIGKILL, prepl, policy, and readiness interactions. Apply the
   per-phase boot instrumentation from
   [`cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md`](../../../seon/issues/cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md)
   and attack any shared config/program recomputation it exposes.

## Default complete tier

The final frozen-tree command was:

```sh
/usr/bin/time -p bin/test --all 2>&1 \
  | tee tmp/default-complete-long-tier-triage-final-2026-08-11.log
```

The source was `c8a0e009fefbf5313b024cddcdf2f197c6ceb95a`. The runner
selected 71 platform tests and 1,038 bulk tests, skipped 53 declared long
tests, and completed 1,109 tests / 8,961 assertions. Wall time was **389.67 s
(6:29.67)**, inside the owner's 5--8 minute ceiling. The command's phases
were:

- shared publication, nine worker launches, namespace loading, and graph
  selection: about 92.5 s before the platform tier;
- platform tier: 33.825 s;
- bulk tier through the last pool result: 173.283 s; and
- four bounded concurrent isolated confirmations plus teardown: about 82.6 s.

The runner was red, but the timing result is complete: three failures and one
error. Three reproduced in clean JVMs:

- `seon.public-contract-test/every-fresh-public-function-has-a-complete-contract`
  still finds the pre-existing uncontracted `seon.test.runner/-main`;
- `seon.render-simplification-test/nested-values-render-their-declared-faces`
  still misses the declared transaction-report face; and
- `seon.test-runner-test/interrupted-launcher-awaits-its-runner-before-retaining-the-root`
  still misses its fake-runner readiness backstop under load.

`seon.sci.eval-test/generated-sources-compose-fork-guard-and-admission` failed
in the pool and passed its isolated confirmation; it remains a parallel-only
stress finding. No pool task exceeded 45 seconds after triage. The slowest
remaining members were source-publication admission at 44.281 s, run-model
transitions at 43.665 s, and the goal/message property at 42.002 s.

## Declared long roster

The final default runner printed this exact 53-test roster. Grouped symbols
share the displayed reason.

- `seon.bootstrap-drive-test/one-fake-o1-drive-grades-on-its-ending-commit` —
  171.859 s pool: real cluster graph bootstrap, objective/fork drive, and
  ending-commit grading.
- All five `seon.cluster.armed-test` vars — 48.642 s slowest pool member:
  every test boots a real armed cluster; fault-to-fact delivery is the proof.
- `seon.cluster.boot-test/a-dead-holders-run-is-unclaimed-by-the-time-start-returns`
  — 60.475 s pool: real boot, simulated dead holder, restart recovery, and
  custody read-back.
- `seon.cluster.boot-test/explicit-refork-destroys-the-old-branch-and-forks-current-source`
  — 192.600 s pool: complete publication, real start, destructive composed
  refork, replacement boot, and read-back.
- `seon.cluster.boot-test/incremental-source-refresh-publishes-without-touching-existing-clusters`
  — 186.733 s pool: complete incremental publication dominates, followed by
  existing-cluster and later-fork agreement.
- `seon.cluster.boot-test/operator-root-history-policy-is-creation-fixed` —
  133.791 s pool: fresh physical-store creation plus real starts/reopens under
  both history policies.
- `seon.cluster.boot-test/partial-clusters-refuse-and-fresh-clusters-are-current`
  — 114.810 s pool: real boot, program-fact corruption/refusal, and
  fresh-cluster currentness proof.
- `seon.cluster.boot-test/refork-does-not-collide-with-the-store-its-caller-already-holds`
  — 158.206 s pool: published real store plus child-JVM operator refork and
  collision/read-back proof.
- `seon.cluster.boot-test/repl-is-live-after-the-boot-tower` — 47.230 s pool:
  published-base clone, real cluster boot tower, live prepl call, and stop.
- `seon.cluster.boot-test/same-jvm-same-name-restart-releases-the-registered-prepl`
  — 49.939 s pool: real start/stop/start generation proves registered prepl
  release.
- `seon.cluster.boot-test/selected-config-repairs-locked-state-before-consumers-arm`
  — 53.139 s pool: real boot, locked-state config repair, restart, and pre-arm
  fact proof.
- `seon.cluster.boot-test/the-tower-stands-in-one-start` — 46.987 s pool:
  complete real boot tower plus sibling-cluster acquisition and independent
  config proof.
- Nine pre-existing boot proofs remain long for their declared real boundary:
  `a-delayed-stop-never-kills-a-replacement`,
  `a-failed-stop-remains-addressable-and-retryable`,
  `a-failed-tower-never-takes-the-repl`,
  `incompatible-sovereign-schema-refusal-steers-the-operator`,
  `incremental-source-refresh-preserves-agreement-across-real-edits`,
  `orderly-stop-awaits-the-active-loop-pass`,
  `stale-advertisements-read-as-absent`,
  `start-allows-an-older-complete-program-without-indexing`, and
  `two-instances-are-isolated`; their reasons respectively name the real
  generation restart, teardown retry, corrupted prepl boot, incompatible
  store, real edit publication, active flow pass, advertisement lifecycle,
  sovereign older program, and two-cluster isolation.
- `seon.cluster.program-restart-test/an-agent-definition-survives-restart-and-another-agent-calls-it`
  — 67.758 s pool: real cluster stop/restart plus cross-agent acquisition and
  call proof.
- Both long `seon.cluster.store-test` vars — foreign-JVM operating-system
  store-fence proofs.
- Both `seon.concurrency-independence-test` vars — 136.721 s pool: one real
  cluster folds 5- and 10-agent plans concurrently and verifies every receipt.
- Both `seon.concurrency-streams-test` vars — shared-cluster database write and
  multi-message delivery races.
- Both long `seon.config-application-test` vars — real-cluster effective config
  and credential-selection proofs.
- `seon.dev.dependency-cache-test/refresh-preserves-a-recorded-jvms-exact-cache-directory`
  — delayed AOT classes across refresh and heap pressure.
- `seon.dev.edit-feedback-test/split-schema-edits-run-admission-before-publication`
  — 59.518 s pool: real hook subprocesses cover schema admission before
  edit-hook publication.
- `seon.dev.fresh-operator-export-test/export-verb-produces-an-openable-queryable-store`
  — 200.542 s pool: real start JVM, export JVM, store copy/reidentify, reopen,
  and query proof.
- Eight `seon.dev.fresh-operator-test` vars remain long: stale-wrapper refresh
  (53.876 s), forced reset (92.850 s), fresh-process instrumentation (93.065
  s), full init lifecycle (110.837 s), cached boot/readiness (90.430 s), live
  schema reload (110.095 s), full operator restart (114.689 s), and source-less
  reset/republication (94.568 s).
- `seon.flow-test/forced-child-jvm-death-preserves-committed-facts` — real
  child-JVM death and committed-fact survival.
- `seon.fn-test/current-output-floor-classification-is-recorded` — complete
  indexed-graph analysis and committed diagnostic update.
- `seon.oversight-test/a-booted-cluster-tells-its-live-fleet-story` — real boot
  plus root-page fleet integration.
- Both long `seon.print-test` properties — 93.195/94.303 s pool for 200
  generated text/Hiccup equivalence and readable EDN round-trip trials.
- `seon.render.transcript-test/every-generated-history-is-ordered-total-and-token-bounded`
  — 66.666 s pool: 40 fresh-branch histories with dual AI/HTML ordering and
  token-bound proofs.
- `seon.sci.desk-test/desk-survives-kill-9-and-explicit-clear` — 80.213 s pool:
  settle defs, SIGKILL the writer JVM, restart, restore, and clear.
- `seon.sci.eval-instrumentation-test/an-instrumented-dev-cluster-completes-one-agent-turn`
  — 233.603 s pool: published-root start, whole-image instrumentation, and one
  settled agent turn.
