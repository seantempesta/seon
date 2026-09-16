---
type: research
status: active
created: 2026-09-16
tags: [datahike, program-graph, schema, deletion, refs, tests]
---

# Deletion semantics for the program graph and the test families

Date 2026-09-16. Branch `steward-platform`, HEAD `6a2201f29`. Read-only. No
production edit, no test JVM, no `bin/seon` state change. Two read-only
`mcp__seon__eval_clj` calls against cluster `default` (mode `jvm`, custody
`(seon.operator/connection "default")`), both pure queries; nothing was
transacted.

The owner's mandate, verbatim: *"Doesn't it depend? Sometimes you want the
associated entity to be retracted and sometimes you don't. What am I ruling on?
What are the specific issues we aren't sure about. … WE OWN THE SCHEMAS. WE ARE
ADAPTABLE. DO NOT LOCK US INTO BAD PRIOR DECISIONS."*

**The answer to "doesn't it depend?" is yes, and it depends on exactly one
thing, which the writer already knows and the schema does not record: whether
the referrer's fact is a STATEMENT ABOUT A LIVING ENTITY or an OBSERVATION OF A
TOKEN.** For an observation, the target's existence is a separate derivable
question, and the fact must survive the target — that is behavior 4 (value) and
it needs no policy, no flag and no enforcement machinery. For a statement about
a living entity, the referrer is either owned by the target (behavior 1,
component) or is a peer whose fact becomes false (behaviors 2/3/5). Only that
last, small set is a genuine policy choice, and §4 prices it. Everything else
in the program graph and the test families is decided by what the writer
observed, not by a ruling.

Authorities read end to end before writing: AGENTS.md §2 and §3;
[the datahike skill](../../../../.claude/skills/datahike/SKILL.md);
[the data-modeling skill](../../../../.claude/skills/data-modeling/SKILL.md);
`reference-code/datahike/src/datahike/db/transaction.cljc` (retraction,
component cascade, `explode`, `upsert-eid`, purge, transaction-function abort),
`reference-code/datahike/src/datahike/pull_api.cljc`,
`reference-code/datahike/src/datahike/query.cljc`;
[the deletion study](datahike-deletion-and-the-program-graph-2026-09-16.md);
[the schema design review](schema-design-review-2026-09-17.md) §C.1 and N1–N11;
[the eval-path and deletion-contract review](design-review-eval-path-and-deletion-contract-2026-09-17.md)
Design 2; [program-facts PRD §1f](../plan/program-facts-are-the-runtime-prd-2026-09-17.md);
[the reset batch](../plan/reset-batch-2026-09-17.md).

---

## 1. The menu, verified against the Datahike source

| # | Behavior | What the source does | Where it is declared | file:line |
|---|---|---|---|---|
| **1** | **cascade** — the referrer dies with the target | `retract-entity` maps every **component-valued datom of the entity being retracted** to `[:db.fn/retractEntity v]`. Direction is **parent → child only**: retracting a child never retracts its parent. | `:seon.db/component true` on the attribute | `reference-code/datahike/src/datahike/db/transaction.cljc:831-834`, dispatch `:1080-1082`; `retractAttribute` does the same for one attribute `:1073-1078` |
| **2** | **sweep** — the referrer's ref datom is retracted silently | `retract-entity` scans `(dbi/search db [nil a e])` for **every** attribute in `(dbi/-attrs-by db :db.type/ref)` and retracts each incoming datom. The surviving referrer is **not re-validated**; a required key can vanish from an entity that stays alive. History keeps the datom. | the default for every plain `:seon.db/ref`; nothing declares it | `transaction.cljc:998-1014` |
| **3** | **refuse** — the writer rejects the transaction unless the referrer is retracted too | Not a Datahike behavior. Ours, at the fork's final-report validator seam that `seon.db/write-report-error` already occupies; needs the fork to carry its resolved retraction targets in an ephemeral report set. | a declared property, or (Design 2) "every non-component ref" by construction | `src/seon/db.clj:3009-3050`; algorithm in [design-review…:325-381](design-review-eval-path-and-deletion-contract-2026-09-17.md) |
| **4** | **value** — the fact stores the target's identity value | No ref exists, so `retract-entity`'s sweep cannot see it. "Names a target with no row" is one `not-join` clause. Measured: 7 such rows after two deletions in the study's E5. | the attribute's Malli type (`:qualified-symbol`, `:qualified-keyword`, `:string`) | [deletion study §4, E5](datahike-deletion-and-the-program-graph-2026-09-16.md) |
| **5** | **purge** — the fact is removed from history as well | `:db/purge`, `:db.purge/entity`, `:db.purge/attribute` operate on a `HistoricalDB` and refuse outright without `keep-history?`; `purge-components` cascades. This is the only behavior that makes `as-of` stop answering. Datahike reserves it for compliance. | the operation, never the schema | `transaction.cljc:1084-1117`, `:836-840` |

**The fifth behavior the assignment anticipated — "keep the referrer fact and
answer through history only" — is real but is not a fifth mechanism: it is
behavior 2 plus a temporal reader.** `as-of` over a pre-deletion `:t`
reconstructs the swept edge exactly (deletion study E2). It is not recommended
anywhere below for one reason: after the sweep, the **current basis** answers
"no edge" to every non-temporal reader, and not one consumer under `src/` runs
a temporal query for an edge. A correct answer that nobody asks for is the
project's named failure class wearing a clean shirt.

### 1.1 What a reader actually sees after a sweep — the reason "silent" is the right word

| Read | Result when the ref target has no datoms | file:line |
|---|---|---|
| Datalog join `[?a :attr ?t] [?t :other ...]` | the clause simply fails to match; `?a` disappears from the result set. A numeric eid is bound verbatim with no existence check. | `query.cljc:1394-1401` |
| pull with a sub-selector | the member is **dropped from the collection**: an empty `kvps` map becomes `nil`, and a nil subpattern result is not kept | `pull_api.cljc:483-486`, `:209-217` |
| wildcard pull, plain ref | `{:db/id n}` with nothing behind it | `pull_api.cljc:351-357`, `:299-302` |
| wildcard pull, component ref | the full nested child map, auto-expanded without being asked | `pull_api.cljc:345-349` |
| `[:db/id]` selector on a dangling eid | `:db/id` is **omitted**, so the map can come back empty | `pull_api.cljc:370-376` |
| top-level `pull` of a dangling numeric eid | `nil` | `pull_api.cljc:508-515` |
| an empty cardinality-many value at write time | **no datoms at all** — `explode` emits one `[:db/add …]` per member and an empty collection emits nothing | `transaction.cljc:739-770`, `:718-737` |
| re-asserting a retracted identity | a **new eid**; `upsert-eid` resolves through the AVET datom that no longer exists | `transaction.cljc:641-712`, `:659` |
| a thrown transaction function | the whole transaction aborts; `[:db.fn/call …]` sees the mid-transaction database, which is why a deletion decision belongs inside one and never in a caller's pre-read | `transaction.cljc:1153-1154` |

