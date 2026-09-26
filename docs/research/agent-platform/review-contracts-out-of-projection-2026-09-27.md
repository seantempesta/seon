---
type: design-review
date: 2026-09-27
status: complete; design revision required
reviewer: Astra
design-commit: 21e3a70682ee012376c0ceb2e2113cc5605fe85c
source-baseline: 21e3a70682ee012376c0ceb2e2113cc5605fe85c
verdict: REVISE
task: 12913e5cd6b0
---

# Review: contracts outside the schema projection

## Third-round verdict — a760a6d08 + retention ruling 21e3a7068

**REVISE.** The revised traversal, final-writer selection and slice order address the principal earlier findings.
Root's one-entry-per-population retention choice remains viable. The outstanding issues concern the registry that
entry actually owns, reference derivation when equal populations have different compiled objects, and the
retention policy's concurrency and sizing guarantees. Two read-only witnesses below demonstrate correctness
failures if the proposed key and walker are composed directly with existing projection overlays and callers.
The design needs these boundaries specified before implementation; this verdict does not reopen the choice
between root's three retention options.

Here **D** is `docs/research/agent-platform/contracts-out-of-projection-design-2026-09-27.md` at `21e3a7068`,
including revision `a760a6d08`. **P** is the inspected, uncommitted prototype
`tmp/defn-scan-opus/scoped-refs-probe.clj`. Source citations in this section use `21e3a7068`; Malli remains
`43c6dca8d2d8df3162b13725939954807be4a4ed`. Installed core.cache is **1.1.234**; its cited source paths below are
inside `/Users/sean/.m2/repository/org/clojure/core.cache/1.1.234/core.cache-1.1.234.jar`.
The named landing note still ends with the digest-refusal investigation at lines 249–280. Neither revision commit
updates that note; D and P supply the new design and prototype evidence. Earlier verdicts below are historical.

### N1–N5 disposition

| Finding | Disposition | Evidence and remaining work |
|---|---|---|
| N1: named-schema bindings and host Var schemas | Partially resolved | D:108–129 includes population bindings, direct bindings and an uncached path for non-EDN authored forms. This fixes the original two independently constructed binding populations and avoids keying a Var's old value while compiling its new value. However, the projection's binding member is not necessarily the binding map its registry compiled against. T1 below gives an equal-key/different-validator witness. |
| N2: retention, refusals and symbol independence | Partially resolved | D:78–103 keeps source identities, stored rows and the advisory memo outside the shared product; D:106–107 excludes every refusal. D:354–378 selects a bounded population table. The table must own one actual population, exclude legacy registries and independent equal registry copies, and specify concurrent insertion, dial acquisition and the slot measurement. T1 and T3–T4. |
| N3: scoped traversal and migration | Traversal approach resolved; integration and migration evidence incomplete | D:187–207 and P:5–43 use actual node scope and a scoped recursion guard, including nested reuse. D:214–225,292–294 requires repair and descendant-owner discovery. P:45–65 compares old/new recomputation, not stored arity facts, and silently skips missing compiled entries. Also the new identity test must use the canonical context belonging to the compiled product. T2. |
| N4: writer selection, render relations and stored descriptors | Resolved at design level | D:237–257 includes asserted/retracted function admission, descendant-only changes, affected renderer contracts and declaring-schema closure. D:218–225 keeps stored-field derivation in program; D:261–263 shares the pure early decision without re-entering a checked report. These are implementation obligations, not installed proof. |
| N5: acquisition timing and load direction | The two named findings are resolved | D:83–88 keeps preparation/program downstream of schema; D:176–178,288–302 defers admission acquisition and resource/range removal to S4 and early checks to S3. The binder move is assigned in S1. The new walker argument migration still needs explicit treatment (T2); it is not supplied by the old set-of-names signature. |

### T1 — Bind a population entry to the registry's actual construction inputs

**Cross-population reuse is not yet safe as written.** D:114–115 calls
`:seon.schema.projection/predicate-functions` the bindings the population compiled against. The installed
`with-predicate-functions` only associates that member; it changes neither registry nor compiled schemas
(`src/seon/schema.clj:401–414`). An actual caller uses this overlay for a contract humanizer
(`test/seon/instrument_test.clj:1530–1539`). That caller's addition is legitimate, but the member cannot also
serve as proof of immutable registry construction inputs for every overlay.

Read-only witness: construct A with named `:review/value` bound to `int?`, then overlay its binding member with
`string?`. Construct B with the same forms and `string?` from the start. D's population key is equal for the
overlay and B, while `[:=> [:cat :review/value] :int]` accepts inputs `[1]`/`["x"]` as **[true false]** and
**[false true]**, respectively. The body took **0.372 ms**, MCP event **4 ms**. This is a Malli/key witness;
the proposed seam is not installed.

There is a separate retention issue even when content equality is sound. Two independent registries with equal
construction inputs can supply two different contract forms to the same table. Each contract retains the
registry it was compiled against (`malli/core.cljc:2634–2639`). The same probe confirms equal population keys
but nonidentical retained registries. One table can therefore keep multiple independently allocated populations
while §10 charges one. Content equality is sufficient for semantic reuse; it does not imply object sharing.

**Required boundary:** the entry owns one immutable schema compilation base, its actual resolved construction
inputs and its canonical reference context. Every inner miss compiles against that held base, not the latest
caller's registry. Keep contract-only predicate overlays in the inner binding inputs; distinguish them from the
bindings already captured by named schemas. If a supported input cannot establish that provenance, compile it
uncached rather than publishing it under an inferred population key. A different actual shape/binding/admission/
purity population must select a different entry; an independently rebuilt equal population may share the held
base and products.

This boundary matters **before S4**. D:96 uses the caller's projection registry, while D:288–301 preserves legacy
producers until S4. Today's registry contains both shapes and function contracts (`schema.clj:536–581`). A
read-only probe on default at basis **536872024** found **3,548 shapes and 2,405 symbol registry entries** reachable
from the options of the tiny contract `[:=> [:cat :int] :int]`; the options retain the identical caller registry
(**1.187 ms** body, **4 ms** event). A 512-slot table using that registry can retain the old whole-program
contract population outside its slots. The shape-only key also omits those symbol entries' meanings.

Specify the S1–S3 bridge: use the existing shape-construction owner to supply a closed shape-only base, or leave
legacy-backed compilations uncached until that base exists. Merely filtering the outer registry map is not proof
of release: retained compiled children keep their own options, and the lazy registry's provider closes over its
prepared definitions (`schema.clj:558–565`; `malli/registry.cljc:81–95`). Do not retain a whole projection,
database, source row, prepared wrapper, table handle or advisory memo in an inner product. Direct predicate
callables are permitted inputs, but their captured objects also need an explicit retention accounting boundary.

### T2 — Keep canonical references paired with the compiled world; compare actual stored facts

The prototype correctly fixes the reported shadowing case **within one compilation world**. Its identity test
is not transferable to another independently compiled, equal population. With two fresh canonical maps
`{:review/value (m/schema :int)}`, compile a contract against the first and run P's walker with each map:

```clojure
{:same-forms true
 :own-canonical #{:review/value}
 :other-equal-population-canonical #{}}
```

The body took **0.588 ms**, MCP event **13 ms**. For the second map, the identity check fails and traversal
descends through the first population's canonical target to `:int`, losing the canonical dependency.
This can occur when a cached contract survives eviction/reconstruction of the separately cached projection.

D:78–79 already returns per-arity reference sets with the compiled contract. Preserve that pairing through
`program/contract-facts` and other consumers, or pass the canonical context captured with the compiled value.
Do not re-walk a cache hit against the current caller's canonical object map. Today's `arity-row` recomputes
all three sets (`src/seon/program.cljc:1033–1038`); adding only the compiled-contract argument at D:276 does not
specify this conversion.

