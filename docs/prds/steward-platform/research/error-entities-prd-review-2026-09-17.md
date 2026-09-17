---
type: research
status: open
created: 2026-09-17
tags: [review, errors, schema, data-modeling]
---

# Independent review: error entities

**Recommendation: keep the model; revise the admission and enforcement details before implementation.** The plan follows the owner's composition, data-first rendering and graph-scoped panic rulings. The remaining blockers are concrete: resource placement, retained payload storage, facet truth at reads/deletion, and the distinction between declared return facets and every facet a value happens to satisfy.

Line-reference convention: **PRD:L** means `docs/prds/steward-platform/plan/error-entities-prd-2026-09-17.md:L`; **Rulings:L** means `docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md:L`. These refer to [docs/prds/steward-platform/plan/error-entities-prd-2026-09-17.md](../plan/error-entities-prd-2026-09-17.md) and [docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md](../plan/program-facts-are-the-runtime-prd-2026-09-17.md). Findings below are ranked; each disagreement has exactly three options, simplest first.

## Must fix before implementation

### 1. Make the schema edit manifest admissible and complete

PRD:71 says supporting maps go in `seon.error.edn`; PRD:83 assigns `:seon.db.read/error` to `seon.db.edn`; PRD:281 puts `:seon.instrument.explanation/entity` in `seon.instrument.edn`. The loader requires a declaration's namespace to equal its resource filename (`src/seon/schema/edn.clj:259–303`). Those declarations would refuse before any constructor ran. The contract-findings lane already encountered exactly this rule (`docs/prds/steward-platform/research/contract-findings-query-2026-09-17.md:23–27`).

Several promised stored shapes are still descriptions rather than declarations: read-target, write-attempt, path segments, bounds/omissions and failed-state members (PRD:71–73, 83–84, 293). Their scalar/ref/cardinality choices cannot all be checked yet. `/fix` is called an existing attribute at PRD:68, but the resource only contains an inline `[:seon.error/fix :string]` entry, not a top-level attribute declaration (`resources/seon/schemas/seon.error.edn:16–24`). An entity entry alone does not give the bridge a registered value grammar (`src/seon/schema/datahike.clj:237–249`).

1. **Recommended — finish the literal declaration manifest in namespace-owned resources.** Guarantee: every promised datom has one resolvable form and every component relation has a target. Cost: a bounded design pass before coding; give up calling the present prose table an executable schema specification.
2. Keep the proposed resource files and rename the new keys into those files' namespaces. Guarantee: current placement admission works; cost: update every example/contract/query; give up the proposed dotted namespace grouping.
3. Change resource placement policy. Guarantee: domain files can hold several namespaces; cost: loader, hook, tooling and documentation changes; give up an existing checked ownership invariant for no demonstrated runtime benefit.

### 2. Correct retained payloads, not only the boolean columns

The ten-resource spot-check below confirms counts and marker partitions, but finds two semantic holes in “retain”:

- `my.note/about-not-found` aliases `my.note/about`, a ref (`resources/seon/schemas/my.note.edn:7,28–29`). Its writer emits the requested subject precisely when lookup found no entity (`src/seon/note.clj:201–205`). Retaining that as a stored ref, as PRD:215 directs, makes a historical observation depend on the missing target: a lookup ref refuses, while a numeric ref does not establish existence. It must be an observed value/projection, not a living-entity statement (modeling guide:243–253; `reference-code/datahike/src/datahike/db/utils.cljc:109–148`).
- PRD:200 preserves `seon.render/unknown`'s members, including `/refusal` and `/call`. `/refusal` is typed as the very `:seon.error/kind` being retired; `/call` is `[:vector :seon.schema/value]` (`resources/seon/schemas/seon.render.edn:157–165`). The latter is neither an ordered stored call nor a native storable leaf: vector storage is cardinality-many and its arbitrary child lacks a native type (`src/seon/schema/datahike.clj:56–69,178–186,206–230`). Several unknown members are only inline declarations. Keeping their data is right; keeping those storage declarations is not.

