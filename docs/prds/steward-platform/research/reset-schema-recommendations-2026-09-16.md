---
type: research
status: active
created: 2026-09-16
tags: [schema, datahike, malli, reset, symbols, tuples, codec, pull]
---

# Reset schema recommendations — everything but the per-ref deletion question

Read-only lane. Scope: the reset's non-deletion half — symbols, required versus
optional, duplicate schemas, kind stamps, the `-edn` strings, the `fn.ast`
family, the pulled-shape direction, and anything the integrator's plan asks for
that Datahike cannot do as specified. Two sibling lanes own the per-ref
deletion question for the program-graph and agent/turn families; no row below
re-decides which refs become symbols *because of deletion* — where I touch one
of those attributes it is for a different reason, and I say which.

**Owner's instruction that governs every row:** "WE OWN THE SCHEMAS. WE ARE
ADAPTABLE. DO NOT LOCK US INTO BAD PRIOR DECISIONS." Ruling 47, G1–G6,
symbols-everywhere as specified, the retirement designs, the tombstone
machinery, the second validator and the pulled-shape direction are all inputs
here, not constraints.

## Authorities read end to end

`AGENTS.md` §2 (the five design laws) and §3 (data and schema, the vocabulary
table); `.claude/skills/datahike/SKILL.md`; `.claude/skills/data-modeling/SKILL.md`;
`reference-code/datahike/src/datahike/schema.cljc` (whole file);
`reference-code/datahike/src/datahike/db/transaction.cljc` (`explode`,
`maybe-wrap-multival`, `transact-add`, `validate-val`, `check-tuple`,
`retract-entity`, the transaction-function splice);
`reference-code/datahike/src/datahike/db/utils.cljc:20-72`;
`reference-code/datahike/src/datahike/pull_api.cljc:290-440`;
`src/seon/schema/datahike.clj` (whole file, 604 lines);
`src/seon/schema/form.cljc:47-91`;
[schema-key audit](schema-key-audit-2026-09-16.md) (defects head, cross-key
findings, the complete reset edit list grouped by resource);
[schema design review](schema-design-review-2026-09-17.md) (all of N1–N44,
B.1–B.12, C.1–C.6, D.1–D.5);
[symbols inventory](symbols-everywhere-inventory-2026-09-17.md);
[entity schema versus pulled shape](entity-schema-vs-pulled-shape-2026-09-16.md);
[the integrator's merged plan](../plan/reset-batch-2026-09-17.md) (all 76
resource tables, the contradiction table, the dependency ledger, the
cross-resource completeness rules, Q1 and Q2).

Two read-only `mcp__seon__eval_clj` calls against `default` (jvm mode, custody
`(seon.operator/connection "default")`, no transact) supply every "live"
number. Live numbers are dated 2026-09-16 on that cluster's population.

---

## 0. The seven things the existing documents get wrong

Each is a correction I can defend from a line I opened; each changes the edit
list.

| # | Claim in a prior document | What is actually true | Consequence |
|---|---|---|---|
| **X1** | Review N26: `:seon.issue/status` is "a stored mirror of a positive fact on the same entity", because `:seon.issue/resolved-tx`'s docstring says absence means open. | **Live: `:seon.issue/status` has 1 742 datoms (open 305, resolved 1 166, superseded 271). `:seon.issue/resolved-tx` has ZERO datoms.** The enum is the *only* authority in the population; the fact it supposedly mirrors is never written. | Deleting the enum at this reset deletes 1 437 terminal-state facts with nothing to derive them from. The enum goes only *after* its writers write `resolved-tx`/`superseded-by`. Reordering, not a veto. |
| **X2** | Review N25 and N44: delete `:seon.eval/outcome`; `:seon.maintenance.result/value :map` is "an unconstrained stored map". | **Neither is a stored attribute.** `:seon.eval/outcome` and `:seon.maintenance.result/value` are both absent from the installed schema on `default`. `form->datahike-value-type-in` has no `:map` case (`src/seon/schema/datahike.clj:55-68`, `:177-185`), so `storable-attribute-in?` (`:299`) answers false, and `database-attributes` only admits an entry of a `{:seon.db/attributes true}` map or a key carrying a database property (`src/seon/schema/form.cljc:66-91`). | These are API-contract edits, not reset edits. They must not sit in a publication group gated on a reset; `:seon.test/failure-identity` is in the same position. |
| **X3** | Symbols inventory §1.1 item 9: `:seon.error/throwable-class` is "already a live mismatch: `src/seon/error.clj:285` writes `(symbol class-name)` into it." | That site is now `src/seon/error.clj:292` and it builds the **signature input map** for `seon.id/id`, not a datom. The datom writer is `src/seon/error.clj:576-577`, which writes the **string** into `/throwable-class` and the **symbol** into `/exception-class`. Live: `/throwable-class` `:db.type/string`, `/exception-class` `:db.type/symbol`, both installed. There is no live type mismatch. | The retype is still right, but it is an ordinary rename-and-merge, not an urgent contract break. The real mismatch is the opposite one: `src/seon/db.clj:2398` puts `(.getName (class cause))` — a **string** — under `:seon.error/exception-class`, which is `:db.type/symbol`. Under `:schema-flexibility :write` (`src/seon/db.clj:92`, `src/seon/cluster/store.clj:192`) that value refuses at `validate-val` (`transaction.cljc:33-52`) the moment it reaches a datom. |
| **X4** | Review N29 / audit / plan: the ordering problem in tuples and vectors. | Confirmed, and **Datahike's heterogeneous tuple check has a hole**: `check-tuple` refuses only when the per-member validity results *differ* — `(not (apply = (map s/valid? tupleTypes v)))` (`transaction.cljc:1037-1039`). A tuple in which **every** member is the wrong type passes silently. `["a/b" "3"]` against `[:db.type/symbol :db.type/long]` is accepted. | The `:seon.fn/call-arities` retype (55 938 live datoms) cannot rely on the dependency to catch a half-converted writer. Its regression must assert the stored member types, not just that the transaction succeeded. |
| **X5** | Pulled-shape option (B): derive the pulled form from entity schema + selector. | Right, and **under-specified**: Datahike's pull grammar has five features (B) does not mention. `:as` renames the result key (`pull_api.cljc:316`); `:default` fabricates a key for an *absent* attribute (`:361-365`); `:limit` defaults to **1 000** and truncates a cardinality-many result **silently** (`:16`, `:315`, `:323`); reverse attributes `:a/_b` produce keys no entity schema declares, and are single-valued exactly when the forward attribute is a component (`multi? (if forward? … (not component?))`, `:328-329`); recursion returns `{:db/id n}` for an already-seen entity (`:238-243`). Also, an unexpanded ref pulls as `{:db/id n}` **or** `{:db/id n :db/ident k}` (`db-ident-and-id`, `:297-302`). | The derivation must handle `:as`, `:default`, component-reverse cardinality and the `:db/ident` extra key, and must **refuse** — a typed refusal, not a guess — a selector using `:limit`, `:default` with recursion, or a recursion operator. The silent 1 000-member truncation is itself the project's named failure class living in the dependency: it belongs in the derived form's docstring and in a bounded-execution check at `seon.db`'s pull owner, not in a footnote. |
| **X6** | Design review §A.3 / D.3: "declare the real union and let the bridge codec store it" for the `-edn` attributes. | Right in intent, but the codec only engages on one shape. `edn-encoded-attr-in?` (`src/seon/schema/datahike.clj:346-368`) fires **only** when the resolved form's head is `:or`, it has no explicit `:seon.db/value-type`, and its children map to **more than one** Datahike type or at least one unmappable type. A `:map`, a `:vector` of maps, or a single-typed `:or` does not reach it. | Every `-edn` retype must be authored as a genuinely mixed `[:or …]`. A concern whose real shape is one map cannot use this path; it must either get an explicit `[:or … ]` union of its literal arms or stay a declared opaque string with a docstring saying so. |
| **X7** | Symbols inventory §4(a): flip the 13 declarations, "keep every existing property verbatim". | `:seon.render/ai` is not a flip — it is **live storage of 390 datoms as `:db.type/string`** produced by the codec in X6, because `[:or :qualified-symbol :string]` is a mixed union. Closing it to `:qualified-symbol` moves 390 datoms from canonical EDN strings to native symbols and takes the attribute *out* of the codec path. `:seon.render/html` (`[:or :qualified-symbol :seon.render/hiccup]`) and `:seon.render/form` (11 datoms) are in exactly the same position. | These three are not "symbol retypes" in the same class as `:seon.fn/sym`; they are **codec exits**, and they must publish with `seon.render`'s readers, which today receive a string and `read-string` it. Group them with the render-pair split, not with the program-identity symbols. |

