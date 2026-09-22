---
type: plan
status: five-commit sweep specified; writer prerequisite RULED 2026-09-23 (owner): the projection is a memoized function of the database value (core.cache, keyed by `:cache-context`) — no writer stamp, no fork change, no single-flight; commit 1 is that memo
---
Verified: 72 transport calls/24 src files; 25+ rows inspected; 13 corrections below; test census is now 511 text matches/86 files, not 509.
Decision 1: cluster executor → `(:io (root-executors))`; callers carry their connection/context.
Decision 2: flow executor → `(:io (@operator-runtime-root-executors))`; retain explicit proc inputs.
Decision 3: effect → keep request/custody binding, delete only the projection binding.
Decision 4: instrumentation → retain argument selection, delete its ambient final arm; retain captured host contract.
Decision 5: admission → explicit optional projection member; bootstrap takes the existing complete-population path. Five commits: ~12 constructor/seam sites; nine C rows + five O rows; remaining 58-class M rows; ~560 test occurrences; owner/fallback deletion.

# Projection as a read: execution order

Execute README §4 step 1.4, A1-3/A1-4/A1-12, with README §7 **codec retained** overriding the older c2 prerequisite.
Evidence references below are working-tree reads at `ad72fa094` (`git rev-parse --short HEAD`), following initial status at `a44be74de`; files had concurrent uncommitted edits. Source paths abbreviate `src/seon/`; test paths abbreviate `test/seon/`. Function names, not stale line offsets, identify edits.
The input is [the complete site table](../../../research/agent-platform/projection-as-a-read-site-table-2026-09-23.md), read in full. Its 58 M / nine C / five O labels classify transport occurrences, **not** the complete deletion closure or nine distinct function signatures. Do not promise a 24-file deletion: `schema.clj`, `schema/datahike.clj`, `schema/admission.clj`, and `program.cljc` are additional owners. The tables here override wrong replacements there.
Laws: AGENTS “Values carry their world” and habit 1: use the held value; never replace an ambient lookup with a reconstruction fallback. No new cache, executor adapter, thread-local, environment registry or test-side projection service.
Read-only preparation observed PID 51528 alive, all proc pings answering, 14 errored receipts. No tests, reload, launch, publication or mutation of default was performed. An initial malformed MCP form was rejected by the reader; the corrected probe below returned normally. This is JVM evidence, not SCI adoption or browser evidence.

## 1. Corrections established before implementation

| # | Source checked | Exact correction to the input |
|---|---|---|
| 1 | `db.clj:1250,1259,2011` | Relation-only queries have **no database/projection**, and empty installed schema prevents decode. Keep the declaration constant as `{::installed-schema {}}`; remove its unused delay. Do not invent `(relation-only-declarations projection)` when its caller has no projection. |
| 2 | `schema/datahike.clj:405,462`; `db.clj:4166` | The transaction stamp **already exists** in `encode-call-output-in`: `(apply f (vary-meta db assoc :seon.schema/projection projection) args)`. `transact-call` passes projection to this codec. Stamping its outer pre-read alone would not stamp Datahike's transaction database. Keep this one existing adapter, including recursive output encoding. |
| 3 | `cluster.clj:1313,1356` | Neither admissibility nor accretion may use `(or carried (load-projection ...))`. Read the carried **candidate** projection; cold publication supplies it before entry. Read persisted declarations for comparison against that candidate. Loading the old rows here would compare the old population with itself. |
| 4 | `cluster/boot.clj:159` | `require-admissible-branch!` runs **before** initial state construction. Move it after state attachment and pass `(db/db provisional-connection)`, not the earlier bare `initial-database`. |
| 5 | `cluster.clj:1613` | Cannot use `projection-state` before it is bound. Order: raw database → load → state → attach connection → stamp database → query/context. Return the stamped database. |
| 6 | `cluster.clj:2194`; `cluster/source.clj:427` | First publication has no populated database. Keep explicit `(schema/declaration-projection (schema.edn/packaged-forms))` at this cold boundary; pass it through the publication request. Do not insert `load-projection` with an ellipsis or empty database. |
| 7 | `test/arm.clj:35`; `test/runner.clj:1743` | Packaged constructors have forms, not a database. Keep their explicit forms constructor. `load-projection` belongs to the platform fixture **after** opening its published database (`test_support.clj:399`). |
| 8 | `test_support.clj:443,478,590,634,948,954,976` | `database-base` is a retrying IDeref/lease object, not a delay. Its constructor loads from its own database. Remove frame capture at `:478/487`; preserve acquire/release/retirement. Do not replace the object with a function and break lease callers. |
| 9 | `render/web.clj:2176,2309,2143` | `render-pass` has 1/3/4 arities, not a three-arity body. Both it and `current-page` already pass database/profile to `derive-page!`; stamp the handle's request member from that same database. No additional positional projection is needed. |
| 10 | `fn.clj:3360,3370` | Input's `:3318/:3328` moved by +42. Request already has a projection member; use it, falling back only to the connection's carried value, with named absence refusal. Making the optional member required is unnecessary narrowing. |
| 11 | `schema/datahike.clj:488,526`; `schema.clj:1131`; `schema/admission.clj:195`; `program.cljc:943,954` | The 72-call census omits `call-with-forms` and `registered-schemas` dependencies. These must convert before deleting the four Vars. A grep restricted to the input's three names does not prove A1-4. |
| 12 | `sci/eval.clj:958,1009,1047,2264`; `test/runner.clj:1493` | Input's ten-constructor list omits two-argument readers and the deliberate drift audit. Convert ordinary two-arg readers in commit 1; the drift audit deliberately uses `load-projection database`, independent of the projection being audited. |
| 13 | `turn.clj:976,1225,1236`; `db.clj:4208` | **Not mechanical:** ordered declarations intentionally reconstruct after earlier declarations inside one transaction. The transaction report currently stamps both before/after with the entering projection. Replacing these reads with carriage alone would silently lose new declarations. Resolve the writer prerequisite below before changing the accessor. |