PRD:99's no-identity rule must cover retained aliases too: note IDs and `seon.fn/schema-declaration-invalid` resolve to identity-bearing scalars (`my.note.edn:1,24–32`; `seon.fn.edn:201`; bridge alias/property resolution at `src/seon/schema/datahike.clj:26–44,246–275`). This is already the right rule, but the per-resource work must actually apply it.

1. **Recommended — replace these observation carriers with non-identity values or the declared projection/cause components.** Guarantee: missing targets cannot destroy the fault and deleted kind grammar has no surviving consumer. Cost: a few explicit inventory corrections and caller changes; give up raw request objects as datoms.
2. Retain complete original payload only in existing admitted blob evidence, with queryable stable tokens alongside it. Guarantee: evidence remains retrievable without invalid refs; cost: weaker direct queries; give up full structured inspection of those payloads.
3. Define additional typed owned entities for every retained complex payload. Guarantee: detailed queries and validation; cost: substantially more schemas, bounds and deletion cases; give up the smallest repair.

### 3. Separate facet candidates from proven facet membership

PRD:256 deliberately permits a valid base with partial domain evidence and validates the occurrence/base owning value, not every possible facet. PRD:320 nevertheless says required-attribute presence plus final-writer validation establishes facet membership. It does not establish alternatives, cross-member constraints, or a facet's extra predicates. Example: base plus `:seon.config/error-key` and neither expectation member is permitted partial evidence by PRD:256, but does not satisfy the config facet's either-expectation requirement at PRD:96. A required-key query alone cannot prove it. The current writer selects declared identity/ownership schemas, not every structurally possible facet (`src/seon/db.clj:3133–3169,3297–3303`).

1. **Recommended — use Datalog to select candidates, then validate the complete derived owned value with `facets` on the same database projection.** Guarantee: query and render report the same satisfied facets; cost: bounded value acquisition/validation; give up a claim that every facet question is one presence-only clause.
2. Compile the supported facet constraint grammar into Datalog, including alternatives and child conditions. Guarantee: equivalent database-only membership within that grammar; cost: another substantial derivation and equivalence proof; give up arbitrary Malli predicate support in such queries.
3. Restrict queryable facets to conjunctions of globally typed required members and validated components. Guarantee: generated presence clauses suffice for that restricted grammar; cost: redesign alternatives such as the config expectation; give up general facet constraints.

### 4. The required cluster ref needs an independently selected obligation

PRD:263 promises optional cluster attachment generally but required attachment in a “recorded-cluster-fault specialization.” No selector for that specialization is specified. If selection depends on the ref's presence, deleting the cluster sweeps the ref and demotes the row to valid base/occurrence; the promised refusal disappears. If the specialization inherits the occurrence identity and the writer selects it unconditionally, *every* occurrence acquires that requirement. Requiredness works only when its schema remains selected after the sweep (`src/seon/db.clj:3137–3139`; `reference-code/datahike/src/datahike/db/transaction.cljc:998–1014`; modeling guide:349–364).

1. **Recommended — make the connecting cluster ref uniformly optional and retain its observed scope token.** Guarantee: fault evidence survives and deletion deliberately sweeps attachment, like agent/turn/evaluation refs; cost: a query exposes detached faults; give up this additional cluster-deletion refusal.
2. Put a conditional requirement in the always-selected occurrence schema, keyed by an independently meaningful observed cluster-scope fact. Guarantee: the obligation survives ref retraction; cost: specify and validate that factual condition on every write; give up unrestricted base-only storage when that condition is present.
3. Require cluster attachment on every stored occurrence; pre-acquisition errors remain transient until genuine custody exists. Guarantee: one simple required-ref rule; cost: limits pre-acquisition recording; give up storing an unattached occurrence.

### 5. Define declared-facet analysis as a complete, arity-aware operation

The indexed symbol-to-facet value relation and reverse query in PRD:333–341 are sound. The specified traversal is incomplete: PRD:328 covers `:or`, `:and`, aliases and refs, but not `:multi` branches/defaults. Malli `:multi` dispatches through entry schemas, not the children grammar of `:or` (`reference-code/malli/src/malli/core.cljc:1861–1926`). `:merge` is correctly unavailable under the chosen plan; it must refuse analysis until uniformly supported, not quietly produce zero edges (PRD:22; `reference-code/malli/src/malli/util.cljc:53–100,398–405`). Recursive refs require visited identities and a positive unknown/refusal for unsupported traversal.

