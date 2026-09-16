---
type: research
status: active
date: 2026-09-16
tags: [schema, datahike, malli, pull, contracts]
---

# One entity schema, three grammars: the pulled-reference defect class

Read-only source study. Every claim below names a file and line I opened.
No test JVM was launched and no evaluation was performed: every question
below was answerable from checked-out source, so the one permitted
`mcp__seon__eval_clj` call was not spent.

Terms used here are the dependencies' own: Datahike **transaction data**,
**datom**, **pull result**, **selector**, **entity id**, **tempid**,
**lookup ref**, **upsert**, **component**; Malli **entity schema**,
**map entries**, **registry**, **`:registry` reference**, **validator**,
**`malli.util` schema transformations**. Seon's own names: **projection**,
**schema bridge**, **write validator**, **reader**.

## 0. The defect, stated once

`:seon.db/ref` (`resources/seon/schemas/seon.db.edn:11`, at HEAD) declares

```clojure
:ref [:or :int :string [:tuple :keyword :seon.db/lookup-ref-value]]
```

That is the grammar of a reference **in transaction data**. It is neither
the grammar of a reference **in a datom** (`:v` is always the entity id,
an int) nor the grammar of a reference **in a pull result**
(`{:db/id n}`, or `{:db/id n :db/ident k}`, or a nested map, or a vector of
those). One Malli key is asked to describe three different grammars, and
the three consumers of the entity schemas that reference it each meet a
different one. Every repair so far has been applied at the attribute that
happened to fail, so the class has been paid for five times and is not
finished.

## 1. Defects found

| # | Defect | Evidence |
|---|---|---|
| D1 | Five per-attribute widenings of one class. `:seon.cluster.eval/run` and `:seon.eval/renderer-fn` widened to `[:map [:db/id :int]]`; `:seon.test/run` and `:seon.message/pulled-reference` the same; `:seon.eval/origin` is the fifth and is failing live. | `resources/seon/schemas/seon.eval.edn:12`, `:30` (HEAD); `seon.test.edn:69` (HEAD); `seon.message.edn:185-190` (HEAD); commit `52044b4f4` |
| D2 | The widened schema is not even reached by the write path, so the patches bought nothing there and were pure reader appeasement. The attribute pass replaces any map or sequential ref value with `0` before Malli sees it; the whole-entity pass validates a row rebuilt from EAVT datoms, where every ref value is already an int. | `src/seon/db.clj:2774-2785` (`write-value`, `normalize`); `src/seon/db.clj:2895-2905` (`write-entity-value`); `src/seon/db.clj:2942-2990` (`write-entity-error`) |
| D3 | `:seon.eval/entity` carries **no** `:seon.db/attributes`, so `write-entity-schemas` never selects it and no evaluation row is ever validated as a whole entity on write. It is a reader-output schema wearing an entity schema's name. | `resources/seon/schemas/seon.eval.edn:5-8` (properties are `#:seon.render{:ai … :html …}` only); `src/seon/db.clj:2683-2702` |
| D4 | Expanded references are hand-mirrored per attribute per caller. `:seon.cluster.eval/ns` is declared `[:map [:seon.ns/name …]]` because one reader's default selector expands it; `:seon.test.failure/file` is declared `[:map [:db/id :int] [:seon.fn.file/relative-path …]]`; `:seon.render.transcript/pulled-transaction` is a hand-written `[:map [:db/id :int] [:db/txInstant …]]`. | `seon.eval.edn:18-20`; `seon.test.failure.edn:69`; `seon.render.transcript.edn:12-17` |
| D5 | `:seon.test.failure/value` is a thirteen-entry hand-copied mirror of `:seon.test.failure/failure`, differing only in dropped attributes and one expanded ref. A hand-maintained mirror of derivable state is a defect on sight under AGENTS.md §2.2 "derive or die". | `seon.test.failure.edn:35-58` vs `:58-72` |
| D6 | Three incompatible treatments of the same fact coexist. (a) widen the schema (D1); (b) normalize the value by hand at each reader — `seon.cluster.message/admitted-message` rewrites four endpoints one at a time; (c) validate against both shapes at selection — the renderer builds a transaction shape and a pulled shape and unions the matches. | (a) D1; (b) `src/seon/cluster/message.clj:550-575`; (c) `src/seon/render.clj:312-322`, `src/seon/render/value.clj:16-61` |
| D7 | The in-flight lane's new regression filters subjects on `(:seon.db/attributes (schema-properties form))`, which excludes `:seon.eval/entity` (D3) — the exact schema whose live refusal motivated the work. The class regression does not cover the reported instance. | `test/seon/schema_test.clj` working-tree hunk, `subjects` binding |
| D8 | A reader that assoc's its own keys onto a pull result produces a fourth grammar the entity schema must also absorb: `seon.eval/of-agent` adds `:t` after the pull, and its contract then demands `[:map [:db/id :int] [:t :seon.db/basis-t]]` on top of `:seon.eval/entity`. | `src/seon/eval.clj:87`; contract at `src/seon/eval.clj:19-24` |

