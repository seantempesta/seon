---
type: prd
status: active
tags: [prd, agent, database]
---

# Config → DB migration — one source of truth, fully reactive

Owner-ruled 2026-07-10 (settled, do NOT re-litigate):

- **The contract:** config is read and applied at BOOT (fresh start or
  resume): write the values into the DATABASE; from then on EVERY runtime
  read is a db query. Fully reactive from the db.
- **Everything to db, caps included.** The earlier "render caps are global
  process caps, not per-agent datoms" carve-out is explicitly SUPERSEDED.
- **Order:** this migration ships FIRST; the enforcement unit (state marker +
  hook gate + suite invariant) follows as its own unit.
- Limited in-process state is fine — the sanctioned classes are the conn,
  compile-state, ALS, live handles, injection seams, in-flight bookkeeping
  (see the atom audit). The violation being fixed here is config-as-runtime-
  source-of-truth.

Evidence base (read both BEFORE implementing — they carry the full inventory
with file:line):
`research/config-db-reactivity-audit-2026-07-10.md` (the lifecycle audit — the
~40 runtime read sites, categories A/B/C/D, the reconcile mechanisms) and
`research/atom-audit-2026-07-10.md` (the 6 memo caches, the pre-conn
bootstrap constraint, sanctioned-state classes).

**Coordination gate (do not start before this clears):** the tooling/ctx lane
holds uncommitted edits to `src/seon/config.cljs` and `src/seon/client.cljs`.
The sequencing request is logged in [[coordination]] (2026-07-10 entry). This
unit launches only after those files land (or are disclaimed) — take a clean
base. `ctx.cljs` / `transcript.cljs` / `render/*` belong to that lane and are
NEVER touched by this unit; the accessor contract below is what makes that
possible.

## Piece 1 — the `:seon.config` singleton, seeded + reconciled at boot

- One config entity in the db (attribute-per-key, every attr registered via
  `schema/register!` with a real type — no EDN-blob dumping; a config value's
  schema is its contract). Identity via a `:db.unique/identity` attr per the
  house pattern.
- Seeded AND reconciled at every boot by the EXISTING `seon.state/reconcile!`
  `#{:config}` scope — the exact mechanism routes and skills already ride
  (`client.cljs` ~2492-2514). Upsert-by-identity + retract-stale: a key
  removed from the file is retracted from the db at next boot. **No new
  mechanism** — extending the scope set is the whole change.
- Boot order: connect (using the pre-conn sliver, Piece 3) → reconcile config
  into the db → everything else proceeds reading the db.

## Piece 2 — accessors keep their names; internals become db reads

