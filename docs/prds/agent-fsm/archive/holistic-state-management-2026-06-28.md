---
type: archive
status: archived
tags: [archive, database, schema, agent, flow]
---

> Superseded — settled content now in [[../../../seon/architecture/agent-runtime]]
> (the `boot-seed! → reconcile!` section) and [[../../../seon/architecture/data-model]]
> (the component cascade + provenance); kept as history.

# Holistic system-state management — one reconcile, provenance-scoped

> Owner direction, 2026-06-28: "I don't think we should be creating separate data loading
> systems for default-seed-blocks and core-routes-tx. Are we going to redo the mechanisms for
> all state? What about backups and restores? We need basic mechanisms … so we can properly
> reset our system (gotcha 33). Come up with a wholistic solution."

> Owner correction, 2026-06-28: "All of this 'kind' framing is wrong. Research datahike and
> understand you never do 'kinds' — it's the absence and presence of a set of attributes and how
> they are connected that determines what you're looking at (regarding holistic state management)."

This supersedes the per-state-area framing in `config-loader-2026-06-28.md` (which made blocks and
routes two separate file-loaded hooks). The owner is right twice: that path adds a new loader per
state area and never delivers backup/restore/reset, AND the first draft of this doc reintroduced a
typed-record taxonomy by parameterizing the fix on a "kind". The corrected fix is ONE primitive,
expressed entirely in attribute / provenance / connection terms, that the existing code already
contains in miniature.

## Why not "kinds" — what datahike actually is

In datahike an entity is nothing but an entity-id plus a set of EAV datoms. **There is no entity
type, class, or "kind" anywhere in the transactor or query engine.** Schema attaches to
ATTRIBUTES, never to entities: a schema entry is `(s/keys :req [:db/ident :db/valueType
:db/cardinality])` keyed by an attribute ident
(`reference-code/datahike/src/datahike/schema.cljc:77`), and the whole system schema is a vector of
attribute-defining maps with no entity-type entity in it
(`reference-code/datahike/src/datahike/constants.cljc:8-158`). Every datahike predicate that sounds
like a type-check — `ref?`, `component?`, `multival?`, `is-attr?` — is really `(contains?
(-attrs-by db property) attr)`, a question about an ATTRIBUTE's property-set
(`reference-code/datahike/src/datahike/db/utils.cljc:16-65`). Identity is the same story:
`:db.unique/identity` is a per-attribute property, and `upsert-eid` resolves a tx-map to an
existing entity by scanning whichever identity attrs the map happens to carry and AVET-looking-up
`[a v]` — it never consults a kind
(`reference-code/datahike/src/datahike/db/transaction.cljc:548-578`).

So "what an entity is" is computed from (a) WHICH attributes are asserted about it and (b) HOW it
connects to other entities via `:db.type/ref` attrs. Seon already models exactly this: a `:map`
schema only becomes catalogued by carrying `{:seon.db/entity true}`, and the comment is explicit
that instances are enumerated by walking the AEVT index for the identity attr, with **"no per-row
`:seon.entity/kind` stamp"** (`src/seon/schema.cljc:202-210`). A grep of `src/` finds no
`:seon.entity/kind` attribute at all — only `:seon.error/kind`, an unrelated error-classification
field. The reconcile below therefore takes NO kind argument and runs NO id-attr-registry loop. It
operates in attribute space: presence, provenance, connection.

## The principle

**System state IS the database, and the managed slice of it is defined by PROVENANCE, not by a
taxonomy.** A row is managed iff its first-assertion tx carries an origin in the managed set (e.g.
`#{:core-seed :config}`); agent-authored rows (`:agent` / `:replay`) sit outside that slice and are
never touched. There is exactly ONE operation worth building — *make the managed datoms match a
desired set of entity-maps* — and **seed, override, backup, restore, and reset are all expressions
of it.** We do not write a loader per state area; we write the reconcile once.

## The mechanism: `reconcile!` (declarative state sync)

One operation. Input: a DESIRED set of entity-maps + a managed scope (a provenance set over
`:seon.db/origin` — the rows this reconcile owns). NO kind parameter, NO id-attr enumeration. The
three moves are all attribute/connection operations:

1. **Add / update — datahike upserts each desired map by its OWN identity attribute.** Each desired
   entity-map already carries some `:db.unique/identity` attr (`:seon.agent/id`, `:seon.eval/id`,
   `:seon.fn/sym`, `:seon.route/name`, …, each registered `{:seon.db/identity true}` which the
   bridge maps to `:db.unique/identity`, `src/seon/db/datahike/schema.clj:108-114`). Transacting the
   map lets `upsert-eid` find-or-create the entity by AVET-looking-up that `[a v]`
   (`transaction.cljc:548-567`) — the SAME code path whether the entity "is" an eval, a turn, or a
   route. No registry of identity attrs is consulted; the row's own attrs are authoritative.

2. **Enumerate the current managed population by PROVENANCE — one scan, no per-attribute loop.**
   "What exists that I might need to retract" is the set of entities whose first (min) tx origin is
   in the managed scope, and `row-origin-scan` already computes exactly that
   (`src/seon/db.cljs:1165-1191`, docstring "Per-ROW, never per-kind-name"): a single `[?e ?a _ ?tx]`
   pass + a min-tx-origin reduce yielding every managed eid — with NO iteration over identity attrs,
   NO per-attribute AEVT slice, no taxonomy. For each managed eid, read its OWN identity datom (the
   `:db.unique/identity` value it carries) directly off the entity. The diff is then a plain
   set-difference of identities: the desired set's (move 1) vs the managed population's (this scan).
   Enumeration is provenance-driven; identity is read per-row by attribute-presence — never a
   per-kind / per-id-attr loop. (An earlier draft sliced AEVT once per identity-attr in the desired
   set; that is the per-kind loop in attribute clothing — rejected. Provenance is the only axis the
   scan walks.)

3. **Remove via retraction + connection cascade.** Retract the managed entities whose identity
   value is absent from the desired set. Where the managed set hangs off a parent through a
   component ref (e.g. `:seon.agent/ctx`, registered `[:vector {:seon.db/component true}
   :seon.db/ref]`), retracting the parent's component attribute cascade-retracts the children —
   datahike's `retract-components` emits `[:db.fn/retractEntity (.-v d)]` for each component ref
   value (`reference-code/datahike/src/datahike/db/transaction.cljc:730-733`). What is removed
   together is the transitive closure of component refs computed at retract time from the live
   datoms — a CONNECTION fact, never a class membership.

That is the algorithm `seon.agent.ctx/upsert-ctx-tx` (`src/seon/agent/ctx.cljs:1687-1695`) already
runs for one agent's context blocks — *retract the component vector, re-add exactly the desired
block set so the stored set matches*. The whole proposal is: **lift that one diff to operate over
any desired set, keyed off each row's own identity attribute and bounded by provenance**, and route
every other managed surface (routes, core entities, …) through it. Blocks then use the general
reconcile too — we delete the special case, we don't keep two.

Three pieces it stands on, all already in the tree:

- `upsert-ctx-tx` — the retract-diff (the algorithm, proven on blocks; `ctx.cljs:1687`).
- per-row `:db.unique/identity` attrs — datahike's find-or-create handle, so the diff needs no
  per-area code (`transaction.cljc:548`).
- `:seon.db/origin` (`src/seon/db.cljs:278`, an enum tag on the TX) + the `row-origin-scan`
  first-tx derivation (`db.cljs:1165`) — the managed-vs-authored discriminator, so reset never eats
  agent work. The seed tags `:core-seed`; config rows tag `:config`; agent rows tag
  `:agent`/`:replay`. Reconcile's managed scope = `#{:core-seed :config}`.

## Everything is now one expression of reconcile