So every consequence of behavior 2 arrives as **fewer results**, never as an
error. That is the one fact the whole table in §2 is organised around.

---

## 2. The inventory — one row per declared ref-typed or symbol-shaped attribute

Enumerated from `resources/seon/schemas/`, not from memory. `L` marks the
recommended behavior from §1's menu.

### 2.1 `seon.fn.edn` — the function and test declaration row

| Attribute | Declared today | Who writes it | What the fact MEANS in the writer's terms | What a reader needs when the target is deleted | L + schema form | Prior ruling |
|---|---|---|---|---|---|---|
| `:seon.fn/sym` | `[:string {:seon.db/identity true, :seon.search/index :symbol}]` (`seon.fn.edn:181-185`) | `seon.fn/var-row` (`src/seon/fn.clj:585`, `:642`); agent seam `seon.sci.eval/definition-row` (`src/seon/sci/eval.clj:392`) | the declaration's identity | nothing — it IS the thing deleted | **retract the whole entity.** `:qualified-symbol` (symbols-everywhere) | **Overturns ruling 47.** Keeping a bare identity row is the tombstone G3 deletes; §3(a) shows what it costs today |
| `:seon.fn/calls` | `[:set :seon.db/ref]` (`:23`), written as lookup refs `[:seon.fn/sym target]` | `seon.fn/var-row` (`src/seon/fn.clj:625` test arm, `:663` function arm, `:702` capability arm) | **an observation of a token**: clj-kondo reported this qualified name inside this declaration's source span | the name, and separately whether a row exists. Readers: `seon.fn/gate-set-in` AVET walk (`src/seon/fn.clj:1343`), test-reach rules (`src/seon/fn.clj:1281-1287`), `seon.test/destructive-path` (`src/seon/test.clj:247-252`), `seon.run` (`src/seon/run.clj:99`), the runner's reach closure (`src/seon/test/runner.clj:1991`) | **4** — `[:set {:seon.db/index true} :qualified-symbol]` | Keeps G2. Overturns the **population invariant**, which exists only to stop this ref dangling |
| `:seon.fn/references` | `[:set :seon.db/ref]` (`:42`) | `seon.fn/var-row` (`src/seon/fn.clj:630`, `:668`, `:900`) | its own docstring: "Function references without a resolved call shape … no invocation or arity is asserted" — a token, weaker than `calls` | same as `calls`; it feeds the same walk (`src/seon/fn.clj:1345`) and the same selection (`src/seon/test/selection.clj:121`) | **4** — `[:set {:seon.db/index true} :qualified-symbol]` | Extends G2, which does not name it (review N1) |
| `:seon.fn/writes` | `[:set :seon.db/ref]` to `:seon.schema/key` rows (`:44-56`) | `seon.fn/var-row` (`src/seon/fn.clj:636`, `:674`, `:907`) | its own docstring: "a qualified keyword whose position lies inside the span of a `seon.db/transact!` usage" — a **keyword observed in text** | the keyword. `:seon.fn/keywords` (`:105-109`) already stores the identical observation as a **value**, in the same file | **4** — `[:set {:seon.db/index true} :qualified-keyword]`; the join to `:seon.schema/key` survives because that identity *is* the keyword | Extends G2 (review N2) |
| `:seon.fn/keywords` | `[:set :qualified-keyword]` (`:105-109`) | `src/seon/fn.clj:634`, `:672`, `:904` | already behavior 4 | nothing changes | **4** — unchanged. **This is the in-file precedent** that settles `writes` | — |
| `:seon.fn/call-arities` | `[:set [:tuple [:string] [:int]]]` (`:57-72`) | `src/seon/fn.clj:638`, `:676`, `:909` | already behavior 4, and its docstring says why: "Datahike stores tuple members verbatim — it resolves neither a lookup ref nor a tempid inside a tuple (probed 2026-09-16)" | nothing changes; retype the callee member to `:qualified-symbol` | **4** — `[:tuple :qualified-symbol [:int {:min 0}]]` | — |
| `:seon.fn/pending-calls` | `[:set [:string {:min 1}]]` (`:99-101`) | the declaration writer | the resolution-failure half of `calls`: "call targets whose program identity is not yet admitted" | nothing — under behavior 4 an unresolved call is just a symbol with no row | **delete.** Dissolved by `calls` becoming a value | Reset batch already schedules this |
| `:seon.fn/unresolved-references` | `[:set :seon.fn/sym]` (`:43`) | `seon.fn/artifact` (`src/seon/fn.clj:1127`, `:1226`) | already behavior 4, at file granularity | nothing changes — but it becomes redundant with per-declaration symbol edges and should be reviewed for deletion once they land (it is read at `src/seon/fn.clj:1288`, `:1388`, `src/seon/test/selection.clj:171`) | **4** — keep for now; candidate for dissolution | — |
| `:seon.fn/ns` | `[:and … :seon.db/ref]` (`:178`) | `seon.fn/var-row` (`src/seon/fn.clj:645`); error path `src/seon/error.clj:1417` | **a statement about a living entity**: this declaration belongs to that namespace entity | the namespace must exist; a function with no namespace is not a function. Readers: steward routing `src/seon/error.clj:1283`, `:1679`; namespace page `src/seon/fn.clj:791`; unowned-namespace check `src/seon/problems.clj:310` | **3 (refuse)** — keep `:seon.db/ref`; a namespace deletion must retract its declarations in the same transaction | Keeps G2's "refs stay where a genuine entity relation exists" |
| `:seon.fn/file` | `[:and … :seon.db/ref]` (`:12`) | `seon.fn/var-row` (`src/seon/fn.clj:615` test arm, `:647` function arm) | **a statement about a living entity**: these are the bytes the analyzer walked | the file row, because the digest on it is the analysis provenance G4 makes required. Readers: `src/seon/fn.clj:1290`, `:1390`; `src/seon/test/runner.clj:2152-2154`; `src/seon/issue/detect.clj:96`, `:128`; `src/seon/issue/opening.clj:47` | **3 (refuse)** — keep the ref. A file entity may not be deleted while a declaration claims it analyzed those bytes | Keeps G2; **required** by G4 |
| `:seon.fn/ast` | `[:and {:seon.db/component true} :seon.db/ref]` (`:22`) | `seon.program/ast-node` via `contract-facts` (`src/seon/program.cljc:533`, `:798`) | **containment**: the parsed contract tree is part of the function's value | nothing — it dies with its parent. 1,140 roots live on `default` | **1 (cascade)** — unchanged, **if it survives at all**; review C.2 proposes deleting the family | — |
| `:seon.fn/arities` | `[:vector {:seon.db/component true} :seon.db/ref]` (`:21`) | `seon.program/arity-row` (`src/seon/program.cljc:692`) | **containment** | nothing. 3,015 components on `default` | **1 (cascade)** — unchanged | — |
| `:seon.fn/capability-fn` | `[:and {:seon.db/index true, :seon.fn/reference-to :seon.fn/sym} :seon.db/ref]` (`:5-11`) | `seon.fn/var-row` (`src/seon/fn.clj:700`) | the docstring concedes it: "a ref to the handler's own declaration, **beside** the `:seon.effect/capability` symbol the metadata carries" — the same fact twice, once as a ref and once as a value | the handler's name. Reader: `src/seon/effect.clj:252-254`, one pull. Live population: **2** pairs (`my.web/search`, `seon.web.jvm/search`) | **4 — delete the ref, join on `:seon.effect/capability`.** Two entities do not justify a second encoding of one fact | **Overturns G2's explicit "refs stay … `/capability-fn`".** G2 listed it as a genuine relation; the docstring and the 2-row population say it is a duplicated name (review N10). This is a marked disagreement, priced in §4 |
| `:seon.fn/reference-to` | `[:qualified-keyword]` (`:4`) | declared as a Malli property | exists only to tell a reader how to recover the name from a ref | nothing | **delete** with the refs it annotates | Its existence is the evidence for behavior 4 |