## 2. Inventory of the class

### 2.1 Readers whose output contract names an entity-shaped schema over a pull

| Reader | file:line | Selector | Output contract | Satisfied by its own pull? |
|---|---|---|---|---|
| `seon.eval/of-agent` | `src/seon/eval.clj:9-24`, body `:27-29`, `:47-64` | default `'[* {:seon.cluster.eval/ns [:db/id :seon.ns/name]} {:seon.cluster.eval/read-evidence [*]}]`, forced to include `:db/id`, `:seon.cluster.eval/id`, `/run`, `/ordinal` (`:60-62`) | `[:vector [:and :seon.eval/entity [:map [:db/id :int] [:t :seon.db/basis-t]]]]` | **No.** `*` leaves `:seon.eval/origin` unexpanded → `{:db/id n}` against `:seon.db/ref`. `:t` is added by the reader, not by the pull (`:87`). |
| `seon.cluster.message/read` | `src/seon/cluster/message.clj:636-651` | `message-selector` (`:532-540`) expands `to`/`inbox`/`from`/`caused-by`, leaves `:seon.message/about` bare | `[:or :seon.message/message :seon.error/value]` (`:seon.message/message` has `:seon.db/attributes`, `seon.message.edn:69`) | **Only after hand normalization.** `admitted-message` (`:550-575`) rewrites the four expanded refs back to lookup refs; `:seon.message/about` survives as `{:db/id n}` and needed `:seon.message/pulled-reference` widened. |
| `seon.cluster.message/send!` | `src/seon/cluster/message.clj:733-753` | delegates to `read` | same | same |
| `my.message/read`, `/send!`, `/reply!` | `src/my/message.clj:27`, `:41`, `:57` | delegate | `[:or :seon.message/message :seon.error/value]` | inherited |
| `seon.note/add!`, `/forget!` | `src/seon/note.clj:255-258`, `:279-290` | `note-selector` (`:24-28`) expands both refs to `[:db/id]` | `[:or :my.note/note :seon.error/value]`; `:my.note/note` has `:seon.db/attributes` (`my.note.edn:9-17`) | **Only after normalization** through `note-row` → `note-value` → `seon.render.value/transacted` (`src/seon/note.clj:34-47`). |
| `my.note/notes`, `/add!`, `/forget!` | `src/my/note.clj:20`, `:35`, `:51` | delegate | `[:or :my.note/notes :seon.error/value]` | inherited |
| `seon.test/run-owned`, `/run` | `src/seon/test.clj:397-400`, `:480` | recorded result rows | `[:or :seon.test/result :seon.error/value]` | `:seon.test/result` (`seon.test.edn:54-74`) has **no** `:seon.db/attributes`; its `:seon.test/run` entry is a D1 widening. |
| `seon.render.transcript/agent-history` | `src/seon/render/transcript.clj:952-1005` | `history-run-selector`, pulls `:seon.turn/opened-tx`/`closed-tx` | `:seon.render.transcript/history` → `/run` → `/pulled-transaction` | Satisfied, by a hand-written pulled shape (D4). |
| `seon.turn` open/closed tx contract | `src/seon/turn.clj:196` | — | `[:or ::closed-tx [:map [:db/id :int]]]` | A sixth inline widening of the same class, written straight into a `:malli/schema`. |
| `seon.test/…` failure rows | `src/seon/test.clj:59` | — | `[:vector [:map [:db/id :int] [:seon.fn/sym …]]]` | Seventh, also inline. |
| `seon.program/…` | `src/seon/program.cljc:1037`, `:1045` | — | `[:map [:db/id :int]]` as an argument shape | Same spelling, used as input. |

