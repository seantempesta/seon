---
type: research
status: active
created: 2026-09-17
tags: [schema, datahike, malli, refs, enums, duplication, reset]
---

# Schema design review — the session's learnings applied to every family, 2026-09-17

Read-only source review of all 74 marked entity maps across the 55 resources
under `resources/seon/schemas/`. Every claim below names a line I opened. This
document carries **only findings the schema-key audit does not already carry**;
where I extend one of its rows I say so. No production edit, no test JVM, no
cluster operation. The one permitted read-only `mcp__seon__eval_clj` call was
**not spent**: every question was answerable from checked-out bytes, and the
audit's own live read already settled the evaluation-identity question
(`schema-key-audit-2026-09-16.md:30-52`).

Authorities read end to end before writing: AGENTS.md §2–§3;
`.agents/skills/data-modeling/SKILL.md`; `.agents/skills/datahike/SKILL.md`;
[program-facts PRD §1f G1–G6](../plan/program-facts-are-the-runtime-prd-2026-09-17.md);
[the deletion study](datahike-deletion-and-the-program-graph-2026-09-16.md)
§7, §8, §8.1; [entity vs pulled shape](entity-schema-vs-pulled-shape-2026-09-16.md);
[the schema-key audit](schema-key-audit-2026-09-16.md);
[evaluation write path](evaluation-write-path-and-retired-identities-2026-09-16.md) §3;
[edges are symbols](edges-are-symbols-plan-2026-09-16.md) §1, §4;
[symbols everywhere](symbols-everywhere-inventory-2026-09-17.md);
[owner decisions](../plan/owner-decisions-2026-09-17.md) Part 3.

## 0. The one sentence

**G1 (deletion is retraction) removes the protection that made every remaining
`:seon.db/ref` safe, and no document names the replacement.** Ruling 47's
"program identity rows never retract" was the reason a ref could be trusted;
retiring it turns all **207** `:seon.db/ref` mentions in the resources
(`grep -c 'seon.db/ref' resources/seon/schemas/*.edn`, summed) into potential
silent losses at the *referrer*, because `retract-entity` sweeps incoming ref
datoms (`reference-code/datahike/src/datahike/db/transaction.cljc:998-1015`).
G2 named exactly two edges. This review finds **eleven more name-edges** with
the same shape, and the most-referenced deletable entity in the population —
the agent — has no declared consequence at all.

---

## A. New findings, defects first

Learning numbers are the assignment's: 1 ref-for-a-name, 2 absence-as-state,
3 duplication, 4 kind stamps, 5 strings-that-are-values, 6 provenance/time,
7 cardinality/order, 8 boundary contracts.

### A.1 Refs whose fact is a name — the G2 class beyond `calls` and `reach`

Every row: the writer resolves the target from a token it does not own, and
`[:db/retractEntity target]` silently deletes the **referrer's** fact.

| # | Attribute | file:line | Why the fact is a name | Deletion consequence today | Edit | L |
|---|---|---|---|---|---|---|
| N1 | `:seon.fn/references` | `seon.fn.edn:42` | Its own docstring: "Function references without a resolved call shape … no invocation or arity is asserted." That is a name the analyzer saw in text, identical to `:seon.fn/calls`. **G2 does not name it.** | Retracting the target deletes the referrer's whole reference fact; selection silently narrows, which is the project's named failure class. | `[:set :qualified-symbol]`, indexed, exactly as G2's `calls`. | 1 |
| N2 | `:seon.fn/writes` | `seon.fn.edn:44-56` | Docstring: "Each value is a `:seon.schema/key` row, never a keyword literal." The analyzer saw a **qualified keyword** inside a `transact!` span. `:seon.fn/keywords` (`:105-109`) stores the same kind of observation as `[:set :qualified-keyword]` **values** in the same file. One file, one fact, two encodings. | Deleting a schema key (G1) retracts every writer's `writes` datom. "Who writes this attribute" silently answers fewer. | `[:set {:seon.db/index true} :qualified-keyword]`; the join to `:seon.schema/key` stays available because that identity *is* the keyword. | 1 |
| N3 | `:seon.schema/references` | `seon.schema.edn:50-53` | "Canonical schema rows directly referenced by this schema" — resolved from registry keys appearing in a Malli form. | Deleting a schema key retracts every referrer's edge; the reference graph loses arcs with no signal. | `[:set :qualified-keyword]` (the key **is** `:seon.schema/key`, `seon.schema.edn:43`, a `:keyword` identity). | 1 |
| N4 | `:seon.fn.arity/input-refs`, `/output-refs`, `/guard-refs` | `seon.fn.arity.edn:5`, `:15`, `:3` | AGENTS §2.2's own illustration reads them as `[?f :seon.fn.arity/input-refs :seon.db/connection]` — a **keyword** in the clause. They are resolved from registry keys named in the contract form. | Same as N3, on the arity component. Also the component is destroyed with its parent, so a deleted schema key silently changes a *surviving* function's declared custody. | `[:set :qualified-keyword]`. | 1 |
| N5 | `:seon.issue/functions`, `/tests`, `/errors`, `/keys`, `/namespaces`, `/runs`, `/issues` | `seon.issue.edn:8`, `:11`, `:14`, `:16`, `:18`, `:22`, `:24` | Each carries `:seon.issue/cites [<identity attribute>]` — the property *names the token spelling* the citation was resolved through — and `:seon.issue/unresolved` (`:27`) already holds the tokens that resolved to nothing. That is `calls` + `unresolved-references` re-spelled per family. | Retracting a cited function/test/namespace deletes the citation, so the note's own prose stops being represented in the database while the note file is unchanged. `unresolved` is not updated, so the loss is invisible on both sides. | Store the cited **token** at its declared type (`:qualified-symbol` for functions/tests, `:symbol` for namespaces, `:qualified-keyword` for keys, `:string` for run/issue ids); `unresolved` then dissolves into "a cited token with no row", one `not-join`. | 1, 3 |
| N6 | `:seon.eval/renderer-fn` | `seon.eval.edn:3` | A ref to the renderer's program row **beside** `:seon.eval/renderer` (`:2`), already `:qualified-symbol`, with docstrings that describe the same fact twice ("the declared AI renderer that produced the saved shown text" / "the stable program identity of the renderer that produced the saved shown text"). | Deleting the renderer function retracts `renderer-fn` from every historical evaluation; the symbol survives, proving the ref buys nothing. It is also D1 widening #2 in the pulled-shape study. | **Delete `:seon.eval/renderer-fn`.** One key, the symbol. | 1, 3 |
| N7 | `:seon.test/pending-subject` | `seon.test.edn:11` | Exists only because `:seon.test/subject` (`:10`) is a ref that must not dangle — the tests' copy of the `calls`/`pending-calls` pair. The edges plan removes `pending-calls` but retypes `pending-subject` and keeps it (`edges-are-symbols-plan-2026-09-16.md:117-120`). | Deleting the subject retracts `subject` and nothing writes `pending-subject`, so the test silently loses its subject. | One `:seon.test/subject :qualified-symbol`; **delete `pending-subject`** in the same beat `pending-calls` goes. | 1, 3 |
| N8 | `:seon.listen/entity` | `seon.listen.edn:2` | Optional ref, docstring: "absent matches any entity." | **Worst instance in the review.** Retracting the watched entity retracts the constraint, and the pattern silently **widens from one entity to every entity**. A deletion converts a narrow wake pattern into a global one, with no signal. | Store the entity's identity value, or make the pattern refuse when its constraint is absent instead of treating absence as a wildcard. Absence must not be the permissive arm. | 1, 2 |
| N9 | `:seon.schedule.task/function` | `seon.schedule.task.edn:3` | Required ref carrying `:seon.fn/reference-to :seon.fn/sym` — the property exists precisely to re-derive the name. | Deleting the handler retracts a **required** key from a surviving task entity. `retract-entity`'s sweep runs no re-validation of the referrer, so the task persists in a state its own schema forbids. | `:qualified-symbol`; resolve at fire time and report an unresolved handler as a typed unknown. | 1, 2 |
| N10 | `:seon.fn/capability-fn`, `:seon.effect/capability-fn` | `seon.fn.edn:5-11`, `seon.effect.edn:2-6` | Both carry `:seon.fn/reference-to :seon.fn/sym` beside a `:seon.effect/capability` **symbol** the metadata already carries (`seon.fn.edn:8` says so verbatim). | Deleting the handler retracts the link; the capability symbol survives. The ref is the redundant half. | Delete the ref; join on the symbol. Marked judgment: the ref is one hop cheaper for the render path — see §C.6. | 1, 3 |
| N11 | `:seon.test.failure/file` + `/reported-file` | `seon.test.failure.edn:13`, `:21` | The resolved file ref and the raw reported path stored side by side: the resolved/unresolved pair again. | Deleting the file entity retracts `file`; `reported-file` survives, so the pair already demonstrates that the string is the durable half. | Keep `reported-file` (a path, honestly a string); delete the ref or declare it derived. | 1, 3 |