---

## 1. What Datahike actually constrains — the lines every row below cites

| # | Constraint | Line opened | Why it matters here |
|---|---|---|---|
| D-a | `:db.type/symbol` is a first-class value type and a member of `:db.type/value` | `schema.cljc:31`, `:48` | Symbol retypes need no dependency change. |
| D-b | Nothing restricts `:db/unique` by value type; `::schema` takes `:db/unique` as an optional key of any attribute | `schema.cljc:77-78`; `db.cljc:837` validates only the *value* of `:db/unique` | `:seon.fn/sym` and `:seon.test/sym` may be symbol-typed unique identities. Live proof already installed: `:seon.ns/name` is `{:db/valueType :db.type/symbol :db/unique :db.unique/identity}`. |
| D-c | A lookup ref needs only `:db/unique`, then one `:avet` seek | `db/utils.cljc:109-138` | Symbol lookup refs resolve; no index property needed. |
| D-d | Range scans (`-index-range`) raise unless `:db/index true` | `db.cljc:287-289` | Prefix search over an identity attribute still needs `:seon.db/index`; unchanged by the retype. |
| D-e | `:db/tupleTypes` is validated as a vector of value types; the bridge derives it from the Malli `:tuple` children | `schema.cljc:167-171`; `src/seon/schema/datahike.clj:266-269` | A tuple member may be a symbol with no bridge change. Live proof: `:seon.error/frame` is installed as `[:db.type/symbol :db.type/symbol :db.type/string :db.type/long]`. |
| D-f | **Heterogeneous tuple validation is `(apply = …)`, not `every?`** | `transaction.cljc:1037-1039` | X4. An all-wrong tuple stores silently. |
| D-g | Homogeneous `:db/tupleType` caps at 8 members and checks only `(first v)`'s type after an `apply =` on member classes | `transaction.cljc:1020-1030` | The bridge never emits `:db/tupleType` (only `:db/tupleTypes`, `:266`), so this path is unreachable from Seon today. Do not introduce it. |
| D-h | **A ref inside a tuple is never resolved.** `transact-add` resolves `v` through `entid-strict` only when `(dbu/ref? db a-ident)`, and `ref?` asks whether the **attribute's** valueType is `:db.type/ref` (`db/utils.cljc:28-30`). A tuple attribute's valueType is `:db.type/tuple`. | `transaction.cljc:786-790`; `db/utils.cljc:28-30` | A `[ref long]` tuple stores an unresolvable raw value, is not swept by `retract-entity`'s incoming scan (which enumerates `(-attrs-by db :db.type/ref)`, `:1002-1013`), and does not expand under pull. `resources/seon/schemas/seon.fn.edn:62-70` already documents this from its own 2026-09-16 probe; I confirm it from the source. **No schema in the population declares a ref inside a tuple** (`grep` over all 55 resources). Keep it that way — the plan must not admit one. |
| D-i | `explode` emits one `[:db/add e a v]` per member; an empty collection emits nothing | `transaction.cljc:739-770`, `:718-737` | `#{}` and absence are byte-identical. G4 holds. |
| D-j | `maybe-wrap-multival` treats **any** non-map collection under a cardinality-many attribute as the member sequence | `transaction.cljc:718-737` | A cardinality-many **tuple** attribute handed a bare single tuple vector (`["a/b" 1]` rather than `#{["a/b" 1]}`) explodes into two scalar datoms and refuses at `validate-val`. This is a live foot-gun for the `call-arities` writer; name it in the regression. |
| D-k | `[:db.fn/call f & args]` is invoked with the **mid-transaction** database and its return is spliced in | `transaction.cljc:1153-1154` | The one legitimate place for a decision the writer must re-decide. But it cannot see "the caller submitted `#{}`" either — the empty set was erased by `explode` before the operation existed. §3 depends on this. |
| D-l | A throw inside a transaction function aborts the **whole** transaction; the writer's error branch `(recur old)` advances nothing | `transaction.cljc:1153-1154`; `writer.cljc:147-160`, `:201-218` | Refusals inside `:db.fn/call` are all-or-nothing, which is what a reset-era validator wants. |
| D-m | `validate-val` checks the value's declared **type** and nothing else; a ref to an entity with no datoms is legal | `transaction.cljc:33-52`, called at `:788` | Why `:schema-flexibility :write` (`src/seon/db.clj:92`) catches a string/symbol mix-up but never a dangling ref. |
| D-n | `retract-entity` retracts the entity's datoms, **every incoming ref datom**, and cascades `:db/isComponent` children | `transaction.cljc:998-1015`, `:831-836` | Owned by the sibling lanes; cited here only where a *non-deletion* row depends on it. |
| D-o | Pull: `:as`, `:default`, `:limit` (default 1 000, silent), reverse-ref cardinality `(not component?)`, recursion `{:db/id n}`, `{:db/id n :db/ident k}` | `pull_api.cljc:16`, `:297-302`, `:315-316`, `:328-329`, `:361-365`, `:238-243` | X5. |
| D-p | `:db/index` may be added monotonically to an existing attribute; **removing an index is unsupported**, and `:db/cardinality` one→many is prohibited while `:db/unique` is set | `schema.cljc:266-283` | Relevant only to a non-reset accretion. Every retype in this batch is reset-only anyway, so these are not blockers — but they are the reason a "just add the index later" plan is safe and a "drop the index later" plan is not. |
| D-q | The bridge's own EDN codec: canonical `pr-str` with sorted maps/sets, tagged literals for non-round-tripping keywords and symbols, validation on encode **and** decode, refusal of non-canonical stored EDN | `src/seon/schema/datahike.clj:399-453`, `:455-473`, `:579-595` | X6. This is a better mechanism than any of the 13 hand-rolled `-edn` attributes, and it already handles the symbol hazard the symbols inventory §1.6 raises — but only inside the codec path. |
| D-r | A `:seon.db/identity false` property is honoured because the bridge reads `(:seon.db/identity props)` and `false` is falsy | `src/seon/schema/datahike.clj:271` | N36's three negations work; they are inverted style, not a defect the database can see. |
| D-s | The whole-entity write validator rebuilds the row from the resulting datoms | `src/seon/db.clj:2895`, `:2942` | §3. |