### 2.2 Hand-written pulled shapes in `resources/seon/schemas/`

| Schema key | file:line | What it hand-describes |
|---|---|---|
| `:seon.db/ref` (lane edit) | `seon.db.edn:11-13` | the unexpanded-ref map, as a fourth alternative |
| `:seon.cluster.eval/run` | `seon.eval.edn:12` (HEAD) | unexpanded ref |
| `:seon.eval/renderer-fn` | `seon.eval.edn:29-30` (HEAD) | unexpanded ref |
| `:seon.cluster.eval/ns` | `seon.eval.edn:18-20` | one caller's **expanded** ref |
| `:seon.test/run` | `seon.test.edn:68-69` (HEAD) | unexpanded ref |
| `:seon.message/pulled-reference` | `seon.message.edn:185-190` (HEAD) | either spelling |
| `:seon.message/pulled` | `seon.message.edn:94-113` | a whole second copy of the message entity |
| `:seon.test.failure/value` | `seon.test.failure.edn:58-72` | a whole second copy of the failure entity, incl. expanded `file` |
| `:seon.render.transcript/pulled-transaction` | `seon.render.transcript.edn:12-17` | expanded transaction entity |
| `:seon.wake/unanswered` | `seon.wake.edn:30-39` | a query result carrying `:db/id` |

### 2.3 Size of the exposed surface

- `:seon.db/ref` is named **204 times** across `resources/seon/schemas/*.edn`
  (`rg -c "seon.db/ref" resources/seon/schemas/*.edn`, summed).
- **60** map schemas across **42** files carry `:seon.db/attributes true`
  (`rg -c ":seon.db/attributes" resources/seon/schemas/`), i.e. 60 declared
  entity schemas, each a potential reader-output contract.
- Instances repaired so far: **5** widenings + **2** inline widenings in
  `:malli/schema` metadata + **5** hand-written pulled shapes = **12**
  hand-written descriptions of one derivable fact.

## 3. How many shapes does one reference have?

Enumerated from Datahike source.

| Context | Spellings | Source |
|---|---|---|
| Transaction data | entity id (int); tempid (string); lookup ref `[:attr v]`; keyword resolved through `:db/ident`; nested entity map | `db/transaction.cljc:946-972` (`entity-map->op-vec` resolves `:db/id`), `:739-760` (`explode`), `:641-712` (`upsert-eid` resolves unique-identity attributes) |
| Datom | always the entity id | `db/transaction.cljc:786-790` (`transact-add` → `validate-val`), which never rewrites `v` for a ref |
| Pull result, no sub-selector | `{:db/id n}`, or `{:db/id n :db/ident k}` when the target has a `:db/ident` | `pull_api.cljc:351-357` (`as-value` = `db-ident-and-id`), `:299-302` (`db-ident-and-id`) |
| Pull result, sub-selector | the nested map that selector describes | `pull_api.cljc:333-337` (`:subpattern`) |
| Pull result, **component** ref, no selector | the **full** nested entity map, unasked — components auto-expand | `pull_api.cljc:345-349` (`expand-frame`) with the wildcard `PullSpec` at `:290-296` |
| Pull result, cardinality-many | a **vector** of whichever of the above applies | `pull_api.cljc:354-357` (`single? (not multi?)`) |
| Pull result, recursion | a recursive pull | `pull_api.cljc:339-343` |
| Already-seen entity under recursion | `{:db/id n}` | `pull_api.cljc:240-243` (`pull-seen-eid`) |
| Reader-added keys | e.g. `:t` | `src/seon/eval.clj:87` |