Also replace every caller of `compiled-references` in the S1 slice. Its current signature takes only a set of
names (`schema.clj:64–79`). The callers include the preflight reference graph (`710–742`), incremental direct
references (`744–765`), `direct-references` (`882–900`), admission (`1600–1604`), function dependency derivation
(`2211`, `2689`) and program arity facts. The first two deliberately compile against `reference-registry`, whose
canonical targets are reference sentinels, not fully compiled shapes (`686–708`). Pass those authoritative
targets from that exact registry; do not force full canonical compilation before the cycle check, or recover
the canonical scope from a potentially shadowed child. The correction can remain one walker with explicit
inputs; it needs no second index.

**Migration evidence correction:** P:52 queries only function symbols; P:56 skips any missing compiled schema;
P:60–61 compares the two walkers. It never pulls stored arity rows. Thus D:209–213 proves walker agreement for
the compiled entries examined, not that this store's facts are valid. Nor does agreement prove there are no
shadowing registries: a shadow can leave the same reference set. The earlier review's 2,355-row stored-fact
comparison was at another basis and cannot complete this 2,357-row observation by implication.

S3's gate must capture one database value, enumerate every surviving contracted row, refuse missing compiled/
arity evidence, derive canonical facts under its final candidate population, and compare them with pulled stored
facts. Normalize absent many-valued empty sets; do not count them as missing work. Repair through the canonical
writer before indexed selection is authoritative. Keep old/new walker agreement as separate compatibility
evidence. Correct the design's current claim and carry the comparison as a reproducible check in the implementation.

### T3 — Specify concurrent table ownership, eviction and dial lifetime

The root ruling bounds **cache-owned retention**, not every live compiled contract. Arming and preparation
deliberately retain compiled values outside the cache (`src/seon/instrument.clj:608–625`; D:83–88,137–142).
Eviction cannot make those values disappear. Reword D:372–378 accordingly: eviction removes the cache's root;
an active caller or installed wrapper remains a legitimate owner. A weak-reference probe must first release
those owners and await relevant cache maintenance. It must not require immediate garbage collection or invalidate
a callable that is still executing.

Specify the following small protocol rather than leaving “core.cache LRU” to imply it:

1. One mutable LRU holder belongs only to the outer entry. Each operation acquires that entry and releases its
   handle on return; compiled products have no backreference to the holder. Do not put the holder on a long-lived
   projection or wrapper, which would keep all slots after outer eviction.
2. Compute a miss against the acquired entry's base. Atomically publish only an eligible successful product into
   that same holder, retaining at most the configured number of slots. Hits update LRU recency. A refused,
   oversized or host-bypass result is returned without insertion, even transiently.
3. If outer entry E1 is evicted while a miss is compiling, the caller can finish with E1's value. A later lookup
   may create E2. The old completion must not reinsert E1 or put a product compiled against E1 into E2 by doing a
   fresh outer lookup. Temporary retention by active operations is distinct from the steady cache budget.

The dependency supplies atomic LRU updates, but not the whole proposed policy. In core.cache 1.1.234,
`clojure/core/cache/wrapped.clj:25–34` is a plain lookup (no LRU hit update); `:38–70` installs the miss result
through `swap!` and has no retention predicate. Its delay prevents repeated computation during one call's CAS
retries; independent concurrent callers can still compute the same miss. Do not reuse it unchanged for refusal
or oversize filtering, and do not claim one compile per concurrent miss from that API. Derive before the atomic
eligible insertion; allow and measure duplicate work unless a measured cost warrants dependency-supported
single-flight. Outer `seon.cache/lookup` coalesces outer construction, not inner contract compiles
(`src/seon/cache.clj:148–175`).

D:362–371 omits the dial from entry identity while making both threshold and insertion-time weight depend on it.
Specify one effective retention policy for the shared process entry and its acquisition lifetime. If a dial change
must affect existing populations, replace/recharge the outer entry at that owner; do not grow a table under its
old weight. Define what happens when clusters sharing a population request different values. An immutable
construction-time policy is also coherent if its apply semantics are declared. The seam's explicit inputs must
carry that boundary choice; reading a dial at arming without passing it onward is insufficient.

Required implementation evidence: same-key concurrent misses, distinct-key pressure at the threshold, refusal/
oversize nonretention, outer eviction during an inner compile, and policy changes. No concurrency experiment or
eviction/GC experiment was run during this review.

### T4 — Make the slot charge measurable and the admission rule executable

D:368–371 reserves `population charge + capacity × slot charge`; this is a reasonable conservative reservation
for a bounded table with **one held base**. It is not yet a retained-memory guarantee. The existing population
formula is calibrated from one workload (`src/seon/schema.clj:2335–2338`), and `seon.cache` explicitly calls its
weights estimates (`src/seon/cache.clj:14–25`). A count of schema forms does not bound arbitrary forms or callable
captures. The 16 KB slot ceiling is a target awaiting evidence, not a measured result.

S1 must name the measurement tool and object boundary, then commit the measurement script and results:

- Measure the population root once, including its key, registry, validation base and holder overhead. Check
  schema populations with different form sizes and resolved bindings; distinguish shared allocation from objects
  retained only because of this root. Do not describe two independently charged outer entries (projection and
  contract-population) as globally charging shared objects exactly once.
- Measure the incremental slot graph: inner key/authored form, local registries, binding map/captures, compiled
  contract, reference sets and LRU bookkeeping. Exclude only the **same held** population graph, not every object
  of a registry-related class. A whole-graph traversal of the contract alone will include the population and
  cannot substantiate a 16 KB slot.
- Include post-use size. Malli schemas have mutable lazy derived-value caches (`malli/core.cljc:345–359`);
  validators and explainers created after insertion can increase retention. Cover the products these consumers
  actually force, multi-arity/local-recursive contracts, large literals/registries, and branch predicate closures.
- Specify the runtime eligibility calculation. Calibration on samples alone cannot determine that a new authored
  form exceeds the slot charge. Supply a conservative size rule backed by those measurements, or a bounded
  measurement at a miss with measured cost. If eligibility cannot be established, return the compiled product
  uncached. Charge keys too. Never walk the population on an ordinary cache hit.

Report estimate, observed retained size, safety margin, bypass rate and measurement/compile timings separately.
If real contracts exceed the 16 KB target, bypass them as ruled; do not silently raise the ceiling. Large-form
and multiple-population evidence must measure retained roots after dropping probe references, not merely assert
that the LRU count is at most 512. Memory and slot-sizing costs were not measured in this review.

### Slice-1 consequences and remaining document corrections

The next revision can preserve the selected architecture. S1 must establish T1's actual population/base ownership,
T2's compiled-value/reference pairing and complete caller conversion, and T3–T4's bounded table policy and
measurement before enabling retention. Keep S4's producer/admission cut deferred as already specified. S3 must
compare actual stored facts rather than call P's current `compare-stored` as its gate.

Preserve the existing contract normalization when moving the binder: the current armed fallback invokes
`schema/compilable-form` **before** `bind-contract-predicates` (`instrument.clj:600–604`). That normalization also
widens component children and carries generator properties (`schema.clj:274–324`); D:90–98 names only the binder.
State both transformations and compile from the same resolved bindings used in the key. For host compiled-schema
objects, clarify that their own options survive (Malli `schema`, `core.cljc:2605–2608`); bypassing retention is not
the same as refusing every option other than `:registry` as D:95 currently says.

Remove D:106's superseded flat `::function-contract` lookup spelling and D:128's count of those lookups: the
outer cache now counts population loads, not inner contract misses. Use explicit probe-local counts for bypass
and inner compilation evidence. Add config-resource ownership and retention/measurement work to S1's file/size
estimate (D:271,306–319); the current estimate does not price them. None of these corrections requires a new
program registry or a second acceptance path.

### Third-round evidence and limits

`bin/seon status` and MCP `runtime_status` selected default pid **20656**, start instant
**2026-09-26T22:20:53.537Z**, running archive **9bbc3568edf72598b3d0c418e8b93c0e6c0c5a11**. Readiness had no
missing layers, but health was **faulted**, with **3 open faults**, **78 failed-test records**, and **27–28 unknown
failed-test records** during the two observations. These pre-existing observations are not passes or failures
of this unimplemented design. They did not prevent the bounded read-only probes.