Additional inspected rows agree after these qualifications: `db.clj:307,1237,1301,3740,3961,4136`; `cluster.clj:315,652,1323,1360,1459,2066,2104,2825`; `effect.clj:487`; `instrument.clj:548,822`; `schema/edn.clj:607`; `flow.clj:1133`; `render.clj:126,137`; `bootstrap.clj:737,739`; `config.clj:663,747`; `reconcile.cljc:315,319`. The `render.clj:150` keyword is not a 73rd call. There are **14**, not the section heading's 12, cluster occurrences.

### The settled decoder probe

MCP: mode `jvm`, `read_only true`, root `/Users/sean/src/seon`, cluster `default`, timeout 10000. Exact successful form:

```clojure
(let [connection (seon.cluster.boot/connection "default")
      database (seon.db/db connection) p (seon.db/carried-projection database)
      f (malli.instrument/-f->original seon.schema/malli-form?)
      values [:int [:map [:probe/x :int]] [:ref :probe/absent] (Object.)]]
  {:carried? (some? p) :ambient (seon.schema/handed-projection)
   :plain (mapv f values)
   :empty-frame (seon.schema/call-with-projection {} #(mapv f values))
   :own-frame (seon.schema/call-with-projection p #(mapv f values))})
```

Returned in **3 ms**: `{:carried? true :ambient nil :plain [true true true false] :empty-frame [true true true false] :own-frame [true true true false]}`. `schema.clj:1304` passes `structural-registry` explicitly; references are intentionally opaque. Thus `ask-declarations` becomes `(question @(::read-projection declarations))`; delete its obsolete 2026-08-07 ambient justification. This probe bypasses the armed wrapper to inspect the predicate itself; armed codec behavior is part of commit 1's scratch regression.

## 2. Five decisions and the nine C rows

Remove both `projection-executor` definitions. Replace `cluster.clj:2876` and `help_trial_test.clj:41` with `(:io (cluster/root-executors))` (unqualified inside cluster); `flow.clj:1189` uses `(:io (@operator-runtime-root-executors))`. Preserve proc arguments/environment; no Runnable needs an ambient schema after its readers convert. Land these deletions with commit 3's last dependent readers if commit 2's probe finds a remaining reader; do not create a transitional executor.
`effect/with-request-context` becomes `(binding [*request-context* context db/*conn* (:seon.db/connection context)] (work))`. Request context and existing database custody have other jobs; deleting them is outside 1.4. Connections are attached at construction, never on each effect dispatch. Keep the detached shell/effect regression.
`instrument/supplied-projection` returns its existing `some` over arguments, without the final `mi/-f->original handed-projection` branch. **Do not delete the scan**: [wrapper landing](../landing/lane-wrappers-changed-identities-2026-09-22.md:7) records failed copied-host-contract isolation. The wrapper's already captured host contract handles absence. `apply!` keeps its optional member and existing named missing-projection refusal; its caller supplies the member. Retain direct/indirect context-contract tests.
`schema.edn/admit`: destructure `projection`; replace `(schema/current-projection)` with that value. Contract: `[:=> [:cat [:map [:seon.schema/forms :map] [:seon.schema/projection {:optional true} :seon.schema/projection]]] [:vector :map]]`. Runtime candidate admission supplies it; bootstrap without it retains the complete-population gate. `schema/activate!` calls without an identity and does not need a fabricated projection. `register!`'s caller at `schema.clj:1635` must supply its explicit candidate in the registration conversion below. None of these decisions needs a new owner ruling.

Here are the input's **nine C rows**, including corrections where no new arity is warranted. `P` below expands literally to `:seon.schema/projection`, `D` to `:seon.db/database-value`; these are notation, not new schema keys.