---

## 2. Per-resource recommendations

Only resources where I have a verdict that differs from, sharpens, or confirms
a prior finding. **Live** = a datom count read from `default` on 2026-09-16.
"Confirms / overturns" names the prior finding, from R (design review), A
(audit), S (symbols inventory), P (pulled-shape study) or the integrator's plan.

### `resources/seon/schemas/seon.fn.edn`

| Key | Today | Recommended | Reason from writer/reader semantics + the constraining Datahike line | Prior finding |
|---|---|---|---|---|
| `:seon.fn/sym` | `[:string {:min 1, :seon.db/identity true, :seon.search/index :symbol, …}]`; **live** `:db.type/string`, `:db.unique/identity`, 5 114 datoms | `[:qualified-symbol {…same properties…}]` | D-a, D-b, D-c. The precedent is installed on the same database: `:seon.ns/name` is a `:db.type/symbol` identity. Keep `:seon.db/index` where a prefix scan exists (D-d). | Confirms S §1.1 item 1 and §3 |
| `:seon.fn/call-arities` | `[:set {…} [:tuple [:string {:min 1}] [:int {:min 0}]]]`; **live** `:db/tupleTypes [:db.type/string :db.type/long]`, 55 938 datoms, sample `["seon.run/wait" 1]` | `[:set {…} [:tuple :qualified-symbol [:int {:min 0}]]]` | D-e: the bridge derives `[:db.type/symbol :db.type/long]` with no change. Keep the value-tuple, **never** a `[ref long]` tuple — D-h, already documented in the docstring at `seon.fn.edn:62-70`. Regression must assert the stored member types because of D-f, and must transact a **set**, not a bare tuple, because of D-j. | Confirms S §1.1 item 3 and batch C 2(b); adds the D-f and D-j hazards |
| `:seon.fn/caller`, `/callee` | `[:string {:min 1}]` each | `:qualified-symbol` | Same fact as `call-arities`' members, in the arity-mismatch report. | Confirms S items 4–5 |
| `:seon.fn/keywords` vs `:seon.fn/writes` | `keywords` `:db.type/keyword` many, **live 35 970**; `writes` `:db.type/ref` many, **live 1 052** | Both `[:set {:seon.db/index true} :qualified-keyword]` | One file, one observation (a qualified keyword the analyzer saw), two encodings, and the two live counts differ by 34×. The keyword encoding is already the working one. Not a deletion-motivated retype: the join to `:seon.schema/key` survives because that identity **is** the keyword. | Confirms R N2 with the live asymmetry |
| `:seon.fn/unresolved-references` | `[:set :seon.fn/sym]`, **live** `:db.type/string` many, 317 | Retypes for free with `:seon.fn/sym` | It is declared *by reference* to `:seon.fn/sym`, so it inherits the change; no separate edit. Named because the plan's per-resource table does not list it. | New |
| `:seon.fn/pending-calls` | `[:set [:string {:min 1}]]`, **live 0 datoms** | Delete with the edges work (sibling lane) | Zero live datoms — the deletion is free in this population. Recorded as pricing evidence, not a verdict. | Pricing for E |
| `:seon.fn/form-span` | `[:tuple :int :int]`, **live** `[:db.type/long :db.type/long]`, 6 140 | Keep | The one tuple in the population whose members are both correct and order-carrying. Cite it, with `:seon.test.failure/contexts`, as the working precedent for N29. | Confirms R N31 |
| `:seon.fn/ast` | `[:seon.db/ref {:seon.db/component true}]`, **live** 1 140 component roots | See §2 `seon.fn.ast.edn` | | |

### `resources/seon/schemas/seon.test.edn`