There is also a concrete inheritance mismatch. PRD:90's turn facet structurally satisfies PRD:91's agent facet, but both are defined as direct base extensions at PRD:79. Following only authored extension ancestors (PRD:328) derives read+turn from the example output, while `facets` returns read+turn+agent (explicitly required at PRD:287). PRD:351's wrapper would flag a correct result as undeclared. The function-wide union also cannot enforce one invoked arity: permission in arity two must not bless an undeclared error from arity one. The current analyzer already exposes each arity independently (`src/seon/program.cljc:607–645,672–707`).

1. **Recommended — specify a supported result-position grammar, canonical inheritance and per-arity allowed sets; store their union for discovery.** Make turn explicitly extend agent, traverse `:multi`/named alternatives and registered refs, reject unsupported forms positively. Guarantee: declared edges and runtime checks agree for the admitted grammar; cost: one analyzer matrix and projection-bound wrapper data; give up claiming arbitrary schema implication.
2. Restrict authored error outputs to explicit canonical facet refs under `:or`/`:and`, with all implied facets declared. Guarantee: a smaller exact traversal; cost: admission restrictions on otherwise valid Malli outputs; give up `:multi` error alternatives.
3. Infer structural entailment across all schema forms. Guarantee: broader inferred capability if proved; cost: general predicates prevent a complete decidable solution, so explicit unknowns remain; give up a small bounded implementation.

### 6. Make wrapper enforcement a prerequisite, including errors produced by the wrapper

PRD:245 requires input refusal in both modes and PRD:351 forbids open maps/base arms from hiding undeclared facets, but §6 has no explicit slice proving this before constructor migration. Today interpreted `:record` returns the original function (`src/seon/instrument.clj:507–547`). Malli reports output only after ordinary output validation fails (`reference-code/malli/src/malli/core.cljc:2218–2222`); therefore a base arm/open map accepting the value bypasses the proposed check entirely. `buried-error` also returns an existing output error before constructing a violation (`src/seon/instrument.clj:99–113,299–305`). Consolidating its predicate alone does not implement declared-error outputs.

The live wrong-arity probe below exposes another decision: instrumentation can emit an error even for a function such as `seon.id/valid?` whose authored output is boolean (`src/seon/id.clj:68–73`). Rulings:486 and PRD:326 require errors to be declared. Specify whether the query describes a function's admitted body returns or the effective callable including wrapper refusals; do not silently give every pure function an implicit contract-error arm.

1. **Recommended — distinguish body output contracts from the instrumentation boundary's declared refusal contract, and implement checks in the existing wrappers before migration.** On a valid invocation, check actual error facets independently of ordinary output validation, per arity; preserve the original cause when reporting mismatch. Guarantee: no hidden body error permission and no invalid input execution in either mode; cost: coordinated host/SCI wrapper work; give up interpreting the body-facet query as all possible wrapper responses.
2. Derive effective callable contracts including instrumentation's possible refusals and expose those in the existing graph. Guarantee: query covers every callable result; cost: many broad contract-error capability edges; give up precise body-only capability answers unless separately derived.
3. Annotate every authored function with wrapper-induced refusal facets. Guarantee: literal output declarations include them; cost: repository-wide repetitive declarations and drift enforcement; give up a small, meaningful authored contract.

## Should fix

### 7. Specify arity failures and ordered explanation invariants

The humanization design is correctly data-first and keeps machine paths separately (PRD:276–283). However, the contract facet requires nonempty explanation rows, each with schema/value location and expected shape (PRD:86,281). Wrong-arity currently has no `m/explain` result (`src/seon/instrument.clj:310–350`); Malli's unmatched multi-arity path can supply no selected input schema (`reference-code/malli/src/malli/core.cljc:2288–2295`). Define that case before saying all such rows come from `humanize`. Do not fabricate a missing leaf or a matched arity.