| Operation | What it is |
|---|---|
| **Seed** | `reconcile!` to the DEFAULT desired set (the code defaults). `default-seed-blocks` + `core-routes-tx` STOP being two ad-hoc transacts — they become entity-maps in the one default desired set. ONE seed path. |
| **Config override** | the EDN/markdown loader (config-loader research) produces an OVERRIDE that merges over the code-default desired set; reconcile applies the merged set. The loader is **not a separate loader** — it is the *input that shapes the desired set*. Blocks + routes + the system message all flow through the one reconcile. |
| **Reset** | `reconcile!` to the canonical desired set → retracts managed entities no longer desired. **This fixes gotcha #33 uniformly** — routes get the same retract-diff for free, so the retired `/world` rows vanish without a store wipe. |
| **Backup / export** | project the managed datoms (origin ∈ scope) back into the desired-set EDN (the config-loader's read-projection). The managed DB slice ↔ the EDN config are two views of ONE thing. |
| **Restore / import** | `reconcile!` the imported EDN desired set. |

## Two faces of state — be honest about the one real split

Not all state materializes the same way, and pretending otherwise would be the dishonest "one
mechanism" claim. There are two faces, but they share ONE source of truth (the DB) and ONE backup
story:

- **Declarative state** — blocks, routes, core entities, config, knowledge: data the renderer
  reads. `reconcile!` fully owns it (everything above). Applying a desired map = upsert by its
  identity attr, full stop.
- **Code state** — fns, ns, schemas: also rows (`:seon.fn` / `:seon.ns` / `:seon.schema`, each
  identified by `:seon.fn/sym` / `:seon.ns/name` / `:seon.schema/key`), but materialized into the
  *runtime* by EVAL (`replay-program-graph!` / the analyzer). Their ROWS are reconciled by the SAME
  mechanism (so backup/restore is uniform), but applying a code row ADDITIONALLY evals it. So: one
  reconcile for the data layer; code rows get the eval-materialization they already have. (This is
  the existing "code as data — the runtime IS the database" split, named honestly: declarative rows
  just sit as data; code rows are also replayed.)
- **Agent-authored state** — fns/schemas/knowledge the agent wrote (origin `:agent`): preserved by
  reconcile because it is OUTSIDE the managed provenance scope, backed up as program-graph +
  knowledge rows, restored by replay. A `#{:core-seed :config}` reset never retracts it. (Matches
  the owner's standing rule: functions, schemas, tests persist; agent eval *state* is ephemeral.)

A full system backup = the DB. A full restore = (replay code rows) + (reconcile declarative rows).
A clean reset = reconcile the `:core-seed`/`:config` managed slice to canonical (+ replay the core
code from the build index). The whole-store CBOR dump (datahike `migrate.clj`, wire-server-only) is
a SEPARATE disaster-recovery concern from this config/state export — different scope, both valid.

## Migration (REPLACE, no parallel system)

> Status 2026-06-28: **Step 1 DONE** (mechanism + tests, no boot reroute). `seon.state/reconcile!`
> is live (`src/seon/state.cljs`), built on the new provenance primitive `seon.db/managed-identities`;
> `:config` added to the `:seon.db/origin` enum; the #35 cascade bug fixed in
> `seon.agent.ctx/upsert-ctx-tx` (`:db.fn/retractAttribute`, not plain `:db/retract`). Tests in
> `test/seon/state_test.cljs`; full CLJS suite green. Steps 2–4 (boot reroute / config loader /
> export) remain.

1. **Build `reconcile!`** — generalize `upsert-ctx-tx`'s retract-diff to (desired-set,
   managed-scope), upserting each map by its own `:db.unique/identity` attr and diffing the current
   population (attribute-presence + origin first-tx filter) against the desired identities.
   Unit-test the diff (add / update / retract / leave-authored-alone) per the existing block tests.
2. **Route the seed through it** — `boot-seed!` builds the default desired set ({blocks, routes,
   core entities}) and calls `reconcile!` once instead of N ad-hoc transacts. Blocks drop their
   bespoke `upsert-ctx-tx` call site in favor of the general one. **Closes #33** (and #28's
   "secondary doors as route datoms" falls out — they are just more entity-maps in the desired set).