### 3.1 Is `{:db/id n}` a legal reference spelling in transaction data?

**Yes, and it asserts exactly the link and nothing else.** Derived from
source, no evaluation needed:

1. `explode` (`db/transaction.cljc:758-759`): for a ref attribute whose
   value is a map, it emits
   `(assoc v (dbu/reverse-ref a-ident) eid)` — the nested map with a reverse
   ref added. For `v = {:db/id n}` under `:seon.eval/origin` on outer entity
   `e`, that is `{:db/id n, :seon.eval/_origin e}`.
2. That map re-enters `entity-map->op-vec` (`:946`). `:db/id` is a number,
   so `resolved-eid = n` (`:952`) and `new-eid = n` (`:962-967`).
   `upsert-eid` (`:641`) finds no unique-identity attribute in the map, so
   it contributes nothing.
3. `explode` of that map skips `:db/id` (`:746`), sees `:seon.eval/_origin`
   as `reverse? true` (`:750`) and emits `[:db/add e :seon.eval/origin n]`
   (`:760-761`).

One datom, the link, nothing new asserted. Two caveats, both from the same
source:

- If the pulled map is `{:db/id n :db/ident k}` (`pull_api.cljc:301`), the
  same path additionally emits `[:db/add n :db/ident k]`. The value is
  already that, so no new datom results, but `check-schema-update`
  (`db/transaction.cljc:924-944`) runs on it; it raises only for system
  keywords or incomplete schema entities, neither of which applies to a
  re-assertion of an existing `:db/ident`.
- `validate-val` (`db/transaction.cljc:33-52`) checks only the value's
  **type** against the installed schema. A dangling `n` is admitted exactly
  as a dangling bare int already is. Admitting the map form adds no new
  dangling-reference exposure.

## 4. What the dependencies and Seon already provide

### Malli

`malli.util` offers schema-to-schema transformations: `merge:53`,
`union:103`, `update-properties:114`, `closed-schema:128`,
`open-schema:148`, `subschemas:168`, `transform-entries:238`,
`optional-keys:246`, `required-keys:258`, `select-keys:271`, `assoc:330`,
`update:337`, `assoc-in:360`, `update-in:368`
(`reference-code/malli/src/malli/util.cljc`). There is **no** built-in
notion of a "projection" of a map schema; `transform-entries` plus
`select-keys` is the idiom one would compose. `malli.transform` is a value
transformer, not a schema transformer, so it cannot produce a contract.

### Datahike

`api/specification.cljc` declares `pull` (`:643-658`) with `:doc` and
`:args`/`:ret` categories but no schema of the pull **result**. It does,
however, already expose the machinery a derivation needs:
`compile-pull-plan:194`, `pull-plan-selector:207`, `pull-plan-spec:218`,
and `pull-dependency-plan:660`, whose docstring is the exact statement of
the problem: *"A database value resolves component-ref expansion precisely;
without one, bare forward attributes widen conservatively."*
(`specification.cljc:672`).

### The one vendored Malli+Datalog precedent

`reference-code/malli-datomic/src/blasterai/malli_datomic/entity_tools.cljc`
exists for exactly this: *"Conform entities pulled out of a Datomic DB to
their canonical form"* (`:1-3`). Its API, `to-standard-form:47`, takes the
Malli schema and **returns a function that normalizes the pull result** —
it derives a normalizer from the schema and leaves the schema describing
the stored entity. It does not widen the schema.
`reference-code/spectomic` derives Datomic schema from specs only and has
nothing to say about pull results.

