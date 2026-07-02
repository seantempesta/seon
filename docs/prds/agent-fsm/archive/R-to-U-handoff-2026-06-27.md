---
type: orchestrator
status: active
tags: [orchestrator, agent, web, architecture]
---

# R → U handoff — everything decided (2026-06-27, late)

A complete handoff of the owner-settled decisions from R's design session, so U
can finish the core-doc updates. **The docs LAG these decisions** — they still
encode the superseded provider seam / render-merge / `:seon.agent/purpose`.
Detailed inputs to fold in: `reconciliation-recommendations-2026-06-27.md`
(P0/P1 + 7-step order) and `kind-removal-migration-2026-06-27.md` (the full
`:kind` audit — §4 below summarizes it; **do not lose it**).

## 1. The context-block model — SETTLED (override/seed/install!)

- **Naming (owner-ruled):** the unit is **block**; the keyword namespace moves
  `seon.ctx` → **`seon.agent.ctx`** → `:seon.agent.ctx/block` / `:seon.agent.ctx/name`
  / `:seon.agent.ctx/priority`. The agent's **collection attr stays `:seon.agent/ctx`**
  (component vector on the agent entity). `install!`/`remove!` live in `seon.agent.ctx`.
- **ONE collection, SEED-COPY.** All blocks are **seeded into the agent's own
  `:seon.agent/ctx` at creation**; render reads the agent's COMPLETE `:seon.agent/ctx`
  sorted by priority. There is **NO render-time merge over a separate
  `default-blocks`**. The whole **`set-blocks-provider!` / `!blocks-provider` /
  `default-blocks` "provider" seam is SUPERSEDED — delete it everywhere** (all 4 docs
  still carry it; that was R's earlier proposal, now dropped).
- **`install!` is scope-aware + VARIADIC** — `(ctx/install! single-map)` OR
  `(ctx/install! [vector-of-maps])` to load the whole set at once. At boot/no-agent-scope
  it builds the default seed set; in an agent's scope it targets THAT agent's
  `:seon.agent/ctx`. Idempotent **upsert-by-`:seon.agent.ctx/name`**. `remove!` drops by
  name (component cascade retracts the child).
- **Override = `install!`/`remove!`, period.** No provider fn, no fork, no central
  hardcoded `core-blocks` catalog. seon's `my.*` nses DEFINE the render fns + block
  data; the set is **batch-installed at seed**. acme overrides by calling
  `install!`/`remove!` from its own nses (loaded via `SEON_EXTRA_SRC`) — new acme
  agents seed acme's set. Same mechanism for everyone (seon, acme, the agents themselves).
- **Renders are fns/symbols, never stored output.** Block envelope = data
  (name/priority/render-SYMBOLS); render fns = code (`:seon.render/ai`/`:seon.render/html`
  symbols, SCI-bounded for agent-authored); rendered output = ephemeral.
- **Block = a SET ordered by `:seon.agent.ctx/priority`, deduped app-level by name.**
  `name` is a plain `:keyword`, NOT a datahike identity (global uniqueness would
  forbid two agents owning a `:transcript` block). Render sorts by priority with a
  stable by-name tiebreaker. (**U already has this right in data-model §3.2 — keep it.**)
- **Global-vs-per-agent = the DATA's agent-ref**, never the block or a `:kind`.
  `:my.kb.*` rows carry NO agent ref → global (one KB, all agents); `:my.todo/*` rows
  carry `:my.todo/agent` → per-agent (each sees its own). Same block registration; the
  render fn scopes by what it queries.

## 2. Purpose + planning — SETTLED

- **`:my.agent/purpose` = a markdown goal string** (migrate FROM `:seon.agent/purpose`).
  It's the first per-agent **seed worked-example**: register the schema + a `refine`
  fn + a self-refining block, seeded into `my.agent.<id>` so the agent owns + sees it.
  Migrating also **fixes the live bug** (`:seon.agent/purpose` is never installed →
  `set-purpose!` throws).
- **Planning = `my.todo` as a TREE** — give a todo a **`:my.todo/parent` ref** + status
  + a **derived roll-up** (parent progress = its children's). The work-list becomes the
  plan tree (top = plans/milestones, leaves = actions). **NOT a separate `:my.plan`
  system.** Folds into the `seon.agent.todo → my.todo` migration (task #56).

## 3. Loop + bootstrap + orchestrator-root — SETTLED

- **The loop starts on a TRIGGER, not at creation.** Creation = an IDLE agent entity
  (id + optional `:seon.agent/default-turn-limit`/`default-deadline-ms` seeds + the ctx
  seed + the home ns). A trigger (inbound message, or a due schedule via the ticker)
  opens a RUN (run-id fencing token, started-at, trigger, cause, turn-limit/deadline
  DERIVED from the agent's defaults, status `:open`, atomic `[:db.fn/cas …:seon.agent/run
  nil…]`); `run-loop!` then drives turns.
- **Bootstrap = seeded eval'd FORMS** run synchronously in the NEW agent's scope BEFORE
  any trigger, recorded as `:seon.eval` rows with **`:core` origin** so they are QUIET
  (no wake, no turn-count). The batched `(ctx/install! […])`, `(schema/register!
  :my.agent/purpose …)`, the purpose fn, the home-ns defns ARE those commands. The agent
  **sees its own startup** in its transcript/program graph — not hidden core magic.
- **Root agent = ONE `:seon.agent/id "root"`** that is BOTH the **`/`-world owner** (UI —
  U already has this) AND the **system orchestrator** (lifecycle — the docs lack this).
  The two are facets of the same elevated grant, NEVER two entities; the `/`-world
  derives from root's system blocks.
- **`seon.agent/start!`** (owner-ruled: a **core verb granted to root**, NOT root-authored)
  = alias of `create!`, called through the SAME `/call` capability gate. It transacts an
  idle child + **writes `:seon.agent/parent` = root** (that write IS the activation of the
  `:seon.agent/parent` attribute, today flagged "aspirational"). "Start" = create +
  quiet bootstrap, leaving the child IDLE; to make it work, send it a message (that
  message is its first trigger). Two steps, one entry verb.
- **Root's own bootstrap = the cluster-boot base case** (root has no parent; boot seeds
  it the same way `start!` seeds a child, with the elevated form-vector). Recursion
  bottoms out: boot → seed-root → root.start!(child).
- **Roles = capability-SETS, not a `:kind`/`:role` enum.** Orchestrator = an agent
  GRANTED spawn/terminate/system fns; worker = without. Differentiation is Datomic
  presence/absence at the `/call` gate.

## 4. The `:kind` recommendations — PRESERVE THESE (full)

UX flagged `:kind` misuse. After grounding in `reference-code/` (datahike + malli) and
REPL-proving, the situation is **much smaller than feared**:

- **Grounded facts (cited in `kind-removal-migration-2026-06-27.md`):** datahike has NO
  entity-type/class — an entity IS its attributes; schema is per-attribute; entities are
  enumerated by walking AEVT *for an attribute*. malli `:map` validates by required-key
  presence; **`:orn` + `m/parse`** returns a `Tag` whose **`:key` IS the identified kind**
  in one structural pass; `:multi` dispatches on an arbitrary fn, not a stored field.
- **Replacement model:** an entity's kind = the **required-attr subset match** against
  registered `{:seon.db/entity true}` schemas (most-specific = highest required-attr count
  wins) for STORED rows, or malli **`:orn`/`m/parse`** for IN-FLIGHT values. You NEVER
  store a field whose job is to select which schema a row obeys. **The active pod already
  does exactly this** (`:seon.entity/id-attr` → `entity-primary-kind`) — the work
  GENERALIZES it, doesn't invent.
- **Audit result:** the **active CLJS pod has ZERO stored entity-kind discriminators.**
  - The two the data-model doc flagged — `:seon.error/kind` (error/instrument.cljc:62)
    and `:seon.warn/kind` (warn.cljs:50) — are **RECLASSIFIED KEEP**: they are value-enums
    on NON-entity *values* (an error envelope's fault-tag, a derived warning's source),
    not entity-kind selectors. The presence rule doesn't apply. (Optional: rename to drop
    the word "kind".)
  - **1 truly-BAD** stored entity discriminator: `seon.ai/::type` (`:session`/`:message`/
    `:tool-call`) — **JVM track, paused** → fix when that track resumes.
  - **1 doc slip:** `toolkit-catalog.md:500` `:seon.code/kind` is actually a derived
    response label — just clarify the wording.
  - 2 JVM-track watch items; the rest KEEP (derived labels, third-party shapes, the render
    mechanism itself).
- **Migration order:** (0) lock the **entity-kind-vs-value-enum distinction** in
  `data-model` → (1) fix the `toolkit-catalog.md:500` slip → (2) optional pure-add
  `seon.db/entity-kind` + `seon.schema/kind-of`/`fingerprint` (**do NOT touch
  `render.cljs` signatures — that's U's `web/**` lane**) → (3) deferred: value-tag renames
  (the one silent-failure risk — `:seon.error/kind` is READ in `db/internal.cljs:1092` to
  retag user-input vs core-bug; must be atomic) → (4) JVM-track `seon.ai/::type` later.
- **Flagged bug-watch:** a tight `db/transact!`-then-lookup-ref in ONE eval raced the
  schema install ("Lookup ref attribute should be marked as :db/unique"); re-reading the
  conn after the write settled was correct. Likely intra-eval async timing, not a bug —
  but if `transact!`'s returned db value can lag the installed schema, a tight
  transact-then-lookup-ref could surprise callers. Not chased; flagging.

## 5. The doc issues to fix (from the reconciliation audit)

- **P0 — data-model internal contradiction:** blocks are seed-copied per-agent component
  children AND render-merged over a separate `default-blocks` provider (mutually
  redundant under seed-copy). → Delete the merge/provider half.
- **P1:** the provider seam is in ALL 4 docs (delete); render-merge instead of seed-copy
  (delete `gather-blocks`-over-`default-blocks`); `install!` absent (add, scope-aware +
  variadic); central public `core-blocks` catalog (delete); `:seon.agent/purpose` not
  migrated (→ `:my.agent/purpose`); the `seon.ctx`→`seon.agent.ctx` rename now RATIFIED
  (owner-decided — propagate).

## 6. Core docs + lane

- **Canonical set (owner-decided): `architecture.md` (the map) + `data-model.md`
  (entities + the `my.kb`/`my.todo`/`my.agent` domain schemas + the data-ref scoping) +
  `layout-context-migration.md` (the file:line path).** Supersede/fold:
  `layout-context-unification-design`, `context-render`, `agent-runtime-spec`,
  `agent-loop`. Keep `datahike-primer` as the mindset reference.
- **Lane:** R owns context/schema/seed/render-engine + the orchestrator-root verbs
  (`start!`, the bootstrap, the `my.*` schemas, the `:kind` generalization in `seon.db`/
  `seon.schema`); **U owns `seon.ui.*`/web/reitit/css** + the slot/layout/`!last-tree`
  diff + the error-tile render. The `:seon.agent/sections`→`:seon.agent/ctx` +
  `:seon.ctx/section`→`:seon.agent.ctx/block` rename touches U's `web/tile.cljs` — it's the
  cross-lane atomic part; grep-verify zero of the old keywords before the cluster reset
  (web Datalog reads of a missed attr fail SILENTLY with empty results).

## 7. Suggested sequence (dependency-ordered)

1. The naming is ratified (`:seon.agent.ctx/*`) — propagate everywhere first.
2. **Keystone:** kill the provider seam + render-merge across all 4 docs; adopt seed-copy
   + variadic `install!`/`remove!` (resolves P0 + most of P1 in one pass).
3. Migrate purpose → `:my.agent/purpose` (proves the seed/install! path, fixes the bug).
4. Bootstrap-as-seeded-commands (quiet `:core` forms, pre-trigger).
5. Orchestrator-root (`start!`, roles-as-capabilities, root base case, UI==orchestrator).
6. `my.kb`/`my.todo`(tree)/`my.agent` domain schemas + data-ref scoping into `data-model`.
7. `:kind` doc fix (lock entity-kind-vs-value-enum; the slip); cleanup; index-everything.