3. **Layer the config loader on top** — the EDN manifest + markdown become the override input that
   merges into the desired set before reconcile (config-loader research, now reframed as "produce
   the override map," not "two loaders").
4. **Export = the read-projection** — one fn projects managed datoms → the desired-set EDN; restore
   = feed it back to reconcile. That is backup/restore.

## Correctness notes the build MUST honor (grounded in datahike source)

These are the attribute/connection facts that make or break reconcile; ignoring them silently
duplicates or orphans rows.

- **Upsert keys off `:db.unique/identity` ONLY** (`transaction.cljc:548-549`). A `:db.unique/value`
  attr (seon's `{:seon.db/unique true}`) enforces uniqueness but does NOT auto-resolve a tx-map onto
  an existing entity. Each desired map must carry exactly ONE `:seon.db/identity` attr as its handle.
  A map with NO identity attr always allocates a fresh eid (`transaction.cljc:863`) — reconcile would
  duplicate that row every run. A map carrying TWO identity attrs that resolve to different existing
  eids raises "Conflicting upserts" (`transaction.cljc:571-578`).
- **Cardinality-many never auto-replaces.** Transacting `{:attr v}` on a many attr ADDS a value
  (`explode` wraps via `maybe-wrap-multival`); it does not replace. To make a set-valued attribute
  match a desired set you must explicitly retract the unwanted values — "omit a key = leave
  unchanged", never "absent = retract". This is precisely why `upsert-ctx-tx` first retracts
  `:seon.agent/ctx` then re-adds the kept+new blocks (`ctx.cljs:1693-1695`).
- **Use the cascading retract op, not plain `:db/retract`, for component parents.** datahike's plain
  `:db/retract` branch returns NO follow-on ops — it does NOT call `retract-components`
  (`transaction.cljc:959-970`); only `:db.fn/retractAttribute` (`972-977`) and
  `:db.fn/retractEntity` (`979-981`) cascade. **Code smell flagged:** `upsert-ctx-tx`
  (`ctx.cljs:1693`) and `remove!` (`ctx.cljs:1731-1736`) use `[:db/retract [:seon.agent/id id]
  :seon.agent/ctx]` and their docstrings claim it "cascade-retracts the old child block entities —
  datahike component semantics," but per the source it does NOT — it severs the agent→block edges
  and leaves the block child entities (`:seon.agent.ctx/name`, `:seon.render/ai` datoms) ORPHANED in
  the store. Render stays correct (the `:seon.agent/ctx` pull only follows live refs), but the store
  leaks dead block rows on every `install!`/`remove!`. The general `reconcile!` should emit
  `[:db.fn/retractAttribute [:seon.agent/id id] :seon.agent/ctx]` to get the documented cascade, and
  the existing block code should be corrected to match (one fix, not a second path).
- **An attribute's datahike schema installs at FIRST transact!, not at `register!`.** Scanning
  `(datoms db :aevt attr)` or a where-clause naming an attr the conn has never seen throws; gate
  enumeration on `(db/installed-schema db)` membership (`src/seon/db.cljs`). Identity attrs must be
  registered (and the schema layer replayed) before reconcile can rely on upsert.
- **Component cascade is recursive and severs inbound refs.** `retract-entity` also retracts every
  inbound `:db.type/ref` pointing at a dying child (`transaction.cljc:897-914`), so cascade-deleting
  a child silently drops any other pointer to it. A reconcile over shared children must expect that.

## Open questions (for the owner / the build)

- **Per-agent scope.** Blocks live per-agent (in `:seon.agent/ctx`); routes/core entities are
  global. `reconcile!` takes an optional owner so the block diff scopes to one agent. (Already how
  `upsert-ctx-tx` works — the agent's `:seon.agent/id` is the connection that bounds the population.)
- **Bootstrapping order.** Identity attrs (`:seon.db/identity`) must be registered before reconcile
  can rely on datahike's unique-attr upsert, so the schema layer comes first (code `register!` +
  replay), THEN reconcile applies declarative state. Same ordering boot already has.
- **Provenance taxonomy.** Confirm the managed scope set: `#{:core-seed :config}` reconciled,
  `#{:agent :replay}` preserved. This is the one new convention; everything else exists.
- **Does reconcile own `:seon.schema`/`:seon.fn` rows, or only declarative entities?**
  Recommendation: reconcile owns ALL managed ROWS uniformly (backup/restore symmetry), but only
  declarative entities are "done" after reconcile; code rows hand off to replay for eval. Confirm we
  want the row-level uniformity vs leaving code rows entirely to the analyzer.
