# Per-declaration reload — owner decision required (2026-09-23)

No production changes or commits. Baseline HEAD at inspection: f0c4564a6.

The requested form-head-only selector cannot cover all requested Clojure semantics with the currently persisted facts. This is the existing [compile-time dependency issue](../../../seon/issues/incremental-publication-omits-callability-changes.md), not another lane's in-flight load failure.

Evidence:

- `src/seon/fn.clj:324` (`function-definition?`) admits arglist-bearing declarations and defmulti. Plain constant defs do not pass this predicate; the declaration-row producer ends with `:else nil` at line 724. HEAD has the same predicate and defined-by persistence (checked with `git show HEAD:src/seon/fn.clj`).
- `src/seon/fn.clj:659-725` persists selected metadata but no inline/const fact. `resources/seon/schemas/seon.fn.edn:31` defines defined-by as the actual defining form, not compiler embedding semantics. Ordinary and inline-metadata defn share the same form head.
- Clojure revision b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d, `reference-code/clojure/src/jvm/clojure/lang/Compiler.java:7507-7525`: isInline reads the resolved Var's :inline metadata and optional arity predicate. At lines 7686-7688 the compiler invokes the expansion during compilation. Inputs are the resolved Var and call arity; recomputation occurs when the caller compiles, proportional to caller compilation. This cannot be inferred solely from defined-by.
- First-party caller: `src/seon/cluster.clj:1935` development-namespaces currently derives namespace seeds from changed identities and traverses requires in both database values. The producer prerequisites are outside assigned ownership (fn.clj and schema population).

Read-only live evidence: `bin/seon status` and MCP runtime_status found default alive, PID 51528, start 2026-09-22T18:46:42.624Z; no missing readiness layers, 14 errored receipts. Explicit-root/cluster JVM eval:

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))]
  (seon.db/pull database '[*] [:seon.fn/sym 'seon.id/digest]))
