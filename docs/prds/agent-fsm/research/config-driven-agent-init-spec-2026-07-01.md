---
type: research
status: draft
tags: [research, agent, config]
---

# Config-driven agent initialization + context composition (SPEC v3 — FINAL)

One `init-agent!` takes a Malli-spec'd, aero-loaded config manifest describing the
ENTIRE agent + its context, and wires everything from it via ONE component-ref
`db/transact!`. This is the CONTEXT-CONFIG half of the `init-agent!` unification
(the lifecycle half landed in `bfac6f50`). SPEC + BUILD PLAN — no build until the
plan is reviewed.

**Canonical inputs:** the 23-decision log
[[config-driven-agent-init-decisions-2026-07-01]] (the settled owner directives)
and the cross-lane namespaces additions [[config-driven-agent-init-namespaces-additions-2026-07-01]]
+ [[compact-namespace-cards-spec]] (owned by the parallel namespace-display lane).
This spec folds all 23 decisions; §7 is the build plan (checkpoints + completeness
ledger + gates).

## TL;DR

- **Two-level entity model (decisions 13-17).** Context = the AGENT entity +
  component-ref'd BLOCK entities (`:seon.agent/ctx`, already
  `[:vector {:seon.db/component true} :seon.db/ref]`, agent.cljs:184). Agent-level
  config = scalar attrs on the agent; per-block config = attrs on each block
  entity, colocated with that block's render code. Block identity =
  `:seon.agent.ctx/name` (upsert/order handle — NOT a `:kind`).
- **NO `:map-of` / vector-of-map config values (decision 22, VERIFIED at
  `db/internal.cljs:243`).** The `seon.db` bridge serializes any complex value to a
  `pr-str`'d EDN blob → kills per-element queryability + reactivity (the decision-2
  keystone). So config is: **presence-sets** (cardinality-many attrs, membership =
  config), **scalars** (bool/int/keyword), or **reified component entities** (the
  moment a per-element facet carries a VALUE — transcript tiers + decay levels).
  `block-priorities` DISSOLVES → a per-block `:seon.agent.ctx/priority` attr.
- **Reactive config-on-record (decision 2, the keystone).** Every renderer reads
  its config from the agent's / block's datoms AT RENDER TIME — never from a
  hardcoded const. One `db/transact!` to a config datom re-derives the next context
  AND the datastar UI, no apply step. The refactor = move the 11 hardcoded-default
  reads (§3) to datom reads.
- **Loading = ONE nested component-ref transact (decision 16).** aero reads a
  SPARSE manifest → `resolve-agent-context` (merge `agent-context ← root-context ←
  per-mint override`, then `m/decode` through `default-value-transformer
  {::mt/add-optional-keys true}` — RECURSES to fill agent AND per-block defaults) →
  ONE `db/transact!` of the nested map → datahike auto-creates the block entities +
  wires the component refs.
- **Malli-native defaults (decision 4).** Every key carries `:default` inline; NO
  separate code-defaults const. Tightened specs + register-once shared shapes
  (decision 5).
- **GENERIC foundation, data-driven (cross-lane).** `resolve-agent-context`
  transacts whatever config datoms the manifest specifies onto block entities; it
  hardcodes NO block-specific knowledge. The namespace-display lane's
  `::full-source`/`::with-tests`/card-render is THEIR code on this foundation.
- **Parked ≠ forgotten (decision 21):** `persona`, `auto-terminate?`, per-agent
  `fs/roots`, `llm/context-window`, `schedule/seed`, `origin-scope` are explicit
  phase-2 items (§6), registered-or-documented so they're not lost.
- **ACME third-party parity is first-class (§2.5).** `config/acme.edn` +
  `config/test.edn` migrate off the deleted `#profile`/`:loadouts`/`:default-load`
  to the v3 manifest; the loader's SEON_CONFIG seam lets any cluster set ALL 46 dials
  from its OWN file with ZERO `src/seon` edits — a build VERIFICATION GATE (§7.2-F,
  CP-5.5), not just a claim. LLM keeps its env→DB cluster default (`:inherit`) AND
  gains per-agent manifest override.

---

## 1. Current-state audit — the "shitshow" mapped

Everything is real at the cited `file:line`. This is what composes an agent's
context TODAY, and what the ONE manifest subsumes.

### 1.1 The composition entry points

| # | Mechanism | file:line | What it does |
|---|-----------|-----------|--------------|
| 1 | `default-seed-blocks` | `seon.agent.ctx` 1700-1786 | Hardcoded ordered block list + inline priorities. SEED-COPIED per agent at create. |
| 2 | `seed-default-ctx!` | `seon.agent.ctx` 1879-1895 | `install!` with `(resolve-loadout (default-seed-blocks) (agent-role id) (load-manifest))`. The one place config shapes the seed. Called from `seon.agent/create!` (mint only). |
| 3 | `resolve-loadout` | `seon.config` 501-538 | Per-role loadout: always-on skill bodies, extra `:blocks`, `:removes`, `:strategy :replace`. |
| 4 | `namespaces-policy` | `seon.config` 195-244 + `seon.agent.ctx.namespaces` 117-234 | Which nses render FULL — `:always` ∪ current-ns ∪ required-full ∪ third-party ∪ live-DB override. |
| 5 | hardcoded ROOT branch | `seon.client` 2367-2379 | `(when (= "root" aid) …)` transacts `:seon.render.live-tile/content 'seon.render.system/system-view`. Carries "removed when Core finishes the config + data loader". THE branch to rip. |

Supporting hardcodes: `home-ns-require-specs` (`seon.eval` 1274-1291),
`stable-priority-max` const (`seon.agent.ctx` 1946), the `SEON_*` tuning env family
(`seon.config` 327-438).

### 1.2 The substrate ALREADY EXISTS (decision 13 is not from scratch)

- **`:seon.agent/ctx`** = `[:vector {:seon.db/component true} :seon.db/ref]`
  (agent.cljs:184) — the cardinality-many COMPONENT ref. A block is already an
  entity; dropping it from the vector cascade-retracts it (ctx.cljs 1856).
- **Block entities** already carry `:seon.agent.ctx/name :keyword` (ctx.cljs 103)
  and `:seon.agent.ctx/priority :int` (ctx.cljs 104). Decision 22's
  "block-priorities dissolves to a per-block priority attr" = USE what exists.
- **`:seon.agent.ctx/render-namespaces`** = `[:vector :keyword]` (ctx.cljs 124) —
  the EXISTING cardinality-many presence-set precedent decisions 22/23 extend.
- **`install!` / `remove!` / `seed-default-ctx!`** (ctx.cljs 1820-1895) already do
  the component-ref upsert-by-name transact. `resolve-agent-context` REPLACES their
  INPUT (a resolved manifest), not the transact mechanism.

So the two-level model is a REFRAME of the existing substrate + a generic loader,
not a new storage design.

### 1.3 `init-agent!` today (client.cljs 1975-2026)

The unified LIFECYCLE path: resolve compile-state+llm → `setup-agent-ns!` → (mint)
`agent/boot!` → `install-wake-trigger!` → `runtime-id/host!`. It does NOT compose
context — context is seeded out-of-band in `agent/create!` → `seed-default-ctx!`,
and root's tile is patched AFTER the loop by the hardcoded branch. v3 makes
`init-agent!` the SINGLE consumer of the resolved manifest, folding both.

### 1.4 The owner-rejected patterns still live

| Pattern | file:line |
|---------|-----------|
| `#profile {:default … :minimal …}` | `config/acme.edn` + `seon.config` 164 |
| `:seon.config/include`/`exclude` + `resolve-skill-rows` | `seon.config` 76-78, 447-464 |
| `:seon.config/default-load` (per-role bridge) | `seon.config` 108, 528-530 |
| `:seon.config/role` + `agent-role` | `seon.config` 56, 439-445 |
| `:seon.config/strategy :replace` | `seon.config` 55, 534-536 |

### 1.5 Where each surface's default lives today (byte-parity map, decision 12)