The path/message schemas also need whole-value constraints: ordinals unique and contiguous, length/count equal to the number of children, explicit zero with absent many-valued children, and declared omission evidence when incomplete (PRD:73,281). Per-child nonnegative ordinals alone permit duplicate positions and contradictory counts; complete owning-value validation is the existing place to check this (`src/seon/db.clj:3250–3303`). The missing-member grammar must state which actual map-key shapes it admits instead of assuming every Malli missing key is qualified (PRD:71; `reference-code/malli/src/malli/core.cljc:1210–1245`).

1. **Recommended — give arity refusal its own factual facet using invocation count and declared arity bounds; retain the ordinary explanation facet for actual explanations.** Add parent-level ordered-value invariants. Guarantee: no invented Malli explanation; cost: one additional concern and small validators; give up one universal contract-error payload.
2. Keep one contract facet with explicit alternative evidence grammars for arity and explained checks. Guarantee: one entry point with honest alternatives; cost: more involved matching/query derivation; give up the simple required-member row.
3. Keep arity errors base-only with bounded dependency evidence. Guarantee: always renderable without fabricated structure; cost: weaker program queries; give up precise arity-facet discovery.

### 8. Name the compositional render entry, not only the filter to bypass

`block.clj:61` is `surface-id`, not entity-pair dispatch. The relevant selection is `src/seon/render.clj:323–377`: after the specificity filter, multiple producers become an ambiguity. Removing that filter alone produces an error, not composed output. PRD:285 correctly calls for an orchestrator; specify its entry and direct pair invocation, including transient errors without occurrence identity and prevention of recursively redispatching the same whole entity.

1. **Recommended — at the existing error render entry, render base then canonical facet pairs directly under one supplied projection/owned value.** Derive stored block IDs from occurrence+facet; transient addresses from their evaluation/render request. Guarantee: composition without ambiguity or duplicate base rendering; cost: one explicit route plus pair invocation; give up treating facets as competing generic candidates.
2. Generalize existing schema selection to return a composed render plan for base-extending error schemas. Guarantee: one generalized selection result; cost: more consumers change; give up the smallest error-specific integration.
3. Precompose the complete error inside its one base renderer and expose only its outer morph target. Guarantee: correct visible content with fewer addressing changes; cost: whole-error updates; give up independently addressable facet blocks.

## What is already right; schema/storage coverage

No replacement kind/class/domain boolean is present in the proposed base/facet tables. Check phase, requested face, factual capping and control action are bounded observations, not table selectors (PRD:56–97,108,252). Native conjunction is a good choice: Malli merge otherwise permits later entries/requiredness to win (`reference-code/malli/src/malli/util.cljc:53–100`). PRD:24's conflict rejection and complete expansion are necessary; current raw map discovery selects only one map (`src/seon/schema/form.cljc:24–45`). Cross-facet incompatibility must remain an invalid conjunction, never last-wins merging.

| §2 family | Storage/model verdict from the specified forms |
|---|---|
| Base at/layer/operation/message/member/expected-key/expected-shape/fix/unavailable | Native scalars or mixed scalar codec are viable; no stored nil. `/fix` needs registration. Symbols and schema names are observation values; expected-shape correctly avoids unique fingerprint metadata (PRD:56–75; `src/seon/schema/datahike.clj:56–69,155–170`). |
| Base location/projection/evidence-items/basis; explanation/humanized children | Component refs are the right ownership. Exact child/ordinal/alternative grammars remain to finish; never substitute a raw `:map` attribute (PRD:48,63–73,281; bridge:178–186). |
| Cause; agent/turn/evaluation links | Optional peers deliberately sweep; observation tokens survive. No cascade into agent/turn/error causes (PRD:67,260–268; Datahike `db/transaction.cljc:998–1014`). Required cluster specialization needs finding 4. |
| Read/write | Operation enum and component target/attempt plus observed basis are sound categories; every operation's exact target/attempt grammar must be declared. A malformed request without acquired basis uses the stated contract/base alternative, not a fake basis (PRD:83–84,102,118). |
| Availability/boot | Connection identity and failed observation, or real phase/root, avoid a compulsory pre-acquisition basis (PRD:85,92). Empty/unknown connection identity must take the explicit base alternative. |
| Contract | Symbol/count/check plus owned explanation/projection are storable in principle; wrong-arity and missing-member alternatives need finding 7 (PRD:86,281). |
| SCI acquisition/evaluation; effect | Digest/member/request/capability/evaluation observations plus components avoid live host objects and unique target IDs (PRD:87–89,99). |
| Turn/agent/cluster | Non-unique observed IDs are correct; turn implies agent structurally, which analysis must also represent (PRD:90–93; finding 5). |
| Provider/render/config/test | Descriptor/test tokens, face enum and components are viable; config's expectation alternative needs full validation, not presence-only classification (PRD:94–97; finding 3). |

