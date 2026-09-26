# Red triage — focused list on default, pid 20656 at 9bbc3568e (2026-09-26 16:21–16:33 local, -0600)

This is a read-only triage. It sorts the 72 red tests in root's focused run by evidence. Sources:
`tmp/orchestrator/tests-0627b/*.txt` (this run), the `tests-0627a`, `tests-0626b` and `tests-0626c` runs, and the stored
`:seon.test.run`/`:seon.test.member` rows on default. Those rows were read once with JVM `eval_clj` on
`(seon.cluster.boot/connection "default")` after the 16:34 restart; that query was read-only. Earlier-day red lists come
from `tmp/orchestrator/red-adopted-2026-09-25.txt` (09-25 08:24), `red-render-fix-2026-09-25.txt` (08:46) and
`verify-reds.out` (09-25 16:45).

## Evidence limit, stated first

- **Stored test evidence before 12:00 today does not exist for any of these reds.** The store was rebuilt at about
  10:47 (the `data/store` directory was created then). The first stored run is at 10:58, and it has 80 runs and 796
  members in total. None of the 72 reds has a stored member before 14:36. So question (1) can only be answered this
  way: "the first stored outcome is red" or "green at HH:MM on program digest X". Only one red has a stored green.
  **`seon.cache-test/the-byte-budget-evicts-and-status-reports-it`: green at 15:33 on program `62cbf50634e3`, red at
  16:31 on `b8f1944cf7b0`.**
- Anything red before today comes from the 09-25 text lists, which give names without failure bodies. "PRE-EXISTING"
  therefore means the test was red on 09-25. The failure identity is compared only where a body was kept, which is the
  timing reds in `verify-reds.out`.
- Pid 20656 started at 16:20:53, 22 s after 9bbc3568e was committed (its process id is `20656-1790461253537`). It
  booted that commit (publication paths under `data/source/9bbc3568e…`). So everything up to 9bbc3568e is loaded, and
  nothing after it is.

## Counts (primary class per test; 72 tests)

| class | n | tests |
|---|---|---|
| NEW-TODAY (the diff confirms it) | 5 | host retirement race, program-change booted-wrapper, config default-document, accretion gate-report output, turn evaluation-ai |
| NEW-TODAY (suspected: one commit fits the evidence, not proven) | 5 | host a-named-change, web-context unrelated-adoption, web two-tabs, one-request stopped-member, turn fired-bound |
| STALE-TEST (asserts retired behaviour) | 9 | 5 retired today, 3 before today, 1 with an unknown date — see table |
| FIXTURE (raw or ill-formed fixture writes) | 10 | 9 from 09-25 writer and fixture commits, 1 unknown |
| TIMEBOUND (only a bound exceeded) | 7 | 5 of them also red or slow on 09-25 |
| PRE-EXISTING (red 09-25; behaviour) | 9 | |
| UNKNOWN | 27 | each row names the evidence that is missing and the best suspect |

## Per test

Columns:
- **stored**: stored member outcomes today, as time, then program digest prefix where it matters, then fail/error counts.
  "first 16:2x" means the only stored run is this one.
- **09-25**: whether the test is on a 09-25 red list.

### one-commit-a (sci/eval, turn settlement, host/accretion)

