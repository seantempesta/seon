---
type: design
status: "Opus revision 3: Astra REVISE 93b519229 folded; root rulings Q1-Q3 and the reversed Q4 (no face fact: face derives from the output schema) folded; §0 findings filed as issues; ready for Astra re-review; no source edited"
created: 2026-09-25
scope: Which program relations become Datahike refs with a named target, produced at the one analysis producer (var-row and its siblings), validated at the final writer and followed by the render walk; which stay values and why; which relations are live deletion obligations; the render index
source-pin: HEAD 757964097; Datahike 4157916b; Malli befcd302a. REPL P1-P9 on default pid 59566 (old store), P10-P14 on fresh default pid 39484
aligned-with: parser-proves-the-spec-2026-09-25.md; language-requirements-2026-09-24.md §§1.9-1.15, 5a, 7, 11, 12; task-context-install-design-2026-09-25.md (revision 4, §4.1 interim value edges); datahike-deletion-and-the-program-graph-2026-09-16.md; unbreakable-connections-2026-09-16.md
review: references-as-refs-design-review-2026-09-25.md (93b519229)
---

# References as refs: one producer names every link

**What exists.**
- The one analyzer is clj-kondo. Its analysis already carries every link: calls, references, keywords,
  `:seon/tasks`, `:seon/must-read`, `:seon/see-also`, seeds and docstrings.
- The producers turn that analysis into canonical rows: `seon.fn/var-row` (`src/seon/fn.clj:683`), `namespace-row`
  (`:226`), `task-links` (`:616`), `seon.program/contract-facts` (`src/seon/program.cljc:1072`) and
  `seon.schema/canonical-schema-rows` (`src/seon/schema.clj:3700`).
- In a canonical row a ref is already written as its target's identity lookup ref, `[:seon.ns/name n]`. The writer
  (`declaration-value`, `src/seon/db.clj:4224`) and the portable exporter (`published-index-rows`, `fn.clj:3597`)
  reproduce that form, and every definition digest is computed over it.
- Which attribute names which identity is recorded in six separate places (§1c).

**The principal Clojure approach.**
- One schema property, `:seon.db/reference-to`, names the **direct target identity** of every attribute that names
  another entity. It carries no other meaning.
- Which relations are **live deletion obligations** is not a new flag. It is already decided by row ownership: the
  attribute belongs to a program declaration family, or to a component owned by one, and `seon.program/shapes-in`
  (`program.cljc:255`) is the one answer to that question.
- Storage is chosen per relation:
  - a ref where the producer can prove the target exists in the candidate world, namely contract keys, schema uses
    and schema render pairs;
  - a value for an observed token: an external Var, a function not yet written, an authored link that may name a
    path or a dependency, or evidence that outlives its target.
- Invocation (reach) stays with `:seon.fn/invokes`. Issue citation stays within the issue schema. Neither is inferred
  from `reference-to`.

## 0. Broken things first

- **Default was replaced twice during this lane.** Root replaced the JVM at 21:53Z and later swapped in a fresh
  store (pid 39484).
  - P1–P9 were measured on the old store. P10–P14 were measured on the fresh one.
  - On the fresh store, entity-id history is trivially stable (1,611 transactions), so the history-stability half of
    P10 proves nothing and is not counted as evidence.
  - Every probe was read-only or ran under `datahike.api/with`, and this lane created no branches.
- **Six places record "attribute names identity", and they disagree** (§1c).
  - `:seon.fn/must-read` and `:seon.fn/see-also` are in no deletion obligation.
  - The first revision said `:seon.test/subject` was missing too. That was wrong: `removed-definition-error` lists it
    (`db.clj:4086`).
- **Docstring links already point at deleted functions** (P13).
  - Function docstrings on the fresh store hold 44 `[[x]]` links, and 41 resolve in the holder's namespace.
  - The three that do not are `seon.schema/restore!`'s `[[snapshot-state]]` and `[[restore-state!]]`
    (`schema.clj:3663`), which name functions that no longer exist, and `seon.ai.tokens/estimate`'s
    `[[shipped-calibration]]`.
  - Namespace docstrings hold 4 more links, which were not resolved.
  - Nothing ingests these links today, so the rot is invisible.
  - Filed as `docs/seon/issues/docstring-links-name-deleted-functions.md`. `src/seon/schema.clj` is held by a running
    lane, so the lane did not edit it; S1 repairs it in the same cut that lands the refusal.
  - `shipped-calibration` exists as a Var, a computed `def` (`src/seon/ai/tokens.cljc:62`), but the fresh store has
    no row for it, while its 10 siblings have rows. Filed as
    `docs/seon/issues/a-computed-def-in-seon-ai-tokens-has-no-program-row.md`.
- **A tempid that names nothing is admitted silently** (P6). `{:db/id "a" … :probe/calls #{"nobody"}}` stored a ref to
  an entity with 0 datoms. Datahike checks only the value's type (`db/transaction.cljc:34-53`).
- **56 first-party callee names have no row** (P3, old store). Examples are `def` aliases
  (`seon.render/same-call-cache-evidence?`, `render.clj:897`), `script/` namespaces and `def`s of objects. The Var
  population is not the row population.
- **Schema dependency ordering does not terminate on a cycle** (`dependency-first-schema-keys`, `schema.clj:3681`).
  - The helper adds a key to `seen` only after visiting its dependencies, so a cycle recurses until the stack
    overflows.
  - P12: the fresh store has 1,951 schemas that reference others, with 0 cycles and 0 self-references, so nothing
    fires today.
  - S2 must refuse a cycle by name (§3b). Filed as `docs/seon/issues/schema-dependency-ordering-overflows-on-a-cycle.md`,
    since `schema.clj` is held.