Base-only errors are handled honestly, including visible unmatched evidence and a total render floor (PRD:110,256). Typed humanization belongs in owned data, not a required prose message; `humanize` returns structured data and `error-value` excludes missing-key errors by default (`reference-code/malli/src/malli/error.cljc:227–232,374–405`). These choices should remain.

## Ten-resource inventory spot-check

Manually checked marked forms and their referenced payload declarations in these ten resources; counts below are observations of the files, not a re-census of all 65. Paths are under `resources/seon/schemas/`; “B/P” counts boolean-marker versus payload-bearing classes, not all auxiliary attributes. All ten counts/partitions match PRD §3. Two retained-payload decisions need correction as finding 2 records.

| Resource and inspected lines | Observed classes; B/P | PRD anchor; result |
|---|---:|---|
| `seon.error.edn:228–236,273` | 1; 1/0 | PRD:125; unclassified deletion correct. |
| `seon.instrument.edn:35–38` | 2; 1/1 | PRD:128; function payload is not a boolean. |
| `seon.fn.edn:183–208` | 13; 6/7 | PRD:127; six booleans and seven substantive members match. |
| `seon.blob.edn:31–40` | 5; 0/5 | PRD:136; all five carry data. |
| `seon.cluster.store.edn:1–13` | 6; 0/6 | PRD:178; including the conjunction-based refused rule. |
| `seon.config.edn:42–78` | 7; 1/6 | PRD:179; refused/reconcile-refused share the rule grammar. |
| `seon.env.edn:83–98` | 8; 8/0 | PRD:182; incomplete/invalid-member already declared. |
| `seon.render.edn:142–165,238–243` | 4; 2/2 | PRD:200; unknown is a substantive multi-member shape, but retained call/kind grammars need repair. |
| `my.note.edn:24–33` | 5; 0/5 | PRD:215; missing-subject ref must become observation data. |
| `seon.test.accretion.edn:98–131` | 1; 1/0 | PRD:226; auxiliary payloads also inspected: `/auto-check` is a map alias and `/failure-groups` vector-of-maps at :20–28,71,145; neither can remain raw stored members. The PRD correctly calls for typed owned groups but must spell out auto-check too. |

## Implementation order and reset

PRD:359–371 correctly serializes shared owners and calls for one isolated batch/reset. Tighten its dependency order: **complete declarations/storage and derived owned/pulled shapes → extension/checker → base recognition/constructors and both-mode wrapper enforcement → facet analysis plus return enforcement → recorder/graph control and render integration → domain migration/B1 → campaign.** This resolves findings 1–8 before broad arming; the existing interpreted record bypass makes wrapper enforcement a prerequisite, not later campaign cleanup (`src/seon/instrument.clj:531–547`).

After those interfaces are fixed, domain resource/source/contract groups can run in parallel under disjoint ownership, and analyzer/query work can proceed alongside graph supervision. `error.clj` recorder/render edits, `instrument.clj` bindings/wrappers, schema expansion/admission and `fn.clj` work remain serial transfers; the plan itself names overlapping ownership at PRD:359–369. Schema/render coherence requires declarations and their callable pairs to become valid together, even when implementation commits are staged separately (`src/seon/schema.clj:1514–1567`).