### Seon

The bridge already derives, per attribute, from the projection, every fact
a pulled-shape derivation needs:

| Fact | Owner |
|---|---|
| is this attribute a reference? | `schema.datahike/form->datahike-value-type-in`, `src/seon/schema/datahike.clj:123-130` (`:seon.db/ref` head → `:db.type/ref`) |
| cardinality one or many? | `form->cardinality-in`, `src/seon/schema/datahike.clj:205-211` |
| the child form of a collection | `form->child-form-in`, `src/seon/schema/datahike.clj:220-226` |
| is it storable at all? | `storable-attribute-in?`, `src/seon/schema/datahike.clj:299` |
| resolve a registry alias to a form | `resolve-malli-form-in:25`, `resolve-datahike-form-in:81` |
| memoize a derivation on the projection | `schema/projection-cache-value`, `src/seon/schema.clj:264-283` |
| install a derived key into a projection | `schema/projection-with-schema`, `src/seon/schema.clj:2513` |
| compile a validator for a key | `schema/projection-validator:3063`, `projection-explainer:3149` |

There is **no** function that turns an entity schema plus a selector into
the Malli schema of that pull's result. There are three partial
substitutes, none of them a derivation of the schema:

- `seon.render.value/transacted` (`src/seon/render/value.clj:16-61`) —
  normalizes the **value** back to transaction shape, using the installed
  Datahike value type and cardinality as authority in its two-argument
  arity. This is Seon's own `to-standard-form`, already correct, already
  database-driven, and used by exactly two callers.
- `seon.render/transaction-shape` + `schema-producers`
  (`src/seon/render.clj:193-208`, `:312-322`) — validates against the
  transacted shape **and** the raw pulled shape and unions the matches,
  with the comment "A pull has two honest shapes".
- `seon.cluster.message/admitted-message`
  (`src/seon/cluster/message.clj:550-575`) — a hand-written normalizer for
  four attributes of one entity.

### Does the instrumentation admit a computed contract?

Partly, and not in the way option (B) would naively need. `arm-var!`
(`src/seon/instrument.clj:618-650`) takes the contract from
`(:seon.schema.projection/function-contracts projection)`, falling back to
the var's authored `:malli/schema`; `contract-definitions` (`:580-601`)
resolves its direct references out of the projection's forms, and
`current-wrapper?` (`:603-615`) compares contract and definitions by
equality to decide re-arming. So a contract is a **data form resolved
against the projection**, and a derived schema is admissible *as a
registered projection key that the contract names* — re-arming on change is
already the declared behavior. A `:malli/schema` whose metadata form is
computed at `def` time is not a route: it would run before any projection
or database exists.

## 5. Options

### (A) Declare the nested-map spelling once in `:seon.db/ref`

**Guarantee.** Every unexpanded pulled reference validates everywhere,
under every entity schema, by one four-line edit; the five per-attribute
widenings and the two inline ones (`src/seon/turn.clj:196`,
`src/seon/test.clj:59`) can be deleted; it is grounded in `explode`
(§3.1), so the admitted spelling is a real Datahike transaction form.

**Cost.** One edit, one class regression.

**What it does not fix.** Expanded references (D4) — `:seon.cluster.eval/ns`,
`:seon.test.failure/file`, `:seon.render.transcript/pulled-transaction`
stay hand-mirrors of one caller's selector. Component references, which
Datahike expands **without being asked** (`pull_api.cljc:345-349`), still
need a hand-written nested shape wherever a reader pulls a component.
Cardinality-many pulled references still arrive as vectors of maps and
still depend on the enclosing `[:set …]`/`[:vector …]` being written by
hand. The whole second-copy schemas (D5, `:seon.message/pulled`,
`:seon.test.failure/value`) remain. And the contract still cannot say
which of the shapes a given reader actually returns: a reader that expands
`:seon.eval/origin` and one that does not now share one contract that
accepts both, so neither is checked.