All three successful probes used MCP JVM mode, explicit root `/Users/sean/src/seon`, cluster `default`,
`read_only true`, and local bindings only, one probe at a time. `ns-resolve` confirmed `function-contract` is
not installed. The registry-retention probe first had one unmatched delimiter and returned a reader failure;
the corrected form supplied the successful observation above. No tests, restarts, branches, Var changes,
publication or adoption were performed. Allocation and retained memory remain unmeasured. Only this review file
is edited; foreign staged/untracked work is excluded from its commit.

The two correctness witnesses are reproducible with these local forms (the second inlines P's traversal; it does
not load the prototype or define a Var):

```clojure
(let [forms {:review/value [:fn 'review/p]}
      mk (fn [p]
           {:seon.schema.projection/forms forms
            :seon.schema.projection/predicate-functions {'review/p p}
            :seon.schema.projection/registry
            (malli.registry/composite-registry
             {:review/value (malli.core/schema [:fn p])} (malli.core/default-schemas))})
      a (mk int?) b (mk string?)
      overlay (assoc a :seon.schema.projection/predicate-functions {'review/p string?})
      key-of (fn [p] (mapv p [:seon.schema.projection/forms
                              :seon.schema.projection/schema-admissions
                              :seon.schema.projection/pure-predicate-symbols
                              :seon.schema.projection/predicate-functions]))
      compile-in (fn [p form]
                   (malli.core/function-schema form
                    {:registry (:seon.schema.projection/registry p)}))
      form [:=> [:cat :review/value] :int]
      accepts (fn [c]
                (let [v (malli.core/validator (:input (malli.core/-function-info c)))]
                  [(v [1]) (v ["x"])]))
      a2 (mk int?) c1 (compile-in a form)
      c2 (compile-in a2 [:=> [:cat :review/value] :string])]
  {:overlay-key-equal (= (key-of overlay) (key-of b))
   :overlay-inputs (accepts (compile-in overlay form))
   :rebuilt-inputs (accepts (compile-in b form))
   :equal-population-key (= (key-of a) (key-of a2))
   :retained-registries-identical
   (identical? (:registry (malli.core/options c1)) (:registry (malli.core/options c2)))})

(let [ca {:review/value (malli.core/schema :int)}
      cb {:review/value (malli.core/schema :int)}
      ra (malli.registry/composite-registry ca (malli.core/default-schemas))
      compiled (malli.core/function-schema [:=> [:cat :review/value] :int] {:registry ra})]
  (letfn [(children [node]
            (keep (fn [c] (cond (malli.core/schema? c) c
                                (and (vector? c) (malli.core/schema? (peek c))) (peek c)))
                  (malli.core/children node)))
          (refs [canonical node seen acc]
            (if (malli.core/-ref-schema? node)
              (let [k (malli.core/-ref node) scope (:registry (malli.core/options node))]
                (cond
                  (nil? k) (reduce #(refs canonical %2 seen %1) acc (children node))
                  (and (contains? canonical k)
                       (identical? (malli.registry/schema scope k) (get canonical k))) (conj acc k)
                  (contains? seen [scope k]) acc
                  :else (refs canonical (malli.core/deref node) (conj seen [scope k]) acc)))
              (reduce #(refs canonical %2 seen %1) acc (children node))))]
    {:same-forms (= (update-vals ca malli.core/form) (update-vals cb malli.core/form))
     :own-canonical (refs ca compiled #{} #{})
     :other-equal-population-canonical (refs cb compiled #{} #{})}))
```

## Revised-design verdict — a3d6847a1

**REVISE.** The revision completes the reader inventory, separates contract-derived keys, and puts the proposed
check at the final writer. The remaining issues are concrete: the cache key omits bindings behind named schemas;
Var-schema resolution and retention need correction; the reference repair lacks a scope-aware traversal and
migration boundary; writer selection omits function-admission-only changes; and S1 changes admission acquisition
before legacy consumers retire. The separation remains the recommended direction. This is a design verdict,
not approval of an installed implementation.

In this section, **D** means `docs/research/agent-platform/contracts-out-of-projection-design-2026-09-27.md` at
`a3d6847a1`; **DO** means `docs/research/agent-platform/derive-once-design-2026-09-26.md` at `2ef8385b0`.
Source citations use `bef6b105e75291f555de4500f8abeb9dc74a45a2`; the inspected source files remained unchanged
during this review. Malli remains `43c6dca8d2d8df3162b13725939954807be4a4ed`, Datahike
`67b00071e020a7ccf310985d01b28128cdadec34`. The latest defn-scan landing section at this design commit concerns
digest refusals (`landing/defn-scan-opus-2026-09-26.md:249-280`); the R1–R7 changes are specified in D itself.
The initial review below retains its original source baseline, `d1f6a37fb0`, and historical evidence.

### Disposition of R1–R7

| Finding | Disposition | Revised design evidence and remaining condition |
|---|---|---|
| R1: reader migration | Resolved at design level | D:141-162 includes `apply!`, `arm-plan`, `contract-findings`, the six public-query call sites, and threading through `source-producer?`. D:252-254 names the candidate/default/arming regressions. This is an inventory finding; N5 still governs loadable implementation. |
| R2: derived keys and supplier snapshot | Resolved at design level | D:146-156 deletes symbol-only arity/output keys and explicitly adds supplier spec and admission reads; D:255-258 covers historical, branch, absent/present and report-snapshot cases. Reuse remains conditional on fixing the underlying seam key in N1. |
| R3: compile inputs and retention | Partially resolved | D:79-126 fixes mode, direct bindings, provenance, malformed reads and central-cache ownership. Reached schema bindings and actual Var inputs remain unspecified; the stated weight and default retention do not establish the claimed bound. N1–N2. |
| R4: references and fact integrity | Partially resolved | D:190-200 gives the right canonical-scope comparison and requires fact equality; D:214-215 selects both schema closures. The current fold cannot implement that traversal by changing one predicate, and old facts must be repaired before the index becomes authoritative. N3. |
| R5: final writer and renderer coherence | Partially resolved | D:219-233 places one checker on `:db-after`, shares early calls, and removes the constructor callback. Its trigger is too narrow and renderer selection must include contracts whose referenced shapes changed. N4. |
| R6: loadable slices | Partially resolved | D:149-151,250,268-272 correctly delays producer removal and includes the resource/tests. D:237-239 moves admission scoping too early; D:97 names a private upper-layer function from the schema seam. N5. |
| R7: acquisition and cost claims | Resolved in the final-state design; measurement pending | D:174-181 scopes both acquisition and the content key, retaining conservative revision misses. D:93-98 and D:273-319 distinguish validation-base reuse, loads, table derivations and per-mode compiles. Move activation to S4 (N5), and treat the approximately 4 ms scoped join as an estimate, not a measured upper bound. Timing reconciliation follows. |

### N1 — Complete the resolved inputs through named schemas and Var-schema values

D:106-119 names only forms/admissions/purity in `population`, and predicates directly named by the contract in
`bindings`. A named schema can contain a predicate without that predicate appearing in the contract. Two shape
registries can therefore have equal proposed keys and different validators. This is a supported constructor
input: `schema.clj:2332-2339` explicitly puts resolved predicate bindings in the existing projection cache key;
`projection-registry` compiles those bindings at `schema.clj:536-562`.

Concrete witness: forms `{:review/value [:fn review/p]}`, identical admissions/purity, and contract
`[:=> [:cat :review/value] :int]`. Resolve `review/p` to `int?` in one registry and `string?` in the other. Direct
contract bindings are empty in both. A read-only Malli probe returned input acceptance `[true false]` versus
`[false true]` for `[1]` and `["x"]`, while the proposed key components were equal (0.318 ms body, 5 ms MCP event).
This proves the missing registry input, not execution of the proposed seam.

