---
type: architecture
status: active
created: 2026-09-17
tags: [architecture, schema, database, datahike, deletion, refs, identity, components]
---

# The data-modeling decision guide

> **What this is.** One place for every modeling decision this project has
> already made about *deletion, refs, identities, components, required-ness,
> events and derivation*, each with the ruling that settled it, the Datahike
> behaviour that grounds it (`file:line`), and a real row from this codebase.
> It is the always-current target authority per
> [AGENTS.md](../../../AGENTS.md) §4, organized by the **question a modeler
> asks**, not by mechanism.
>
> **What it is not.** It invents no rules. Everything here is sourced. Where
> two sources disagree the disagreement is printed verbatim as
> `X says … / Y says … / current ruling: …` rather than silently resolved.
>
> **The owner's concern that produced it** (2026-09-17 ~06:50Z, verbatim):
> *"it really depends on the situation. we need to come up with a reasoned
> guide for when we want data to retract vs marking it archived or refs vs
> identities vs whatever. a previous agent was supposed to do this and I
> thought we already made a lot of these decisions. I'm worried we are losing
> information."* The decisions were made. They were spread across eight
> research notes, four PRD sections, two skills and the ideas ledger. This
> file is their index. **Nothing below is new.**

**Sources, all read end to end for this consolidation.** AGENTS.md §2–§3;
[data-modeling skill](../../../.claude/skills/data-modeling/SKILL.md);
[datahike skill](../../../.claude/skills/datahike/SKILL.md);
[design ideas ledger](../../prds/context-generation/plan/design-ideas-ledger-2026-08-13.md)
(rulings 43, 47, 63–65, 70);
[program-facts PRD](../../prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md)
§1f (G1–G6), §1g, §1h, §1i;
[deletion semantics — program graph](../../prds/steward-platform/research/deletion-semantics-program-graph-2026-09-16.md);
[deletion semantics — agents and turns](../../prds/steward-platform/research/deletion-semantics-agents-and-turns-2026-09-16.md);
[the Datahike deletion study](../../prds/steward-platform/research/datahike-deletion-and-the-program-graph-2026-09-16.md);
[message, wake and provenance modeling](../../prds/steward-platform/research/message-wake-and-provenance-modeling-2026-09-17.md);
[reset schema recommendations](../../prds/steward-platform/research/reset-schema-recommendations-2026-09-16.md);
[schema design review](../../prds/steward-platform/research/schema-design-review-2026-09-17.md);
[entity schema versus pulled shape](../../prds/steward-platform/research/entity-schema-vs-pulled-shape-2026-09-16.md);
[unbreakable connections](../../prds/steward-platform/research/unbreakable-connections-2026-09-16.md) C1–C16;
[retirement is a fact](../../prds/steward-platform/research/retirement-is-a-fact-2026-09-16.md);
[REPL-native retraction and refactoring](../../prds/steward-platform/research/repl-native-retraction-and-refactoring-2026-09-16.md);
[reset deletion boundary](../../prds/context-generation/research/reset-deletion-boundary-2026-09-17.md);
[reset batch](../../prds/steward-platform/plan/reset-batch-2026-09-17.md);
[data model](data-model.md).

**[TARGET] markers.** A row marked TARGET is ruled but not yet in the tree; the
schema change lands in the one reset (G6). Do not read a TARGET row as a
description of `resources/seon/schemas/` today.

---

## The one page, before the questions

| Question | Answer | Ruling |
|---|---|---|
| Does a deleted thing leave a marker row? | **No.** Deletion is `[:db/retractEntity …]`; the past is `history` / `as-of` / `since`. There is no retirement attribute. | G1 |
| Does *anything* get closed rather than deleted? | **Yes** — an entity whose lifecycle ends but which stays addressable and resumable: a turn (`closed-tx`), an issue (`resolved-tx`), an agent (`archived-tx`). Always a **positive** transaction fact, never an absence and never an enum mirror. | turn PRD §14; 1g |
| Ref or value? | Ask: does the fact **STATE** something about a living entity, or **OBSERVE a TOKEN**? Statement → ref. Observation of a name → value. | AGENTS.md §3; G2 |
| Component or peer ref? | Component iff the target is **part of the parent's value** (containment). Peers are plain refs. | G5 |
| What happens to a peer ref when its target is retracted? | The **required/optional dial** decides: required → the transaction **refuses**; optional → the datom is **swept silently**. There is no policy property. | agents/turns §0 |
| Can I be lax about required? | No. A **required cardinality-many** key is unsatisfiable when empty, because `#{}` stores nothing. | G4; reset-schema §3 |
| Event or state? | Transitions the database causes take a **`-tx` ref**; events that genuinely predate their recording take a real **instant**. | N38/N39, J3 |
| Kind stamps? | Never. An entity IS its attributes. Bounded closed states of a *dependency's* grammar are the narrow exception. | AGENTS.md §3; A.4 |
| When is the answer no attribute at all? | Whenever a query over facts already present answers it. `open?` = no `closed-tx`; supersession = the ordinal fold; provenance = the transaction. | derive-or-die (ruling 23); 1h |

---

## 1. Retract, archive by a positive fact, or keep?

### 1.1 The rule

**Deletion is retraction, and the past is a history query.** A deleted
function, test, namespace, schema key or issue-with-no-note is
`[:db/retractEntity …]`. No entity is kept alive for the sake of another
entity's refs, and **there is no retirement attribute**
(program-facts PRD §1f **G1**, owner 2026-09-16 late evening:
*"The recommendation, which I agree with. Awesome. Do it!"*).

**An entity whose lifecycle ends but which stays addressable is closed by a
positive fact, not deleted.** The fact is a transaction ref on the entity
itself and its complement is derived:

| Entity | Closing fact | Derived predicate | Real row |
|---|---|---|---|
| turn | `:seon.turn/closed-tx` | `open?` = absence of it, decided **at the writer** | `resources/seon/schemas/seon.turn.edn:16`; `seon.turn/open?` `src/seon/turn.clj:189` |
| issue | `:seon.issue/resolved-tx` — docstring: *"absence means open"* | `open` | `resources/seon/schemas/seon.issue.edn:38` |
| agent | **[TARGET]** `:seon.agent/archived-tx`, derived `archived?`; lists and the debug page filter it | ruled 1g; no datom in `resources/seon/schemas/seon.agent.edn` yet |

