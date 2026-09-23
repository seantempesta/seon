---
type: research
status: proposed-for-independent-review
created: 2026-09-23
tags: [agent-platform, contracts, refusal, canonicalization]
---

# Preserve named predicates in contract-refusal evidence

The system already converts named predicates to callable Vars and back to durable schema data. The smallest composition is to preserve the predicate name in the owning declaration, then use that existing compilation/canonicalization path; a second canonicalizer cannot recover information discarded by evaluated metadata.

**Decision for review:** repair the named predicate declarations in `seon.schema.internal`, retain the existing refusal normalizer, repair the producer fixture, and add one loaded-contract refusal regression. This is design only, not approval to implement. The real canonicalization failure is reproduced; correcting the fixture alone would conceal it.

## Evidence and mechanism

Baseline checkout: `4a1908656` on `refactor/agent-platform`. Source references below describe the inspected checkout; instrumentation has concurrent edits. Runtime is separately identified below.

- Assignment receipt: `tmp/orchestrator/proof-value2.txt`, run `190f30cce76f`: 3 executed, 35 reused, 268 passes, 0 failures, 2 errors, 10,632 ms. Both errors belong to `seon.render.value-test/declared-producers-still-have-absolute-precedence`, one each for AI and HTML. This is not two failing members.
- Prior diagnosis: [value-test reds](value-test-reds-2026-09-23.md), committed in `21298b74a`, producer-precedence row and smallest-fix item 3. This design resolves its open question about where named predicate identity is lost.
- Related existing defect class: [search contract predicate durability](../../seon/issues/a-search-contract-predicate-cannot-be-made-durable.md). It records the same evaluated-metadata loss and quoted-symbol repair (`375f79b01`); it does not establish that this runtime has its historical generator/SCI failure.
- History: `c6db6b3586` changed refusal fingerprint use to dependency-aware normalized fingerprints; its patch already had normalization calls before the change, so it is not proof that this commit first introduced normalization. `8704e5ed5` preserves generator identity and adds `named-generators-survive-compilation-in-refusal-evidence`; reuse that path rather than invent another schema walker.

The exact path is:

1. `test/seon/render/value_test.clj:379–398` globally substitutes `matching-shapes-in` with a row naming `:fixture/declared-producer`, without installing its schema. The value resembles a refusal, and the assertions require its declared AI/HTML producer to take precedence.
2. `src/seon/render.clj:368–375` calculates producer specificity through the compiled registry entry. That synthetic key has none; `internal/entity-entries` receives nil. Producer precedence exposes the defect because discovery now needs the real declaration before it can select either producer.
3. `src/seon/schema/internal.cljc:71–75` correctly requires a compiled Malli schema. Its metadata writes `[:fn malli.core/schema?]` unquoted. Clojure evaluates metadata, leaving the raw function object, not the symbol or Var. The read-only probe establishes object identity with the host `malli.core/schema?` root.
4. `src/seon/instrument.clj:640–655` can compile loaded metadata when there is no retained contract. `schema/compilable-form` (`src/seon/schema.clj:315–331`) deliberately leaves an already callable predicate alone. Compilation therefore succeeds, validation rejects nil, and the compiled schema still contains the unnamed raw root.
5. `boundary-refusal` (`src/seon/instrument.clj:586–637`) uses `m/explain`, then `schema-shape/normalized-form` for the checked contract and problem schemas. `src/seon/fn/schema_shape.clj:75–81,171–198` reads `m/form` and calls `schema/canonical-definition`.
6. `src/seon/schema.clj:635–648` can recover a Var symbol or match a callable by identity in the explicitly supplied predicate map. It cannot name this raw host root from a map containing a different callable (the live database projection supplies an SCI Var). It correctly refuses `:seon.schema/unnamed-callable`. That secondary exception replaces the wanted invalid-input diagnostic on the loaded runtime.

A current database projection does carry the durable contract with `malli.core/schema?` as a symbol, and its compiled input canonicalizes successfully. Passing that projection around a call does not prove that the already installed host wrapper was compiled from it. The direct loaded-Var probe still fails. This distinction prevents a false fix proved only against the database's good contract.

The working-tree `compiled-wrapper` already contains an in-flight fallback to `error.refusal/diagnostic` when refusal construction fails (`instrument.clj:665–693`). It preserves a core fault, but cannot make the original expected-shape evidence valid. It is foreign work, not this fix or evidence that the loaded runtime has adopted it. `src/seon/error/refusal.clj:78–117` owns full cause-chain construction; leave it there.

## Smallest change and cost, before implementation