```

Returned in 3 ms; defined-by = clojure.core/defn, source and contract present. MCP tools available. No default mutation. No scratch root or shell left running. No tests, cold boot, publication measurements, or performance improvement claimed.

Stop boundary: user explicitly requires three options when the plan leaves a decision open; AGENTS.md Facts over inference requires missing facts at their producer. Choosing source parsing or live Var metadata at the reload consumer would introduce an unruled authority and would not recover unindexed constant declarations.

Options, simplest constraint first:

1. Restrict supported incremental edits to indexed form-head cases; refuse unsupported edits conservatively. Smallest selector change, but gives up the requested inline-metadata and constant coverage and needs explicit owner acceptance of that restriction.
2. Recommended: extend producer ownership to persist compiler-embedding facts and constant declarations, then consume prior/current facts in the existing reload selector. Cross-owner producer/schema work plus scratch boot; preserves the intended ordinary-defn optimization and makes required distinctions queryable. Arbitrary macros reading defs require a declared conservative policy (e.g. all plain defs), not inference of arbitrary macro execution.
3. Keep conservative dependent reload until that producer prerequisite lands. No new semantic gap from this lane; gives up the requested core-edit speedup for now.

After the ruling: implement, canonical fixture regressions, scratch-root semantic proofs, committed publication clock rows and phase breakdown remain outstanding. Instrumentation selection and retired-identity unmapping have not been changed.


## Owner ruling and commit A execution

Owner selected option 2 in two commits. Commit A uses a declared set of compiler-embedded form heads and a typed `:seon.reload/unknown` for incomplete facts; unknown widens. Commit B follows the indexer-facts landing and adds producer inline/constant facts. The prior stop is resolved.

Commit A changes only the reload span, tests, and reproducible measurement harness. It queries defining heads in one batch per immutable database value, retains old and new graphs, and traverses dependency seeds independently of the directly changed namespace set. Retired unmapping and arming selection are unchanged.

Foreign proof boundaries encountered: HEAD 56bae6708 referenced absent `schema/load-projection`, so the initial HEAD-plus-owned-paths launcher failed at load. That commit was subsequently amended by its owner; no foreign files were changed. A shared-tree test hit the in-flight partition validator against the older canonical fixture. A subsequent snapshot at fe374f1ef loaded but its old canonical fixture exposed existing contract/arity mismatches in `seon.db/validate-pulled-result`, `seon.fn/source-rows`, and `seon.cluster.agent/release-context!`. Those failures are not accepted evidence. Reload rule reads now use a batched Datalog query, avoiding repeated pulls and their cost.

Scratch source snapshot: b6fe3ffa1 plus owned reload changes; dependency sources linked from the main checkout. Root `tmp/reload-per-decl-root`, PID 94809, start 2026-09-22T20:12:13.243Z. From-zero boot readiness 146520 ms; no missing layers. Live selector for `seon.id/valid?` returned unknown, 375 namespaces, 33.419125 ms. This is the conservative A behavior, not the final speedup.

The one-request hook harness is `test/seon/cluster/reload_measure.clj`. It checks actual client calls, adoption observations, and an independently compiled caller's Var and function-root identity. Its scratch-only `seon.id/valid?` edit changes the result only for the sentinel string `reload-probe`; identity generation is untouched. All source edits restore in finally. Raw clocks: `tmp/reload-per-declaration-evidence/hook-a.edn`.

| Case | Baseline hook ms | Commit A hook ms | Requests | Reloads |
|---|---:|---:|---:|---:|
| No change | 217 | 123.890250 | 1 | 0 |
| Leaf | 6257 | 2665.444667 | 1 | 1 |
| Core defn | 24799 | 15317.185750 | 1 | 374 |

The A core remains deliberately broad. Different snapshot/load/host conditions make these observations, not a causal attribution of all latency differences. The probe's caller returned true and retained both Var and function-root identity, but because this disposable caller is not indexed, the final exact reload-set assertion is reserved for B. The real indexed dependency regressions use the canonical fixture.

The initial preparation request refused zero arming (`{:registered 0 :instrumented 0}`); a real leaf edit adopted successfully and established the clocks' baseline. No silent treatment of that refusal as success. The macro/protocol canonical tests and final load/commit are pending below.


### Commit A completed proof

Committed legacy measurement script ran unchanged with `PUBLICATION_CLOCK_RESUME=1`, `tmp/reload-per-declaration-wt`, and `tmp/reload-per-decl-root`: first 345.401 ms; no-change 347.536 ms; leaf 5053.648 ms; core 21953.282 ms. Reloads 0/1/374; observed wrapper replacements 0/3/1678. Optional retention sweep was unavailable (the script retains its refusal); it is not publication success evidence. Script exited 0 and observed exact scratch process exit. Logs copied into `tmp/reload-per-declaration-evidence/legacy-a/`.

Focused canonical regression: `bin/test-fast --paths src/seon/cluster.clj test/seon/cluster/reload_per_declaration_test.clj -- seon.cluster.reload-per-declaration-test`, run from the isolated snapshot, with its recording store linked to the stopped scratch root. Run `bb8a9351bb7d`: **4 executed, 0 reused, 17 assertions, 0 failures/errors**, 1693 armed contracts (1685 program-armable). Program digest `e1b460cff91629e7d544673f6d3b79cfeea5fc5619208d4296a3d13346e2cc63`; log `tmp/reload-per-declaration-evidence/focused-a.log`. An initial snapshot run lacked that store link and refused absent current-src before execution; corrected recording is the successful run, not that refusal. No cold gate/platform suite ran.

Leaf progress breakdown from hook A: source build (capture/classify/analyze combined) 1716.805 ms; first program reconciliation transaction 226.943 ms; development row adoption 203.456 ms; reload plus source verification/projection advance 10.484 ms; re-arm 27.179 ms; adoption record 27.401 ms. Full phase vector is in hook-a.edn. Capture/classify subdivision remains for B's measurement; these aggregate labels do not claim that split.

Concurrent lock-removal edits appeared elsewhere in shared cluster.clj during this work. They are excluded from A's staged patch and from A's proof snapshot. No other lane's sessions or files were edited. The earlier main-checkout test attempts used the canonical recording authority; default was never published, stopped, or reset by this lane.


## Commits B, C, D (Opus 5.5 continuation, 2026-09-22)

Commits: `2bd568c08` (B), `da703089b` (C), `af5eea72f` (D).

- **B** — the analyzer marks plain `def`s whose initializer is literal data
  (`literal-value?`, sharing the existing kondo node parse, converting only
  def-headed lists); the indexer stores them as declarations with
  `:seon.fn/constant? true`, and stores `:seon.fn/inline?` true/false on every
  `defn`/`defn-`. `declaration-reload-rule` returns `:seon.reload/var-indirection`
  for a defn with `inline? false`; a missing fact stays unknown and widens. The
  contract/reach/doc detectors exclude constants by fact
  (`seon.issue.detect/declarations`).
- **C** — a `:seon.ns/name` identity present in both values reloads only its own
  namespace (an ns form compiles nothing into callers); created/retired seeds
  dependents. The committed reload clock
  (`docs/prds/agent-platform/research/measure-publication-reloads-2026-09-22.clj`)
  observed reloads through the source-refresh holder that `a102a8403` deleted
  (capture failed: NPE deref of an unresolved var); it now wraps the progress hook
  root for the init request and restores it at report.
- **D** — a `:seon.fn/sym` whose `:seon.program/definition-digest` is equal in both
  values seeds no dependents. Cause found live: the `seon.id/valid?` body edit
  delivered every later declaration of `seon.id` as a changed identity (span
  moved), including the constant `seon.id/default-length`, so C still reloaded
  378 namespaces.

Paths: `src/seon/fn.clj`, `src/seon/fn/analyzer.clj`,
`resources/seon/schemas/seon.fn.edn`, `src/seon/issue/detect.clj`,
`src/seon/cluster.clj` (reload selector hunks only; edited before the file-ownership
ledger assigned cluster.clj elsewhere), `test/seon/cluster/reload_per_declaration_test.clj`,
`test/seon/cluster/publication_delta_test.clj`, `test/seon/cluster/reload_measure.clj`,
`test/seon/issue/detect_test.clj`, the reload clock above.

### Clocks

Committed script, `PUBLICATION_CLOCK_RESUME=1` after its own cold start, at B
(`tmp/reload-per-declaration-evidence/legacy-b/`): first 385, no-change **337**, leaf **6,240**, core
**25,449** ms; reloads 0/0/1/103, rearmed vars 0/0/3/1,688. The script's "docstring"
perl edit changes the **namespace** docstring of both files, so its core row
measured the ns-row widening C removes, not a defn edit.

Hook harness (`test/seon/cluster/reload_measure.clj`, one request per case,
`tmp/reload-per-declaration-evidence/hook-c.edn`) at C: no-change **126**, leaf
(`my.note/notes` docstring) **2,886**, core (`seon.id/valid?` body) **18,885** ms,
reloading 378 namespaces (the harness's `RELOAD_EXPECT_NARROW` assertion failed;
that failure is how D's cause was found). **D's core number is not measured live**:
the scratch adoption to D failed (see limits).

Leaf phases at C (2,886 ms; progress-line deltas, nested spans in brackets):
source build 1,733 [analyze 908, index 520, capture 0.3, classify 0.1];
development reconciliation transactions 313 + 263 [transact 579 total];
branch publication complete 142; branch publication started 79; publication source
identity 74; publication branch head 45; contract projection 44; adoption record 66;
bootstrap configuration 36; JVM instrumentation 25 [re-arm 24]; reload `my.note`
6 [3]. Reload is 0.2 % of the leaf; publication (analysis + two reconciliation
transactions + branch publication) is the cost.

### Tests

In the scratch JVM (armed, `seon.test.published-base` = a directory whose
`data/store` links the scratch root's store; plain `clojure.test`, no recording):
`seon.cluster.reload-per-declaration-test` + `seon.cluster.publication-delta-test`,
13 tests, 45 pass, 0 fail, 3 errors, 21,142 ms. The errors are outside this lane:
two are `fixture-namespace-rows-lack-the-required-definition-digest` (test_support
program-row), one is `selected-rows-reconcile-without-a-manifest` refusing
"Program deletion leaves surviving referrers" for its own test row in a full-population
base. `seon.issue.detect-test` fails at its seed on HEAD's required program digests
(same fixture class, unconverted here). Detector exclusion proven on the published
population instead: 60 public constants under `src`, 0 among 12 contract subjects
and 0 among 119 reach subjects (26,377 ms). `bin/test-fast` could not run: its newest
published base is older than HEAD's partition validator
(`test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md`) and
`bin/test --prepare-head-base` refused on a surviving `seon.render/invoke-selected`
→ `seon.db/render-diff-ai` edge (filed by another lane as
`incremental-publication-refuses-a-deletion-whose-unchanged-caller-edge-survives.md`).

### Schema change proof and limits

The new attributes were installed by the committed script's cold start (from-zero,
readiness 165,327 ms, wall 185,126 ms, before the owner's no-from-zero ruling
reached the lane); 4,406 `inline?` facts and 366 constants were queried on it. **The
incremental proof the ruling asks for is not done.** Incremental adoption of other
edits on that live store: B→C delta (17 files) 31,060 ms; C cluster.clj+test 16,275 ms;
C→D delta refused after 228,300 ms
(`incremental-adoption-refuses-owned-values-plans-under-a-stale-contract.md`), retry
exceeded the 300 s prepl bound; a warm restart from a `c1d2e6d7f` archive then hung in
`seon.cluster.agent/arm!` (`warm-restart-hangs-in-agent-arm-waiting-on-an-atom-monitor.md`).
All over-10-second operations above are defects by the 2026-09-23 rule.

Hook publication: the leaf is still ~2.9 s (hook) / 6.2 s (script, ns docstring) and
the core defn edit unmeasured after D; reload itself is sub-10 ms for a leaf. The
evidence does not show sub-second publication; the orchestrator decides.

No RESET NEEDED: default (pid 51528) was only read. Scratch roots and snapshots of
this lane were stopped and removed; the `reload-b-wt` worktree was removed by
`git worktree remove` after saving its diff (reference-code link changes only).