- **Every `seon.config` accessor keeps its exact name and arity** —
  `eval-render-cap`, `result-body-render-cap`, `repl-mode`, `value-*`,
  `render-*`, the dial accessors (`spawn-depth-cap`, watchdog, breaker,
  `repair`, web policy, ns policy), `default-ctx-blocks`,
  `resolve-agent-context`, all of them. Only the implementation changes:
  memoized-manifest read → db query. This is the coordination contract — the
  ~40 caller sites (incl. the ctx lane's files) get ZERO edits.
- Delete the 6 `SEON_CONFIG`-keyed memo caches. Do NOT re-introduce a cache
  reflexively: datahike `:memory`/local reads are sub-ms — **measure before
  caching** (the house rule). If a genuinely hot path shows up, cache against
  the db value (basis-t-keyed), and mark it per the sanctioned-state classes.
- Accessors that are consumed pre-conn (see Piece 3) read the boot sliver
  until the conn exists, the db thereafter — ONE switchover point at
  connect+reconcile, not per-accessor ad-hoc fallbacks.

## Piece 3 — the pre-conn bootstrap sliver (the sanctioned exception)

A small set is consumed BEFORE the conn exists (the atom audit names them:
the `on-core-error` dial, fs/log/blob config, boot ns-policy, and the values
needed to reach the store at all — cluster dir, wire socket). These:

- stay file-read at boot (unavoidable — they're needed to reach the db),
- are STILL seeded into the `:seon.config` entity once connected (so runtime
  reads, forensics, and the inspector see them like everything else),
- and are enumerated ONCE in code with a short comment naming the constraint
  ("consumed pre-conn") — not scattered. If the sliver needs a marker, use
  whatever the enforcement unit later standardizes; don't invent one here.

## Piece 4 — agent-defaults reconcile (provenance → install! fix → healing)

The same boot pass extends to the agent-materialized config (the copy-once
class from the audit):

1. **Provenance on config-seeded agent state.** Blocks and agent attrs
   materialized from config at `create!` are marked with an origin (the
   `:seon.db/origin` pattern the code corpus already uses — e.g. a
   config-seed origin value; read `core-index-tx` in `client.cljs` ~1891-2035
   for the model: content comparison gated by provenance). Store enough to
   distinguish "pristine copy of default X at content C" from
   "agent-diverged" (content hash of the seeded version, matching however the
   code-corpus reconcile compares).
2. **Fix `ctx/install!`'s symbol round-trip** (issue
   `ctx-install-canvas-symbol-roundtrip.md`) — the read path must
   round-trip symbol-valued block attrs faithfully (root cause is the
   storage-bridge/read asymmetry; fix the bridge/read, not call sites). This
   is the mechanism a clean reconcile uses; it's on the critical path.
3. **Boot healing:** for every agent, diff stored config-origin state against
   current defaults — pristine (provenance matches, content matches the OLD
   default) → update/add/remove per the new manifest; diverged → PRESERVE
   untouched (agent customization is sacred). Home-requires and any other
   copy-once agent scalars (the audit's B3 list) ride the same pass.
   Existing agents WITHOUT provenance (pre-migration worlds): treat blocks
   byte-identical to a known current-or-prior default as pristine; anything
   else as diverged — conservative, no clobbering.

Result: `cluster reset` is no longer a config-application tool; "config
edit → restart pod" applies EVERYTHING uniformly; new default blocks reach
every agent at next boot (retiring the surgical-transact workaround used
2026-07-06).

## Piece 5 — the two loose datom-violations (small, same unit)

From the atom audit's violations list:

- **`eval.cljs` `!timeout-ms`** — fold into the `:seon.config` entity (it IS
  a dial; runtime-mutable via transaction now, visible to replay).
- **`shell/internal.cljs` `!jobs` records** — the job RECORD (cmd, state,
  exit, output pointer) becomes datoms; the live child HANDLE stays in the
  atom (sanctioned). **First check recent history**: the OBS-1
  jobs-per-agent work (`cbb41c76`/`153e5c2e`, 2026-07-09) touched job
  scoping — read it; if job records are already datom-backed, this item is
  done, verify and say so. If the remaining gap is real but turns out to be
  its own sizable unit, report back and split it out rather than bloating
  this one.

## What this unit does NOT do

- No enforcement machinery (marker/hook gate/suite invariant) — next unit.
- No edits to `ctx.cljs`, `transcript.cljs`, `render/*` (ctx lane's files).
- No new caching, no `defstate`-style sugar, no second config mechanism.
- No behavior changes to any dial's VALUE — same values, new source of truth.

## Testing + live proof

Hermetic (`bin/test-cljs` fixtures):

- Boot reconcile: seed a config, boot → entity matches; change a value +
  re-reconcile → db updated; REMOVE a key + re-reconcile → attr retracted.
- Accessor equivalence: every migrated accessor returns the same value
  db-backed as it did manifest-backed (a table-driven test over the config
  keys beats 40 hand-written cases).
- Agent healing: a pristine-provenance block updates when the default
  changes; a diverged block survives untouched; an agent from a
  pre-provenance world is treated conservatively.
- `install!` round-trips a symbol-valued block (the canvas case from the
  issue).
- Pre-conn sliver: the on-core-error dial is readable before connect and
  matches the db after.

Suite: ONE full `bin/test-cljs` at the end, green incl. fault gate.

Live proofs (default pod):

1. Edit a render cap in `config/system.edn` → `restart pod` → the new cap is
   IN THE DB (query it) and a rendered context reflects it. Remove a key →
   restart → retracted.
2. **The forensic proof (the payoff):** `cluster fork` at a basis-t BEFORE a
   dial change → the fork's rendered context uses the OLD dial value (config
   is now in history). This is the property that was impossible before —
   prove it, don't infer it.
3. A pre-existing agent (root) picks up a newly-added default block at
   restart with NO surgical transact.

## Docs (same patch)

- `docs/seon/architecture/context.md` §Configuration — rewrite to the new
  truth: config seeds the db at boot; every dial is a datom; the manifest is
  a seed file, not a runtime dependency.
- `docs/seon/components/` config/dev-tools notes if they describe the memo
  behavior; the acme operational note ("removed rows → cluster reset")
  becomes obsolete — update it.
- Mark the two audit research docs' recommendations as adopted (one line
  each); update [[feels-stateful-remaining-work-spec]] Unit 2 (this unit
  subsumes it) and the roadmap we-are-here.
