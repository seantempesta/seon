---
type: research
status: current
created: 2026-09-23
scope: Native producer outputs, current Seon projections, and minimal query-and-render context facts
---

# Producer inventory: data tells its own story

## 1. Answer first

Seon already projects source analysis, schemas and execution observations into rows. The smallest composition is to retain those producers, preserve their structured values at the boundary, and query relationships into the existing entity render pairs.

**Evidence convention:** VERIFIED means inspected source or the explicitly recorded CLI probe, not installed behavior. UNVERIFIED marks proposals, estimates and missing runtime proof. Within tables, V = VERIFIED, U = UNVERIFIED; a column marked U is a recommendation throughout. This research made no Seon evaluations, transactions, reloads, provider calls or system lifecycle calls. No live schema population or adoption claim is made.

1. **VERIFIED — use ordinary metadata for authored links, but distinguish source metadata from evaluated metadata.** `defn` merges docstring/attribute maps into Var metadata (`reference-code/clojure/src/clj/clojure/core.clj:299`); clj-kondo exports the authored map (`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:119`). Probe P1 below retains `(quote [clojure.core/inc :story/input])`. U: allow declared literal/quoted data without evaluating arbitrary metadata to discover links. A qualified symbol is an observation, not an instruction to call it.
2. **VERIFIED — most raw information is already available upstream; Seon's projection is selective.** `:added`, `:deprecated`, protocol identity and keyword `:reg` are emitted at `reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:92` and `:180`; Seon drops those top-level fields at `src/seon/fn/analyzer.clj:120` and `:146`. Metadata survives that adapter but `var-row` selects particular keys (`src/seon/fn.clj:602`). U: add only fields answering a named query; do not build another analyzer.
3. **VERIFIED — a mention, a call and a registration are different facts.** Kondo emits arity only when available, quoted symbols separately, and registration only when hooks provide it (`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:19`, `:60`, `:176`). Current Seon already separates `calls`, `references`, `keywords`, `writes`, `invokes` (`resources/seon/schemas/seon.fn.edn:49`, `:68`, `:71`, `:18`). U: never promote every keyword into a dependency or every metadata symbol into executable reach.
4. **VERIFIED — Malli already owns structured explanations and schema navigation.** `form`, `properties`, `children`, `explain`, `-instrument` exist (`reference-code/malli/src/malli/core.cljc:2579`, `:2586`, `:2600`, `:2664`, `:3125`). Seon turns explanation paths/actuals into multiple component families (`resources/seon/schemas/seon.instrument.explanation.edn:10`). U: retain the native logical shape and use the existing storage bridge; do not create a second explanation language.
5. **VERIFIED — distinguish evidence loss from mere encoding.** Test assertion expected/actual values become strings (`src/seon/test/runner.clj:189`); evaluation triage becomes `pr-str` (`src/seon/sci/eval.clj:3370`); schema forms become `pr-str` (`src/seon/schema.clj:3870`). Only the first necessarily loses native value structure in that projection. U: standard EDN is not a custom mini-language; replacement requires a proven storage representation, not a blanket ban on strings.
6. **VERIFIED — a past renderer is a symbol observation; current storage also links its living row.** `evaluation-facts` writes both `/renderer` and `/renderer-fn` (`src/seon/turn.clj:105`; declarations `resources/seon/schemas/seon.eval.edn:2`). U: remove the redundant ref after consumers use the observed symbol plus definition evidence. Preserve exact `/shown`; it records what was seen (`resources/seon/schemas/seon.eval.edn:43`).
7. **VERIFIED — provider usage has a historical authority and a current display mirror.** Attempts retain `usage-edn` and effective `settings-edn` (`resources/seon/schemas/seon.ai.attempt.edn:9`); `model-observation-tx` writes last-used/latency/tokens-per-second gauges (`src/seon/ai.clj:1007`). U: derive the display from indexed attempts when measured retrieval permits; do not delete historical effective settings in favor of mutable configuration refs.
8. **VERIFIED — not every schema is stored data.** Profile schemas explicitly declare volatile values (`resources/seon/schemas/seon.profile.edn:1`); wake schemas describe derived observations (`resources/seon/schemas/seon.wake.edn:25`); Flow ping replies are transient (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136`). U: “universal context” needs readable values and durable evidence, not a transaction for every object.
9. **VERIFIED — issue citations still convert observed names into living refs.** `resources/seon/schemas/seon.issue.edn:8` declares function citations as refs, while unresolved tokens become strings (`:27`); `src/seon/issue.clj:412` resolves text into those groups. U: retain cited qualified symbols/keywords as values, resolving them in queries; this is a concrete mismatch with the requested observation rule, unlike function call edges which are already values.
10. **VERIFIED — omitted producer classes matter.** Render/prompt captures (`src/seon/context.clj:359`), external-effect receipts (`src/seon/effect.clj:247`) and Clojure `datafy`/`nav` (`reference-code/clojure/src/clj/clojure/datafy.clj:15`) also produce relevant data. U: include these without inventing a generic event entity with a stored kind.

## 2. Evidence and producer matrix

**VERIFIED — checkout scope.** Initial HEAD `c442a4d69c24733e2c7becb8abac12b51eac0981`, branch `refactor/agent-platform`. Source citations refer to working-tree bytes read on 2026-09-23, including other lanes' uncommitted changes. This is a moving shared checkout, not proof those bytes were installed. Initial dirty owners included `fn.clj`, `instrument.clj`, `test.clj`, `cluster.clj`, `deps.edn` and the Malli gitlink. No foreign file was changed by this research.

**VERIFIED — dependency checkout revisions** (`git -C reference-code/LIB rev-parse HEAD`; all seven dependency working trees reported clean at this check):

| Library | Revision |
|---|---|
| clj-kondo | `57252e07975710aa579b24f0d1b2b1e04195caa2` |
| Clojure | `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` |
| Malli | `56394c54e34415a333d6281c4bfddf7122d02bd5` |
| SCI | `fcbd8862800e638dc0f8f5521111f999279cbcd2` |
| Datahike | `c79cd03a44427ac1734d917c7484c3e529c77716` |
| langchain4clj | `889f9e60e3d2bb13948f9a9921aa294712fcace7` |
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051` |

**VERIFIED — native metadata is an open map, not a standardized link ontology.** Clojure documents metadata on symbols/collections and conventional Var keys; it does not make a custom `:see-also` a resolver. [Metadata](https://clojure.org/reference/metadata), [Vars](https://clojure.org/reference/vars). The local implementations below are the authority for version-specific details. No live provider API behavior was researched or asserted.

Paths in the matrix use `S/` = `src/seon/`, `R/` = `resources/seon/schemas/`, `L/` = `reference-code/`. The numbered sections expand each row's citations and qualifications. “Unused” means absent from the inspected adapter/projection, not proof that no code anywhere reads the value. Keys abbreviated `/x` retain the explicitly named family namespace.

| Producer | (a) Native output — V | (b) Current facts/writer — V | (c) Available but unused — V unless qualified | (d) Redundancy/direction assessment | (e) Smallest specification — U |
|---|---|---|---|---|---|
| 1 Source analysis | Kondo maps: namespace/Var defs, usages, keywords, protocols, Java members; §2.1 | `R/seon.fn.edn:127`, `R/seon.ns.edn:1`; `S/fn.clj:299,602` | Added/deprecated, keyword registration; instance/Java member output not enabled in `S/fn/analyzer.clj:22` | V string arglists/spec; U retain indexed reach but derive callers | Definition → namespace/file refs; observed callee/schema names as values; `clj-kondo.core/run!` |
| 2 Runtime metadata | `meta`, symbol→Var maps, `:doc :arglists :ns :name :file :line :column`, §2.2 | Same declaration families; runtime fallback `S/instrument.clj:191` | Custom qualified keys legal; generic metadata persistence not in `S/fn.clj:602` | U second runtime declaration mirror would compete with source/branch rows | Use `meta`/`ns-interns` for host installation inspection; persist chosen authored facts through definition producer |
| 3 Malli schemas | Schema forms/properties/children/refs/registry, §2.3 | `R/seon.schema.edn:80`; `S/schema.clj:3848`; shape writer `S/fn/schema_shape.clj:306` | Property maps retain descriptions/custom symbol values natively | V serialized form plus shape/reference projection; U potential redundant materialization, not automatic deletion | Authored form authority; `m/schema`, `m/form`, `m/walk`, supplied registry; symbol renderer values, keyword schema names |
| 4 Instrumentation | Report keyword + map; explanation errors/path/in/schema/value, §2.4 | `R/seon.instrument.explanation.edn:10`; `S/instrument.clj:586,638` | Native paths, args, guard, schema-at-call exception | V componentized paths/actual/humanized; U simplify only with lossless evidence | Refusal → observed function symbol/digest, native explanation values; `m/-instrument` and `m/explainer` |
| 5 Tests | Reporter maps, contexts and Var stack, §2.5 | `R/seon.test.failure.edn:1`, `R/seon.test.member.edn:1`; `S/test/runner.clj:203,1499` | Native expected/actual and full testing-var hierarchy not retained by failure projection | V expected/actual strings and recurrence counters; U derive display/recurrence only from complete retained events | Completion → tested symbol/digest, counters, ordered failure components; `clojure.test/report` events |
| 6 Evaluations | SCI value/namespace; parse form+source; bound writers; exception data, §2.6 | `R/seon.cluster.eval.edn:27`, `R/seon.eval.edn:1`; `S/turn.clj:91,1436` | Structured triage before stringification; live result handle | V historical renderer symbol+ref duplication; U author flag removable only if derivation complete | Evaluation → turn ref + ordinal; exact source/shown/output; volatile live result; `sci/eval-form` |
| 7 Errors | `ex-info`, `ex-data`, `Throwable->map`, triage, §2.7 | `R/seon.error.edn:25,268`; `S/error.clj:513,1336` | Native class symbols; complete cause/stack structures | V chain class converted to string; U projected evidence can be simpler, but bounds cannot silently drop evidence | Flat producer-specific error; occurrence ownership; observed function/schema values; `Throwable->map` plus Seon attribution |
| 8 Transactions | Report databases/datoms/tempids/metadata, §2.8 | `R/seon.db.edn:287`, `R/seon.store.edn:1`; `S/db.clj:4223,4475` | Effective report/history already answer changed facts | U duplicate publication/change ledgers unnecessary; no proof every current digest is redundant | Domain writes + minimal tx provenance, current DB/commit identity; listener consumes report, never stores database object |
| 9 Provider calls | Java ChatResponse or text; Seon HTTP JSON, §2.9 | `R/seon.ai.attempt.edn:1`; `S/turn.clj:3967`; `S/ai.clj:986` | Provider IDs/model/tool calls absent from inspected completion projection | V latest-model gauges duplicate observations; raw JSON strings are transport data, not a Clojure DSL | Attempt → turn/model relationships + historical settings/usage; preserve unknown provider fields |
| 10 Turns/messages/tasks | Native domain transition maps, §2.10 | `R/seon.turn.edn:2`, `R/seon.message.edn:19`, `R/my.plan.item.edn:1`; `S/turn.clj:390`, `S/cluster/message.clj:149`, `S/plan.clj:574` | Native transaction time, parent traversal, token subjects already available | V wake unanswered is query-shaped; U unify plan/task only after lifecycle equivalence | Writer-owned transitions; recipient/agent refs; observed subjects tokens; preserve explicit sibling position |
| 11 Profiling | JDK long nanoseconds/adders/accumulator; §2.11 | `R/seon.profile.edn:1`; `S/profile.clj:55,138` produces values, no durable writer here | Raw ns retained in cells; display rounds to ms/us | V mean/top are derived, volatile; U don't persist rankings or infer exclusive time | Version/origin-qualified immutable cumulative observation if durability needed; existing completion writer |
| 12 Files/Git | Bytes/path; index mode/object-id/stage/path; §2.12 | `R/seon.fn.file.edn:1`; `S/cluster/source.clj:54,106`; `S/test/cache.clj:176` | Commit/blob/blame provenance not emitted by these readers | U gitlink pin != dirty checkout bytes; digest != publication counter | Definition → file ref; file path/digest; dependency pin value; query blame on demand |
| 13 Flow | Ping state/status/ports/count; error ex/pid/state/cid/msg, §2.13 | `R/seon.flow.edn:271`; `S/flow.clj:1085,1247` | Native port/count/state available on fault before projection | U ping roster is observation, not permanent health; no generic status mirror | Preserve fault + execution identity at existing error writer; `flow/start`, `flow/ping` |
| 14 Rendering/context | AI text, HTML value, prompt capture; §2.14 | `R/seon.context.capture.edn:33`; `S/context.clj:359`; `S/render/value.clj:703` | Clojure `datafy`/`nav` inspection, not currently established as Seon producer | V exact saved text is historical; U duplicated current context assembly is candidate | Query → entity render pair/default printer; save shown text once, no stored context-kind |
| 15 External effects | Declared capability return; shell exit/stdout/stderr; §2.15 | `R/seon.effect.edn:20`; `S/effect.clj:247,300`; `src/my/shell.clj:26` | Structured return before receipt encoding | V request/result EDN is historical evidence; U don't reconstruct by replaying effects | Effect → evaluation/turn ownership, operation symbol, outcome; existing writer and declared capability |

### 2.1 Clojure source definitions and static analysis

**VERIFIED:** `reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:87` emits Var definition `:ns/:name/:defined-by/:defined-by->lint-as/:protocol-ns/:protocol-name` symbols, `:filename/:doc/:arglist-strs` strings/vector of strings, `:fixed-arities` set of integers, `:varargs-min-arity` integer, flags and user `:meta` map. Added/deprecated/test are optional values supplied by the declaration/analyzer, not an invariant boolean triple. Namespace definitions at `:126` emit name symbol, locations, metadata and optional language/in-ns. Usages at `:19` emit `:from/:from-var/:to/:name` symbols, optional integer arity, integer locations, alias/refer and callee attributes. `:dispatch-val-str` is an upstream string representation, not a new Seon syntax.

**VERIFIED:** Keywords at `:176` have `:name` string, `:ns` namespace value, `:reg` hook-supplied registration symbol, flags, holder and locations. Registration is not inferred for arbitrary schema keywords (`reference-code/clj-kondo/analysis/README.md:158`). Protocol implementations at `analysis.clj:192` carry protocol namespace/name, method, implementation namespace, defining symbol and ranges. Instance invocations at `:216` carry method-name **string** and range; this does not resolve a receiver class. Java members carry name/type strings, parameter-type vector and keyword flags (`reference-code/clj-kondo/src/clj_kondo/impl/analysis/java.clj:164`; docs `reference-code/clj-kondo/analysis/README.md:206`). Public JVM members only in that bytecode reader; source and bytecode output should not be conflated.

**VERIFIED:** Seon's configured outputs and normalization are `src/seon/fn/analyzer.clj:22`, `:108`, `:120`, `:130`, `:146`, `:609`. Namespace rows preserve resolver context (`src/seon/fn.clj:299`). Function/test rows keep source, file/span, digest, selected metadata, graph edges and textual arglists/spec (`:602`). Qualified keyword membership is constructed at `:423`; writes are a separate heuristic owner at `:479`, not native Kondo semantics. Protocol and Java-class analysis are enabled; Java-member and instance-invocation options are not explicitly enabled by this config. CLI defaults may differ (P1).

**UNVERIFIED — specification:** Retain source truth and analyzer-version/config identity; canonicalize only known literal metadata data. Existing calls/reference/keyword indexes are useful materialized analysis evidence, recomputed on changed source/config/dependency inputs; do not duplicate them with backlinks. Store location occurrences only if “show this exact use” is required; current sets/arity tuples cannot reconstruct multiplicity. O(changed source bytes + usages), query reverse edges by indexed symbol. Preserve unresolved tokens; do not mint placeholder entities. No reason here to retire the indexer.

### 2.2 Runtime Var and namespace metadata

**VERIFIED:** `meta` returns the attached metadata map (`reference-code/clojure/src/clj/clojure/core.clj:203`). `defn` accepts docstring, leading/trailing attribute map, and emits quoted arglists (`:285`). `ns-publics` returns symbol→public Var mappings; `ns-interns` includes private Vars (`:4212`, `:4230`). `:ns` is a Namespace object at runtime, `:name` a symbol; arglists are Clojure collections, `:doc/:file/:added` conventional strings, `:line/:column` integers when available. `deftest` supplies executable `:test` metadata (`reference-code/clojure/src/clj/clojure/test.clj:624`). Not every Var has every conventional field.

**VERIFIED:** `src/seon/instrument.clj:191` reads runtime arglists as fallback; `:837` collects contracts through loaded `ns-interns`. Authored metadata travels through `src/seon/fn.clj:602`, not a generic all-metadata store. `resources/seon/schemas/seon.fn.edn:17` declares textual arglists. **UNVERIFIED:** absence of a separate runtime metadata writer outside these inspected owners; installation was not inspected.

**UNVERIFIED — specification:** A declared qualified metadata key containing quoted symbol/keyword collections is enough for direct authored references; `:see-also` has no special compiler semantics. Treat `:tag`, `:inline`, `:macro`, `:dynamic`, `:private` as compiler/Var facts, not prose labels. Never store the Var, Namespace, function-valued `:test` or executable metadata expression as a durable value. Normalize Namespace to its symbol; runtime metadata is installation evidence and may differ from branch declarations. Enumerating `ns-interns` is O(namespace Vars); inspect only replaced Vars when proving adoption.

### 2.3 Malli declarations, properties and references

**VERIFIED:** `m/schema` compiles a form with options/registry (`reference-code/malli/src/malli/core.cljc:2555`); `m/form`, `m/properties`, `m/children`, `m/entries`, `m/deref` expose form, property map, children, entries and referenced schema (`:2579`, `:2586`, `:2600`, `:2776`, `:2823`). Registry `schema`/`schemas` lookup existing declarations (`reference-code/malli/src/malli/registry.cljc:97`). A property is ordinary data; a keyword literal inside an enum is not necessarily a schema ref.

**VERIFIED:** Seon uses Malli's ref-aware walker (`src/seon/schema.clj:61`) and writes `:seon.schema/key`, serialized `/form`, `/references`, declaration digest and projected properties (`:3848`). Shape links are declared at `resources/seon/schemas/seon.schema.edn:81`; shared shape ownership is in `resources/seon/schemas/seon.schema.shape.edn:1` and `seon.schema.shape.child.edn:1`. **UNVERIFIED:** whether every shape materialization can be removed without slowing existing signature/query consumers.

**UNVERIFIED — specification:** Keep authored schema as authority, compile once per immutable form/registry dependency content, derive descriptions/render pair/refs with Malli. A schema naming another schema holds a keyword value; a canonical shared shape entity relation is a ref, not parent-owned by every user. Do not flatten arbitrary property values into independent entities just to make them queryable. Materialize only demonstrated indexed queries. Cost O(changed/reached schema nodes), not registry enumeration per render.

### 2.4 Malli instrumentation and explanations

**VERIFIED:** `m/-instrument` reports `:malli.core/invalid-input` with `:input/:args/:schema`, invalid-output with `:output/:value/:args/:schema`, invalid-guard with `:guard/:value/:args/:schema`, invalid-arity with integer `:arity`, set `:arities`, args/input/schema (`reference-code/malli/src/malli/core.cljc:2213`). This **fork** also reports `:malli.core/invalid-schema-at-call` with `:arm/:schema/:exception` (`:2209`); do not describe it as universal upstream behavior. The report callback must throw to prevent continued execution; merely logging is not rejection (`:2217`).

**VERIFIED:** `m/explainer` returns nil for valid values or `{:schema compiled :value actual :errors seq}` (`:2647`). Error values expose `:path/:in/:schema/:value`, optional `:type` (`reference-code/malli/src/malli/impl/util.cljc:20`); Seon consumes paths and calls `m/explain` at `src/seon/instrument.clj:287`, `:609`. Current compiled wrapper calls the library and throws an `ex-info` carrying the declared refusal (`:693`). Stored explanation has ordinal, schema/value-location components, expected-shape digest, actual projection, optional humanized component (`resources/seon/schemas/seon.instrument.explanation.edn:10`).

**UNVERIFIED — specification:** Store the report's operation symbol, exercised definition/contract identity, arm and exact admitted args/value; normalize compiled schema objects with `m/form`; retain path vectors as data through the proven bridge. Reuse compiled `m/explainer` per immutable schema. Humanization is rendering, unless preserving previously shown text. Flat Seon error base remains necessary for routing/identity; generic Malli report shape does not replace domain error alternatives. Work is proportional to checked value/schema and returned problems, not all schemas.

### 2.5 clojure.test and Seon test evidence

**VERIFIED:** `clojure.test/do-report` attaches location for failure/error, then calls multimethod `report` (`reference-code/clojure/src/clj/clojure/test.clj:353`). Report variants at `:374` carry `:type` keyword, arbitrary `:expected/:actual`, optional message; `:actual` may be Throwable. Context strings and Var hierarchy are dynamically bound, not automatically complete members of every event (`:269`, `:271`, `:716`). `deftest` is a Var with executable test metadata, not a separate language.

**VERIFIED:** Seon captures begin/end/pass/fail/error and elapsed nanos (`src/seon/test/runner.clj:203`), stringifies expected/actual, orders contexts, records class symbol/file/line/signature (`:189`); completion and transaction owners are `:1201`, `:1499`, `:1573`. `resources/seon/schemas/seon.test.failure.edn:8` declares strings, `:18` first/last-run refs plus recurrence count/time. These are not complete native reporter events.

**UNVERIFIED — specification:** Retain test identity/digest/input evidence and positive completion independently of assertion counts. Failure occurrences own ordinal, contexts, observed testing symbols, expected/actual data and complete throwable evidence; live objects use explicit unavailable/bounded representation. Do not claim a successful zero-assertion test without the existing admission rule. First/last/count become queries only if occurrence retention is sufficient; deleting aggregate counters before that would lose evidence. Report processing O(events); static reach selection remains distinct from observed execution.

### 2.6 REPL / SCI evaluations

**VERIFIED:** `sci/eval-string*` returns a value; `sci/eval-string+` returns `:val` and Namespace `:ns`; `sci/eval-form` evaluates parsed data (`reference-code/sci/src/sci/core.cljc:353`, `:359`, `:421`). `parse-next+string` pairs form and source (`:411`); `stacktrace` reads SCI exception frames (`:430`). These APIs do not inherently return a Seon transaction, durable result symbol or captured stdout/stderr bundle.

**VERIFIED:** Seon's SCI failure path creates textual error and EDN triage with `main/ex-triage (Throwable->map throwable)` (`src/seon/sci/eval.clj:3360`); turn settlement selects fields (`src/seon/turn.clj:91`), and `record-evaluated-call` owns recording (`:1436`). Current receipt schema includes source/run/ordinal/namespace/output/error/triage/read-evidence (`resources/seon/schemas/seon.cluster.eval.edn:27`). `seon.id` owns evaluation/result identities (`src/seon/id.clj:47`, `:55`); no new handle producer is needed.

**UNVERIFIED — specification:** Preserve exact input and exact shown text, observed namespace symbol, turn relation, ordinal, read basis/evidence and outcome. Bind live values in the existing SCI context; do not pretend result EDN recreates arbitrary objects after restart. Keep stdout and stderr distinguishable if both are captured; current `/error` is a diagnostic, not proof it contains original stderr. Triage is a query/render over retained exception evidence if that evidence actually survives. Runtime behavior and result-handle availability after compaction remain unprobed.

### 2.7 Exceptions and flat domain errors

**VERIFIED:** `ex-info` constructs ExceptionInfo with map and optional cause; `ex-data` returns that map or nil (`reference-code/clojure/src/clj/clojure/core.clj:4924`). `Throwable->map` emits `:via` vector, `:trace` vector, optional `:cause/:data/:phase`; each link has symbol `:type`, optional string `:message`, map `:data`, `[class-symbol method-symbol file-string-or-nil line-int]` `:at` (`reference-code/clojure/src/clj/clojure/core_print.clj:467`). It does not supply all frames for every intermediate cause or suppressed exceptions. `ex-triage` emits qualified `:clojure.error/*` keys for phase/source/path/line/column/symbol/class/cause/spec (`reference-code/clojure/src/clj/clojure/main.clj:207`); `ex-str` renders them (`:268`).

**VERIFIED:** `src/seon/error/refusal.clj:75` uses `Throwable->map`, adds selected first-party frames per cause and converts class symbols to strings. `src/seon/error.clj:513` prepares bounded declared facts; `:1336` writes recurrence/routing evidence. `resources/seon/schemas/seon.error.edn:268` keeps string throwable-class and symbol exception-class; `/chain` is explicitly transient evidence at `:282`, not independently stored attributes. Therefore a returned rich error map alone does not prove the full graph is queryable.

**UNVERIFIED — specification:** Keep producer-specific flat errors and occurrence components. Reuse `Throwable->map` for native exception observations, retain symbol classes directly, add Seon operation symbol/basis/attribution separately. Preserve complete retained evidence before retiring class strings, projection/blob families or humanized components. Stack class demunging is heuristic attribution (`src/seon/error/refusal.clj:18`), not proof of culpability. Captured wrapper operation identity is stronger evidence when available. O(cause links + frames + admitted data).

### 2.8 Datahike transactions, listeners, commits and history

**VERIFIED:** Reports contain immutable `:db-before/:db-after`, datom vector `:tx-data`, tempid map, metadata map (`reference-code/datahike/src/datahike/core.cljc:126`). `listen!` delivers transaction reports (`:200`); this checkout's writer adds commit ID to `[:tx-meta :db/commitId]` (`reference-code/datahike/src/datahike/writer.cljc:261`). Transaction completion exposes effective datoms and runs validation (`reference-code/datahike/src/datahike/db/transaction.cljc:1288`). Commit UUID, integer transaction ID and transaction instant are different identities, not interchangeable clocks.

**VERIFIED:** Seon's `transact-call` supplies report validation and invokes Datahike (`src/seon/db.clj:4223`); public `transact!` is `:4475`. Provenance appears as `:seon.db/user` in writer requests (`src/seon/plan.clj:526`). Wake listener consumes changed datoms (`src/seon/cluster/wake.clj:279`, `:432`). **UNVERIFIED:** live transaction metadata population and complete retention behavior.

**UNVERIFIED — specification:** Store assertions and minimal transaction provenance; use the report to update interested consumers. Use `d/history`, `d/as-of`, `d/since` for temporal questions under retained history (VERIFIED seams: `reference-code/datahike/src/datahike/api/impl.cljc:148`, `:153`, `:185`); never copy entire database snapshots into domain rows. Do not equate no effective datoms with no attempted operation. Explicit positive analysis/completion evidence is necessary for an empty result. Querying callers/history can replace mirrors, but not durable external observations or exact shown text. Writer cost should follow submitted/effective facts plus enforced closure, not program size.

### 2.9 Model/provider requests and responses

**VERIFIED:** langchain4clj `chat` can return string or Java `ChatResponse`, not a universal persistent map (`reference-code/langchain4clj/src/langchain4clj/core.clj:574`). Request construction accepts tools, response-format, model-name, generation settings and messages (`:535`). **VERIFIED:** the library already has data adapters: `reference-code/langchain4clj/src/langchain4clj/listeners.clj:139` emits `:ai-message` and `:response-metadata` containing `:response-id`, `:model-name`, keyword `:finish-reason`, and `:token-usage`. Its token adapter emits integer `:input-tokens/:output-tokens/:total-tokens`, replacing absent counts with zero (`:74`); its message adapter emits text and `:tool-execution-requests` vector with string `:tool-id/:tool-name/:arguments` (`:99`). `messages/message->edn` is public and uses a different `:type`/`:id`/`:name` vocabulary (`reference-code/langchain4clj/src/langchain4clj/messages.clj:72`). U: reuse a suitable adapter if this library becomes the provider owner, but do not inherit its absent→zero or unknown-model defaults as positive Seon observations.

**VERIFIED:** Current Seon completion owner consumes decoded HTTP JSON `choices[0].message.content/reasoning_content`, `finish_reason`, `usage`, `completion_tokens` (`src/seon/ai.clj:952`). Usage normalization produces namespaced integer prompt/completion/total/cached counts (`:986`). This inspected path does not call langchain4clj `chat`; it has its own HTTP sender (`:1306`). Attempt writer saves usage/settings EDN, model and run evidence (`src/seon/turn.clj:3967`, `:4075`; `resources/seon/schemas/seon.ai.attempt.edn:9`). Model display gauges are explicitly derived from attempts (`src/seon/ai.clj:1007`).

**UNVERIFIED — specification:** Keep observed request/model identity, exact effective non-secret settings, response text, provider finish reason, original usage document and normalized queried counts. Provider IDs/tool-call ID/name/arguments would remain external strings/data, not automatically qualified Seon symbols or executable forms. Add only when the actual transport consumes them; the REPL-only agent protocol needs no tool-call executor. Attempt→turn ref; provider/model observed names survive descriptor deletion. Normalize once per response, query aggregations by attempt/model; no per-token graph writes.

### 2.10 Turns, messages, wakes, agents and plans/tasks

**VERIFIED:** `open-call`, `close-tx`, `open-tx`, `receipt-settle-call` construct domain transitions (`src/seon/turn.clj:390`, `:427`, `:692`, `:1568`). Message inbound writer mints identity and links recipient (`src/seon/cluster/message.clj:149`); `/to` is a required recipient ref with listener properties (`resources/seon/schemas/seon.message.edn:19`). Wake `/attribute` is keyword and `/t` integer in a derived unanswered value (`resources/seon/schemas/seon.wake.edn:21`). No distinct upstream library can decide Seon's reply-completion semantics.

**VERIFIED:** Plan items have token-valued `/about`, optional ref `/subject`, completion transaction ref, ordered position and owned nested steps (`resources/seon/schemas/my.plan.item.edn:26`). `add-step-call` resolves owner, parent and prerequisites (`src/seon/plan.clj:574`); transaction provenance is `:526`. `/done-query` is a Datahike query value (`my.plan.item.edn:43`), not a custom string language; `/done-when` is human prose, not an executable completion contract.

**VERIFIED:** issues also produce work: `src/seon/issue.clj:96` parses Markdown frontmatter/title/Problem section and word tokens; `:322` `index-tx` resolves citations, and `:1057` `create-tx` / `:1246` `settle-call` own database-authored work. `resources/seon/schemas/seon.issue.edn:1` declares ID/title/status/severity/path/problem, function/test citations, detector/agent and resolved-tx. At `:8` function citations are refs, whereas `/unresolved` at `:27` is a set of strings: resolution changes the representation of an observed name. This is an existing text interpretation layer beyond the source metadata adapter. U: replace identity-bearing prose extraction with authored typed citations; keep human prose and imported lifecycle evidence explicitly historical.

**UNVERIFIED — specification:** Keep writer-produced start/settlement facts; derive open, unanswered, frontier and reverse ownership. Messages retain recipient and sender separately from literal subject. Plans can become task parent/needs/position relations only after preserving completion checks and deletion obligations. Do not call existing parent→owned-child components SQL-shaped merely because a proposed task model uses child→parent peers. Independent task lifetime differs from component lifetime. Follow transaction-indexed changes; no periodic all-agent scan implied.

### 2.11 Profiling and timing

**VERIFIED:** `src/seon/profile.clj:55` allocates JDK `LongAdder` call/total/throw counters and `LongAccumulator` max; `:107` uses `System/nanoTime` around normal and throwing calls; `:138` reads counters into symbol/digest/scope/context, calls, total-ms, mean-us, max-ms, throws. `resources/seon/schemas/seon.profile.edn:1` explicitly marks this family nonstored. Mean/rankings are computed on reads (`src/seon/profile.clj:148`, `:152`). JDK object identity is not a durable schema value.

**VERIFIED — JDK 26.0.1 source:** archive `/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home/lib/src.zip`, entries `java.base/java/lang/System.java:347,385`, `java.base/java/util/concurrent/atomic/LongAdder.java:112,120`, `java.base/java/util/concurrent/atomic/LongAccumulator.java:126,134`. `nanoTime` returns a long for elapsed-time differences, unrelated to UTC; counter reads explicitly are not atomic snapshots.

**UNVERIFIED — specification:** Treat wrapper differences as inclusive elapsed time, not CPU/self time. Persist only requested immutable cumulative observations with definition digest and origin/lifetime at existing completion boundaries; never persist live cells. Independent concurrent sums do not establish an exact tuple (native guarantees cited above). Do not report interval maximum by subtraction or a missing observation as zero. Existing wrapper update cost is constant operations; snapshot/ranking cost grows with cells (`:178`). No timing benchmark or memory measurement of Seon was run here.

### 2.12 Files, digests and Git

**VERIFIED:** `src/seon/cluster/source.clj:54` reads `Files/readAllBytes`, hashes exact bytes, emits path→digest and aggregate digest. `:106` captures named files once and uses gitlink pins for directories; `:156` records launch dependency inputs in a retained value. Gitlink reader calls `git ls-files --stage` (`src/seon/test/cache.clj:176`); file rows declare relative-path identity, digest, relative-root (`resources/seon/schemas/seon.fn.file.edn:1`). Source publication owner is `src/seon/cluster/source.clj:539`; store lifecycle remains separate `src/seon/cluster/store.clj`.

**UNVERIFIED — specification:** Treat Git mode/object ID/stage/path as provenance of the index, filesystem digest as identity of read bytes, and Datahike commit as identity of admitted database state. None substitutes for the others. Store file path/digest once and declaration→file ref/span; namespace ownership derives from explicit rows, not directory naming. Git commit/blame are useful on-demand research inputs, not a second authoritative call graph. Indexed dependency gitlinks alone do not prove a dirty dependency's loaded bytes. Rehash changed files; avoid recursive source walks per render.

### 2.13 core.async.flow events

**VERIFIED:** `flow/start` returns `:report-chan/:error-chan`; faults include `:clojure.core.async.flow/ex` Throwable and optional other fields (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:108`). Ping includes pid/status/count/ins/outs/state; step failure adds cid/msg/op/ex (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:275`, `:313`). Status/op are dependency grammar keywords; pid/cid/message/state have caller-supplied value shapes. Ping returns only responding processes (`flow.clj:136`).

**VERIFIED:** Seon's fault committer consumes native fault maps and calls the existing error recorder with a declared schema (`src/seon/flow.clj:1085`); fanout taps native report/error channels (`:1247`). **UNVERIFIED — specification:** Keep volatile inspection as a bounded observation, durably record faults with owning process/agent/evaluation identity. Never infer “healthy” from a missing ping response. Normalize arbitrary state/message through declared evidence boundaries rather than serializing executors/channels. No replacement scheduler or report bus needed. O(received events), bounded ping wait, error delivery proof still required.

### 2.14 Rendered values and context captures (additional producer)

**VERIFIED:** `src/seon/render/value.clj:703` and `:710` expose AI/HTML rendering; `src/seon/context.clj:359` writes capture facts; `resources/seon/schemas/seon.context.capture.edn:33` gives capture ID, exact prompt and run relation. Exact shown text lives at `resources/seon/schemas/seon.eval.edn:43`. These observations cannot be regenerated faithfully by rendering against changed code or mutable objects.

**VERIFIED:** `clojure.datafy/datafy` adds original-object/class metadata when transforming an object; `nav` delegates contextual navigation (`reference-code/clojure/src/clj/clojure/datafy.clj:15`, `:30`). Built-in Namespace datafication gives `:name/:publics/:imports/:interns` (`:51`), Throwable uses `Throwable->map` (`:42`). **UNVERIFIED — specification:** use these for inspection of live native objects, not a second durable graph or persistence of `:clojure.datafy/obj`. Query/render contract stays plain values with one pair per entity schema and an attribute-map fallback. Original shown text is immutable history; current caller lists/context contributions are queries. Render budget applies once, not during native fact production.

### 2.15 External effects (additional producer)

**VERIFIED:** `src/my/shell.clj:26` declares `:my.shell/exit`, `/stdout`, `/stderr` result and routes through a declared capability. `src/seon/effect.clj:247` opens a receipt and `:300` settles it; `resources/seon/schemas/seon.effect.edn:20` stores request/result EDN. The capability's own return schema, rather than a universal successful-result shape, defines each effect's data.

**UNVERIFIED — specification:** Preserve effect request, operation symbol, evaluation/turn relations, terminal outcome and exact admitted returned data. Shell process results, HTTP results and filesystem reads are external observations; do not replay them to regenerate context. Raw payload bytes and structured queryable selections can coexist with explicitly different roles. Result EDN is encoding, not a reason to build another result-node language. A list of search terms is ordinary collection data; an agent can `map` the declared web operation over it, but availability, concurrency and side effects remain that operation's contract, not renderer behavior.

### 2.16 Native probe P1

**VERIFIED:** CLI only; `/opt/homebrew/bin/clj-kondo`, `v2026.07.24`; not the running JVM or proof of identical checkout binary. Run with stdin source below and arguments `--lint - --cache false --config '{:output {:format :edn} :analysis {:var-definitions {:meta true} :namespace-definitions {:meta true} :arglists true :keywords true :var-usages true}}'`.

```clojure
(ns story.probe {:story/links '[clojure.core/map]})
(defn f "Uses [[clojure.core/inc]]." {:added "1" :deprecated "2" :story/links '[clojure.core/inc :story/input]} [x] (inc (:story/input x)))
```

**VERIFIED — exact selected output members** (selection, not the complete analysis envelope):

```clojure
{:findings []
 :summary {:error 0, :warning 0, :info 0, :type :summary, :duration 9, :files 1}}
;; first :namespace-definitions :meta
{:story/links (quote [clojure.core/map])}
;; first :var-definitions :meta
{:story/links (quote [clojure.core/inc :story/input]), :added "1", :deprecated "2"}
;; body keyword's selected keys
{:ns story, :name "input", :from story.probe, :from-var f, :row 2}
;; body inc usage's selected keys
{:from story.probe, :from-var f, :to clojure.core, :name inc, :arity 1, :row 2}
```

**VERIFIED:** exit 0; stderr empty. The first probe used the same two forms with the two quote characters removed. It also exited 0, findings empty, reported duration 9 ms; metadata values were bare vectors and Var-usages additionally contained `clojure.core/map` and metadata's `clojure.core/inc` without `:from-var`/`:arity`. This second representation is NOT runtime proof that those symbols remain symbols. Both CLI invocations returned within the tool's 0.3–0.4 s wall envelope; process memory was not measured. No scratch files or Seon resources were created. **UNVERIFIED:** runtime evaluation of either form; the proposed link boundary deliberately accepts literal data rather than arbitrary expression evaluation.

### 2.17 Current sizes

**VERIFIED:** Physical lines including comments/blank lines, working-tree read using `Path.read_bytes().splitlines()`. Counts include whole owner files and schema declarations, not just stored attributes or producer code. Shared owners repeat: **do not sum these rows**. Prefix `S/`/`R/` as above; patterns expand files only. Number of source/schema files appears before line count. These are source-scope measurements, not installed-system size or deletion estimates.

| Producer | Source files / lines; exact scope | Schema files / lines; exact scope |
|---|---|---|
| 1 | 4 / 5,197 — `S/fn.clj`, `S/fn/*` | 18 / 1,081 — `R/seon.fn*.edn`, `seon.ns*.edn`, `seon.program.edn`, `seon.lint.edn` |
| 2 | 1 / 1,144 — `S/instrument.clj` (runtime consumer, shared) | 2 / 305 — `R/seon.fn.edn`, `seon.ns.edn` |
| 3 | 6 / 6,850 — `S/schema.clj`, `S/schema/*`, `S/fn/schema_shape.clj:306` | 8 / 418 — `R/seon.schema*.edn` |
| 4 | 1 / 1,144 — `S/instrument.clj` | 6 / 343 — `R/seon.instrument*.edn` |
| 5 | 5 / 4,643 — `S/test.clj`, `S/test/*` | 12 / 1,204 — `R/seon.test*.edn` |
| 6 | 7 / 7,185 — `S/eval.clj`, `S/eval/*`, `S/sci/*`, `S/repl.clj` | 9 / 1,006 — `R/seon.eval*.edn`, `seon.cluster.eval.edn`, `seon.sci*.edn`, `seon.repl.edn` |
| 7 | 2 / 2,452 — `S/error.clj`, `S/error/*` | 10 / 670 — `R/seon.error*.edn` |
| 8 | 2 / 5,086 — `S/db.clj`, `S/cluster/registry.clj` | 11 / 637 — `R/seon.db*.edn`, `seon.store.edn`, `seon.listen.edn` |
| 9 | 2 / 1,721 — `S/ai.clj`, `S/ai/*` | 7 / 726 — `R/seon.ai*.edn` |
| 10 | 8 / 11,659 — `S/turn.clj`, `S/agent.clj`, `S/cluster/{agent,message,wake}.clj`, `S/plan.clj`, `S/issue.clj`, `src/my/plan.clj` | 12 / 1,681 — `R/seon.turn*.edn`, `seon.message.edn`, `seon.wake.edn`, `seon.agent*.edn`, `seon.plan.edn`, `my.plan*.edn`, `seon.issue*.edn` |
| 11 | 1 / 286 — `S/profile.clj` | 1 / 95 — `R/seon.profile.edn` |
| 12 | 3 / 1,719 — `S/cluster/{source,store}.clj`, `S/test/cache.clj` | 4 / 213 — `R/seon.source.edn`, `seon.cluster.source.edn`, `seon.cluster.store.edn`, `seon.fn.file.edn` |
| 13 | 1 / 1,373 — `S/flow.clj` | 1 / 274 — `R/seon.flow.edn` |
| 14 | 13 / 12,056 — `S/render.clj`, `S/render/*`, `S/context.clj` | 18 / 1,094 — `R/seon.render*.edn`, `seon.context*.edn` |
| 15 | 4 / 1,113 — `S/effect.clj`, `src/my/{shell,web,fs}.clj` | 9 / 829 — `R/seon.effect.edn`, `my.shell*.edn`, `my.web*.edn`, `my.fs.edn` |

## 3. Smallest composition and deletion boundary

**UNVERIFIED — proposed design, not permission to implement.** Keep eight composed responsibilities: (1) declaration analysis, (2) schema/contract compilation, (3) database writer/history, (4) evaluation/effect execution, (5) tests/observations, (6) provider attempts, (7) agent/message/task lifecycle, (8) query/render. Profiling and Flow errors feed observations at their existing owners; files/Git feed declarations. These are responsibilities, not eight new namespaces or a new event registry.

**UNVERIFIED — minimal durable producer set:** authored declarations (including metadata/schema properties), admitted database transitions, evaluation/effect observations, test completions, provider attempts, and domain lifecycle decisions. Store fault occurrences at their actual boundaries. Rendering adds durable shown-text observations only where shown/sent. Live runtime metadata, Flow ping, callers, unresolved mentions, wake/frontier and profile rankings remain reads. Optional durable profile snapshots are a policy decision, not required to start universal context.

**UNVERIFIED — first implementation slice after freeze:** expose one declared metadata collection of observed qualified symbols/keywords on existing namespace/function rows; reuse the indexer's metadata seam and existing query/render functions. No prose parser required for this slice. Approximately 20–40 source lines + 10–20 schema lines + 20–40 focused test lines (50–100 total); a diff over 100 lines should trigger redesign. This estimate excludes migration of error storage, plan/task unification and all-library occurrence indexing. Query unresolved symbols with values, never rewrite them into required refs. Referenced library symbols must remain legible when no library row has been imported.

**UNVERIFIED — deletion candidates, with prerequisites:**

| Candidate | What can disappear | Gate before deletion; estimated size |
|---|---|---|
| Issue citation resolution | Citation-to-ref conversion and unresolved-string split in `src/seon/issue.clj:412`; `/functions` and related citation attributes in `seon.issue.edn` become observed values | Preserve typed subjects and every deletion/settlement consumer; scope/line estimate requires caller inventory |
| Renderer identity duplication | `:seon.eval/renderer-fn` declaration/write/read plumbing in `src/seon/turn.clj` and `src/seon/repl.clj` | Convert every consumer to observed symbol + retained definition evidence; roughly 15–40 source/schema lines |
| Current model gauges | `src/seon/ai.clj:1007` `model-observation-tx` and last-used/latency/rate attributes | Query retained attempts with measured index cost and equivalent UI behavior; 24 source lines in this function, further consumer/schema deletion unmeasured |
| Explanation encoding families | `seon.error.location*`, `seon.instrument.humanized*`, possibly `seon.error.projection` schemas and corresponding conversion/validation code | Lossless native path/value storage and exact old shown-text retention proven; no reliable net-line estimate yet |
| Schema shape materialization | Potentially `src/seon/fn/schema_shape.clj`, `seon.schema.shape*`, signature adapters | Inventory all queries/contract consumers and prove immutable Malli derivation cost; **not demonstrated deletable** by this research |
| Plan family | `my.plan*.edn`, `seon.plan.edn`, eventually `src/seon/plan.clj` implementation | One task owner must first subsume parent/needs/order/query/settlement guarantees; keep thin `src/my/plan.clj` API or migrate callers; size not an immediate saving |
| Context assembly mirrors | Selected assembly/contribution calculations in `src/seon/context.clj` and `src/seon/render/transcript.clj` | One query/render path must preserve history, read evidence and exact prompt accounting; do not delete capture rows merely because text is renderable |

**VERIFIED:** nothing above was deleted. **UNVERIFIED:** no entire current source owner is proven safely removable now. Preserving error recorder, schema bridge, effect receipts, test completion and transaction writer is necessary until their specific contracts are composed elsewhere. A 10,000-line target cannot be substantiated by adding the overlapping census or counting whole owners as deletions.

## 4. Decisions for the owner

All options below are **UNVERIFIED proposals/effort estimates**, not implementation acceptance. Existing law settles observed-name values versus live refs; that is not reopened.

| Decision | Options and recommendation |
|---|---|
| Authored direct links | **Recommend:** one declared metadata key containing literal/quoted symbol/keyword collection; 0.5–1 day plus review, no prose parsing, no arbitrary metadata evaluation. Alternatively retain wikilinks only (small reader, requires explicit grammar and loses typed authorship), or support both (1–2 days, must deduplicate provenance without treating mention as invocation). |
| Persisted native evidence | **Recommend:** preserve current codecs while expose structured values at reads; 1–2 days bounded adapter work, no immediate schema-family deletion. Alternatively adopt proven native heterogeneous storage (multi-owner work, days and comparator/retention proofs), or retain text-only evidence (least work, sacrifices graph queries into actual values). |
| Historical provider/render identity | **Recommend:** observed symbol/provider name + definition/settings evidence, live refs only for genuine relationships; 1–2 days consumer migration. Alternatively retain documented optional navigation refs as caches (less migration, drift/sweep semantics remain explicit). |
| Source occurrence detail | **Recommend:** current sets plus source spans, add locations only for demanded questions; ~1 day initial metadata slice. Alternatively retain full native occurrence stream (several days, larger storage/retention), or compute occurrences on demand from pinned source (lower storage, repeated analysis latency). |
| Profile durability | **Recommend:** volatile inspection initially; explicit “not durable” in context, no new writer. Alternatively capture immutable cumulative observations at existing completions (several days attribution/concurrency/retention proof), or record each invocation (unbounded volume relative to context need; not recommended). |
| Model display gauges | **Recommend:** replace only after query-cost measurement (~0.5–1 day). Alternatively keep explicitly derived display cache (zero immediate change; future invalidation responsibility stays). |

**UNVERIFIED — remaining proof:** run read-only installed-schema/writer queries when separately authorized; compare current branch declarations to host metadata; exercise round-trip storage of quoted metadata, heterogeneous paths, arbitrary expected/actual values and deletion-safe historical symbols on disposable branches after the freeze. Measure changed-input indexing, query/render latency and memory. No Seon test gate, boot/adoption timing or installed performance result is claimed by this source-only brief.