| C | Exact final Malli arity / operation | All affected entrances and regression retained |
|---|---|---|
| db relation-only | No function: constant `{::installed-schema {}}`; `with-declarations`' existing 3/4 arities unchanged. | `db.clj:1280` continues with the constant. Keep relation-input-only `q`, including string values: no projection acquisition or decode. |
| populate-source! | `[:=> [:cat [:map [:seon.db/connection :seon.db/connection] [P {:optional true} P]]] [:or :nil :seon.reconcile/result]]`; preserve all other accepted map members. | `cluster/source.clj` population request receives P from `publish!`; `cluster.clj:1459` passes the same P to `index!`. If omitted, obtain it from the carried connection, never resources. Preserve first publication and incremental schema publication. |
| refresh-source! | Public four arities stay exactly as `cluster.clj:2162`; cold projection goes in the existing `roots` map. `full-source-refresh!`: `[:=> [:cat :seon.boot/root :seon.store/store [:map [:seon.fn/root :string] [:seon.source/roots :seon.source/roots] [P P]]] :seon.source/published]`. | Pass P via `roots` to `full-source-refresh!` and the publication request; development adoption uses published database's projection, not the packaged one. Existing development arity stays `[:=> [:cat :seon.store/store :seon.boot/instance :seon.source/published [:vector :string] [:map [:seon.fn/root :string] [:seon.source/roots :seon.source/roots]]] :seon.source/adoption-result]`. Preserve no-change publication and changed-schema adoption. |
| current-page | `[:=> [:cat :map D :seon.schema/value] :seon.schema/value]`; existing three parameters. The registration key/page are heterogeneous renderer values. | Its route callers stay; handle receives `:seon.schema/projection (db/carried-projection database)`. Existing profile remains the positional argument to `derive-page!`. Keep first GET/SSE derivation and explicit profile. |
| render-pass | `[:function [:=> [:cat :map] :map] [:=> [:cat :map D :boolean] :map] [:=> [:cat :map D :boolean :boolean] :map]]`. | Preserve every call, including 1→4 and 3→4 forwarding. Assoc P into local handle from database; `derive-page!` arity stays seven. Keep invalidation/retained-package behavior and observe paint at integration. |
| report-options | `[:=> [:cat :map] :seon.test.runner/report-options]`; delete zero arity. Read P from supplied map or its `::report-options`; default print/profile values remain compiled constants. | `runner.clj:135` builds defaults locally; `:203` uses its already supplied `options`; `:674` custody, `:1307` request, `:1647` resolution, `:2100` `{:seon.schema/projection projection}`. Ensure custody/request/resolution producers carry P. Keep bounded failure text and duration reporting (`test/runner_test.clj:27` supplies P). |
| worker-command-loop!/database-base | Worker arity unchanged; **no new database-base function**. `create-base` retains one path argument and loads its own database. `retrying-base` retains its 1/2/3 callable arities. | `runner.clj:2063` directly derefs base; `test_support.clj:478/487` calls `construct` without capturing a projection. `:634,948,954,976` retain their lease protocol. Keep retry/retirement and two simultaneous holders regression in runner tests. |
| run-coordinator! | `[:=> [:cat P :seon.boot/cluster-name :seon.boot/root :string :string [:sequential :string]] :int]`. | `coordinator-main! :4918` prepends packaged P; carry it into run request/report options and recording requests. Keep selected-program execution and recording failure => nonzero. |
| test.fast/-main | CLI arguments unchanged. `record-snapshot!` stays `[:=> [:cat :string :seon.source/test-recording-request] :seon.source/test-recording-result]`; its union becomes `[:and [:or :seon.test.run/completion :seon.source/test-selection-request] [:map [P {:optional true} P]]]`. | `fast.clj:78,97`, `runner.clj:4783,4871` assoc P from arming/coordinator. Thread map unchanged through persistent recording to source owner, which stamps opened connection before writes. Keep snapshot admission/completion and source-basis isolation. |

Do not tighten `fn/index!` or `instrument/apply!` to require formerly optional keys. Missing usable carriage still refuses by name. Do not add a zero-argument “fixture projection” accessor. Add contracts to newly edited private functions, using the existing named output/refusal schemas; the table does not license widening a pre-existing output.

## 3. Commit 1: loader, constructors, decoder, identity regression

