---
type: prd
status: draft
tags: [prd, agent, architecture]
---

# Reconciliation: agent-fsm context/seed/root docs vs the owner-agreed model

Deciding-architect assessment over four draft docs (all uncommitted, mid-edit):

- `architecture.md` (living document, draft)
- `data-model-2026-06-27.md` (prd, draft)
- `layout-context-unification-design-2026-06-27.md` (draft)
- `layout-context-migration-2026-06-27.md` (draft)

against the 13-item owner-agreed model (seed-copy + scope-aware variadic
`install!`, `:my.agent/purpose`, orchestrator-root, etc.).

## 1. Where we are

The mechanical rename is well underway and largely converged: all four docs
move `:seon.ctx/section` → `:seon.agent.ctx/block`, `:seon.agent/sections` →
`:seon.agent/ctx`, and adopt the block schema (two optional renders selected by
key-presence, no `:kind` discriminator), priority-sort, app-level dedup-by-name,
and name-NOT-a-datahike-identity. `data-model-2026-06-27.md` is the strongest —
it affirms these correctly and even flags a prior doc's "name = the single
identity" error. `renders-are-symbols-never-stored` (item 4) and the
fixed/non-overridable `system-text` (item 12) are aligned everywhere they appear,
and the UI-root-as-an-agent half of item 10 is well-developed.

But the docs LAG the agreed model on the single most important axis: the
override/seed mechanism. All four still encode the SUPERSEDED provider seam
(`set-blocks-provider!` / `!blocks-provider` / `default-blocks` / `core-blocks`)
plus render-time `gather-blocks` merge over a separate default set — and the
scope-aware variadic `ctx/install!` (seed-copy into the agent's complete
`:seon.agent/ctx`) is ABSENT in every doc. `data-model-2026-06-27.md` has gone
half-way and now contradicts itself (stores blocks as per-agent component
children AND merges over a separate provider at render). Purpose has not migrated
to `:my.agent/purpose` anywhere. Entirely missing across all docs: the
orchestrator-root/spawn role + `:seon.agent/parent` writer, bootstrap-as-seeded-
commands, roles-as-capability-sets as a stated principle, the `my.kb`/`my.todo`/
`my.agent` domain-data schemas + data-agent-ref scoping, and index-everything/
show-`my.*`-full. Net: the storage layer migrated, the mechanism did not, and the
per-agent-seed/lifecycle half of the agreed model has no home yet.

## 2. Issues

Priorities: P0 = internal contradiction / breaks the data model. P1 = diverges
from an agreed decision. P2 = missing/gap. P3 = minor/wording. Adversarial
self-checks inline; transients/already-resolved called out and NOT counted.

### P0 — internal contradiction in the data model

**P0-1 — `data-model-2026-06-27.md`: blocks are seed-copied per-agent AND
render-merged over a separate `default-blocks`.**
Location: lines 220-221 (blocks are component children of `:seon.agent/ctx`,
cascade-retract with the agent) vs lines 226-228 + 735-737 (rendering does a
`gather-blocks` merge of "one agent's set merged over `default-blocks`" backed by
a `set-blocks-provider!`/`default-blocks` provider fn). If each agent already
OWNS its complete block set, the render-time merge over a separate default set and
the provider fn are redundant and contradictory. Agreed (items 1-3): render reads
the agent's COMPLETE seeded `:seon.agent/ctx`; there is NO render-merge and NO
provider. Fix: delete the merge/provider half; keep per-agent ownership; the
default set is SEEDED in (copied at creation), not merged at render.
Adversarial check: real contradiction, not a transient — the doc never reconciles
the two and recommends BUILDING the provider (lines 735-737). This is the canary
that the migration is half-done.

### P1 — diverges from an agreed decision

**P1-1 — Provider seam present and load-bearing (all 4 docs).**
`set-blocks-provider!` / `!blocks-provider` / `default-blocks` are documented as
canonical vocabulary and as build work. Locations: `architecture.md` L97-98,
L244-250, L506-507; `data-model` L735-737; `unification` §2 L66-76, L259-260,
L284, L288-289; `migration` §1 L81-90, §3 L107. Agreed (items 2-3): this whole
provider seam is superseded and must be removed. One-line fix: replace the entire
seam with the scope-aware variadic `ctx/install!` verb seeding `:seon.agent/ctx`.

**P1-2 — Render-time merge instead of seed-copy (architecture, data-model,
unification, migration).** `architecture.md` L250/L390; `data-model` L226-228;
`unification` L24/L81-83; `migration` L89-90/L96. Agreed (item 1): render reads
the agent's COMPLETE seeded ctx; no merge over a separate default set. Fix: render
sorts the agent's own `:seon.agent/ctx` by priority and stops — delete
`gather-blocks`-over-`default-blocks`.