| Surface | Current default | Source |
|---------|-----------------|--------|
| blocks | the 11-block list + inline priorities | `default-seed-blocks` |
| namespaces render-set | `[my.kb my.data my.ui my.tile seon.agent.todo seon.agent.message seon.agent.lifecycle]` | `default-namespaces-policy` |
| current-ns | rendered full | `default-namespaces-policy` |
| skills always-on | `[:repl]` | manifest `:skills/load` |
| soul/agents | present-file→block | `default-seed-blocks` filterv |
| transcript clip | `:none` (OFF) | transcript.cljs 544 |
| home requires | the 6-spec list | `home-ns-require-specs` |
| toolkit | `my.data`/`my.ui`/`my.tile`/`my.kb` | client requires + `:always` |
| root canvas | `system-view` | hardcoded client.cljs:2374 |
| inventory block | DISABLED (omitted) | default-seed-blocks comment |
| eval-result cap | fixed 16384, NO decay | `format-eval-row` (ctx.cljs 592) |

---

## 2. The config schema — two-level, datahike-native (v3)

### 2.0 The two-level entity model (decisions 13, 16, 17)

```
AGENT entity  ─┬─ agent-level config attrs (scalar / presence-set on the agent)
               │    :seon.agent.run/default-turn-limit 20
               │    :seon.ai/agent-provider :inherit
               │    :seon.eval/toolkit #{:my.ui :my.data …}   (presence-set)
               │    :seon.eval/home-requires "<edn blob>"     (set-once, decision 22c)
               │    :seon.agent.ctx/capabilities #{:grep}     (presence-set)
               │    …
               └─ :seon.agent/ctx  (cardinality-many COMPONENT ref)
                    ├─ BLOCK entity  {:seon.agent.ctx/name :namespaces
                    │                  :seon.agent.ctx/priority 20
                    │                  :seon.render/ai 'ns-block-sym
                    │                  :seon.agent.ctx.namespaces/full-source [:my.kb …]  ← presence-set
                    │                  :seon.agent.ctx.namespaces/current-full? true}      ← scalar
                    ├─ BLOCK entity  {:seon.agent.ctx/name :transcript
                    │                  :seon.agent.ctx/priority 100
                    │                  :seon.render/ai 'transcript-block-sym
                    │                  :seon.agent.ctx.transcript/turns-retained 8
                    │                  :seon.agent.ctx.transcript/tiers        [<tier-ent> …]  ← reified refs
                    │                  :seon.agent.ctx.transcript/result-decay [<level-ent> …]} ← reified refs
                    └─ BLOCK entity  {:seon.agent.ctx/name :live-tile
                                       :seon.render.live-tile/content 'system-view}
```

- **Agent-level config** (model, turn-limit, home-requires, toolkit, capabilities,
  wake, origin-scope) = attrs on the AGENT entity.
- **Per-block config** = attrs ON EACH BLOCK entity, colocated with that block's
  render code.
- **"Which blocks I want"** = which block entities exist on `:seon.agent/ctx`.
- **"A block's config"** = that block's datoms.
- **Block identity = `:seon.agent.ctx/name`** — an upsert/order handle (decision
  17), NEVER a `:kind`. What a block DOES follows from its attributes: a block
  carrying `:seon.agent.ctx.namespaces/full-source` is what the namespaces renderer
  picks up; a block carrying `:seon.render.live-tile/content` is the canvas. The
  renderer already dispatches on `:seon.render/ai` (a symbol slot), not on name —
  name is purely identity+order. **No `:kind` sneaks in.**

### 2.0a NO map-of / vector-of-map values (decision 22 — VERIFIED)

VERIFIED at `db/internal.cljs:243`: the `:or` mixed-type / complex-value bridge
stores the value as `:db.type/string` carrying `pr-str`'d EDN — "datahike's typed
schema cannot hold" the shape. A `:map-of` or `[:vector [:map …]]` config value
would land as an OPAQUE BLOB → no per-element query, no per-datom reactivity (kills
decision 2). Three shapes only:

- **(a) presence/value cardinality-many attr** — for flat sets + per-member
  booleans. Membership = config, absence = the default. E.g.
  `:seon.agent.ctx.namespaces/full-source [:vector :seon.ns/name]` (presence = full,
  absence = compact), `:seon.eval/toolkit [:vector :keyword]`, `:my.skills/load`,
  `:seon.agent.ctx/capabilities`. Matches the existing
  `:seon.agent.ctx/render-namespaces [:vector :keyword]`.
- **(b) reified component entity** — the moment a per-element facet carries a VALUE.
  Transcript **tiers** (`{from-turn, to-turn?, token-cap}`) and **result-decay
  levels** (`{from-turn-offset, token-cap}`) each become an ENTITY,
  `:db/isComponent`-ref'd off the transcript block (cascade-delete), pulled as a
  vector of maps and queried per-element. NOT a `[:vector [:map …]]` blob.
- **(c) serialized EDN blob** — ONLY where per-element is NEVER queried. The one
  case: `:seon.eval/home-requires` (a set-once list of require specs, read whole by
  `setup-agent-ns!`, never queried by element). Explicitly the exception.

**Rule of thumb:** presence-set for booleans/flat-sets; reify-with-component the
moment a value attaches; blob only for never-queried set-once config.

### 2.0b Colocation naming (decision 3) + malli-native defaults (decision 4)

Each key's namespace = the real CODE ns that operates it; the `schema/register!`
moves there (use `::keyword`), colocated with the reading fn. Every key carries its
`:default` inline; `resolve-agent-context` = merge the two explicit layers then
`m/decode` through `(mt/default-value-transformer {::mt/add-optional-keys true})`
(VERIFIED `reference-code/malli` transform.cljc:484-520; `add-optional-keys` is
REQUIRED because our keys are `{:optional true}`). There is NO code-defaults const.
The decode RECURSES into the nested block maps, filling per-block defaults
(decision 16).

### 2.0c Tightened specs + register-once shared shapes (decision 5)

Bounded ints (`[:int {:min 0}]`), real `:enum`s, and shared shapes registered ONCE:
`:seon.agent.ctx/block` (the block map — already the target of `install!`
validation), `:seon.agent.schedule/schedule` (schedule.cljs 44-51),
`:seon.agent.ctx.transcript/tier` + `/decay-level` (the reified-entity shapes),
`:seon.eval/require-spec`, the `:seon.ai/provider` enum. Leaf-rule: `seon.config`
references these by keyword (registry is global), never var-requires the owning ns.

---

### 2.1 AGENT-LEVEL config (scalar / presence-set attrs on the agent entity)

#### 2.1.1 `seon.ai` — per-agent LLM (KEEP, decision 6)

Per-agent overrides of the global `:seon.ai/config` row (seon.ai 152-213), each
`:inherit` by default; the resolver `effective-config-for id` merges per-agent over
global. Registered IN `seon.ai`, reusing the existing global-row value shapes.

```clojure
;; in seon.ai (::keyword expands to :seon.ai/*)
(schema/register! ::agent-provider    [:or {:default :inherit} [:enum :inherit] :seon.ai/provider])
(schema/register! ::agent-model       [:or {:default :inherit} [:enum :inherit] [:string {:min 1}]])
(schema/register! ::agent-temperature [:or {:default :inherit} [:enum :inherit] [:double {:min 0.0 :max 2.0}]])
(schema/register! ::agent-max-tokens  [:or {:default :inherit} [:enum :inherit] [:int {:min 1}]])   ; OUTPUT cap
(schema/register! ::agent-thinking    [:or {:default :inherit} [:enum :inherit] [:string {:min 1}]])
(schema/register! ::agent-max-retries [:or {:default :inherit} [:enum :inherit] [:int {:min 0}]])  ; REPLACES SEON_AI_MAX_RETRIES (turn.cljs 344)
;; PARKED (decision 21): ::agent-context-window — NEW input budget, nothing enforces it today.
```

#### 2.1.2 `seon.eval` — home-ns wiring + toolkit

