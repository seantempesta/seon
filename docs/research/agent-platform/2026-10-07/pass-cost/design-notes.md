---
type: notes
lane: pass-cost
status: awaiting-central-proof
---

# Pass cost: measured producer fixes, budget still unmet

The pass renders each value against its viewer namespace, reads configuration, and prepares evaluation settlement through the database owner. This slice orders the existing function snapshot, reuses immutable configuration defaults, uses the Datalog parser's traversal, and moves terminal queries and JVM render settings to pass scope. An identity lookup uses the existing indexed pull.

**Not ready to land.** The corrected 100-form probe is 349.7 MB SCI and 194.9 MB JVM. This is a substantial reduction, but does not meet the approved tens-of-MB target. Central tests, publication, adoption and combined-current-HEAD execution are unproven. No commit was made, no tests were launched, and no worktree was created. The user reserves test runs for root; `bin/lane commit --test` itself launches tests, so it has not been invoked.

## Measurement authority and limitations

Runtime archive: `5b0a3327ca7782279d11d714712f1b59727a756c`. All prototype definitions, arming, resets and measurements used the owned throwaway root `/tmp/seon-pass-cost-session/pass-cost/root`, PID 28018, start instant `2026-10-07T13:04:22.133Z`. Default was not stopped, reset or given prototype definitions. Default received ordinary task reads and creation/update of measurement defect `f9a66e62bffc` through its installed task writer.

The runtime stays at the parent archive. Selected definitions were read from source and evaluated and armed in that disposable JVM. This is behavioral/performance evidence, not publication or installation proof. In particular, the settlement prototypes include adjacent s5a-fix definitions landed in `191627656`. The static candidate is a git archive of `a423b8b297ac9b46645c93b4d99e7f342bdae7a5` plus this lane's eight changed files. It contains the landed printer changes; the measurement JVM does not. Those are explicit foreign boundaries.

`probe.clj` runs the same `seon.pass-probe/mcp!` entrance, three observations per mode and size after warmup. Arithmetic is repeated `(+ 1 1)`. ThreadMXBean counts platform/carrier allocation and total JVM allocation; SCI virtual-thread counters themselves are -1. Whole-JVM figures include concurrent runtime work and the growing store/history. The fixed-value microprobes count allocation on their platform thread, excluding other threads. JFR weights are sampling estimates, not exact function totals.

**Measurement defect found and fixed:** after helpers were armed, `seon.pass-probe/written` threw on every store hook. Its `[:vector :tuple]` output declared zero-element tuples; the actual tuple is `[op key commit-id branch]`. `storage` had the same invalid input declaration. Exact refusal: `:seon.instrument/contract-error`, expected a tuple with 0 entries, offending `[:assoc :probe nil nil]`. The hook exception was swallowed: previous rows reported zero writes despite changing branch commit ids. Those rows also include error-allocation overhead. Both contracts now declare the four members. Corrected parent and candidate runs use the identical probe repair. **Only the `clean-*` full-pass rows below are acceptance evidence.** Earlier rows remain in `measurements.edn` as diagnostic history, not valid final comparisons. The fixed-value microprobes do no writes and are unaffected.

## Corrected same-probe comparison

Medians of three runs. Decimal MB. The candidate was measured on either side of the parent; the table uses the later candidate (`clean-change2`). `clean-change` also remains in the raw artifact. Growing retained query-cache buckets/store state are not controlled away; no cache was cleared to obtain these figures.

| Mode | Forms | Parent ms → candidate ms | Allocated MB, parent → candidate | Armed calls, parent → candidate |
|---|---:|---:|---:|---:|
| SCI | 1 | 66.155 → 56.371 | 117.370 → 113.770 | 8,463 → 8,632 |
| SCI | 10 | 102.218 → 73.640 | 191.711 → 142.452 | 31,546 → 30,558 |
| SCI | 100 | 371.091 → 191.287 | 868.873 → 349.741 | 260,250 → 250,947 |
| JVM | 1 | 31.762 → 33.553 | 48.491 → 47.558 | 3,237 → 3,090 |
| JVM | 10 | 54.174 → 43.502 | 90.581 → 66.588 | 15,106 → 12,488 |
| JVM | 100 | 207.834 → 114.863 | 413.735 → 194.907 | 133,802 → 106,337 |