Owner, ruling 1g, on the agent: *"Agents are always resumable but sure we can
have an archived flag and if so we don't surface it in the UI."* The
program-graph analysis agreed for an independent reason: the agent is the
**largest deletion blast radius in the population** — retracting one silently
sweeps `:seon.message/to`/`/from`/`/inbox`, `:seon.issue/agent`/`/created-by`,
`:seon.ns/steward`, `:my.note/agent`, `:seon.context.contribution/agent`,
`:seon.error.occurrence/agent`, `:seon.error/steward`, and *another agent's*
message history loses its sender with no signal
(schema-design-review A.2; agents/turns §7 decision 4, recommendation
*"strongly: option one"*).

### 1.2 When each is right

- **Retract** when the thing genuinely ceases to be a member of the current
  program or the current record: a function removed from source, a test
  deleted, a schema key unregistered, a file removed, an issue whose note is
  gone, an evaluation wiped by compaction.
- **Close by a positive fact** when the entity remains a legitimate subject of
  reads, refs and resumption after its active life ends. Three members today
  (turn, issue, agent). The test is *"will anything still address this row by
  identity?"* — an agent is resumable; a deleted function is not.
- **Keep untouched** when the fact is an observation of the past. A test's
  recorded `:seon.test/reach` is evidence about a **past run**, not a claim
  about the present, and is never a reason to refuse anything
  (program-graph §2.5).

### 1.3 The refusal — deletion is strict, with no escape

A function with live callers **is not deletable until the callers are fixed**
(owner, 2026-09-16; carried into AGENTS.md §3 as a TARGET). The rule, from
program-graph §2.1:

> For every identity value **v** whose row this transaction retracts, and for
> every declared edge attribute **a**, if any entity still alive in
> `:db-after` names **v** through **a**, the transaction is **refused**, and
> the refusal names every such entity and attribute.

Three consequences fall out with no further design: the fix may ride in the
**same transaction** for free (a re-analyzed caller simply does not name `v`
in `:db-after`); otherwise **the refusal IS the work list**; and nothing is
half-applied, because `validate-report` rejects by throwing and the
transaction is atomic
(`reference-code/datahike/src/datahike/db/transaction.cljc:1206-1216`).

The seam already exists: `seon.db/write-report-error` (`src/seon/db.clj:3009-3041`)
binds `:db-before`, `:db-after`, `:datahike/attempted-tx-data`, the affected
eid set (`:3014`) and the deleted row's prior identities (`:3034-3039`). The
only new work is one AVET lookup per (deleted identity × declared edge
attribute). **No fork change is required** (reset deletion boundary,
2026-09-17).

**Strict, with no escape** — owner, ruling 1g, verbatim intent: *"we want
either all the fixes in a single transaction (so we can verify with the
db-after that everything is still correct) or we will farm out in a
distributed way to agents to refactor and remove references to the function so
it can be removed. If an agent declares a function and doesn't want it they
just remove it and no one is depending on it so there's no problem … lets
stick with being strict and figuring out how to make this work with agents."*
program-graph §5.1 had priced the alternative (a recorded override) and
recommended against it for the same reason.

### 1.4 "Losing information" — what history does and does not cover

The owner's own settlement: *"We have history on the db so nothing is ever
lost."* Grounded: every retraction is written as `(datom e a v tx false)`
(`transaction.cljc:813-819`), Seon runs with history on
(`:seon.config.db/keep-history? true`, `config/default.edn:4`), so
`history` / `as-of` / `since` reconstruct a retracted or swept edge exactly
(deletion study E2). **Retracting a definition loses nothing.**

Two precise distinctions the sources insist on:

1. **Loss versus silence.** The defect in a silent sweep was never the lost
   datom — it is that *a surviving entity now asserts something different and
   nobody was told*. That is the project's named failure class and it is
   orthogonal to whether the bytes survive (program-graph §1.1).
2. **Loss versus elision.** *"The database elides nothing: every edge, every
   caller, every retraction is a datom."* Elision is a **presentation**
   decision living in the one clipping spot — the AI render functions and the
   value renderer. A refusal diagnostic carries the *complete* caller list as
   data; a long list is elided at render as an elision value naming the count,
   never truncated in the fact. *"Losing information is a database defect;
   eliding it is a render contract. They never trade against each other"*
   (program-graph §1.1; AGENTS.md §2.4).

**The one true deletion is purge.** `:db/purge` (`transaction.cljc:1084`),
`:db.purge/attribute` (`:1095`), `:db.purge/entity` (`:1106`) and
`:db.history.purge/before` (`:1119`) route through `transact-purge-datom`
(`:820-829`), which removes the fact from the temporal index. It is the
compliance escape hatch, never the data lifecycle: Datahike's own doc says
*"Use retractions for normal data lifecycle"*
(`reference-code/datahike/doc/time_variance.md:360`). If a reader of this guide
reaches for purge to "clean up", that is the bug.

**Two corrections from the study lane (§7.1 O8).** (a) Retraction preserves
temporal evidence **only with `keep-history?` and without `:db/noHistory`** —
`with-datom` tests both (`transaction.cljc:440-484`). An attribute carrying
`:seon.db/no-history? true` has no past, so "never" and "long ago" converge
toward the same absence there (schema-design-review N42/J5 requires the
docstring to say so). (b) **Purge changes temporal indexes in the resulting
database; it is not an all-snapshots erasure guarantee.** Older retained
commits and branches can still reach the old index nodes, and GC does not
prune datoms still reachable from a retained head's history
(`gc.cljc:22-81`, `:144-167`). Branch/commit retention is a separate decision
from temporal retention.

### 1.5 Contradictions on this question