```clojure
;; in seon.eval
(schema/register! ::require-spec       ; SHARED shape, register-once (decision 5)
  [:cat :symbol [:enum :as :refer] [:or :symbol [:vector :symbol]]])

;; home-requires: set-once, read WHOLE by setup-agent-ns!, never queried per-element
;; → the ONE decision-22(c) serialized-blob case. Registered as a :vector of the
;; shared shape; the bridge stores it as a pr-str'd EDN string (acceptable — never
;; a per-element query).
(schema/register! ::home-requires
  [:vector {:default '[[seon.agent.message :as message]
                       [seon.agent :as agent]
                       [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
                       [seon.schema :as schema]
                       [seon.db :as db]
                       [seon.agent.todo :as todo]]}
   ::require-spec])

;; toolkit: a flat keyword SET → cardinality-many presence attr (decision 22a).
(schema/register! ::toolkit
  [:vector {:default [:my.ui :my.data :my.tile :my.kb]} :keyword])
```

#### 2.1.3 `seon.agent.run` — run bounds

```clojure
;; in seon.agent.run
(schema/register! ::default-turn-limit  [:int {:default 20 :min 1}])    ; REPLACES SEON_DEFAULT_TURN_LIMIT (config.cljs 402-408) + const
(schema/register! ::default-deadline-ms [:int {:default 900000 :min 1}]) ; DEFAULT = run.cljs const (CONFIRM live value at build)
;; seed values; the run's own :seon.agent.run/turn-limit is the live bumpable bound.
```

#### 2.1.4 `seon.client` — wake-arm; `seon.agent.ctx` — capabilities

```clojure
;; in seon.client
(schema/register! ::wake? [:boolean {:default true}]) ; arm the message wake trigger at init. REPLACES unconditional arm (client.cljs 2021). small guard.

;; in seon.agent.ctx — the capability gate (spans search/fs/http; no single provider
;; ns owns the gate, so it lives in the composer). Flat keyword set → presence attr.
(schema/register! ::capability   [:enum :grep :exec :http])          ; register-once enum
(schema/register! ::capabilities [:vector {:default [:grep]} ::capability])
```

#### 2.1.5 PARKED agent-level keys (decision 21 — park ≠ forget)

Documented, not registered in the v1 build (net-new mechanisms deferred to phase 2):

- `:seon.agent.fs/roots` / `:seon.agent.fs/read-only?` — per-agent fs grant (today
  process-global `configure!` + `SEON_FS_ROOT`, fs.cljs 8-10). PHASE 2.
- `:seon.ai/agent-context-window` — per-agent input budget (nothing enforces it
  today). PHASE 2.
- `:seon.db/origin-scope` — the `:seon.db/origin` store slice the memory blocks read
  (no per-agent scoping today). PHASE 2.
- `:seon.agent.schedule/seed` — cron seed at init (runtime-only today); references
  the shared `:seon.agent.schedule/schedule` shape. PHASE 2.

---

### 2.2 BLOCK-LEVEL config (attrs on each block entity)

Each block is an entity on `:seon.agent/ctx`. Its config attrs are colocated with
its render code. **`enabled?` bools DISSOLVE (decision 15):** a block's PRESENCE is
"on", dropping the block entity is "off" — so there are NO
findings/warnings/inventory/relevant `enabled?` keys; add/remove the block instead.
Per-block `:seon.agent.ctx/priority` (existing, ctx.cljs 104) replaces
`block-priorities` (decision 22).

#### 2.2.1 `seon.agent.ctx.namespaces` block — OWNED BY THE NAMESPACE-DISPLAY LANE

**Cross-lane boundary:** these attrs + the compact-CARD renderer + the docstring
doc-lint are the namespace-display lane's code
([[config-driven-agent-init-namespaces-additions-2026-07-01]] +
[[compact-namespace-cards-spec]]). This spec's config FOUNDATION is generic (§3) —
it transacts whatever the manifest specifies onto this block; it hardcodes NO
namespaces knowledge. Reproduced here for completeness; that lane owns the shape:

```clojure
;; in seon.agent.ctx.namespaces — presence-sets, NOT a :map-of (decision 22a/23)
(schema/register! ::full-source   [:vector {:default []} :seon.ns/name]) ; ns present → FULL source (absent → compact CARD)
(schema/register! ::with-tests    [:vector {:default []} :seon.ns/name]) ; ns present → also show its tests
(schema/register! ::current-full?  [:boolean {:default true}])           ; current ns → full
(schema/register! ::current-tests? [:boolean {:default true}])           ; …and its tests
(schema/register! ::include-referred-local? [:boolean {:default true}])  ; auto-add current ns's local requires
```

- **Compact CARD, not `:signatures` (decision 23).** Most nses render as a compact
  card (schema block + one-line fn heads, bodies elided); `:signatures` DROPPED (0×
  adoption footgun). Presence in `::full-source` = full; absence = card.
- **Value type `:seon.ns/name` keyword** (tolerates dynamic `my.agent.*`); a
  configured-but-unmatched ns yields a DERIVED reactive warning (decision 23,
  fail-visible).
- **Current ns = two scalar bools**, no magic `:current` map key.
- **Examples DROPPED (decision 23)** — real evals live in the transcript;
  harvesting busts the prompt cache.
- v3 REPLACES v2's `::include`/`::view`/`::current-ns-view`/`::view-overrides`/
  `::third-party?`/`::render` with these presence-sets. **Decision 14** (the
  per-namespace render `:map-of`) is SUPERSEDED by decision 22/23: a `{ns → aspect}`
  map is unrepresentable in datahike (serializes to a blob), so the aspect model
  becomes presence-sets (`::full-source`/`::with-tests`) + two current-ns bools. A
  third-party ns is just an entry in `::full-source`.

#### 2.2.2 `seon.agent.ctx.transcript` block — eviction + eval-result DECAY (reified)

Tiers and decay levels carry VALUES per element → REIFIED component entities
(decision 22b), NOT `[:vector [:map …]]` blobs.

```clojure
;; in seon.agent.ctx.transcript — the reified per-element entity shapes, register-once
(schema/register! ::tier        ; ONE eviction tier entity
  [:map
   [::from-turn                :int]
   [::to-turn   {:optional true} :int]
   [::token-cap [:int {:min 0}]]])
(schema/register! ::decay-level ; ONE eval-result decay level entity
  [:map
   [::from-turn-offset [:int {:min 0}]]
   [::token-cap        [:int {:min 0}]]])

;; component refs off the transcript block → each tier/level is its own entity,
;; queryable per-element, cascade-deleted with the block.
(schema/register! ::tiers        [:vector {:seon.db/component true :default []} :seon.db/ref]) ; of ::tier entities
(schema/register! ::result-decay [:vector {:seon.db/component true
                                           :default [{::from-turn-offset 0 ::token-cap 16384}
                                                     {::from-turn-offset 2 ::token-cap 1500}
                                                     {::from-turn-offset 5 ::token-cap 200}]}
                                  :seon.db/ref]) ; of ::decay-level entities

;; scalars on the transcript block
(schema/register! ::turns-retained [:int {:default 8 :min 0}])
(schema/register! ::summary-head?  [:boolean {:default true}])
(schema/register! ::cite-card?     [:boolean {:default true}]) ; the fabrication guard (#63). PARK-flag: toggle wanted, or always-on? (decision 21 candidate)
```

Note: `:default` on a component-ref attr is a vector of nested MAPS; the
`default-value-transformer` fills it, and datahike's nested transact reifies each
map into a `::tier`/`::decay-level` entity (decision 16 recursion). This is the
decision-22b reification realized through the same nested-transact loader.

#### 2.2.3 `seon.render.live-tile` block — the canvas

```clojure
;; in seon.render.live-tile (::content attr already lives here)
(schema/register! ::content [:or {:default :none} [:enum :none] :symbol]) ; widen existing attr to allow :none. REPLACES the hardcoded root branch (client.cljs 2367-2379).
;; NB: no ::enabled? — block PRESENCE is "on" (decision 15). Root's block carries
;; ::content 'seon.render.system/system-view via root-context (§3.2).
```

#### 2.2.4 `seon.agent.ctx.transcript` — eval-result DECAY: today vs design

**Today (investigated, ctx.cljs 592-644):** `format-eval-row` renders one eval with
caps SPLIT by component — echoed source + stdout at `eval-render-cap` (1500), the
citable result body at `result-body-render-cap` (16384, = the store ceiling). **NO
age/decay.** The only age distinction is `prior?` (a previous-process eval → no
`result/<id>` handle). A `:seon.render/full?` per-row flag can pin whole, but
NOTHING sets it by recency. A giant file read 20 turns ago renders at the SAME 16384
as this turn's.