### 2.2 `seon.fn.arity.edn`, `seon.fn.argument.edn`, `seon.fn.ast.edn` — the contract components

| Attribute | Declared today | Who writes it | What the fact MEANS | Reader need on deletion | L + form | Ruling |
|---|---|---|---|---|---|---|
| `:seon.fn.arity/arguments` | `[:vector {:seon.db/component true} :seon.db/ref]` (`seon.fn.arity.edn:8-9`) | `seon.program/arity-row` (`src/seon/program.cljc:692`) | containment | nothing | **1** unchanged | — |
| `:seon.fn.arity/input`, `/output`, `/guard` | plain `:seon.db/ref` (`seon.fn.arity.edn:4`, `:16`, `:2`) | `seon.program/arity-row` (`src/seon/program.cljc:692`), values are `component-id`s **inside the sibling `:seon.fn/ast` tree** | a pointer into another attribute's component forest on the same parent | **This is a live instance of the N9 disease.** `:seon.fn.arity/input` and `/output` are **required** entries of `:seon.fn.arity/row` (`seon.fn.arity.edn:31-32`); reconciliation retracts `:seon.fn/ast` with `[:db.fn/retractAttribute … :seon.fn/ast]` (`src/seon/fn.clj:2311-2314`), which cascades `retractEntity` into the tree and **sweeps these required refs off the arity**. It is only safe today because the same code retracts `:seon.fn/arities` in the same breath (`src/seon/fn.clj:2311-2314`) | **1 (cascade) — make them components of the arity, or delete them with the AST family.** A required key that a sibling attribute's retraction can silently remove is a defect whichever family survives | New finding; not named by G1–G6 or N1–N11 |
| `:seon.fn.arity/return-schema`, `/guard-schema` | plain `:seon.db/ref` (`seon.fn.arity.edn:10-11`) | `src/seon/program.cljc:725`, `:731` | a ref to a **shared, content-addressed** `seon.schema.shape` row — `seon.fn.schema-shape/shape-row`'s own docstring: "Shared content-addressed row for one compiled Malli schema" (`src/seon/fn/schema_shape.clj:299-300`), upserted on `:seon.schema.shape/fingerprint` (`seon.schema.shape.edn:2`) | the shape. `/return-schema` is **required** (`seon.fn.arity.edn:34-35`) | **3 (refuse)** on the shape row, with the honest note that **nothing retracts shape rows today, so they leak**: 2,781 schemas carry one and 3,015 arities reference them, with no reclamation path | New finding; §4 prices it |
| `:seon.fn.arity/input-refs`, `/output-refs`, `/guard-refs` | `[:set :seon.db/ref]` to `:seon.schema/key` rows (`seon.fn.arity.edn:5`, `:15`, `:3`) | `seon.program/arity-row` (`src/seon/program.cljc:734-736`) | **a keyword named in the contract form** — AGENTS §2.2's own illustration reads them as a keyword: `[?f :seon.fn.arity/input-refs :seon.db/connection]` | the keyword. Readers: `src/seon/db.clj:2308-2310`, `:2473`; `src/seon/sci/eval.clj:1162-1163`, `:1215`; `src/seon/bootstrap_drive.clj:188`. Live: **146** arities name `:seon.db/connection` | **4** — `[:set {:seon.db/index true} :qualified-keyword]` | Extends G2 (review N4) |
| `:seon.fn.argument/binding` | `[:and {:seon.db/component true} :seon.db/ref]` (`seon.fn.argument.edn:5`) | `src/seon/program.cljc:683` | containment | nothing | **1** unchanged | — |
| `:seon.fn.argument/schema`, `/rest-tail-schema`, `/rest-element-schema` | plain `:seon.db/ref` (`seon.fn.argument.edn:6-8`) | `src/seon/program.cljc:685`, `:686`, `:688` | ref to the shared shape row | `/schema` is **required** (`seon.fn.argument.edn:26`); readers `src/seon/call_preparation.clj:179`, `:315`, `:557` | **3 (refuse)** on the shape row, same as above | New finding |
| `:seon.fn.ast/child`, `/children`, `/guard`, `/input`, `/key`, `/keys`, `/output`, `/properties`, `/registry`, `/value`, `/values` | component refs (`seon.fn.ast.edn:2-3`, `:4-8`, `:30`, `:32-35`, `:37-38`) | `seon.program/ast-node` (`src/seon/program.cljc:533`) | containment | nothing | **1** unchanged (or the whole family goes, review C.2) | — |
| `:seon.fn.ast/ref` | plain `:seon.db/ref` (`seon.fn.ast.edn:29`) | `src/seon/program.cljc:576`, written as `[:seon.schema/key (:value ast)]` | **a schema key named inside a contract form** — the same observation as `input-refs` | the keyword | **4** — `:qualified-keyword` | Extends G2; not named by N1–N11 |
| `:seon.fn.argument/label-symbol` | `:symbol` (`seon.fn.argument.edn:11`) | `src/seon/program.cljc:690` | already behavior 4 | — | **4** unchanged | — |