Own `schema.clj`, `db.clj`, `problems.clj`, `schema/datahike.clj`, `cluster.clj`, `cluster/{boot,source,registry}.clj`, `sci/eval.clj`, `turn.clj`, `test/runner.clj`, `test_support.clj`, `registry_isolation_test.clj`, `schema/projection_acquisition_test.clj`, `test/fixture_timing_test.clj:19` (profiling Var changes to load-projection). Estimate **−80 to −180 lines**, excluding the writer prerequisite below, ~12 constructor/seam sites plus two-argument callers; retain transport definitions for subsequent commits.
`schema/load-projection` has exactly one contract: `[:=> [:catn [:seon.schema/database-value :map]] :seon.schema/projection]`. Validate real database and refuse missing schema rows using existing named refusal. Use the existing late-resolution idiom (`schema.clj:906`) for `db/carried-projection`; do not introduce a schema→db require cycle.
Move `derive-projection-from-database`'s three row queries into the cold loader; preserve `projection-rows`/`projection-admissions`, duplicate identity checks, EDN decoding, admission provenance, render-contract validation and predicate data from `projection-from-rows`. Feed `build-projection` once with forms/contracts/options. Eliminate reusable fingerprint/rebuild selection. “≈40 lines” is a target for the query assembly, not permission to erase admission validation or squeeze code. Retain an internal parser helper if needed; delete the old public `projection-from-rows` with its converted direct tests in this commit.
`projection-from-database` keeps only its existing one-argument contract and becomes a carried read followed by named refusal when absent. Convert two-arg calls in `sci/eval.clj:958,1009,1047,2264`, `test_support.clj:399`, and `runner.clj:1493` here; ordinary reads drop the reusable argument only after post-write carriage is correct, the last two are cold/audit loaders. `turn.clj:1236` is a writer transition, not an ordinary reader. No `(or carried (load-projection ...))` at an ordinary reader.

### Ruled 2026-09-23 (owner): memoize the function, with Clojure's tools

None of the three scopes below. The projection is a function of the database value,
memoized through `clojure.core.cache` — already a Datahike dependency, used by Datahike's
own schema cache (`reference-code/datahike/src/datahike/schema_cache.cljc`) — keyed by the
committed value's `:cache-context` (`db.clj:838-873`), a bounded LRU whose bound is data. A
miss derives from the value's declaration rows through `projection-from-rows`. `load-projection`
IS that derivation; `carried-projection` becomes look-up-or-derive; the writer-side stamp
(`carry-projection-state`) and any candidate carried through `row-tx` are deleted. Datahike's
batching and late commits become irrelevant. The ordered-declaration case derives from the
transaction's intermediate `with` value (unmemoized when it carries no context). Commit 1
below is rewritten accordingly; the proof keeps A-then-B, abort, plus hit/miss counts.

### Writer prerequisite: the table cannot authorize a blind replacement

The five O decisions are settled. This additional omission is a cross-owner design boundary under AGENTS “One mechanism”: the current code proves that stamping the entering P is insufficient for sequential declarations or db-after. Before giving commit 1 to Sol, the orchestrator must select one of these **three concrete scopes**; no further discovery is assigned to Sol:

1. **Constrain this sweep to transport removal.** Keep explicit reconstruction at the ordered writer and post-write constructor; no ambient bindings. Guarantee: existing declaration semantics survive. Cost: change the commit 1 plan to retain a named writer reconstruction entrance. Gives up the cold-only loader/zero reconstruction exit, so this does **not** complete 1.4.
2. **Complete the writer producer first (recommended).** One bounded owner slice in `turn.clj`, `db.clj`, `schema.clj`, `schema/datahike.clj`: carry the admitted candidate through the ordered declaration transaction, publish the post-write P only on successful commit, and stamp db-before with entering P/db-after with resulting P. Reuse row-tx's existing candidate operations (`turn.clj:1253,1323,1330`) and the codec adapter; do not rebuild at the consumer. Guarantee: ordinary carried reads are correct after writes and between declarations. Cost: an additional non-mechanical producer slice and atomicity/ordering proof, not priced as the 58-row sweep. Gives up an immediate Sol-only start. Then execute the five commits as written.
3. **Retain the accessor until a later cut.** Land only independent wrapper/argument/test conversions, leaving the old construction accessor and writer sites intact. Guarantee: HEAD keeps the current writer semantics. Cost: two phases and delayed deletion; gives up this step's completion and acquisition-speed claim.

Producer proof is concrete: one transaction declares schema A then schema B referring to A; both are usable in returned db-after, entering db-before lacks both, retained earlier database identity is unchanged; a later invalid declaration aborts the whole transaction and does not advance connection P. This is required before `projection-from-database` becomes a read. No speed measurement or grep may waive it. The input's requested five-commit **complete** mechanical sweep is therefore conditional on option 2; it would be dishonest to label the current tree ready for that launch.

| Cold owner | Construction and stamp |
|---|---|
| `cluster.clj:1613` source base | Raw db → `(schema/load-projection database)` → `(sci.eval/projection-state database projection)` → attach connection → `(db/carry-projection-state database state)`; return this database and pass it to context/query. |
| `cluster/boot.clj:153,160,173,180` boot and reset | Initial projection from already acquired source-base, otherwise load exact persisted initial db. Attach provisional connection before admissibility/coherence/accretion. Reuse initial projection if basis unchanged; if changed, use the post-write carried projection. Attach reopened cluster connection before standing layers. Reset uses this same function; no separate reset loader. |
| `cluster/source.clj:183,297`; `cluster/registry.clj:222` | Materialized committed values call `db/carry-derived-projection`; newly opened recording/fork connections get state built from supplied P or explicitly loaded exact database **at acquisition**, before config/write calls. Ordinary `record-fork!` never reconstructs as a read fallback. |
| `test_support.clj:176,399,732` platform fixture | Load exact opened published database once at fixture construction; attach state. Branch children reuse the base projection and attach their own state/connection. `:732` stamps the resulting database. Preserve cleanup, lease, and independent fresh-store paths. |
| `cluster.clj:2194`; `test/arm.clj:35`; `runner.clj:1743` | Forms-only genesis/packaged arming remains explicit `declaration-projection forms`. Publication stamps candidate connection **before** accretion/indexing; once populated, cold reopened databases use the row loader. |
| `runner.clj:1493` drift audit | Deliberate independent `load-projection database`; compare declaration content to exit projection, not object identity (a new cold load need not be identical). Do not let exit projection validate itself. |