**P1-3 — `ctx/install!` (scope-aware, variadic, batch, idempotent upsert-by-name)
is absent everywhere.** No occurrence in any doc; per-agent mutation is `add-block!`/
`remove-block!` over the merge. Agreed (item 2): one verb, single-map OR
vector-of-maps, boot-scope builds the default set, agent-scope targets that agent's
ctx. Fix: introduce `ctx/install!` + `ctx/remove!` as the sole seed/override verbs.

**P1-4 — Central hardcoded `core-blocks` list (unification, migration).**
`unification` L69; `migration` L85/L107/L252 (makes `core-blocks` PUBLIC, the
catalog of record, dual-registers each block "in its owning ns AND in
`core-blocks`"). Agreed (item 3): no central hardcoded list; the `my.*` nses
define render fns + block data, batch-installed at seed. Fix: delete the central
catalog; the seed bootstrap batches `(ctx/install! [...])` from the owning nses.

**P1-5 — Purpose not migrated to `:my.agent/purpose`.**
`architecture.md` L390-392; `data-model` L168; `migration` L184-185 all KEEP
`:seon.agent/purpose`; `unification` is silent. Agreed (item 7): migrate to
`:my.agent/purpose` as the per-agent seed worked-example (register schema + refine
fn + self-refining block, seeded into `my.agent.<id>`), which also fixes the live
`set-purpose!`-throws bug (`:seon.agent/purpose` is never installed). Fix:
migrate + make it the first seed example.

**P1-6 — ns/keyword rename `seon.ctx` → `seon.agent.ctx` is a doc proposal the
agreed model has not ratified.** Docs use `:seon.agent.ctx/block|name|priority`
(`unification` L44-45/L313-314/L333; `migration` L46-60; `data-model` L188-197;
`architecture` glossary); the agreed model writes `:seon.ctx/block|name|priority`.
This touches every block keyword. Adversarial check: could be owner shorthand
rather than a real conflict — but it is one of the docs' explicit "asks to R"
(`unification` L313-314), so it needs a ruling, not an assumption. Fix: owner
ratifies one namespace; propagate. (Note the asymmetry to confirm in the same
ruling: block attrs are `:seon.agent.ctx/*` while the collection attr is
`:seon.agent/ctx` — intentional, but lock it.)

### P2 — missing / gap (no home yet)

**P2-1 — Orchestrator-root role + `:seon.agent/parent` writer absent.**
`architecture` L392/L567 (parent listed, deferred, "no writer"); `data-model`
L169/L376-377 (spawn/terminate NAMED under root's elevated grant, but parent
writer still "aspirational, no writer until spawn"); `unification` and `migration`
absent. Agreed (item 10): root STARTS/MANAGES agents; "start an agent" = `create!`
+ set `:seon.agent/parent = root`, capability-gated. See §3.

**P2-2 — Bootstrap-as-seeded-commands absent everywhere.** In all docs
"bootstrap" means the `cljs.js` compiler, never an agent's startup forms recorded
as `:seon.eval` rows. Agreed (item 9). See §3.

**P2-3 — Roles-as-capability-sets not stated as a principle.** `data-model`
L378-379 ("root = superuser by grant", no `:kind`/`:role` enum) is aligned but
local; the general principle (roles = granted capabilities + which bootstrap ran,
Datomic presence/absence) is unstated. Agreed (item 11). See §3.

**P2-4 — `my.kb`/`my.todo`/`my.agent` domain-data schemas + data-agent-ref
scoping absent.** Only core `:seon.agent.todo/*` exists (`data-model` §3.6).
No `:my.kb.*` (no agent ref = global), no `:my.todo/agent` (scoped). Scoping is
framed as a "system-scoped block" property (root-only) rather than the agreed
"the DATA's agent-ref decides global-vs-per-agent; the render fn scopes by what it
queries" (item 6). Naming mismatch to fold in: `migration` L110 uses
`seon.agent.todo.internal`, not `my.todo`. Fix: add the three domain schemas and
restate scoping as a property of the data, not the block.

**P2-5 — Creation-as-idle-entity not stated; deadline-from-default
under-specified.** Loop-starts-on-trigger is CORRECT where present (`architecture`
L446-451 has the `[:db.fn/cas …:seon.agent/run nil…]`; `data-model` defers run
mechanics to `agent-runtime-spec`), but "creation = an IDLE agent entity, no run"
is not explicitly asserted, and deadline derivation from `default-deadline-ms` is
an open question (`architecture` L565). Agreed (item 8). Fix: state idle-at-
creation; resolve the deadline-default question.

**P2-6 — Index-everything / show-only-`my.*`-full absent everywhere (item 13).**
No statement about indexing all nses' valid forms while rendering only `my.*` in
full. Fix: add to whichever doc owns the program-graph/context surface
(`data-model` §3.10 is the natural home for the index half).

### P3 — minor / wording

**P3-1 — "vector" vs "cardinality-many set" framing.** `architecture` L390/L245,
`unification` L24/L79, `migration` L34 call `:seon.agent/ctx` a VECTOR; agreed
item 5 says SET. Adversarial check: NOT a real divergence — the Malli registration
shape `[:vector {:seon.db/component true} :seon.db/ref]` is the standard seon
idiom that the bridge maps to `:db.cardinality/many`, and `data-model` L84 already
states this. All docs always sort by priority, so order-independence holds. Fix:
one sentence in each doc confirming the vector shape bridges to cardinality-many
and order is never relied upon; no schema change.

**P3-2 — "name = the single identity" wording is overloaded** (`unification`
L57). It is app-level identity, deliberately NOT a datahike identity (contrast
`:seon.route/name` `{:seon.db/identity true}` L134). Intentional and correct;
just reword to "app-level upsert key" to stop the word "identity" colliding.

**P3-3 — block-attr namespace (`:seon.agent.ctx/*`) vs collection-attr
(`:seon.agent/ctx`) asymmetry** — fold the confirmation into the P1-6 naming
ruling.

### Transients / already-resolved (NOT counted as issues)

- The section→block / sections→ctx rename being "in flight" (`architecture`
  L52-53 marks the old names "today") is deliberate and flagged — not a
  contradiction.
- `data-model` flagging the prior doc's "name = single identity" error is the doc
  doing the RIGHT correction, not a defect.
- `system-text` fixed/non-overridable (item 12) is ALIGNED: `unification` L320,
  `migration` L177-182 (and the per-request `:seon.ai/system-prompt` seam survives
  one layer up — `migration` L180-182 confirms item 12's seam question). No
  action beyond confirming it in `architecture`.
- No doc-internal contradiction exists in `architecture`, `unification`, or
  `migration` — each is internally consistent on its (superseded) model. The only
  internal contradiction is P0-1 in `data-model`.

## 3. Orchestrator-root integration (the 6 open problems)

The whole half-of-item-10 that is missing is one idea: **root is an ordinary
agent holding capabilities others don't, not special core machinery.** Concrete
recommendations:

**(a) `start-agent` as a capability-gated verb that activates `:seon.agent/parent`.**
Add `seon.agent/start!` (alias `create!`) as a system-level `:seon.fn` granted to
root, called through the SAME `/call` capability gate (not a bypass). It transacts
a new IDLE agent entity: `{:seon.agent/id, :seon.agent/parent <caller-id>,
optional :seon.agent/default-turn-limit / :seon.agent/default-deadline-ms seeds,
the ctx seed, a fresh `my.agent.<id>` home ns}`. The `:seon.agent/parent` write IS
the activation of that attribute — no separate writer is needed, and it removes
the "aspirational, no writer" status. The gate check: caller must hold the
spawn capability; root does by grant, a normal agent does not unless granted.

**(b) Where seeded bootstrap commands live + who runs them + quiet-eval.**
An agent's bootstrap is a **form-vector** carried with the seed (a default
form-vector for ordinary agents; root gets an elevated one). After `start!`
transacts the entity, it runs those forms SYNCHRONOUSLY in the NEW agent's scope,
BEFORE any trigger can open a run, recording each as a `:seon.eval` row with
`:seon.eval/origin :core` (or `:human`). Origin ≠ `:agent` makes them QUIET: the
loop's turn-counter and wake logic ignore them, no run opens. Because they are
`:seon.eval` rows in the agent's own scope, the agent SEES its own startup in its
transcript/ctx — not hidden core magic. The bootstrap forms ARE the agreed seed
commands: the batched `(ctx/install! [...])`, `(schema/register! :my.agent/purpose …)`,
the purpose refine fn, and any home-ns `defn`s.

**(c) Roles as capability-sets, not a `:kind`.** No `:seon.agent/role` / `:kind`
attr. A role = (the set of `:seon.fn` capabilities granted to the agent) + (which
bootstrap form-vector ran). "Orchestrator" = an agent granted the spawn/terminate/
cross-agent fns; "worker" = an agent without them. Differentiation is Datomic
presence/absence of grants, queried at the `/call` gate. `data-model` L378-379
already states the root case ("superuser by grant"); generalize that one sentence.

**(d) The root's own bootstrap = the cluster-boot base case.** Cluster boot seeds
the root agent the SAME way `start!` seeds a child, except root has NO parent
(`:seon.agent/parent` absent — root IS the base case of the recursion). Boot runs
root's elevated bootstrap form-vector: install the system-scoped blocks, seed the
`/`-world layout symbol on root's route, grant the spawn/terminate/system fns.
After boot, root is an idle agent that owns `/` and holds the lifecycle
capabilities. The recursion bottoms out cleanly: boot → seed-root →
root.`start!`(child) → seed-child → …

**(e) Bootstrap (at creation) vs loop-trigger (the first run): TWO steps, ONE
entry verb.** `start!`/`create!` does creation + bootstrap (quiet, synchronous,
no run opened) and leaves the agent IDLE. The FIRST run opens only when a trigger
arrives — inbound message or a due schedule via the ticker — via the existing
`[:db.fn/cas … :seon.agent/run nil …]`. So "start an agent" = create+bootstrap
(idle); "run an agent" = trigger-driven, separate. If a caller wants the child to
immediately work, it SENDS the child an inbound message as a distinct act; that
message is the trigger that opens run #1. This keeps creation pure and the loop
strictly trigger-driven (item 8 preserved).

**(f) UI-root and orchestrator-root are the SAME entity.** ONE `:seon.agent/id
"root"`. Its `/`-world is DERIVED from its system-scoped ctx blocks (the UI role,
which the docs already have) AND it holds the spawn/terminate/lifecycle
capabilities (the orchestrator role, which the docs lack). Both are facets of the
same elevated grant + same bootstrap. The "start an agent" affordance on the
`/`-world is just a UI surface over the orchestrator capability — it calls root's
`start!` through `/call`. There is no second "supervisor"/"dashboard" entity. The
fix is purely additive: attach the lifecycle capabilities to the already-seeded
root, never invent new core machinery.

## 4. Recommended resolution order

Dependency-ordered so each step unblocks the next:

1. **Ratify the ns/keyword naming (P1-6, P3-3).** `:seon.ctx/*` vs
   `:seon.agent.ctx/*`, the collection attr `:seon.agent/ctx`, and confirm the
   vector→cardinality-many bridge (P3-1). Everything references these names; settle
   first. Owner ruling.
2. **Kill the provider seam + render-merge; adopt seed-copy + `ctx/install!`
   (P0-1, P1-1, P1-2, P1-3, P1-4).** This is the keystone — one rewrite resolves the
   P0 data-model contradiction AND all four override divergences. Rewrite the
   override sections of all four docs to the `install!`/seed-copy model; delete
   `set-blocks-provider!`/`!blocks-provider`/`default-blocks`/`core-blocks`.
3. **Migrate purpose → `:my.agent/purpose` as the first seed worked-example
   (P1-5).** Rides on the now-defined seed/`install!` path and fixes the live
   `set-purpose!` bug — proves the seed mechanism end-to-end. Depends on step 2.
4. **Define bootstrap-as-seeded-commands (P2-2).** The form-vector, quiet
   `:seon.eval` origin, run-before-trigger. The batch `(ctx/install! […])` and the
   purpose registration ARE the canonical bootstrap commands, so this depends on
   steps 2-3.
5. **Add the orchestrator-root (P2-1, P2-3, §3 a-f).** `start!`/`create!`
   capability-gated verb activating `:seon.agent/parent`; roles-as-capability-sets;
   root bootstrap = boot base case; UI-root == orchestrator-root. Depends on step 4
   (it runs the child's bootstrap) + the `/call` gate. Lands in `architecture.md`
   (it owns agent lifecycle) as a new section.
6. **Add `my.kb`/`my.todo`/`my.agent` domain schemas + data-agent-ref scoping
   (P2-4).** Mostly independent; depends only on step 1's naming. Can run in
   parallel with 3-5. Lands in `data-model`.
7. **Cleanup (P2-5, P2-6, P3-2): creation-as-idle + deadline-default; index-
   everything/show-`my.*`-full; wording.** Last, once the structural model is
   settled.

Then a **coherence pass + doc-ownership assignment**: `data-model` owns schemas +
the index half; the unification/migration docs own the `install!`/render
mechanism; `architecture` owns root/bootstrap/loop/lifecycle; `unification` owns
UI/render. Confirm `system-text` (item 12) is stated identically in all four.