Include the resolved bindings that the reached canonical schema population actually compiled against, as well
as direct contract/local-registry bindings. Using the population's complete binding map is a simpler first cut
than another dependency index. A later content identity may compress these inputs only after establishing the
same guarantee. Preserve live invocation of the same predicate Var; changing its root need not force a new
validator. Replacing that Var or a branch callable must distinguish the compiled world.

The Var-schema clause also names the wrong input type. `mr/var-registry` resolves **Var objects**, not symbols
(`reference-code/malli/src/malli/registry.cljc:67-71`). A read-only probe returned nil for the symbol
`clojure.core/*clojure-version*` and its map value for the Var. Host `:malli/schema` can contain such objects;
calling the seam's input “canonical EDN” does not describe that existing armed path. Resolve the actual Var
inputs, including further Var-schema references reached through their values, and compile from that same
captured resolution. Keying a dereferenced value but compiling against the live Var registry leaves a check/use
gap. Leaving these exceptional host forms uncached is also viable and smaller. State how nested non-EDN schema
objects are handled; their own options survive `m/schema` (`malli/core.cljc:2605-2639`).

### N2 — Bound retained inputs and retain only successful, symbol-independent products

Moving contract versions into `seon.cache` fixes the former unbounded holder ownership. However,
`8192 × arity-count` (D:100-101) ignores the size of the authored form, local registries, resolved schemas and
the population retained through a compiled contract's options. A single arity can contain an arbitrarily large
form. Multiple distinct populations can remain reachable from contract entries after the projection entries
evict. The existing cache weighs only the supplied value once (`cache.clj:115-124,155-164`), so the estimate must
account for retained keys and registry graphs, conservatively if necessary. S1 should measure variable form
sizes and distinct populations, not only many tiny versions under one shared projection.

The proposed four-argument `lookup` defaults to `some?` retention (`cache.clj:145-146`): a returned validation
refusal is a retained value. Such a refusal has no arities, so the proposed weight can be zero; repeated rejected
forms would have no byte-budget charge. D:115 excludes unresolved-symbol failures but must exclude **all**
refusals explicitly. The omitted symbol is safe only for successful compiled products with no symbol/source
identity. Refusals name the requested identity (`schema.clj:1532-1542`), and stored arity rows include function
identity and source bindings (`program.cljc:1024-1071,1100-1123`); neither belongs in this symbol-free cache.

Keep a reusable validation base limited to immutable population products. Do not move its per-operation
`reference-advisories` basic cache (`schema.clj:2033-2055`) into an indefinitely growing holder, or capture one
predicate overlay's bindings in a holder shared by another. The proposed `uncached-var-schema` status counter
(D:118-119) is not an installed cache API: `cache/status` exposes only hits, misses, evictions, bytes and load-ms
(`cache.clj:194-214`). Either price a declared diagnostic addition or use existing evidence.

### N3 — Use scope at traversal time and repair existing reference facts before relying on them

The identity comparison in D:190-192 is appropriate for compiled canonical registries. A read-only probe of the
original shadowing example found canonical-object equality **false** at local `:review/a` and **true** at reached
`:review/b`; the old walker still returned `#{:review/a}` (0.220 ms body, 4 ms MCP event).

It is not one predicate change in the existing fold. `compiled-references` receives only a set of canonical
names (`schema.clj:64-79`), so it must also receive the canonical registry or compiled declarations. Its traversal
options receive **only the reference name** (`malli/core.cljc:2012-2019,2092-2096`), while `fold-walk` invokes its
node callback after traversing children (`schema/internal.cljc:18-36`). Changing the node's emitted item cannot
make an already-pruned local child appear. State the scope-aware traversal using Malli's actual walker/deref
seams, preserving a scoped recursion guard. Malli's built-in `::walked-refs` is name-based; nested scopes reusing
a recursive name need a specific counterexample probe. The existing scoped reference identity in
`schema/internal.cljc:47-54` is a useful local precedent, not a replacement walker already installed.

The previous 2,355-contract comparison used the **old** walker. It cannot certify facts for the corrected walker.
D:200 must include an explicit one-time comparison/repair of existing contracts before S3 trusts the index,
using the canonical writer and final candidate population. Checking only subsequently changed specs leaves old
shadowed references invisible to schema-change selection. Future integrity selection must also reach function
owners when arity/reference children alone are changed or retracted; root-link datoms need not change then.
Use the writer's existing before/after owning-root discovery (`db.clj:4482-4487`) instead of another owner index.

### N4 — Complete final-writer selection and distinguish contract facts from prepared descriptors

The final-report location and absence of a constructor callback are correct. The “only when” list at D:220-221
omits a change to `:seon.schema.admission/source` **on a function row**. A core-to-agent admission change can
make the unchanged contract fail completeness or purity; the existing replacement includes
`function-admission-changes` in affected functions (`schema.clj:2673-2678`). After R7 scopes the projection read,
no projection miss can incidentally recheck that change. Add both admission assertions and retractions to the
checker selection, resolving ownership in before and after values. Include descendant-only fact edits from N3.

Renderer coherence depends on compiled shapes, not only literal renderer contract edits. For example, a
renderer taking named schema `:review/input` can stop accepting an unchanged declaring schema when only
`:review/input` changes. Step 2 selects that renderer's contract; mere compile/admission still succeeds. Step 3
must recheck render relations for those affected contracts too, and for transitively affected declaring schemas,
as well as direct relation edits/removals. The comparison reads both compiled sides at
`schema.clj:1892-1908`. State this selection explicitly within the same checker.

Finally, D:197-199's equality check needs a declared comparison value. `call-preparation/arity-facts` produces
runtime slot descriptors, including compiled schemas (`call_preparation.clj:125-157`); it does not produce the
stored arity rows, which include source signature matching, IDs, argument bindings and refs
(`program.cljc:1033-1123`). Keep that source-dependent derivation in `program/contract-facts`, accepting the
already compiled contract. Compare its canonical stored fields at the writer. Share the decision with early
entrances using explicit candidate inputs; avoid re-entering a checked `db/with` from its own final checker.

### N5 — Correct the two remaining slice-order/load boundaries

1. **Activate schema-only admission acquisition in S4, not S1.** D:237-239 calls the scoped admission edit
   additive, but `projection-admissions` is already called by `projection-from-rows`
   (`schema.clj:2863-2874`). S1's scoping would drop core provenance from function rows while S1–S3 still load
   contracts, causing the loader to default them to agent admission. Scoping `declaration-content-key` early
   likewise makes function-admission-only changes invisible to its content key while they remain projection
   inputs. An unused new helper may land in S1; switch both existing owners with the S4 range/resource cut.
2. **Do not make `seon.schema` require `seon.call-preparation`.** D:97 invokes its private `arity-facts`, but
   `call_preparation.clj:37-47` already requires schema and db. A direct call/require does not load as written.
   The smaller composition is for the schema seam to return the compiled contract and schema-level facts;
   preparation derives its own descriptors from that value under the complete contract key, and program derives
   stored rows with source inputs. If a helper moves instead, name the move and convert all callers in S1.

With those changes, the intended order is sound: S1 adds the pure seam and reference fix; S2 migrates consumers
without changing legacy producer/admission behavior; S3 repairs reference facts and installs the final checker;
S4 switches producers, acquisition, resource, fingerprint and retired APIs/tests together. D:149-151 and D:259-261
should explicitly place early checker calls in S3, since the reader table names them before they exist. This is
a source-level ordering assessment; no slice has been loaded or tested in this review.

### Reconcile approximately 100 ms with 250–300 ms of a steady approximately 600 ms defn

These figures have different scopes and baselines; neither should be subtracted as a proven speedup.