## 1. Inventory: attributes that name another entity

### 1a. Program attributes (P2 old store; P10 fresh store)

"Resolve" means an identity row carries the value. "Lifecycle" is what surviving declarations do when the target is
removed:
- **blocks** means removal refuses while a surviving declaration still names the target;
- **observation** means the value survives and never blocks;
- **sweep** and **refuse** are Datahike's optional and required ref behavior.

| Attribute | Holder family | Stored | Datoms / distinct / resolve | Why a value today | Storage verdict | Lifecycle |
|---|---|---|---|---|---|---|
| `:seon.fn/calls` | fn, test rows | symbol, many, indexed | 65,605 datoms (fresh); old store 65,935 / 5,032 / 4,299, of which 677 names are external | 0e8f7d323, deletion study D1–D6 | value | blocks (a program declaration) |
| `:seon.fn/references` | fn, test | symbol, many, indexed | 33,419 / 2,673 / 1,529 (old) | "Names survive target retraction" (0e8f7d323) | value | blocks |
| `:seon.fn/unresolved-references` | file row | symbol, many | 2,414 / 564 / 262 (old) | unresolved by definition | value | observation (no `reference-to`: the targets are unknown) |
| `:seon.fn/keywords` | fn, test | keyword, many | 42,894 / 5,930 / 2,303 (old) | literal mentions | value | none: not a reference, so no `reference-to` |
| `:seon.fn/writes` | fn | keyword, many | **P10: 1,107 / 291 / 236**. Unresolved: `:db.unique/identity`, `:datahike/expected-basis-t`, `:seon.config/desired-row` | analysis observes written keys, including installed attributes and tx-meta keys | value, `reference-to #{:seon.schema/key :db/ident}` | blocks, for both schema rows and installed attributes (today's `db.clj:4096` case) |
| `:seon.effect/capability` | fn | symbol, indexed | 10 (old) | metadata token | value | blocks, fn holders only (today's `db.clj:4099-4100` condition) |
| `:seon.fn/must-read`, `:seon.fn/see-also` | fn, ns | symbol, many, indexed | 0 on both stores (no source annotation outside tests) | `task-links` resolves them through aliases. Authored targets include external Vars (`task_ingestion_test.clj:24`: `` `s/join ``) and paths | **value** (changed from revision 1) | blocks for program targets |
| docstring `[[x]]` links | fn, ns | not ingested | P13: 44 fn-doc links, 41 resolve; 4 ns-doc links | — | value, in `:seon.fn/see-also` (§3e) | blocks for program targets |
| `:seon.fn.arity/input-refs`, `output-refs` | arity (component of fn) | keyword, many, indexed | **P10: inputs 3,048 / 575 / 575**; old outputs 2,270 / 586 / 586 | 0e8f7d323 swept them along; no reason of their own was recorded | **ref** under new names | blocks, through the owning fn |
| `:seon.fn.arity/guard-refs` | arity | keyword, many | **P10: 0 datoms** | same | ref under a new name | blocks |
| `:seon.schema/references` | schema row | keyword, many | 5,841 / 2,479 / 2,479 (old) | same sweep. `canonical-schema-rows` refuses a missing target (`schema.clj:3702`) | **ref** under a new name | blocks |
| `:seon.render/ai`, `:seon.render/html` stored on schema rows | schema row | EDN-string codec | 334 / 50 renderers / 50 have rows (old) | the `:or` falls to the codec (`schema/datahike.clj:113-121`) | **ref** under schema-specific new names | blocks |
| `:seon.ns/requires` | ns | symbol, many | 4,334 / 217 / 111 (old); 106 external | external namespaces | value | blocks for program targets |
| `:seon.fn/defined-by` | fn | symbol | 4,928 / 8 / 0 | grammar token (`clojure.core/defn`) | value | none (no `reference-to`) |
| `:seon.test/subject` | test | symbol, indexed | 0 (fresh) | test-first: a test may name a function not yet written | value | blocks once the subject exists (today, `db.clj:4086`) |
| `:seon.test/reach` | `:seon.test/result` (data) | symbol, many | 0 | execution evidence | value | observation: invalidates proof reuse, never blocks |
| `:seon.task/tasks` | fn, ns, test | string handle, many | P10: 8 / 3 / 3 | ruled in 7.2: tasks are data and never ride program merge | value, `reference-to #{:seon.task/id}` | destination-gate dangling check (7.5, installed `seon.issue/candidate-dangling`), not a program deletion obligation |
| `:seon.task/must-read` | task (data) | symbol, many | 7 / 7 / 6 (old) | 7.4: EDN may name a subject that does not exist yet | value | observation |
| `:seon.fn/ns`, `:seon.test/ns`, `:seon.fn/file` | fn, test | ref | installed | — | ref | required members; portable through `published-index-rows` |
| `:seon.schedule.task/function` | schedule task (data) | required ref | 3 holders | — | ref | a required ref refuses the sweep. Invocation comes from `schedule.clj:630` `{:seon.fn/invokes #{:seon.schedule.task/function}}` |
| `:seon.issue/functions` … `issues` | issue (data) | ref | installed | `:seon.issue/cites` | ref | the issue's own required/optional and retention rules; not program obligations |
| `:seon.issue/files` → `:seon.issue.citation/file` | issue → owned citation → file | two refs | installed | the `cites` target sits on the outer edge today | refs | component and ref |
| `:seon.agent/task` (proposed by task-context) | agent (data) | optional ref | — | — | ref | sweep |
| `:seon.error/operation`, `:seon.instrument/fn`, `:seon.eval/renderer` | error, instrument, eval (data) | symbol, indexed | 133 / 133 / 11 (old) | guide §3: the observed identity must survive deletion | value (observations) | observation; never blocks deletion (ruling Q3). An occurrence's live data relations are refs |

About 60 error-member attributes only report an offending name inside a refusal, for example
`:seon.call-preparation/unresolved-symbol`. They stay values without `reference-to`.

### 1b. The history, quoted

- **0e8f7d323** (2026-09-16) moved `calls`, `references`, `writes`, arity `input`/`output`/`guard-refs` and `reach`
  from refs to values. It deleted `seon.fn.ast` and `:seon.fn/pending-calls`, whose description was
  "Analyzer-observed call targets whose program identity is not yet admitted. The declaration writer resolves them
  when that identity arrives."
- The deletion study (§1) records D1–D6:
  - D1: the sweep silently loses caller edges;
  - D2 and D3: tombstones and their second validator;
  - D4: a ref to an entity with no datoms is admitted and then disappears from joins;
  - D5: redefinition mints a new eid;
  - D6: "a name mentioned in source text" had been declared as "a relation between two entities".
- Ruling 47's dissolved invariant ("every name the SCI context can resolve has a program row, minted where the
  context learns it") is not revived.
- **Refs for contract keys, schema uses and render pairs are acceptable only with the proofs of §3.** D1, D4 and D5
  do apply to them: the sweep on target deletion, a target identity replaced under a new eid, and a foreign eid
  replayed by merge. What differs is that the producer can refuse a missing target at admission, because Malli refuses
  an unregistered contract key (parser T11) and `canonical-schema-rows` refuses a missing schema. The final writer
  (§3c) and the portable-row owner (§3d) carry the rest.

### 1c. The six places that record "attribute → identity"

| Place | Holds | Fate |
|---|---|---|
| `removed-definition-error` obligations, `db.clj:4067-4104` | calls, references, subject, capability → fn; requires → ns; writes, schema refs, arity refs → schema; writes → `:db/ident` | derived (§2b). The equality witness is that the derived set equals today's list plus must-read and see-also |
| `write-render-target-error`, `db.clj:4027-4065` | re-reads every schema form with `edn/read-string` for renderer symbols without rows | kept until S3 proves the form-to-ref derivation (§3c) |
| `declared-reference-rules` rule 2, `fn.clj:1696-1701`; `test.clj:384-392` `reference-to` branch | `reference-to` treated as a reach edge | **P14: rule 2 yields 0 edges**. The only declaration is `:seon.schedule.task/function`, whose holders are schedule tasks, not function rows, and its reach comes from `:seon.fn/invokes` (`schedule.clj:630`). Both branches are deleted; invocation stays with `invokes` |
| `:seon.issue/cites`, `issue.clj:189-217` | issue attribute → cited identities, one map entry per identity (a later attribute overwrites an earlier one) | kept as the issue-local citation property (§2c) |
| `:seon.fn/reference-to`, `seon.fn.edn:17` | one declaration, on `:seon.schedule.task/function` | renamed to `:seon.db/reference-to`, direct-target meaning only |
| the walk's interim table, task-context §4.1 | eight `[holder identity]` pairs | derived from `:seon.db/reference-to` |

## 2. The model

### 2a. The descriptor, specified and validated

```clojure
;; resources/seon/schemas/seon.db.edn
:seon.db/reference-to
[:set {:min 1 :description "Direct target identity attributes of this attribute's values. Navigation only: invocation is :seon.fn/invokes; deletion obligation derives from row ownership."}
 :qualified-keyword]
```

- **Descriptor validation.** Validation runs once per supplied projection, in the one projection-derived value
  `seon.program/references-in` (memoized on projection identity, like `shapes-in`). Every target must be:
  - a registered schema whose properties carry `:seon.db/identity true`, or `:db/ident`;
  - carrying a `:seon.program/row-schema`, except for `:db/ident`.

  A violation refuses the projection by attribute and target, as `assert-complete-schema!`
  (`src/seon/schema/internal.cljc:309`) refuses incomplete contracts. `m/properties` only reads properties; it does
  not validate them (`reference-code/malli/src/malli/core.cljc:2592`). So the property values are validated with a
  compiled `m/validator` (`core.cljc:2637`) over the descriptor schema. No second registry or discriminator is added.
- **Its value** is `{attribute {:targets #{identity…} :storage (:ref | :value) :owner-family identity-attribute?}}`.
  - `:storage` comes from the bridge's value type (`compiled-storage`, `schema/datahike.clj:77`).
  - `:owner-family` comes from `shapes-in` plus `:seon.db/component-schema`, for component attributes such as
    arity members.
  - It is O(schema). About 25 attributes carry the property.
- **The value representation at each stage**, each with its schema and producer:

| Stage | Representation | Schema / owner |
|---|---|---|
| authored metadata | `:seon/tasks #{"handle"}`, `:seon/must-read ['q/sym]`, docstring `[[sym]]` | `:seon.task/annotation` (installed, `fn.clj:627`). The task id stays the installed string handle (`seon.task.edn:4`), not the parser prototype's keyword |
| analysis entry | clj-kondo var and keyword entries | the `seon.fn.analyzer/analyze` contract (parser §2b proposes entry schemas) |
| canonical row | a value member, or a ref member as its identity lookup ref `[:seon.schema/key :k]` | `:seon.program/row`. This is the digest input (`program.cljc:390`) and the form `declaration-value` rebuilds (`db.clj:4224`; it reproduced 7,040 stored digests) |
| transaction encoding | an installed target stays a lookup ref; a target that is only a candidate becomes an identity-keyed tempid whose entity map is in the same transaction | the writer (`seon.db/transact!`) |
| datom | an eid | Datahike |
| pulled shape | derived from the entity schema plus the selector | data-modeling guide §7 |

### 2b. Deletion obligation authority, without a second list

A declaration that survives in the final database still names a removed target. That refuses unless the same
transaction repairs it (§3c). The attribute counts when:

1. it declares `:seon.db/reference-to`; and
2. it is owned by a program declaration family in `seon.program/shapes-in` (fn, test, ns, file, lint, schema), or
   by a component whose `:seon.db/component-schema` a family owns (the arity rows); and
3. the removed target's identity attribute is in its `:targets`.

What this rule decides:
- **Blocks:** calls, references, requires, writes (schema key or `:db/ident`), subject, capability (fn holders),
  must-read, see-also, docstring links, contract keys, schema uses and render pairs.
- **Observation:** evidence lives on data-partition entities (`:seon.test/result`, errors, instrument and eval rows),
  which are not in `shapes-in`, so those relations never block. Reach invalidates proof reuse through its own owner.
- **Tasks and issues** are data. Their relations follow their own writers: the destination-gate dangling check, and
  the issue's required/optional refs.
- **`:seon.fn/keywords`** declares no `reference-to` and never blocks.

The equality witness for S0: on the fresh store, the derived obligation set equals the hand list at
`db.clj:4067-4104`, plus must-read, see-also and docstring links.

### 2c. Issue citations keep their own property

`reference-to` is global, so a scan for "attributes targeting `:seon.fn/sym`" would find calls, must-read and
schedule fields. Issue citation destinations are therefore derived only from issue attributes. The change:
- `:seon.issue/cites` stays, and is read only on attributes of the issue schema (`:seon.issue/issue`).
- `citation-attributes` (`issue.clj:189`) refuses when two issue attributes declare the same cited identity,
  instead of overwriting the map entry.
- The two-hop file citation keeps its structure:
  - `:seon.issue/files` is the component edge to `:seon.issue.citation/citation`;
  - the direct target sits on `:seon.issue.citation/file` (`seon.issue.citation.edn:4`), which gains
    `:seon.db/reference-to #{:seon.fn.file/relative-path}`.

### 2d. Storage per relation (recommended scope)

| Relation | Storage | Card. | Named key (new names for every retype, e00a3ccda) |
|---|---|---|---|
| arity → input / output / guard schema | ref | many (order stays on the arity row) | `:seon.fn.arity/inputs`, `outputs`, `guards`, retiring `input-refs`, `output-refs`, `guard-refs` |
| schema → schema used | ref | many | `:seon.schema/uses`, retiring `:seon.schema/references` |
| schema → AI / HTML renderer | ref | one each | `:seon.schema/render-ai`, `:seon.schema/render-html`, retiring only the schema-row codec use. The value-level `:seon.render/ai`/`html` override stays |
| calls, references, requires, writes, subject, capability, must-read, see-also, docstring links, tasks, task must-read, evidence | value + `reference-to` | as installed | unchanged names |
| agent → task | optional ref | one | `:seon.agent/task` (the task-context lane owns it) |

## 3. The blocking reasons, answered

### 3a. External dependency Vars have no rows

- **Measured (P2–P4, old store).**
  - 677 distinct external callee names account for 43,733 of 65,935 call datoms.
  - 106 of 217 required namespaces are external.
  - There are 0 `:seon.ns/name` rows for `clojure.core`, `datahike.api`, `malli.core` or `sci.core`.
- **What clj-kondo could supply.** Its dependency cache (`seon.fn.analyzer/analyze-dependencies!`) holds definitions
  for dependency Vars, so rows for them *could* be produced.
- **Why they should not be.** Such rows would bring back ruling 47. They would sit in no program partition, and the
  56 first-party Vars without rows would refuse the program on day one.
- **Authored links name external Vars too.** One test's see-also names `` `s/join `` (`task_ingestion_test.clj:24`).
  That is why must-read and see-also stay values in this revision.
- **Calls stay values, with declared targets, and they block deletion of known program targets.** Splitting one
  relation into a ref for program callees and a value for external ones would be one relation in two encodings.

### 3b. The producer's candidate world and forward references

The producer resolves every link against **the admitted base database plus the complete candidate identity set** of
the publication: every row that `analyze-rows` or `canonical-schema-rows` builds in this admission.

- **An installed target** is written as its lookup ref. At transaction time a lookup ref resolves against the
  database state reached so far (`db/utils.cljc:115`); P6 showed one naming a later operation refuses with "Nothing
  found for entity id".
- **A target in the candidate set but not yet installed** is written in the canonical row as the same lookup ref. The
  writer's transaction encoding turns it into an identity-keyed tempid, and that candidate's entity map is in the same
  transaction.
  - P6: tempids resolved a mutual cycle `a ↔ b` and upserted the existing `seon.db/q` without changing its eid.
  - This covers a new renderer arriving with the schema that declares it, and a function using a schema created in
    the same admission.
- **A target in neither set** refuses by holder, attribute and value at the producer. There is no placeholder row and
  no pending queue.
- **Schema cycles.** 0 exist (P12). Recursive schema references are **refused** by the schema owner, by name, before
  rows are built. This means a cycle check before the helper at `schema.clj:3681`, whose post-order `seen` would
  otherwise overflow the stack. Supporting recursive schemas is out of scope.
- **Function versus test identity.** A value resolves against each identity in `:targets`. More than one match
  refuses as ambiguous. P11: fn and test symbols are disjoint today (4,868 against 2,198, overlap 0), so no
  ambiguity arises now.
- **Malformed experimental contracts.** Arity rows exist only after `m/function-schema` compiles the contract
  (`program.cljc:1095`), so a malformed contract admitted under `:warn` produces no arity refs. Whether its function
  row then carries no arity rows at all is **unverified** (witness W8).
- Validation happens at `var-row` and its sibling constructors. No renderer or reader parses links again.

### 3c. The final check and deletion repair

The check lives in the existing final-report validator (`write-report-validator`, `db.clj:4398`, through
`write-report-error`, `:4321`). It sees the database before and after, the effective tx-data and the attempted
datoms.

1. **Selection.** Take the relations of `references-in` from:
   - (a) every asserted datom of a declared ref attribute;
   - (b) every removed or changed target identity, found by comparing identity datoms before and after. This covers
     a retracted identity datom, a renamed identity and a re-created identity. Their incoming holders come from the
     before AVET (refs) or the before and after AVET (values, which are indexed).

   An identity-less arity component resolves to its owning function through `:seon.fn/arities` in whichever of the
   two databases holds it. The existing complete owning-value validation stays unchanged. Components gain no
   identities.
2. **Existence.** For each distinct target eid in (a), an EAVT seek for one of the allowed identity attributes, keyed
   by `(eid, targets)`. Memoizing only by eid would let one attribute's check pass another attribute's target family.
   This refuses the dangling tempid (P6) and a ref to an identity-less entity.
3. **Obligation.** For each target in (b), every holder in the before database that survives in the after database
   refuses, unless it is repaired.
4. **Repair evidence.** A holder counts as repaired only when this transaction carries **current producer evidence**
   for it:
   - its `:seon.program/analyzed-source-digest` is asserted in this transaction, on the owning declaration for an
     arity component;
   - and its final relation set equals what the producer derived from that analysis. The row the producer submitted
     is the attempted datoms of that entity.

   Coordinated removal of the holder also counts. An edge that disappears only through the sweep is **not** evidence:
   swept datoms are not in the attempted set. A changed definition digest alone is not evidence either.
5. **Render pairs.** `write-render-target-error` stays until S3 proves form-ref agreement. The rule to prove is that
   the stored `render-ai`/`render-html` refs of a schema row equal the pair derived from its `:seon.schema/form` by
   `canonical-schema-rows`. The schema row's definition digest covers both the form and the refs, and the writer
   already recomputes digests from stored rows. Only once that is witnessed does the form re-read retire.

Witnesses, each a regression in the deletion class:

| # | Case | Expected |
|---|---|---|
| W1 | retract a callee's identity datom only; its other datoms and incoming values unchanged | refuse |
| W2 | rename a schema key that a contract names | refuse, or accept with the repair evidence of every holder |
| W3 | sweep the last member of an arity `inputs` set | refuse (the before holder survives, and there is no repair evidence) |
| W4 | sweep one of many members | refuse |
| W5 | coordinated repair: re-analyze the caller without the call, and delete the callee | accept |
| W6 | coordinated deletion of holder and target | accept |
| W7 | retract and re-create a target under a new eid, with holder refs to the old eid | refuse: the old eid has no identity (existence check) |
| W8 | a `:warn`-admitted malformed contract | no arity refs; the row admits |
| W9 | a dangling tempid in a ref member | refuse by attribute and value |

### 3d. Merge and portability reuse the existing owner

- **Entity ids collide after divergence** (P6: two `with` values from one base gave the same eid to different rows).
- The existing portable-row owner is `seon.fn/published-index-rows` (`fn.clj:3597`).
  - It reads peer refs as `[identity value]` through `reference` (`:3628`), drops `:db/id` in `row` (`:3639`) and
    keeps owned components structurally.
  - Merge's `replacement` already delegates to it and `reconcile-tx` (`src/seon/cluster/source.clj:772`).
  - The new ref attributes get the same treatment by declaration, with no second normalizer. The witness is that
    `published-index-rows` exports `inputs` and `uses` as lookup refs.
- **Digests** use the same canonical form everywhere. Producer rows, `declaration-value` (`db.clj:4224`) and the
  portable export all write a peer ref as its identity lookup ref. Tempids exist only in transaction encoding.
  - Witness: the producer digest equals the stored-reconstruction digest, which equals the branch-export digest, for
    a function whose contract names a schema key.
- **The merge entrance's current boundary.** `prepare-merge!` refuses additions, deletions and empty deltas; it
  accepts replacements of existing functions and tests (`cluster/source.clj:780`, `:811`). So a merge adding schema
  keys on two branches is not an executable witness today.
  - The collision witness (W10) sits at the portable-row seam: export a function row whose `inputs` name a schema key
    from branch A, and transact it onto branch B, where that key has a different eid.
  - A full schema-addition merge is a prerequisite owned by the merge lane.
- Missing destination targets refuse, through §3c existence. Program and data partitions keep their rule
  (`published-index-rows` inlines a data ref as its declared identity).

### 3e. Docstring links: grammar at the producer

**Root ruling Q2:** docstring markdown is read by a markdown library's parse tree, never by a Seon production regex.

**The parser.**
- The library is `io.github.nextjournal/markdown` 0.7.213. Its JVM implementation is commonmark-java 0.24.0
  (`nextjournal/markdown/impl.clj:8-12` imports `org.commonmark.*`).
- It is in `~/.m2`, and `reference-code/clerk/deps.edn:10`, `babashka/deps.edn:56` and `clay/deps.edn:12` use it.
  It is **not** on Seon's classpath (`deps.edn` has no markdown dependency), so S1 adds it to `deps.edn` and vendors
  the source under `reference-code/` per the vendoring rule.
- `nextjournal.markdown/parse` takes `{:text-tokenizers [nextjournal.markdown.impl.utils/internal-link-tokenizer]}`.
  That tokenizer ships with the library and emits `{:type :internal-link :text "…"}` nodes.
- Text tokenizers run only on `:text` leaf nodes (`impl/utils.cljc:232-269`). Code spans and code blocks are
  separate node types, so a `[[x]]` inside backticks or an indented example is never a link.

**Measured** in a disposable clojure process with the library from `~/.m2`, not the shared JVM:
- This sample docstring:

  ```text
  … [[snapshot-state]] / [[restore-state!]]. See [[db/q]] and `(fn [[a b]] a)`.

      (let [[x] y] x)

  Also [[a b]] and [[:seon.db/db]].
  ```

  yielded exactly the five link texts `snapshot-state`, `restore-state!`, `db/q`, `a b` and `:seon.db/db`. The code
  span and the indented block produced nothing. The first parse took 11.7 ms.
- Warm, over 1,858 `defn` docstrings from `src/seon`, it took **58.4 ms in total (31 µs per docstring)** and found
  34 links.
- A full reindex therefore adds about 80 ms for 2,582 docstrings. A one-file publication adds microseconds. S1 records
  both in its clock row.

**Resolution at the producer** happens in `var-row` and `namespace-row`, over each `:internal-link` node's text:

| Link text | Result |
|---|---|
| `build-projection` in `seon.schema` | `seon.schema/build-projection` (unqualified names resolve in the holder's namespace) |
| `db/q` with `seon.db :as db` | `seon.db/q`, through `seed-symbol` (`fn.clj:487`) |
| `snapshot-state` (no such Var or row) | refuse `:dangling-doc-link`: holder `seon.schema/restore!`, text `snapshot-state` |
| `a b`, `:seon.db/db`, any text that is not one qualified or unqualified symbol | refuse `:malformed-doc-link`: holder and text. Write prose outside `[[ ]]`, or code in backticks |
| `[[a b]]` inside backticks or a code block | not a link: the parser never tokenizes code |

- The text reads as a symbol through edamame, the existing reader, and must print back to the same text.
- Resolved links join `:seon.fn/see-also` values, with `reference-to #{:seon.fn/sym :seon.test/sym}`. The canonical
  head keeps the docstring bytes, and the digest already covers the docstring.
- The renderers never parse markdown links again.
- The three dangling links today (§0) are S1's first refusals. The cut that lands S1 repairs `restore!`, once
  `schema.clj` is free. It resolves `shipped-calibration` through the row-producer issue.

## 4. The render index

1. **Declared pairs** map schema → renderer.
   - The pair map `{schema-key {:ai sym :html sym}}` is derived from the projection, memoized on projection identity.
     It is the same value `render-declarations-in` computes (`schema.clj:1852`).
   - With S3's refs, "which schemas does `f` render" becomes one AVET seek. That replaces the form scan in
     `my.program/render-referrers` (`src/my/program.clj:228`; 17.7 ms in parser T15).
   - P8, old store, on the stored codec attribute: 334 rows over 50 renderers in 0.54 ms cold.
2. **The viewing namespace's render functions.**
   - **There is no face fact** (root ruling, reversing Q4 on the owner's objection). A render function's face is
     where it is attached: the `:seon.render/ai` or `:seon.render/html` key on a value or on a schema pair.
     Otherwise it comes from its declared **output** schema:
     - text output is the AI face;
     - `:seon.render/hiccup` output is the HTML face.
   - That is the rule `namespace-stage` already applies per value, through `function-accepts-and-returns-in?` with
     the face's output schema (`render.clj:622-646`). The index precomputes the same rule once.
   - **The derivation** is a fold over the projection's compiled function contracts
     (`:seon.schema.projection/function-contracts`, the map `render.clj:430` reads).
     - For each `:=>` arity output, the fold walks `:or`/`:maybe` alternatives and derefs refs.
     - A ref to `:seon.render/hiccup` gives `:seon.render/html`. A schema whose Malli type is `:string` gives
       `:seon.render/ai`. `:seon.render/source` derefs to `[:string {:min 1}]`.
     - Declared error alternatives contribute no face.
   - **P15 (fresh default):** over 2,167 contracts, the fold took **17.8 ms** and found:
     - 217 AI-face functions;
     - 76 HTML-face functions;
     - 8 with both faces, where arities or `:or` alternatives differ.

     All 42 declared HTML pair renderers return `:seon.render/hiccup`. Of the 51 AI pair renderers, 27 declare
     `:string`, which is a Malli built-in and not a registered key, so `output-refs` cannot see them. That is why the
     index reads compiled contracts, not the arity refs.
   - The AI face is broad. 217 functions return text, among them `seon.ai/attempt-ai` and
     `seon.instrument/expected-shape`. That matches today's semantics: `namespace-stage` treats any public function of
     the viewing namespace whose contract accepts the value and returns text as a candidate, and refuses ambiguity.
     The index narrows only which functions are checked, not which may render.
   - **Keys.**
     - The face map is keyed by projection identity, because the contracts come from the projection.
     - The namespace candidate set is the viewing namespace's program rows plus the agent's private SCI definitions.
       It is keyed by what it reads: the namespace rows' definition digests, and the agent context's
       private-definition set.
   - The existing contract and default-supply owner (`function-accepts-and-returns-in?` and call preparation) matches
     the value. Visibility, ambiguity and first-match stay exactly as in `namespace-stage` (`render.clj:618-646`).
   - P8's 1.31 ms (108 arities over 84 functions) measured the old output-reference query only. It is superseded by
     P15.
3. **The floor** needs no index.

The walk's structure then holds the pair map (by projection) and, per namespace, the candidate map (by row digests
and context). A frontier node's pair is a map lookup through its identity attribute's `:seon.program/row-schema`.

## 5. Cost: refs against value joins (P5, old store)

`:probe/calls` held 22,202 resolved call datoms inside `datahike.api/with`. Building it took 166 ms and wrote
nothing.

| Walk | Value join | Ref |
|---|---:|---:|
| forward, one function (58 edges) | 0.21 ms | 0.04 ms |
| forward, 60 functions (96 edges) | 0.93 ms | 0.33 ms |
| forward, 500 functions, warm / cold | 2.27 / 2.99 ms | 0.85 / 1.18 ms |
| reverse, 50 targets | 0.98 ms | 0.55 ms |
| callee closure of `evaluate` (919 nodes) | 20.0 ms | 3.9 ms |

Caveats:
- The ref datoms sat in the unflushed `with` tail.
- The 500-frontier runs drew different random frontiers (902 and 968 edges), so the ratios are indicative, not a
  requirement.
- The committed store is unmeasured; that is S2's clock row.

What the walk gains:
- Forward seeks are faster, on sub-millisecond queries. The walk's cost is dominated by plan compilation (53 of 71
  ms, task-context §2.2).
- Reverse lookup is AVET on the ref. Datahike has no VAET (`datahike/db.cljc:310`), and refs are auto-indexed
  (`db/utils.cljc:307-313`). Task-context's "VAET reverse" should read "AVET on the ref".
- Native pull through contract keys: the walk's three-clause contract family becomes a forward-ref family.
- The larger gain is correctness: a named target, an exact render key, and a refusal at the writer.

## 6. Migration in loadable slices

Each slice edits its owner in place and ends with its probe repeated on the adopted program.

**Adoption pattern for a retype** (the lesson of e00a3ccda: stored row requirements blocked a retirement). One set
of three publications covers S2 and S3 together:
- **P-a.** Add the new attributes (`inputs`, `outputs`, `guards`, `uses`, `render-ai`, `render-html`) as optional
  row-schema members. Make the old members optional. The producer writes both.
- **P-b.** Switch every reader to the new attributes: `my.program/reads-key`, the walk, `removed-definition-error` via
  `references-in`, and `published-index-rows` (by declaration).
- **P-c.** Stop writing the old attributes, retract their datoms, and retire their declarations.

| Slice | Change | Deletions | Added (est.) |
|---|---|---|---|
| **S0: declare targets; derive obligations** | `:seon.db/reference-to` with descriptor validation (§2a); `references-in`; declarations on the §1a attributes; `removed-definition-error` reads `references-in` + `shapes-in` (§2b); rename `:seon.fn/reference-to`; `citation/file` gains its direct target; `citation-attributes` refuses duplicates (§2c) | the hand obligation list (−20), `declared-reference-rules` rule 2 and `test.clj` branch (−12), the walk's interim constant (−10) | +40 (descriptor validation 12, `references-in` 12, derived obligation 10, citation refusal 6) |
| **S1: one producer emits every link** | the link step inside `var-row`/`namespace-row`/`task-links`: tasks, must-read and see-also (installed), docstring links from the nextjournal/markdown parse tree (§3e; `deps.edn` dependency and `reference-code/` vendoring), stage schemas; the dangling doc links repaired | the file row's misfiled ns-level references (parser T4) | +30 |
| **S2+S3 retypes (P-a, P-b, P-c)** | contract and schema keys and schema render pairs become refs; the candidate-world encoding (§3b); the schema cycle refusal; the final check of §3c; W1–W10; the §4 render index (the pair map, and the face map derived from compiled output contracts) used by `namespace-stage` | `write-render-target-error` (−39) after W-form; the walk's contract family (−12); `namespace-stage`'s per-value enumeration of every public (−8); the six retired attribute declarations | +80 (final-check selection 25, existence 10, repair evidence 15, candidate tempid encoding 10, cycle refusal 5, pair refs 5, face map 10) |

Totals: about +150 added and about −103 deleted, before review, plus one dependency (nextjournal/markdown, which
brings commonmark-java). No face-fact slice remains. S2+S3 is the one slice near the 100-line presumption. Its
additions are the final writer check, which the review requires for any ref. The alternative is option 1 below.

**Risks.**
- The writer path is hot. S2+S3 need a clock row, parent against change, for a full reindex and a one-file
  publication, with regressions over 20% or 50 ms fixed.
- The derived obligations widen refusals (must-read, see-also, doc links). Deletion tests that relied on the gap
  will go red.
- The candidate tempid encoding is new at the writer. W9 guards the trap.
- Recursive schemas become a named refusal.
- A new dependency: nextjournal/markdown 0.7.213 and commonmark-java 0.24.0 join the classpath, and
  `analyze-dependencies!` must lint them once.

**Priced options**, simplest first:
1. **Values everywhere, targets declared (S0 + S1).** About 3.5 hours.
   - Guarantee: one descriptor drives navigation, derived obligations and the walk; links are ingested and
     validated at the one producer; stale docstring links refuse.
   - Sacrifice: no native pull through contract keys; a missing contract or schema target is refused by Malli and
     the producer, but not by the writer.
2. **Recommended: option 1 plus the S2+S3 retypes with the render index.** About 7.5 hours across three adoptions.
   - Guarantee: contract keys, schema uses and render pairs are refs, proven by W1–W10 and portable through
     `published-index-rows`; the render index derives faces from stored contracts, with no new fact.
   - Sacrifice: the final-check machinery (+80) and three adoptions.
3. **Refs for every relation, with dependency rows from kondo's cache.** Days.
   - It reinstates ruling 47 and refuses 56 current first-party names.
   - It cannot express authored links to paths and external Vars.
   - Not recommended.

## 7. Requirement rows

Rows that depend on an open question are marked open.

| Row | Becomes | Status after this design |
|---|---|---|
| 1.9 followable both ways | `reference-to` names every direct target; forward by EAVT (ref) or AVET (value), reverse by AVET | closed by S0 for navigation |
| 1.10 one deletion authority | the derived obligation (§2b) at the final writer | closed by S0; repair evidence by S2+S3 |
| 1.12 derived contracts | derived inputs stored in the same `inputs` refs | storage by S2+S3; the derivation stays with derived-contracts |
| 1.14 canonical authoring | links read from the canonical head; the digest covers them | S1 |
| 1.15 every stage specified | §2a stage table; refusals at the producer and the writer | S0+S1; W1–W10 for refs |
| 5a.2–5a.3 hop content and addresses | the walk follows `references-in`; the named target selects the pair | S0 |
| 5a.6 must-read/see-also ingestion | installed in `task-links`; docstring links from the markdown parse tree | S1 (Q2 ruled) |
| 7.2, 7.3, 7.5, 7.13 tasks | values with `reference-to #{:seon.task/id}`; destination-gate dangling check | S0 (declaration only) |
| 7.9, 7.9b ingestion | the one link step | S1 |
| 11.1, 11.2 per-face selection; a render function's face | the §4 index: the attachment key, else derived from the declared output schema; no face fact | S2+S3 (Q4 ruled) |
| 12.2 error members | an error member naming a function stays a symbol value and never blocks deletion (errors are data); an occurrence's live data relations are refs; row 12.2 reconciled in `language-requirements-2026-09-24.md` | closed by ruling Q3 |

### Root rulings (2026-09-25)

1. **Q1:** `:seon.db/reference-to`, direct target only.
2. **Q2:** docstring links come from a markdown library's parse tree, never a production regex. They resolve through
   `seed-symbol`. The chosen library is nextjournal/markdown on commonmark-java (§3e).
3. **Q3:** an error member naming a function stays a symbol value. Errors are data, never program, so they do not
   block deletion. Row 12.2 was reconciled to say so.
4. **Q4 (reversed on the owner's objection):** there is no face fact. The face is the attachment key where one is
   attached, and is otherwise derived from the declared output schema (§4). AGENTS.md was corrected by root.

## 8. Probe log (JVM mode, read-only or `datahike.api/with`)

| # | Store | Probe | Result |
|---|---|---|---|
| P1 | old | installed attributes by value type | 260 ref, 94 symbol, 174 keyword |
| P2 | old | datoms / distinct / resolve per attribute | §1a |
| P3 | old | unresolved callees, first-party against external | 56 first-party, 677 external |
| P4 | old | ns rows for four dependency namespaces | 0 |
| P5 | old | `:probe/calls` built in `with` | 22,202 datoms, 166 ms; §5 |
| P6 | old | tempid cycle, forward lookup ref, missing lookup ref, dangling tempid, eid allocation | `#{[a b] [a q] [b a]}`; "Nothing found for entity id" ×2; dangling tempid → 0-datom target; same eid 123838 in two `with` values |
| P7 | old | holders of `:seon.render/ai` | 334 schema rows, codec strings |
| P8 | old | pair query; old output-ref query | 0.54 ms / 334; 1.31 ms / 108 arities (old query only) |
| P9 | checkout | `[[…]]` in `src/` text (rg) | 50 occurrences, including code (`(fn [[sym]] …)`, `sci/eval.clj:2581`) |
| P10 | fresh | writes / guard / inputs resolution | writes 1,107 / 291 / 236; guard 0; inputs 3,048 / 575 / 575; must-read, see-also, subject 0 |
| P11 | fresh | fn and test symbol overlap | 4,868 fn, 2,198 test, 0 shared |
| P12 | fresh | cycles in schema references | 1,951 holders, 0 cycles, 0 self-references |
| P13 | fresh | docstring `[[x]]` links resolving in the holder namespace | 44 fn-doc links, 41 resolve (dangling: `snapshot-state`, `restore-state!`, `shipped-calibration`); 4 ns-doc links |
| P14 | fresh | `declared-reference-rules` rule 2 edges; `reference-to` declarations | 0 edges; one declaration (`:seon.schedule.task/function`, 3 holders) |
| P15 | fresh | face fold over the projection's 2,167 compiled contracts; outputs of declared pair renderers | 17.8 ms; 217 AI, 76 HTML, 8 both; HTML pair renderers 42/42 `:seon.render/hiccup`; AI pair renderers 27/51 declare `:string` (no output ref) |
| P16 | disposable clojure process, `~/.m2` | nextjournal/markdown 0.7.213 `internal-link-tokenizer` on a sample and on 1,858 `src/seon` docstrings | code spans and blocks excluded; 58.4 ms warm total, 31 µs per docstring, 34 links |
| P17 | fresh | rows for `seon.ai.tokens` defs | `shipped-calibration` has no row; its 5 checked siblings do (issue filed) |