**Design (decisions 8, 20 — near-full → partial → clipped, CONFIGURABLE):** the
`::result-decay` levels (reified per §2.2.2) key the per-result cap on AGE
(turn-offset = current-turn − the eval's turn). Default 3-level: 0→16384 (near-full,
view the file you just read), 2→1500 (partial), 5→200 (clipped stub + `result/<id>`
handle). Mechanism: `format-eval-row` gains the result's turn-offset (derivable from
`:seon.eval/at` / its turn), selects the level's cap, passes it to `cap-result-body`
(which already emits the "bind result/<id>" guide on clip). BYTE-STABILITY: a level
renders byte-identically while the result sits in its band (fixed cap per band, only
changes at a boundary) — the #62 age-band discipline. Additive to #62 (tiers band
the WHOLE transcript; decay caps INDIVIDUAL result bodies) — both age-keyed, both
now configurable (decision 20).

#### 2.2.5 `seon.agent.ctx` block-shared attrs

```clojure
;; in seon.agent.ctx — every block entity carries these (existing attrs)
;; :seon.agent.ctx/name     :keyword  (ctx.cljs 103) — identity/order handle, NOT a :kind
;; :seon.agent.ctx/priority :int      (ctx.cljs 104) — REPLACES block-priorities (decision 22)
;; :seon.render/ai          symbol    — the render-fn slot (dispatch is on THIS, not name)
;; soul/agents blocks: :seon.agent.ctx/file-path (existing) — the file-block source
(schema/register! ::escape-clipping? [:boolean {:default true}]) ; #43 — blocks render FULL. Agent-level for v1 (see OQ-1 §6).
(schema/register! ::cache-breakpoint [:int {:default 20 :min 0}]) ; priority ≤ this = cached prefix. REPLACES stable-priority-max const (ctx.cljs 1946). Agent-level.
```

#### 2.2.6 `my.skills` — the skills corpus (presence-set)

```clojure
;; in my.skills — flat keyword SET → cardinality-many presence attr (decision 22a).
;; DEEPER: a loaded skill IS already a :skill/<name> ctx BLOCK entity (my.skills
;; 231-233); "loaded" is DERIVED from block presence (loaded-skill-names, my.skills
;; 176) — NO stored flag. So skills are ALREADY the two-level model (decision 10).
;; ::load seeds WHICH skill bodies are always-on: the boot loader transacts a
;; :skill/<name> block per named skill (same datom the runtime (load :x) writes).
(schema/register! ::load [:vector {:default [:repl]} :my.skills/name]) ; REPLACES :seon.config/skills :load + include/exclude + #profile SETS
;; PARKED: ::order (explicit render order) — decision 21, small.
```

---

### 2.3 CONFIRMED owning nses (from source, decision 3)

- **skills** → `my.skills` (`src/my/skills.cljs`): owns `load`/`catalog-block`/
  `skill-block`/`loaded-skill-names`. `:my.skills/name` is the existing handle.
- **soul / file-blocks** → `seon.agent.ctx` (`file-block`/`file-block-ai`, ctx.cljs
  131-215). No separate soul ns; ctx owns them. Soul is a BLOCK (presence/file-path
  attr), not an agent scalar. `::persona` PARKED (decision 21).
- **capabilities gate** → `seon.agent.ctx` (spans `seon.agent.search`[grep] +
  `seon.agent.fs`[fs] + future http; no single provider ns owns the gate).
- **memory surfaces** (findings/warnings/inventory/relevant) → NO config keys —
  their `enabled?` DISSOLVES to block presence (decision 15). Each block ns
  (`seon.agent.ctx.findings`/`.warnings`/`.inventory`/`.relevant`) owns its render
  fn; whether it renders = whether its block entity is on `:seon.agent/ctx`.
- **transcript tiers/decay + cite-card** → `seon.agent.ctx.transcript`.
- **namespaces presence-sets** → `seon.agent.ctx.namespaces` (cross-lane).
- **canvas** → `seon.render.live-tile` (`::content` already there).

### 2.4 Full SPARSE aero manifest example (decision 16)

`config/system.edn` — ONE manifest, NO `#profile`. A block is a namespaced map with
`:seon.agent.ctx/name` (identity) + its render slot + its config attrs. SPARSE: any
attr omitted is filled from its schema `:default` by the recursive decode (§2.0b),
so a real manifest writes only what DIFFERS. Shown near-full for visibility:

```clojure
{:seon.config/agent-context
 {;; ── agent-level scalars / presence-sets ──
  :seon.ai/agent-provider          :inherit
  :seon.agent.run/default-turn-limit 20
  :seon.eval/toolkit               [:my.ui :my.data :my.tile :my.kb]
  :seon.eval/home-requires         [[seon.agent.message :as message]
                                    [seon.agent :as agent]
                                    [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
                                    [seon.schema :as schema]
                                    [seon.db :as db]
                                    [seon.agent.todo :as todo]]
  :seon.agent.ctx/capabilities     [:grep]
  :seon.agent.ctx/escape-clipping? true
  :seon.client/wake?               true

  ;; ── the block entities (component-ref'd onto :seon.agent/ctx) ──
  :seon.agent/ctx
  [{:seon.agent.ctx/name :soul :seon.agent.ctx/priority 5
    :seon.agent.ctx/file-path "SOUL.md" :seon.render/ai 'seon.agent.ctx/file-block-ai}
   {:seon.agent.ctx/name :namespaces :seon.agent.ctx/priority 20
    :seon.render/ai 'seon.agent.ctx.namespaces/namespaces-block
    ;; presence-sets (namespace-display lane owns the shape)
    :seon.agent.ctx.namespaces/full-source [:my.kb :my.data :my.ui :my.tile
                                            :seon.agent.todo :seon.agent.message :seon.agent.lifecycle]
    :seon.agent.ctx.namespaces/current-full? true}
   {:seon.agent.ctx/name :live-tile :seon.agent.ctx/priority 35
    :seon.render/ai 'seon.agent.ctx.live-tile/live-tile-block
    :seon.render.live-tile/content :none}
   {:seon.agent.ctx/name :transcript :seon.agent.ctx/priority 100
    :seon.render/ai 'seon.agent.ctx.transcript/transcript-block
    :seon.agent.ctx.transcript/turns-retained 8
    ;; reified per-element entities (nested maps → datahike reifies them)
    :seon.agent.ctx.transcript/result-decay
      [{:seon.agent.ctx.transcript/from-turn-offset 0 :seon.agent.ctx.transcript/token-cap 16384}
       {:seon.agent.ctx.transcript/from-turn-offset 2 :seon.agent.ctx.transcript/token-cap 1500}
       {:seon.agent.ctx.transcript/from-turn-offset 5 :seon.agent.ctx.transcript/token-cap 200}]}]}

 ;; the ROOT agent — SPARSE override, selected by IDENTITY (id "root"), NOT a :kind
 :seon.config/root-context
 {:seon.agent/ctx
  [{:seon.agent.ctx/name :live-tile
    :seon.render.live-tile/content seon.render.system/system-view}]}}   ; upsert-by-name onto the base
```

Root-context is a SPARSE override merged over agent-context (decision 11); the
`:live-tile` block upserts by `:seon.agent.ctx/name`, setting root's canvas =
`system-view`. This REPLACES the hardcoded `(when (= "root" aid) …)` branch.

### 2.5 ACME — the third-party proof (first-class, owner-requested)

Acme is the isolated downstream-consumer harness (pod 7980, wire 7981, its own
store, `SEON_CONFIG=config/acme.edn`). **Standing rule (acme-override-proof): a
`SEON_CONFIG` cluster must configure ALL dials from its OWN manifest with ZERO
`src/seon` edits.** In v3 this is a DESIGN PROPERTY of the generic loader (§3.1) —
`resolve-agent-context` reads whichever manifest `SEON_CONFIG` points at through the
same aero path and transacts whatever config datoms it specifies. Any of the 46
dials that can't be set from acme's file is a build FAILURE (ledger §7.2-F).

#### 2.5.1 `config/acme.edn` migration (off the deleted mechanisms)