| Evidence | What it establishes |
|---|---|
| `eval-trace-2026-09-26.md:32-45,51-71,262-270` | First defn on pid 89341, archive `bb07fdf08`: 70.437 ms across two loads and 32.852 ms across six contract-projection calls; 13.840 ms projection-build is nested. Counters are inclusive and lack parent/thread spans. Approximately 100 ms is a summary of these named calls, not all consequences of new projection identity. |
| `landing/shrink-db-opus-2026-09-26.md:87-121` | Later pid 17853, `15e6a31c4`, after P1/P4: a second contracted defn on its branch measured 643 ms. The attributed 250–300 ms includes candidate projection work, writer acquisition and rebuilt tables, and post-commit installation/preparation. It is a cross-phase causal estimate, not the same pair of counters. |

Both support eliminating projection churn. Direct construction/load time and downstream misses are different
costs, and the latter can make the affected total larger. The earlier trace itself records post-commit
content-projection at 74.218 ms, including a 51.416 ms load, in another run (`eval-trace:330-334`).

Precision is limited: shrink-db's coarse phase figures sum to approximately 585 ms against 643 ms end to end;
its approximately 130 ms writer phase includes arity work as well as projection work, while the named
`validate-write-report` counter was 53 ms. Its JFR window had only 30 execution samples. Do not count the entire
215 ms evaluation or 150 ms post-commit phase as removable, add overlapping counters, or add 250–300 ms to
the earlier 100 ms. Durability, analysis, contract compilation, gate work and source/arity checks remain.

Update D:29-44,302-308 to describe approximately 100 ms as the older direct-call evidence and 250–300 ms as the
newer estimated affected work including downstream derivations. The scoped admission content read is a further
cost to measure. Keep D's parent/change replay, with separate first-new-symbol and steady-new-symbol samples,
contract replacement/body-only/delete cases, stage timing, loads, table misses, allocations, compiles per mode
and retained memory. No fixed final latency or 250–300 ms saving is established yet.

### Composition with derive-once

The two designs overlap; their projection cuts must be one integration decision. DO:141-143,224-239 retains a
contracts map attached to the base and a `[forms-digest contracts-digest]` projection identity. D:49-64 instead
removes contracts from the projection. Prefer D's selected shape-only boundary for these consumers; retire DO's
`assoc-in [:contracts sym]` recipe rather than shipping both representations. DO's table-sharing and bounded
cache migration still apply to the remaining shape products.

DO's `[forms-digest contract-form-digest]` key is shorthand insufficient for D's two modes, admission source,
resolved registry bindings and Var-schema cases (N1). One shared contract-cache owner must preserve those
inputs. A forms digest is useful only if it includes schema admissions and the actual binding semantics;
predicate code-closure digests alone do not identify distinct branch callables. The fuller key can land before
V23 exists; it needs no second graph or digest owner.

DO slice 3 (`DO:345`) overlaps D S1–S4, and DO slice 5 (`DO:347`) replaces call-arity gate derivation in the same
writer. Keep `program-relation-error` as the one relation authority and compose the arity algorithm there or
beside it in the existing final gate; do not create another acceptance path. Corrected contract-reference facts
are a prerequisite for DO's contract→schema graph edges. Sequence held files through root. The graph's general
soundness, incremental algorithm and render-cache guarantees belong to the separate review; none is approved
here. Savings from these overlapping cuts must be measured once, not added across plans.

### Re-review evidence and limits

`bin/seon status` and MCP `runtime_status` selected default pid **20656**, start
`2026-09-26T22:20:53.537Z`, archive `9bbc3568edf72598b3d0c418e8b93c0e6c0c5a11`. The operator command said
“OK”, but readiness lacked the database connection, SCI context, graph and served web layer; MCP called it
degraded with unknown health. This foreign readiness boundary prevents fresh live-database measurements and
does not prevent reviewing the design. No cause is inferred from missing readiness.

Three serial, read-only MCP JVM forms used explicit root `/Users/sean/src/seon`, cluster `default`, session
`contracts-rereview`. The first found loaded schema/Malli namespaces but **no** `function-contract` or
`program-relation-error` Var (0.010 ms body, 3 ms event). The next two used local Malli schemas for the N1/N3
witnesses; they neither defined Vars nor wrote database facts. Their complete MCP envelopes returned values,
with no diagnostic event. The witness constructions are specified above; no proposed API was exercised.
No tests, restarts, branches, publication, adoption, source edits, allocation measurement or heap measurement
were performed. Only this review file is changed and committed. The next step is revision of N1–N5 and the
cross-design boundary, followed by the already specified implementation proofs when implementation is authorized.

Exact witness forms (line wrapping only; the key equality compares the specified inputs, while compilation
demonstrates the differing registry behavior):

```clojure
(let [started (System/nanoTime)
      forms {:review/value [:fn 'review/p]}
      population [forms {:review/value {:seon.schema.admission/source :core}} #{'review/p}]
      contract [:=> [:cat :review/value] :int]
      compile-with (fn [p]
                     (malli.core/function-schema
                      contract
                      {:registry (malli.registry/composite-registry
                                  {:review/value (malli.core/schema [:fn p])}
                                  (malli.core/default-schemas))}))
      a (compile-with int?) b (compile-with string?)
      accepts (fn [c v] (malli.core/validate (:input (malli.core/-function-info c)) v))
      registry (malli.registry/var-registry)]
  {:same-proposed-key (= [:shape contract :core population {} {}]
                         [:shape contract :core population {} {}])
   :a-inputs [(accepts a [1]) (accepts a ["x"])]
   :b-inputs [(accepts b [1]) (accepts b ["x"])]
   :var-registry-symbol (malli.registry/schema registry 'clojure.core/*clojure-version*)
   :var-registry-var-is-map (map? (malli.registry/schema registry #'clojure.core/*clojure-version*))
   :ms (/ (- (System/nanoTime) started) 1e6)})

(let [started (System/nanoTime)
      registry (malli.registry/composite-registry
                {:review/a (malli.core/schema :int) :review/b (malli.core/schema :string)}
                (malli.core/default-schemas))
      compiled (malli.core/schema [:schema {:registry {:review/a :review/b}} :review/a]
                                  {:registry registry})
      nodes (seon.schema.internal/fold-walk
             compiled
             (fn [node _]
               (when-let [ref (when (malli.core/-ref-schema? node) (malli.core/-ref node))]
                 [{:ref ref
                   :canonical-object?
                   (identical? (malli.registry/schema (:registry (malli.core/options node)) ref)
                               (malli.registry/schema registry ref))}]))
             {:malli.core/walk-schema-refs true :malli.core/walk-refs true})]
  {:old-references (seon.schema/compiled-references compiled #{:review/a :review/b})
   :nodes nodes :string-valid? (malli.core/validate compiled "x")
   :ms (/ (- (System/nanoTime) started) 1e6)})
```

## Initial verdict — ccab0b3a8 (historical)

**REVISE.** Separating function contracts from the data-schema projection addresses the measured cause. The
proposed cut is not yet safe: three production readers are missing, contract-derived caches would become stale,
the new cache has unresolved inputs and unbounded retention, and admission must move to the final writer.
Dropping the contract range alone also does not deliver the promised zero projection loads.

This reviews `contracts-out-of-projection-design-2026-09-27.md` at `ccab0b3a8`, including its measurement landing
note. Source citations below use the baseline above; its named implementation files equal those at the design
commit. Malli is pinned at `43c6dca8d2d8df3162b13725939954807be4a4ed`; Datahike at
`67b00071e020a7ccf310985d01b28128cdadec34`. This review changed only this document. It ran no tests, changed no
Vars, created no branches, and restarted no JVMs. The only runtime evaluations were serial, read-only MCP JVM probes
with explicit root `/Users/sean/src/seon` and cluster `default`.
Other work advanced HEAD and edited shared files during the review. Citations remain pinned to the recorded
baseline; those edits were neither reverted nor included in this review's commit.

## Answers to the six review questions