| Key | Today | Recommended | Reason | Prior finding |
|---|---|---|---|---|
| `:seon.test/sym` | `[:string {…identity…}]` | `[:qualified-symbol {…}]` | D-a/D-b/D-c. | Confirms S item 2 |
| `:seon.test/reach` | `[:vector :seon.db/ref]`; **live** `:db.type/ref` many, **484 412 datoms** | `[:set :qualified-symbol]` (sibling lane owns the deletion argument); **the `[:vector …]` head is wrong either way** | The bridge maps `:vector`, `:set` and `:sequential` identically to `:db.cardinality/many` (`src/seon/schema/datahike.clj:195-211`) and Datahike stores cardinality-many as a set — the declaration promises an order the storage cannot keep. This is the **largest attribute in the population**; whatever the deletion lanes rule, declare it `[:set …]`. | Sharpens R A.5; the 484 412 number is new |
| `:seon.test/reach-digest`, `/reach-unknown` | digest `:string` optional, **live 588**; `reach-unknown` **live 194** | Require the digest on a recorded closure; delete `reach-unknown` only in the publication where a failed or absent reach is a typed refusal | 194 tests carry the sentinel today. Its own docstring confesses that absence of both membership and the marker is "legacy unknown, never proof of an empty closure" — D-i is why. The integrator's contradiction table already rules E §3 + G4 over R N15; I confirm it and add that the 194 rows must be re-derived or explicitly refused, not silently dropped. | Confirms the plan's resolution of R N15 |
| `:seon.test/failing-assertions` vs `/failures` | `failing-assertions` `:db.type/string` many, **live 305**; `/failures` `:db.type/ref` many component, **live 322** | Delete `failing-assertions`; the ordinal lives on `:seon.test.failure/ordinal` | The counts **differ by 17 on the live population** — the two representations have already drifted, which is the concrete evidence R B.3 predicted without measuring. | Confirms and measures R B.3 |
| `:seon.test/failure-identity` | `[:string {:min 64 :max 64}]` | Retype to the digest format, but **not** at the reset | **Live: not a stored attribute.** X2 — this is an API contract, so it publishes with its reader, not with a population change. | Overturns R N35's placement |
| `:seon.test/destructive-path` | `[:vector {:min 1} [:string {:min 1}]]` | `[:vector {:min 1} :qualified-symbol]` — **and confirm whether it is stored** | The members are function symbols (`src/seon/test.clj:218-262`). Ordering is load-bearing here, so if it is stored, the `[:vector …]` head has the D-i/A.5 problem and the path needs per-step ordinals or a single tuple. If it is not stored, it is a contract-only edit like X2. | Sharpens S item 11 |

### `resources/seon/schemas/seon.render.edn`

| Key | Today | Recommended | Reason | Prior finding |
|---|---|---|---|---|
| `:seon.render/ai` | `[:or :qualified-symbol :string]`; **live `:db.type/string`, 390 datoms** | `:qualified-symbol`, with the rendered-text meaning moved to `:seon.render/rendered` | X7. The `:string` branch is why the codec (D-q) engages and why 390 datoms are canonical EDN strings rather than symbols. This is a codec exit and must publish with `seon.render`'s readers, which today `read-string` the stored value. | Sharpens S item 13 and R B.12 with the live storage fact |
| `:seon.render/html` | `[:or :qualified-symbol :seon.render/hiccup]`; **live `:db.type/string`** | Split: `:qualified-symbol` for the declared renderer; Hiccup values under their own key | Same mechanism, same 390-datom class. The plan already says "preserve rendered HTML under its own Hiccup value key, not a string-only output slot" — this is the storage reason why. | Confirms the plan's B.12 resolution |
| `:seon.render/form` | `:qualified-symbol` property; **live `:db.type/string`, 11 datoms**, authored on 10 entity schemas | Retire as a third output selector; change the 10 declaring sites and the walk consumer in the same publication | 11 datoms — retirement is cheap. `AGENTS.md` §3 lists `:seon.render/form` as a **legacy spelling** while 10 schemas declare it; one of the two is drift and the reset is when that is settled. | Confirms R B.12; prices it |
| `:seon.render/rendered` | `[:or :string :seon.render/hiccup :seon.render/form]` | Declare the union honestly; **live: not stored** | X2 class — contract only today. If the render split makes it stored, it lands in the codec path (D-q) automatically, which is the right outcome. | New |

### `resources/seon/schemas/seon.issue.edn`

| Key | Today | Recommended | Reason | Prior finding |
|---|---|---|---|---|
| `:seon.issue/status` | `[:enum :open :resolved :superseded]`; **live 1 742 datoms: open 305, resolved 1 166, superseded 271** | **Keep until its replacement facts are written.** Then delete. | X1: `:seon.issue/resolved-tx` has **zero** datoms live, so the enum is not a mirror of anything — it is the sole authority. The review's "derive-or-die names this a defect on sight" is correct about the *design* and wrong about the *sequence*. Real example row: `{:seon.issue/id "a-blocking-realization-is-not-bounded-by-the-interrupt", :seon.issue/status :open, :seon.issue/opened #inst "2026-09-08T04:23:37Z"}` — no `resolved-tx`, no `superseded-by`. | **Overturns R N26's ordering**; confirms its target shape |
| `:seon.issue/opened` | `:inst`, **live 1 742** | Keep the instant **and** add the transaction fact; do not reinterpret | The plan already rules this ("never reinterpret an old date as the transaction that imported it"). I confirm from the data: all 1 742 issues carry an authored `opened` date, many of them imported, so an `opened-tx` would be the import transaction, a different fact. | **Overturns R N38**; confirms the plan |
| `:seon.issue/unresolved` | `[:set :string]`, **live 1 563 datoms** | Dissolves into "a cited token with no row", one `not-join` — but only after the citation retype lands | 1 563 datoms is the second-largest issue-family attribute. Deleting it before the cited-token values exist removes the only record of what did not resolve. Sequencing, not a veto. | Sequences R N5 |

### `resources/seon/schemas/seon.eval.edn` and `seon.cluster.eval.edn`