| test | stored | 09-25 | failure (this run) | class / commit |
|---|---|---|---|---|
| host-test/retirement-closes-admission-at-the-writer-and-races-submission | 14:36 unfinished; 15:30, 16:06 and 16:22 red (f1) | no | host_test.clj:219, `raced []` | **NEW-TODAY 55b5b580f** (15:03, "a form's first durable write opens its run"). The settlement's open was refused `:seon.turn/admission-closed`, and the refusal settlement carried the same open. **Fix claimed by 9909d2e42 + b1229d1a3 (not loaded).** |
| host-test/a-refused-settlement-is-its-forms-refusal-and-the-run-closes | red at every stored run from 14:36 (f2, then f1) | no | host_test.clj:418, shown refusal `nil` | UNKNOWN origin (red at the first stored run). **Fix claimed by b1229d1a3 ("refused unopened runs answer their refusal").** |
| host-test/one-identity-is-homed-on-one-branch-decided-by-the-writer | 14:36 unfinished; e1 from 15:30 | no | `OutOfMemoryError` in `core_print`, 15,055 ms | UNKNOWN origin. This is the OOM class: a clojure.test message printed whole worlds. **Fix claimed by b1229d1a3 (+ fac32e9c4).** |
| host-test/a-named-change-reached-only-by-an-ineligible-test-refuses-by-name | e1 at 14:36, 15:30, 15:38 and 16:06; f1 at 16:22 | no | `seon.fn/gate-sets` got `[db-value {:seon.db/db … :seon.fn/seeds …}]` against the positional arity | NEW-TODAY suspected **1b77876af / a4dcbc0c8** (rc-m2b, root-applied: the callee wrapper supplies declared defaults). A supplied `:seon.db/db` was prepended to a map-arity call. 15c1c423a fixed the earlier fixture error (namespace seeded), which exposed this one. |
| test.accretion-test/a-definition-evaluates-once-and-its-gate-proves-that-root | e1 at 16:08 and 16:23 | no | `gate-report` output refused: `:auto-check` is nil where a map is expected | **NEW-TODAY**, partly fixed by 15c1c423a. The request key became optional, but the output still carries `:auto-check nil`, which breaks the no-stored-nil law. The caller patch `tmp/one-commit-a/turn-gate-report-auto-check.patch` has not been applied. |
| test.accretion-test/a-gate-test-runs-with-its-authors-cluster-custody | f1/e1 at 16:08 and 16:23 | yes (error at accretion_test.clj:19) | `OutOfMemoryError` printing, plus 14,336 ms over the bound | PRE-EXISTING (a different failure on 09-25). The OOM is the message class. **Fix claimed by b1229d1a3.** |
| test.accretion-test/source-harvest-keeps-refusal-render-contracts-coherent | f1 at 16:08 and 16:23 | yes (09-25: not started) | 12,884 ms over the 5,000 ms bound | TIMEBOUND |
| test.one-request-test/a-refused-recording-write-is-a-stored-core-fault-carrying-its-whole-refusal | first 16:28 (f6 e1) | no | `(:seon.fault/policy recorded)` nil; no stored fault digest | **STALE-TEST, today: 0cbf4336f** (12:27, rc-m1 0a: `fault!` returns its receipt; the policy dial was deleted in 7c40396b3) |
| test.one-request-test/a-stopped-requests-unstarted-member-is-unknown-and-an-identical-request-reruns-it | first 16:28 (e1) | no | writer refuses `b-runs-until-released`: row digest `6546…` against writer digest `66e5…` | NEW-TODAY suspected **9b6df1ff4** (defn-scan). The digest now keeps the context of namespaces the row's keywords name. The member whose source has `^{:seon.test/long …}` metadata is refused, and `a-quick` (no keywords) is admitted. Row side and writer side disagree. Not proven. |

### shrink-turn (turn.clj)