TODAY `config/acme.edn` uses DELETED shapes: `:seon.config/loadouts` +
`#profile {:default … :minimal []}` + `:seon.config/role`/`:default-load`
(the maximal-corpus skill list). The migration — same aero seam, new manifest shape,
NO `#profile`:

**Before (deleted shape):**

```clojure
{:seon.config/loadouts
 #profile {:default [{:seon.config/role         :default
                      :seon.config/default-load [:repl :data-oriented-clojure :datahike
                                                 :data-modeling :clojurescript :ui-live-tiles]}]
           :minimal []}}
```

**After (v3 `:seon.config/agent-context`):** the `:default-load` skill LIST becomes
the `:my.skills/load` presence-set; the `:minimal` variant becomes a SEPARATE
leaner manifest (`config/acme-minimal.edn`, pointed at by `SEON_CONFIG`) — NOT a
`#profile`. Acme's maximal loadout ALSO demonstrates the per-agent overrides the
new model unlocks (a leaner ns render, a per-agent model):

```clojure
{:seon.config/agent-context
 {;; agent-level — acme overrides per-agent LLM (was ONLY the .env.acme global row)
  :seon.ai/agent-provider :inherit          ; still honors .env.acme SEON_AI_PROVIDER (dual default, §2.5.2)
  :seon.eval/toolkit      [:my.ui :my.data :my.tile :my.kb]
  ;; acme = MAX skill corpus always-on (the old :default-load, now a set)
  :my.skills/load         [:repl :data-oriented-clojure :datahike
                           :data-modeling :clojurescript :ui-live-tiles]
  :seon.agent/ctx
  [{:seon.agent.ctx/name :namespaces :seon.agent.ctx/priority 20
    :seon.render/ai 'seon.agent.ctx.namespaces/namespaces-block
    ;; acme can pin its OWN nses full (incl. its own acme.* third-party code) — the
    ;; presence-set proves third-party ns render is configurable per-cluster
    :seon.agent.ctx.namespaces/full-source [:my.kb :my.data :my.ui :my.tile
                                            :seon.agent.todo :seon.agent.message :seon.agent.lifecycle]
    :seon.agent.ctx.namespaces/current-full? true}
   {:seon.agent.ctx/name :transcript :seon.agent.ctx/priority 100
    :seon.render/ai 'seon.agent.ctx.transcript/transcript-block
    ;; acme can tune its OWN decay schedule from its file
    :seon.agent.ctx.transcript/result-decay
      [{:seon.agent.ctx.transcript/from-turn-offset 0 :seon.agent.ctx.transcript/token-cap 16384}
       {:seon.agent.ctx.transcript/from-turn-offset 3 :seon.agent.ctx.transcript/token-cap 800}]}]}}
```

`config/acme-minimal.edn` (replaces the `#profile :minimal []`) — a leaner cluster,
same seam: `SEON_CONFIG=config/acme-minimal.edn bin/acme restart pod`:

```clojure
{:seon.config/agent-context
 {:my.skills/load []                                     ; no always-on skill bodies
  :seon.agent/ctx
  [{:seon.agent.ctx/name :namespaces :seon.agent.ctx/priority 20
    :seon.render/ai 'seon.agent.ctx.namespaces/namespaces-block
    :seon.agent.ctx.namespaces/full-source [:my.kb]      ; lean: only the toolkit KB full, rest compact
    :seon.agent.ctx.namespaces/current-full? true}]}}
```

#### 2.5.2 LLM dual-default preserved (owner note — don't break env→DB)

LLM is TODAY the global `:seon.ai/config` env→DB row; acme sets it in `.env.acme`
(`SEON_AI_PROVIDER=…`). The new per-agent `:seon.ai/agent-*` keys DEFAULT
`:inherit`, so `effective-config-for id` resolves `:inherit` → the global row →
the cluster's `.env.acme` value. **Both paths kept:** the cluster env default still
works untouched AND acme can now override per-agent IN the manifest (set
`:seon.ai/agent-model "…"` on the agent-context to override just that agent). No
break to the env→DB cluster default.

#### 2.5.3 `config/test.edn` migration

TODAY: `:seon.config/skills {:seon.config/exclude …}` + `:seon.config/loadouts
[{:role :default}]` + `:seon.config/routes {:removes …}`. Migration:

```clojure
{;; skills exclude → just a leaner :my.skills/load set (the excluded dev skills
 ;; were never agent-corpus anyway — the exclude was corpus curation, now moot;
 ;; a test cluster names exactly the skills it wants always-on)
 :seon.config/agent-context {:my.skills/load [:repl]}
 ;; routes — ORTHOGONAL to agent-context, KEEP as its own manifest section (§4.1)
 :seon.config/routes [{:seon.config/removes [:seon.route/agent-call]}]}
```

`:seon.config/routes` stays its own section (route curation is cluster-level, not
per-agent — §4.1). The skills `:exclude` (dev-skill corpus curation) dissolves: with
`:my.skills/load` naming the always-on set explicitly, there's nothing to exclude.

---

## 3. How `init-agent!` consumes it — REACTIVE config-on-record (the keystone)

### 3.0 The keystone (decision 2): every renderer reads config from datoms

**EVERY renderer reads its config from the agent's / block's datoms AT RENDER
TIME** — never from a hardcoded const or a boot-frozen value. So a single
`db/transact!` to a config datom → the next context AND the datastar UI re-derive,
NO apply step. The agent reconfigures ITSELF by transacting its own config datoms
(e.g. `(db/transact! {:seon.db/tx-data [[:db/add block-eid
:seon.agent.ctx.namespaces/full-source :seon.warn]]})` pins a ns full for its next
turn). The refactor = move these 11 hardcoded-default reads to datom reads:

| Renderer / fn | Reads today (hardcoded/frozen) | Becomes (agent/block datom) |
|---------------|-------------------------------|------------------------------|
| `default-seed-blocks` (ctx.cljs 1700) | fixed block list + inline priorities | the agent's own `:seon.agent/ctx` block entities (already datom-backed) + per-block `:seon.agent.ctx/priority` |
| `namespaces-block` / `render?` (ns.cljs 296/223) | `config/namespaces-policy` (manifest read) | the namespaces BLOCK's `::full-source` / `::with-tests` / `::current-full?` presence-sets |
| `render-namespace` detail (ctx.cljs 1531) | `:seon.render/detail :full` (fixed) | full vs compact-card = block-datom presence (namespace-display lane) |
| `format-eval-row` (ctx.cljs 592) | fixed `result-body-render-cap` (16384) | the transcript block's `::result-decay` level entities × the result's age |
| `transcript-block` clip (transcript.cljs 544) | `:seon.render/clip :none` (frozen) | the transcript block's `::tiers` + `::turns-retained` datoms |
| skill seed (config.cljs `resolve-loadout`) | `resolve-loadout` over the manifest | `:my.skills/load` presence-set → seeds `:skill/<name>` blocks |
| soul/agents file-blocks (ctx.cljs 1744) | hardcoded `soul-file-path` presence | the `:soul` block entity's `:seon.agent.ctx/file-path` (present = on) |
| clip gate (ctx.cljs 349 `clip-or-full`) | per-value cap default | `:seon.agent.ctx/escape-clipping?` → `:seon.render/full?` |
| run bounds (run.cljs 93) | `default-turn-limit` const / `SEON_DEFAULT_TURN_LIMIT` | `:seon.agent.run/default-turn-limit` agent datom |
| LLM call (turn.cljs) | the global `:seon.ai/config` row | `effective-config-for id` = per-agent `:seon.ai/agent-*` over the global row |
| root canvas (client.cljs 2374 branch) | the hardcoded `(when (= "root" aid) …)` | the root `:live-tile` block's `:seon.render.live-tile/content`, seeded from root-context |

Uniform: a renderer that reads a module-level const instead reads the attr off the
entity it already pulls. Where a renderer calls `config/namespaces-policy` (a
manifest read), it instead reads the block's datoms — the manifest is consulted
ONCE at seed time, not per render.

### 3.1 Loading pipeline — ONE nested component-ref transact (decision 16)

