---
type: research
status: complete
created: 2026-09-19
tags: [schema, data-model, audit]
---

# Schema audit B — the error, eval and program-graph families, 2026-09-19

Read-only audit of **70 resources**, files 71–140 of
`ls resources/seon/schemas` (`seon.config.render.agent.edn` →
`seon.ns.edn`), covering the whole **error family** (13 resources), the
**eval** family, and the **fn / program-graph** family. 3 955 lines,
194 560 bytes. Every claim below names a line I opened in the working tree
at branch `steward-platform`, with other lanes' uncommitted edits present in
`src/seon/db.clj`, `src/seon/schema.clj`, `test/seon/db_test.clj` and others
(`git status` at entry); line numbers in `src/` are working-tree bytes.

Read end to end before opening a schema: AGENTS.md §2–§3;
[the data-modeling decision guide](../../../seon/architecture/data-modeling-guide.md)
(all 837 lines); [the data-modeling skill](../../../../.agents/skills/data-modeling/SKILL.md);
[the datahike skill](../../../../.agents/skills/datahike/SKILL.md);
[schema design review](schema-design-review-2026-09-17.md);
[datahike modeling study](datahike-modeling-study-2026-09-17.md);
[error-entities PRD](../plan/error-entities-prd-2026-09-17.md) §§1–6;
[symbols everywhere inventory](symbols-everywhere-inventory-2026-09-17.md);
plus the bridge (`src/seon/schema/datahike.clj:55-310`), the declaration
walker (`src/seon/schema/form.cljc:25-109`) and the final write validator
(`src/seon/db.clj:3580-3620`).

**The headline.** The 2026-09-18 additive error manifest landed **16 new
base-extending facets and 14 owned child schemas** in this slice, and they
are correct modeling — ordinals on ordered members, positive counts beside
optional collections, owned components with declared child schemas. But
**not one of them can be written**: a facet error entity has no identity
attribute and is named by no `:seon.db/component-schema`, so
`src/seon/db.clj:3607` refuses it as `::unowned-entity`. Meanwhile **52
legacy `{:seon.error/class true}` marker schemas and 35 `[:= true]` boolean
stamps survive beside them**, and `seon.error/error?`
(`src/seon/error.clj:1735-1744`, `:1786-1796`) still recognizes *only* the
legacy class property — so the new family is invisible to the system's own
error predicate. Two complete error models occupy the same files and
neither is whole.

---

## 1. The ranked class table

| # | Class | Instances in slice | Severity | One-line fix rule |
|---|---|---|---|---|
| **C1** | **Facet error entities that cannot be stored** — `{:seon.db/attributes true}`, no identity attribute, named by no `:seon.db/component-schema` | **16 facets** + `:seon.error.disposition/observation` | **blocker** | Every storable entity is reachable: give it an identity attribute, or a declared owning component relation. |
| **C2** | **Two error models in one file** — legacy `{:seon.error/class true}` + `[:= true]` marker beside a new base facet | **52** class-marker schemas, **35** `[:= true]` attrs, across 13 files | **blocker** | Ruling 1o: the schema IS the kind. Delete `:seon.error/class`, the `[:= true]` markers, and `:seon.error/kind`; the predicate becomes "satisfies base". |
| **C3** | **Refs to deletable entities, deletion behaviour undeclared** | **44 of 51** ref declarations carry no docstring at all | **blocker** | The reset-batch policy: name cascade / sweep / refuse / value / pending in the attribute's own docstring. |
| **C4** | **A fact that must outlive its target stored as a ref** | 7 named below (`:seon.error/run`, `/fn`-family, `:seon.eval/renderer-fn`, `:seon.error.occurrence/{turn,proc-fn,evaluation}`, `:seon.listen/entity`, `:seon.issue/{functions,tests,errors,keys,namespaces,runs,issues}`) | **blocker** | Statement → ref; observation of a token → value (G2). |
| **C5** | **Missing render pairs on entity schemas that render into agent context** | **16 facets + `:seon.error/base`**; `seon.error/facet-ai`/`facet-html` do not exist in `src/` | **friction** | One AI/HTML pair on the base and one per facet, or the default attribute printer is the declared answer. |
| **C6** | **Digest formats that do not constrain hex** | **7** `[:string {:min 64 :max 64}]` declarations; 4 of them new on 2026-09-18 | **friction** | Reuse `:seon.blob/digest` `[:re "^[0-9a-f]{64}$"]` (or `:seon.source/digest` where identity is wanted). |
| **C7** | **Missing or absent docstrings** — they render into agent context | ~**658** declarations, **95** `:description` (≈14 %); **29 of 70 files** carry none | **friction** | Every declaration a reader meets states what the value is and what its deletion does. |
| **C8** | **Kind stamps / table-pickers still stored** | `:seon.error/kind`, `:seon.eval/outcome`, `:seon.fn.binding/shape`, `:seon.issue/status`, `:seon.fn/{index-phase,capability-rule,analysis-phase}` | **friction** | Attribute presence selects; an unbounded `:keyword` classification is never a fact. |
| **C9** | **"We looked" not recorded** — declared-but-unrequired, unwritten provenance | `:seon.fn/error-facets`, `:seon.fn.arity/error-facets`, `:seon.fn.arity/error-facet-digest` (no writer in `src/`, absent from their entity maps) | **blocker** | G4: the derivation digest is a required member of the row it describes, or the question is unanswerable. |
| **C10** | **Weak types at declared boundaries** — bare `:map`, unbounded `:int`, degenerate tuple | `:seon.maintenance.result/value`, `:seon.error/data`, `:seon.effect/arguments`, `:seon.fn.{file,manifest}/findings`, `:seon.fn.arity/{min,max,order,argument-count}`, `:seon.error/evidence` | **friction** | A declared map has entries; a count has `{:min 0}`; a one-member enum in a tuple carries no information. |
| **C11** | **Duplicate declarations of one concept across files** | 4 spellings of basis-`t`; `:seon.fn.argument/order` + `/index`; `:seon.error.occurrence/process` + `:seon.error/process`; `:seon.error/throwable-class` + `/exception-class`; `:seon.eval/renderer` + `/renderer-fn` | **friction** | One declaration, aliased where reused. |
| **C12** | **Legacy vocabulary in live attribute names** | `:seon.error/run`, `:seon.effect/run`, `:seon.context.capture/run`, `:seon.effect/receipt`, `:seon.maintenance.receipt/*`, `:seon.eval/entity` keyed by `:seon.cluster.eval/*` | **cleanup** | AGENTS §3 vocabulary: turn, evaluation, result. |
| **C13** | **Absence read as the permissive arm** | `:seon.listen/entity`, `:seon.context.contribution/evaluations` + optional `:seon.render.block/name` | **blocker** | Absence is never the wildcard and never the selector. |