| Key | Today | Recommended | Reason | Prior finding |
|---|---|---|---|---|
| `:seon.eval/renderer` vs `/renderer-fn` | `renderer` `:db.type/symbol` **live 24**; `renderer-fn` `:db.type/ref` **live 24** | Delete `renderer-fn` | Exactly 24 and 24: a one-to-one duplicate, measured. The symbol already carries the fact. | Confirms R N6 with the 1:1 measurement |
| `:seon.eval/outcome` | `[:enum :ok :time :error]` | Delete — **but it is a contract edit, not a reset edit** | X2: not installed. | Corrects R N25's placement |
| `:seon.cluster.eval/author` | `[:enum :agent :system]`; **live 109 datoms: agent 40, system 69** | Delete; derive | The data-modeling skill forbids the stamp by name, and `src/seon/repl.clj:476` already derives the same answer from `:seon.eval/renderer` presence. But note the arithmetic: 24 `renderer` datoms against 69 `:system` — **the two mechanisms do not agree on this population**. The derivation must be settled and proven against these 109 rows before the stamp goes, or 45 evaluations change author silently. | **Sharpens R N24 / J1 with a live contradiction the review did not have** |
| `:seon.cluster.eval/read-basis-transaction` | optional `:db.type/long`; **live 109 datoms — one per evaluation** | **Require it**; it is G4's positive fact, already universally present | The since-diff reads `(when-not (seq (:seon.cluster.eval/read-evidence prior)) …)` (`src/seon/turn.clj:846`) and treats an empty evidence collection as "not a read" — D-i says that is indistinguishable from "never observed". The positive fact that fixes it is **already written on every row**, so requiring it costs nothing in this population. `read-evidence` is `:db.type/ref` many component, live 744. | **Confirms R N14 and prices it at zero** |
| `:seon.cluster.eval/triage-edn` | `:db.type/string`, **live 9** | Declare the real `[:or …]` union so the codec (D-q) owns it; drop `-edn` | X6: only a mixed `:or` reaches the codec. 9 datoms — free. | Confirms R N20 with X6's constraint |
| `:seon.eval/entity` vs `:seon.cluster.eval/receipt` | two declarations of one entity | One canonical stored declaration; the reader form derives (§4) | Confirms the audit's correction that the receipt map **is** selected by the whole-entity validator (`src/seon/db.clj:2683`); do not add a second validation path. | Confirms A |

### `resources/seon/schemas/seon.error.edn` and `seon.error.occurrence.edn`

| Key | Today | Recommended | Reason | Prior finding |
|---|---|---|---|---|
| `:seon.error/throwable-class` + `/exception-class` | string **and** symbol, both installed | One attribute, `:symbol`. Change `src/seon/error.clj:576`, `src/seon/bootstrap_drive.clj:365`, `src/seon/render/web.clj:2228` to `(symbol …)` in the same publication, and **fix `src/seon/db.clj:2398`**, which puts a string under the symbol-typed key. | X3. Under `:schema-flexibility :write` (`src/seon/db.clj:92`) that value refuses at `validate-val` (D-m) the moment a fault carrying it becomes a datom — a live defect on the fault-committing path, which is exactly where a refusal is worst. | **Corrects S §1.1 item 9; finds a real defect it missed** |
| `:seon.error.occurrence/process` vs `:seon.error/process` | `:db.type/ref` (live 2) and `:db.type/string` (live 2), on the same 2 entities | Keep one — and note they are **different types**, so "two attributes for one fact" understates it | The review found the duplication; the live read shows one is a ref and one is a string, so the two cannot even be compared without a join. | Sharpens R B.6 |
| `:seon.error/fn` | `:db.type/ref`, **live 1 datom** | Deletion argument belongs to the sibling lane; recorded here as pricing: **one datom** | | Pricing |
| `:seon.error/frame` | `[:tuple :symbol :symbol :string :int]`, **live `[:db.type/symbol :db.type/symbol :db.type/string :db.type/long]`, 0 datoms** | Keep | **The second installed proof that symbol tuple members work** (D-e), beside `:seon.ns/name`'s identity proof. Cite it in the `call-arities` regression. | New evidence for S §3.3 |

### `resources/seon/schemas/seon.instrument.edn`

`:seon.instrument/fn` is `[:string {:min 1}]`, **live 0 datoms**. Retype to
`:qualified-symbol` as S item 8 says; the retype is free in this population.
The nine `(str function-symbol)` coercions in `src/seon/instrument.clj` are the
real work, and they land in agent-visible errors, so the regression must read
the **rendered** error, not the datom.

### `resources/seon/schemas/seon.schema.edn`

`:seon.schema/namespace-name` → `:symbol` (unqualified; a namespace name has no
namespace). `:seon.schema/references` is `:db.type/ref` many — sibling-lane
territory; the non-deletion reason to change it is that the referent identity
`:seon.schema/key` **is** a keyword (`seon.schema.edn:43`), so the ref buys a
hop and costs a join. `:seon.search/index :symbol` on `:seon.schema/key`
**stays**: it selects the identifier tokenizer (`src/seon/search.clj:170-174`),
and the decisive evidence is that it sits on a `:keyword` attribute, so it
cannot be a string-type mirror. I confirm S §1.3 and **overturn**
`AGENTS.md:431-433` and batch C Part 2(a) — that AGENTS.md sentence is a defect
to fix in the same commit as the retype.

### `resources/seon/schemas/seon.fn.ast.edn`, `seon.fn.ast.entry.edn`

The Q2 grep re-run at HEAD (`git grep -c -E 'seon\.fn\.ast|:seon\.fn/ast' HEAD -- src/`):
**`src/seon/fn.clj` 4, `src/seon/program.cljc` 24, `src/seon/turn.clj` 1 — 29 lines, three files.**
This matches the integrator's count exactly and **refutes the design review's
literal "no production reader"** claim (`C.2`, `B.4`); `src/seon/fn.clj`'s
backfill reads AST presence and retracts it. **Live: `:seon.fn/ast` has 1 140
component roots.** My verdict on the non-deletion half:

- The family's *specific* defects — `:seon.fn.ast/type` as a `:string` where
  `:seon.schema.shape/type` is a `:keyword`, and the missing child ordinals
  (R N29) — should **not** be repaired. Repairing a family scheduled for removal
  is work a later commit erases, and the integrator's contradiction table
  already rules this. I confirm it.
- The one thing that must not be lost either way: `retract-entity` cascades
  components recursively (D-n, `transaction.cljc:831-836`), so the AST forest is
  destroyed with its function automatically. Nothing needs to be written to make
  the deletion safe; the work is entirely in the 29 reader/writer lines.

### `resources/seon/schemas/seon.maintenance.result.edn`

`:seon.maintenance.result/value :map` is **not a stored attribute** (X2), and
`/entity` declares exactly one member, the id. The correct edit is unchanged
from R C.3 — the root declares its component attributes so G5's
parent-with-components-expanded validation covers the tree — but the `/value`
row leaves the reset batch and becomes an ordinary contract edit.
`:seon.maintenance.receipt/handler` is `:db.type/ref`, **live 7 datoms**: the
plan's cross-resource rule to make it a `:qualified-symbol` value is right and
costs 7 rows.

### `resources/seon/schemas/seon.context.contribution.edn`