| Question | Finding |
|---|---|
| Reader completeness | Falsified. Add `instrument/apply!`, `call-preparation/arm-plan`, and `fn/contract-findings`; also migrate the caches and declaration schema described below. |
| Complete cache key | Conditional for fixed registry/options/bindings, false as an unconditional claim about the current holder. Predicate overlays share holders; external Var resolution and `var-registry` are additional inputs. Symbol-only derived keys are definitely incomplete after the cut. |
| Stored references and refusal query | All 2,355 contracted functions in the observed database have arities and matching reference facts. The proposed query executes. Nested refs and ordinary aliases work, but a supported local registry shadow hides a canonical dependency. Final-writer coverage and ongoing fact integrity remain required. |
| Safe slices | Not as written. S2 changes producers before every old consumer is converted; S4 omits a required schema-resource edit. |
| Expected cost | The direction is supported by the measurement. Zero loads is contradicted by the remaining admission-attribute dependency. The latency, allocation and call-count targets remain unverified estimates. |
| Simpler composition | Keep a pure, explicit contract compile/admission seam, pass its compiled value to arming, preparation and fact derivation, and perform cross-row checks once at the final writer. Keep program-dependent renderer checks outside the cached shape constructor. |

## R1 — Complete the reader migration before changing producers

The design's “15” is a grouping of sites rather than a complete accessor inventory. Independent searches covered
the projection's four function members, `projection-with-function-contract`, `function-arities-in`, the four
public function-query APIs, and registry/compile-options accesses in `src`, `script`, and Clojure tests.

These production readers are absent from the design:

| Reader | Evidence and consequence after S4 |
|---|---|
| `seon.instrument/apply!` | `src/seon/instrument.clj:865-883` requires `(mr/schema registry (var-symbol candidate))` before adding a changed Var to `contracts`. Without function registry entries it excludes every changed candidate, then restores its uninstrumented original. Converting `compiled-wrapper` alone does not fix incremental arming. |
| `seon.call-preparation/arm-plan` | `src/seon/call_preparation.clj:757-776` reads the function directly from the registry. Its absence produces nil and drops supplied-default preparation from wrappers; its caller is `instrument.clj:689-699`. Pass the exact compiled contract being armed. This also works before a candidate has a stored row. |
| `seon.fn/contract-findings` | `src/seon/fn.clj:2076-2109,2190-2202` already holds spec strings but inspects a compiled registry entry by function symbol. It must compile that held spec through the new seam. |

The design correctly identifies the other direct production groups: instrumentation selection at
`instrument.clj:596-608,725-771`; the preparation table at `call_preparation.clj:159-169,300,619`; render presence,
return alternatives and retained evidence at `render.clj:411-477,863-919`; debug evidence at
`render/web.clj:960-969`; first-load comparison at `test.clj:2132-2158`; index/publication at
`fn.clj:3078-3143,3246-3277`; turn admission at `turn.clj:1258-1355`; and SCI admission/installation at
`sci/eval.clj:917-956,2038-2074,4802-4819`. Its schema-removal sites also exist.

The production call sites of the four public function-query APIs are `render.clj:279,525,526,676,1239` and
`effect.clj:175`. There are six direct calls in this baseline, not seven. The surrounding render request often
has a database, but `source-producer?` itself receives only projection, symbol and arguments
(`render.clj:272-281`); the database or contract must be threaded through its callers. This is more than changing
the callee's signature. `error/declared-output-validators` is correctly described as already reading an explicit
form (`error.clj:1749-1772`).

Inside `schema.clj`, include the entire dependency chain, not just keyword reads: registry construction
(`524-581`), fingerprint and population options (`2341-2349,2530-2544`), predicate binding (`2105-2121`), declaration
validation/build (`2002-2291`), incremental replacement (`2510-2774`), row parsing and deleted predicates
(`2793-3023`), all three replacement APIs (`3110-3133,3401-3448`), renderer coherence (`1880-1965`), and cached
function arities (`3683-3752`). The public generic validator/explainer APIs take schema keys; they are not
additional function-symbol readers.

**Required regression:** from a projection with no function entries, prove incremental host arming still rejects
bad input, a new SCI candidate receives omitted defaults before persistence, and contract findings inspect its
declared slots. Preserve removal/disarming behavior when the function really has no admitted contract.

## R2 — Fix every contract-derived key and the supplied-default snapshot

The proposed `[::contract-arities sym]` in design §2 is stale as soon as the same symbol gets another contract in
an unchanged projection. The existing `[::function-arities function-symbol]` (`schema.clj:3683-3691`) and
`[::output-alternatives function-symbol]` (`render.clj:417-424`) have the same problem. A correctly keyed inner
compiler cannot repair an outer cache hit. This includes a cached absent contract becoming present.

The supplied-default cache has a separate, concrete dependency loss. `snapshot-read-attributes` derives its read
set from the row query, row attributes, and `schema/projection-attributes`
(`call_preparation.clj:359-378`). The row query mentions supplier symbol but not supplier spec (`111-119`). Today
the projection contributes `:seon.fn/spec`. After S4 that attribute disappears from the snapshot revision key
(`403-419`) and from the final-report reuse decision (`484-505`). A supplier-contract edit can then reuse its old
coherence verdict, even though `derive-snapshot` reads that contract (`285-312`).

**Required fix:** key arities and return alternatives by the actual compiled contract or its complete content
inputs. Give snapshot acquisition its own explicit contract/admission read dependencies; do not inherit them
from the data-shape projection. Keep supplied-default rows and calling environment separate from the compiled
Malli schema. The wrapper depends on the snapshot as well as the schema (`instrument.clj:689-699,732-735,848-857`);
the proposed cache must retain schemas, not prepared wrappers or original callables.

**Required regressions:** same projection, same symbol, two different specs; absent then present; two branches
with equal schemas but different contracts; historical reads after a newer lookup; supplier spec-only change
turning a coherent supplier incoherent; snapshot refresh and `report-snapshot` both refusing that change;
default-row-only changes affecting preparation without recompiling the contract.

## R3 — State and enforce the compile inputs; bound retention

The correct library seam exists. `m/function-schema` delegates to `m/schema` and verifies a function schema
(`reference-code/malli/src/malli/core.cljc:3130-3134`). With an explicit immutable registry, canonical EDN form,
resolved predicate bindings and one fixed option set, compiling against a retained population is sound.
Local `:registry` properties belong to the form; Malli composes them ahead of the supplied registry
(`core.cljc:2545-2554`). Existing compiled schema objects retain their own options (`2605-2608,2634-2639`).

The holder claim nevertheless has counterexamples:

1. `with-predicate-functions` is an `assoc`, not a constructor installing a fresh holder
   (`schema.clj:401-414`). A real caller overlays bindings on a constructed projection
   (`test/seon/instrument_test.clj:1530-1539`). Two maps can share `:compiled` while carrying different predicate
   bindings. `[sym form admission-source]` cannot distinguish them.
2. A predicate absent from the shape population is resolved outside it. `runtime-predicate` returns a Var
   (`schema.clj:146-171`); `compilable-form` also falls back to the loaded namespace (`290-305`). Retaining a Var
   correctly observes a new root of that same Var. It does not prove that a symbol always names that Var after
   `ns-unmap`/namespace removal and re-interning. Explicit branch predicate callables are likewise distinct
   inputs. Resolve the relevant bindings before choosing the cache key, or establish their immutability at a
   named owner. Do not cache a failed unresolved lookup as permanent absence.
3. The armed option tag distinguishes two option *kinds*, not changing inputs within a kind. `mr/var-registry`
   dereferences a Var to obtain a schema (`registry.cljc:67-71`); Malli then compiles that value
   (`core.cljc:2616-2618`). Changing a Var holding `:int` to `:string` changes compilation without changing the
   form's Var object or the shape projection. Host collection covers all loaded namespaces
   (`instrument.clj:789-799`), so the durable EDN constraint cannot simply be assumed for all armed contracts.
4. Predicate/error behavior must retain its existing inputs. `bind-contract-predicates` handles `:error/fn`
   overrides (`instrument.clj:428-457`); `compilable-form` does not perform that map transformation
   (`schema.clj:274-324`). Merely extending predicate discovery with `runtime-predicate` is not equivalent.
   Malli's evaluator also reads loaded qualified values, and supports `::disable-sci`/`::sci-options`
   (`core.cljc:2934-2956`); its function validator can use `::function-checker` (`2145-2173`). The current shape
   constructor supplies only `:registry`, so these options are fixed absent there. Specify the exact accepted
   option set at the new seam; any supported additional options must enter its identity.