Batch into the one reset: deletion of markers/kind/class and stale consumers; all new namespace-owned scalar/component resources; alias uniqueness removal and observation ref retypes; occurrence/base replacement; failure-state relations; all constructor and host/SCI wrapper changes; derived render/pull shapes; analyzer facts and a full reanalysis of existing functions. Do not read existing analysis digests as evidence a newly introduced facet derivation ran until that rebuild is complete (PRD:339,371; `src/seon/program.cljc:648–707`). Include all new files in explicit ownership; “same-named resources” alone misses the dotted-namespace resources from finding 1.

The panic design is aligned with 1m: healthy-store fault+failed-state transaction, graph stop/ack, surviving status/REPL; unavailable store stops from acquired custody without pretending to persist (PRD:289–297; Rulings:424–438). Add the planned proof where the original store is healthy but *fault recording itself refuses*: the stop must still be observed within its bound without recursion or claiming durable failed state. PRD:272 calls this a bounded failure path but §6 does not enumerate that case.

**Nice:** retain the ten-row inventory check as an implementation admission check derived from EDN, not a second maintained roster; add measured emitted entity/datom counts for root-path, missing-key, multi-problem and large-evidence faults. This will price the ordered component representation without inventing a tuning constant (PRD:73,231,281; `src/seon/db.clj:3171–3207`).

## Live evidence: exactly three supported MCP calls

All calls selected root `/Users/sean/src/seon`, cluster `default`; both evaluations used `mode: "jvm"`, `read_only: true`, timeout 10,000 ms. No SCI context mutation, definitions, transactions, contract arming, tests, operator commands or hand-written prepl client. These are observations of the running JVM, not an adoption proof. The two eval responses were tool-windowed and supplied retrievable digests; comparisons below use only the visible returned members.

1. `mcp__seon__runtime_status`: alive PID **80593**, advertised start **2026-09-17T17:50:55Z**, health `observed`; **3 error signatures, 6 errored evaluations, 11 failed tests**, **1 agent**. Armer/render/search procs answered ping. This does not prove an agent turn proc is healthy: the returned flow list was plumbing only (status observation; PRD:295 requires positive agent/graph coverage).
2. Read-only fault pull, **6 ms**. Reproducible form:

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))
      rows (seon.db/q '[:find ?e ?signature
                       :where [?e :seon.error/signature ?signature]] database)
      picked (first (sort-by second rows))
      fault (when picked (seon.db/pull database '[*] (first picked)))
      occurrence (first (:seon.error/occurrences fault))]
  {:review/root-row-count (count rows) :review/fault fault
   :review/first-occurrence occurrence
   :review/source (seon.db/q '[:find ?e ?commit
                             :where [?e :seon.source/commit-id ?commit]] database)})
```

Returned root **38113**, signature **`4c02215b44ec67c8a375c1b6934cd752b7e4bdbc306bdbec6b56e32ffa555f19`**; occurrence **38115**, ID **`fb6aaf529a87`**, count **1**, first/last-at **2026-09-17T17:53:27Z**. Root kind was `:seon.turn.loop/write-refusals-exhausted`; observed function `seon.turn/offer-write-refusal-fault!`; occurrence agent ref **36989**, process ref **37010**, process token **`80593-1789667455558`**. Message reported root had no open turn, three consecutive refused writes at bound three, and a parked turn proc. Evidence was capped, source-size **18229**, blob ref **38114**; the inline evidence contained an over-bound observation of **7806** bytes. Tool result digest: **`3cb5db4f1424089c0d29828b44443fbaa9ab4edd54a9058e6cf076d6c48e200d`**, size **8364**.

Comparison: neither root nor occurrence carries the proposed `:seon.error/at`, `/layer`, `/operation` base trio. Occurrence time currently lives in `/first-at` and `/last-at`; domain operation is `/op`, function is `/fn`; no observed turn token was returned. This is a real agent-attributed error that must not invent a turn to satisfy the turn facet (PRD:56–58,90–91; current recording at `src/seon/error.clj:1453–1480`). The source-commit query returned `[]`: **adoption freshness remains unestablished**, not “current.”

3. Wrong-arity refusal, **4 ms**:

```clojure
(try (seon.id/valid?)
     (catch Throwable failure
       {:review/exception-class (symbol (.getName (class failure)))
        :review/refusal (ex-data failure)}))