`:seon.context.contribution/evaluations` is `:db.type/ref` many with **0 live
datoms**, and `src/seon/context.clj:483` selects the block by `(seq …)` on it —
so that branch takes the empty arm for **every row in the population today**.
This is D-i at its worst: a selector that has never once taken its other arm.
`:seon.render.block/name` is `:db.type/keyword` with **1 123 live datoms**, so
requiring it on the capture-contribution arm is essentially free. The
integrator's resolution (require name/hash/tokens on the capture constructor,
keep `append-tx`'s distinct arm with agent required) is right; the live counts
are the reason it is safe.

### `resources/seon/schemas/seon.listen.edn`

`:seon.listen/entity` is an optional `:db.type/ref` with **exactly 1 live
datom**, and its docstring makes absence the **permissive** arm ("absent matches
any entity"). Under D-n, retracting the watched entity retracts the constraint
and the pattern silently widens from one entity to all. R N8 calls this the
worst instance in the population and I agree on severity — and the population
price of fixing it is **one row**. The integrator's "explicit listen constraint"
(group 4) should be pulled earlier purely because it is this cheap.

### `resources/seon/schemas/seon.effect.edn`

`:seon.effect/result-edn` and `/request-edn` are `:db.type/string` with
`:db/noHistory true`. Declare the real mixed `[:or …]` union so D-q's codec owns
them, and drop `-edn` — with X6's constraint: if the honest shape is one map,
the codec cannot take it and the attribute stays a documented opaque string.
`:seon.effect/content-blobs` has **0 live datoms**, so the plan's J7 resolution
(a set of attachments, no ordinals, from `src/seon/effect.clj:568` and `:350-354`)
costs nothing to adopt. I confirm it.

### `resources/seon/schemas/seon.fn.argument.edn`, `seon.schema.map-entry.edn`, `seon.fn.binding.entry.edn`, `seon.schema.shape.child.edn`

`:seon.fn.argument/order` and `/index` both have **4 648 live datoms** —
identical counts, which confirms the plan's N34 resolution from
`src/seon/program.cljc:691-692` (both written from `(long order)`). Keep
`order`, delete `index`.

The typed-sibling / `-edn` pattern is the largest `-edn` family:
`:seon.schema.map-entry/key-edn` has **8 053 live datoms**,
`:seon.fn.argument/label-edn` **304**. R C.4's target — one union attribute
through the codec plus a fingerprint for the join — is right, **subject to X6**:
the union must be authored as a mixed `[:or …]` of the literal arms (keyword,
string, symbol, boolean, int, double, uuid, inst, and an explicit container for
a nil-valued key), which it naturally is. The bridge's canonical ordering and
tagged-literal handling (D-q, `src/seon/schema/datahike.clj:399-453`) then gives
these 8 357 rows something the hand-rolled strings never had: a decode that
**refuses** non-canonical bytes (`:593-594`).

### `resources/seon/schemas/seon.ai.model.edn`, `seon.ai.attempt.edn`

`:seon.ai.model/deepseek-off-peak-windows` is `:db.type/ref` many with **3 live
datoms**; `/deepseek-pricing-schedule-status` is `:db.type/keyword` with **2**.
The plan's generic-name mapping is right and the population price is five rows.
`:seon.ai.attempt/usage-edn` is `:db.type/string` `noHistory` with **31 live
datoms** beside a fully declared `:seon.ai.usage/*` family — delete it, as the
review says; 31 rows.

### `resources/seon/schemas/seon.source.edn`

`:seon.source/digest` declares `:seon.db/identity true` **on the format alias**,
forcing three `{:seon.db/identity false}` negations. The bridge honours them
(D-r, `src/seon/schema/datahike.clj:271`), so this works and is a style
inversion, not a database defect. Split it anyway — format alias without
identity, identity declared at the identified use, as `:seon.activation/…`
already does. `:seon.fn.file/digest` is `[:string {:min 64 :max 64}]` and does
**not** constrain hex; that one is a real hole, and it becomes load-bearing the
moment G4's analysis-provenance digest is required on that seam.

### `resources/seon/schemas/seon.lint.edn`

`:seon.lint/fn` is `:db.type/ref` with **546 live datoms** — the largest of the
"handler reference" rows the plan's cross-resource rule sweeps into symbol
values. Named here because the plan mentions it without a number.

---

## 3. Required versus optional, and the empty-set question

**The honest answer in Datahike's terms: the audit's submission-time check
cannot work, and `[:db.fn/call …]` cannot rescue it either.**

`explode` erases an empty collection before any operation exists
(D-i, `transaction.cljc:739-770`). The whole-entity validator rebuilds the row
from the resulting datoms (D-s, `src/seon/db.clj:2895`, `:2942`). A transaction
function sees the **mid-transaction database** (D-k, `transaction.cljc:1153-1154`)
— which is the right place for a decision the writer must re-decide, but it
still never sees the submitted `#{}`, because that set was erased one step
earlier. So there is no seam in Datahike at which "the caller submitted an
explicit empty set" is observable after `explode`.

Therefore:

1. A submission-time check that requires `calls` "including `#{}`" is a
   pre-read the authority re-decides — AGENTS.md's owner law by name. It can be
   written, it will pass, and it proves nothing about the row that lands.
2. The only encoding that survives into the report is **a datom**. G4's
   positive analysis-provenance fact — the exact analyzed-input digest, required
   — is therefore the one honest construction, and `analyzed?` becomes presence.
   Once it is present, the final validator may normalize absent
   cardinality-many attributes to logical `#{}` **because the provenance datom
   proves the looking happened**; without it, absence stays unknown.
3. The integrator's contradiction table already rules this way (G4 + approved
   E §1 over S2's submission acceptance). **I confirm it from the dependency's
   source and add that the fallback position — a transaction function — is also
   closed**, which no document says.

The cheapest live instance is the one to lead with: **`:seon.cluster.eval/read-basis-transaction`
is already present on all 109 evaluations**, so the since-diff's
absence-as-state defect (`src/seon/turn.clj:846`) is fixed by requiring a fact
that is already universally written. That is a zero-cost proof of the pattern
before the expensive program-graph instance.

On the **15 keys optional only because one writer omits them** (audit,
"Optional because a writer omits it"): the rule that makes each one decidable
is the same. A key is optional because a *state* legitimately lacks it, or it
is optional because a *writer* is incomplete. The test is whether any surviving
row in the live population lacks it. Three measured here answer immediately —
`:seon.render.block/name` (1 123 datoms, present everywhere → require it),
`:seon.cluster.eval/read-basis-transaction` (109/109 → require it),
`:seon.context.contribution/evaluations` (0 datoms → the `(seq …)` selector has
never taken its other arm, so it is not a selector at all). The remaining
twelve need the same one-line count before the requirement is written; that is
a mechanical step for the implementing lane, not a design question.

---

## 4. Duplicate schemas — what ONE schema each should be

| Cluster | One schema | Reason |
|---|---|---|
| Two evaluation schemas (`:seon.eval/entity`, `:seon.cluster.eval/receipt`) | One canonical **stored** declaration under the evaluation owner; every reader names a **derived pulled form** under its own selector (§5) | The audit's correction stands: the receipt map is already selected by the whole-entity validator (`src/seon/db.clj:2683`), so this is a consolidation, not the creation of validation where there was none. |
| The pulled copies (`:seon.message/pulled`, `:seon.test.failure/value`, `:seon.render.transcript/pulled-transaction`, `:seon.wake/unanswered`, and the fifth the review found, `:seon.test.failure/report`) | Delete all five; derive | Each is a hand-maintained mirror of derivable state — AGENTS.md §2.2 "derive or die". The pulled-shape study's option (C) is already in the tree twice and drifted both times. |
| Three `seon.ai.model` entity maps | **Keep all three** — model, provider descriptor, pricing window are genuinely distinct entities | The defect is the twelve vendor-prefixed attribute keys, which is a table-picker by naming convention, not a duplicate entity. The plan's rename mapping is correct; population price is 5 datoms. |
| Thirteen maintenance results | **Keep the twelve operation components; fix the root** | They are different observations of different operations. The root declares one member, so G5 validation sees one key. `/value` leaves the reset entirely (X2). |
| capture / contribution / evaluation | **Keep all three** — parent, component, and a distinct family | `:seon.context.capture/contributions` is already a component set. The honest concern is `:seon.context.capture/prompt`, a materialized copy of a derivation — an owner decision (§5, second one). |
| `:seon.test.run/provenance` vs `/run` | Derive one from the other with `malli.util/required-keys` | Six keys each, differing only in optionality; after the audit's edit they become literally the same map. |
| Four "receipt" families | Rename all four to **evaluation** / **result** | These are declared attribute families, so the word reaches every agent's rendered context — which is why the vocabulary retirement has to reach `resources/seon/schemas/`, and the owner-decisions document does not list it. |

**Kind stamps versus attribute presence.** The population's stamps split three
ways, and only one group is wrong:

- **Delete (a table-picker):** `:seon.fn.binding/shape` (`:db.type/keyword`
  live) — `:symbol`/`:map`/`:sequential` pick which sibling attribute is
  present. One caveat the plan is right about and the review missed: an empty
  `{}` or `[]` binding has **no member datoms** (D-i), so the replacement must
  derive from the stored `binding`/`form` through the reader, never from
  collection presence.
- **Delete, but only after the replacement fact is written:**
  `:seon.issue/status` (X1), `:seon.cluster.eval/author` (the 24-vs-69
  contradiction above).
- **Keep — dependency facts or genuinely closed states:** `:seon.fn.ast/type`,
  `:seon.schema.shape/type`, `:seon.test.failure/type`, `:seon.lint/type`,
  `:seon.error/kind`, `:seon.fn/workload`, `:seon.turn/disposition`,
  `:seon.schema.admission/source`, and — explicitly, because they read like
  stamps and are not — `:seon.fn.binding.child/role` and
  `:seon.fn.binding.entry/spelling`, which are the Clojure reader's own
  destructuring grammar.

---

## 5. Decisions that genuinely require the owner's policy choice

Each is a choice semantics cannot settle. Recommendation first, two priced
options in prose, with a real row from `default`.

### 5.1 The issue lifecycle: does the reset carry 1 437 terminal states forward, or re-derive them?

**Recommendation: carry them forward — write `resolved-tx` and `superseded-by`
from the enum in the same publication that deletes the enum, and only then
delete it.**

Real row from `default`: `{:seon.issue/id "a-blocking-realization-is-not-bounded-by-the-interrupt",
:seon.issue/status :open, :seon.issue/severity :friction, :seon.issue/opened
#inst "2026-09-08T04:23:37Z"}` — and across the population, 305 open, 1 166
resolved, 271 superseded, against **zero** `:seon.issue/resolved-tx` datoms.

The first option is to treat the enum as the migration source: the publication
that introduces `superseded-by` and starts writing `resolved-tx` also derives
those facts for the 1 437 rows that have them, from the issue notes' own
frontmatter, which is where the enum came from. It costs the implementing lane
an extra pass over `docs/seon/issues/` and one regression proving a resolved
issue's terminal fact matches its note. What it buys is that the derive-or-die
edit does not itself destroy the only copy of the state it wants derived.

The second option is to delete the enum and let the issue indexer re-derive
every status from the notes on the next publication, accepting a window in which
the database says nothing about 1 437 issues. It costs nothing to implement and
is defensible under "database data is disposable by ruling" — the notes are the
durable record and the database is a projection of them. What we give up is that
the ranked schedule the owner reads (`bin/issues-index --check`) is blank until
the re-derivation runs, and any query written against status in that window
reads absence as health, which is the project's named failure class.

### 5.2 `:seon.context.capture/prompt` — evidence, or a materialized derivation?

**Recommendation: keep it, and drop `:seon.db/no-history? true` from it.**

The review's J6 already marks "keep", and the reason is sound —
`:seon.ai.tokens/characters` exists beside it precisely so the assembled prompt
can be calibrated against the provider's own token count. The decision the owner
still owns is the `no-history?` flag, because with history off the calibration
evidence cannot be read temporally, which is most of what calibration evidence
is for.

The first option is to keep the prompt and drop `no-history?`: every capture's
assembled prompt becomes a temporal fact, "what did the prompt look like three
resets ago" becomes an `as-of` query, and the storage cost is the full prompt
text per capture retained in history rather than overwritten. Against a store
that went from 21 GB to 102 MB across eight resets on 2026-09-17, prompt history
is the single largest thing this decision could regrow, and nobody has measured
it.

The second option is to keep `no-history?` and accept that the prompt is a
latest-value dial rather than a record — in which case the docstring must say
so, and the calibration claim has to be rewritten, because a dial cannot
calibrate a trend. That costs nothing and gives up the only reason the attribute
was defended.

### 5.3 `-at` instants versus transaction refs, for events the system itself causes

**Recommendation: transaction refs for database transitions; keep real instants
for observed external events — and write the rule into the docstrings, not just
into a plan.**

This is the plan's own resolution and I confirm it, but the owner's choice is
the *scope*, because the split is not obvious at the boundary. A real row makes
it concrete: `:seon.issue/opened` carries an authored date for all 1 742 issues
(the example above is `2026-09-08T04:23:37Z`), most of them imported from note
frontmatter written before the transaction that stored them. Reinterpreting that
as a transaction would be a lie; the plan says so.

The first option is the narrow rule: only attributes whose event is *by
construction* the transaction that records it become refs — effect settlement,
maintenance completion, turn closure, plan completion. Everything else keeps its
instant with a docstring naming what it observed. This is a small edit set and
leaves the families visibly mixed, which future readers will re-litigate.

The second option is a family-wide rule with an explicit exception list of
observed-external events, written once in AGENTS.md §3's vocabulary table so the
next reader does not have to re-derive it. It costs one more authority edit and
buys the thing the project keeps paying for: the rule living where the drift
would be caught.

### 5.4 The AST family: merge its role, or delete it outright

The plan's Q2 already prices this (option A ≈ one engineering day, option B ≈
half a day) and requests A. I have nothing to add on price, and one fact to add
on evidence: **the literal "no production reader" claim is false — 29 lines in
three files, confirmed at HEAD** — and `:seon.fn/ast` has **1 140 live component
roots**, so the cascade (D-n) removes the forest for free once the 29 lines go.
This stays on the owner's list because it is the only edit in the batch that
deletes a whole capability rather than a duplicate.