The one-form JVM time and one-form SCI call count did not improve; no blanket no-regression claim. The 10-to-100-form allocation slope is still approximately 2.30 MB/form SCI and 1.43 MB/form JVM. Three sizes do not prove asymptotic linearity or bounded cost as history grows.

Every corrected row has one transaction attempt and one observed own durable commit. Candidate store writes range 175,568–181,997 bytes (SCI 1), 201,166–216,071 (SCI 10), 315,740–319,179 (SCI 100), 158,700–166,984 (JVM 1), 187,959–197,461 (JVM 10), 267,887–274,674 (JVM 100). Store bytes vary with changed index nodes and accumulated history; they are not a storage-improvement claim. The replies are 18, 184 and 1,843 estimated tokens for 1, 10 and 100 forms. Separate real-entrance inspection checks every returned value is 2 and has no reply diagnostic.

## Ranked initial allocation profile

Five 100-form passes per mode. Nearest Seon frame includes called library work. These recordings precede the armed broken storage-hook measurements. Unattributed samples are retained separately.

| Rank | Producer / mode | Sampled MB | Work proportional to |
|---:|---|---:|---|
| 1 | `seon.db/vector-query-call`, SCI | 1,038.5 | queries × retained dependency cache buckets, then query work |
| 2 | `seon.sci.kernel/public-functions-in`, SCI / JVM | 744.9 / 811.2 | forms × all program functions |
| 3 | `seon.db/pull-plan-with-evidence`, SCI / JVM | 113.6 / 349.4 | pulls × dependency pull-cache/planning work |
| 4 | `seon.config/effective-in`, JVM | 179.9 | two configuration derivations per form |
| 5 | call preparation, JVM | 176.6 | prepared armed calls |
| 6 | armed invocation, SCI | 102.3 | checked calls |
| 7 | query-variable-attributes / may-read-vectors, SCI | 90.4 / 66.0 | repeated parsed-query walks |

SCI total 3,131.1 MB, 1,827 samples; JVM 1,907.9 MB, 1,200 samples. No nearest-Seon frame: SCI 442.5 MB, JVM 126.8 MB. Later warmed recording `allocation-profile.json` is supplemental, before the final identity-lookup change; it is not the final clean comparison. Its largest SCI buckets were query execution/cache lookup (386.5 MB), LZ4 buffer allocation (199.9 MB), and armed invocation (175.0 MB), across five passes.

## Changes and design check

1. **EQUIVALENT — namespace candidates seek the existing snapshot.** `kernel.clj:218,250` makes its existing function map sorted and uses `subseq`, stopping at the namespace boundary. Public means the existing literally-false private flag; absent flags stay excluded. No second index or roster. Acquisition O(P log P), lookup/update O(log P), namespace candidates O(log P + K), replacing O(P + K log K). Clojure pin `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`, `Symbol.java:109–123` compares namespace then name; `core.clj:5166–5184` implements `subseq` via `Sorted.seqFrom`. Same-namespace ordering equals the prior string order. Producers and fork merges were inspected in `sci/eval.clj` (cache-program!, cache-function!, base-first merges). **100 empty-namespace calls: 35.205 ms / 165.836 MB → 0.427 ms / 0.214 MB.** New snapshot regression covers empty, private, unspecified privacy, neighboring namespaces, order, replacement and clearing.

2. **EQUIVALENT — defaults belong to the immutable projection.** `config.clj:165` uses existing `schema/projection-cache-value` keyed by the projection and the derivation name. Live entity and overlay reads remain live. O(dials) once per projection, then reuse instead of rederiving on each effective-config call. Contract added at the producer. **100 derivations: 1.477 ms / 2.847 MB → 0.114 ms / 0.092 MB.** Existing accretion/precedence/isolation tests must remain green.

3. **EQUIVALENT — use the parser's own traversal.** `db.clj:2143` uses datalog-parser `impl/collect-type Pattern` over `:qwhere`; deletes the generic `parsed-nodes` tree walker. Dependency `org.replikativ/datalog-parser 0.2.37`, `impl.cljc` collect-type and `impl/util.cljc` collect own the traversal; matched Pattern nodes stop the walk instead of traversing their record leaves. It preserves nested pattern traversal. Work follows parser nodes, with smaller constants; no new cache. **100 three-pattern collections: 0.729 ms / 3.289 MB → 0.050 ms / 0.047 MB.** All helper microprobes include 100 calls; they do not count the pass's other armed calls.