**Stricter or looser?** **Neither, on the write path.** §1 D2 is the
measurement: the attribute pass replaces any map ref value with `0` before
Malli (`src/seon/db.clj:2777-2779`) and the whole-entity pass validates a
row rebuilt from datoms whose ref values are ints
(`src/seon/db.clj:2895-2905`). No transaction-data map ever reaches the
`:seon.db/ref` alternatives as a whole-entity value, so widening them
admits nothing that was previously refused. On the **reader** path it is a
genuine loosening of the contract's precision, as described above: it
converts "this attribute is an entity id" into "this attribute is an
entity id or some map with a `:db/id`". Against the owner's priority —
"the right data is required, everything entering the database is validated,
errors are loud" — (A) costs nothing entering the database and costs
precision on the way out.

### (B) Derive the pulled form of an entity schema under a selector — **recommended**

**Shape.** One function in `seon.schema`, named in Malli's and Datahike's
words, e.g. `seon.schema/pulled-form-in [projection schema-key selector]`,
returning the Malli form of that pull's result:

- `:db/id` present iff the selector names it or is a wildcard
  (`pull_api.cljc:372-377`, `:450-455`);
- a reference with no sub-selector → `[:map [:db/id :int]]`;
- a reference with a sub-selector → the target entity schema's pulled form
  under that sub-selector;
- a **component** reference with no sub-selector → the target's pulled form
  under the wildcard, because Datahike expands it anyway
  (`pull_api.cljc:345-349`);
- cardinality-many → the enclosing collection form from
  `form->cardinality-in` / `form->child-form-in`;
- everything else unchanged from the entity schema.

Registered into the projection through `projection-with-schema:2513`,
memoized through `projection-cache-value:264`, and named by reader
contracts the way any other registry key is — `arm-var!` already resolves
contracts out of the projection (`src/seon/instrument.clj:618-625`) and
re-arms on change (`:603-615`).

**Guarantee.** Entity schemas describe **only** the stored entity, so the
write validator gets *stricter* by deletion of the widenings; every reader
contract states exactly the shape its own selector produces, so
`of-agent`'s expansion of `:seon.cluster.eval/ns` and non-expansion of
`:seon.eval/origin` are both checked instead of both tolerated; all twelve
hand-written descriptions in §2.2 are deleted; the answer cannot drift,
because it derives from the same projection the bridge derives Datahike
facets from. It is the Seon-shaped version of what the one vendored
precedent does (`entity_tools.cljc:47`).

**Cost.** One new function plus its class regression; each affected
reader's contract rewritten to name its derived key; selectors that are
built at call time (`of-agent` merges required attributes into the caller's
selector, `src/seon/eval.clj:60-62`) must hand the derivation the same
selector they hand the pull — that is the one place where getting it wrong
reintroduces a mismatch, and the honest construction is to derive the
selector once and use the same value for both.

**What we give up.** A reader whose selector is genuinely dynamic per call
cannot name a static derived key; its contract falls back to
`:seon.db/pull-result` generality, or the reader is changed to a fixed
selector. Two readers today have fixed selectors and one (`of-agent`) has
a defaulted-but-caller-overridable one.

### (C) One `:seon.<kind>/pulled` schema per entity, hand-declared

**Reject.** This is already in the tree twice and it did not stop the
defect: `:seon.message/pulled` (`seon.message.edn:94-113`) and
`:seon.test.failure/value` (`seon.test.failure.edn:58-72`) are exactly
this, and both are hand-copied mirrors that drifted — `:seon.message/pulled`
still needed `:seon.message/pulled-reference` widened per attribute
(D1), and `:seon.test.failure/value` hand-writes its expanded `file` ref
(D4). It is the per-attribute patch re-spelled at entity granularity: 60
entity schemas would become up to 120 declarations, each a hand-maintained
mirror of derivable state, which AGENTS.md §2.2 "derive or die" rules a
defect on sight. It also cannot express what a *selector* changes, which is
the actual variable.