---

## 6. Corrected ordered publication list — deltas from the integrator's plan only

The plan's seven-group order is sound and I do not restate it. These are the
places my evidence moves a row:

1. **Out of the reset entirely** (contract edits; publish with their readers,
   no population change, no reset gate): `:seon.eval/outcome`,
   `:seon.maintenance.result/value`, `:seon.test/failure-identity`,
   `:seon.render/rendered`, `:seon.cluster.wake/offer-result`. All five are
   **not installed** on `default` (X2). Keeping them in a reset-gated group
   blocks five cheap edits behind the most expensive event in the batch.

2. **Pull group 4's `:seon.cluster.eval/read-basis-transaction` requirement and
   group 4's explicit listen constraint forward into group 1**, as the
   zero-cost proofs of the G4 pattern. `read-basis-transaction` is present on
   109 of 109 evaluations and `:seon.listen/entity` has exactly one datom; both
   are one-row changes that demonstrate positive-fact-over-absence before the
   484 412-datom `:seon.test/reach` change relies on it.

3. **Split the render pair out of the symbols group.** `:seon.render/ai` (390
   datoms), `/html` and `/form` (11) are **codec exits**, not symbol retypes
   (X7): they publish with `seon.render`'s readers, which today receive a
   canonical EDN string. Landing them with `:seon.fn/sym` mixes two unrelated
   storage changes in one incompatible publication.