```clojure
(defn resolve-agent-context [id override]
  (-> (merge (context-config-for id (load-manifest))  ; agent-context ← root-context (§3.2)
             override)                                 ; per-mint override
      ;; RECURSIVE default fill — agent-level AND per-block (nested map) defaults.
      (->> (m/decode :seon.config/agent-context))      ; via (mt/default-value-transformer {::mt/add-optional-keys true})
      ))
;; then ONE transact of the nested map: datahike auto-creates each block entity,
;; wires the :seon.agent/ctx component refs, and reifies the ::tier / ::decay-level
;; nested maps into their own entities.
(db/transact! {:seon.db/tx-data [(assoc (resolve-agent-context id override) :seon.agent/id id)]})
```

Two EXPLICIT merge layers (agent-context ← root-context ← per-mint), key-level (not
deep). The `m/decode` fills every unspecified key — agent-level AND per-block —
from the schema `:default`s (RECURSES into the nested block maps, decision 16). ONE
`db/transact!` of the resulting nested map; datahike builds the ref'd tree. Changing
config later = a transact (retractEntity a block, add a block map, or assoc a
block's config attr) → reactive re-render (§3.0). This REPLACES the input to
`seed-default-ctx!`/`install!` (ctx.cljs 1879-1895), not the transact mechanism.

**GENERIC (cross-lane):** `resolve-agent-context` transacts WHATEVER config datoms
the manifest specifies onto block entities — it hardcodes NO block-specific
knowledge (no namespaces logic, no transcript logic). Data-driven: the lanes don't
overlap in code. The namespace-display lane's card renderer reads its own
`::full-source` datoms off the block this foundation created.

**THIRD-PARTY PARITY (design property, acme-override-proof).** The generic loader
reads the cluster manifest through the SAME aero path
(`load-manifest` honors `SEON_CONFIG`, config.cljs 174) — so a `SEON_CONFIG` cluster
(acme) configures ALL 46 dials from its OWN manifest with ZERO `src/seon` edits.
The SEON_CONFIG seam is PRESERVED end-to-end: `SEON_CONFIG=config/acme.edn` →
`load-manifest` → `resolve-agent-context` → the nested transact onto that cluster's
agents. Because the loader is data-driven (no dial is special-cased for the default
cluster), any dial settable in `system.edn` is settable in `acme.edn`. This is a
VERIFICATION GATE, not just a claim: §7.2-F requires an acme boot + a DeepSeek
override-proof drive confirming acme's overrides render AND every surface is drivable
from acme's file with zero src edits. If ANY of the 46 dials can't be set from the
acme manifest, that's a ledger FAILURE (not done).

### 3.2 Config-by-identity selection (decision 11 — NOT `:kind`)

```clojure
(defn- context-config-for [id manifest]
  (if (= id "root")
    (deep-merge-blocks (:seon.config/agent-context manifest)   ; base
                       (:seon.config/root-context manifest))    ; sparse override, block upsert-by-name
    (:seon.config/agent-context manifest)))
```

Root is identified by `:seon.agent/id` "root" — NO stored `:seon.agent/role`/`:kind`
(today's `agent-role` already honors this, config.cljs 439-445). The root override
merges block-by-`:seon.agent.ctx/name` (the `:live-tile` block upserts to set
`system-view`), replacing the hardcoded branch.

### 3.3 Where `init-agent!` seeds (folds in `seed-default-ctx!`)

`init-agent!` (client.cljs 1975) gains the context-seed step: after
`setup-agent-ns!` (mint only, since a resumed agent keeps its own edited config), it
`db/transact!`s the resolved nested manifest. The seed runs inside the new agent's
`with-agent` scope (as `seed-default-ctx!` does today, ctx.cljs 1892). After the
seed, the DATOMS are the source of truth (§3.0) — renderers read them, not the
manifest. A resumed agent keeps its own edited config (never re-seeded, matching
`create!`'s mint-only seed today).

---

## 4. Migration / rip-out (the ~18-item deletion list)

### 4.1 Deleted

| Deleted | file:line | Replaced by |
|---------|-----------|-------------|
| hardcoded root branch | `seon.client` 2367-2379 | root-context `:live-tile` block `:seon.render.live-tile/content` |
| `#profile` + `SEON_PROFILE` read | `seon.config` 164 | named configs (agent-context/root-context) |
| `config/acme.edn` `:loadouts`+`#profile {:default … :minimal []}`+`:role`/`:default-load` | `config/acme.edn` | v3 `:seon.config/agent-context` (`:default-load`→`:my.skills/load` set); `:minimal`→`config/acme-minimal.edn` (§2.5.1) |
| `config/test.edn` `:skills/exclude`+`:loadouts` | `config/test.edn` | v3 `:seon.config/agent-context {:my.skills/load [:repl]}`; routes stay (§2.5.3) |
| `:seon.config/include`/`exclude` + `resolve-skill-rows` | `seon.config` 76-78, 447-464 | `:my.skills/load` presence-set |
| `:seon.config/default-load` bridge | `seon.config` 108, 528-530 | `:my.skills/load` (one path) |
| `:seon.config/role` + `agent-role` | `seon.config` 56, 439-445 | identity→named-config (§3.2) |
| `:seon.config/loadout` (role-keyed) + `:strategy :replace` | `seon.config` 105-115, 501-538 | agent-context (no role key) |
| `default-namespaces-policy` const | `seon.config` 195-206 | the namespaces block's `::full-source` default |
| `:seon.config/namespaces-spec`/`namespaces-policy` split | `seon.config` 88-103 | the namespaces block's presence-set attrs |
| `resolve-loadout`/`resolve-namespaces`/`resolve-routes` | `seon.config` 214-552 | ONE `resolve-agent-context` |
| `seed-default-ctx!` role indirection | `seon.agent.ctx` 1879-1895 | `init-agent!` transacts the resolved manifest |
| global `home-ns-require-specs` as sole source | `seon.eval` 1274 | `:seon.eval/home-requires` (const → default value) |
| `stable-priority-max` const | `seon.agent.ctx` 1946 | `:seon.agent.ctx/cache-breakpoint` |
| inline block priorities | `seon.agent.ctx` 1744-1786 | per-block `:seon.agent.ctx/priority` (existing attr) |
| `SEON_DEFAULT_TURN_LIMIT` env read | `seon.config` 402-408 | `:seon.agent.run/default-turn-limit` |
| `SEON_RENDER_TRANSCRIPT_TOKEN_CAP` | `seon.config` 357-363 | the transcript block's `::tiers` |
| `SEON_RENDER_RESULT_CAP` (fixed eval cap) | `seon.config` 334-339 | the transcript block's `::result-decay` (age-keyed) |
| `SEON_AI_MAX_RETRIES` env read | `seon.agent.turn` 344 | `:seon.ai/agent-max-retries` |

`:seon.config/routes` (cluster-level route curation) is ORTHOGONAL — KEEP as its own
manifest section, not part of this rip-out.

**Env rip-out** is owner-flagged where load-bearing for the gym (`SEON_PROFILE`
steering). Launch-wiring env (ports, sockets, cluster dir) is NOT touched.

### 4.2 Behavior parity (decision 12 — the gate)

Every current default (§1.5) maps to a schema `:default`. A no-override boot must
produce a **byte-identical** seed to today. Fresh world via `bin/seon cluster reset
default` (no migrating old data — standing rule). The ONLY intended behavior
changes: `:seon.agent.ctx/escape-clipping?` default true (#43) and transcript
`::tiers` ON (#62) — both gym-measured before/after. The namespace compact-everywhere
flip is a SEPARATE owner-gated A/B step AFTER the parity build (decision 23,
render-prominence 0× guardrail) — v1 defaults keep today's full set full.

---

## 5. Subsumes — pending tasks folded in

| Task | Folds in as | wire-existing / to-build |
|------|-------------|--------------------------|
| **#42** explicit-listing config | the namespaces block's `::full-source` presence-set; `#profile`/`:minimal` deleted | wire-existing (reshaped) |
| **#43** escape value-clipping | `:seon.agent.ctx/escape-clipping?` (default true) | wire-existing (flip clip to `:none`) |
| **#45** disable inventory | the `:inventory` block is simply absent (decision 15) | wire-existing |
| **#56 / #73** home-ns aliases real | `:seon.eval/home-requires` per-agent | wire-existing; the authored-ns rewrite (#73) is a SEPARATE eval-path fix (Core A2) |
| **#62** transcript tiers | the transcript block's `::tiers` (reified) | to-build-PARTIAL (age-banding exists) |
| **#74** todo signature-trim | MOOT — `todo` is just a member (or not) of `::full-source`; compact card supersedes signatures | n/a |
| **#83** tests-in-context | the namespaces block's `::with-tests` / `::current-tests?` (un-elides deftests) | to-build (namespace-display lane) |

---

## 6. Open questions / parked items for the owner

The v1/v2 OQs are RESOLVED by the decision log (llm KEEP d6, token-budget DROP d7,
views d9→presence-sets d22/23). Remaining:

1. **`:seon.agent.ctx/escape-clipping?` — agent-level or per-block?** Registered
   agent-level for v1 (one flag). If a block ever needs its own clip escape, promote
   to a per-block attr. (Recommend agent-level; revisit if a block needs it.)
2. **`::cite-card?` toggle** — is a toggle wanted, or is the fabrication guard
   unconditionally on? (Recommend keep-on, drop the key — decision-21 candidate.)
3. **PARKED phase-2 items (decision 21, park ≠ forget):** `:seon.agent.ctx/persona`,
   `:seon.agent.lifecycle/auto-terminate?`, `:seon.agent.fs/roots`+`/read-only?`,
   `:seon.ai/agent-context-window`, `:seon.agent.schedule/seed`,
   `:seon.db/origin-scope`, `:my.skills/order`. Documented here; NOT in the v1 build.
4. **Exact-default confirmations:** `:seon.agent.run/default-deadline-ms` (spec shows
   900000 — CONFIRM the live run.cljs const at build); `::result-decay` levels
   (0→16384, 2→1500, 5→200) and `::turns-retained` (8) are proposed — gym-tune.

---

## 7. BUILD PLAN — checkpoints + completeness ledger + gates

**The #1 constraint (decision 19): NO early victory.** "Done" = every ledger box
checked + gym-green + byte-parity proven + a live drive. Builder's-choice order, but
the plan encodes ordered git-commit checkpoints, a completeness ledger with
verification, and clean-break-then-sweep-all-breakage. Cross-lane: the block-entity
foundation + generic loader (CP-1, CP-2) sequence EARLY so the namespace-display
lane's card render can build on them.

**Sequencing (decision 18): INTERLEAVE.** This config refactor is the main build
focus; the inspect-bridge benchmark (#86) finishes in the background (different
lane) and #85 memory-evolution comes after — neither stalls the other. Within this
plan the checkpoints are ordered; across tracks the work interleaves.

### 7.1 Checkpoints (each = a git commit, bisectable, clean-break available)

- **CP-0 — Baseline capture.** Record today's byte-exact seed for a no-override boot
  (the parity oracle): mint an agent on a fresh world, snapshot its full rendered
  context + `:seon.agent/ctx` block set + priorities. Commit the snapshot as the
  parity fixture. *Gate: snapshot committed.*
- **CP-1 — Schema foundation (agent + block attrs, defaults, shared shapes).**
  Register every v3 attr in its owning ns (§2.1-2.2) with `:default` inline + shared
  shapes (§2.0c) + `agent-bootstrap-attrs` lines. No behavior change yet (nothing
  reads them). *Gate: compiles; `bin/test-cljs` green; every new attr transacts +
  reads back (live-proof); grep confirms each `register!` in its owning ns.*
- **CP-2 — The generic loader (`resolve-agent-context` + nested transact).** aero
  sparse-manifest read → merge → recursive `m/decode` default-fill → ONE
  component-ref transact (§3.1). GENERIC — no block-specific knowledge. Wire
  `init-agent!` to call it (mint path). *Gate: a minted agent's `:seon.agent/ctx`
  tree matches the parity snapshot BYTE-IDENTICALLY (CP-0); reified tier/decay
  entities present + queryable; `bin/test-cljs` green.* **← namespace-display lane
  unblocks here.**
- **CP-3 — Renderer→datom moves (the 11, §3.0).** Move each hardcoded-default read
  to a datom read, ONE at a time within this checkpoint, converging (no v2 path left
  beside it). Includes: block-priorities→per-block attr; run-bounds→datom;
  llm→`effective-config-for`; soul→block; skills→`::load`-seeds-blocks;
  transcript-clip→`::tiers`; eval-decay→`::result-decay`; root-canvas→root-context.
  *Gate per move: the moved renderer reads the datom (live-proof) + parity holds;
  after all 11: full parity snapshot matches CP-0 except the two intended changes.*
- **CP-4 — Clean break + sweep-all-breakage (decision 19).** DELETE the ~18 rip-out
  items (§4.1) in one structural commit; then systematically sweep: (a) compile to
  zero errors, (b) `bin/test-cljs` to zero failures, (c) `grep` every ripped symbol
  to ZERO occurrences, (d) `bin/seon cluster reset default` + boot clean, (e) a live
  DeepSeek drive. Drive breakage to zero. *Gate: grep-to-zero on every ripped symbol;
  suite green; clean boot; live drive succeeds.*
- **CP-5 — Intended-change verification (byte-parity gate, decision 12).** Turn on
  the two intended changes (escape-clipping true, tiers on); gym-measure before/after
  (`bin/gym-scorecard`); confirm no battery regression. *Gate: gym-green; parity
  diff = ONLY the two intended changes; a live drive on the new default context.*
- **CP-5.5 — ACME third-party override-proof (owner-requested gate).** Migrate
  `config/acme.edn` + `config/test.edn` to the v3 manifest shape (§2.5), add
  `config/acme-minimal.edn`. Then `bin/acme build && bin/acme restart pod` and run a
  DeepSeek override-proof drive on the acme cluster. *Gate: acme boots clean on its
  migrated manifest; acme's overrides RENDER (its `:my.skills/load` set, its pinned
  `::full-source` nses, its per-agent `:seon.ai/agent-*`, its `::result-decay`); a
  spot-check confirms EVERY dial class is settable from `config/acme.edn` with ZERO
  `src/seon` edits (a dial that can't = FAILURE); the env→DB LLM dual-default still
  resolves `.env.acme`'s `SEON_AI_PROVIDER` via `:inherit`.*
- **CP-6 — Ledger closeout.** Every §7.2 box checked with its verification recorded.
  Update the component notes + the PRD. *Gate: ledger 100%; honest report of any
  remaining/parked item.*

### 7.2 Completeness ledger (every item tracked + its verification)

Format: each item = `[ ] <item> — VERIFY: <observable check>`. "Done" only when the
verify is OBSERVED in the running system (not inferred).

**A. Schema registrations (CP-1)** — one box per attr, VERIFY = transact a value +
read it back live:

- Agent-level: `[ ]` `:seon.ai/agent-provider` `[ ]` `agent-model` `[ ]`
  `agent-temperature` `[ ]` `agent-max-tokens` `[ ]` `agent-thinking` `[ ]`
  `agent-max-retries` `[ ]` `:seon.eval/require-spec` (shared) `[ ]`
  `:seon.eval/home-requires` `[ ]` `:seon.eval/toolkit` `[ ]`
  `:seon.agent.run/default-turn-limit` `[ ]` `:seon.agent.run/default-deadline-ms`
  `[ ]` `:seon.client/wake?` `[ ]` `:seon.agent.ctx/capability` (enum) `[ ]`
  `:seon.agent.ctx/capabilities` `[ ]` `:seon.agent.ctx/escape-clipping?` `[ ]`
  `:seon.agent.ctx/cache-breakpoint`.
- Block-level: `[ ]` `:seon.agent.ctx.transcript/tier` (shared) `[ ]`
  `/decay-level` (shared) `[ ]` `/tiers` (component ref) `[ ]` `/result-decay`
  (component ref) `[ ]` `/turns-retained` `[ ]` `/summary-head?` `[ ]` `/cite-card?`
  `[ ]` `:seon.render.live-tile/content` (widen to `:none`) `[ ]` `:my.skills/load`.
  VERIFY reified: `[ ]` a tier/level nested map transacts to its OWN entity +
  per-element query returns it (NOT a blob — the decision-22 proof).
- Cross-lane (namespace-display OWNS; foundation must not block): `[ ]`
  `::full-source` `[ ]` `::with-tests` `[ ]` `::current-full?` `[ ]`
  `::current-tests?` `[ ]` `::include-referred-local?` register cleanly on the
  foundation. VERIFY: their lane can transact these onto the namespaces block CP-2
  creates.

**B. Deletions (CP-4, the ~18 rip-out)** — one box per §4.1 row, VERIFY = grep the
ripped symbol to ZERO:

- `[ ]` root branch (client.cljs 2367-2379) `[ ]` `#profile`+`SEON_PROFILE` `[ ]`
  acme `#profile` `[ ]` `include`/`exclude`+`resolve-skill-rows` `[ ]`
  `default-load` `[ ]` `role`+`agent-role` `[ ]` `loadout`+`:strategy` `[ ]`
  `default-namespaces-policy` `[ ]` `namespaces-spec`/`namespaces-policy` `[ ]`
  `resolve-loadout`/`resolve-namespaces`/`resolve-routes` `[ ]` `seed-default-ctx!`
  indirection `[ ]` global `home-ns-require-specs` sole-source `[ ]`
  `stable-priority-max` `[ ]` inline priorities `[ ]` `SEON_DEFAULT_TURN_LIMIT`
  `[ ]` `SEON_RENDER_TRANSCRIPT_TOKEN_CAP` `[ ]` `SEON_RENDER_RESULT_CAP` `[ ]`
  `SEON_AI_MAX_RETRIES`. VERIFY each: `grep -rn "<symbol>" src/` = 0 (or only the
  new home).

**C. Renderer→datom moves (CP-3, the 11 of §3.0)** — VERIFY = the renderer reads the
datom (live) + parity holds:

- `[ ]` `default-seed-blocks`→agent's `:seon.agent/ctx` `[ ]` `namespaces-block`→
  block presence-sets `[ ]` `render-namespace` detail→block presence `[ ]`
  `format-eval-row`→`::result-decay`×age `[ ]` `transcript-block` clip→`::tiers` `[ ]`
  skill seed→`::load` `[ ]` soul→block file-path `[ ]` clip gate→`escape-clipping?`
  `[ ]` run bounds→`default-turn-limit` `[ ]` LLM→`effective-config-for` `[ ]` root
  canvas→root-context.

**D. Wire-ups (CP-2/3)** — VERIFY = the path runs end-to-end live:

- `[ ]` `resolve-agent-context` (merge + recursive decode) `[ ]` `context-config-for`
  identity selection `[ ]` `init-agent!` transacts the resolved manifest (mint path)
  `[ ]` ONE nested component-ref transact reifies tiers/decay `[ ]` `effective-config-for`
  per-agent LLM resolver `[ ]` `::load`→seeds `:skill/<name>` blocks `[ ]`
  generic-loader hardcodes NO block-specific knowledge (code review + the cross-lane
  card render builds on it).

**E. Gates (CP-0/5/6)** — VERIFY = the named artifact:

- `[ ]` CP-0 parity snapshot committed `[ ]` byte-parity: no-override boot = snapshot
  except the 2 intended changes `[ ]` `bin/gym-scorecard` green (no battery
  regression) `[ ]` a live DeepSeek drive on the new context succeeds `[ ]` cluster
  reset + clean boot `[ ]` component notes + PRD updated `[ ]` honest closeout report.

**F. ACME third-party override-proof (CP-5.5, owner-requested)** — VERIFY = acme
boots on its migrated file + a drive confirms every dial is cluster-settable:

- `[ ]` `config/acme.edn` migrated to `:seon.config/agent-context` (`:default-load`
  → `:my.skills/load` set; `#profile`/`:role`/`:loadouts` GONE) — VERIFY: `grep
  "#profile\|:seon.config/loadouts\|:seon.config/role" config/acme.edn` = 0.
- `[ ]` `config/acme-minimal.edn` created (replaces `#profile :minimal []`).
- `[ ]` `config/test.edn` migrated (`:skills/exclude`+`:loadouts` → `agent-context`;
  routes kept) — VERIFY: `grep "loadouts\|exclude" config/test.edn` = 0.
- `[ ]` acme boots clean on the migrated manifest (`bin/acme build && bin/acme
  restart pod`; "auto-boot ready" in `logs/acme/pod.log`).
- `[ ]` acme's overrides RENDER — VERIFY on the acme pod (7980, wire 7981): its
  `:my.skills/load` bodies present, its pinned `::full-source` nses full, its
  `::result-decay` schedule applied, a per-agent `:seon.ai/agent-*` override takes.
- `[ ]` DIAL-COVERAGE: every dial CLASS is settable from `config/acme.edn` with ZERO
  `src/seon` edits (spot-check one of each: a block presence, a per-ns full-source, a
  skill, a per-agent model, a decay level, a run-bound). A dial that can't be set from
  the file = FAILURE.
- `[ ]` LLM env→DB dual-default: `.env.acme`'s `SEON_AI_PROVIDER` still resolves via
  `:seon.ai/agent-provider :inherit` (the cluster env default is NOT broken).
- `[ ]` NEVER touched the live default pod (only acme 7980) during this verification
  (standing rule).

### 7.3 Discipline (decision 19, restated as build law)

- **Every checkpoint is a git commit** with an explicit pathspec (shared tree; peers
  live) — never `git add -A`.
- **SIMPLIFY/CONVERGE at each move** — ONE config path; delete the old read in the
  SAME move that adds the datom read. NEVER a `foo`/`foo-v2`.
- **Clean-break-then-sweep** (CP-4) over fragile keep-green-every-micro-step: make
  the structural deletion, then drive ALL breakage to zero.
- **Gym byte-parity is the gate** (CP-5). Cluster-reset for a fresh world; no
  migrating old data.
- **`bin/test-cljs` green ONCE per checkpoint** (not per edit — the token economy);
  never overlap `cljs.test/run-tests` in the live pod.
- **Atomic** (decision 12): all keys land as one unit across CP-1..CP-5; CP-6 is the
  all-boxes-checked closeout. No partial "done".

---

## Appendix — grounding (every claim at a real file:line)

- `init-agent!` — `src/seon/client.cljs` 1975-2026
- hardcoded root branch + comment — `src/seon/client.cljs` 2367-2379
- `:seon.agent/ctx` component ref — `src/seon/agent.cljs` 184
- block attrs `:seon.agent.ctx/name`/`/priority` — `src/seon/agent/ctx.cljs` 103-104
- `:seon.agent.ctx/render-namespaces` presence-set precedent — `src/seon/agent/ctx.cljs` 124
- `default-seed-blocks` — `src/seon/agent/ctx.cljs` 1700-1786
- `install!`/`remove!`/`seed-default-ctx!` — `src/seon/agent/ctx.cljs` 1820-1895
- clip fns — `src/seon/agent/ctx.cljs` 349, 1209; `format-eval-row` — 592-644
- blob-serialization of complex values — `src/seon/db/internal.cljs` 243, 249-254
- `seon.config` manifest + resolvers — `src/seon/config.cljs` 55-130, 195-244, 439-552
- `namespaces-policy` render — `src/seon/agent/ctx/namespaces.cljs` 117-234
- `home-ns-require-specs` — `src/seon/eval.cljs` 1274-1291
- `my.skills` load/catalog/derived-loaded — `src/my/skills.cljs` 176-233
- transcript clip OFF — `src/seon/agent/ctx/transcript.cljs` 46-52, 544
- malli `default-value-transformer` — `reference-code/malli/src/malli/transform.cljc` 484-520; README 1323-1332
- decision log — `docs/prds/agent-fsm/research/config-driven-agent-init-decisions-2026-07-01.md` (23 decisions)
- cross-lane namespaces additions — `docs/prds/agent-fsm/research/config-driven-agent-init-namespaces-additions-2026-07-01.md`
- `SEON_CONFIG` seam (`load-manifest` honors env override) — `src/seon/config.cljs` 174
- acme manifest (old shape: `#profile`/`:loadouts`/`:default-load`) — `config/acme.edn`
- test manifest (old shape: `:skills/exclude`/`:loadouts`) — `config/test.edn`