4. **EQUIVALENT success path — terminal membership once per run.** `turn.clj:3292,3319,3503` computes the triggered run's last agent ordinal once per distinct run id and passes it into receipt preparation. Ordinary untriggered runs use one query per pass; triggered runs at most two. The previous implementation did two queries per form. The single-evaluation arity remains, so its caller is unchanged. Missing/failed query evidence is thrown whole at this internal helper, rather than mistaken for a terminal ordinal. A new regression counts exactly one helper call for 1- and 10-form MCP passes and checks returned values. Combined SCI query counts, middle 100-form observations: **325 → 26**, including the next fix; terminal helper exactly once. Earlier staged comparisons are contaminated by the probe defect and do not establish a separate wall/allocation gain for this change.

5. **EQUIVALENT — identity lookup uses the unique index.** `turn.clj:4655` replaces a one-clause Datalog query with `db/pull [:db/id]` by the declared evaluation identity. Missing evaluations stay nil; read refusals pass through as errors. The first design considered moving generated-run classification out of the form loop; the smaller existing lookup operation removes the query-cache path without changing that classification. **100 distinct absent ids on one immutable database: 17.332 ms / 41.064 MB → 3.119 ms / 3.808 MB.** Contract added. The generated-form regression and preview behavior are required central checks.

6. **BEHAVIOR CHANGE — JVM render policy is acquired once from the pass's starting basis.** `turn/batch.clj:889,918` captures a delay holding effective configuration, result caps and profile. The show closure passes those values; explicitly supplied world members win. Acquisition remains in PRINT, so it does not prevent EVAL before a print failure. This matches pass-scoped SCI policy; configuration writes inside a JVM pass affect its next pass. No persisted database is added to an evaluation world. **Effective-config calls at 1/10/100 forms: 3/21/201 → 2/2/2.** The new JVM regression checks values, diagnostics and constant configuration-read count. The combined corrected JVM 100-form change is **207.834 ms / 413.735 MB / 133,802 armed calls → 114.863 ms / 194.907 MB / 106,337 calls**; that figure includes all changes, not just configuration.

7. **BUG FIX — probe tuple contracts match observed writes.** `test/seon/pass_probe.clj` declares the actual four fields for `written` and `storage`. No effects, storage code or counters were bypassed. The same armed helpers now observe exactly one own durable commit and positive store bytes for every measured pass. Task `seon.pass-probe/reports-zero-writes-while-the-branch-commit-advances` (`f9a66e62bffc`) records the exact refusal and verified cause.

The simpler designs held against real values. Two prototype installation mistakes were rejected: changing the snapshot map without rebuilding retained contexts caused a HashMap/Sorted ClassCastException; changing jvm-show arity while the branch still carried the old declaration caused an arity refusal. Those measurements are excluded. Final jvm-show preserves its two-argument interface. `reacquire-snapshots.clj` explicitly runs the existing snapshot producer on retained contexts in the throwaway root. Root must use normal adoption/reacquisition or restart after acceptance; a hot Var edit alone is insufficient.

## Verification and root's exact focused list

No test runner was started by this lane. Requested from root:

- `seon.pass-cost-test/a-pass-reads-its-terminal-ordinal-once`
- `seon.pass-cost-test/jvm-render-settings-are-acquired-once-per-pass`
- `seon.sci.kernel-test/public-functions-follow-the-current-namespace-snapshot`
- `seon.sci.eval-test/acquired-source-context-forks-own-their-lazy-program-state`
- `seon.config-test/an-entity-written-before-a-dial-existed-reads-its-declared-default`
- `seon.config-test/one-compiler-applies-default-overlay-environment-precedence`
- `seon.config-test/two-clusters-on-one-jvm-have-no-config-bleed`
- `seon.db-test/a-query-with-nothing-to-decode-returns-datahikes-value`
- `seon.db-test/query-symbol-values-use-the-declared-codec-in-every-binding-shape`
- `seon.db-test/collection-bound-query-evidence-covers-every-input-attribute`
- `seon.turn-test/a-replaced-result-is-shown-by-the-one-print-owner`
- `seon.turn-test/a-turn-dependent-generated-form-keeps-its-value-beside-the-fault`
- `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`
- `seon.pass-probe-test/a-pass-of-each-entrant-is-measured` (declared long test; root schedules it).