Change the owning declaration's predicate to `[:fn 'malli.core/schema?]`. Apply the same mechanical quoting rule to the `malli.core/schema?` contract occurrences in `src/seon/schema/internal.cljc` so sibling internal boundaries do not retain the identical defect. The minimum triggering change is the two predicate occurrences in `entity-entries`; the same-file class conversion adds no mechanism or lines. Do not change runtime predicate invocations.

`schema/compilable-form` already resolves the symbol using the supplied bindings or loaded Var. `canonical-definition` already recovers the symbol. `normalized-form` remains the fingerprint owner. The comparison probe below proves the existing wrapper constructs a valid contract error with this input, without changing any default Var. No change to `canonical-definition`, `normalized-form`, instrumentation, dependency source, or refusal serialization is needed for this observed class. A wider repository conversion is outside this bounded fix; discovered siblings should use the existing defect authority and their file owners.

Simplest alternatives considered: repairing only the fixture hides the production failure; teaching `entity-entries` to accept nil weakens its contract; scanning global Vars to name arbitrary function objects invents a second identity mechanism; catching in the renderer cannot restore the lost contract evidence. Carrying another authored-schema table in the wrapper duplicates information the declaration can already preserve. The named-declaration conversion is smaller and is demonstrated at the actual wrapper boundary.

**Complexity:** zero new work on successful invocations. Existing changed-contract preparation walks that contract, O(S) in its form size, resolving each named predicate by map/Var lookup. On refusal, existing normalization traverses the relevant schema and reachable schema references; its supplied predicate bindings are sorted, O(P log P), with up to O(P) identity lookup per raw callable. The repair does not add scans, caches, new normalization passes, whole-program work, or per-call reconstruction. Target remains sub-second for the bounded refusal. Measured local raw/named comparison: 1.680083 ms total; allocation/heap was not measured, and no memory improvement is claimed. The change adds no retained data structure.

Canonical EDN printing is a different concern: `src/seon/sci/admit.clj:85–97` resets ambient print bindings but does not turn an unnamed callable into a named predicate. It cannot repair this failure. Preserve that existing printer wherever serialization is required; do not add another one or route schema identity through presentation strings. `value/prepare` still applies `print/fit` once for AI (`src/seon/render/value.clj:605–615`); the exception floor (`:547–563`) remains presentation-only. No clipping or HTML behavior changes.

Errors remain declared flat data. `src/seon/error.clj:151` owns signature derivation; this design adds no ID, stamp, discriminator or deduplication path. Panic/record disposition and whole-cause reporting remain with the existing owners. Do not downgrade an actual reporter failure into an ordinary contract violation.

## Regression and implementation boundary

1. Keep `declared-producers-still-have-absolute-precedence` and both AI/HTML assertions. Install a real synthetic schema and its render declarations with the canonical fixture (`test/seon/test_support.clj:652–668` supports `:seon.test-support/extra-schema`), positively establish its registry entry and match, and remove the global `with-redefs`. Use valid declared renderer functions through the existing fixture declaration path. Keep the error-shaped candidate that makes precedence meaningful. This fixes the stale setup, while the separate regression prevents it from hiding the real defect.
2. Add one class regression beside `named-generators-survive-compilation-in-refusal-evidence` (`test/seon/instrument_test.clj:1373`). Read the actual loaded metadata of `entity-entries`, construct a local wrapper via the existing `compiled-wrapper` seam under a fresh probe symbol absent from the projection, and invoke it with nil. This forces the metadata compilation path rather than silently selecting the good retained database contract. Assert `:seon.instrument/contract-error` validation, `:input`, the original operation, one explanation, its expected-shape fingerprint and nil observation. Assert a valid compiled schema is accepted. No replacement of a production Var. The test must fail on today's metadata and pass after the declaration conversion.
3. In that regression, cover the contract family's named-predicate round-trip using the existing `compilable-form` → `m/schema` → `m/form` → `canonical-definition` composition. Preserve the existing generator regression and the rule that genuinely anonymous callables refuse. No second anonymous-function naming policy.
4. After independent review and ownership release: adopt and arm changed declarations through the installed owner; repeat the direct nil-schema probe, then use one focused `seon.test/run` request on the implementation branch for the repaired producer test and class regression. Run affected integration at the cut boundary. Report tested source identity, executions versus reuse, positive arming evidence, and timings. No `bin/test` gate, scratch JVM, reset, or global redefinition. No green or publication claim is made by this design.

| Implementation path | Intended edit | Observed ownership / working state |
|---|---|---|
| `src/seon/schema/internal.cljc` | Quote named schema predicates in metadata | Clean; no explicit hold found in the inspected ledger. Recheck before assigning. |
| `test/seon/instrument_test.clj` | One metadata-path refusal class regression | Dirty (+33 lines at inspection); `a1-arming` ledger hold. Route to holder or wait. |
| `test/seon/render/value_test.clj` | Repair real producer fixture, preserve assertions | Clean in git status, but ledger assigns `agent-branch-sweep`; clean is not unheld. |

`src/seon/instrument.clj` is dirty (+37/−13) and named by both `a1-arming` and `opus-leak-fix` ledger entries; resolve that ownership before any future change. `src/seon/schema.clj` is clean but also held by `opus-leak-fix`. Neither needs an edit for this design. No other lane was contacted or altered.

Estimated implementation size: src net 0 lines; modest test-only growth for the additional behavior class, offset partly by deleting the fake producer setup. That test growth buys coverage of the specific loaded-metadata path the existing generator regression does not establish. Exact counts belong to the implementation landing. This design's actual src/test delta is 0/0.

## Read-only REPL evidence

`bin/seon status` and MCP `runtime_status` selected default, pid **36741**, start **2026-09-23T17:10:34.815Z**, loaded source `/Users/sean/src/seon/data/source/11cc76396a21caaab6f33d947d2be6d2d6ef9c7b`. Hook publication is off. Runtime reports 16 error signatures, 34 errored receipts, one failed run and one unknown failed-test count: reachable is not green. Its pre-existing profile includes `seon.test/run` 10,631 ms and member-result max 3,117 ms. These exceed the required bounds: the owning runner work must explain proportionality and fix the cost before continuation; the receipt above records the over-ten-second defect here, not as an accepted cost of this design. No new test run or slow operation was started.

All probes: explicit root `/Users/sean/src/seon`, cluster `default`, JVM mode, namespace `diagnosis.refusal-canonicalization-20260923`, private session `refusal-canonicalization-design`, `read_only true`, timeout 5,000 ms. No defs, reload, adoption, default Var replacement, branch mutation or provider request. The MCP transport persisted oversized diagnostic envelopes as blobs; read-only here describes evaluated forms, not a transport no-write guarantee.

Exact owner reproduction:

```clojure
(let [start (System/nanoTime)
      projection (seon.schema/projection-from-database
                  @(seon.cluster.boot/connection "default"))]
  {:result (try
             (seon.schema/call-with-projection
              projection (fn [] (seon.schema.internal/entity-entries nil)))
             (catch Throwable t (Throwable->map t)))
   :ms (/ (- (System/nanoTime) start) 1e6)})