---

## 2. Findings per class

### C1 — the new error family is declared but unwritable (blocker)

**Evidence.** A facet is declared as, e.g.
`resources/seon/schemas/seon.db.read.edn:4-17`:

```clojure
:seon.db.read/error
[:and [:and {:seon.db/attributes true} :seon.error/base
       [:map {:seon.db/attributes true}
        [:seon.db/read-operation :seon.db/read-operation] …]]
 [:fn … seon.error/read-operation-agrees?]]
```

The base (`seon.error.edn:284-308`) declares `at`, `layer`, `operation`,
`message`, `member`, `expected-key`, `expected-shape`, `location`,
`offending-projection`, `evidence-items`, `evidence-unavailable`, `cause`,
`fix`, `basis` — **none carries `:seon.db/identity`**, and
`test/seon/schema_test.clj:1270-1271` asserts that deliberately
(*"An observation cannot upsert its domain subject"*).

The final write validator's root pass is
`src/seon/db.clj:3603-3608`:

```clojure
(when (and (seq value) (empty? identities))
  (fail! ::unowned-entity {::entity root ::entity-value value}))
```

A root is exempt only when some other entity owns it through a declared
component relation (`:3610-3617`). I extracted every
`:seon.db/component-schema` target across all 210 resources — **55 targets**
— and **no facet `…/error` key appears among them**. The 14 new *child*
schemas (`:seon.error.projection/entity`, `/evidence/entity`,
`/location/entity`, `/omission/entity`, `/key/entity`, `/basis/entity`,
`:seon.db.read.target/entity`, `:seon.db.write.attempt/entity`,
`:seon.instrument.arity/bounds`, `:seon.instrument.explanation{,s}/entity`,
`:seon.instrument.humanized{,.message}/entity`, `:seon.failure/entity`) **are**
owned and correct. The facets that own them are not.

Nor can a facet ride the existing fault row: the occurrence
(`seon.error.occurrence.edn:16-43`) is a separate entity map that does not
extend the base, and selection by its `:seon.error.occurrence/id` identity
would then require `:seon.error/data-edn`, `/capped?`,
`:seon.error.occurrence/message` and `/process` — which no facet supplies.

`:seon.error.disposition/observation` (`seon.error.disposition.edn:16-29`) is
the same shape and worse: `{:seon.db/attributes true}`, no identity, no owner,
and **no reference anywhere in `src/`** — an orphan declaration nothing
constructs.

**Design tension this exposes, worth an owner decision.** A component
relation declares exactly ONE `:seon.db/component-schema`
(`src/seon/db.clj:3589-3591` refuses a missing one), while facets *compose* —
one error value satisfies read + turn + agent simultaneously. So "own the
error as a component of the occurrence" needs the child schema to be
`:seon.error/base` with the facet members validated separately, or the base
needs its own identity.