Archive lint covers src, script and test, `--cache false`, zero errors; warnings are not represented as passes. Contract compilation checks all 475 src/test paths against the packaged candidate plus baseline-aware checks of touched functions. Final results are saved in `verification.edn`. The installed checker is the older parent implementation; its warm run still allocates about 1 GB, linked to `seon.contracts-compile-test/corpus-check-allocation-is-floored-by-edamame-per-char-boxing`. It is not evidence for the newly landed checker implementation.

No publication/adoption, combined HEAD boot, browser paint or central test success is claimed. Source +21 net lines; tests +77 net lines after the probe correction (eight changed paths). No commit hash exists. Claims are retained while root proof and budget work are pending.

## Remaining S4–S6 work and observed defects

- **S4:** remove retired generated-context refresh/read-evidence work and its scans at the owner. Current allocation still has substantial armed/call-preparation work (task `ee57516cbaef`), and the snapshot fix does not remove schema renderer scans. Existing viewer scan task `f55702031f83` is covered here but not marked proven.
- **S5:** finish once-per-pass recording/analysis and return held replies; remove repeated observation rereads, fault-window queries and profile upserts as the approved spec directs. The candidate does not claim those cuts. Writer, index serialization and LZ4 buffers remain material. One durable transaction is proven; small allocation is not.
- **S6:** remove retired opening/recovery/receipt machinery with its consumers. Keep generated-form behavior until that cut has its replacement proof.
- **Dependency seam:** Datahike pin `e5453d967b749794944dbdfe64b751519983ba59`, `query.cljc:3025–3086`: inheritable-entry walks retained result buckets; compatible-source-keys? allocates member/subvec/mapv structures before checking whether a bucket contains this query key. Cost follows retained source buckets even for unrelated queries. No dependency code was edited. This is part of the main pass-cost defect and needs a maintained-fork slice with query-cache inheritance regressions, not cache clearing in production.
- Default initially reported three fault signatures, including show-row passing nil to shown-failure (existing `56e06f9e410a`, `fe8f23d6095c`), and publication/projection over-bound warnings. First disposable boot took 48,061 ms, source readiness 45,062 ms (publication task `9665c2ed25f0`). A detached launcher lost its child; a retained foreground launcher was needed, also observed in the prior review. No cause is claimed. Disposable reset operations ranged from 633 ms to 16,258 ms; none was default.
- Final observed disposable RSS was 1,665,264 KiB; heap used 569,180 KiB, committed 950,272 KiB. These are observations, not memory-improvement claims. The old runtime's status retains failures from rejected prototypes; clean reply verification and positive durable counters do not erase that history.

Reproduction scripts and numeric records are beside this note. Larger JFR recordings and full raw envelopes remain under `/tmp/seon-pass-cost-session/pass-cost/`. Default needs no reset for this work; a normal root-managed restart/reacquisition is required only after an accepted landing. Cleanup status is in `verification.edn`.

## Gate red at commit: config-test whole-map assertions

`seon.config-test/two-clusters-on-one-jvm-have-no-config-bleed` (2 fails at its result-caps assertion) was red on HEAD
without this slice: `bin/seon check` of HEAD plus a docs-only path, run edf34b61f648, pass 8 / fail 2. The cause is the
test, not config bleed: it compared the whole `seon.config/result-caps` refusal, and the installed
`seon.error.refusal/error` now attributes `:seon.error/type` and `:seon.error/world` (health attribution), so the
equality could never hold. `caps-refused-for-want-of-a-cluster-carry-that-refusal-as-the-cause` had the same stale
assertion. Both now assert the behavior's members (type, operation, missing cap key, offending refusal, carried
cluster name) with `select-keys`. The cached `registration-defaults` reads only the declaration projection that holds
the cache, so it cannot carry one cluster's configuration into another. Check run 3400fcdeb723: 12 pass / 0 fail.