```

Body **0.702 ms**, prepl **3 ms**. Complete throwable map retained in blob `bccdaea12f216730b269c80df50948c8edf0af63a9989e1e5347c3ad8cdb2dce` (43,429 bytes). Its cause is “A durable Malli definition contains an unnamed callable.”, with `:seon.schema/noncanonical-definition :seon.schema/unnamed-callable` and raw `malli.core/schema?`. A second probe returning every Seon frame and the entire `:via` chain took **0.604125 ms**, prepl **2 ms**; blob `4ade6558d7367c5d2b0503d0d19f2ea6f935f203a2e45f2a7e6916f40ff2658f`. Loaded stack: `canonical-definition:643` → `authored-form:81` → `normalized-form:184` → `boundary-refusal:630` → installed wrapper. The chain has one exception, so there is no hidden original throwable to recover from it.

Exact local wrapper comparison (no installed Var replacement):

```clojure
(let [start (System/nanoTime)
      projection (seon.schema/projection-from-database
                  @(seon.cluster.boot/connection "default"))
      caps (seon.config/result-caps seon.config/defaults)
      attempt
      (fn [predicate]
        (let [wrapped
              (#'seon.instrument/compiled-wrapper
               projection 'diagnosis.refusal-canonicalization-20260923/probe
               [:=> [:cat [:fn predicate]] :boolean] (constantly true) caps
               {:seon.config/on-core-error :panic
                :seon.config.error/max-evidence-bytes 8192})]
          (try (wrapped nil)
               (catch Throwable t
                 {:class (str (class t)) :message (ex-message t) :data (ex-data t)
                  :contract-error?
                  ((seon.schema/projection-validator
                    projection :seon.instrument/contract-error) (ex-data t))}))))]
  {:raw (attempt malli.core/schema?)
   :named (attempt 'malli.core/schema?)
   :ms (/ (- (System/nanoTime) start) 1e6)})
```

Body **1.680083 ms**, prepl **3 ms**. Raw: unnamed-callable exception, contract-error? false. Named: contract-error? true, check input, original probe operation, one explanation, checked-contract fingerprint `4a5bd89880576ebe781457dbb15c3abb4c7a67138a2f1f2ac58ab77bbc16924c`, problem fingerprint `5962e5e86952e00e95f9410f3e9864e8b755d8dd19f09c304bcebf24a47cad26`, nil observation, evidence bound 8192. Full envelope blob `42965206c928e7a2d5700912ab35ec4236a82111aa89f32ccf36056ecb0f274d` (10,113 bytes). Inspection of the stored contract and successful normalization took **1.072083 ms**, prepl **3 ms**; explicit raw-metadata canonicalization with `{'malli.core/schema? malli.core/schema?}` and compilation round-trip took **0.363583 ms**, prepl **2 ms**. These are diagnostics, not recorded tests or browser evidence.

Dependency seam: committed Malli gitlink **8725a8cbd9d595f4a970ce53a2eefdbe7211b96d**; checkout is dirty, so no claim that its current bytes or this pin identify the loaded dependency. Inspected upstream APIs `schema`, `form`, `validator`, and `explainer` at `reference-code/malli/src/malli/core.cljc:2562–2584,2632–2656` accept the supplied form/registry and expose compiled form data; validators/explainers use Malli's own cache. No fork-specific feature is required. The named-predicate preparation and inverse are Seon's existing responsibility. Recompute when a declaration changes; preserve the compiled value between calls.

## Landing boundary

Only this research note is committed. No src/test edits, gate, push, implementation or independent review occurred. No publication clock row is needed for a documentation-only slice. The next step is the independently reviewed declaration/fixture change under the file holds above.