**Genuine entity relations — keep the ref** (stated because the assignment asks
for the verdict either way): `:seon.fn/ns`, `:seon.fn/file`, `:seon.fn/ast`,
`:seon.fn/arities` (components or true containment); `:seon.message/to`,
`/from`, `/inbox`, `/caused-by`; `:seon.issue/agent`, `/created-by`;
`:seon.ns/steward` (`seon.ns.edn:33-38` — "the one agent that stewards this
namespace"; an agent is a live entity, not a name in text);
`:my.plan.item/subject`, `:seon.message/about`, `:seon.eval/origin` — see N12.

| # | Attribute | file:line | Verdict |
|---|---|---|---|
| N12 | `:seon.eval/origin` | `seon.eval.edn:1` | **Keep the ref, but it is untyped**: "the entity whose render declared this generated evaluation" can point at any family, so no schema states what it points at and no reader can validate it. It is also D1 widening #5, the one failing live. Concrete edit: declare the admitted target families in the docstring and let the derived pulled form (option B of the pulled-shape study) carry the reader's shape. Under G1, retracting the origin entity silently un-provenances every generated evaluation in history — record that consequence in the docstring rather than pretending it cannot happen. |
| N13 | `:seon.error/fn`, `:seon.instrument/fn` | `seon.error.edn:271`, `seon.instrument.edn:10` | `:seon.error/fn` is a ref, `:seon.instrument/fn` is a string of the same fact, and both ride the same `:seon.error.occurrence/occurrence` map (`seon.error.occurrence.edn:36`, `:26`). **Two spellings of "which function", on one entity.** The symbols inventory retypes `:seon.instrument/fn` to `:qualified-symbol` (§1.1 item 8) without noticing the ref beside it. Edit: keep the symbol, delete `:seon.error/fn`. A fault must survive the deletion of the function that caused it — that is the whole point of a fault record. |

### A.2 Absence read as a state, where a positive fact belongs

| # | Reader | Attribute | Evidence | What is missing | L |
|---|---|---|---|---|---|
| N14 | `seon.turn` since-diff | `:seon.cluster.eval/read-evidence` | `src/seon/turn.clj:122`, `:846` (`(when-not (seq (:seon.cluster.eval/read-evidence prior)) …)`), `:1519`, `:1549` | An empty evidence vector decides **"this evaluation was not a read"**, so the since-diff never refreshes it. The datahike skill states the opposite rule verbatim: "An empty result still has read dependencies. Missing evidence is unknown, never an empty set that proves health." This is G4's disease inside the turn loop, not in the program graph. Edit: a positive `:seon.cluster.eval/read-observed-tx` (or require `read-basis-transaction`, `seon.cluster.eval.edn:2`, on every evaluation that ran under the evidence-capturing path) so `read? = presence`, `no-dependencies = that fact with no evidence members`. | 2 |
| N15 | `seon.test` selection | `:seon.test/reach`, `/reach-digest`, `/reach-unknown` | `src/seon/test.clj:68` (`(not (seq (:seon.test/reach row)))`), `:563-564`; declaration `seon.test.edn:2`, `:5`, `:4` | The `reach-unknown` docstring **confesses the defect**: "absence of both membership and this marker is legacy unknown, never proof of an empty closure." Three attributes, three absence arms, and a **string sentinel** doing a fact's job. `:seon.test/reach-digest` is already almost G4's provenance fact but is optional. Edit: make the analysis-provenance digest **required** on a test with a recorded reach (the edges plan's `:seon.program/analyzed-source-digest`, `edges-are-symbols-plan-2026-09-16.md:74-80`), then **delete `:seon.test/reach-unknown`** — the typed unknown becomes "provenance present, no members" versus "provenance absent". | 2 |
| N16 | `seon.fn` reconciliation | `:seon.fn/ast` | `src/seon/fn.clj:2283` (`(or (nil? (:seon.fn/ast row)) …)`) | Absent AST means "never analyzed" and triggers a rebuild — the same empty/never conflation G4 rules out, on a **component tree** whose emptiness is also unrepresentable. Covered once the required provenance digest lands; named here so the AST family is not missed. | 2 |
| N17 | `seon.context` | `:seon.context.contribution/evaluations` | `src/seon/context.clj:483` | `(seq …)` on a stored set decides which block a contribution is. Edit: the block's identity is `:seon.render.block/name` (`seon.context.contribution.edn:8`), which is **optional**; require it and select on it. | 2, 4 |
| N18 | `seon.repl` | `:seon.eval/renderer` | `src/seon/repl.clj:476` (`(not (:seon.eval/renderer emission))`) | Absence of a renderer decides **agent-authored vs generated** — the same question `:seon.cluster.eval/author` stamps as an enum (see N24). Two mechanisms for one derivation, and neither is the ruled one. | 2, 3, 4 |

**Entity lifecycles with no positive fact, read by absence today** (the
assignment asks which are missing; the audit's list of existing positive facts
at `schema-key-audit-2026-09-16.md:1534` is complete for what exists):

| Entity | Creation | Deletion | Consequence |
|---|---|---|---|
| `:seon.agent/agent` (`seon.agent.edn:2-24`) | none — existence is "has a `:seon.agent/id` datom" | none, and under G1 it is now `retractEntity` | **The largest G1 blast radius in the population.** Retracting one agent silently sweeps `:seon.message/to`/`/from`/`/inbox`, `:seon.issue/agent`/`/created-by`, `:seon.ns/steward`, `:my.note/agent`, `:seon.context.contribution/agent`, `:seon.error.occurrence/agent`, `:seon.error/steward`. Another agent's message history loses its sender with no signal. Nothing in any schema or ruling names this. |
| `:seon.ns/ns` stewardship | `:seon.ns/steward` carries `:seon.program/written-by seon.cluster.agent/steward-call` (`seon.ns.edn:26-29`) | none | "Unstewarded" and "steward deleted" are the same bytes. |
| `:seon.render.cost/fact` (`seon.render.cost.edn:6-15`) | `/at` instant only | n/a | The fact has **no identity and no link to what it measured** — four scalars floating free. "Which render cost this?" is unanswerable, which AGENTS §2.2 rules a defect report about the data model. |
| `:seon.cluster/cluster` (`seon.cluster.edn:2-9`) | none | none | Same shape as the agent, smaller blast radius. |
| `:seon.blob` content | digest only | konserve GC | Out of scope here; named so the sweep is complete. |

### A.3 Printed EDN where the bridge already owns a codec

`seon.schema.datahike/edn-encoded-attr-in?` (`src/seon/schema/datahike.clj:346`)
plus `encode-transaction` and `decode-attribute-value` (`:458`, `:583-594`)
**automatically** store a mixed `[:or …]` union as an EDN string and read it
back as the value; the comment at `:165-168` says so. Thirteen attributes
hand-do that job, and by hand the reader gets a string, no Malli shape is
checked, and the encoding is carried in the **attribute name**.

| # | Attribute | file:line | Edit | L |
|---|---|---|---|---|
| N19 | `:seon.effect/result-edn`, `/request-edn` | `seon.effect.edn:136`, `:135` | Declare `:seon.effect/result` / `/request` with the real union; let the bridge encode. Name loses `-edn`. | 5 |
| N20 | `:seon.cluster.eval/triage-edn` | `seon.cluster.eval.edn:1` | Same. Its reader already branches on presence (`src/seon/repl.clj:181`). | 5 |
| N21 | `:seon.error/data-edn` | `seon.error.edn:70` | Required beside optional `/data-blob` + `/data-size`: this one is the ruled three-tier projection (datom = projection, blob = full content). **Keep the string, rename without `-edn`** and declare in the docstring that it is a bounded projection, not the value. | 5 |
| N22 | `:seon.fn.argument/label-edn`, `:seon.fn.binding.entry/default-edn`, `:seon.fn.ast.entry/value-edn`, `:seon.schema.shape.child/value-edn`, `:seon.schema.map-entry/key-edn` | `seon.fn.argument.edn:8`, `seon.fn.binding.entry.edn:6`, `seon.fn.ast.entry.edn:25`, `seon.schema.shape.child.edn:4`, `seon.schema.map-entry.edn:6` | Each sits **beside** a full set of typed siblings (`label-keyword`/`-string`/`-symbol`; `key-keyword`/`-string`/`-symbol`/`-boolean`/`-int`/`-double`/`-uuid`/`-inst`). The `-edn` string is the fallback arm **and** a duplicate of whichever typed arm is present. Edit: one union attribute through the bridge codec; the typed siblings become the query index, not the storage. | 5, 3 |
| N23 | `:seon.ai.attempt/usage-edn`, `/settings-edn`, `:seon.test.accretion/report-edn`, `:seon.ai/extra-body-edn` | `seon.ai.attempt.edn:9`, `:13`, `seon.test.accretion.edn:68`, `seon.ai.edn:107` | All `:seon.db/no-history? true`. Genuinely opaque provider payloads: **keep as strings**, drop `-edn` from the name, and say in the docstring that they are provider bytes, not Seon values. `usage-edn` is the exception — `:seon.ai.usage/*` (`seon.ai.usage.edn`) already declares the typed fields, so it is a second copy of them. | 5, 3 |

### A.4 Kind stamps and discriminators

| # | Attribute | file:line | Verdict | L |
|---|---|---|---|---|
| N24 | `:seon.cluster.eval/author [:enum :agent :system]` | `seon.cluster.eval.edn:8-13` | **Dissolve.** The data-modeling skill rules it out by name: "'System' derives from reply presence and no provider attempt. Do not add an author-kind stamp." Its own docstring admits it replaced an earlier stored label. `src/seon/repl.clj:476` already derives the same answer from `:seon.eval/renderer` presence. Two mechanisms, one question, one of them prohibited. | 4, 2 |
| N25 | `:seon.eval/outcome [:enum :ok :time :error]` | `seon.eval.edn:40` | **Dissolve.** It selects nothing behavioural that attribute presence does not already select: `/error` (`seon.cluster.eval.edn:3`), `/interrupted-at` (`:20`), `/shown` (`seon.eval.edn:43`). A third authority for the same terminal state. | 4, 3 |
| N26 | `:seon.issue/status [:enum :open :resolved :superseded]` | `seon.issue.edn:3`, stored at `:49` | **Dissolve the `:open`/`:resolved` arms.** `:seon.issue/resolved-tx`'s docstring (`:38`) says "absence means open" — so the required enum is a stored mirror of a positive fact on the **same entity**. Derive-or-die names this a defect on sight. `:superseded` is a genuine third state with no fact: give it `:seon.issue/superseded-tx` (or a ref to the superseding issue, which is more useful) and delete the enum. | 4, 3, 6 |
| N27 | `:seon.fn.binding/shape [:enum :symbol :map :sequential]` | `seon.fn.binding.edn:2` | **Dissolve.** It is a table-picker: `:symbol` ⇒ `/symbol` present, `:map` ⇒ `/entries`, `:sequential` ⇒ `/children` (`:4`, `:8`, `:5`). Attribute presence already answers it. Compare `:seon.fn.binding.child/role [:enum :element :rest :as]` (`seon.fn.binding.child.edn:2`) and `:seon.fn.binding.entry/spelling [:enum :explicit :keys :strs :syms]` (`seon.fn.binding.entry.edn:3`): those two are **genuine bounded destructuring facts from the reader's grammar — keep them**, with a docstring. | 4 |
| N28 | `:seon.ai.model/deepseek-pricing-schedule-status [:enum :announced :active]` | `seon.ai.model.edn:33` | Dissolve into a positive fact (`/pricing-window-active-tx`), which also answers "since when", which the enum cannot. | 4, 2 |
| — | `:seon.fn.ast/type`, `:seon.schema.shape/type`, `:seon.test.failure/type`, `:seon.lint/type`, `:seon.error/kind`, `:seon.fn/workload`, `:seon.fn/external-sink`, `:seon.turn/disposition`, `:seon.schema.admission/source` | — | **Keep, all of them** — dependency facts or genuinely closed states, and the audit already ruled this (`schema-key-audit-2026-09-16.md:1533`). Two corrections to that row are in §A.5 and §B.4. | 4 |

### A.5 Ordering silently lost, and encoding inconsistency

The bridge maps **any** `[:vector …]`, `[:set …]` or `[:sequential …]` head to
`:db.cardinality/many` (`src/seon/schema/datahike.clj:195-202`), and Datahike
stores cardinality-many as a **set**. Parsing every resource, **34 storable
attributes are declared `[:vector …]`.** Most are honestly unordered. These are
the ones where order is load-bearing:

| # | Attribute | file:line | Order carrier today | L |
|---|---|---|---|---|
| N29 | `:seon.fn.ast/children`, `/keys`, `/values`, `/registry`, `/properties` | `seon.fn.ast.edn:2`, `:8`, `:41`, `:38`, `:35` | **None.** The AST node has no ordinal. `[:tuple :int :string]`, `[:cat …]` and `[:=> [:cat a b] out]` are order-sensitive Malli forms; their stored decomposition cannot be read back in order. `:seon.fn.ast.entry/order` (`seon.fn.ast.entry.edn:2`) exists only on map entries and is **optional** (`:10`). | 7 |
| N30 | `:seon.fn.arity/arguments` | `seon.fn.arity.edn:6` | `:seon.fn.argument/order` **and** `:seon.fn.argument/index`, both required, both `:int` (`seon.fn.argument.edn:1-2`). Two ordinals on one row with no declared difference — see N34. | 7, 3 |
| N31 | `:seon.test.failure/contexts` | `seon.test.failure.edn:6` | `[:vector [:tuple [:int {:min 0}] :string]]` — the ordinal is **inside the tuple**, which is the correct construction. Named here as the working precedent the AST family should copy. | 7 |
| N32 | `:seon.test/failing-assertions` | `seon.test.edn:37` | None, and it duplicates `:seon.test/failures` (`:39`, component refs to the same failures). Two representations of one ordered list, neither ordered. See §B.3. | 7, 3 |
| N33 | `:seon.effect/content-blobs` | `seon.effect.edn:37` | None. If the blobs are a byte sequence, order is the whole meaning; if they are a set of attachments, the declaration should be `[:set …]` and say so. Judgment — see §C.6. | 7 |

`:seon.schema.shape` is the **same job done right**:
`:seon.schema.shape.child/order` and `:seon.schema.shape.entry/order` are
required `:int` (`seon.schema.shape.child.edn:2`, `seon.schema.shape.entry.edn:2`),
each child carries an identity (`:1` in both), and `:seon.schema.shape/type` is
`:keyword` (`seon.schema.shape.edn:6`) where `:seon.fn.ast/type` is `:string`
(`seon.fn.ast.edn:40`). See §B.4.

### A.6 One format, four declarations

| # | Finding | Evidence | Edit | L |
|---|---|---|---|---|
| N34 | Two ordinals on one row | `:seon.fn.argument/order` and `/index`, `seon.fn.argument.edn:1-2`, both required at `:19-21` | Keep one. If they genuinely differ (position in the arglist vs position among schema-bearing arguments), the docstring must say so; today neither has one. | 3 |
| N35 | Four spellings of a 64-hex SHA-256 | `:seon.source/digest [:re {:seon.db/identity true} "^[0-9a-f]{64}$"]` (`seon.source.edn:11`); `:seon.blob/digest [:re "^[0-9a-f]{64}$"]` (`seon.blob.edn:2`); `:seon.fn.file/digest [:string {:min 64 :max 64}]` (`seon.fn.file.edn:4` — **does not constrain hex**); `:seon.test/failure-identity [:string {:min 64 :max 64}]` (`seon.test.edn:34`) | One `:seon.source/digest` format. `:seon.fn.file/digest` accepting any 64 characters is a real hole at the analysis-provenance seam G4 is about to make required. | 3, 8 |
| N36 | The digest format carries identity | `:seon.source/digest` declares `:seon.db/identity true` **on the format**, forcing three `{:seon.db/identity false}` negations: `seon.test.run.edn:4`, `seon.test.edn:5`, and (for the error signature) `seon.test.failure.edn:16`. The bridge honours the negation (`src/seon/schema/datahike.clj:271` reads `(:seon.db/identity props)`, and `false` is falsy) — so it works, but a format alias that every reuse must switch off is inverted. | Split: `:seon.source/digest` = the format, identity declared at the identified use (`:seon.activation/…`, `seon.activation.edn:2`, already does exactly that). The three negations then vanish. | 3 |
| N37 | Two declarations of one enum | `:seon.fn/workload [:enum :io :compute]` (`seon.fn.edn:180`) and `:seon.flow/workload [:enum :compute :io]` (`seon.flow.edn:193`) — core.async's own tags, declared twice in different member order. | One declaration; the other aliases it, as `:seon.turn/disposition` already aliases `:my.turn/disposition` (`seon.turn.edn:39-41`). | 3 |

### A.7 Provenance and time

| # | Finding | Evidence | Verdict | L |
|---|---|---|---|---|
| N38 | Two clocks on one lifecycle, one entity | `:seon.issue/opened :inst` (`seon.issue.edn:5`) vs `:seon.issue/resolved-tx` ref (`:38`) and `/budget-exhausted-tx` (`:37`) | The issue's open event is an instant, its close events are transaction refs. A transaction ref yields both the instant (`:db/txInstant`) and the basis `:t`; an instant yields neither. **Make it `:seon.issue/opened-tx`.** | 6 |
| N39 | Two clocks across families | turn/plan/issue use `-tx` refs (`seon.turn.edn:157-158`, `my.plan.item.edn:79`); effect and maintenance use `-at` instants (`seon.effect.edn:52`, `:63`, `:161`; `seon.maintenance.receipt.edn:12`, `:14`, `:20`) | One family-wide choice. The transaction ref is strictly more informative for an event **recorded in the transaction that caused it**. Judgment where the event and its recording genuinely differ — §C.6. | 6 |
| N40 | Three provenance facts on the test row for one run | `:seon.test/run` ref (`seon.test.edn:33`), `/run-at :inst` (`:32`), `/run-basis-t` (`:31`) — and the run entity itself carries `at` and `basis-t` (`seon.test.run.edn:1`, `:5`) | `run-at` and `run-basis-t` are copies of the run's own facts onto the domain entity, which AGENTS §3 forbids ("never copied onto domain entities"). Extends the audit's "require result group conditional on `/run`": **delete both copies**, join the run. | 6, 3 |
| N41 | Materialized aggregate on a failure | `:seon.test.failure/first-run`, `/last-run`, `/seen-count`, `/last-seen-at` (`seon.test.failure.edn:18-21`) | Four attributes for "when has this been seen". `first-run`/`last-run` are refs to deletable runs (G1 sweep); `seen-count` and `last-seen-at` are derivable from the run refs plus history. Judgment — §C.6. | 6, 3 |
| N42 | A mutable observation triple with history off | `:seon.ai.model/last-tokens-per-second`, `/last-latency-ms`, `/last-used-at`, all `:seon.db/no-history? true` (`seon.ai.model.edn:18-23`) | The latest observation overwrites, and `no-history?` makes the previous one unrecoverable, so "never used" and "used once, long ago" converge toward the same absence. Judgment — §C.6. | 2, 6 |

### A.8 Boundary contracts — the audit's §8 conclusion confirmed, with one gap

I re-parsed every resource. There is **no `{:closed true}`**, and every `:any`
and `:maybe` in the population carries
`:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary`
with a written reason — `seon.error.edn:79`, `seon.schema.edn:1`, `:11`,
`seon.sci.admit.edn:68`, `seon.sci.eval.edn:7`, `:75`, `:142`, `:171`,
`seon.print.edn:270`, `:276`, `seon.turn.loop.edn:106`, `seon.render.data.edn:7`,
`:16`, `:19`, `seon.render.value.edn:20`, `:26`, `seon.render.edn:223`, `:230`.
The stored path additionally refuses `:maybe` at the bridge
(`src/seon/schema/datahike.clj:169-175`). The audit's "no direct `[:maybe]`
entry" holds for nested forms too.

| # | Gap | Evidence | Edit | L |
|---|---|---|---|---|
| N43 | An enum with `nil` as a member | `:seon.cluster.wake/offer-result [:enum true false nil]` (`seon.cluster.wake.edn:10`) | Not storable, so the bridge never sees it — but it is a three-valued flag with an explicit nil arm at a boundary, which is the in-memory spelling of "absent means something". Name the three outcomes (`:delivered` / `:refused` / `:no-channel`), as the sibling `:seon.cluster.wake/delivery` (`:3-7`) already does correctly in the same file. | 8, 2 |
| N44 | An unconstrained stored map | `:seon.maintenance.result/value :map` (`seon.maintenance.result.edn:236`) and `:seon.maintenance.result/entity` = one entry, the id (`:232-235`) | See §C.3. | 8 |

---

## B. Duplication clusters — verdict each

| Cluster | Verdict | One line |
|---|---|---|
| B.1 `:seon.eval/entity` (`seon.eval.edn:6`) vs `:seon.cluster.eval/receipt` (`seon.cluster.eval.edn:30`) | **Merge** — already ruled | One canonical stored declaration; the reader form derives under its selector. Audit row; nothing to add except that `:seon.eval/renderer-fn` (N6) and `:seon.eval/outcome` (N25) should be **deleted**, not merged. |
| B.2 `:seon.message/pulled` (`seon.message.edn:94`), `:seon.test.failure/value` (`seon.test.failure.edn:58`), `:seon.render.transcript/pulled-transaction`, `:seon.wake/unanswered` | **Delete all four** — already ruled | Derive the pulled form (pulled-shape study option B). **New**: `:seon.test.failure/report` (`seon.test.failure.edn:22-32`) is a **fifth** hand mirror — nine of the failure entity's own keys, all optional — that the pulled-shape inventory does not list. It is `malli.util/select-keys` + `optional-keys` of `/failure`. |
| B.3 `:seon.test/result` (`seon.test.edn:55`) vs `:seon.test/test` (`:77`) | **Merge is wrong; derive** | `/result` is the reader projection of the result attributes already on `/test`; it is a `select-keys` of the entity plus `[:vector :seon.test.failure/value]`. **New**: `:seon.test/failing-assertions` (`:37`) and `:seon.test/failures` (`:39`) are two representations of one ordered failure list on the same entity — keep the component refs, **delete `failing-assertions`**, and put the ordinal in the failure (it already has `:seon.test.failure/ordinal`, `seon.test.failure.edn:4`). |
| B.4 `seon.fn.ast*` vs `seon.schema.shape*` | **Delete `seon.fn.ast*`; keep `seon.schema.shape*`** | Two entity encodings of one Malli form. `schema.shape` has a fingerprint identity, required ordinals on children and entries, a `:keyword` type and a stored canonical form; `fn.ast` has a `:string` type, no ordinals (N29), no identities, and — decisively — **no production reader**: `grep -rn 'seon\.fn\.ast' src/` outside `src/seon/program.cljc` returns nothing, and `:seon.fn/ast` is read in `src/` only by its own reconciliation comparison (`src/seon/fn.clj:2276-2313`, `src/seon/turn.clj:1192`). `seon.schema.shape` is read by `src/seon/schema.clj`, `src/seon/call_preparation.clj`, `src/seon/fn.clj` and `src/seon/issue/detect.clj`. Deleting the AST forest removes 4 resources, ~31 declarations and a recursive component tree written for every function. |
| B.5 `seon.fn.binding*` vs `seon.fn.argument*` | **Keep both — parent/component, correctly declared** | `:seon.fn.argument/binding` is a `{:seon.db/component true}` ref (`seon.fn.argument.edn:4`): an argument *has* a binding tree. Not duplication. The defects inside them are N27, N30, N34, N22. |
| B.6 `seon.error.edn` vs `seon.error.occurrence.edn` | **Keep — signature vs sighting** | `:seon.error/error` is the deduplicated signature, `/occurrence` is one sighting. Genuinely distinct. **New defect inside it**: the occurrence map requires **both** `:seon.error.occurrence/process` and `:seon.error/process` (`seon.error.occurrence.edn:24`, `:25`) — two attributes for one fact on one entity — and carries `:seon.error/fn` (ref) beside `:seon.instrument/fn` (string) (N13). |
| B.7 `seon.context.capture` vs `seon.context.contribution` vs `seon.cluster.eval` | **Capture/contribution: keep, parent/component. Evaluation: distinct.** | `:seon.context.capture/contributions` is already `[:set {:component true}]` (`seon.context.capture.edn:31`). The honest concern is elsewhere: capture stores the **assembled prompt string** (`seon.context.capture.edn:34`, with `:seon.db/no-history? true`) and per-block hashes and token counts — a materialized copy of a derivation the design insists is `fn(db)` at a basis `:t`. Judgment — §C.6. |
| B.8 `seon.ai.model` three entity maps | **Not a `:type` stamp; the vendor prefixes are the defect** | `provider-entity` (`seon.ai.model.edn:41`) is a genuine entity (endpoint, key variable) the model refs; `deepseek-window-entity` (`:54`) is a genuine pricing-window entity. What is wrong is that **eleven attributes carry a vendor name**: `:seon.ai.model/deepseek-off-peak-windows`, `/deepseek-window-id`, `/deepseek-utc-start`, `/deepseek-utc-end`, `/deepseek-regular-price-factor`, `/deepseek-peak-price-factor`, `/deepseek-pricing-schedule-status`, `/meta-search-usd-per-kquery`, `/meta-free-requests-per-minute`, `/meta-free-tokens-per-minute`, `/meta-paid-requests-per-minute`, `/meta-paid-tokens-per-minute` (`:24-38`). A provider name in an attribute key is a table-picker by naming convention: adding a third provider means a third prefix rather than a row. See §C.5. |
| B.9 `seon.maintenance.result`'s thirteen maps | **Keep the component trees; fix the root** | The twelve census/reap/collect/cleanup component maps are genuinely different observations of different operations — not duplication. The root `:seon.maintenance.result/entity` (`:232-235`) declares **one entry, the id**, so the whole-entity validator selects it and validates nothing, and `:seon.maintenance.result/value :map` (`:236`) is an unconstrained escape. See §C.3. |
| B.10 `:seon.test.run/provenance` (`seon.test.run.edn:24`) vs `/run` (`:36`) | **New cluster — derive one from the other** | Six keys each, same attributes, differing only in optionality (`provenance` requires what `run` leaves optional) plus `tested-branch`. After the audit's edit requiring `basis-t`, `branch` and `program-digest` on `/run` (`schema-key-audit-2026-09-16.md:1598`), the two become literally the same map. `malli.util/required-keys` derives one from the other. |
| B.11 Four families named **"receipt"** | **Rename all four at the reset** | `:seon.cluster.eval/receipt` (`seon.cluster.eval.edn:30`), `:seon.effect/receipt` (`seon.effect.edn:67`), `:seon.maintenance.receipt/*` (whole resource), `:my.background/receipt` (`my.background.edn:2`). Owner-decisions Part 3 retires "receipt" in favour of **evaluation** / **result**, and lists `config/default.edn` and tests — it does **not** list `resources/seon/schemas/`, where the word is a declared attribute family and therefore reaches every agent's rendered context. |
| B.12 Three render-pair keys, two of them `:or`-typed | **One meaning per key** | The symbols inventory (§1.1 item 13) found `:seon.render/ai [:or :qualified-symbol :string]` (`seon.render.edn:19`) means both "the renderer" and "the rendered text". **New**: `:seon.render/html [:or :qualified-symbol :seon.render/hiccup]` (`:185`) has the identical collision, and `:seon.render/form` — which AGENTS §3's vocabulary table lists as a **legacy spelling** — is a live third member of the pair, declared at `:176`, admitted by `:seon.render/output [:enum :seon.render/ai :seon.render/html :seon.render/form]` (`:192`), and authored as a property on ten entity schemas (`my.turn.edn:14`, `:21`; `my.note.edn:4`, `:13`, `:22`; `seon.agent.edn:125`; `seon.error.edn:219`; `seon.message.edn:72`, `:151`; `seon.ns.edn:15`). Either the vocabulary table is stale or ten schemas are. |

---

## C. Bad designs — whole families shaped wrong

### C.1 The ref inventory has no deletion contract, and G1 just removed the one it had

**Target shape.** Every `:seon.db/ref` in the population is classified into
exactly one of three kinds, declared as a property on the attribute:

1. **containment** — `:seon.db/component true`; the child dies with the parent,
   which is what Datahike already does
   (`transaction.cljc:831-836`). Nothing to decide.
2. **relation to a living entity** — the referrer's fact is *about* the target
   and is meaningless without it (`:seon.message/to`, `:seon.issue/agent`,
   `:seon.fn/ns`). Retracting the target **must refuse** unless the referrer is
   retracted in the same transaction; the decision belongs inside
   `[:db.fn/call …]`, which sees the mid-transaction database
   (`transaction.cljc:1153-1154`), never in a pre-read.
3. **mention of a name** — the referrer observed a token; the target's
   existence is a separate, derivable question. These **stop being refs**
   (N1–N11).

**What it dissolves.** The `:seon.fn/reference-to` property
(`seon.fn.edn:4`), which exists only to tell a reader how to get the name back
out of a ref; `:seon.issue/unresolved` (`seon.issue.edn:27`) and
`:seon.fn/pending-calls` and `:seon.test/pending-subject`, which are the
resolution-failure half of kind 3; and the open question of what
`retract-entity`'s incoming sweep does to a surviving entity's **required**
keys (N9 today has no answer).

**Why it is urgent.** G1 is ruled and lands at this reset. Until kind 2 refuses
and kind 3 is retyped, every `bin/seon` deletion path is a silent-data-loss
path, and the loss is invisible by construction — the project's named failure
class, in the mechanism the project just adopted.

### C.2 `seon.fn.ast` — a recursive component forest nobody reads

**Target shape.** Delete `seon.fn.ast.edn`, `seon.fn.ast.entry.edn` and
`:seon.fn/ast` (`seon.fn.edn:22`, `:141`). Point the contract-shape question at
`seon.schema.shape*`, which already answers it with ordinals, identities and a
`:keyword` type, and which four production namespaces already read.

**What it dissolves.** Two resources, ~31 declarations, one recursive
`retract-entity` cascade per function, the `:seon.fn.ast/type` string retype the
audit scheduled (`schema-key-audit-2026-09-16.md:1566`), the missing-ordinal
defect (N29), and the `nil?`-means-never-analyzed read at
`src/seon/fn.clj:2283` (N16). It is the only edit in this review that makes the
database strictly smaller.

**Falsifiable before acting.** The claim "no production reader" is a `grep` over
`src/`; run it at the reset's HEAD before deleting. If a reader exists, the
verdict flips to "merge `fn.ast` into `schema.shape`", not "keep both".

### C.3 `seon.maintenance.result` — a root entity that validates nothing

**Evidence.** `:seon.maintenance.result/entity` is
`[:map {:seon.db/attributes true} [:seon.maintenance.result/id …]]`
(`seon.maintenance.result.edn:232-235`). Twelve sibling component maps hold the
actual census/reap/collect/cleanup observations, and **no declaration links them
to the root** — no component attribute on `/entity` names any of them. The
whole-entity write validator selects the root by its identity
(`src/seon/db.clj:2683`), validates one key, and reports success. Beside it,
`:seon.maintenance.result/value :map` (`:236`) admits anything.

**Target shape.** The root declares its component attributes (one per operation
kind, optional, mutually exclusive by presence — not by an added enum), and G5's
parent-with-components-expanded validation then covers the whole tree in one
pass. `/value` gets the real union or goes.

**What it dissolves.** The largest block of the audit's 29 no-required-identity
maps (thirteen of them are this family, `edges-are-symbols-plan-2026-09-16.md:296`),
without inventing a single identity attribute — exactly what §8.1 asks for.

### C.4 The `key-*` / `label-*` typed-sibling pattern, declared three times

`:seon.schema.map-entry/*` supplies eight typed key attributes plus `key-kind`,
`key-edn` and `key-fingerprint`; they are pasted into
`:seon.schema.shape.entry/row` (`seon.schema.shape.entry.edn:7-53`) and
`:seon.fn.binding.entry/row` (`seon.fn.binding.entry.edn:8-60`) — eleven
entries each, identical. `:seon.fn.argument/*` repeats a three-member version
(`label-keyword`/`-string`/`-symbol`/`-edn`, `seon.fn.argument.edn:8-11`).

**Target shape.** One declared "literal value" concern: one union attribute
stored through the bridge's EDN codec, plus `key-fingerprint` for the join.
`key-kind` derives from the stored value (the audit already says so,
`schema-key-audit-2026-09-16.md:1533`); the eight typed siblings become a
derived index if a query needs one, not eleven map entries repeated in three
schemas.

**What it dissolves.** Twenty-two map entries, three copies of one taxonomy, and
N22's `-edn` duplication in the same edit.

### C.5 Vendor names as attribute keys in `seon.ai.model`

Twelve attributes prefixed `deepseek-` or `meta-` (`seon.ai.model.edn:24-38`).
**Target shape**: generic names on the pricing-window and rate-limit entities
(`:seon.ai.model/off-peak-windows`, `/window-utc-start`, `/regular-price-factor`,
`/free-requests-per-minute`, …), owned by the **provider descriptor row**
(AGENTS §3's declared term) rather than by the model. Adding a provider then
adds rows, not keys. **What it dissolves**: the naming convention doing a
table's job, and the coupling that makes `:seon.ai.model/entity` grow by five
keys per new provider.

### C.6 Judgment calls, both options in one line each

| # | Question | Option A | Option B | Marked |
|---|---|---|---|---|
| J1 | `:seon.cluster.eval/author` (N24) | Delete; derive from the turn's reply-and-no-attempt shape, as the skill rules. | Keep; per-**form** authorship inside a mixed turn is not per-turn authorship. | **A** — no writer today mixes them, and the skill names the stamp by name. |
| J2 | `:seon.fn/capability-fn` / `:seon.effect/capability-fn` (N10) | Delete the ref; join on `:seon.effect/capability` symbol. | Keep; the render path saves one hop. | **A** — one hop is not a reason to keep a fact that a deletion silently erases. |
| J3 | `-at` instants vs `-tx` refs (N39) | One family rule: transaction ref everywhere the event is recorded in its own transaction. | Keep instants where the event genuinely predates its recording (`:seon.ai.attempt/at`, `:seon.error.occurrence/first-at`). | **A for effect and maintenance, B for provider and occurrence** — the split is the rule, and it must be written in the docstrings. |
| J4 | `:seon.test.failure/seen-count` / `last-seen-at` (N41) | Delete; derive from the run refs and history. | Keep as a bounded materialized aggregate the failure page reads without a scan. | **A** — the refs are deletable under G1, so the aggregate will silently drift anyway. |
| J5 | `:seon.ai.model/last-*` with `no-history?` (N42) | Make each observation an entity; the model's "latest" is a query. | Keep the overwrite; it is a live dial, not a record. | **B** — but the docstring must say "latest observation, unrecoverable", so no reader mistakes absence for never-used. |
| J6 | `:seon.context.capture/prompt` (B.7) | Delete; the prompt is `fn(db)` at the capture's `basis-t`. | Keep; it is calibration evidence against the provider's own token count, and `:seon.ai.tokens/characters` exists for exactly that (`seon.context.capture.edn:22`). | **B** — but `:seon.db/no-history? true` on it (`:34`) means the evidence cannot be read temporally; drop `no-history?` or state why. |
| J7 | `:seon.effect/content-blobs` order (N33) | Declare `[:vector …]` with a per-blob ordinal if the blobs are a byte sequence. | Declare `[:set …]` if they are attachments. | **Unresolved — read the writer** (`src/seon/effect.clj`) before the reset; today the declaration claims an order the storage does not keep. |

---

## D. Ordered edit list, grouped by resource

**Accretion** = adds an optional key, a docstring or an index; safe without a
reset. **Reset** = changes a type, a required key, or deletes an attribute.
Every reset row belongs in the ONE reset with symbols-everywhere, S2 and G1–G6
(G6). Rows are ordered so that a deletion never precedes the edit that makes it
safe.

### D.1 Reset — the G2 extension (do with `calls`/`reach`, same publication)

| Resource | Edit | Finding |
|---|---|---|
| `seon.fn.edn:42` | `:references` → `[:set {:seon.db/index true} :qualified-symbol]` | N1 |
| `seon.fn.edn:44` | `:writes` → `[:set {:seon.db/index true} :qualified-keyword]` | N2 |
| `seon.fn.edn:4` | `:reference-to` — keep only for surviving kind-2 refs; delete its uses on retyped attributes | C.1 |
| `seon.schema.edn:50` | `:references` → `[:set :qualified-keyword]` | N3 |
| `seon.fn.arity.edn:3`, `:5`, `:15` | `guard-refs` / `input-refs` / `output-refs` → `[:set :qualified-keyword]` | N4 |
| `seon.issue.edn:8`, `:11`, `:14`, `:16`, `:18`, `:22`, `:24` | cited sets store the token at its declared type; **delete `:seon.issue/unresolved` (`:27`)** | N5 |
| `seon.test.edn:10-11` | one `:subject :qualified-symbol`; **delete `pending-subject`** | N7 |
| `seon.schedule.task.edn:3` | `:function` → `:qualified-symbol`; fire-time resolution returns a typed unknown | N9 |
| `seon.fn.edn:5-11`, `seon.effect.edn:2-6` | **delete** both `capability-fn` refs; join `:seon.effect/capability` | N10, J2 |
| `seon.listen.edn:2` | `:entity` stores an identity value; absence must refuse, not match everything | N8 |
| `seon.error.edn:271` | **delete `:seon.error/fn`**; `:seon.instrument/fn` becomes `:qualified-symbol` (symbols inventory §1.1 item 8) | N13 |
| `seon.test.failure.edn:13` | **delete `/file` ref**; keep `/reported-file` | N11 |

### D.2 Reset — deletions of duplicate mechanisms

| Resource | Edit | Finding |
|---|---|---|
| `seon.eval.edn:3` | **delete `:seon.eval/renderer-fn`** | N6 |
| `seon.eval.edn:40` | **delete `:seon.eval/outcome`** | N25 |
| `seon.cluster.eval.edn:8-13` | **delete `:seon.cluster.eval/author`** | N24, J1 |
| `seon.issue.edn:3` | **delete `:seon.issue/status`**; add `:seon.issue/superseded-by` ref | N26 |
| `seon.fn.binding.edn:2` | **delete `:seon.fn.binding/shape`** | N27 |
| `seon.test.edn:37` | **delete `:seon.test/failing-assertions`** | B.3 |
| `seon.test.edn:4` | **delete `:seon.test/reach-unknown`** once the required provenance digest lands | N15 |
| `seon.test.edn:31-32` | **delete `/run-at` and `/run-basis-t`**; join `/run` | N40 |
| `seon.test.failure.edn:22-32` | **delete `:seon.test.failure/report`**; derive from `/failure` | B.2 |
| `seon.test.failure.edn:18-21` | **delete `/seen-count` and `/last-seen-at`** | N41, J4 |
| `seon.test.run.edn:24` | **delete `/provenance`**; derive from `/run` | B.10 |
| `seon.fn.ast.edn`, `seon.fn.ast.entry.edn`, `seon.fn.edn:22`, `:141` | **delete the whole AST family** after re-confirming no `src/` reader | C.2, B.4 |
| `seon.error.occurrence.edn:24-25` | **delete one of the two process attributes** | B.6 |
| `seon.fn.argument.edn:1-2` | **delete one of `order` / `index`** | N34 |
| `seon.flow.edn:193` | alias `:seon.fn/workload`; delete the second enum | N37 |

### D.3 Reset — retypes and required keys

| Resource | Edit | Finding |
|---|---|---|
| `seon.fn.file.edn:4` | `:digest` → `:seon.source/digest` format (hex-constrained) | N35 |
| `seon.test.edn:34` | `:failure-identity` → the one digest format | N35 |
| `seon.source.edn:11` | remove `:seon.db/identity true` from the **format**; declare identity at the identified uses; delete the three `{:seon.db/identity false}` negations (`seon.test.run.edn:4`, `seon.test.edn:5`, `seon.test.failure.edn:16`) | N36 |
| `seon.effect.edn:135-136`, `seon.cluster.eval.edn:1` | declare the real union; the bridge codec stores it; drop `-edn` from the name | N19, N20 |
| `seon.fn.argument.edn:8`, `seon.fn.binding.entry.edn:6`, `seon.fn.ast.entry.edn:25`, `seon.schema.shape.child.edn:4`, `seon.schema.map-entry.edn:6` | one union attribute per literal-value concern; typed siblings become a derived index | N22, C.4 |
| `seon.ai.attempt.edn:9` | **delete `/usage-edn`**; `:seon.ai.usage/*` already declares the fields | N23 |
| `seon.context.contribution.edn:8` | require `:seon.render.block/name` | N17 |
| `seon.cluster.eval.edn` | add the positive read-observation fact required by the since-diff | N14 |
| `seon.issue.edn:5` | `:opened` → `:opened-tx` ref | N38 |
| `seon.effect.edn:52`, `:63`, `:161`; `seon.maintenance.receipt.edn:12`, `:14`, `:20` | `-at` instants → transaction refs | N39, J3 |
| `seon.ai.model.edn:24-38` | rename the twelve vendor-prefixed attributes; move windows/limits onto the provider descriptor row | C.5, B.8 |
| `seon.ai.model.edn:33` | `/deepseek-pricing-schedule-status` → a positive activation fact | N28 |
| `seon.maintenance.result.edn:232-236` | root declares its component attributes; `/value` gets the real union | C.3, N44 |
| `seon.render.edn:19`, `:185` | close both `:or`s to `:qualified-symbol`; move the rendered-text meaning to `:seon.render/rendered` (`:194`) | B.12 |
| `seon.cluster.eval.edn:30`, `seon.effect.edn:67`, `seon.maintenance.receipt.edn`, `my.background.edn:2` | rename the four "receipt" families to **evaluation** / **result** | B.11 |

### D.4 Accretion — no reset needed

| Resource | Edit | Finding |
|---|---|---|
| every kind-2 ref in the population | add the deletion-consequence sentence to its docstring; add the kind property C.1 declares | C.1 |
| `seon.eval.edn:1` | name the admitted `:seon.eval/origin` target families and the retraction consequence in the docstring | N12 |
| `seon.ai.model.edn:18-23` | docstring: "latest observation, unrecoverable; absence is not never-used" | N42, J5 |
| `seon.fn.binding.child.edn:2`, `seon.fn.binding.entry.edn:3` | docstring naming these as the reader's destructuring grammar, so a later sweep does not delete them with N27 | N27 |
| `seon.cluster.wake.edn:10` | name the three outcomes; delete the `nil` member | N43 |
| `seon.render.cost.edn:6-15` | add an identity and a ref to what was rendered | A.2 table |
| `seon.agent.edn:2`, `seon.cluster.edn:2`, `seon.ns.edn:33` | declare the G1 consequence for the agent, the cluster and stewardship | A.2 table |
| `seon.test.failure.edn:6` | record as the working ordinal precedent for N29 | N31 |
| `seon.effect.edn:37` | resolve J7 from the writer, then fix the declaration to match | N33, J7 |
| `AGENTS.md` §3 vocabulary table | reconcile `:seon.render/form`: either it is current (ten schemas declare it) or the ten schemas are drift | B.12 |

### D.5 Order

1. **D.1 first** — the G2 extension travels with `calls`/`reach` in the same
   publication; otherwise half the name-edges retype and half stay refs, and the
   G1 deletion path lands on the mixed population.
2. **D.4's kind-property accretion second**, because D.2's deletions are only
   safe once each surviving ref's kind is declared.
3. **D.2 and D.3**, in the table order.
4. **C.2's AST deletion last**, after its `grep` is re-confirmed at HEAD.

---

## Verification boundary

Source read in the `steward-platform` working tree at the session's HEAD, with
other lanes' uncommitted edits present in `resources/seon/schemas/seon.config.edn`,
`seon.test.edn`, `src/seon/db.clj`, `src/seon/program.cljc`, `src/seon/turn.clj`
and others (`git status` at entry). Line numbers are the bytes I opened; a lane
landing between now and the reset must re-anchor them rather than trust them.

The inventory numbers (74 marked maps, 34 storable `[:vector …]` attributes,
207 `:seon.db/ref` mentions, 13 `*-edn` attributes) come from an EDN parse of
every resource plus `grep` counts, not from text matching alone. The
"no production reader" claim for `seon.fn.ast` is a `grep` over `src/` and is
marked falsifiable in C.2.

No JVM was started, no gate was run, no cluster was operated, and the one
permitted read-only evaluation was not spent. Every recommendation about a
writer's behaviour is a source reading, not a measurement; the implementation
lanes must prove them on the armed harness after the reset.