| test | stored | 09-25 | failure | class / commit |
|---|---|---|---|---|
| turn-test/virtual-turns-use-the-proc-and-compaction-is-agent-scoped | first 16:25 (f9) | yes | one transaction of 55 datoms against `[30 14]`, one of 25 against `[16 6]`; also 10,011 ms | PRE-EXISTING. The datom assertions are also stale from **55b5b580f** (open folded into the settlement). |
| turn-test/recovery-preserves-terminal-receipts-exactly | first 16:25 | yes | 12,945 ms over 5,000 | TIMEBOUND |
| turn-test/transitions-agree-with-the-model | first 16:25 | yes | 20,636 ms over 5,000 | TIMEBOUND |
| turn-test/a-fired-turn-bound-requests-its-owners-stop-and-settles-its-join-whole | first 16:25 | no | terminal operation is `seon.turn/turn-completion-error`, not `:seon.agent/turn-transform` (that value is still inside `:seon.error/data`) | NEW-TODAY suspected **0cbf4336f** (the `fault!` receipt changed the terminal's wrapping). The test lines were rewritten at 10:05 (e6c3487ad). |
| turn-test/evaluation-ai-is-only-the-repl-session | first 16:25 | no | expects `":error {:seon.error/message"`; the printer emits `#:seon.repl{:error #:seon.error{…}}` | **NEW-TODAY e0e730dfb** (13:17, err-s12). The assertion was written that day and never matched the printer's namespaced-map form. |
| turn-test/a-forms-read-evidence-is-only-what-the-form-read | first 16:25 | no | a pure form records a read of `#{:seon.agent/id :seon.agent/namespace :seon.ns/name}` | UNKNOWN. The evaluation now reads the agent's namespace. Suspects: 1b77876af (M2b) and faedc30f1 (M2). |
| turn-test/an-agent-dial-read-that-refuses-is-not-an-absent-dial | first 16:25 | no | `cluster-handle` throws "requires a matching cluster row" for `no-such-dial-cluster` | FIXTURE, from **4d163db4b (09-25 21:09)**: `cluster-handle` reads the effective config and throws before the turn. This test uses the missing row on purpose. |
| turn-loop-test: a-clean-last-form…, a-refused-phase-escalates…, prompt-and-call-resolve-once…, refused-generated-opening… | first 16:27 | yes | the same `cluster-handle` refusal, for fixture clusters with no config row | FIXTURE, from **4d163db4b (09-25)**. Red before today as well. |
| turn-loop-test/install-gate-failure-settles-the-started-receipt-as-a-failure | first 16:27 | no | the same `cluster-handle` refusal | FIXTURE, from 4d163db4b (09-25) |
| turn-loop-test/committed-ending-namespace-seeds-a-resumed-fold | first 16:27 | no | the first evaluation's `[:seon.program/row :seon.ns/name]` is nil | UNKNOWN. There is no prior evidence. Suspects: 55b5b580f and faedc30f1. |

### shrink-db (db.clj) and read evidence

| test | stored | 09-25 | failure | class / commit |
|---|---|---|---|---|
| db-test/a-namespace-row-change-re-derives-its-declarations-digests | first 16:24 | no | digest unchanged after adding `clojure.set` to the requires | **STALE-TEST, today: 9b6df1ff4** (15:47, defn-scan: the digest hashes only the used resolver context; `(defn f [x] x)` uses no require) |
| db-test/one-owner-digests-a-definition-with-its-stored-namespace | first 16:24 | no | a widened, unused `is` refer is no longer refused | **STALE-TEST, today: 9b6df1ff4** |
| db-test/durable-pull-digests-replay-without-retaining-read-results | first 16:24 | no | durable evidence has no 64-char digest and is not current after an unrelated tx | STALE-TEST, before today: **b96d6e684 (09-25 21:15)**. Only process-local retained evidence keeps the digest and the replay. |
| db-test/wildcard-pull-digests-cover-empty-large-and-canonical-results | first 16:24 | no | digest length 0 | STALE-TEST, b96d6e684 (09-25) |
| db-test/a-function-body-write-reads-its-basis-projection | first 16:24 | no | 12.84 ms against the in-test 10 ms bound | TIMEBOUND |
| db-test/a-pattern-over-an-uninstalled-attribute-witnesses-no-change | first 16:24 | no | read-evidence is current where it should be stale; no changes found | UNKNOWN. The test was added today at 10:29 (e75e23167), and the only stored run is red. Missing: a run between 10:29 and 16:24. |
| db-test/an-entity-pull-is-checked-only-on-the-attributes-that-changed | first 16:24 | no | not current; replays 0 | UNKNOWN. Likely the b96d6e684 no-durable-replay class, not proven. |
| db-test/every-database-value-reader-answers-for-all-four-view-shapes | first 16:24 | no | history/since evidence is not current against its own value | UNKNOWN (same read-evidence class) |
| db-test/a-write-naming-another-clusters-branch-is-refused-naming-both | first 16:24 | no | an elided arity returns no `:db-after` | UNKNOWN. Suspects: one-commit writer changes fcee85161, de67fdd1a and 55b5b580f. |
| db-test/an-unknown-read-operation-refuses-naming-the-attribute | first 16:24 | no | `:seon.error/member` and `:offending` are nil | UNKNOWN. Suspect 09fe1d064 (13:13, one refusal normalization). |
| db-test/nested-native-reports-admit-bounded-reference-identities | first 16:24 | no | db values admit as `#{:seon.sci.admit/opaque :seon.sci.admit/projection-error}` | UNKNOWN. Suspects: datahike `db?` change cefd8a2bf (10:46) and 09fe1d064. |
| db-test/render-declarations-require-their-final-function-row | first 16:24 | no | refusal `:seon.error/data` lacks `:seon.render/declarations` | UNKNOWN. Suspect 09fe1d064. |
| db-test/uncarried-read-reports-one-projection-fallback-per-call | first 16:24 | no | no fallback refusal and no WARN line; a projection was found anyway | UNKNOWN. Suspects: 92b22cbf1 (projection family in the process cache) and 558446356. |
| problems-test/missing-projection-is-a-refusal-not-query-rows | first 16:27 | no | the same class: fallback not reached | UNKNOWN (same suspects) |
| owned-value-test/a-transaction-entity-root-reaches-the-arity-gate | first 16:25 | no | `changed` is nil | UNKNOWN. **P4 (15e6a31c4) is refuted as the cause of the identity set:** `identity-attributes` through rschema equals the old schema scan on default (49 = 49, `:seon.fn/sym` in both). P1 and P1b (6460d7bfb, 2f2f3f4af) are not checked. |
| test-support-test/a-canonical-database-is-the-production-source-population | first 16:25 | no | 9,919 ms, and the live process id `20656-1790461253537` is in the canonical database | UNKNOWN (the live process row leaks into the fixture), plus TIMEBOUND |
| test-support-test/a-long-refusal-reaches-the-failure-message-whole | first 16:25 | no | the reporter clips the message at `Entity: #:seon.…` | UNKNOWN. Note that b1229d1a3's "bounded test messages" goes in the same direction, so check it before calling this fixed. |
| web-context-test/context-and-page-do-not-demand-the-render-proc | first 16:30 | no | a bare `{:db/doc …}` fixture entity is refused `unowned-entity` | FIXTURE. The refusal has existed since 09-16 (74d020fc6) and the fixture line since 09-16, so the start date is unknown. Suspect fd8f97877 (09-25 owned walk). |

### perf-boot (boot arming, analyzer, fn.clj, cluster.clj)

| test | stored | 09-25 | failure | class / commit |
|---|---|---|---|---|
| instrument-test/boot-arming-arms-every-loaded-contract-with-its-definition-digest | first 16:21 | no | `arm-host-program!` refused: the request has no `:seon.db/db` | **STALE-TEST, today: a4dcbc0c8** (12:10, rc-m2b: boot passes the cluster db). The test calls it with digests and namespaces only. |
| cluster.program-change-test/a-booted-wrapper-supplies-its-declared-defaults | first 16:23 | no | `my.program/breaks` refused: supplied `:seon.db/db` missing | **NEW-TODAY 23a08e4ad** (perf-boot). The regression was added with the fix, and it is red on the JVM that booted the fix. Resume 6 reports live SCI green, so the test entrance (a body in `with-database`) is not supplied the default. |
| cluster.publication-delta-test/schema-referrers-are-selected-for-arming-without-a-namespace-reload | red at 16:10 and 16:23 | yes | the probe symbol is not in the selected set | PRE-EXISTING. 23a08e4ad names it as a regression, and it is still red. |
| fn.caller-lint-test/only-a-signature-change-selects-caller-files | e3 at 16:09 and 16:24 | no | raw fixture edits refused by the writer's digest check | FIXTURE, from **79b6d9abd (09-25 09:10)**. **Fixed by 67be150a1 (not loaded).** |
| fn.publication-cache-test/lint-direct-callers-after-committed-declaration-changes | first 16:24 | no | a no-change pass lints all 4 files; a blocking-lint refusal | UNKNOWN. Suspects: 46ad08a41 (16:04, one parse per file / names-only previous read) and d10d79061 (14:56). |

### defn-scan (program.cljc digests)

| test | stored | 09-25 | failure | class / commit |
|---|---|---|---|---|
| program-test/indexed-and-evaluated-declarations-are-the-same-entities | red at 15:34, 16:08 and 16:27 | yes | the evaluated row lacks the `sample.s1/right` call | PRE-EXISTING |

Also 9b6df1ff4, filed under shrink-db above: the two db-test digest tests, and the suspected one-request stopped-member.

### render (render.clj, web, walk: S1/P2)

| test | stored | 09-25 | failure | class / commit |
|---|---|---|---|---|
| render.web-context-test/unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot | first 16:30 | no | `oversight/html-table` and `agent/render-settings-html` rendered twice | NEW-TODAY suspected **978bac337** (16:03, S1 trade: a moved revision re-renders instead of replaying) |
| render.web-test/two-tabs-each-get-their-own-complete-paint | e1 at 16:10; f2 at 16:31 | no | 4 sends against 2; 2 derivations against 1 | NEW-TODAY suspected 978bac337 |
| render.web-test/unrelated-transaction-reuses-debug-observation-and-render-call | unfinished at 16:10; e1 at 16:31 | no | the body did not exit | UNKNOWN. Consistent with 978bac337, which deleted the debug retained-read display. |
| render.web-context-test/read-only-mcp-return-preserves-root-page | first 16:30 | no | event `:unspecified-mcp-return-invalidates` never published; 20,695 ms | UNKNOWN (a missing event, not just a bound) |
| render.web-test/a-feed-writer-failure-enters-the-cluster-fault-path | red at 16:10 and 16:31 | no | `:feed-writer-fault` never published | UNKNOWN. Suspect 0cbf4336f (fault path). |
| render.web-test/a-page-failing-every-pass-records-one-fault-and-the-proc-survives | red at 16:10 and 16:31 | no | recorded faults 0 | UNKNOWN. Suspect 0cbf4336f. |
| render.web-test/transaction-refusals-map-to-http-without-success | red at 16:10 and 16:31 | no | **refusals answer HTTP 204 with no body** (expected 500 and 422) | UNKNOWN. Suspects: 0cbf4336f and 09fe1d064. **This one matters for behaviour.** |
| render.web-test/start-refuses-a-flat-process-write-before-binding | red at 16:10 and 16:31 | no | the result has no `:seon.error/message` and no `:refused-member` | UNKNOWN (same suspects) |
| render.web-test/data-resolves-an-entity-root-… and data-uses-the-one-floor-… | red at 16:10 and 16:31 | no | body assertions; the actual text was elided to a blob | UNKNOWN (the body is not inspected; the blob is on the old JVM) |
| render.web-test/declared-units-are-components-in-schema-order | red at 16:10 and 16:31 | yes | an extra `:seon.agent/admission-closed-tx` | STALE-TEST, before today: **8e3a6315a (09-25 19:35)** |
| render.web-test/coalesce-floor-one-derivation-test | red at 16:10 and 16:31 | yes | 6 repaints for 6 commits | PRE-EXISTING |
| render.web-test/next-turn-context-keeps-runtime-history-through-an-empty-turn-and-a-wake | red at 16:10 and 16:31 | yes | emissions out of order; the edge retraction was refused | PRE-EXISTING |
| render.web-test/reconnect-is-repaint-wire-test | red at 16:10 and 16:31 | yes | 41,628 ms | TIMEBOUND (pre-existing) |
| render-source-test/preview-cache-is-memory-only-and-invalidates-on-read-changes | first 16:30 | yes | re-evaluated (2 against 1) | PRE-EXISTING. 978bac337 now makes re-render the ruled behaviour, so this may become STALE-TEST. |
| render-source-test/source-contracts-execute-in-memory-and-terminal-transcripts-never-reexecute | first 16:30 | yes | `ClassCastException` Keyword → Number in `output-refs` | PRE-EXISTING |
| render.retained-test/equal-committed-database-skips-read-replay | red at 16:10 and 16:29 | yes | rendered `A:…fixture-a` instead of `other connection` | PRE-EXISTING |
| render-cache-test/a-fixture-branch-rendering-root-leaves-the-parent-branch-packages-unchanged | e1 at 16:10 and 16:29 | no | the fixture message to `[:seon.agent/id "root"]`: there is no root agent on the fixture branch | FIXTURE, origin UNKNOWN |

### cache-central (seon.cache, config)

| test | stored | 09-25 | failure | class / commit |
|---|---|---|---|---|
| config-test/the-default-document-has-one-canonical-complete-location | red at 15:32 and 16:30 | no | `:seon.config.cache/max-bytes` is declared but has no `config/default.edn` line (69 against 68) | **NEW-TODAY 92b22cbf1** (15:02, cache-central S1) |
| config-test/sparse-file-reading-does-not-compile-a-second-time | green at 15:32 (p4); red at 16:30 | no | 0 declaration acquisitions against 1 | **STALE-TEST, today: 558446356** (15:27). `declared-projection` is a `seon.cache` hit, so the counted acquisition never runs. |
| config-test/config-loads-the-packaged-population-in-a-fresh-jvm | red at 15:32 and 16:30 | yes (10,820 ms) | 10,062 ms | TIMEBOUND (pre-existing, same identity) |
| config-application-test/every-config-entry-has-an-honest-application-contract | e1 at 15:33 and 16:31 | no | a durable definition holds `(fn* … (instance? java.io.File …))` | UNKNOWN. The form is not in the checkout (searched src, test and resources), so it is likely a stored row. Missing: which entity holds it. |
| cache-test/the-byte-budget-evicts-and-status-reports-it | **green at 15:33 (`62cbf50634e3`), red at 16:31** | no | 16 evictions against ≥ 17; kind bytes 1,200 against 900 | UNKNOWN. No commit touched `seon.cache` or the test between the two runs. The suspect is Caffeine's asynchronous eviction bookkeeping under load, not proven. |

### Others

| test | stored | 09-25 | failure | class / commit |
|---|---|---|---|---|
| test-test/admission-refuses-stale-program-and-records-empty-selection | e1 at 16:09 and 16:28 | no | a raw `:seon.fn/doc` datom refused by the digest check | FIXTURE, from 79b6d9abd (09-25). **Fixed by 553ab872e (not loaded).** |
| test-test/recording-distinguishes-run-replay-from-a-new-event | f1 at 16:09 and 16:28 | no | the read refusal now carries the writer's attempt context | STALE-TEST (the introducing commit is not identified). **Fixed by 553ab872e.** |
| problems-test/absent-facts-produce-no-entries | first 16:27 | no | 10,613 ms | TIMEBOUND |

No red is attributed to eval-feedback (repl.clj; repl-test green, 92/0/0), oom-array (refusal.clj) or shrink-test
(test.clj wedge fix).

## Reds that commits after 9bbc3568e claim to fix (not loaded in this run)

- **67be150a1**: caller-lint-test/only-a-signature-change-selects-caller-files.
- **553ab872e**: test-test/admission-refuses-stale-program…; test-test/recording-distinguishes-run-replay….
- **9909d2e42 + b1229d1a3**: host-test/retirement-closes-admission…. b1229d1a3 also covers host-test/a-refused-settlement…
  ("refused unopened runs answer their refusal").
- **b1229d1a3 + fac32e9c4** (the OOM class): host-test/one-identity-is-homed…; accretion-test/a-gate-test-runs…. That
  test's 14.3 s bound failure follows from the OOM print.
- The remaining commits (d06dc197b, 9fd4d9d50 konserve, 3d48f13e8, a760a6d08, 21e3a7068) name no red in this list.

## Evidence gaps that decide the UNKNOWNs

1. A stored run of `seon.db-test` between 10:29 and 16:20. The store holds none, so the read-evidence and refusal-shape
   reds (7 in db-test, 1 in problems-test) cannot be dated. One named test on 9bbc3568e^ in a `git archive` snapshot
   would split 09fe1d064 from the one-commit writer changes and from 92b22cbf1.
2. The web-test fault and HTTP reds (4) need the stored faults of run `746cf87526a6`, or one body rerun, to separate
   0cbf4336f from 09fe1d064. The HTTP 204 for a refusal is the one to take first.
3. The config-application `fn*` definition: find the entity that holds it on the fixture's canonical database.