**Fix (exact).** Add to `resources/seon/schemas/seon.error.edn`:

```clojure
:seon.error/observation-id
[:string {:min 12 :max 12 :seon.db/identity true
          :description "One recorded error observation. Minted by seon.id; the value is the row, never an upsert key for a domain subject."}]
```

and make it a required member of `:seon.error/base`. Every facet then
selects through it and validates as one owning value.

**Migration cost.** Reset-only for the schema; code change is the recorder
(`src/seon/error.clj:1427` `commit-call`, `:1506` `recording`) which must
mint the id. No consumers exist yet — `grep -rn 'seon.db.read/error' src/`
finds only the `:seon.db/error-result` union declaration
(`seon.db.edn:6-30`).

### C2 — two error models, and the predicate sees only the old one (blocker)

**Measured:** 52 `{:seon.error/class true}` schemas and 35 `[:= true]`
boolean marker attributes remain in this slice, distributed:
`seon.fn.edn` 13, `seon.env.edn` 8, `seon.flow.edn` 6, `seon.db.edn` 5,
`seon.dev.mcp.edn` 5, `seon.message.edn` 5, `seon.effect.edn` 3,
`seon.instrument.edn` 2, and one each in `seon.context.edn`,
`seon.dev.mcp.artifact.edn`, `seon.error.edn`, `seon.eval.drive.edn`,
`seon.fn.binding.edn`.

`seon.fn.edn:187-211` is the clearest instance: thirteen
`…-error` schemas, each a `[:= true]` stamp plus a message, sitting eleven
lines above the new `:seon.fn/error` facet at `:217-224`.

The predicate is the blocking half. `src/seon/error.clj:1735-1744`:

```clojure
(filter (fn [{schema-key :seon.schema/key}]
          (true? (:seon.error/class (class-properties forms schema-key)))))
```

and `error?` (`:1786-1796`) is `(boolean (seq (matched-error-classes value)))`.
`:seon.error/base` declares only `{:seon.db/attributes true}` — **no
`:seon.error/class`** — so a value satisfying base plus any facet is *not an
error* to the system's own predicate, and `error-marker` (`:1798-1809`)
falls back to `(:seon.error/kind value)`. `seon.error/facets`
(`src/seon/error.clj:1764`) and `facet-keys` (`:1745`) exist and are correct;
nothing calls them from the predicate.

`:seon.error/kind :keyword` (`seon.error.edn:268`) is **required** on the
stored fault (`:seon.error/error`, `:194`), on `:seon.error/fact` (`:113`)
and on the in-memory `:seon.error/value` (`:39`) — an unbounded keyword
classification that ruling 1o deletes.

**Migration cost.** Large and unavoidable: `:seon.error/kind` appears
**978 times across 85 files in `src/`**, in 10 resources, and **947 times in
`test/`**. `:seon.error/class` has exactly one consumer,
`src/seon/error.clj:1741`. The order that minimises churn: make `error?`
accept the base first (one function), then retire kinds family by family.

### C3 — 44 of 51 refs do not say what their deletion does (blocker)

The reset-batch policy (`reset-batch-2026-09-17.md:271`) and the datahike
skill both require the chosen behaviour in the attribute's docstring.
Measured in this slice: **51 `:seon.db/ref`-typed declarations, 7 with a
`:description`.** The 44 without, by file:

`seon.context.capture/run`; `seon.context.contribution/agent`;
`seon.db/{process,user}`; `seon.effect/{owner,notify,run}`;
`seon.error/{run,ref,steward,issue,resolved-tx,cause}`;
`seon.error.occurrence/{process,agent,turn,proc-fn,data-blob,cluster,evaluation}`;
`seon.failure/{fault,requested-tx,stopped-tx}`;
`seon.fn.argument/{binding,rest-tail-schema,rest-element-schema}`;
`seon.fn.arity/{input-schema,return-schema,guard-schema}`;
`seon.fn.binding.child/binding`; `seon.fn.binding.entry/binding`;
`seon.fn/ns`;
`seon.maintenance.receipt/{fire,task,handler,request,result,error}`;
`seon.maintenance.request/{task,fire,handler,agent}`;
`seon.maintenance.result/{reap-census,cluster-cleanup-collection}`.