Admission is more than compiling Malli: it uses schema admissions, purity and reference traversal
(`schema.clj:1470-1650`). The source enum is the relevant function-admission field in the current checker, but
the caller must obtain it from the same function row. Missing stored provenance currently defaults to agent
(`schema.clj:2869-2874`). `declared-contract` returning only EDN leaves this acquisition unspecified. Preserve
that default and distinguish unreadable/malformed contracts from absence. Do not reuse an admission verdict
under a different predicate binding or purity population.

There is also a retention defect. The holder is an unbounded basic cache (`schema.clj:332-355,367-381`). Once the
projection survives arbitrary defns, it retains every contract version and every branch-only symbol until that
projection dies. The central cache weighs a projection once by its number of schema forms
(`schema.clj:2335-2339`; `cache.clj:115-124,159-164`), so later holder growth does not enter the byte budget.
“Does not outlive the projection” is not a bound when that projection is the deliberately long-lived object.

**Required fix:** use the existing bounded cache for an unbounded stream of contract versions, with a complete
resolved-input key and an explicit retention/weight policy. Alternatively prove a bounded owner for those
entries within the central budget. An armed-mode discriminator alone is insufficient. For mutable Var-schema
inputs, resolving their schema contents into the key or leaving that exceptional compile uncached is smaller
than inventing invalidation. Keep predicate Var invocation live where that is the declared semantics.

**Required regressions:** overlays sharing a base holder; same-Var predicate root replacement versus symbol
removal/recreation; distinct branch predicate bindings; armed Var-schema root changes; `:error/fn` binding
parity; agent/core admission parity; deleted predicates in historical contracts; many versions under one
unchanged projection staying within the configured retention budget. Preserve the existing deleted-predicate
diagnostic behavior (`schema.clj:2808-2840`) when contract predicates leave that scan.

## R4 — The stored facts are present, but reference completeness needs repair

The actual producer calls `compiled-references` for input, output and guard (`program.cljc:1033-1071`), and
`with-contract-facts` is used by file indexing (`fn.clj:3124-3133`) and SCI (`sci/eval.clj:941-953,3847-3856`). The
three attributes are indexed (`resources/seon/schemas/seon.fn.arity.edn:2-10`). Empty reference sets correctly
have no datoms; their absence is not evidence that a zero-ref contract lacks analysis.

Live evidence at basis `536871395`: **2,355 contracted functions, zero missing arity roots, zero mismatches**
between each stored arity's three sets and recomputation from its current projection registry entry. The full
comparison took **407 ms** in MCP JVM mode. The proposed query also ran unchanged for
`:seon.db/database-value`: **554 symbols, 8 ms**, at basis `536871414`. These observations establish presence
and parity for that population; they do not prove the walker or all future writes correct.

Ordinary nested references, explicit `:ref` inside a map, and a local string alias to a canonical key all produced
the expected canonical reference in a separate 3 ms probe. The failure is scoped-name shadowing:

```clojure
;; Canonical forms: {:review/a :int, :review/b :string}
[:=> [:cat [:schema {:registry {:review/a :review/b}} :review/a]] :int]
```

The compiled input accepts `["x"]`, so its meaning depends on canonical `:review/b`. Yet
`compiled-references` returns only `#{:review/a}`. Calling `assert-complete-contract!` with agent admission and
the two core schema admissions returns `[]` for this contract (3 ms). The canonical reverse graph has no edge
from `:review/a` to `:review/b`, so changing/removing `:review/b` never selects this function through §3's query.
The walker stops on a matching *name*, without proving it resolves to the canonical declaration
(`schema.clj:64-79`; Malli registry precedence at `registry.cljc:54-59`, local registry composition at
`core.cljc:2551-2553`). This is an existing walker defect inherited by the proposal, not a new query defect.

Presence alone is also not a continuing invariant. The row schema makes arities and their reference sets optional
(`resources/seon/schemas/seon.fn.edn:158-167`; `seon.fn.arity.edn:23-31`). Backfill checks root/argument-count
presence, not reference-set equality (`fn.clj:3188-3198`). The writer's digest check proves the digest of supplied
row content, not that its reference sets were derived from its spec (`db.clj:4313-4326,4352-4401`).

**Required fix:** repair reference discovery at its owner so local scope is respected, or demonstrate an existing
admission rule that excludes the counterexample; the observed checker does not. Establish canonical-writer
maintenance of exact arity facts on contract replacement and an explicit policy for missing/stale facts before
trusting the index. Select affected contracts using the appropriate before/candidate schema closures and validate
the *surviving candidate* contract, allowing repair or removal in the same transaction. Admission-only schema
changes also matter: the existing selection includes them (`schema.clj:2670-2677`).

The query performs three reference-attribute branches plus owner/symbol joins, with deduplication. “One AVET
probe per key” is not a demonstrated plan or its entire cost. The observed 8 ms proves execution, not a planner
guarantee. Price it as indexed reference lookups plus joins and the affected contracts' compilation/reference
walks; inspect the installed plan before promising tighter bounds.

**Required regressions:** input/output/guard refs, nested maps and unions, canonical alias chains, local aliases,
the shadowing example, local recursive registries, empty-ref contracts, missing/stale arity facts, ref replacement
without residual datoms, schema-admission-only changes, and same-transaction contract repair/removal.

## R5 — Put the refusal at the final writer and keep renderer coherence separate

The “two write paths” are not the authority covering every schema/program change. `validate-write-report`
acquires the final database's projection (`db.clj:4481-4503`). Today that acquisition compiles the contracts and
checks renderer coherence (`schema.clj:2998-3013`). Removing contracts from acquisition removes those checks
from ordinary writes, publication, adoption and merge paths that do not call `turn/row-tx` or SCI admission.
`write-report-error` validates stored values, digests, renderer *existence* and call arities
(`db.clj:4403-4479`); `write-render-target-error` checks only whether the named function exists
(`4086-4124`). Neither is a substitute for contract compilation/coherence.

Renderer coherence is a relation between schema rows and function contracts. Today a contract-only change
rechecks every schema naming the affected renderer (`schema.clj:2763-2773`). A `contract-of` callback supplied
only when the shape projection builds will not run when a renderer's contract changes and the shapes stay
identical. Keeping that callback in constructor options would also introduce program/closure identity into the
cache key the design is trying to remove (`schema.clj:2332-2339`).

**Required fix:** make the final-report gate mandatory in S3, checking newly changed contracts and contracts
affected by schema changes against `:db-after`. Share that pure checking operation with early admission where
useful. Trigger renderer coherence on either side of its relation changing; perform it outside the cached shape
constructor. Cover simultaneous repairs and deletions using final rows. No second program registry is needed.

**Required regressions:** direct admitted database write and publication/merge schema changes; invalid new spec
without a schema edit; renderer spec changes from accepting its declared shape to rejecting it; renderer contract
removal; same-transaction renderer/schema repair. An unrelated data write must perform none of these checks.

## R6 — Revise the slice boundaries and declaration resource together

S2 already changes index/publication to build shapes only and SCI to keep the previous projection. Meanwhile
`function-arities-in` and renderer checks inside `schema.clj` are scheduled in §4/S4, and the missing readers in
R1 are unscheduled. Saying “the projection still holds contracts” does not preserve them in projections those
converted producers stop populating. In particular a new candidate's arming plan needs its new compiled contract,
not the previous symbol entry or a database row that does not exist yet.

S4 is explicitly assigned only `schema.clj`/fingerprint, but
`resources/seon/schemas/seon.schema.edn:58-63` requires `:seon.schema/function-contract-rows` in
`:seon.schema/projection-input`. `projection-from-rows` declares that input schema (`schema.clj:2848-2854`).
Dropping the range without changing its declaration makes the armed loader reject its own input.