```

Returned `clojure.lang.ExceptionInfo`. Top-level refusal had kind `:seon.instrument/contract-violated`, marker `:seon.instrument/contract-violated seon.id/valid?`, message and `/data`. Nested data carried function `seon.id/valid?`, arity **0**, arm **:input**, Malli cause **:malli.core/invalid-arity**, expected arglists **`([length id])`**, one problem with path **`[]`**, offending **0**, and fix “Call one of the declared arglists.” Lookup status was `:found`; diagnostic layer was unqualified `:instrumentation`, operation the function symbol, member `:arity`. No proposed timestamp, top-level layer/operation, shape fingerprint, owned projection/location or humanization was present. Tool result digest: **`1718fef3a4267a165c3edc97e34e37fe6bd6f1fdd50df56d17430e05647f16ae`**, size **5056**. This supports the constructor/wrapper and arity work above, not a claim the future facets already validate (`src/seon/instrument.clj:299–350,450–459`; PRD:86).

## Reading and verification boundary

Read end to end in the requested order: `AGENTS.md` opening/§§1–3; `.agents/skills/data-modeling/SKILL.md`; `.agents/skills/datahike/SKILL.md`; [docs/seon/architecture/data-modeling-guide.md](../../../seon/architecture/data-modeling-guide.md); Rulings §§1j–1o; [docs/prds/steward-platform/research/error-and-data-model-design-2026-09-17.md](error-and-data-model-design-2026-09-17.md); [docs/prds/steward-platform/research/critical-findings-triage-2026-09-17.md](critical-findings-triage-2026-09-17.md); [docs/prds/steward-platform/research/entity-schema-vs-pulled-shape-2026-09-16.md](entity-schema-vs-pulled-shape-2026-09-16.md); [docs/prds/steward-platform/research/datahike-deletion-and-the-program-graph-2026-09-16.md](datahike-deletion-and-the-program-graph-2026-09-16.md) §§4,5,8,9; [docs/prds/steward-platform/research/contract-findings-query-2026-09-17.md](contract-findings-query-2026-09-17.md); the requested Malli/error/instrument/render/schema seams; then the review PRD end to end. Also used the REPL and data-oriented-clojure skills for the bounded source/live review.

| Dependency pin | Source seams read | First-party consumers |
|---|---|---|
| Malli `3517a3cd9271b2083780ac7be1725493905bca2e` | `reference-code/malli/src/malli/error.cljc:177,288,374,392`; `core.cljc:1210,1861,2233`; `util.cljc:53,398` in that directory | `src/seon/instrument.clj:299,507`; `src/seon/program.cljc:648`; `src/seon/render.clj:323` |
| Datahike `73afe78271a289861da236c5ac3457e64349653f` | `reference-code/datahike/src/datahike/db/utils.cljc:109`; `db/transaction.cljc:998` under the same dependency source root | `src/seon/schema/datahike.clj:233`; `src/seon/db.clj:3133,3171` |

Source snapshot advanced during review through `cff1c3bea`; reviewed PRD commit `ea32d6dce`. Foreign uncommitted edits observed in operator, database/schema, function and test files were preserved. No foreign gate was run or diagnosed, no lane was operated, and no scratch root/worktree/background process was created by this lane. Only this note is owned. Static source review, ten-resource inspection and the three MCP observations are the verification boundary; no future schema, storage round trip, stop behavior, browser paint or analyzer implementation is claimed proven.

Automatic hook boundary: the first note write reported repository-wide pin-citation errors (including `docs/prds/context-generation/research/agents-md-audit-2026-09-15.md:226`) and queued publication. The hook's `tmp/source-publications/e36fa853-45d4-4f98-861c-cb122c60de84.edn:1` then reported `:seon.operator.subprocess/deadline-exceeded`, `init phase=preflight failed`, with `data/operator/operations/init-preflight-17369.log` as evidence. No lifecycle workaround or fourth MCP call was made. The pin checker associates exact hashes with nearby dependency citations (`script/seon/dev/markdown.clj:731–795`); this note separates its dependency pins from its abbreviated source commit accordingly.