The seven that do are the good precedent: `seon.message/{to,from,caused-by}`,
`seon.effect/{eval,file,program,to}`, `seon.ns/steward`,
`seon.issue/{agent,created-by,detector}` — each states the relation and,
for `to`, the refusal (`seon.message.edn:22`: *"Required peer ref refuses
recipient deletion on a surviving message."*).

**Migration cost.** Accretion only — a docstring per attribute, no reset.

### C4 — refs whose fact is an observation of a name (blocker)

Verified still present at HEAD. Where a prior note already carries the
finding I say so and confirm.

| Attribute | file:line | Why it is a name | Status |
|---|---|---|---|
| `:seon.error/run [:and #:seon.db{:index true} :seon.db/ref]` | `seon.error.edn:25` | Written as a lookup ref from a token and read straight back out: `(assoc :seon.error/run [:seon.turn/id run-id])` (`src/seon/error.clj:731`) and `(second (:seon.error/run fact))` (`:1183`, `:1536`), with a restore pass at `src/seon/problems.clj:96-97`. **A turn is compactable — compaction retracts evaluations and their turns — so the sweep silently un-attributes the fault.** A fault must outlive the turn that produced it. | **New**; the schema design review named `:seon.error/fn` (N13, since deleted) but not this sibling. |
| `:seon.eval/renderer-fn` | `seon.eval.edn:3` | Sits beside `:seon.eval/renderer` (`:2`), already `:qualified-symbol`, with near-identical docstrings. **Eleven consumer sites are all ref→symbol round trips**: `src/seon/repl.clj:385,387`; `src/seon/turn.clj:107,1346,1436,1450-1452`; `src/seon/render/transcript.clj:48,277`. Every one asks `(get-in … [:seon.eval/renderer-fn :seon.fn/sym])` — the name-observation-wearing-a-ref tell, verbatim. | schema-design-review **N6** (*"delete it"*), **still present**. |
| `:seon.error.occurrence/{turn,proc-fn,evaluation}` | `seon.error.occurrence.edn:8,9,49` | `proc-fn` is a ref the error PRD §4.2 replaces with the observed symbol; `turn`/`evaluation` point at compactable rows. | PRD ruled; **not applied**. |
| `:seon.listen/entity` | `seon.listen.edn:2` | `[:and {:description "Optional entity constraint; absent matches any entity."} :seon.db/ref]` — **retracting the watched entity sweeps the constraint and the wake pattern widens from one entity to every entity, with no signal.** | schema-design-review **N8** (*"worst instance in the review"*), **still present, unchanged**. |
| `:seon.issue/{functions,tests,errors,keys,namespaces,runs,issues}` | `seon.issue.edn:8,11,14,16,18,23,25` | Each carries `:seon.issue/cites [<identity attribute>]` — the property names the token spelling the citation was resolved through — and `:seon.issue/unresolved` (`:27`) already holds the tokens that resolved to nothing. | schema-design-review **N5**, **still present**. |

**Already fixed since the 2026-09-17 review — do not re-report:**
`:seon.fn/calls`, `/references`, `/writes` are now indexed values
(`seon.fn.edn:31,50,52-55`); `:seon.fn/sym` is `:qualified-symbol` (`:175-179`);
`:seon.fn.arity/{input,output,guard}-refs` are
`[:set {:seon.db/index true} :qualified-keyword]` (`seon.fn.arity.edn:2,4,13`);
`:seon.fn/capability-fn`, `:seon.effect/capability-fn`, `:seon.error/fn`,
`:seon.fn/ast`, `:seon.fn/pending-calls` are **deleted**;
`:seon.message/about` and `/assignment` are indexed values with
`/from` the sole inside marker (`seon.message.edn:31-36,109-115`) and
`:seon.message/inbox` is gone; `:seon.eval/origin` is `:seon.issue/id`
(`seon.eval.edn:1`); `:seon.program/analyzed-source-digest` is a **required**
member of `:seon.fn/fn` (`seon.fn.edn:116`), which is G4 landed.
`:seon.fn/reference-to` survives (`seon.fn.edn:7`) with one remaining user,
`:seon.schedule.task/function` (outside this slice), read at
`src/seon/fn.clj:553,1303,1309`.

### C5 — the new family has no render pair (friction)

All 16 facets and `:seon.error/base` declare properties
`{:seon.db/attributes true}` and nothing else. The error PRD §4.3 names
`:seon.error/facet-ai` / `/facet-html` as the common data-driven pair;
`grep -rn 'facet-ai' src resources` returns **nothing** — the functions do
not exist. Meanwhile the legacy `:seon.error/error` and `:seon.error/fact`
both declare `seon.error/render-ai` / `render-html`
(`seon.error.edn:108-109`, `:190-191`), so retiring the legacy family
without landing the pair removes the render, which for an error is exactly
the surface an agent reads.

`src/seon/schema/form.cljc:65` merges the property maps of every literal
`:map` in an `:and`, so a pair declared on `:seon.error/base` is inherited by
every facet automatically — the cheapest fix is one pair on the base.

### C6 — seven digests that do not constrain hex (friction)

`:seon.blob/digest` is `[:re "^[0-9a-f]{64}$"]` (`seon.blob.edn:2`) and
`:seon.source/digest` is the same with `{:seon.db/identity true}`
(`seon.source.edn:10-11`). These seven accept any 64 characters:

| Attribute | file:line | Note |
|---|---|---|
| `:seon.error/dropped-fault-digest` | `seon.error.edn:104` | pre-existing |
| `:seon.error/signature` | `seon.error.edn:275` | pre-existing, and it is an **identity** |
| `:seon.error/expected-shape` | `seon.error.edn:332` | **new 2026-09-18**; the PRD §2.2 specifies `:seon.source/digest` |
| `:seon.fn.arity/error-facet-digest` | `seon.fn.arity.edn:44` | **new 2026-09-18** |
| `:seon.fn.file/digest` | `seon.fn.file.edn:4` | schema-design-review **N35**, still present — *the analysis-provenance seam G4 just made required* |
| `:seon.instrument/declared-facet-digest` | `seon.instrument.edn:103` | **new 2026-09-18**; a live writer fabricates `(apply str (repeat 64 "0"))` at `src/seon/error.clj:2464` |
| `:seon.instrument.explanation/expected-shape` | `seon.instrument.explanation.edn:40` | **new 2026-09-18**; §2.2 specifies `:seon.source/digest` joined to `:seon.schema.shape/fingerprint` |

Four of the seven were introduced *after* N35 recorded the class. Fix is
one alias substitution each; reset-only.

### C7 — docstrings (friction)

Approximate EDN-shaped parse of the slice: **~658 declarations, 95 carrying
`:description`** (≈14 %). **29 of 70 files carry no `:description` at all**,
including `seon.flow.edn` (49 declarations), `seon.maintenance.result.edn`
(27), `seon.env.edn` (20), `seon.eval.drive.edn` (14), `seon.dev.mcp.edn`
(11), `seon.fn.binding.edn` (10), `seon.maintenance.receipt.edn` (10).
Every one of these renders into an agent's context through `dir`/`doc` and
the schema pair. The counts are a regex approximation over declaration
heads, not a Malli parse; the *file* list is exact.

### C8 — kind stamps and table-pickers still stored (friction)

| Attribute | file:line | Verdict and status |
|---|---|---|
| `:seon.error/kind :keyword` | `seon.error.edn:268` | **Dissolve** (ruling 1o). Required on three maps; see C2. |
| `:seon.eval/outcome [:enum :ok :time :error]` | `seon.eval.edn:40` | Dissolve — attribute presence (`/error`, `/interrupted-at`, `/shown`) already selects it. schema-design-review **N25**, still present. |
| `:seon.fn.binding/shape [:enum :symbol :map :sequential]` | `seon.fn.binding.edn:2` | Dissolve — a table-picker for `/symbol`, `/entries`, `/children` (`:3,7,5`), and **required** at `:12`. schema-design-review **N27**, still present. |
| `:seon.issue/status [:enum :open :resolved :superseded]` | `seon.issue.edn:3`, required at `:49` | A stored mirror of `:seon.issue/resolved-tx` (`:38`, docstring *"absence means open"*) on the **same entity**. **Do not dissolve yet** — the modeling study measured 1 766 status datoms against 0 `resolved-tx` (override O6); the writer comes first. |
| `:seon.fn/{index-phase,capability-rule}` `:keyword`; `/missing-population`, `/analysis-phase` `:qualified-keyword` | `seon.fn.edn:181,182,184,214` | Unbounded keyword classifications carried only by the legacy `-error` maps and the new `:seon.fn/error` facet. Name the actual observation or declare the closed set. |
| `:seon.fn.binding.child/role`, `:seon.fn.binding.entry/spelling`, `:seon.lint/type`, `:seon.lint/level`, `:seon.fn/{workload,external-sink,projection-boundary}`, `:seon.db.read.target/index`, `:seon.instrument/{arm,check}`, `:seon.error.disposition/action` | — | **Keep** — bounded grammars of the reader, the linter, core.async, Datahike and a control decision. Already ruled; recorded so a later sweep does not take them. |

### C9 — declared provenance nothing writes (blocker)

`:seon.fn/error-facets` (`seon.fn.edn:226-227`),
`:seon.fn.arity/error-facets` and `/error-facet-digest`
(`seon.fn.arity.edn:43-47`) are declared and indexed, and:

- **none is a member of its entity map** — `:seon.fn/fn` is
  `seon.fn.edn:114-154`, `:seon.fn.arity/row` is `seon.fn.arity.edn:14-40`;
  neither names them, so the whole-entity validator never requires them;
- **no writer exists**: `grep -rn 'error-facets\|error-facet-digest' src/`
  returns nothing but the unrelated `:seon.instrument/declared-facet-digest`
  at `src/seon/error.clj:2464` and `src/seon/instrument.clj:757`.

So "this arity declares no error facets" and "nobody ever derived its error
facets" are byte-identical — the exact G4 disease, freshly declared. Error
PRD §5.1 requires the opposite: *"a **new required derivation digest** proves
this operation ran."*

**Fix.** Add `[:seon.fn.arity/error-facet-digest
:seon.fn.arity/error-facet-digest]` as a **required** member of
`:seon.fn.arity/row`, and leave `/error-facets` optional (an empty set
stores nothing and is then legitimately readable as "none").

### C10 — weak types at declared boundaries (friction)

| Form | file:line | Why it is wrong | Fix |
|---|---|---|---|
| `:seon.maintenance.result/value :map` | `seon.maintenance.result.edn:250` | The bridge has **no `:map` case** (`src/seon/schema/datahike.clj:298-305` throws `::value-type-unavailable`), so the attribute has **no datoms** — the datahike skill names this exact attribute as the live instance. Beside it `:seon.maintenance.result/entity` (`:246-249`) declares **one entry, the id**, so the validator selects the root and checks nothing. schema-design-review **C.3/N44**, unchanged. | Root declares its twelve component attributes; `/value` gets the real union or goes. |
| `:seon.error/data :map` | `seon.error.edn:211` | In-memory only (it is a member of `:seon.error/value`, not an attributes map), but it is the opaque bag the PRD replaces with typed evidence. | Replaced by `:seon.error/evidence-items` + facet members. |
| `:seon.effect/arguments [:map {:description …}]` | `seon.effect.edn:47-50` | A declared map with **no entries** at a writer boundary. | Declare the entries, or say in the docstring it is an open transaction-function input with no promised shape. |
| `:seon.fn.file/findings [:vector :map]`, `:seon.fn.manifest/findings [:vector :map]` | `seon.fn.file.edn:19`, `seon.fn.manifest.edn:6` | `:map` with no entries inside the indexing artifact contract. | `[:vector :seon.lint/finding]` — the shape already exists at `seon.lint.edn:11`. |
| `:seon.fn.arity/{min,max,order,argument-count} :int`; `:seon.fn.argument/{order,index} :int` | `seon.fn.arity.edn:7,10,11,12`; `seon.fn.argument.edn:1,2` | Bare `:int` admits negatives for counts and ordinals. The sibling `:seon.instrument.arity/{min,max,ordinal}` gets this right with `[:int {:min 0}]` (`seon.instrument.arity.edn:18-25`). | `[:int {:min 0}]`. |
| `:seon.error/evidence [:tuple [:enum :seon.error/id] :seon.error/id]` | `seon.error.edn:36` | A two-member tuple whose first member is a **one-member enum** — it carries zero information, and `check-tuple` passes an all-invalid tuple anyway (`db/transaction.cljc:1037`). Written at `src/seon/error.clj:827`. | The id alone. |
| `:seon.error.basis/t :int` | `seon.error.basis.edn:21-22` | A basis `t` with no lower bound, while `:seon.error/basis-t` next door is `[:int {:min 0}]` (`seon.error.edn:258`). | `:seon.db/basis-t`, as the PRD §2.2 specifies. |
| `:seon.context.contribution/hash [:string {:min 1}]` | `seon.context.contribution.edn:32` | A "hash" with no format at all. | `:seon.blob/digest` if it is one. |

### C11 — one concept, several declarations (friction)

- **Basis `t`, four spellings**: `:seon.db/basis-t :int` (`seon.db.edn:196`),
  `:seon.error/basis-t [:int {:min 0}]` (`seon.error.edn:258`),
  `:seon.error.basis/t :int` (`seon.error.basis.edn:21`),
  `:seon.context.capture/basis-t [:int {:min 0}]`
  (`seon.context.capture.edn:1`). Alias all three to `:seon.db/basis-t` and
  give that one the bound.
- **Two ordinals on one row, neither documented**:
  `:seon.fn.argument/order` and `/index`, both required
  (`seon.fn.argument.edn:1-2`, `:15-16`). schema-design-review **N34**,
  unchanged.
- **Two "which process" on one entity**: `:seon.error.occurrence/process`
  and `:seon.error/process` both required on the occurrence
  (`seon.error.occurrence.edn:22`, `:28`). schema-design-review **B.6**,
  unchanged.
- **Two "which Throwable class"**: `:seon.error/throwable-class
  [:string {:min 1}]` (`seon.error.edn:270`) beside
  `:seon.error/exception-class [:symbol]` (`:277`), whose own docstring
  admits *"throwable-class retains the historical string evidence."*
  Symbols inventory §1.1 item 9, unchanged.
- **Two renderers**: `:seon.eval/renderer` + `/renderer-fn` (C4 above).
- **Typed-sibling literal pattern, declared twice in this slice**:
  `:seon.fn.argument/label-{edn,keyword,string,symbol}`
  (`seon.fn.argument.edn:8-11`) and the eleven
  `:seon.schema.map-entry/key-*` entries pasted into
  `:seon.fn.binding.entry/row` (`seon.fn.binding.entry.edn:19-47`).
  schema-design-review **N22/C.4**, unchanged.

### C12 — legacy vocabulary in live attribute names (cleanup)

`:seon.error/run` (`seon.error.edn:25`), `:seon.effect/run`
(`seon.effect.edn:131`), `:seon.context.capture/run`
(`seon.context.capture.edn:40`) are all refs to a **turn**;
`:seon.effect/receipt` (`seon.effect.edn:60`) and the whole
`seon.maintenance.receipt` resource are "receipt", which owner-decisions
Part 3 retires in favour of **evaluation** / **result**
(schema-design-review **B.11**, unchanged — and B.11's point stands: these
are declared attribute families, so the legacy word reaches every agent's
rendered context).

`:seon.eval/entity` (`seon.eval.edn:5-36`) is the sharpest instance: the
*evaluation* entity's own identity, ordinal, source, output and error all
live under `:seon.cluster.eval/*`, which the AGENTS §3 vocabulary table
lists as the legacy spelling, while `:seon.eval/*` holds only `shown`,
`origin`, `renderer` and the counters.

### C13 — absence as the permissive arm (blocker)

- `:seon.listen/entity` — see C4. Absence widens the pattern; a deletion
  makes it global.
- `:seon.context.contribution/evaluations [:set :seon.db/ref]`
  (`seon.context.contribution.edn:30`, optional at `:20-22`) with
  `:seon.render.block/name` **also optional** (`:8-10`): the block's identity
  is optional and its emptiness is the selector.
  schema-design-review **N17** asked for `:seon.render.block/name` to be
  required; **unchanged**.
- Related, and fixed: `:seon.context.capture/prompt` no longer carries
  `:seon.db/no-history? true` and its docstring now states temporal
  retention (`seon.context.capture.edn:37-39`) — J6's objection is resolved.

### The program-graph question: can the graph answer "what breaks if I change F"?

Almost. Direct callers, references and written keys are now **indexed
values** (`seon.fn.edn:31,50,52-55`) and call arities are a value tuple
(`:56-70`), so a deletion leaves the caller's datom intact and
"names something with no row" is one clause. **Three attributes still block
a purely-by-query answer** in this slice:

1. `:seon.fn/arities → :seon.fn.arity/{input,return,guard}-schema`
   (`seon.fn.arity.edn:3,8,9`) are **refs into shared shape rows**, and
   `:seon.fn/arities` is a **component vector** (`seon.fn.edn:30`) — so
   deleting a function cascades its arities, and "which functions accepted
   this shape" loses arcs with no signal. Correct as a containment
   relation; the missing half is that no docstring says so.
2. `:seon.fn/error-facets` / `:seon.fn.arity/error-facets` — declared,
   unrequired, unwritten (C9). "What errors can F return" is currently
   unanswerable and *reads as an empty set*.
3. `:seon.fn/ns` (`seon.fn.edn:168`) is a bare `:seon.db/ref` with no
   docstring, required by `:seon.fn/fn` (`:120`) — so a namespace deletion
   refuses, which is right, and nothing says it.

`:seon.fn/unresolved-references` (`seon.fn.edn:51`) is the model of a
correct declaration: a value set whose docstring states exactly what its
absence does *not* prove.

---

## 3. The three highest-leverage refactors

### R1 — give `:seon.error/base` an identity, so the facet family can be written

Unblocks C1 (16 facets + 1 orphan), and is the prerequisite for retiring C2.

```clojure
;; resources/seon/schemas/seon.error.edn  — add
 :seon.error/observation-id
 [:string {:min 12 :max 12 :seon.db/identity true
           :description "One recorded error observation, minted by seon.id/id. It identifies the observation only; it never upserts the domain subject the error is about."}]

;; and in :seon.error/base, ADD as the first required member:
 [:seon.error/observation-id :seon.error/observation-id]
```

Delete `resources/seon/schemas/seon.error.disposition.edn`'s
`:seon.error.disposition/observation` entity map, or make it a component of
the recorded error:

```clojure
;; seon.error.edn — add
 :seon.error/disposition
 [:and {:seon.db/component true
        :seon.db/component-schema :seon.error.disposition/observation
        :description "The control decision this boundary took. Component: it is part of the observation's value and dies with it."}
  :seon.db/ref]
```

**Cost:** reset-only for the schema; one code change in the recorder
(`src/seon/error.clj:1427`, `:1506`). **Severity: blocker.**

### R2 — make `error?` recognize the base, then delete the class markers

```clojure
;; src/seon/error.clj:1786 — error? becomes
(if-let [projection (schema/current-projection)]
  (boolean (or (seq (facets projection value))
               ((schema/projection-validator projection :seon.error/base) value)))
  (and (map? value) (contains? value :seon.error/message)))
```

Then, per family, delete the `{:seon.error/class true}` schema and its
`[:= true]` attribute and move its payload into the facet — **52 schemas,
35 attributes** in this slice. `:seon.error/class` has exactly one consumer
(`src/seon/error.clj:1741`); `:seon.error/kind` has **978 `src/` sites in 85
files** and **947 test sites**, so this is the expensive one and it belongs
to the reset, family by family, with the predicate landing first so nothing
is unrecognized in between.

**Cost:** one function, then reset + 85 source files. **Severity: blocker.**

### R3 — require the derivation digests, and constrain every digest to hex

```clojure
;; seon.fn.arity.edn — inside :seon.fn.arity/row, add as REQUIRED:
 [:seon.fn.arity/error-facet-digest :seon.fn.arity/error-facet-digest]
;; and retype, here and in the six siblings:
 :seon.fn.arity/error-facet-digest :seon.blob/digest
```

Same substitution for `:seon.error/expected-shape` (`seon.error.edn:332`),
`:seon.instrument/declared-facet-digest` (`seon.instrument.edn:103`),
`:seon.instrument.explanation/expected-shape`
(`seon.instrument.explanation.edn:40`), `:seon.fn.file/digest`
(`seon.fn.file.edn:4`), `:seon.error/dropped-fault-digest`
(`seon.error.edn:104`), and — with identity retained —
`:seon.error/signature` (`:275`):

```clojure
 :seon.error/signature [:re {:seon.db/identity true} "^[0-9a-f]{64}$"]
```

This also retires the live fabrication at `src/seon/error.clj:2464`
(`(apply str (repeat 64 "0"))`), which the current declaration accepts and
a hex format still would — so that writer needs a real digest in the same
slice.

**Cost:** reset for the type changes; the required-member change needs the
analyser that writes the digest (error PRD §5.1, `seon.fn/add-contract-facts`
at `src/seon/fn.clj:2327`), which does not exist yet.
**Severity: blocker (C9) + friction (C6).**

---

## 4. What I could not verify without a JVM

1. **Whether the `::unowned-entity` refusal actually fires for a facet
   error.** I read the declaration (no identity, no owning component
   relation) and the validator (`src/seon/db.clj:3603-3608`), and I confirmed
   no `:seon.db/component-schema` names a facet. I did **not** transact one.
   `test/seon/schema_test.clj:1244-1281` proves only that every facet member
   is `storable-attribute-in?` and that its generators satisfy the
   declaration — it never writes a facet row, so it cannot refute or confirm
   this.
2. **Live datom counts.** Every count in this note is a source count. The
   modeling study's live figures (basis 536871223, 2026-09-17) are the last
   measured population; I did not re-read `default`.
3. **Whether the installed schema on any cluster matches these resources.**
   `storable-attribute-in?` answers per projection; a resource is live on
   disk the moment it is written while a JVM still holds the previous
   declarations (AGENTS §7). Nothing here claims the installed schema.
4. **Whether `:seon.maintenance.result/value` is genuinely datom-less
   today.** The bridge has no `:map` case
   (`src/seon/schema/datahike.clj:298-305`) and the datahike skill names it
   as the live instance; I did not re-measure.
5. **Render behaviour of a base-only error.** `seon.error/facet-ai` does not
   exist, so the default attribute printer is what an agent would see — I
   did not render one.
6. **The `:fn` predicates on the new child schemas** (`evidence-complete?`,
   `projection-complete?`, `ordered-location?`, and ten more) all exist in
   `src/seon/error.clj` with their `:gen/gen` generators as `def`s
   (`:2364`, `:2411`, …). I confirmed existence by name; I did not exercise
   them.

## Verification boundary

No JVM, no `bin/test`, no `bin/seon`, no cluster operation, no MCP
evaluation, no background shell, no scratch root. One file written: this
note. All other working-tree edits were left untouched. The `:seon.error/kind`
and `:seon.error/class` consumer counts are `grep` over `src/` and `test/`;
the 51-ref, 658-declaration and 95-description figures are a regex-shaped
parse of the 70 slice resources and are approximations of declaration heads,
while the per-file lists and every `file:line` citation are exact bytes I
opened.