### 2.3 `seon.test.edn` and `seon.test.failure.edn` — the test families

| Attribute | Declared today | Who writes it | What the fact MEANS | Reader need on deletion | L + form | Ruling |
|---|---|---|---|---|---|---|
| `:seon.test/sym` | `[:string {:seon.db/identity true}]` (`seon.test.edn:70-74`) | `seon.fn/var-row` (`src/seon/fn.clj:612`) | identity | — | retract; `:qualified-symbol` | Overturns ruling 47 |
| `:seon.test/reach` | `[:vector {:seon.db/cardinality :many} :seon.db/ref]` (`seon.test.edn:2`) | `seon.test.runner/record-tx` (`src/seon/test/runner.clj:2384-2386`), members computed as `[:seon.fn/sym s]` lookup refs (`src/seon/test/runner.clj:2003-2005`) | **an observation**: "these names were in the closure the run actually exercised". It is evidence about a past run, not a relation to a present entity | the names, and the ability to say "this reach member no longer exists". Reader: `seon.test/changed-since-green` (`src/seon/test.clj:62`, `:98-101`), which re-reads `:seon.fn/source`/`/spec` on the members' eids | **4** — `[:set {:seon.db/index true} :qualified-symbol]` | Keeps G2. **This one is the strongest case in the document**: reach is recorded evidence that crosses a publication boundary, which is precisely why `seon.cluster.source/absent-program-identities` and the whole tombstone apparatus exists (`src/seon/cluster/source.clj:337-370`) |
| `:seon.test/reach-unknown` | `[:string {:min 1}]` (`seon.test.edn:4`) | `src/seon/test/runner.clj:2387-2390` | a string sentinel standing in for a fact | — | **delete**, once G4's required analysis-provenance digest lands: "provenance present, no members" vs "provenance absent" replaces it. Its own docstring confesses the defect: "absence of both membership and this marker is legacy unknown, never proof of an empty closure" | Implements G4 (review N15) |
| `:seon.test/subject` | `:seon.db/ref` (`seon.test.edn:10`) | `seon.fn/var-row` (`src/seon/fn.clj:641`, `:679`) from the test Var's `:seon.test/subject` metadata | **a name the author wrote in metadata** | the symbol. Readers: `src/seon/fn.clj:1297`, `:1312`, `:1365`; `src/seon/test.clj:248-251`; `src/seon/test/runner.clj:1986` | **4** — `:qualified-symbol` | Extends G2 (review N7) |
| `:seon.test/pending-subject` | `[:string]` (`seon.test.edn:11`), written by `seon.turn/relation-assertions` | the resolution-failure half of `subject` | the same symbol | — | **delete** with `pending-calls` | Review N7 |
| `:seon.test/ns` | `:seon.db/ref` (`seon.test.edn:1`) | `src/seon/fn.clj:613`; runner tempid `src/seon/test/runner.clj:2400` | statement about a living namespace entity | the namespace. Readers `src/seon/test.clj:516`, `:594`, `:1059`; `src/seon/bootstrap.clj:244` | **3 (refuse)** — keep the ref | Keeps G2 |
| `:seon.test/run` | `:seon.db/ref` to `:seon.test.run/run` (`seon.test.edn:38`) | `seon.test.runner/record-tx` (`src/seon/test/runner.clj:2378-2380`) | statement about a living run record: "this result came from that run" | the run must exist, or the result loses its provenance. Reader: `seon.test/changed-since-green`'s green-basis reduction keys on the presence of a `:seon.test/run` assertion (`src/seon/test.clj:76-91`) — **a sweep here would silently un-green a test's whole history** | **3 (refuse)** — keep the ref; a run record is append-only evidence and may not be deleted while a result cites it | New finding; the `:seon.test/run` reader at `src/seon/test.clj:87` is an absence-reads-as-state site under any sweep |
| `:seon.test/failures` | `[:vector {:seon.db/component true} :seon.db/ref]` (`seon.test.edn:39`) | `seon.test.runner/record-tx` (`src/seon/test/runner.clj:2392-2399`) | containment — the failures ARE part of this result's value | nothing; they die with the test row. Live: the sampled failing test carries 1 | **1 (cascade)** unchanged, **with G5's caveat**: the component rows are never selected by the whole-entity validator today, so they must be validated as part of the parent pulled with components expanded | Implements G5 |
| `:seon.test/failing-assertions` | `[:vector {:cardinality :many} :seon.test.failure/id]` (`seon.test.edn:36-38`) | `src/seon/test/runner.clj:2378` | the same failures, as **values**, beside the component refs | — | **delete** the duplicate; the components are the durable half | Review N36/B.3, already in the reset batch |
| `:seon.test/adoption-identities` | `[:set :seon.db/ref]` (`seon.test.edn:—`, near the adoption map) | the adoption writer | which program identities an adoption installed — **an observation about a past event** | the identities. A later deletion of one of them must not rewrite what the adoption recorded | **4** — `[:set :seon.program/identity]` (the tuple type already exists) | New finding; not named by N1–N11 |
| `:seon.test/adoption-cluster` | `:seon.db/ref` | the adoption writer | statement about a living cluster | the cluster | **3 (refuse)** | — |
| `:seon.test.failure/test` | `:seon.db/ref` (`seon.test.failure.edn:3`) | `src/seon/test/runner.clj:2206`; preserved across publication as a lookup ref at `src/seon/cluster/source.clj:433` | containment in the other direction — the failure is already a component OF the test, so this is a **back-pointer** | nothing: a component's parent is never deleted without the component | **delete the back-pointer**, or keep it as behavior 1's inverse and document that it is redundant with `:seon.test/_failures`. It is the reason `result-preservation-tx` has to re-resolve refs by lookup (`src/seon/cluster/source.clj:433-437`) | New finding |
| `:seon.test.failure/file` | `:seon.db/ref` (`seon.test.failure.edn:13`) | `src/seon/test/runner.clj:2178`, as `[:seon.fn.file/relative-path path]` | **a path the reporter printed** — and `:seon.test.failure/reported-file` (`:21`) stores the same fact as a string beside it | the path. Readers `src/seon/test.clj:32`; `src/seon/problems.clj:358`; `src/seon/test/runner.clj:2441` | **4** — keep `reported-file` as the durable half; delete the ref. Publication already proves the string is the durable one: `result-preservation-tx` strips and re-resolves the ref across a rebuild (`src/seon/cluster/source.clj:471-477`) | Extends G2 (review N11) |
| `:seon.test.failure/first-run`, `/last-run` | `:seon.db/ref` (`seon.test.failure.edn:17-18`) | `src/seon/test/runner.clj:2207-2208`; preserved at `src/seon/cluster/source.clj:434-435` | statement about living run records; both are **required** (`seon.test.failure.edn:44-45`) | the run. A sweep removes a required key from a surviving failure row | **3 (refuse)**, same as `:seon.test/run` | New finding |
| `:seon.test.failure/throwable` | `:symbol` (`seon.test.failure.edn:19`) | the runner | already behavior 4 | — | **4** unchanged — the in-family precedent for `/file` | — |