`carry-derived-projection` (`db.clj:276`) becomes existing-metadata identity, otherwise `(vary-meta database assoc :seon.schema/projection (schema/load-projection database))`; delete packaged-overlay reconstruction. `carry-projection-state` preserves an existing immutable snapshot; no later atom advancement changes it. Delete `connection-projection-state`'s running-instances search (`db.clj:225`) once every constructor above attaches metadata: connection metadata is its only authority.
The writer already supplies P to `encode-transaction-in` (`db.clj:4166`), which adapts `:db.fn/call` at `schema/datahike.clj:462` and stamps at `:405`. Retain that stamp, including nested returned transactions; delete redundant indexer stamp only after this path is proven. No dependency patch or second adapter. Keep validator and codec behavior. Drop ambient read/transaction fallbacks in db and `problems.clj:386` now, so the raw-value refusal regression passes from commit 1.
Dependency: Datahike `41c79c1a70f108cf969b8c5ec6d3ba81c8835eb8`, `reference-code/datahike/src/datahike/db/transaction.cljc:1153` invokes `(apply f db args)` with its transaction database. Supplied inputs are this database plus codec's P/f/args; event is each transaction-function invocation; work is metadata assoc plus actual returned operations, not whole-program reconstruction. Malli `606083c5c5b388e84d169c7080af33ed3ec242ae` retains compiled registry objects. No new registry cache.

Land `registry-isolation-test/carried-projection-follows-database-value` here, using two canonical fixture branches and the existing `cluster-projection` setup (`registry_isolation_test.clj:34`), converted to explicit admitted projection/state. Assert different A/B projections; identity on repeated carried reads; A's left-only declaration absent from B; retain A database, advance B state, assert A identity unchanged. A raw dependency db must yield nil carriage and `problems/problems` must refuse with `:seon.error/layer :seon.schema/projection` even inside legacy B binding. Keep this regression unchanged through commit 5; remove its temporary binding in commit 5. Add transaction-function read assertion to this same behavior class: the callback sees identical P and decodes a stored heterogeneous value correctly. Preserve malformed-row/admission assertions when converting loader tests; delete only fingerprint/reconstruction machinery assertions.

Scratch probe **P1**, after loading/arming this commit on scratch `projection-sweep`:

```clojure
(let [c (seon.cluster.boot/connection "projection-sweep")
      d (seon.db/db c) p (seon.db/carried-projection d)]
  {:carried (some? p)
   :same (identical? p (seon.schema/projection-from-database d))
   :relation (seon.db/q '[:find ?x :in [[?x]]] [["unchanged"]])})
```

Expect `{:carried true :same true :relation #{["unchanged"]}}`; run the named identity/decoder regression under armed contracts on scratch. Never use default for implementation probes.

## 4. Commit 2: explicit contracts and callers