**Required order:** first add the explicit compile/admission seam with complete inputs; then convert every
consumer, including all derived keys and public API callers, while preserving legacy producer behavior; next
install final-writer contract/reference/renderer validation; finally remove producer population, range, old APIs
and required resource members together. Do not stop producers earlier. Convert tests referencing the removed
APIs/maps in that same cut, including the split schema acquisition/nil-contract tests, publication, SCI,
instrumentation, render coverage and test-accretion fixtures. No runtime load proof was run for this review.

S1 should also specify where database reads for `declared-contract` live. `seon.program` currently obtains its
database dependency indirectly; `seon.db` deliberately late-resolves program helpers (`db.clj:4273-4281`). Keep
that load direction explicit, use the database owner, and return its read refusals. A pure form compiler needs no
database dependency.

## R7 — Narrow the performance promise to what the proposed work actually removes

The measured contrast is credible evidence for separating contracts: the landing note records four definitions
of each kind on pid 89341, p50 **134 ms versus 757 ms**, **157 MB versus 1.21 GB allocated**, and approximately
**26,300 versus 189,900 armed calls**. These are historical measurements, not measurements of this design or the
currently running root.

However, `projection-attributes` retains `:seon.schema.admission/source` after dropping the function range
(`schema.clj:3063-3067`). New function rows receive that attribute (`program.cljc:1242-1243`; file rows at
`fn.clj:3328-3329`). Both `projection-cache-key` and `declaration-content-key` consume the entire attribute,
including non-schema entities (`db.clj:1303-1326,1328-1360`). A new contracted function therefore still changes
both keys. Merely filtering the loader's admission join to schema identities does not filter those keys. A load
may return the same projection via the unchanged-input path (`schema.clj:2620-2626`), but that is not zero loads.
Distinguish new definitions, edits that leave admission datoms unchanged, and deletion.

**Required fix:** make the projection's semantic admission inputs schema-scoped at acquisition/content-key
ownership, while preserving invalidation for actual schema admission changes. Update the “no db.clj edit” claim
and distinguish conservative revision misses from expensive loads. Measure both projection identity and actual
derivations; equality alone is insufficient.

The listed load cost (about 63-88 ms per defn at 1.75 loads) and first-use tables (up to about 105 ms per new
projection) do not independently account for the full roughly 620 ms difference. They may also overlap other
profiled costs. The 150-250 ms / 200 MB / 30-40k targets need an installed replay rather than additive subtraction.
The claim of one compile needs plumbing: `program/contract-facts` currently compiles again
(`program.cljc:1096-1099`), and arming has a distinct option set. Reuse the new compiled result where semantics
match; label remaining compiles explicitly. Likewise avoid rebuilding the population-wide validation base or
`core-references?` scan for every contract (`schema.clj:2030-2065`).

**Required regression/measurement:** through the actual agent path, define a fresh contracted symbol, replace
its contract, change only its body, delete it, and repeat on a branch sharing the population. Count projection
loads, content-key work, table derivations and contract compiles. Record warm p50, allocation, armed calls and
retained memory under repeated versions; preserve the same parent/change probe and workload. No such replay or
memory measurement was performed here.

## Smaller composition and acceptance condition

Keep the selected separation. The smaller implementation within that direction is to compile an explicitly
supplied form once, pass the compiled schema to existing `arity-facts`, contract-fact derivation and wrapper
construction, and have database owners resolve rows only where callers lack them. Use the existing central
cache, with resolved immutable inputs, for contract products that need reuse. Keep shape construction pure over
shape inputs; let one final-writer checker own program/schema relations. Early checks can call that same checker
with candidate values. This avoids a program callback retained by each projection, duplicate symbol lookups,
duplicate compilation and separate refusal policies at each entrance.

The design's deletion estimate is not yet an implementation estimate: the missing readers, snapshot dependencies,
cache retention, reference integrity, final writer and resource migration are outside its priced substitutions.
Recount after revising those sections. Acceptance requires a revised design naming those owners and slice
boundaries, followed by the listed behavior regressions and installed cost evidence during implementation.

## Read-only evidence and limits

Initial `bin/seon status` / MCP `runtime_status` reported pid **12212**, start instant
`2026-09-26T22:05:18.940Z`, degraded readiness and no database connection. The root was still advancing during
this review; a later read found a real connection. No recovery action was taken. The later loaded program identity
was `6ab841c1-c082-5523-a8b5-5cd5fa6ab705`. This does not establish whole-runtime health or installation of the
proposed API. `function-contract` and `declared-contract` remain proposed APIs.

The decisive stored-fact probe was the following single JVM form (407 ms; result shown above):

```clojure
(let [database @(seon.cluster.boot/connection "default")
      projection (seon.db/carried-projection database)
      registry (:seon.schema.projection/registry projection)
      canonical (set (keys (:seon.schema.projection/forms projection)))
      rows (seon.db/q
            '[:find ?sym
              (pull ?f [{:seon.fn/arities
                         [:seon.fn.arity/order :seon.fn.arity/input-refs
                          :seon.fn.arity/output-refs :seon.fn.arity/guard-refs]}])
              :where [?f :seon.fn/spec _] [?f :seon.fn/sym ?sym]] database)
      mismatches
      (into []
            (keep
             (fn [[sym row]]
               (let [compiled (malli.registry/schema registry sym)
                     expected
                     (when compiled
                       (mapv
                        (fn [arity]
                          (let [info (malli.core/-function-info arity)]
                            (mapv (fn [role]
                                    (if-let [schema (get info role)]
                                      (seon.schema/compiled-references schema canonical) #{}))
                                  [:input :output :guard])))
                        (malli.core/-function-schema-arities compiled)))
                     actual
                     (mapv (fn [arity]
                             (mapv #(set (get arity %))
                                   [:seon.fn.arity/input-refs :seon.fn.arity/output-refs
                                    :seon.fn.arity/guard-refs]))
                           (sort-by :seon.fn.arity/order (:seon.fn/arities row)))]
                 (when (not= expected actual)
                   {:sym sym :expected expected :actual actual}))))
            rows)]
  {:basis (:max-tx database) :contracted (count rows)
   :mismatch-count (count mismatches) :mismatches (vec (take 10 mismatches))})
```

The reference counterexample and admission check were this single JVM form (3 ms):

```clojure
(let [forms {:review/a :int :review/b :string}
      registry (malli.registry/fast-registry
                (merge (malli.core/default-schemas) (update-vals forms malli.core/schema)))
      options {:registry registry}
      form [:=> [:cat [:schema {:registry {:review/a :review/b}} :review/a]] :int]
      compiled (malli.core/function-schema form options)]
  {:admission
   (seon.schema/assert-complete-contract!
    {:seon.schema/identity 'review/f :seon.schema/definition form
     :seon.schema/forms forms :seon.schema/admission {:seon.schema.admission/source :agent}
     :seon.schema/admissions {:review/a {:seon.schema.admission/source :core}
                              :review/b {:seon.schema.admission/source :core}}
     :seon.schema/compiled compiled
     :seon.schema/compiled-schemas (update-vals forms malli.core/schema)
     :seon.schema/schema-dependencies {:review/a #{} :review/b #{}}
     :seon.schema/registry registry :seon.schema/compile-options options
     :seon.schema/canonical-keys (set (keys forms))})
   :refs (seon.schema/compiled-references compiled (set (keys forms)))})
;; => {:admission [], :refs #{:review/a}}
```

An initial multi-case probe put explicit `:ref` directly in a seqex slot and failed with
`:malli.core/potentially-recursive-seqex`; it produced no successful batch result. The corrected probe put that
ref inside a map and returned `#{:review/b}` for nested, explicit-ref and ordinary local-alias cases, versus
`#{:review/a}` for the shadowed case. No stored fixture or production mutation was used. Probe timings are MCP
evaluation times, not end-to-end agent performance. Allocation and retained memory were not measured.