### Sequencing

(A) and (B) are not alternatives. (A) is the correct **first** step and (B)
is the dissolution: land (A) to stop the live refusal, then let (B) delete
`[:map [:db/id :int]]` from `:seon.db/ref` again, because under (B) an
entity schema never describes a pull result and no longer needs it.

## 6. The in-flight lane `pulled-ref-is-a-ref`

Working-tree diff read on `resources/seon/schemas/seon.db.edn`,
`seon.eval.edn`, `seon.message.edn`, `seon.test.edn`,
`src/seon/render/transcript.clj`, `test/seon/schema_test.clj`,
`test/seon/render/web_test.clj`, and the issue note.

**It is a correct first step toward (A), and must not be replaced.** It
does the right four things: adds `[:map [:db/id :int]]` as a fourth
alternative of `:seon.db/ref` with a description grounded in
`transaction/explode`; reverts `:seon.cluster.eval/run` and
`:seon.eval/renderer-fn` to their bare declarations; reverts
`:seon.test/run`; collapses `:seon.message/pulled-reference` to
`:seon.db/ref`. Its schema-test edit at `schema_test.clj:687-737` correctly
rewrites the stale assertion that `:seon.db/ref` "admits no entity map".

Four things it should carry before it lands:

1. **D7 — its class regression does not cover the reported instance.** The
   `subjects` filter requires `(:seon.db/attributes (schema-properties form))`,
   and `:seon.eval/entity` has no such property
   (`resources/seon/schemas/seon.eval.edn:5-8`), so
   `pulled-references-satisfy-every-declared-entity-contract` skips the one
   schema that is failing live at `[17 :seon.eval/origin]`. The filter
   should select map schemas with reference-typed entries, whether or not
   they are storable, and the transact-and-pull half should apply only to
   the storable ones.
2. **Two inline widenings are left behind**: `src/seon/turn.clj:196`
   (`[:or ::closed-tx [:map [:db/id :int]]]`) and `src/seon/test.clj:59`.
   They are the same patch written in `:malli/schema` metadata and are now
   redundant.
3. **The transcript change is a different defect.** The `try`/`catch` around
   `render/acquire-context!` in
   `src/seon/render/transcript.clj:1408-1425` converts a thrown contract
   violation into a rendered `seon.error/diagnostic`. That is a
   presentation repair of the symptom (the 500 on the prompt panel), not
   the pulled-reference fix, and it makes the page report
   "Session prompt unavailable" for *any* throwable — including the next
   contract defect of this class. Under AGENTS.md §2.4 it is defensible as
   a total boundary, but it should be a separate commit with its own
   justification, and it should not be allowed to mask the class the same
   branch is fixing.
4. **The issue-note addendum records an unavailable probe**, which is the
   honest thing to do, but the facts it wanted (the carried projection's
   `:seon.db/ref` form, the bridge's ref type, a pulled origin) are all
   readable from source — `seon.db.edn:11`,
   `schema/datahike.clj:123-130`, `pull_api.cljc:351-357` — and did not
   need the cluster.

## 7. What I did not do

No test JVM, no `bin/test`, no `bin/test-fast`, no cluster operation, no
evaluation. Every statement about Datahike behavior in §3 is read from
`reference-code/datahike/src/datahike/` at the checked-out gitlink;
§3.1 in particular is derived from `explode`, `entity-map->op-vec`,
`upsert-eid` and `validate-val` rather than observed, and a live
`datahike.api/with` on a throwaway in-memory database remains the cheapest
confirmation if one is wanted before (A) lands.