Own C/O table paths plus `resources/seon/schemas/seon.source.edn` for the recording member, `help_trial_test.clj`, and the focused caller tests. Carry the resource and consumer in one commit. Estimate **−30 to +80 lines**, nine C rows/five O decisions (corrected no-op arities are not fabricated changes).
Apply §2 in table order. Keep transport definitions until all callers are gone. Executor removals may move with their dependent M readers into commit 3, as one loadable slice. Core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`, `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:29,52,148` already selects supplied Executor and executes its task; Seon supplies explicit proc environment at `flow.clj:123`. Removing a wrapper changes no scheduling policy and introduces no recomputation event.
Probe **P2**: repeat P1, then `(#'seon.test.runner/report-options {:seon.schema/projection (seon.db/carried-projection (seon.db/db (seon.cluster.boot/connection "projection-sweep")))})`; expect a report-options map with identical supplied P, a render profile and time limit. Exercise the existing detached-effect and two-context contract regressions once; no full gate.

## 5. Commit 3: M sweep in the table's file order

Own the following source files; estimate **−110 to −210 lines**. The table's 58 M occurrences include rows already discharged in commit 1; do not edit them twice. `U` = replace `(schema/call-with-projection p (fn [] BODY))` / state twin with BODY, preserving a `let` for any construction/effect in the removed argument. For `#(...)`, invoke the existing thunk or unwrap its body; never delete parentheses by regex. `R` = remove ambient arm, and remove any now-redundant reconstruction arm; preserve named absence refusal. A held database wins over a context from a different basis.

| File, source sites | Per-site rule |
|---|---|
| `db.clj:307,1237,1301,3740,3961,4136` | R in resolve/read/arity/transact; direct question in ask; U validator. Relation constant handled in C1. |
| `cluster.clj:315,652` | U; obtain one `(db/carry-projection-state (db/db connection) projection-state)` for config/oversight. |
| `cluster.clj:1319,1323,1356,1360,1424,1459` | Carried candidate only, U; use explicit population request P for index!; no loader or forms fallback here. |
| `cluster.clj:1617,2066,2104,2194,2825` | U after construction stamp; index request already carries P; adoption uses post-adoption database's P; cold roots carry candidate P; executor deletion as §2. |
| `cluster/boot.clj:34,165,180` | U after connection attachment, reordered as §3. Opening branch itself requires no frame. |
| `render/web.clj:683,2186,2322,2472,3250,3432,3535` | U; explicit function P at 683; C-table handles at 2186/2322; assoc request P at 2472 using supplied db first, ctx only when no db; config reads db at 3250/3432; handler no wrapper. |
| `render.clj:126,137,340,1030` | R request-profile; U config/schema-producers/invoke; invoke keeps db/ctx. Rename expected keyword at 150 to `:seon.schema/projection`. |
| `render/walk.clj:383,551,552,763,764` | R current-projection; U; assoc selected P into request passed to root-pull-plan/root-acquisition. |
| `test/runner.clj:131,1992,2063,2409,4916` | C table report/base/coordinator; U recursion with arming; R program-digest. |
| `test.clj:443,1564,2066` | U bounded-result/resolvers/check-request; preserve custody and request P/db. |
| `turn.clj:5265` | U pass; its handle/connection already owns state. |
| `bootstrap.clj:737,739` | Use ctx P then `(next-entry-in (assoc request :seon.schema/projection projection) turn-id)`. |
| `config.clj:663,747`; `problems.clj:386` | R; retain named missing projection refusal. `apply-compiled!` must not retain its ordinary reconstruction arm. |
| `effect.clj:487` | §2 binding body; no removal of custody. |
| `fn.clj:3360,3370` | Request P or carried connection value; U callback; existing codec stamp covers callback database. |
| `instrument.clj:548,822` | Remove only ambient scan tail; supplied apply member plus named refusal. |
| `reconcile.cljc:315,319` | R then direct `(plan-transaction-data projection db request)`. |
| `flow.clj:1133`; `cluster/registry.clj:231,233` | Plain executor; registry constructor attaches once, then R/U recording. |
| `cluster/source.clj:311`; `cluster/agent.clj:583` | U on already attached recording connection / supplied handle. Remeasure source submit's historical 728→147 ms separately; don't treat it as current. |
| `schema/edn.clj:607`; `test/arm.clj:246`; `test/fast.clj:73` | Explicit optional admission P; direct require loop; explicit arming/recording request P. |
| `sci/eval.clj:1816,2266,2935` | U; retain acquisition db stamp, ctx P, custody and read-database binding. Never replace state refresh with a stale copied P. |

Probe **P3**: P1 plus `(seon.config/effective (seon.db/db (seon.cluster.boot/connection "projection-sweep")) "projection-sweep")`, expected effective configuration, and `(seon.problems/problems (datahike.api/db (seon.cluster.boot/connection "projection-sweep")) {})`, expected named projection refusal. Observe first page paint and the render proc ping, independently of these JVM forms.

## 6. Commit 4: tests, lexical substitutions and exclusions

Own the 86 files returned by `rg -l 'handed-projection' test`, union the 30 returned by `rg -l 'call-with-projection' test`, minus files assigned to commits 1/2 until their edits are committed. Also own codec, registration, admission/population and one-arg shape tests touched by commit 5's retirement. Count at review: **511 text matches, 508 matched lines, 86 files**, including names/comments/redefs; not 511 calls. Estimate **−100 to −300 lines**, ~560 text occurrences including wrappers. Recount after lanes land; do not claim the input's 509 as current.
Safe substitution is **range-limited**, after the actual lexical authority is explicit. Use fully qualified destination names to avoid alias edits. For a selected region holding `connection`, the literal BRE rule is:

```sh
sed 's/(schema\/handed-projection)/(seon.db\/carried-projection (seon.db\/db connection))/g; s/(seon\.schema\/handed-projection)/(seon.db\/carried-projection (seon.db\/db connection))/g'
```

Apply to selected source forms, not a whole repository stream. Patterns/rules:

| Pattern | Exact conversion |
|---|---|
| Existing local `projection` | Both literal zero-arg spellings → `projection`. Remove newly redundant `let [projection projection]` structurally. |
| Existing immutable `database` / `db` | → `(seon.db/carried-projection database)` / `(seon.db/carried-projection db)`; prefer this captured basis over rereading a connection. |
| Fixture callback `[connection]`, `[conn]`, `[left]`, `[right]` | Substitute the actual parameter in `(seon.db/carried-projection (seon.db/db PARAM))`; select the relevant branch, never the other callback's connection. |
| State binding deliberately under test | Use `(:seon.schema/projection @projection-state)` as the explicit input, or stamp a db with that state; never blindly use fixture P. |
| Call-with-projection/state wrapper | Structural U only after every body's read converted; keep construction expressions and setup/cleanup effects. No sed rule for balanced Clojure bodies. |
| Test name/docstring/expected keyword | Rename ambient vocabulary to supplied/carried where it describes surviving behavior; expected member becomes `:seon.schema/projection`. Never turn a symbol under `with-redefs` into a call expression. |

**Not mechanical:** `ai_test.clj:79` has no lexical connection (contrary to the input); wrap the test's body with canonical `with-database (fn [connection] ...)` or accept the installed test supplier's connection, then bind P once. Apply the same explicit-fixture rule to top-level helpers/properties without a world. Do not introduce another dynamic fixture Var. A helper used by several tests receives P as an added first parameter; callers pass their fixture P. Pure schema-unit tests can use an explicitly constructed small projection when that is their subject.
`registry_isolation_test.clj:66` binding-precedence assertions retire; two-cluster behavior and commit 1 identity regression remain. `instrument_test.clj:1030,1051` becomes explicit apply member/missing-member refusal, keeping “no collection before refusal”; remove handed-projection redefs. `schema_redeclare_test.clj`, `schema/edn_test.clj:385`, `schema/datahike_test.clj:163`, `schema_test.clj:824`, `db_test.clj:1143` require pure candidate/explicit `-in` calls; do not delete wanted schema-change behavior. `test/runner_test.clj:95` retains the base's deref/lease protocol. Codec tests use `encode-transaction-in P` / `decode-attribute-value-in P`. SCI source strings containing registration syntax are **not** text-substitution candidates; preserve interpreted evaluation of their arguments.
Probe **P4**: repeat P1 and invoke the focused identity regression via `(clojure.test/test-vars [#'seon.registry-isolation-test/carried-projection-follows-database-value])` on scratch with the canonical fixture armed; assertion tally must be positive and failures/errors zero. Test conversions may be prepared concurrently with commit 3 **only** for disjoint files and the first three lexical patterns above. The whole 86-file set is **not** a mechanical second lane; keep registration/redefs/fixture/API changes with the source owner. No second lane edits `test_support.clj` or commit 1/2 tests.

## 7. Commit 5: last callers and transport/fallback retirement

Own `schema.clj`, `schema/{edn,datahike,admission}.clj`, `program.cljc`, `sci/eval.clj`, `cluster{,/source}.clj`, `instrument.clj`, remaining affected tests. Estimate **−450 to −650 lines**, four Vars, two projection binders, three readers, forms binder, registration delta and fallback owners. This closure exceeds the 24 transport files; reserve it explicitly.
Delete `*projection*`, `*projection-state*`, `*packaged-forms*`, `*candidate-forms-overlay*`, `call-with-projection`, `call-with-projection-state`, `active-projection`, `current-projection`, `handed-projection`; delete `call-with-forms` and its codec wraps at `schema/datahike.clj:488,526` (predicate proof §1). Codec remains. Delete zero-arg declaration constructor, ambient shape wrappers plus `shape-projection`, `activate-projection!`, activation/snapshot/restore/clear/registry convenience APIs with their last converted callers.
Remove A1-4 fallback block (`schema.clj:929–1085`), `candidate-forms`, its mutation helper and packaged fallback cache (`schema/edn.clj:361`); retain the stamped explicit `packaged-forms` resource reader. `instrument/state` must receive P explicitly, add it to its existing returned map, and `restore!` uses it, never packaged reconstruction. Convert callers `test_support.clj:1079` and `instrument_test.clj:1310` with their fixture P; the preservation helper takes P plus body rather than obtaining ambient P. `cluster/source/publish!` reads explicit candidate P. Delete obsolete zero-arg codec readers with callers converted to `-in`.
Convert `schema/admission.clj:179`'s default-registry-excluding to derive its **cold file admission** population from explicit packaged forms, subtract candidate files as before. In `program/with-contract-facts :943,954`, require the producer's existing schema-forms or derive them from its supplied compile-options registry; remove `registered-schemas` fallback. Delete dead CLJS arms referring to retired ambient APIs; do not rebuild the deleted CLJS pod. `canonical-schema-rows` retains explicit forms/projection arities only. `registered?` becomes explicit projection lookup; `schema-definition` retains `[forms k]` only.
Registration is not “delete the delta and hope.” `sci/eval.clj:2461` already owns declaration capture and pure `projection-with-schema`/`projection-without-schema`. For a reader-classified register form (`sci/reader.cljc:356,372`), evaluate its third expression once through that same SCI evaluator, then call the pure candidate operation with explicit P/key/definition; return the key as shown value and build the row from the **evaluated** definition. For unregister, validate pure removal and return the key. Preserve source/key matching refusal and transaction-time final validation; no atom overlay, new binding or second evaluator. Non-declaration forms use the existing `eval-form!` unchanged. Change its owning closure to accept the selected expression, keeping SCI namespace and interruption/custody. Retire JVM mutating register/unregister entry points after their fixture callers use pure candidate functions; keep the reader's declaration syntax recognition. A literal parsed schema expression is not a substitute for its evaluated value.
Preserve regressions for evaluated schema expressions, invalid candidates leaving branch unchanged, same-evaluation visibility after settlement, and unregister dependent refusal. Delete only delta bookkeeping assertions. Remove temporary legacy binding in commit 1's identity regression. Probe **P5**: P1/P3/P4 plus existing `seon.sci.reader-test` and `seon.cluster.turn-test` schema declaration/removal cases on scratch. No surviving Var reference may be deleted ahead of its caller conversion.

## 8. Completion proof, measurements and ownership handoff

Every commit leaves its source loadable and the named scratch probe answering; record exact form/result, loaded revision, arming evidence and line delta in the executing lane's landing note. Estimates are net lines, not promises. This spec-writing assignment runs **no tests**. During execution use installed focused requests; if necessary `bin/test-fast --paths <owned paths> -- <focused namespaces>`, HEAD plus owned paths only. Lanes never run `bin/test` gates. If the shared tree cannot load, prove HEAD plus only the lane's diff from a `git archive` snapshot with the shared caches linked (no git worktree, owner 2026-09-23); name foreign boundary in landing evidence. Never edit/resume/message another lane's session. Default is never the scratch root.
The orchestrator runs **`bin/test --platform` once at the step's end**, affected integration and a **from-zero scratch-root boot**, particularly after the request schema/registration changes. Confirm ready layers, source program identity, carried snapshot and a transaction-function decode, not just process exit. Reset reuses boot; no reset of default. Work exceeding ten seconds, including cold boot/index, needs the existing owner authorization explicitly recorded for that run.
Remeasure the original acquisition after final source adoption, using `test/seon/sci/branch_execution_test.clj:92` setup (two branches, changed `cut?`, same `a-ctx`): `(measured #(eval/acquire! {:seon.sci.eval/ctx a-ctx :seon.db/db (db/db a)}))`. Record **1,658.341 ms → measured new ms**, no invented target result; repeat unchanged acquisition separately. The baseline is [commit 1 landing](../landing/lane-realities-commit-1-2026-09-22.md:137). Record heap before/after and whether compilation/acquisition was cold. Publication slices also owe the clock row from `docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh`; no suite substitutes for that measurement.
Exit “every reconstruction fallback; the last dynamic-var authority” requires this search to print **nothing** (exit 1). Local variables named `current-projection` are legitimate, so match API calls/definitions, not all prose or local names:

```sh
rg -n '\*(projection|projection-state|packaged-forms|candidate-forms-overlay)\*|\((schema/|seon\.schema/)?(call-with-projection(-state)?|call-with-forms|handed-projection|current-projection|active-projection|candidate-forms|registered-schemas|begin-registration-delta|call-with-registration-delta)\b|\(defn-? (call-with-projection(-state)?|handed-projection|current-projection|active-projection|derive-projection-from-database)\b|\((schema/|seon\.schema/)?declaration-projection\s*\)|warn-classpath-fallback!|packaged-population-cache' src
```

Also inspect `rg -n 'load-projection|projection-from-database' src`: load calls only the named cold/acquisition/audit owners above; projection-from-database has one metadata-read implementation and no derivation. This second positive inventory is necessary: zero retired-name matches alone cannot detect a renamed fallback. Two-argument readers were removed in commit 1; no ordinary missing-carriage path loads rows/resources.
Run this source sweep **alone in src/**. Observed collision map (dirty files and the named landing notes, not an assertion that every historical lane remains running): wrappers/error-contract lanes → `instrument.clj`, instrument resource/test; A2 db deletion → `db.clj`, db resource/test; indexer facts → `fn.clj`, schema resources, fn tests; publication/reload → `cluster.clj`, `cluster/source.clj`, publication tests; lifecycle/entrance supplier → `sci/eval.clj`, `test.clj`, runner and `test_support.clj`; schema retirement/population → `schema.clj`, `schema/edn.clj`, schema resources; program partition/three-way → `program.cljc`, program tests. Hook's `bin/seon-hook`/operator script are disjoint, but its publication caller overlaps cluster. Research-only lanes are disjoint. These are the files the orchestrator must release, regardless of whether the current count is nine; dirty status alone cannot prove a lane ended.
Only the selected lexical test conversion subset may run beside commit 3, with disjoint test ownership and no fixture/API edits. Commit it as commit 4 after source caller changes land. Stage/commit exact owned paths; no `git add -A`. End owned shells and verify actual process exit before cleanup/reporting; preserve uncertain scratch evidence and never sweep another lane's live root.