### 2.4 `seon.ns.edn`, `seon.schema.edn`, `seon.lint.edn`, `seon.fn.file.edn`, `seon.program.edn`, `seon.instrument.edn`, `seon.render.edn`

| Attribute | Declared today | Who writes it | What the fact MEANS | Reader need on deletion | L + form | Ruling |
|---|---|---|---|---|---|---|
| `:seon.ns/name` | `[:symbol {:seon.db/identity true}]` (`seon.ns.edn:8-12`) | `src/seon/fn.clj:303` | identity, already a symbol | — | retract the entity | Overturns ruling 47 |
| `:seon.ns/requires` | `[:set :seon.db/ref]` (`seon.ns.edn:31`) | `src/seon/fn.clj:303`, `:748` | **a libspec the ns form names** — a token in source text, exactly like `calls` | the namespace symbol. Readers: reload ordering `src/seon/cluster.clj:2203-2209`; `src/seon/fn.clj:2363`; `src/seon/turn.clj:995`, `:3643`. Live: **67** namespaces require `seon.turn` | **4** — `[:set {:seon.db/index true} :symbol]`. `:seon.ns.alias/target-ns` and `:seon.ns.refer/target-ns` in the same family are **already symbols** (`seon.ns.alias.edn:13-16`, `seon.ns.refer.edn:19-22`) — one family, one fact, two encodings | Extends G2; **not named by N1–N11**, and it is the highest-fanout name-edge in the program graph |
| `:seon.ns/aliases`, `/imports`, `/refers` | component sets (`seon.ns.edn:2`, `:6`, `:30`) | `src/seon/fn.clj:303` | containment | nothing | **1** unchanged | — |
| `:seon.ns/steward` | `[:and … :seon.db/ref]` (`seon.ns.edn:33-38`) | `seon.cluster.agent/steward-call` (declared `:seon.program/written-by`, `seon.ns.edn:26-29`) | **a statement about a living agent**: "the one agent that stewards this namespace" | the agent. Readers: `src/seon/error.clj:1284`, `:1678`, `:1730`; `src/seon/agent.clj:18-30`; the unowned-namespace check `src/seon/problems.clj:278` reads **absence** as unowned — so a swept steward is indistinguishable from an unstewarded namespace | **3 (refuse)** — keep the ref; deleting an agent must reassign or explicitly retract stewardship | Keeps G2; the `problems.clj:278` absence read makes this a blocker-class instance (review A.2) |
| `:seon.schema/key` | `[:keyword {:seon.db/identity true}]` (`seon.schema.edn:43-46`) | `seon.schema/canonical-schema-rows` (`src/seon/schema.clj:2928`), `register!` (`:1333`) | identity | — | retract the entity | Overturns ruling 47 |
| `:seon.schema/references` | `[:set :seon.db/ref]` (`seon.schema.edn:50-53`) | `src/seon/schema.clj:2980` | **registry keys named in a Malli form** | the keyword. Reader `src/seon/ai.clj:327-332` immediately maps back to `:seon.schema/key`, which is the proof the ref buys nothing. Live: **32** schemas reference `:seon.db/connection` | **4** — `[:set :qualified-keyword]` | Extends G2 (review N3) |
| `:seon.schema/ns` | `[:and … :seon.db/ref]` (`seon.schema.edn:49`) | `src/seon/sci/reader.cljc:410`; `src/seon/program.cljc:964-965` | statement about a living namespace | the namespace. Reader `src/seon/render/ns.clj:893` | **3 (refuse)** | Keeps G2 |
| `:seon.schema/shape` | `:seon.db/ref` (`seon.schema.edn:48`) | `seon.program/with-contract-facts` (`src/seon/program.cljc:838`) | a ref to the shared, content-addressed shape row | the shape. Readers `src/seon/call_preparation.clj:161`, `:201`; `src/seon/issue/detect.clj:20-23`. Live: **2,781** | **3 (refuse)** on the shape; see §4 on reclamation | New finding |
| `:seon.schema/predicate`, `/identity-projection` | `:qualified-symbol` (`seon.schema.edn:41`, `:38`) | schema admission | already behavior 4 | — | **4** unchanged — the in-family precedent for `/references` | — |
| `:seon.schema.shape/children`, `/entries` | component vectors (`seon.schema.shape.edn:9-10`, `:11-12`) | `seon.fn.schema-shape/shape-row` (`src/seon/fn/schema_shape.clj:299`) | containment **inside a row that is itself shared by fingerprint** | nothing today, because nothing retracts a shape row. But **if one were ever retracted, its cascade would destroy a subtree several parents point at** | **1** unchanged, with the sharing recorded in the docstring | New finding |
| `:seon.fn.file/relative-path` | `[:string {:seon.db/identity true}]` (`seon.fn.file.edn:1-3`) | `seon.fn/artifact` (`src/seon/fn.clj:1217`) | identity: the file path | — | retract the entity; the **rename** case is §3(e) | — |
| `:seon.lint/fn` | `[:and … :seon.db/ref]` (`seon.lint.edn:2`) | `src/seon/fn.clj:1201`, as the declaration's program identity | **the declaration whose span contains this finding** — containment in substance, a plain ref in declaration | today a sweep silently drops the link and the finding survives, orphaned, attributed to nothing. Reader: `src/seon/render/ns.clj:845` uses the reverse `:seon.lint/_fn` | **1 (cascade)** — declare it `:seon.db/component`? **No**: the component must be the child, and the finding is the child. Recommend instead: **the function row owns `:seon.lint/_findings` as a component**, or the finding stores `:qualified-symbol` (**4**). Given findings are rebuilt on every analysis, **4** is simpler and needs no cascade | New finding; not in N1–N11. Live: **546** findings carry it |
| `:seon.lint/file` | `:seon.db/ref` (`seon.lint.edn:3`), **required** (`:16`) | `src/seon/fn.clj:1196`, as `[:seon.fn.file/relative-path path]` | the file the finding is in | a sweep removes a **required** key from a surviving finding (N9's shape again). Live: 34 findings on `src/seon/turn.clj`, 4 on `src/seon/id.clj` | **3 (refuse)** or **4** (`:string` path, like `:seon.test.failure/reported-file`). Recommend **4**: findings are re-derived from analysis, never repaired | New finding |
| `:seon.program/identity` | `[:tuple :seon.program/identity-attribute :seon.schema/value]` (`seon.program.edn`) | `seon.program/deletion-row` (`src/seon/program.cljc:1058`) | already behavior 4 — the identity as a value | — | **4** unchanged. **This is the shape `:seon.test/adoption-identities` should use** | — |
| `:seon.program/written-by` | `:qualified-symbol` property | declared on entity map entries | already behavior 4, naming a function without a ref | — | **4** unchanged — the precedent that a program-graph fact can name a function by symbol with no row required | — |
| `:seon.instrument/fn` | `[:string {:min 1}]` (`seon.instrument.edn:—`, `:fn` entry) | the instrumentation error constructors | a function name in a diagnostic | the name must survive the function, which is the whole point of a fault record | **4** — `:qualified-symbol`. Its sibling `:seon.error/fn` is a **ref** to the same fact on the same occurrence map (`seon.error.occurrence.edn:26`, `:36`) — delete the ref | Review N13; already in the reset batch |
| `:seon.render/ai`, `/html`, `/form` | `:qualified-symbol` **Malli properties**, not datoms (`seon.fn.edn:114-115`, `seon.ns.edn:15-18`, `seon.test.edn:—`) | declared on the entity map | already behavior 4: a render pair names its function by symbol and the function's deletion cannot corrupt the schema | the symbol; an unresolvable render function must be a typed unknown at selection time, not a missing key | **4** unchanged — **the cleanest existing precedent in the whole population** | — |

---

## 3. The five deletion events, worked with real identities from `default`

All identities and counts below come from the two permitted read-only queries
against cluster `default` at HEAD `6a2201f29` (population: 5,114 functions,
1,861 tests, 2,790 schema keys, 438 namespaces, 339 files).

### (a) A function is deleted from source — `seon.turn/open?`

**Today.** The deletion path is `seon.turn/row-tx` (`src/seon/turn.clj:1304`),
which calls `program/exact-replacement-tx declaration {identity-attribute
identity-value}` (`src/seon/turn.clj:1336-1339`): every owned attribute is
retracted and the identity datom stays — the tombstone. Nothing points at
nothing, and `seon.turn/open?` continues to answer a join
`[?a :seon.fn/calls ?c] [?c :seon.fn/sym ?s]` exactly like a live function
(the study's D2).

**Under these recommendations** the transaction is
`[:db/retractEntity [:seon.fn/sym 'seon.turn/open?]]`, and:

| Referrer | Today | After |
|---|---|---|
| **5 callers** — `seon.turn/recover-call`, `seon.turn/receipt-run`, `seon.turn/render-ai`, `seon.turn/require-open-run`, `seon.turn/open-run-tx-call` | tombstoned, so the edges survive by accident | `:seon.fn/calls` is a symbol set: **no caller datom moves**. Each caller still says it calls `seon.turn/open?`, and `(not-join [?s] [_ :seon.fn/sym ?s])` names it as a call with no row |
| **4 tests reaching it through those callers** — `seon.turn-test/one-run-lifecycle-teaches-the-call-shapes`, `seon.turn-test/state-is-derived-from-primitives`, `seon.fn.analyzer-test/ordered-forms-use-existing-context-and-original-row-numbers`, `seon.fn.analyzer-test/reply-analysis-does-not-contaminate-build-analysis` | selected | **still selected**, because `gate-set-in`'s AVET walk (`src/seon/fn.clj:1343`) walks symbol datoms |
| **54 tests whose recorded `:seon.test/reach` includes it** | preserved by the tombstone | preserved because reach is a symbol set. `seon.test/changed-since-green` (`src/seon/test.clj:62`) must then resolve each member symbol and report an unresolvable one **positively** — a deleted dependency is a reason to re-run, never a silently shorter closure |
| **0 `:seon.fn/references`, 0 subject-tests** | — | unchanged |
| its `:seon.fn/ns`, `:seon.fn/file` | retracted with the row | retracted with the row; neither namespace nor file is touched (sweep is incoming-only) |
| its `:seon.fn/arities`, `:seon.fn/ast` | retracted attribute by attribute | **cascade**, one operation (`transaction.cljc:831-834`) |
| the shared shape rows its arities referenced | orphaned | orphaned — **unchanged, and still a leak** (§4) |
| `history` / `as-of` | answers | answers, unchanged (the study's E2) |

**Without the retype, `retractEntity` here would silently delete 5 caller
edges and quietly narrow 4 tests' selection.** That is the whole argument.

### (b) A namespace file is deleted — `src/seon/turn.clj` / namespace `seon.turn`

Live: the file carries **176 declarations**; the namespace owns **176
functions**, **0 tests**, **0 schema keys**, and is **required by 67 other
namespaces**; it currently has **no steward**; the file carries **34 lint
findings** and **0 failure rows**.

The transaction is 176 `[:db/retractEntity [:seon.fn/sym …]]` plus
`[:db/retractEntity [:seon.ns/name 'seon.turn]]` plus
`[:db/retractEntity [:seon.fn.file/relative-path "src/seon/turn.clj"]]`.

| Referrer | What happens under the recommendations |
|---|---|
| the 176 functions' `:seon.fn/ns` (behavior 3) | the namespace deletion **refuses** unless those 176 retractions are in the same transaction. This is the correct, loud outcome: a namespace is not deletable while its declarations exist |
| the **67 requiring namespaces** | `:seon.ns/requires` is a symbol set: **nothing moves**. Reload ordering (`src/seon/cluster.clj:2203-2209`) still sees the edge and must report the missing target, not silently drop the node from the graph. **Today this is 67 silently retracted datoms and a reload order that quietly changes shape** |
| the 34 lint findings' `:seon.lint/file` | under **4** they carry the path string and survive the file row; under **3** the file deletion refuses until they are retracted in the same transaction. Either is defensible; **4** is recommended because findings are rebuilt wholesale by the next analysis |
| each function's callers in other namespaces | symbol sets: unmoved, and now queryable as unresolved |
| the namespace's `:seon.ns/aliases` / `/imports` / `/refers` | cascade |
| `:seon.ns/steward` | none here; had there been one, the **agent** is untouched — the sweep runs from the deleted entity outward, and the namespace is the referrer, not the target |

### (c) A test is deleted — `seon.turn-test/a-refused-generated-form-records-its-refusal`

Live: it carries **1** `:seon.test/failures` component, whose
`:seon.test.failure/last-run` points at a run record.

`[:db/retractEntity [:seon.test/sym 'seon.turn-test/…]]`:

- the failure component **cascades** and dies with it (behavior 1) — correct,
  a failure is part of a result's value;
- the failure's `:seon.test.failure/first-run` / `/last-run` refs are its own
  outgoing refs, so the **run records are untouched**;
- `:seon.test/run`, `:seon.test/ns`, `:seon.test/subject` are outgoing — the
  run record and the namespace survive;
- **nothing incoming exists**: no attribute in the population refs a test row
  except `:seon.test.failure/test`, which is the component's own back-pointer
  and dies with it. A test is the cleanest deletion in the graph;
- history keeps every recorded result, so "what did this test's reach used to
  be" is an `as-of` query.

**The one thing that must not happen** is the inverse: deleting the **run**
record. `:seon.test/run` and `:seon.test.failure/first-run` / `/last-run` are
required refs into it, and `seon.test/changed-since-green` decides greenness
from the **presence of a `:seon.test/run` assertion in history**
(`src/seon/test.clj:87`). A sweep there converts every test's green history
into "no green result is retained" — absence read as a state, at the gate.
Hence behavior 3 on all three.

### (d) A schema key is deleted — `:seon.db/connection`

Live referrers: **4** functions declare they write it
(`seon.issue/add!`, `seon.issue/tests!`, `seon.issue/start!`,
`seon.turn/close-turn`); **32** schemas reference it; **146** arities name it
in `:seon.fn.arity/input-refs`; **177** functions mention the keyword in
`:seon.fn/keywords` — which is **already a value and already survives**.

Today `seon.turn/row-tx` handles a schema-key deletion specially
(`src/seon/turn.clj:1304-1326`): it computes a candidate projection without the
key, calls `assert-schema-data-unused!`, and emits the attribute-change
transaction — then falls through to the same identity-only tombstone.

Under the recommendations the key row is retracted outright and:

- the **4 writers**, **32 schemas** and **146 arities** keep their facts, as
  keywords. `(not-join [?k] [_ :seon.schema/key ?k])` over `:seon.fn/writes`
  is the honest "writes an attribute that no longer exists" report;
- the **177** `:seon.fn/keywords` mentions are unchanged — they always were;
- `:seon.schema/shape` is an **outgoing** ref, so the shared shape row is not
  cascaded (it is not a component) and not swept (the key is the referrer).
  It is simply orphaned. With 2,781 shape-carrying schemas and no reclamation
  path, this is the leak §4 asks the owner to rule on;
- `assert-schema-data-unused!` stays: refusing to delete a key whose attribute
  still has datoms is behavior 3 applied to *data*, not to refs, and it is
  correct.

**Today, the same deletion silently retracts 4 + 32 + 146 = 182 datoms from
surviving entities**, 146 of them from `:seon.fn.arity/*-refs` sets inside
component rows nobody validates.

### (e) A file is renamed — `src/seon/id.clj` → some other path

Live: the file carries **6** declarations (`seon.id/digest`,
`seon.id/evaluation`, `seon.id/id`, `seon.id/sha-256`, `seon.id/symbol-in`,
`seon.id/valid?`), **4** lint findings, **0** failure rows.

`:seon.fn.file/relative-path` is the identity, so a rename is **a new entity,
not an update** — `upsert-eid` cannot follow it (`transaction.cljc:641-712`).
The publication therefore writes the new file row and retracts the old one.

| Referrer | Today | Under the recommendations |
|---|---|---|
| the **6** declarations' `:seon.fn/file` | the old row is tombstoned by `exact-replacement-tx`, so the refs survive **pointing at a row with no digest** — a ref whose target is a husk, which is exactly D2 | behavior 3 forces the same transaction to re-point all 6 at the new file row. A rename becomes one atomic, loud operation instead of six quiet ones |
| the **4** lint findings' `:seon.lint/file` | same husk | under **4** they carry the old path string, which is **correct evidence**: the finding was reported at that path. The next analysis replaces them wholesale |
| `:seon.test.failure/file` (0 here; 34 lint rows on `turn.clj` show the scale) | `result-preservation-tx` already strips and re-resolves the ref by lookup across a publication (`src/seon/cluster/source.clj:471-477`) — **machinery that exists only because the ref cannot survive a rebuild** | under **4** (`reported-file`) that machinery deletes |

**The rename is the event that most clearly shows which refs are wrong.**
Every attribute that needed special preservation code to survive a rename is a
name-observation wearing a ref.

---

## 4. What genuinely depends on a policy choice

Everything above except the following is decided by what the writer observed.
These are real decisions. Two priced options each, in prose, recommendation
marked.

### 4.1 What a peer relation does when its target is deleted

This is the only question in the document that is genuinely about policy rather
than about what a fact means. It covers `:seon.fn/ns`, `:seon.fn/file`,
`:seon.test/ns`, `:seon.test/run`, `:seon.test.failure/first-run` and
`/last-run`, `:seon.schema/ns`, `:seon.ns/steward` and the shape refs.

*The first option is to refuse.* Every non-component ref requires a living
target: the writer rejects a transaction that leaves a surviving entity
pointing at a deleted one, unless the referrer is retracted in the same
transaction. The guarantee is total and needs no per-attribute declaration, so
a ref added next month is covered the day it is declared. It costs a fork
change — `retract-entity` must carry its resolved targets in an ephemeral
report set so the final-report callback can see expansions and cascades, which
is its own fork commit with the dependency's suite proof — plus a coordinated
change at every deletion caller, which must now retract referrers itself. It
gives up the ability to express a deliberately weak relation, and it gives up
atomic reparent-then-delete under a strict reading.

*The second option is to let the sweep stand and make the surviving entity's
schema the check.* No fork change: after any transaction that retracted
entities, the existing whole-entity validator re-validates each surviving
referrer against its schema, so a swept **required** key is caught and a swept
optional one is allowed. This is cheap and uses machinery we already have, but
it is precisely the "absence reads as health" failure for every optional ref —
`:seon.ns/steward` is optional, and a swept steward is byte-identical to an
unstewarded namespace at `src/seon/problems.clj:278`.

**Recommendation: the first option, scoped.** Refuse, but only for the eight
attributes named above rather than for all 207 ref mentions in the resources,
and land it *after* the §2 retypes have removed the name-observations — the
retypes cut the protected set to a size where the coordinated deletion callers
are a day of work rather than a program. The optional-ref hole in the second
option is not a corner case: it is the exact shape of the worst instance in
the schema review (`:seon.listen/entity`, where a deletion widens a wake
pattern from one entity to all of them).

### 4.2 Whether `:seon.fn/capability-fn` keeps its ref

G2 explicitly says refs stay at `/capability-fn`; §2.1 recommends deleting it.
The owner should settle this because it is a direct disagreement with a ruling.

*Keep the ref.* `seon.effect/dispatch` resolves the handler in one pull
(`src/seon/effect.clj:252-254`) instead of a symbol lookup, and the indexer
already refuses a capability marker whose handler has no row, so it cannot
dangle today. The cost is a second encoding of a fact the same row already
carries as `:seon.effect/capability`, and it is the one ref in the function
family whose target deletion would silently unhook a capability.

*Delete the ref, join on the symbol.* One fact, one encoding; a deleted
handler becomes a typed unresolved-handler result at dispatch instead of a
silently missing key. The cost is one extra lookup on the effect path and the
loss of the writer-side guarantee that the handler exists.

**Recommendation: delete the ref.** The live population is **two** pairs. A
duplicated encoding defended by a one-hop saving on two rows is not worth a
ruling, and the attribute's own docstring already describes the symbol as the
primary fact.

### 4.3 Shared content-addressed shape rows — reclamation

`seon.fn.schema-shape/shape-row` produces rows deduplicated by fingerprint
(`src/seon/fn/schema_shape.clj:299-300`), referenced from `:seon.schema/shape`
(2,781 live), `:seon.fn.arity/return-schema`, `/guard-schema`,
`:seon.fn.argument/schema` and its rest variants (3,015 arities). **Nothing
retracts them.** They accumulate across every publication.

*Leave them.* Zero work, zero risk, and the store is disposable by ruling — a
reset reclaims everything. The cost is unbounded growth between resets on an
entity family nobody can count from a query without walking every referrer.

*Reclaim them.* A maintenance task retracts shape rows with no incoming ref.
That is genuinely safe only under 4.1's first option, because a concurrent
publication could add a referrer between the scan and the retraction — which
means the decision belongs inside `[:db.fn/call …]`, where it sees the
mid-transaction database. The cost is one more maintenance owner and a
transaction function.

**Recommendation: leave them and measure.** Record the count at each reset in
the reset note; if it grows faster than the program does, reclaim it then. The
[TARGET] root maintenance portfolio is where this eventually lives, and
inventing it now for an unmeasured leak is the addition this project prefers
to dissolve.

### 4.4 Whether `:seon.lint/file` is a ref or a path string

*Keep the ref.* Findings join to the file entity, so "every finding under this
source root" is one clause (`src/seon/issue/detect.clj:128` already does this
shape through `:seon.fn/file`). The cost is that `:seon.lint/file` is
**required**, so a file deletion or rename sweeps a required key off a
surviving finding — N9's disease, at 546 rows carrying `:seon.lint/fn` today.

*Store the path.* The finding records where it was reported, exactly as
`:seon.test.failure/reported-file` does, and survives any file event. The cost
is that the root-relative grouping query needs a string prefix instead of a
join — and a string prefix over paths is close enough to a regex to want the
owner's eye.

**Recommendation: keep the ref and apply 4.1's refusal.** Unlike a failure
row, a lint finding is rebuilt wholesale by the next analysis, so the
coordinated-deletion cost is one line in the publication, and the grouping
query stays a join instead of becoming string arithmetic.

### 4.5 `:seon.test/reach` members that no longer exist

Under behavior 4, reach is a set of symbols and a member can name a deleted
function. `seon.test/changed-since-green` must then decide what an
unresolvable member means.

*Treat it as a changed dependency.* The test is selected. Simple, and
correct in the only direction that matters — a deleted dependency is a reason
to re-run. The cost is that a large deletion selects broadly.

*Treat it as a typed unknown that fails the verdict.* The test reports that
its closure cannot be evaluated. Louder, and it makes "the reach is stale"
visible; the cost is that an ordinary deletion turns a green test red until it
re-runs, which is a worse signal than a re-run.

**Recommendation: the first.** Selecting a test is cheap; a red test that is
not broken is expensive, and AGENTS §2.3's rule is that a bound firing must
name what never arrived — here nothing failed to arrive, a dependency simply
went away.

### 4.6 Not a policy choice, stated so it is not mistaken for one

- **`:seon.fn.arity/input` and `/output` pointing into the sibling AST
  component tree** (§2.2) is a defect either way: a required key removable by a
  sibling attribute's retraction. Whether the AST family survives (review C.2)
  changes the fix, not the verdict.
- **`:seon.test.failure/test`** is a back-pointer from a component to its
  parent. `:seon.test/_failures` answers the same question. It is redundant,
  not a policy.
- **`:seon.ns/requires`** as a symbol set is not a choice: the same family
  already stores `:seon.ns.alias/target-ns` and `:seon.ns.refer/target-ns` as
  symbols (`seon.ns.alias.edn:13-16`, `seon.ns.refer.edn:19-22`). One fact,
  one encoding.
- **G4's required analysis-provenance fact** is what makes every "empty means
  never analyzed" read above legitimate. Without it, behavior 4 turns "A calls
  nothing" and "A was never analyzed" into the same zero datoms
  (`transaction.cljc:739-770`), and every recommendation in §2 inherits the
  defect it was meant to remove.

---

## 5. Verification boundary

- Source read at `steward-platform` HEAD `6a2201f29`. Every `file:line` cited
  is a line I opened in this session.
- **`src/seon/test/runner.clj`, `src/seon/test/selection.clj`,
  `src/seon/schema/edn.clj`, `src/seon/schema/datahike.clj` and several schema
  resources carry another lane's uncommitted edits.** Lines cited in those
  files are working-tree lines, not HEAD lines; the `runner.clj` citations
  (`:1991`, `:2003-2005`, `:2152-2154`, `:2178`, `:2206-2208`, `:2378-2400`,
  `:2441`) should be re-checked at the reset's HEAD before they are used in a
  spec.
- Two read-only `mcp__seon__eval_clj` calls against `default` (mode `jvm`,
  custody `(seon.operator/connection "default")`), both pure `seon.db/q`
  bundles. Nothing was transacted. Every count in §3 comes from those two
  calls.
- No gate was run; no test JVM was launched; no `bin/seon` state changed.
- **Not established:** the cost of behavior 3 at the fork — the ephemeral
  retraction-target report set is designed in the eval-path review but not
  built or measured; whether shape-row accumulation is material (§4.3 asks for
  a measurement, it does not report one); and whether any production reader of
  `seon.fn.ast` survives, which review C.2 makes a precondition for that
  family's deletion and which I did not re-run.