- **AGENTS.md §2 ruling 47 says** program identity rows NEVER retract and the
  population invariant holds (ledger ruling 47, owner 2026-08-29: *"Identity
  rows never retract (unmap retracts definition facts, not identity), so
  lookup refs are stable forever"*). **G1 says** deletion is retraction and
  those two corollaries are RETIRED. **Current ruling: G1.** AGENTS.md §2
  carries the retirement marker in place; the deletion study §7 explains why
  (a bare identity row makes the break *unrefusable*, because the caller's
  lookup ref still resolves — the study's D2).
- **[retirement is a fact](../../prds/steward-platform/research/retirement-is-a-fact-2026-09-16.md)
  (status: proposed, awaiting review, 2026-09-16) says** use one shared
  `:seon.db/retired-tx` ref asserted by the writer that removes the content,
  plus a separate `:seon.db/referenced-tx` for evidence-only identities.
  **G1 says** there is no retirement attribute. **Current ruling: G1** — the
  program-identity half of that note is superseded. *Two of its findings
  survive and are still live work:* (a) its inventory of **six** program
  identity families and **four** retirement writers
  (`src/seon/program.cljc:11`; `turn/row-tx` `src/seon/turn.clj:1281-1330`;
  `fn/reconcile-tx-in` `src/seon/fn.clj:2557-2597`; `issue/index-tx` and
  `adopt-tx` `src/seon/issue.clj:225-393`), which is the list G3's tombstone
  deletion must cover; and (b) its "third state" observation — an identity
  minted only to resolve evidence in a database that never defined it is
  **not** retirement — which G4 answers as a required positive provenance
  fact rather than as a `referenced-tx` attribute. The open blocker issue
  [retained identities have no declared retirement state](../../seon/issues/retained-identities-have-no-declared-retirement-state.md)
  still cites the superseded design; its *acceptance* clauses about the
  reference-minting path and the second validator remain G3's work.
- **[data-model.md](data-model.md) said** *"Program identity rows survive
  definition removal as tombstones, preserving refs to those identities."*
  **G1 says** the opposite. Corrected in the same commit as this guide.
- **Ruling 70 says** *"handled = a claim ref from the handling run, because
  retracting a routed edge would wake."* **The tree today** retracts the
  routed inbox edge (`src/seon/turn.clj:435-437`,
  `src/my/../cluster/message.clj:276-277`). **Current ruling: 70**, restored
  by ruling 1h, which names this exact drift as *"to repair with the same
  slice."*

---

## 2. Ref, identity value, component, or tuple?

### 2.1 The discriminator — one question

**Does the fact STATE something about a living entity, or OBSERVE a TOKEN?**
(AGENTS.md §3; data-modeling skill, *"The one question that decides a ref"*.)

- **A statement about a living entity** → a **ref**. Either the target
  *contains* the referrer (`:seon.db/component true`) or they are peers, and
  a peer's deletion policy is the required/optional dial (§2.3).
- **An observation of a token** — a name the analyzer, the author or the
  reporter *saw*, in source text, metadata, a note or a report → a **VALUE**
  (`:qualified-symbol`, `:qualified-keyword`, a path string). A name denotes
  itself; whether anything by that name exists is a separate derivable
  question.

Ruled as **G2**: `:seon.fn/calls [:set :qualified-symbol]`,
`:seon.test/reach [:set :qualified-symbol]`. **[TARGET]** — today
`resources/seon/schemas/seon.fn.edn:23` is still `[:set :seon.db/ref]` and
`resources/seon/schemas/seon.test.edn:2` is still a ref vector.

**A value edge must be indexed explicitly.** Ref attributes are automatically
indexed, and so are BOTH kinds of unique attribute
(`reference-code/datahike/src/datahike/db/utils.cljc:307-313`) — **an ordinary
symbol edge is not.** Retyping `:seon.fn/calls` off `:seon.db/ref` therefore
silently loses its reverse-traversal index unless the declaration adds
`{:seon.db/index true}`; the reverse walk uses the same AVET shape either way
(`db/search.cljc:140-157`). N2's recommended edit already spells it
`[:set {:seon.db/index true} :qualified-keyword]` for this reason. There is
**no VAET index** in Datahike (`datahike/db.cljc:310`).

**And choose the right unique kind.** `:db.unique/identity` participates in
entity-map **upsert**; `:db.unique/value` only enforces uniqueness
(`transaction.cljc:641-713`, `:26-32`). Both work as lookup attributes.
Neither promises permanent ownership of a key after its identity datom
disappears (§2.7).

**The tell for a misfiled token** is machinery that exists only to survive a
rename or a republish: a `:seon.fn/reference-to` annotation whose whole job is
telling a reader how to get the name back out of a ref
(`resources/seon/schemas/seon.fn.edn:4`); a preservation pass that strips and
re-resolves refs across a publication (`src/seon/cluster/source.clj:471-477`);
a sibling attribute storing the same name as a value beside the ref. *"Every
one of those is a name-observation wearing a ref"* (data-modeling skill).
program-graph §4(e) calls the file rename **the event that most clearly
separates the two kinds of fact**.

**Why the value form matters is not what G2 first said.** G2's stated reason
was *"deleting a function touches only its own datoms."* Under the strict
refusal (§1.3) the goal is the opposite, and the retype serves it better: the
value edge is what lets the refusal tell a **repair** from an **erasure**.
`retract-entity`'s sweep removes the caller's ref datom from `:db-after`
before the check runs, so a repaired caller and a swept caller are
**byte-identical**; a repaired caller's new *symbol set* simply does not
contain the deleted name (program-graph §2.3).

### 2.2 The five behaviours, verified against the fork

| # | Behaviour | What Datahike does | How it is declared | `file:line` |
|---|---|---|---|---|
| 1 | **cascade** — the child dies with the parent | `retract-entity` maps every **component-valued datom of the entity being retracted** to `[:db.fn/retractEntity v]`; parent → child only | `:seon.db/component true` | `transaction.cljc:831-836`, dispatch `:1080-1082`; `retractAttribute` `:1073-1078` |
| 2 | **sweep** — the referrer's ref datom is retracted silently | `retract-entity` scans `(dbi/search db [nil a e])` for **every** `:db.type/ref` attribute and retracts each incoming datom | the default for a plain **optional** `:seon.db/ref`; nothing declares it | `transaction.cljc:998-1015` |
| 3 | **refuse** — the writer rejects the whole transaction | the swept datom lands in `:tx-data`, so Seon's final-report validator re-validates the referrer's whole row; a **required** key now fails and `validate-report` throws atomically | required-ness in the referrer's entity map | `transaction.cljc:1206-1216`; `src/seon/db.clj:3009-3041`, `:2958`, `:3014` |
| 4 | **value** — the fact stores the target's identity value | no ref exists, so the sweep cannot see it, and the fact is still present in `:db-after` for the refusal check to read | the attribute's Malli type | deletion study E4–E6 |
| 5 | **move** — a pending edge its own settlement migrates to a durable sibling | ours; invented independently twice | by construction | `:seon.message/inbox` → `/read-tx`; `:seon.effect/notify` → `/to` (agents/turns `:91-93`, `:399-402`) |

**Correction on row 5 (§7.1 O7).** The datahike skill, as the study lane
revised it, says a settlement that moves an edge is **application logic, not a
fifth native deletion mode**, and names the message inbox move specifically as
the one **not to copy** — ruling 1h restores listened `:seon.message/to` plus a
handling-turn claim ref, which is ruling 70's construction. Read row 5 as a
pattern some writers use, never as a behaviour the database offers.

Plus **purge** (§1.4), which is not a modeling choice.

The anticipated sixth — *"keep the referrer fact and answer through history
only"* — is behaviour 2 plus a temporal reader, and is **disqualified**: it
answers correctly only to a reader that knows to ask (program-graph §1).

### 2.3 The dial, and its holes

**Required versus optional IS the deletion dial. There is no policy property
and there never was one** (agents/turns §0, the finding that reframed the
question). The chain, opened end to end:

1. `[:db/retractEntity e]` sweeps every incoming ref datom
   (`transaction.cljc:998-1015`) and cascades components (`:831-836`).
2. Every such retraction is written through `transact-retract-datom`
   (`:813-819`), so it lands in the report's `:tx-data`.
3. Seon attaches a final-report validator to every `transact!`
   (`src/seon/db.clj:3178-3179`); any non-nil return throws
   `:transaction/validation-rejected` (`transaction.cljc:1206-1216`).
4. `write-report-error` computes `affected` over attempted **and** effective
   tx-data (`src/seon/db.clj:3014`) — which therefore **includes every entity
   the sweep touched** — and validates each resulting row (`:2958-3007`).
5. So a **required** swept key fails `:malli.core/missing-key` and the
   deletion **refuses**; an `{:optional true}` one **sweeps silently**.

**Refined by the modeling study (§7.1 O1).** A required ref refuses a sweep
**only when the surviving row is selected and validated** — the selection hole
below is not an edge case, it is part of the dial's contract. And required
presence is **not a native foreign-key constraint**: numeric ref values do not
prove target existence
(`reference-code/datahike/src/datahike/db/utils.cljc:109-148`).

**Two real holes, both named:**

- `write-entity-error` is `(when (seq row))` (`src/seon/db.clj:2961`) — an
  entity retracted to nothing is skipped. Correct, and it is *why coordinated
  deletion works*.
- `schema-keys` derives from the row's **identity attributes** (`:2964`), so
  **an entity with no identity attribute is never validated at all** — which
  is exactly the 29 unselected component maps
  (`schema-key-audit-2026-09-16.md`). A component row swept by a foreign
  deletion is unchecked.

**Say which behaviour you chose, in the attribute's docstring.** The reset
batch carries this as policy, not as a blanket rule: *"Audit actual storable
refs by their writer: components cascade; required refs refuse; optional refs
may deliberately sweep. … Document the chosen behavior; no universal
enforcement"* (`reset-batch-2026-09-17.md:271` and nine sibling rows).

**The standing ruling is "it depends, per attribute, declared in the
docstring"** — message-wake §1.1, *"the menu, not a rule"*, which is the note
the owner remembered.

### 2.4 Genuine entity relations — keep the ref

Stated because the reviews ask for the verdict either way
(schema-design-review A.1): `:seon.fn/ns`, `:seon.fn/file`, `:seon.fn/ast`,
`:seon.fn/arities` (containment or components); `:seon.message/to`, `/from`,
`/inbox`, `/caused-by`; `:seon.issue/agent`, `/created-by`; `:seon.ns/steward`
(*"an agent is a live entity, not a name in text"*,
`resources/seon/schemas/seon.ns.edn:33-38`); `:my.plan.item/subject`.

**Eleven more name-edges** were found beyond G2's two and are TARGET retypes:
`:seon.fn/references` (N1), `:seon.fn/writes` (N2), `:seon.schema/references`
(N3), `:seon.fn.arity/input-refs`/`output-refs`/`guard-refs` (N4), the
`:seon.issue/functions`/`tests`/`errors`/`keys`/`namespaces`/`runs`/`issues`
citation family (N5), `:seon.eval/renderer-fn` (N6, delete — the symbol beside
it already carries the fact), `:seon.test/pending-subject` (N7, delete),
`:seon.listen/entity` (N8 — **the worst instance**: retracting the watched
entity retracts the constraint and the wake pattern silently *widens from one
entity to every entity*), `:seon.schedule.task/function` (N9),
`:seon.fn/capability-fn` (N10), `:seon.test.failure/file` (N11),
`:seon.error/fn` (N13, delete — *"a fault must survive the deletion of the
function that caused it"*).

### 2.5 Component = containment

**A component is part of its parent's value** (G5). Pull auto-expands a
component even under a wildcard with no sub-selector
(`reference-code/datahike/src/datahike/pull_api.cljc:345-349`), and
`retractEntity` destroys it with the parent
(`transaction.cljc:831-836`). Therefore the **validation unit is the parent
pulled with its components expanded, validated as one value** against the
parent's entity schema. **Do not invent identity attributes on component rows
to make a selector see them** — that would be a `:type` stamp in disguise
(deletion study §8.1). TARGET: 29 marked component maps are currently
unselected.

**Refined by the modeling study (§7.1 O2).** Datahike's cascade **does not
enforce exclusive ownership** (`transaction.cljc:831-836`), so a component
declaration is a claim the schema makes, not one the database checks. Shared,
fingerprint-identified shapes therefore stay behind **ordinary refs**: the
shape model owns its child/entry occurrence rows, but their `/schema` refs
lead to shared shapes
(`resources/seon/schemas/seon.schema.shape.edn:8-11`,
`resources/seon/schemas/seon.schema.shape.child.edn:3-4`). Preserve that
distinction when merging another representation into it — and validate
**complete owning values**, including owners discovered from *before and
after* a child-only edit or unlink.

A real one: `:seon.test/failures` is a component, so deleting a test cascades
its failure rows correctly — while the failure's `/first-run` and `/last-run`
are **outgoing**, so the run records are untouched. *"A test is the cleanest
deletion in the graph"* (program-graph §4(c)).

The inverse is the dangerous one: deleting a **run** record.
`:seon.test/run` and `:seon.test.failure/first-run`/`/last-run` are required
refs into it, and `changed-since-green` decides greenness from the presence of
a `:seon.test/run` assertion in history (`src/seon/test.clj:87`). A sweep there
converts every test's green history into *"no green result is retained."*

### 2.6 When a tuple

Use a tuple for **one fixed ordered observation** — a callee and its argument
count, say. **Its complete value is the index key, not each member**
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:31-132`;
modeling study, §7.1 O3), so you cannot query one member of a tuple the way
you query a scalar attribute. Two more dependency facts decide the rest:

- **A ref cannot live in a tuple** in any way that behaves like a ref: the
  bridge emits `:db/tupleTypes` (`src/seon/schema/datahike.clj:267`) over
  scalar types; there is no ref member type. So an identity pair carried in a
  tuple is carried as **values**, which is exactly why the identity-pair
  carrier was ever proposed (agents/turns §7) and why it is a value decision,
  not a ref decision.
- **Do not put a ref inside a tuple expecting ref resolution or the incoming
  sweep.** Both operations dispatch on the *attribute's* ref type
  (`transaction.cljc:786-790`, `:998-1015`), so a ref-shaped member of a tuple
  is an opaque value to every deletion behaviour in §2.2.
- **A heterogeneous tuple whose members are ALL the wrong type stores.**
  `check-tuple` refuses only when the per-member validity results *differ*
  (`transaction.cljc:1037`); all-invalid is `(= false false)`, which passes.
  Only the member COUNT is enforced (`:1033`). **A regression over a tuple
  attribute must assert the stored member types, never merely that the
  transaction succeeded.**
- `maybe-wrap-multival` treats any non-map collection under a
  cardinality-many attribute as the member sequence (`:718-736`), so a
  cardinality-many tuple attribute handed a bare tuple vector **explodes into
  scalar datoms**.

Ledger ruling 48(a) already refused the composite: *function identity stays
ONE SCALAR qualified symbol — no composite tuple.*

### 2.7 A ref to a retracted entity is legal, and that is the trap

`validate-val` checks the value's **type** and nothing else
(`transaction.cljc:33-52`, called from `transact-add` at `:788`); it never
asks whether the target has datoms. Consequences, all silent:

| Read | Result when the target has no datoms | `file:line` |
|---|---|---|
| Datalog join | the clause fails to match; the referrer disappears from the result | `query.cljc:1394-1401` |
| pull with a sub-selector | the member is **dropped from the collection** | `pull_api.cljc:483-486`, `:209-217` |
| wildcard pull, plain ref | `{:db/id n}` — a link to nothing, indistinguishable from a live one | `pull_api.cljc:351-357` |
| `[:db/id]` on a dangling eid | omitted; the map can come back empty | `pull_api.cljc:370-376` |
| top-level `pull` of a dangling eid | `nil` | `pull_api.cljc:508-515` |
| re-asserting a retracted identity | a **new eid** — `upsert-eid` resolves through an AVET datom that no longer exists; the old ref does not follow | `transaction.cljc:641-712`, `:659` |
| a lookup ref to a retracted identity | **raises** `"Nothing found for entity id"` and aborts | `db/utils.cljc:141-148`; first-party at `src/seon/cluster/source.clj:358-363` |

*"Every consequence of behaviour 2 arrives as fewer results, never as an
error. Every consequence of behaviours 3 and 4 arrives as a refusal or a
positive query row"* (program-graph §1.2).

### 2.8 Contradictions on this question

- **G2 says** *"Refs stay where a genuine entity relation exists (…
  `/capability-fn`)."* **Ruling 1g says** the `:seon.fn/capability-fn` ref is
  **deleted** and the handler symbol joins the deletion refusal set,
  explicitly *"overturns G2's exception."* **Current ruling: 1g.** Priced at
  program-graph §5.2 and schema-design-review J2 (*"one hop is not a reason to
  keep a fact that a deletion silently erases"*).
- **schema-design-review N9 says** *"`retract-entity`'s sweep runs no
  re-validation of the referrer, so the task persists in a state its own
  schema forbids."* **agents/turns §0 says** that premise is **false** — it
  does re-validate, at `src/seon/db.clj:3032-3040`, and the task persists in
  no state at all because the transaction is refused. **Current ruling:
  agents/turns §0**, which C.1's *"open question … N9 today has no answer"*
  also resolves. The retype of `:seon.schedule.task/function` to a symbol is
  still recommended, for the token/statement reason, not for this one.
- **agents/turns §7 decision 1 recommends** an identity-pair carrier for
  `:seon.eval/origin`. **schema-design-review N12 says** keep the ref (and
  record the un-provenancing consequence in the docstring). **message-wake
  §1.1 withdraws** the identity-pair carrier. **Ruling 1h says**
  `:seon.eval/origin` **retypes to `:seon.issue/id`** — a value that survives
  the issue's deletion. **Current ruling: 1h.**
- **agents/turns §7 decision 2 recommends** converting `:seon.message/about`
  and `:my.note/about` to identity values; **schema-design-review** lists
  `:seon.message/about` under *keep the ref*. **Ruling 1h says**
  `:seon.message/about` is **split three ways**: the subject becomes the
  identity token the agent supplies (no `resolve-about` pre-read),
  `:seon.message/from` is the sole inside-wake marker, and the
  assignment/declination protocol gets its own attribute. **Current ruling:
  1h.**
- **agents/turns §7 decision 3 recommends** retyping
  `:seon.cluster.eval/refreshes` to a value. **Ruling 1h says** the attribute
  and both `refresh-call` functions are **deleted**; supersession stays the
  since-diff's fold over ordinals. **Current ruling: 1h** — which is §6's
  pattern, not §2's.
- **The eval-path review proposed** *"every non-component ref is living"* as a
  blanket rule. **agents/turns §7 decision 5 declines it** for this family:
  `:my.plan/current-step`, `:seon.runtime/trigger`, `:seon.turn/trigger`,
  `:seon.message/caused-by` and `:seon.ai.attempt/failover-from` are
  docstring- or code-declared **provenance and live pointers** where sweeping
  is correct and already intended. **Current ruling: the per-attribute dial**,
  confirmed by message-wake §1.1 and the reset batch's *"no universal
  enforcement."*
- **program-graph §5.4 recommends keeping** `:seon.lint/file` as a ref (the
  root-relative grouping query stays a join instead of string arithmetic, and
  a file deletion retracts its findings in the same transaction — findings are
  rebuilt wholesale anyway). No source disputes it. **Open judgment, marked.**

---

## 3. Required or optional?

### 3.1 The rule

**Required means the writer always knows it.** Because required-ness IS the
deletion dial (§2.3), the choice is never cosmetic: it decides whether a
foreign deletion refuses or sweeps.

**A required cardinality-many key is unsatisfiable when it is empty.** A
cardinality-many attribute with no members has **no datoms**: `explode` emits
one `[:db/add e a v]` per member of `(maybe-wrap-multival db a-ident vs)`
(`transaction.cljc:739-770`, `:718-737`), so `#{}` emits nothing.
`{:a/id 1 :a/calls #{}}` and `{:a/id 1}` are **byte-identical after storage**
(deletion study E7). Declaring such a key required makes every legitimately
empty row refuse.

### 3.2 "The looking is an event and the event is a datom" (G4)

If a reader will ever need to distinguish *"we looked and found nothing"* from
*"we never looked"*, the looking is a **positive fact**. Every definition row
carries the identity of what produced its definition facts — the file
entity/digest for an indexed declaration, the evaluation for an agent-authored
one — **required**. Then `analyzed?` is its presence; *"calls nothing"* is
that fact plus no edges; `#{}` is never a sentinel.

**And a submission-time-only check cannot repair it.** The whole-entity
validator rebuilds the row from the resulting EAVT datoms
(`seon.db/write-entity-value`, `src/seon/db.clj:2895`; `write-entity-error`,
`:2942`), where the empty collection is already gone — so a submission-time
check is *"a pre-read the authority re-decides"*, AGENTS.md's owner law by
name. **The fallback is closed too:** a transaction function sees the
mid-transaction database (`transaction.cljc:1153-1154`) but still never sees
the submitted `#{}`, because `explode` erased it one step earlier
(reset-schema §3, which states this and notes *"which no document says"*).

**Refined by the modeling study (§7.1 O5).** An event fact proves exactly the
observation **its writer completed**: a definition-analysis digest does not
prove test reach ran, and a maintenance request's existence does not prove
every sub-operation ran. So **do not require nonempty datoms for a legitimate
empty result** — the event's positive provenance is what permits reading
absent membership as empty (`transaction.cljc:718-770`). And a **digest
records content identity, not the number of times identical content was
observed**: idempotent assertions are omitted from effective tx-data
(`:587-625`), so a digest cannot double as an observation counter.

Owner's framing, recorded in the deletion study §8: *"The owner is right: this
is our logic failure, not Datahike's."* The failure was asking one collection
attribute to carry **two** questions: *what does A call* and *did anyone ever
look*.

### 3.3 Real rows, and how to decide the residue

| Row | State | Verdict |
|---|---|---|
| `:seon.cluster.eval/read-basis-transaction` | present on **109/109** evaluations | **require it** — a zero-cost proof of the pattern, and it fixes the since-diff's absence-as-state defect at `src/seon/turn.clj:846` |
| `:seon.render.block/name` | 1 123 datoms, present everywhere | **require it** |
| `:seon.context.contribution/evaluations` | **0 datoms** | the `(seq …)` selector at `src/seon/context.clj:483` has never taken its other arm — it is not a selector at all |
| `:seon.test/reach-unknown` | a **string sentinel** doing a fact's job; its own docstring confesses *"absence of both membership and this marker is legacy unknown, never proof of an empty closure"* (`resources/seon/schemas/seon.test.edn:4`) | make the reach **digest** required, then **delete** `reach-unknown` |
| `:seon.fn/file` | every indexed declaration has one; an agent-authored one legitimately has none | required **only for `:core` provenance** — it lands with the provenance work, not before it (unbreakable §5 tier 1) |

**The test for the 15 keys that are optional only because one writer omits
them:** a key is optional because a *state* legitimately lacks it, or because
a *writer* is incomplete. Count the live population; if no surviving row lacks
it, require it (reset-schema §3). That is mechanical, not a design question.

### 3.4 Contradiction on this question

**`schema-key-audit-2026-09-16.md:17` proposed** validating `calls` "including
`#{}`" at submission, before Datahike erases the empty collection.
**Deletion study §8 and reset-schema §3 say** that is a pre-read the authority
re-decides, and the transaction-function fallback is closed too.
**Current ruling: G4** — a required positive provenance datom, and the
integrator's contradiction table already ruled G4 over S2's submission
acceptance.

---

## 4. Event or state?

### 4.1 The rule

- A **transition the database itself causes** — recorded in the same
  transaction that causes it — takes a **`-tx` ref**. A transaction ref yields
  both the instant (`:db/txInstant`) and the basis `:t`; an instant yields
  neither (schema-design-review N38).
- An event that **genuinely predates its recording** — an external
  observation — takes a real **instant**.

Marked answer, J3: *"A for effect and maintenance, B for provider and
occurrence — the split is the rule, and it must be written in the
docstrings."*

### 4.2 Real rows

| Row | Today | Verdict |
|---|---|---|
| `:seon.turn/closed-tx`, `:my.plan.item/*-tx` | `-tx` refs (`seon.turn.edn:157-158`, `my.plan.item.edn:79`) | correct, the pattern |
| `:seon.issue/opened` | `:inst` (`seon.issue.edn:5`) beside `/resolved-tx` and `/budget-exhausted-tx` refs (`:38`, `:37`) | **two clocks on one lifecycle** — make it `:seon.issue/opened-tx` |
| `:seon.effect/*-at`, `:seon.maintenance.receipt/*-at` | instants (`seon.effect.edn:52`, `:63`, `:161`; `seon.maintenance.receipt.edn:12`, `:14`, `:20`) | convert to `-tx` — the event is recorded in its own transaction |
| `:seon.ai.attempt/at`, `:seon.error.occurrence/first-at` | instants | **keep** — the event genuinely predates its recording |
| `:seon.test/run-at`, `/run-basis-t` | copies of the run entity's own facts onto the domain entity (`seon.test.edn:32`, `:31` vs `seon.test.run.edn:1`, `:5`) | **delete both** — AGENTS.md §3 forbids copying transaction projections onto domain entities; join the run |

### 4.3 What is still open here

**The rule is marked; the per-family SCOPE is not enumerated.** J3 names the
split and says it must be written into the docstrings, but no source lists
every attribute that converts. The reset batch carries *"Audit transaction/
effect provenance per family; no universal enforcement and no cascading
transaction entities"* (`reset-batch-2026-09-17.md:211`) as the assignment.
**Marked as owner-pending scope; this guide does not enumerate it.**

Related and also unresolved: **J7**, `:seon.effect/content-blobs` order —
*"Unresolved — read the writer before the reset; today the declaration claims
an order the storage does not keep."*

---

## 5. One schema per entity; the pulled shape derives

### 5.1 An entity IS its attributes — no kind stamps

Do not add `:type`/`:kind` discriminator attributes. Query attribute presence
to find entities, use a unique identity attribute to identify one, follow refs
to relate one (AGENTS.md §3). The narrow exception is a genuinely bounded,
closed set of states, justified in the schema's docstring — and even then it
describes the entity, never picks a table.

**Dissolve** (schema-design-review A.4): `:seon.cluster.eval/author
[:enum :agent :system]` — the data-modeling skill rules it out by name, and
`src/seon/repl.clj:476` already derives the same answer;
`:seon.eval/outcome [:enum :ok :time :error]` — attribute presence already
selects it; `:seon.issue/status`'s `:open`/`:resolved` arms — a stored mirror
of `:seon.issue/resolved-tx` on the **same entity**, which derive-or-die names
a defect on sight (`:superseded` is a genuine third state and earns its own
positive fact); `:seon.fn.binding/shape` — a table-picker for `/symbol`,
`/entries`, `/children`.

**Keep** (dependency facts or genuinely closed states, already ruled):
`:seon.fn.ast/type`, `:seon.schema.shape/type`, `:seon.test.failure/type`,
`:seon.lint/type`, `:seon.error/kind`, `:seon.fn/workload`,
`:seon.fn/external-sink`, `:seon.turn/disposition`,
`:seon.schema.admission/source`,
`:seon.fn.binding.child/role`, `:seon.fn.binding.entry/spelling` — *"genuine
bounded destructuring facts from the reader's grammar."*

### 5.2 An entity schema describes the STORED entity only

A reference has **three grammars** — transaction data, datom, pull result —
and one Malli key cannot describe all three. `:seon.db/ref`
(`resources/seon/schemas/seon.db.edn:11`) declares the **transaction-data**
grammar. A reader's pulled shape **derives** from the entity schema under that
reader's selector; it is never a per-attribute `[:map [:db/id :int]]` widening
and never a second hand-written pulled schema.

Cost already paid: five per-attribute widenings of one class (D1), plus the
widened schema **not even being reached by the write path** — the attribute
pass replaces any map or sequential ref value with `0` before Malli sees it
(`src/seon/db.clj:2774-2785`) and the whole-entity pass validates a row
rebuilt from EAVT datoms where every ref value is already an int (`:2895-2905`)
— so the patches were *pure reader appeasement* (D2). Twelve hand-written
mirrors in total. `:seon.test.failure/value` is a **thirteen-entry hand-copied
mirror** of `:seon.test.failure/failure` (D5) — a hand-maintained mirror of
derivable state, a defect on sight under derive-or-die.

### 5.3 No `-edn` strings where the bridge owns a codec

`seon.schema.datahike/edn-encoded-attr-in?` (`src/seon/schema/datahike.clj:346`)
plus `encode-transaction` / `decode-attribute-value` (`:458`, `:583-594`)
**automatically** store a mixed `[:or …]` union as an EDN string and read it
back as the value. Thirteen attributes hand-do that job, and by hand the reader
gets a string, no Malli shape is checked, and the encoding rides in the
attribute *name* (A.3, N19–N23). Two survivors with reasons:
`:seon.error/data-edn` is the ruled three-tier projection (datom = projection,
blob = full content) — keep the string, drop `-edn` from the name; the
provider payloads (`usage`, `settings`, `extra-body`) are genuinely opaque
bytes — keep as strings, say so in the docstring.

### 5.4 Two more dependency behaviours that report nothing

- **Cardinality-many does not acquire order because a Malli declaration used a
  vector.** The bridge maps any `[:vector …]`, `[:set …]` or `[:sequential …]`
  head to cardinality-many, which is an unordered **set**. Store an ordinal on
  ordered members (data-modeling skill; AGENTS.md vocabulary row for tuple).
- **Pull silently truncates a cardinality-many result at 1,000 members** —
  `+default-limit+` is 1000 (`pull_api.cljc:16`), `limit` defaults to it
  (`:315`), the transducer is `(take limit)` (`:323`). No marker, no refusal,
  just a shorter collection. That is *this project's named failure class
  living inside the dependency*: a pull that can exceed the bound reports the
  cut as an **elision naming the bound**. **A wildcard pull is therefore not a
  completeness proof** — it caps each many-valued attribute at 1,000 and
  recursion can yield id-only maps (`pull_api.cljc:238-243`, `:315-351`).
  Obtain the complete value under the declared work bound, or refuse
  (modeling study, §7.1 O4).

---

## 6. When the answer is a query, not an attribute

**Durable system state is a fact; a derived question is a query** (AGENTS.md
§2.2). *"If answering a question requires a convoluted reconstruction —
joining text, guessing from names, walking files — stop: the missing fact is
the root problem."* And its converse, derive-or-die (ledger ruling 23, owner
law 2026-08-13): *every hand-maintained mirror of derivable state must be
DERIVED, ENFORCED by a checker that fails on drift, or DATED as a
point-in-time record.*

| Question | The query | Not an attribute |
|---|---|---|
| Is this turn open? | no `:seon.turn/closed-tx` — decided **at the writer**, inside the transaction (`seon.turn/open-run-tx-call`, `src/seon/turn.clj:417`) | no `open?` boolean |
| Is this issue open? | no `:seon.issue/resolved-tx` | the `:open`/`:resolved` enum arms dissolve (§5.1) |
| Which evaluation supersedes which? | the since-diff's **fold over ordinals** | `:seon.cluster.eval/refreshes` is **deleted** (1h) |
| Who wrote this? | minimal transaction metadata — `:seon.db/user`, `:seon.db/process`, `:db/txInstant` — joined through the datom's transaction | never copied onto domain entities (AGENTS.md §3) |
| Is this evaluation agent-written or system? | reply presence and no provider attempt | no author-kind stamp (data-modeling skill, by name) |
| Which tests exercise this function? | `(seon.fn/tests-reaching (seon.db/db) "seon.turn/open-tx")` | never a naming convention |
| Which functions need cluster custody? | `[?f :seon.fn.arity/input-refs :seon.db/connection]` | never a hand-maintained roster |
| What was true before? | `history` / `as-of` / `since` | never a retirement attribute (G1) |
| Does A call a name with no row? | one Datalog clause over the symbol edge (one `not-join`, measured at 7 rows in E5) | never the population invariant (retired with ruling 47) |

**The three banned substitutes are one mistake in different clothes:** a
hand-maintained list, a naming convention, and a regex over text. *A regex in
production code requires the owner's permission.*

---

## 7. What is still undecided

### 7.1 The Datahike modeling study and its overrides

**Ruling 1i** (owner, 2026-09-17 ~06:35Z, verbatim): *"learn from the datahike
modeling and override our previous decisions on the schemas and refs vs
components or whatever. don't be dogmatic."* The astra study
`datahike-modeling-study` and its note
`docs/prds/steward-platform/research/datahike-modeling-study-2026-09-17.md`
are **the authority that overrides this guide** wherever they correct G1–G6,
1g, 1h or the reset plan. The integrator rebases `reset-batch` on its
corrections before the reset, and **every override is written into the PRD
with the Datahike line that decided it**.

**Verified 2026-09-19:** the complete
[study](../../prds/steward-platform/research/datahike-modeling-study-2026-09-17.md)
is in the tree and was read end to end during the namespace-agent audit.
The overrides below are grounded by that note and its dependency evidence,
not by an unavailable future artifact. The
[data-modeling](../../../.agents/skills/data-modeling/SKILL.md) and
[Datahike](../../../.agents/skills/datahike/SKILL.md) skills carry the same
corrections. Current implementation status still requires source and live
verification; the study's dated missing-mechanism claims are not current
proof of absence.

| # | Override | Carried at | Datahike line |
|---|---|---|---|
| **O1** | A required ref refuses a sweep **only when the surviving row is selected and validated**; required presence is **not** a foreign-key constraint, because numeric ref values do not prove target existence | §2.3 | `db/utils.cljc:109-148` |
| **O2** | The component cascade **does not enforce exclusive ownership**; shared fingerprint-identified shapes stay behind ordinary refs, and validation covers complete owning values discovered before *and* after a child-only edit or unlink | §2.5 | `transaction.cljc:831-836` |
| **O3** | A tuple is one fixed ordered observation and **its complete value is the index key, not each member** | §2.6 | `index/persistent_set.cljc:31-132` |
| **O4** | **A wildcard pull is not a completeness proof** — 1,000-member cap plus id-only maps under recursion | §5.4 | `pull_api.cljc:238-243`, `:315-351` |
| **O5** | An event fact proves exactly the observation its writer completed; **do not require nonempty datoms for a legitimate empty result**, and a digest is content identity, not an observation count | §3.2 | `transaction.cljc:718-770`, `:587-625` |
| **O7** | A settlement that **moves** an edge is application logic, **not a fifth native deletion mode**; do not copy the message-inbox move — 1h restores listened `:seon.message/to` plus a handling-turn claim | §2.2 | (Seon-side; `src/seon/turn.clj:435-437`) |
| **O8** | Retraction preserves history **only with `keep-history?` and without `:db/noHistory`**; **purge is not an all-snapshots erasure guarantee** — retained commits still reach old index nodes and GC does not prune a retained head's history | §1.4 | `transaction.cljc:440-484`; `gc.cljc:22-81`, `:144-167` |
| **O9** | **A value edge is not automatically indexed** — refs and both unique kinds are; an ordinary symbol edge needs an explicit index. There is no VAET | §2.1 | `db/utils.cljc:307-313`; `db.cljc:310` |
| **O6** (last, because it qualifies a ruling) | **A positive transition fact is the current state only when its writer actually supplies one.** `:db/noHistory` deliberately removes the history guarantee; an imported observation time is not its import transaction time. Live counterexamples the study measured: **terminal issue status without a `resolved-tx`**, zero-argument arity without argument datoms, and separate message subject/sender | §1.1, §4.1, §5.1 — **this one qualifies a ruling**, see below | `transaction.cljc:440-484` |

**O6 is the one that changes an answer in this guide.** §1.1 and §5.1 say the
issue's `:open`/`:resolved` enum arms dissolve into `:seon.issue/resolved-tx`,
because the attribute's own docstring says *"absence means open"*
(`resources/seon/schemas/seon.issue.edn:38`). The study measured **live issues
in a terminal status with no `resolved-tx` datom**. Two readings, and the
guide does not pick between them: either the writer is incomplete (and the
fix is the writer, after which the enum still dissolves), or terminal status
genuinely arrives on a path that supplies no transition fact (and the enum
stays until that path does). **Resolve this when the note lands; do not
dissolve `:seon.issue/status` before then.**

When the note lands:

1. Re-source O1–O9 against the note itself and drop the provisional marking.
2. Record every further override as its own row here, with the Datahike
   `file:line` that decided it, beside the ruling it corrects — **do not
   delete the corrected ruling**; mark it superseded, the way §1.5 and §2.8 do.
3. Update the PRD §1i in the same commit.
4. Settle O6 against `:seon.issue/status` explicitly.

### 7.2 Decisions the owner still owns

| # | Question | Where it lives |
|---|---|---|
| 1 | The per-family SCOPE of the `-tx` versus `-at` conversion (§4.3) | J3; `reset-batch-2026-09-17.md:211` |
| 2 | `:seon.effect/content-blobs` — ordered vector or set (read the writer first) | J7 |
| 3 | `:seon.lint/file` — ref or path string (§2.8, recommendation: keep the ref) | program-graph §5.4 |
| 4 | Shared content-addressed shape rows: reclaim or leave (2 781 + 3 015 referrers; **nothing retracts them**) | program-graph §5.3, recommendation *"leave them and record the count at each reset"* |
| 5 | `:seon.wake/inside` derived from transaction provenance instead of declared on `from` — *"revisited with the listening redesign (needs a positive human account identity on inbound transactions)"* | ruling 1h |
| 6 | The open blocker issue's remaining half: reference-only identities and the second validator | [retained identities have no declared retirement state](../../seon/issues/retained-identities-have-no-declared-retirement-state.md); G3 |
| 7 | `:seon.eval/origin`'s admitted target families, now that it is a value | 1h vs N12 (§2.8) |

The owner's full open list is the roadmap's decision rows in
[the steward-platform plan README](../../prds/steward-platform/plan/README.md)
and [owner decisions, 2026-09-17](../../prds/steward-platform/plan/owner-decisions-2026-09-17.md).

---

## 8. Two things this guide could not source

1. **No source states what a deletion refusal *renders* to the agent.**
   unbreakable-connections §6 designs the four operations
   (`my.program/delete!`, `rename!`, and two more) and names the refusal's
   payload shape, and program-graph §2.1 says the refusal is the work list —
   but no ruling declares the render pair, the elision behaviour of a
   1 000-caller refusal, or where the issue family picks it up. It is
   modeling-adjacent and it is not decided.
2. **No source enumerates which entities besides turn, issue and agent should
   close by a positive fact.** §1.2's test (*"will anything still address this
   row by identity?"*) is this guide's phrasing of the three ruled cases; it
   is a reading of G1 + 1g + the turn PRD, not a quoted rule. Treat new
   candidates as owner decisions.