4. **Gate `:seon.issue/status`'s deletion on its replacement writers** (X1) and
   **gate `:seon.cluster.eval/author`'s deletion on a derivation proven against
   the live 40/69 split** — the `:seon.eval/renderer` presence rule currently
   disagrees with the stamp on 45 of 109 rows. Both are group 4 today; both
   should be explicitly two-step within group 4, with the replacement fact
   written first.

5. **Sequence `:seon.issue/unresolved`'s deletion after the cited-token
   retype**, not with it: 1 563 live datoms, and it is the only record of what
   did not resolve.

6. **Add one row the plan's cross-resource rules do not name:** the four
   `:clj-kondo/unknown-namespace/*` symbols the symbols inventory found
   (§1.6). Native `:db.type/symbol` storage does **not** go through `pr-str`, so
   the database is safe; the hazard is confined to seams that print and re-read
   a program row. The bridge's own codec already handles exactly this case with
   a `seon.schema.datahike/symbol` tagged literal
   (`src/seon/schema/datahike.clj:438-440`) — so the fix is to normalize at the
   analyzer seam (`src/seon/fn/analyzer.clj`) **or** to route those seams
   through the existing codec, not to invent a third mechanism.

7. **Add to every regression that touches a tuple:** assert the stored member
   **types**, because D-f means Datahike accepts an all-wrong heterogeneous
   tuple silently; and assert that a cardinality-many tuple attribute is handed
   a **set**, because D-j explodes a bare tuple vector into scalar datoms.
   `:seon.fn/call-arities` is 55 938 live datoms and is the one attribute where
   both hazards apply.

8. **Add a bounded-execution row to the pulled-form work** (X5): Datahike's pull
   truncates a cardinality-many result at 1 000 members with no signal
   (`pull_api.cljc:16`, `:315`, `:323`). `:seon.test/reach` has 484 412 datoms
   across tests; any wildcard pull of a test row is already silently cut. The
   derived pulled form must refuse a `:limit`-bearing selector and the pull
   owner must report the cut as an elision naming the bound — AGENTS.md §2.3 and
   §2.4, in the dependency.

---

## Verification boundary

Read-only, in the `steward-platform` working tree at `8e74014d6`, with several
other lanes' uncommitted edits present (`git status` at entry lists
`resources/seon/schemas/seon.config.edn`, `seon.context.capture.edn`,
`seon.source.edn`, `src/seon/cluster.clj`, `src/seon/sci/eval.clj`,
`src/seon/schema/datahike.clj`, `src/seon/schema/edn.clj`, `src/seon/instrument.clj`,
`src/seon/render.clj`, `src/seon/test/runner.clj` and others). Line numbers are
the bytes I opened; a lane landing before the reset must re-anchor them.

Live numbers come from two read-only `mcp__seon__eval_clj` evaluations against
cluster `default` in jvm mode with explicit custody
(`(seon.operator/connection "default")`), reading `(:schema db)` and
`datahike.api/datoms :aevt` counts. No transaction, no mutation, no
publication, no gate, no test JVM, no `bin/seon` state change, no browser
observation. Both results were windowed by the MCP render profile; three
attributes in the first call and ten in the second were elided and are **not**
reported above — notably `:seon.test/subject`, `:seon.test.failure/file`,
`/reported-file`, `/report`, `/contexts`, `/seen-count`, `:seon.test/run`,
`/run-at`, `/run-basis-t` and `:seon.test.run/provenance`, for which I have no
live measurement and make no live claim.

Every recommendation about a writer's behaviour is a source reading, not a
measurement. The implementation lanes must prove them on the armed harness
after the one reset.
