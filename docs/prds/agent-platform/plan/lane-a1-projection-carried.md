---
type: spec
status: implementation specification; acceptance proofs pending
created: 2026-09-21
lane: A1
tags: [agent-platform, malli, projection, instrumentation, call-preparation, schema-shape, admission]
---

# Lane A1 — the projection is read, never rebuilt

Owned: `src/seon/schema.clj`, `src/seon/schema/{admission,edn}.clj`
(`schema/datahike.clj` is A2's), `src/seon/instrument.clj`,
`src/seon/call_preparation.clj`, `src/seon/test/arm.clj`,
`src/seon/fn/schema_shape.clj`, the 18 test files in §7, and
`reference-code/malli` (our fork, gitlink `606083c5`). Seam with A2:
`seon.db/carried-projection` (`src/seon/db.clj:1219-1225`), read, never
edited. Source citations and historical measurements use snapshot `209a6652a`; paths
`schema.clj`, `instrument.clj`, `call_preparation.clj`, `schema_shape.clj`,
`admission.clj`, `edn.clj`, `db.clj` mean `src/seon/…`; `core.cljc`,
`registry.cljc`, `error.cljc` mean `reference-code/malli/src/malli/…`.
`cluster.clj` and `fn.clj` citations use that HEAD. Measurements below are dated baseline evidence, not proof of the proposed implementation.

Evidence: [A1 data pack](../research/data-pack-a1-malli-2026-09-21.md) and [durable rulings](../research/durable-goals-and-rulings-2026-09-21.md) supply the dated numbers and ruling citations below. Required implementation behavior is stated in this specification.

## 0. For the owner: what was dumb, and the simpler way

**What the code does today.** Every cluster's database value already carries
its compiled Malli schemas as metadata (`db.clj:249-271`), and
`seon.db/carried-projection` reads it after walking a temporal value to its
origin (`db.clj:1156-1170`, `:1219-1225`). Beside that one value we built the
same thing again, repeatedly. `schema/projection-from-database` re-queries
3,337 schema rows and 1,555 contract rows and recompiles them — 589 ms on the
warm `default` JVM, 3,996 ms of the 4,648 ms test fixture (§1). Arming a
function compiles a SECOND whole population from the classpath as a fallback
wrapper (`instrument.clj:995-997`), recompiles that function's own contract
(`:742-754`), and on EVERY call of each of the 1,570 armed functions scans
the arguments three ways for a projection to validate against
(`:604-619`, `:880-889`). Call preparation answers "what does this function
accept" with eight Datalog queries over mirror rows (`call_preparation.clj:604-713`)
and a per-symbol plan cache keyed by a transaction it re-queries
(`:591-602`, `:1004`), while the compiled contract already answers by three
Malli reads. A 36-line hand walker canonicalises schema forms for
fingerprints (`schema_shape.clj:21-56`) where `m/ast` is the canonical form.
Registry assembly copies all 5,040 compiled entries into a fresh `HashMap`
even when one declaration changed (`schema.clj:454-503`,
`registry.cljc:17-22`).

**Why that shape is wrong.** Each is a mirror kept beside the authority, plus
machinery to keep the mirror current, plus tests policing the machinery's
cost. The work is proportional to the PROGRAM (5,040 entries) where the change
is one declaration, and the wrapper guards its own cost on every call because
it does not trust the world it was armed in. Counting compilations does not
prove the work is small: the registry copy and the shape-projection rebuild
walk the population with zero compilations.

**The simpler way, as data flow.** One admitted projection carries compiled
schemas. Ordinary reads reuse it. Cold acquisition reads the exact database
program once; updates reuse unaffected compiled objects and touch only the
changed declaration and its reverse dependents. Arming reads
`(mr/schema registry sym)` and closes over the retained contract; call
preparation derives plans from that same object and keeps them until a
supplier row or a contract changes; the fingerprint hashes a normalised
`m/ast`; error text comes from Malli's table plus a small data overlay. This
lane also measures and removes the population work that survives a small
compilation count: entries copied, shape rows walked, contracts visited.

## 1. Goal and the numbers that prove it

| Number | Historical baseline (2026-09-21) | Target | Source |
|---|---|---|---|
| Projection acquisition at the seam, warm `default` JVM | 588.99 ms rebuild; 77.63 ms with the carried value handed as reusable; carried forms/contracts/fingerprint all `=` | a metadata read | §4 form 1, 2026-09-21 |
| Same, cold fixture base | 3,995.96 ms of 4,647.8 ms | ≈ 650 ms residual is a hypothesis for B4 to verify, not an A1 bound | data-pack-a1 §1 quoting `test-system-fork-2026-09-23.md:104`, verified by data-pack-b4 §2 |
| Registry entries copied into a `HashMap` per generation build | **5,040** (3,337 forms + 1,555 contracts + Malli defaults) | changed declarations + reverse closure; count copied entries, never only compilations | §4 form 2 |
| Named-declaration compilations per arming pass over an unchanged generation | 1 classpath population (`instrument.clj:995-997`, only when Vars are pending) + 1 per pending Var (`:742-754`) | 0 on first arming against an admitted projection AND on unchanged re-arming, counted separately | goals note `:131`; §4 form 3 |
| Per-call work in the armed wrapper | 11 items (data-pack-c1 §1): argument scan ×3, cache lookup, arity scan, … | rest-arg seq, `m/-instrument` input/output/guard, `try` frame | read of `arm-var!` `:860-896` |
| Call-preparation snapshot | **137.66 ms** for 5 supplied-default rows and 471 prepared symbols, re-derived on ANY newer basis (`:534-561`) | plans retained across unrelated transactions; a supplier or contract change re-derives affected plans only; count contracts/slots visited | §4 form 2 |
| Datalog queries per plan | 8 structure queries + 3 prepared-symbol queries + 1 contract-t query | 0 | read of `plan-for` `:837`, `contract-transaction` `:591` |
| Second holder `:seon.schema.projection/compiled` | **5,600** entries: 2,416 write-attribute-plan, 1,555 function-arities, 1,179 write-validator, 310 wrapper, 123 edn-encoded?, 17 singletons | zero DUPLICATE plain validators/explainers; other products stay with their owners until A2 carries them | §4 form 2 and the dated holder census |
| Armed Vars / arity rows / shape rows | 1,570 / 1,684 / 5,141 | unchanged; shape rows re-stamped at the RESET | §4 form 1 |

Anything above ~2 s after this lane is an algorithm finding. The only
O(program) steps that survive are the one cold construction at boot or
publication and the canonical test fixture’s initial packaged acquisition (`test/arm.clj:35-46`). Cold acquisition is not repeated per test.

## 2. The data flow

| Mechanism | Data, time and carrier | Trigger and work |
|---|---|---|
| Generation | forms, contracts, dependency maps, compiled schemas carried by the immutable database value (`db.clj:249-271`) and the SCI ctx | Cold: full population, once (`schema.clj:454-503`). Update: changed declarations plus reverse dependents compile (`projection-with-schema` `:2811`, `-function-contract` `:3231`); those identities are handed to preparation. Probe `mr/simple-registry` over the updated persistent map (`registry.cljc:24-28`) to delete the `HashMap.putAll` copy (`:17-22`) and the whole-population selection (`schema.clj:465-475`). Count visited/copied entries; no O(change) claim while they remain |
| Shape projections | required-attribute maps, shape index and catalog on the projection | today: full shape-row processing on any shape change (`schema.clj:2201-2235`, `:2880-2893`). Probe updating the existing maps from the replaced rows; the sorted catalog is an explicit cost until removed |
| Acquisition | the projection through the database owner's origin-aware seam (`db.clj:1156-1170`, `:1219-1225`) | ordinary value: metadata read; temporal value: origin traversal; absence in running code REFUSES (`refuse-projection-source` shape, `schema.clj:2737`). Named cold acquisition reconstructs only from the exact supplied database, never the current classpath |
| Arming | retained contract (`mr/schema`), declared output schemas, policy, in callable closures; unchanged wrappers keep identity (`current-wrapper?` `:844-858`) | compilation: zero named declarations. Selection today visits every loaded contracted Var and compares dependency maps (`:898-908`, `:991-993`); publication's affected identities (B1) bound it. A shared JVM Var has no per-cluster generation: deleting host projection selection needs the two-generation proof (§5 A1-2). SCI context-owned roots already have an ownership seam (`sci/impl/utils.cljc:362-379`) |
| Call preparation | supplier facts (rows `:seon.call-preparation/key`/`schema`/`supplier`, `call_preparation.clj:14-20`) and plans carried by the existing snapshot; slot structure read off the compiled contract | derive plans once; an unrelated transaction retains them; a supplier or contract change derives affected plans; count contracts/slots visited. Match by declared schema NAME, never by structural equivalence (goals note `:281`) |
| Fingerprint | normalised AST as hash input; authored Malli form stays stored (goals note `:125`); dependency fingerprints stay part of identity (`schema_shape.clj:100-113`) | changed shapes and reverse dependents. No compile to recover structure a retained Schema already has. RESET NEEDED when normalisation changes |
| Admission | prospective declarations AND deletions over the carried projection → findings | cold: all declarations; edit: changed declarations and affected dependents validate. The name-overlap and exact-reuse advisories scan the population (`admission.clj:297-349`) and are measured separately |
| Error rendering | `me/error-message` with a shared immutable noun overlay over one problem and its compiled schema | at rendering; proportional to the schema described. Error-specific messages stay separate from the noun description; custom schema messages are preserved |
| Derived products | validators/explainers live on Malli Schema objects (`core.cljc:345-361`, `:2626-2633`); attribute plans, identity indexes, retention rules, pulled forms and error indexes stay with their projection or acquiring owner | remove only duplicate derivations; never replace a retained derivation by repeated `(derive-fn)` |

## 3. Reading list — open before editing

| Open | Guarantees |
|---|---|
| `registry.cljc:17-22` `fast-registry`; `:24-28` `simple-registry` | fast copies `m` into a `HashMap` (`putAll` = O(population)); simple answers `(m type)` on the persistent map with no copy |
| `registry.cljc:54-59` `composite-registry`; `:81-93` `lazy-registry`; `:97-102` `schema` | composite `-schemas` merges every member; lazy memoises per identity; `mr/schema` is the read |
| `core.cljc:2550-2556` `schema`; `:2574-2602` `form`/`properties`/`children` | `m/schema` on a Schema is identity; the others are field reads |
| `core.cljc:2193-2220` `-function-info`/`-instrument-f`; `:2279-2294` multi-arity `-instrument-f` | `:report` is a NOTIFICATION: the body still runs (`:2210-2215`); the same `f` goes to every arity (`:2280`) but `:gen` receives each child schema at construction (`:2207`) |
| `core.cljc:3118-3143` `-instrument` | takes `:schema` compiled (`(schema options)` passes a Schema through), `:scope`, `:report`, `:gen` |
| `core.cljc:2771-2798` `entries` | keys and `:optional` come off the compiled node as `MapEntry`s |
| `core.cljc:2848-2880` `from-ast`/`ast` | AST keeps `:order`, `{:closed false}`, `{:optional false}`; literals inside `:enum` are untouched values (§4 form 2) |
| `core.cljc:268`, `:345-361`, `:2626-2648` `-memoize`, `-cached`, `validator`/`explainer` | every Schema caches its own validator/explainer; a Seon duplicate is waste |
| `error.cljc:44-172` `default-errors`; `:288-305` `error-message` | lookup order: schema props → type props → `(errors type)` → `(errors (m/type schema))`; `:vector :sequential :map :set :tuple :and :or :fn` are absent from the table |
| `reference-code/malli/src/malli/instrument.clj:18-43` `-strument!` | global `m/function-schemas`; why the Seon wrapper stays (goals note `:368`) |
| `reference-code/sci/src/sci/impl/utils.cljc:362-379` `bind-root!` | copies a Var whose `:sci/generation` differs from the ctx's; a JVM Var has no such seam |
| `schema.clj:454-503` `projection-registry` | the landed idiom: retained roots skip compilation, but `:465-475` selects over the population and `:503` copies it |
| `schema.clj:2811-2914`, `:3231-3308` incremental constructors | recompile the changed key and its reverse closure; `:2880-2893` still rebuilds shape data when any shape row changed |
| `schema.clj:2737-2770` `refuse-projection-source`; `:906-910` late `d/q` resolution | the refusal shape; the idiom for `schema` → `db` without a require cycle |
| `db.clj:249-292` `carry-projection-state`, `carry-derived-projection` (`:286` calls the seam); `:1156-1170`, `:1219-1225` | how a value gets and answers its projection (A2 owns) |
| `src/seon/sci/eval.clj:694-711` `install-function-contract!`; `:903,944,988,2233` two-argument seam calls; `:2115` `same-program-root?` | B2's half of the arming interface |
| `call_preparation.clj:353-391` `named-entry-facts`, `:424-520` `snapshot`, `:534-561` `current-snapshot`, `:563-586` `watch!`, `:1099` `supply`, `:1277` `prepare`, `:1371` `hook` | surviving seams; `named-entry-facts` and supplier coherence read shape rows today |

## 4. REPL protocol

Codex reaches the REPL through `.codex/config.toml` → `bin/mcp-server` →
`seon.dev.mcp` `eval_clj` (`mode "jvm"`, `read_only true`) against `default`;
one evaluation in flight; `mode "sci"` never for measurement. A mutating
probe (arming, `with-redefs`) runs in the implementation's isolated fixture
under `seon.test-support/preserving-instrumentation-state`
(`test/seon/test_support.clj:1085`), or on a scratch cluster
`bin/seon --root tmp/a1-root start a1` (directory created first, downed and
deleted in the same turn). A lane never resets `default`.

**Form 1 — premise and seam cost** (2026-09-21, `default`, 703 ms):

```clojure
(let [db (seon.db/db (seon.operator/connection "default"))
      carried (seon.db/carried-projection db)
      t0 (System/nanoTime) p (seon.schema/projection-from-database db)
      ms (/ (- (System/nanoTime) t0) 1e6)
      t1 (System/nanoTime) p2 (seon.schema/projection-from-database db carried)
      ms2 (/ (- (System/nanoTime) t1) 1e6)]
  {:rebuild-ms ms :rebuild-with-reusable-ms ms2
   :forms (count (:seon.schema.projection/forms p))
   :contracts (count (:seon.schema.projection/function-contracts p))
   :same-forms? (= (:seon.schema.projection/forms carried) (:seon.schema.projection/forms p))
   :same-contracts? (= (:seon.schema.projection/function-contracts carried)
                       (:seon.schema.projection/function-contracts p))
   :fingerprint-equal? (= (:seon.schema.projection/fingerprint carried)
                          (:seon.schema.projection/fingerprint p))
   :arities-datoms (count (datahike.api/datoms db :aevt :seon.fn/arities))
   :shape-rows (count (datahike.api/datoms db :aevt :seon.schema.shape/fingerprint))
   :instrumented (count (seon.instrument/instrumented))})
;; => {:rebuild-ms 588.990958 :rebuild-with-reusable-ms 77.627833 :forms 3337 :contracts 1555
;;     :same-forms? true :same-contracts? true :fingerprint-equal? true
;;     :arities-datoms 1684 :shape-rows 5141 :instrumented 1570}
```

**Form 2 — population work that survives a zero compilation count**
(2026-09-21, `default`, basis 536870949, 140 ms):

```clojure
(let [db (seon.db/db (seon.operator/connection "default"))
      p (seon.db/carried-projection db)
      t0 (System/nanoTime) snap (seon.call-preparation/snapshot db p)
      snap-ms (/ (- (System/nanoTime) t0) 1e6)]
  {:snapshot-ms snap-ms
   :supplied-default-rows (seon.db/q '[:find (count ?e) . :where [?e :seon.call-preparation/key _]] db)
   :prepared-symbols (count (:seon.call-preparation/prepared-symbols snap))
   :registry-entries-copied (count (malli.registry/schemas (:seon.schema.projection/registry p)))
   :holder-entries (count @(:seon.schema.projection/compiled p))
   :enum-literal-ast (malli.core/ast (malli.core/schema [:enum :x {:order 0}]))})
;; => {:snapshot-ms 137.655042 :supplied-default-rows 5 :prepared-symbols 471
;;     :registry-entries-copied 5040 :holder-entries 5600
;;     :enum-literal-ast {:type :enum :values [:x {:order 0}]}}   ; a literal map keeps its :order
```

**Form 3 — counting form, isolated fixture only** (never `read_only` on
`default`; `apply!` and `with-redefs` mutate live roots). Count named
definition constructions — calls whose argument is NOT already a Schema — on
(a) first arming against an admitted projection and (b) unchanged re-arming,
reported separately with the entries copied:

```clojure
(let [n (atom 0) counting (fn [f] (fn [x & more] (when-not (malli.core/schema? x) (swap! n inc)) (apply f x more)))]
  (with-redefs [malli.core/schema (counting malli.core/schema)
                malli.core/function-schema (counting malli.core/function-schema)]
    (seon.instrument/apply! {:seon.config/on-core-error :panic
                             :seon.sci.admit/caps (seon.config/result-caps seon.config/defaults)
                             :seon.schema/projection fixture-projection}))
  {:named-constructions @n
   :registry-size (count (malli.registry/schemas (:seon.schema.projection/registry fixture-projection)))})
;; acceptance after A1-1: first and unchanged arming => 0 named constructions
```

`registry-size` is population size, not measured copying. Count actual entries visited/copied at the registry construction seam in the isolated fixture before claiming change-proportional work; counting calls to `m/schema` is only a proxy until named provider constructions are distinguished from identity reads.

**Form 4 — two generations, one JVM Var** (isolated fixture): arm `f` under
projection P1 (contract C1, policy `:panic`) and under P2 (contract C2,
policy `:degrade`) in BOTH orders; call `f` with a P1 value and a P2 value;
record which contract validated each call. Decides §5 A1-2.

After each commit, re-run forms 1–3 and paste the rows into the landing note.

## 5. The work, ordered as commits

Each commit is path-limited, net-negative or a named feature, and proven
twice: `clojure -M -e "(require 'seon.schema 'seon.instrument 'seon.call-preparation 'seon.fn.schema-shape 'seon.schema.admission 'seon.test.arm)"`
and, on `default`, `require :reload` of the touched namespaces through the
edit hook's adoption followed by a bounded `runtime_status` read — the debug
probe named per row. A public retirement and every caller's conversion,
tests included, are ONE commit; until the owning files are free, the existing
API stays and independent internal reductions land. Fork commits are pushed
before the deletion they enable. Recovery when a commit breaks `default`
anyway: `bin/seon reset --force`, performed only by the orchestrator. Reset discards the store’s recorded agents, turns, evaluations, tasks and history, plus in-memory private objects and results; disposability authorizes that loss. Preserve evidence before recovery.

| # | Commit | Delete | Build / convert | Probe on `default` |
|---|---|---|---|---|
| A1-1 | **The wrapper reads the retained contract** | per-wrapper recompile `instrument.clj:742-754` (`compilable-form` + `bind-contract-predicates` + `m/schema`); `contract-digest` `:871-873` + meta key `:896`; the classpath population in `bootstrap` `:995-997` | contract = `(mr/schema (:seon.schema.projection/registry projection) sym)`; the fallback `boot-wrapper` `:874-877` closes over the ARMING projection instead of a classpath population; predicate-binding recursion (`:428`, `:882`) stays until retained predicate roots are verified. Probe `:gen` (`core.cljc:2207`): a function of the child arity schema returning the original wrapped with that arity's declared output permission, so Malli owns arity dispatch and the scan `:778-786` dies; verify invalid input, invalid arity, undeclared error output, and an error satisfying both declared and open schemas before deleting it. Rejection stays non-returning (`:report` does not stop the body). Leave `arm-var!` as C1's hook point | form 3 (b) = 0; `runtime_status` |
| A1-1b | **`wrap-interpreted` reads the registry** | the `spec-edn` string parameter and its `edn/read-string` (`:462-522`) | contract = `(mr/schema registry sym)`; the one caller `sci/eval.clj:702` converts in this commit when the file is free, else STOP and name it. Contract with B2: the wrapper is a pure function of (retained contract, original, policy), idempotent through `::interpreted-original` (`:413-418`), and never reads or writes `:sci/generation`; A replacement base must not mutate a base-owned SCI Var shared by active forks (`reference-code/sci/src/sci/impl/utils.cljc:362-379`). B1 definition identity and the two-generation proof precede removal of acquisition or arming state. | an agent turn evaluates one contracted call |
| A1-2 | **Host projection selection — decided by form 4** | `supplied-projection` `:604-619`, `request-member` `:572-603`, the per-call branch `:880-889` — ONLY if form 4 shows both orders validate each cluster's call correctly without them | the argument is B2's: a definition that differs between clusters is interpreted under its own program, so a shared JVM Var only ever serves one definition and its one contract. If form 4 refutes it: keep today's selection minus the classpath fallback and hand the owner three options in the landing note (keep per-call selection; host Vars armed by the development cluster only, other clusters interpreted; per-cluster Var copies — no mechanism exists, rejected) | form 4; a second cluster started and a contracted call from each |
| A1-3 | **The seam reads the carried value** — one slice with A2/B2/B4 | `derive-projection-from-database` `:2771-2787`; the two-argument arity of `projection-from-database` `:2788-2810`; `projection-from-rows` `:2546-2736` collapses to one ≈40-line row loader `load-projection` (`projection-rows`/`-admissions` `:2514-2545` stay as its reads) | `projection-from-database` returns `(db/carried-projection db)` and refuses when absent; a `schema` → `db` require cycle is avoided by the late-resolution idiom at `:906-910`. Conversions in the SAME commit: `sci/eval.clj:903,944,988,2233` and `turn.clj:1235` (two-argument callers, B2); `db.clj:286` `carry-derived-projection` and the 8 `projection-cache-value` sites (A2); `test/runner.clj:1506` (B4, deliberate reconstruction → `load-projection`); the 7 boot sites `cluster.clj:1677,2093,3251,3269`, `cluster/source.clj:160,267`, `cluster/registry.clj:236` (B1 → `load-projection`). Verify before landing: raw dependency values, materialized commit values, branch/fork values, ordinary Seon values and temporal values each have an explicit acquisition owner; a raw `datahike.api/db` value without projection refuses ordinary use until that owner supplies carriage. A post-write value carries the admitted post-write projection; a retained older value keeps its earlier projection; temporal values traverse origin. The 24 other running-code callers read the carried value untouched; their `or` fallbacks (`fn.clj:2778` pattern ×8) become dead one-liners for their owners | form 1 rebuild → a read |
| A1-3b | **Registry assembly proportional to the change** | the population selection `:465-475` and the `HashMap` copy `:503` | `projection-with-schema`/`-function-contract` hand the affected identities to `projection-registry`; the registry is `mr/simple-registry` over the persistent map updated by `assoc` of the changed entries (§6 C1 decides); shape data (`:2880-2893`) updated from the replaced rows only | form 3 entries-copied |
| A1-4 | **The classpath fallback is a refusal** — one slice with A2/B1 | `schema.clj:929-1085` (frame walking, `clojure.basis`, `decade?`, `!fallback-counts`, `warn-classpath-fallback!`); `candidate-forms`' last arm `:1099`; `edn.clj:361-390` `packaged-population-cache` + `forget-packaged-population!`; the zero-arity `declaration-projection` `:1227` | `packaged-forms` (`edn.clj:406`) keeps the stamped resource read; explicit `(declaration-projection forms)` calls are constructors and stay (`test/arm.clj:39`). Zero-arity callers converted in this commit: `schema/datahike.clj:509,555` (A2), `cluster/source.clj:408` (B1), `instrument.clj:1122` (`restore!` receives the projection as a named member of its captured state) | boot a scratch cluster; `runtime_status` |
| A1-5 | **Config admission per declaration** | `assert-config-display!` `:1206-1225` and its call `:1237` (a `structural-schema` compile of 3,337 forms per constructor call) | the check reads `(m/properties (mr/schema registry k))` for each ADMITTED declaration at construction — cold (`declaration-projection`/`build-projection`) and changed (`projection-with-schema`) — and inside `admission/compilation-finding`; `register!` `:1545` alone is not coverage (it defers reference resolution) | a config dial edit publishes |
| A1-6 | **Plans off the retained contract, by declared name** | `arity-query`…`required-named-map-entry-query` `:604-713`; `argument-validators` `:642`; `prepared-*-query` `:329-391`; `contract-transaction` `:591-602`; the `plan` cache `:1004-1054`; `by-fingerprint` `:322`; the shape-row reads in `named-entry-facts` `:353-391` and supplier coherence `:470-500`; the four `snapshot`/`current-snapshot` guards `:557,583,1400,1404` (D12: the callee's contract declares its union) | `plan-for` takes `(mr/schema registry sym)`: arities `m/-function-schema-arities` → `m/-function-info`; slots `(m/children input)` with `:catn`/regex nodes handled through their compiled structure; entries `(m/entries (m/deref-all slot))` with `(:optional (m/properties v))`. A slot is suppliable when its reference node NAMES the supplied default's declared schema key (inspect the reference before dereferencing); two schemas with identical bodies stay different names. Preserve caller-wins, required-entry match, fixed-arity precedence, ambiguous-placement refusal, variadic behaviour. Plans live in the snapshot and are RETAINED when supplier rows and retained contracts are unchanged; a newer basis alone rebuilds nothing. Supplier rows, `supply`, `prepare`, `hook` unchanged | form 2 after an unrelated transaction: plans identical; after a supplier edit: affected plans only |
| A1-7 | **Fingerprint off a normalised `m/ast`** — **RESET NEEDED** | `split-schema-form`, `canonical-map-entry`, `canonical-form` `:21-56`, `map-properties` `:14-17` | `authored-form` `:75-81` still returns the AUTHORED form (stored in `:seon.schema.shape/form`, references and predicates as names); the hash input becomes `(normalise (m/ast compiled))` where `normalise` touches only AST-owned positions: entry `:order`, `{:closed false}`/`{:optional false}` in property positions, and the empty property map they leave. Never dissoc from arbitrary literals (form 2: an enum value `{:order 0}` must survive). Convert every helper caller — `:128-129`, the row codec `:274`, `:293` — in this commit; keep `form-fingerprint`'s dependency composition `:100-113`. Verify entry reorder, absent vs explicit-false properties, literal maps, aliases, local recursion, bound predicates. Bump `normalization-revision` `:10-12`; record the commit id as RESET NEEDED | after the orchestrator's reset: shape rows re-stamped, count = form 1 |
| A1-8 | **Fork: the eight absent error types** (pushed first) — `error.clj` is B3's | `error.clj:977-1000` `schema-expectation` only when `git status` shows the file free | `default-errors` gains `:vector :sequential :map :set :tuple :and :or :fn` in Malli's sentence idiom. Seon's noun grammar is a data overlay passed as `{:errors (merge me/default-errors nouns)}` from `explain-problem` `:1020`; the dereference and the `:type` removal (`:983`, `:1046`) stay, scoped to the expected-description noun. Overlay entries derive from the retained grammar cases (`refusal_grammar_test.clj`), compose children through the same options, keep custom messages. Measure net deletion including the fork | a refused call renders "expected a vector" |
| A1-9 | **Admission is pure over the carried projection** — one slice with B1 | `schema-files`, `default-schema-directory`, `read-declarations`, `default-registry-excluding`, `canonical-path` `:71-158`; the second walker `:159-183`; `-main` `:438-443` and B1's subprocess fallback `bin/seon-hook:500-506` | `admit` request = `{::sources {path source} ::projection carried}` → findings; the prospective population = carried forms − declarations the edited files no longer contain + candidates; affected dependents recompile through `projection-with-schema`'s reverse-dependency selection (a composite over old compiled dependents does not do this); house findings walk `(m/walk (structural-schema form) …)`. Advisory scans (`:297-349`) measured separately. B1 rewires `bin/seon-hook:498` to send the carried forms of `default` and reports "admission unavailable" when no live process answers | an edit-hook publication of one schema file |
| A1-10 | **Duplicate validators leave the holder** — paired with A2 | the `::wrapper`/`::base-validator`/`::refusal-validator` families where they duplicate `m/validator` on the retained Schema | `with-compiled-cache` `:357-385` stays for attribute plans, identity indexes, retention rules, pulled forms and error indexes until A2 carries them on their acquiring owner; no interim `(derive-fn)`; docstring corrected to what it holds | form 2 holder families |
| A1-11 | **Small deletions** — one slice each with all callers | `byte-array?`/`sha-256` `:761-772` (callers → `clojure.core/bytes?`, `seon.id/sha-256` with `[bytes]` argument shape; `register-core-predicate!` `:1153` re-points); env readers `instrument.clj:161,232` in B3's paired environment cut | `portable-string-hash` `:695-697` is NOT converted here: its consumers XOR ints (`:774-816`) and `canonical-data-fingerprint` declares `:int`; a change names representation, width, comparison guarantee and RESET | load check |

The seven inline guards naming `seon.db/q`, `db` or `history`
(`call_preparation.clj:240,313,414,421,438,725,740`) have their unions in
`db.clj` (A2): listed in the landing note, never a predicate.

## 6. Better than the floor — probe first

| Candidate | Probe that decides | If it holds |
|---|---|---|
| **C1 `simple-registry` over the persistent map** | build the fixture generation both ways; count entries copied (form 3) and time 10⁶ `mr/schema` reads on each (`HashMap.get` vs persistent-map `get`) | `fast-registry` and the `putAll` copy leave; an update is `assoc` of the changed entries; no layered registry chain |
| **C2 `:gen` hosts the per-arity permission** | wrap each child arity through `:gen` (`core.cljc:2207`) and confirm `::invalid-arity`/`::invalid-input` arrive through `:report` while the body is not invoked on refusal (the wrapper's own non-returning rejection, since `:report` returns) | Seon's arity scan and `arities` vector leave the call path; one `m/-instrument` per Var |
| **C3 Plans retained by identity** | after A1-6, an unrelated transaction: plans `identical?`; a supplier edit: only plans naming that key rebuilt; count contracts visited | no `contract-t`, no plan cache, no `[sym contract-t basis-t]` key |
| **C4 Shape data updated in place** | after replacing one shape row, count rows visited by `shape-projections` (`:2201`) vs the affected keys | the sorted catalog rebuild leaves `projection-with-schema` |

A probe that shows a smaller cut than §5 wins; the landing note records the
evidence and the rejected candidates.

## 7. Tests

The lane runs only the tests reaching its change through B4’s final `seon.test/run` request on the exact supplied program. Before that API lands, use the currently admitted in-process entry point or `bin/test-fast --paths <owned> -- <ns>`; retire it only with all callers. Never a suite or a lane cold gate. B4’s platform owner supplies cold-load proof separately.

Owned test files (18, **7,011** lines by `wc -l` at HEAD): `schema_test`
1,590; `instrument_test` 1,478; `call_preparation_test` 826;
`schema/datahike_test` 619 (shared with A2); `schema_usage_guard_test` 589;
`schema/edn_test` 481; `schema/admission_gate_test` 223; `fn/schema_shape_test`
191; `schema/admission_test` 154; `schema/projection_acquisition_test` 143;
`schema/declaration_population_test` 110; `schema/program_test` 108;
`refusal_grammar_test` 105; `registry_isolation_test` 100;
`schema_redeclare_test` 94; `schema_audit_test` 92;
`schema_reference_graph_test` 87; `schema_encoding_test` 21.

| Group | Members | Disposition |
|---|---|---|
| Benchmarks as deftests | `schema_test.clj:41,305,1468`; `instrument_test.clj:1195,1223` | delete (~150 lines); measurement is §4's forms in the landing note |
| Pinned to deleted mechanisms | `instrument_test.clj:944,968,979` (contract-digest), `:960`, `:1091` | delete with the mechanism, in its commit |
| Class B "a refusal names the offending argument" | `instrument_test.clj:280,612,644,655,687,721,770,1339,1357,1425`; `refusal_grammar_test.clj:13,29,44`; `schema_test.clj:806` | collapse 14 → 2 (host wrapper, SCI), keeping every grammar case as data for A1-8 |
| Class A "arming is idempotent" | `instrument_test.clj:471,866,878,913,1064,1117,1285`; `registry_isolation_test.clj:51` | collapse to 3: idempotent; re-arm on contract change; re-arm on referenced-declaration change. `registry_isolation_test.clj:51` (two clusters) becomes form 4's regression |
| Class C "the population resolves once" | `schema/edn_test.clj:77,107,122,185`; `schema/declaration_population_test.clj:63,82`; `schema/datahike_test.clj:441` | delete 5 with A1-4, when the fallback is unconstructable; keep one missing-projection refusal regression |
| Datalog-structure tests | `call_preparation_test.clj` members asserting query rows or plan cache keys | delete; behaviour tests over `prepare`/`hook`/`supply` stay and gain: a plan derives from the retained contract with no shape row; two names with one body match separately; plans survive an unrelated transaction |
| Fingerprint | `fn/schema_shape_test.clj` | rewrite the canonical-form cases as normaliser cases: entry order, `{:closed false}`, `{:optional false}`, literal `{:order 0}` untouched, reference-as-name, row codec round trip |
| Pinned to private helpers | `schema_test.clj:1265`; `schema/edn_test.clj` ×8 on `#'schema.edn/resource-population` | rewrite against behaviour |
| **Class regression landing with the cut** | new, in `instrument_test.clj` | first arming against an admitted projection and unchanged re-arming each perform ZERO named-definition constructions, counted by form 3 |

Time escapes (all six verified); a removed exception keeps the declared
default bound and fails over it:

| Declaration | Becomes |
|---|---|
| `schema/admission_test.clj:127` 300,000 ms | bound removed with A1-5/A1-9; publishes a SMALL fixture population, never `src/` |
| `schema/datahike_test.clj:244` 25,000 ms | A2 owns the shared file: hoist repeated canonical setup, preserve all 80 cases, then measure against the default bound; any remaining allowance needs measured work and a reason |
| `schema/datahike_test.clj:361` 120,000 ms | bound removed: the cost was the seam rebuild (A1-3); re-measured before removal |
| `schema/projection_acquisition_test.clj:38` 10,000 ms | deleted with `projection-from-rows` (A1-3) |
| `schema/projection_acquisition_test.clj:95` 10,000 ms | bound removed: one carried generation |
| `schema_redeclare_test.clj:73` 300,000 ms | kept: a real cold boot; tightened to the measured time |

Test target: **≈ 5,600** lines, provisional (approximately −1,400: the groups above, ≈ 150 + 90 +
300 + 250 + 120 + 350 + 40 rewritten, counted per commit in the landing note).

## 8. Size target, done, landing note, stop rules

| File | Before | Target | Terms (non-overlapping spans) |
|---|---:|---:|---|
| `schema.clj` | 3,904 | 3,550 | −157 fallback `:929-1085`; −150 rows → loader; −17 derive; −20 config assert; −12 sha; the registration-delta (`:3358-3480`), pulled-form (`:2915-3190`) and identity-only (`:3696-3886`) regions are other lanes' questions and stay |
| `schema/admission.clj` | 443 | 320 | −88 file walker; −25 second walker; −6 `-main`; −4 hook fallback (shell) |
| `schema/edn.clj` | 650 | 620 | −30 cache + forget |
| `instrument.clj` | 1,138 | 1,000 | −48 selection + union (A1-2, conditional on form 4; 1,060 if refuted); −25 digest/bootstrap/recompile; −10 arity scan (C2); −10 env readers |
| `call_preparation.clj` | 1,417 | 1,100 | −110 queries; −60 prepared queries; −12 contract-t; −50 plan cache; −30 by-fingerprint; −8 guards; −50 shorter `plan-for` |
| `fn/schema_shape.clj` | 468 | 445 | −36 walker + 12 normaliser; callers converted, codec unchanged in shape |
| `test/arm.clj` | 254 | 250 | unchanged except the handed projection |
| **A1 src total** | **8,274** | **≈ 7,300 (−12 %)** | provisional; the audit floor of 1,430–1,960 for the whole Malli area spans A2's `schema/datahike.clj` and B3's `error.clj` and counted the holder and hash conversions the preserved guarantees exclude. C1–C4 may take it lower; each is priced when its probe lands |

**Done:** form 1 reports the seam as a read; form 3 reports 0 named constructions on both passes, with separate construction-seam evidence that visited/copied entries are proportional to the change; form 2 shows plans
retained across an unrelated transaction; form 4 recorded with both orders;
HEAD loads after every commit and `default` answered `runtime_status` after
each reload; the reaching tests green in process with the tally pasted; the
cold proof named as owed to the orchestrator.

**Landing note:** `docs/prds/agent-platform/landing/lane-a1.md` with forms
1–4 and values before/after, `git diff --stat` per commit, RESET NEEDED with
the A1-7 commit id, the seven `db.clj` guard sites, the 24 untouched carried
callers and 8 dead `or` arms by `file:line`, each §6 probe's result including
rejected candidates, the A1-8 grammar entries derived, any span whose lines
moved, and whether each observation exercised hot reload, a fork or
in-place adoption.

**Stop rules.** Check current ownership before editing; a historical dirty-file list does not establish a present hold. Keep every public API until all caller conversions can land in the same loadable slice. A1-1b/3/4/8/9/11 coordinate with B2, A2, B1, B4 and B3 respectively. Unsettled custody or schema semantics stop only the dependent deletion; record three concrete options with guarantee, cost and lost capability.

**Cross-plan contracts.** B1 supplies changed definition identities and their canonical digests at installation and arming; A1 supplies the existing wrapper seam to C1 and B4’s shared execution observation. A1 validates the returning arity’s declared alternatives with Malli’s retained validators, while B3 supplies the error constructor/contracts; an additional open schema never turns undeclared output into a permitted error. B1’s schema-reference selection must land before deleting broad arming. A2 keeps supplied-default acquisition until A1-6 covers every supplier/contract dependency, and derives it once per relevant value rather than once per caller edge. Effective supplied bounds are not static arity minima. Existing materialized render properties remain the facts; any type/index correction is one A1/A2/B2 declaration-and-consumer slice.
