---
type: prd
status: draft
tags: [prd, database, schema, agent, flow]
---

# Holistic system-state management — one reconcile, not per-kind loaders

> Owner direction, 2026-06-28: "I don't think we should be creating separate data loading
> systems for default-seed-blocks and core-routes-tx. Are we going to redo the mechanisms for
> all state? What about backups and restores? We need basic mechanisms … so we can properly
> reset our system (gotcha 33). Come up with a wholistic solution."

This supersedes the per-kind framing in `config-loader-2026-06-28.md` (which made blocks and
routes two separate file-loaded hooks). The owner is right: that path adds a new loader per
state-kind and never delivers backup/restore/reset. The fix is ONE primitive that the existing
code already contains in miniature.

## The principle

**System state IS the database. Every loadable/resettable thing is rows of some entity-kind.**
So there is exactly ONE operation worth building — *make the DB's managed rows of a kind match a
desired set* — and **seed, override, backup, restore, and reset are all expressions of it.** We do
not write a loader per kind; we write the reconcile once and parameterize it by kind.

## The mechanism: `reconcile!` (declarative state sync)

One operation. Input: a DESIRED set of entity rows (grouped by kind) + a managed-scope (a
provenance tag — which rows this reconcile owns). For each kind:

1. Look up the kind's identity attr from the schema registry (`:seon.entity/id-attr` — already
   derived for every `{:seon.db/entity true}` schema, schema.cljc:205). This is the diff key. No
   per-kind code.
2. Query the CURRENT *managed* rows of that kind (origin ∈ scope) → their identities `C`.
3. The desired identities = `D`. Then in ONE tx: **upsert `D`** (add new / update changed) and
   **retract `C − D`** (remove rows no longer desired). Rows outside the managed scope
   (agent-authored) are never touched.

That is exactly what `seon.agent.ctx/upsert-ctx-tx` (ctx.cljs:98) does today — *"a diff (additions +
explicit retractions) so the stored set EXACTLY matches"* — but only for one agent's context
blocks. The whole proposal is: **lift that one function to be kind-general** via the id-attr
registry, and route every other kind (routes, core entities, …) through it. Blocks then use the
general reconcile too — we delete the special-case, we don't keep two.

Three pieces it stands on, all already in the tree:

- `upsert-ctx-tx` — the retract-diff (the algorithm, proven on blocks).
- `:seon.entity/id-attr` — the per-kind identity key (so the algorithm is kind-agnostic).
- `:seon.db/origin` (db.cljs:1161) — the managed-vs-authored discriminator (so reset never eats
  agent work). The seed already tags `:core-seed`; config rows tag `:config`; agent rows tag
  `:agent`/`:replay`. Reconcile's managed-scope = `{:core-seed :config}`.

## Everything is now one expression of reconcile

| Operation | What it is |
|---|---|
| **Seed** | `reconcile!` to the DEFAULT desired set (the code defaults). `default-seed-blocks` + `core-routes-tx` STOP being two ad-hoc transacts — they become entries in the one default desired-set. ONE seed path. |
| **Config override** | the EDN/markdown loader (config-loader research) produces an OVERRIDE that merges over the code-default desired-set; reconcile applies the merged set. The loader is **not a separate loader** — it is the *input that shapes the desired set*. Blocks + routes + the system message all flow through the one reconcile. |
| **Reset** | `reconcile!` to the canonical desired set → retracts stale rows. **This fixes gotcha #33 uniformly** — routes (and every kind) get the retract-diff for free, so the retired `/world` rows vanish without a store wipe. |
| **Backup / export** | project the managed rows back into the desired-set EDN (the config-loader's read-projection). The DB's managed state ↔ the EDN config are two views of ONE thing. |
| **Restore / import** | `reconcile!` the imported EDN desired-set. |

## Two faces of state — be honest about the one real split

Not all state materializes the same way, and pretending otherwise would be the dishonest
"one mechanism" claim. There are two faces, but they share ONE source of truth (the DB) and ONE
backup story:

- **Declarative state** — blocks, routes, core entities, config, knowledge: data the renderer
  reads. `reconcile!` fully owns it (everything above).
- **Code state** — fns, ns, schemas: also rows (`:seon.fn` / `:seon.ns` / `:seon.schema`), but
  materialized into the *runtime* by EVAL (`replay-program-graph!` / the analyzer). Their ROWS are
  reconciled by the SAME mechanism (so backup/restore is uniform), but applying a code row
  ADDITIONALLY evals it. So: one reconcile for the data layer; code rows get the eval-materialization
  they already have. (This is the existing "code as data — the runtime IS the database" split,
  named honestly: declarative rows just sit as data; code rows are also replayed.)
- **Agent-authored state** — fns/schemas/knowledge the agent wrote (origin `:agent`): preserved by
  reconcile (outside the managed scope), backed up as program-graph + knowledge rows, restored by
  replay. A `:core-seed` reset never retracts it. (Matches the owner's standing rule: functions,
  schemas, tests persist; agent eval *state* is ephemeral.)

A full system backup = the DB. A full restore = (replay code rows) + (reconcile declarative rows).
A clean reset = reconcile the `:core-seed`/`:config` managed state to canonical (+ replay the core
code from the build index). The whole-store CBOR dump (datahike `migrate.clj`, wire-server-only) is
a SEPARATE disaster-recovery concern from this config/state export — different scope, both valid.

## Migration (REPLACE, no parallel system)

1. **Build `reconcile!`** — generalize `upsert-ctx-tx`'s retract-diff to (kind, desired-set,
   managed-scope) keyed off `:seon.entity/id-attr`. Unit-test the diff (add / update / retract /
   leave-authored-alone) per the existing block tests.
2. **Route the seed through it** — `boot-seed!` builds the default desired-set ({blocks, routes,
   core entities}) and calls `reconcile!` once instead of N ad-hoc transacts. Blocks drop their
   bespoke `upsert-ctx-tx` call site in favor of the general one. **Closes #33** (and #28's
   "secondary doors as route datoms" falls out — they're just more rows in the desired-set).
3. **Layer the config loader on top** — the EDN manifest + markdown become the override input that
   merges into the desired-set before reconcile (config-loader research, now reframed as
   "produce the override map," not "two loaders").
4. **Export = the read-projection** — one fn projects managed rows → the desired-set EDN; restore =
   feed it back to reconcile. That is backup/restore.

## Open questions (for the owner / the build)

- **Per-agent scope.** Blocks live per-agent (in `:seon.agent/ctx`); routes/core entities are
  global. `reconcile!` takes an optional owner so the block diff scopes to one agent. (Already how
  `upsert-ctx-tx` works.)
- **Bootstrapping order.** Schemas must be registered before reconcile can look up id-attrs, so the
  schema layer comes first (code register! + replay), THEN reconcile applies declarative state. Same
  ordering boot already has.
- **Provenance taxonomy.** Confirm the managed-scope set: `{:core-seed :config}` reconciled,
  `{:agent :replay}` preserved. This is the one new convention; everything else exists.
- **Does reconcile own `:seon.schema`/`:seon.fn` rows, or only declarative kinds?** Recommendation:
  reconcile owns ALL kinds' ROWS uniformly (backup/restore symmetry), but only declarative kinds are
  "done" after reconcile; code kinds hand off to replay for eval. Confirm we want the row-level
  uniformity vs leaving code rows entirely to the analyzer.
