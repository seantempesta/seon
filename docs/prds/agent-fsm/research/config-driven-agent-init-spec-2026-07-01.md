---
type: research
status: draft
tags: [research, agent, config]
---

# Config-driven agent initialization + context composition (SPEC)

One `init-agent!` takes a Malli-spec'd, aero-loaded config map describing the
ENTIRE agent + its context, and wires everything from it. This is the
CONTEXT-CONFIG half of the `init-agent!` unification (the lifecycle half landed
in `bfac6f50`). SPEC ONLY — no build; the owner reviews before any rip-out.

## TL;DR

- **Today** context composition is scattered across ~5 mechanisms: a hardcoded
  `default-seed-blocks` list, a `seon.config` manifest that half-retired
  `#profile`/`:include`/`:exclude` into a `:load` bridge, a per-role
  `resolve-loadout`, a hardcoded root `live-tile` branch in `client.cljs`, a
  hardcoded `home-ns-require-specs`, and a `namespaces-policy` that is only
  partially config-driven. Three of these (`#profile`, `:include`/`:exclude`,
  the per-role `:default-load` bridge) are **owner-rejected** and still live.
- **Target:** ONE registered config map (`:seon.config/agent-context`) selected
  by identity (`root` vs the rest), merged `aero-default ← named-config ←
  per-mint override`, consumed by `init-agent!` to seed EVERY context surface.
  No hardcoded defaults in fns; no `#profile`; no `:minimal`.
- **Deletion list (§4.1, ~18 items):** `#profile`+`SEON_PROFILE`,
  `:include`/`:exclude`+`resolve-skill-rows`, `:seon.config/default-load` bridge,
  `:seon.config/strategy` `:replace`, `:seon.config/loadout` (role-keyed),
  `resolve-loadout`'s dual-path, `agent-role` selector, the hardcoded root
  `live-tile` branch (`client.cljs:2374`), the `:namespaces`/`:skills`
  split-manifest sections, `config/acme.edn`'s `#profile` block,
  `default-namespaces-policy` const, `seed-default-ctx!` role indirection,
  hardcoded block priorities + `stable-priority-max`, and the tuning ENV reads
  (`SEON_DEFAULT_TURN_LIMIT`, `SEON_FS_*`, the `SEON_RENDER_*_CAP` family,
  `SEON_AI_MAX_RETRIES`) — folded into config keys (env-to-config is owner-flagged
  where load-bearing for the gym harness).
- **Final key count: 44 flat, fully-namespaced registered dial keys** across the
  12 categories (the seed's 16 + 28 promoted from hardcodes/env/per-fn defaults;
  full inventory in §2.7). FLAT keys (the keyword-namespace IS the grouping, NOT
  nested maps) so the config lands as the agent's queryable, live-overridable
  DATOMS (§2.0).

---

## 1. Current-state audit — the "shitshow" mapped

Everything below is real, at the cited `file:line`. This is what composes an
agent's context TODAY, and what the ONE config map subsumes.

### 1.1 The composition entry points (5 mechanisms, one job)

| # | Mechanism | file:line | What it does |
|---|-----------|-----------|--------------|
| 1 | `default-seed-blocks` | `seon.agent.ctx` 1700-1786 | Hardcoded ordered block list (soul/agents/shared-instructions/skills-catalog/namespaces/live-tile/warnings/open-todos/relevant-source/findings/transcript). SEED-COPIED per agent at create. |
| 2 | `seed-default-ctx!` | `seon.agent.ctx` 1879-1895 | Calls `install!` with `(resolve-loadout (default-seed-blocks) (agent-role id) (load-manifest))`. The one place config shapes the seed. Called from `seon.agent/create!` (mint only). |
| 3 | `resolve-loadout` | `seon.config` 501-538 | Merges the manifest's per-role loadout: always-on skill bodies (`:load` OR legacy `:default-load`), extra `:blocks`, `:removes`, `:strategy :replace`. |
| 4 | `namespaces-policy` | `seon.config` 195-244 + `seon.agent.ctx.namespaces` 117-234 | Which nses render FULL in the `:namespaces` body — `:always` set ∪ current-ns ∪ required-full helpers ∪ third-party ∪ live-DB override. |
| 5 | hardcoded ROOT branch | `seon.client` 2367-2379 | `(when (= "root" aid) …)` transacts `:seon.render.live-tile/content 'seon.render.system/system-view` onto root. Carries the comment *"this will be removed when Core finishes the config + data loader"*. THIS is the branch to rip. |

Plus two supporting hardcodes:

- **`home-ns-require-specs`** (`seon.eval` 1274-1291) — the hardcoded
  `(:require …)` every agent's home ns is wired with (`message`/`agent`/
  lifecycle-refers/`schema`/`db`/`todo`). No per-agent variation possible.
- **`agent-bootstrap-attrs`** (`seon.client` 379-567) — the datahike attr set;
  `:seon.agent/ctx` and friends are here. (NOT ripped — this is the storage
  schema, orthogonal to config. New config keys that persist get added here.)

### 1.2 `init-agent!` today (client.cljs 1975-2026)

`init-agent!` is the unified LIFECYCLE path: resolve compile-state+llm →
`setup-agent-ns!` (home-ns wiring) → (mint) `agent/boot!` → `install-wake-trigger!`
→ `runtime-id/host!`. It takes only `{:seon.agent/id :seon.agent/purpose ::mint?
::llm-fn ::compile-state}`. **It does NOT compose context** — context is seeded
out-of-band inside `agent/create!` → `seed-default-ctx!`, and the root's tile is
patched AFTER the `init-agent!` loop by the hardcoded branch. This spec makes
`init-agent!` the SINGLE consumer of a context config, folding both.

### 1.3 The owner-rejected patterns still live

| Pattern | file:line | Status |
|---------|-----------|--------|
| `#profile {:default … :minimal …}` | `config/acme.edn` (loadouts) + `seon.config` 164 (`SEON_PROFILE`) | REJECTED — opaque named sets. |
| `:seon.config/include` / `:exclude` | `seon.config` 76-78, 447-464 (`resolve-skill-rows`) | To retire — replaced by explicit `:skills/load`. |
| `:seon.config/default-load` (per-role) | `seon.config` 108, 528-530 | "migration bridge" — the legacy per-role skill list. |
| `:seon.config/role` + `agent-role` | `seon.config` 56, 439-445 | Role SELECTOR — replace with identity→named-config. |
| `:seon.config/strategy :replace` | `seon.config` 55, 534, 536 | Rarely-used "start from empty base". |

Note `namespaces-policy` (#42) is ALREADY partly done: the hardcoded
`full-source-whitelist`/`verb-signature-whitelist`/`canonical-full-my-ns` are
gone, replaced by `:seon.config/namespaces`. Signatures are RETIRED — every
rendered ns renders FULL; the only knob is WHICH nses (`:always` + current-ns).
So #74 ("trim todo to signatures") is MOOT — folded into "which nses render".

### 1.4 Where each surface's default lives today (so nothing silently changes)

| Surface | Current default | Source |
|---------|-----------------|--------|
| blocks | the 11-block list | `default-seed-blocks` (hardcoded) |
| namespaces `:always` | `[my.kb my.data my.ui my.tile seon.agent.todo seon.agent.message seon.agent.lifecycle]` | `default-namespaces-policy` + `config/system.edn` |
| current-ns render | `:full` | `default-namespaces-policy` |
| skills always-on | `[:repl]` (system.edn) / `:all`+list (acme) | manifest `:skills/load` |
| soul/agents | present-file→block, absent→none | `default-seed-blocks` filterv |
| transcript clip | `:none` (OFF) | `transcript.cljs` 544; cap `SEON_RENDER_TRANSCRIPT_TOKEN_CAP` 6000 |
| home requires | the 6-spec list | `home-ns-require-specs` |
| toolkit | `my.data`/`my.ui`/`my.tile`/`my.kb` (via required-in-client + `:always`) | `client.cljs` requires + namespaces `:always` |
| root canvas | `system-view` | hardcoded `client.cljs:2374` branch |
| inventory block | DISABLED (seed omits it) | `default-seed-blocks` comment 1775-1779 |
| findings block | ON (priority 97) | `default-seed-blocks` |
| model | env→DB `:seon.ai/config` row | `seon.ai` (separate surface — see OQ-3) |

---

## 2. The config schema — `:seon.config/agent-context` (EXHAUSTIVE)

ONE registered map covering the ENTIRE agent + context surface — every dial that
today lives in a hardcoded value, a per-fn default, a flag, or an env var. Grouped
by the 12 categories. Each key: fully-namespaced, typed, one-line doc, DEFAULT,
and the CURRENT source-of-truth it REPLACES (the rip-out target). Marked
**wire-existing** (config just reshapes/toggles built machinery) or **to-build**
(needs new mechanism). This is the audit-and-registry in one — every current
source-of-truth is named so the owner can see exactly what gets removed.

### 2.0 STRUCTURE — flat, fully-namespaced keys (NOT nested maps)

**Every dial is ONE flat, fully-namespaced, registered Malli key.** The
keyword-NAMESPACE encodes the grouping — a block IS a namespace, its sub-dials
are the keys sharing that namespace:

- `:seon.agent.ctx/blocks` — which blocks render (the set).
- a block's sub-config = the keys under its namespace:
  `:seon.agent.ctx.namespaces/include`, `:seon.agent.ctx.namespaces/current-ns?`,
  `:seon.agent.ctx.transcript/tiers`, `:seon.agent.ctx.transcript/turns-retained`,
  `:seon.agent.ctx.llm/provider`, `:seon.agent.ctx.skills/load`, …
- agent-level dials sit at `:seon.agent/*` / `:seon.agent.fs/*`.

**Why flat, not `{:namespaces {:include …}}`:**

1. **Datom-native.** The config IS the agent's STORED config — flat namespaced
   keys transact directly as the agent's datoms, queryable + live-overridable
   per-agent (`with-agent` / `ctx/install!`), the reactive-context model.
   Datahike has NO nested maps: a nested `{:include …}` would force a component
   sub-entity per group. Flat keys are one flat datom set.
2. **No bare keys.** `{:namespaces {:include …}}` has a BARE `:include` — breaks
   "every key namespaced". The flat `:seon.agent.ctx.namespaces/include` keeps
   the rule AND shows the grouping.
3. **Uniform.** Block dials at `:seon.agent.ctx.<block>/*`, agent dials at
   `:seon.agent/*` — one rule.

Nested VALUES under a flat key are fine (transcript `tiers` vector, `home-requires`
vector) — that's DATA, not structure.

`:seon.config/agent-context` is the umbrella `:map` whose keys are ALL of the flat
keys below (every key `{:optional true}` — an absent key falls to the code
default). The Category-4 LLM keys live at `:seon.agent.ctx.llm/*` (flat, not a
nested `:llm` map).

### 2.0b Shape registration (the leaf rule)

`seon.config` is a LEAF (no `seon.agent.ctx` / `my.*` / `seon.ai` var refs), so it
registers these shapes as leaf types (`:keyword`, `[:vector :any]`, `[:set
:keyword]`) and the full validation happens downstream (`install!` validates each
block, `transact!` each attr, `seon.ai` validates the config row). Same discipline
as today's `seon.config` (docstring 41-42). Shared shapes register ONCE
(§ CLAUDE.md "register once, reference"). NB the aero manifest authors symbols for
ns names (`my.kb`); `resolve-agent-context` coerces them to keywords (`:my.kb`) to
match the `[:set :keyword]` shape — same as today's `ns-sym->kw` (config.cljs 208).

### Category 1 — Blocks (which render, order, per-block on/off)

```clojure
(schema/register! :seon.agent.ctx/blocks
  ;; WHICH context sections render. :all = every default block; a set names
  ;; an explicit subset. DEFAULT :all.
  ;; REPLACES: the hardcoded default-seed-blocks list (ctx.cljs 1700-1786) +
  ;; the "disable by omission" comments. wire-existing.
  [:or [:enum :all] [:set :seon.agent.ctx/name]])

(schema/register! :seon.agent.ctx/block-priorities
  ;; Per-block priority OVERRIDES (name→int); reorders the static→volatile
  ;; layout without editing the seed. DEFAULT {} (the hardcoded priorities:
  ;; soul 5, agents 8, shared-instructions 10, skills-catalog 12, namespaces 20,
  ;; live-tile 35, warnings 40, open-todos 45, relevant-source 48, findings 97,
  ;; transcript 100). REPLACES: the inline :seon.agent.ctx/priority literals in
  ;; default-seed-blocks. wire-existing (priority already drives sort +
  ;; stable-priority-max cache breakpoint, ctx.cljs 1936, 1946).
  [:map-of :seon.agent.ctx/name :int])

(schema/register! :seon.agent.ctx/extra-blocks
  ;; Additional/override blocks seeded on top (full :seon.agent.ctx/block maps,
  ;; upserted by name). DEFAULT []. REPLACES: :seon.config/loadout :blocks
  ;; (config.cljs 112). wire-existing (install! upsert-by-name).
  [:vector :map])
```

### 2.2 Namespaces (the #42 context lever)

```clojure
(schema/register! :seon.agent.ctx.namespaces/include
  ;; Always-render-FULL ns keywords. DEFAULT #{my.kb my.data my.ui my.tile
  ;; seon.agent.todo seon.agent.message seon.agent.lifecycle}.
  ;; REPLACES: seon.config/default-namespaces-policy :always (config.cljs 204)
  ;; + config/system.edn :seon.config/namespaces :always. wire-existing.
  [:set :keyword])

(schema/register! :seon.agent.ctx.namespaces/current-ns?
  ;; Auto-render the agent's CURRENT ns full. DEFAULT true.
  ;; REPLACES: :seon.config/current-ns :full/:off (config.cljs 88-95),
  ;; reshaped to a bool. wire-existing (render? cond, namespaces.cljs 234).
  :boolean)

(schema/register! :seon.agent.ctx.namespaces/include-referred-local?
  ;; Pull in the current ns's LOCAL requires (not external libs) that carry
  ;; stored full source. DEFAULT true.
  ;; REPLACES: required-full-set (namespaces.cljs 197-221) — currently ALWAYS
  ;; on with no knob; this makes it configurable. wire-existing.
  :boolean)

(schema/register! :seon.agent.ctx.namespaces/third-party?
  ;; Render third-party (downstream/acme) ns source full. DEFAULT true.
  ;; REPLACES: third-party-ns? unconditional inclusion (namespaces.cljs
  ;; 156-176, render? 233) — currently ALWAYS on; this makes it a knob so a
  ;; lean cluster can drop downstream bulk. wire-existing.
  :boolean)

(schema/register! :seon.agent.ctx.namespaces/render
  ;; :full | :signature. DEFAULT :full.
  ;; REPLACES: the RETIRED full-source-whitelist/verb-signature-whitelist +
  ;; :seon.render/detail :signature (owner rejected signature-trim, #74 moot).
  ;; FLAG (OQ-1): signatures are dead — keep this key as :full-only or DROP it?
  [:enum :full :signature])
```

### Category 2 — Namespaces (done above; the #42 context lever)

### Category 3 — Skills (load-set, order)

```clojure
(schema/register! :seon.agent.ctx.skills/load
  ;; :all = every corpus skill body always-on; a set names the always-on
  ;; subset; #{} = none. DEFAULT #{:repl}.
  ;; REPLACES: :seon.config/skills :load + the DELETED :default-load bridge
  ;; (config.cljs 528-530) + include/exclude corpus curation (config.cljs
  ;; 76-78, resolve-skill-rows 447-464) + the #profile :default/:minimal SETS.
  ;; Corpus curation folds into naming the set explicitly. wire-existing.
  [:or [:enum :all] [:set :seon.config/skill-name]])

(schema/register! :seon.agent.ctx.skills/order
  ;; Explicit render order for the always-on skill bodies (a vector of names;
  ;; unlisted follow, alpha). DEFAULT [] (priority-16 seed order, cache-stable).
  ;; REPLACES: the implicit scan/seed order (config.cljs skill-block 466-474).
  ;; to-build (small — the seed currently doesn't order beyond priority 16).
  [:vector :seon.config/skill-name])
```

### Category 4 — Model / LLM (provider, temperature, caps, retry)

The `:seon.ai/config` singleton row (env→DB, `seon.ai` 152-213: `::provider`,
`::model`, `::temperature`, `::max-tokens` [OUTPUT cap], `::thinking`,
`::timeout-ms`, `::base-url`) is TODAY a GLOBAL row seeded from `SEON_AI_*` env.
The owner wants the agent config to cover it. Two paths (OQ-2): fold these into
agent-context (per-agent LLM), or keep `seon.ai` global and reference it. Spec'd
here as FLAT `:seon.agent.ctx.llm/*` keys (NOT a nested `:llm` map) that each
DEFAULT to `:inherit` (use the global row) but can override per-agent — a real
new mechanism (per-agent LLM resolution does not exist).

```clojure
(schema/register! :seon.agent.ctx.llm/provider
  ;; :inherit | :deepseek | :anthropic | :openai-compat | :diffusiongemma.
  ;; DEFAULT :inherit. REPLACES (per-agent hook over): :seon.ai/provider
  ;; (SEON_AI_PROVIDER). to-build (per-agent LLM resolution).
  [:enum :inherit :deepseek :anthropic :openai-compat :diffusiongemma])

(schema/register! :seon.agent.ctx.llm/model
  ;; :inherit | model-string. DEFAULT :inherit.
  ;; REPLACES (per-agent hook): :seon.ai/model (SEON_AI_MODEL). to-build.
  [:or [:enum :inherit] :string])

(schema/register! :seon.agent.ctx.llm/temperature
  ;; :inherit | double. DEFAULT :inherit.
  ;; REPLACES (per-agent hook): :seon.ai/temperature (SEON_AI_TEMPERATURE). to-build.
  [:or [:enum :inherit] :double])

(schema/register! :seon.agent.ctx.llm/max-output-tokens
  ;; The LLM OUTPUT cap (::max-tokens). :inherit | int. DEFAULT :inherit.
  ;; REPLACES (per-agent hook): :seon.ai/max-tokens (SEON_AI_MAX_TOKENS). to-build.
  [:or [:enum :inherit] :int])

(schema/register! :seon.agent.ctx.llm/context-window
  ;; The INPUT context-window budget (distinct from output cap — CLAUDE.md
  ;; token note). :inherit | int. DEFAULT :inherit.
  ;; REPLACES: NOTHING today — no context-window limit is enforced anywhere.
  ;; to-build (new; ties to :seon.agent.ctx/token-budget below).
  [:or [:enum :inherit] :int])

(schema/register! :seon.agent.ctx.llm/thinking
  ;; :inherit | thinking-mode string ("high"/"max"/…). DEFAULT :inherit.
  ;; REPLACES (per-agent hook): :seon.ai/thinking (SEON_AI_THINKING). to-build.
  [:or [:enum :inherit] :string])

(schema/register! :seon.agent.ctx.llm/max-retries
  ;; LLM retry budget (the `again`-ported seon.retry backoff). :inherit | int.
  ;; DEFAULT :inherit. REPLACES (per-agent hook): the env read
  ;; SEON_AI_MAX_RETRIES in call-llm! (turn.cljs 344). to-build.
  [:or [:enum :inherit] :int])
```

### Category 5 — Identity (soul, persona)

```clojure
(schema/register! :seon.agent.ctx/soul
  ;; :none = no soul block; :file = SOUL.md present→block absent→none;
  ;; a string = an explicit soul file path. DEFAULT :file (today's behavior).
  ;; REPLACES: the hardcoded soul-file-path file-block (ctx.cljs 1744-1746).
  ;; wire-existing.
  [:or [:enum :none :file] :string])

(schema/register! :seon.agent.ctx/persona
  ;; An inline persona/name string seeded as a block (for a config-defined
  ;; agent without a SOUL.md file). DEFAULT :none.
  ;; REPLACES: NOTHING — persona today is ONLY the SOUL.md file. to-build
  ;; (small — a literal-string block). FLAG: keep, or is soul-file enough?
  [:or [:enum :none] :string])
```

### Category 6 — Transcript / history (eviction, retained, cache breakpoint)

```clojure
(schema/register! :seon.agent.ctx.transcript/tiers
  ;; Age-banded clip tiers. DEFAULT :off (today: :seon.render/clip :none,
  ;; transcript.cljs 544). Each tier = {::from-turn ::to-turn* ::token-cap};
  ;; ::to-turn OPTIONAL (absent = open-ended). REPLACES: the disabled eviction
  ;; + SEON_RENDER_TRANSCRIPT_TOKEN_CAP (config.cljs 357-363). to-build-PARTIAL
  ;; (age-banding exists; this parameterizes + enables it — #62).
  [:or [:enum :off]
       [:vector [:map
                 [:seon.agent.ctx.transcript/from-turn :int]
                 [:seon.agent.ctx.transcript/to-turn   {:optional true} :int]
                 [:seon.agent.ctx.transcript/token-cap :int]]]])

(schema/register! :seon.agent.ctx.transcript/turns-retained
  ;; How many past turns render verbatim before eviction applies. DEFAULT 8.
  ;; REPLACES: the implicit "all turns" render (transcript.cljs 544 "renders
  ;; ALL"). to-build (drives the tier from-turn boundary).
  :int)

(schema/register! :seon.agent.ctx.transcript/summary-head?
  ;; Render the masthead/summary head at the top of the transcript. DEFAULT
  ;; true. REPLACES: the always-on masthead (transcript-block). wire-existing
  ;; (toggle the masthead render).
  :boolean)

(schema/register! :seon.agent.ctx/cache-breakpoint
  ;; The priority at/below which blocks are the byte-stable cacheable PREFIX
  ;; (provider prompt-cache). DEFAULT 20 (stable-priority-max, = :namespaces).
  ;; REPLACES: the hardcoded stable-priority-max const (ctx.cljs 1946).
  ;; wire-existing.
  :int)
```

### Category 7 — Token budget / clipping (#43)

```clojure
(schema/register! :seon.agent.ctx/token-budget
  ;; Soft whole-context target (advisory: drives eviction-tier selection + a
  ;; render warning). DEFAULT 0 = unbounded. REPLACES: NOTHING — there is NO
  ;; whole-context budget today (ctx.cljs 1908-1910 "There is NO char budget").
  ;; to-build (OQ-3: soft advisory vs hard cap).
  :int)

(schema/register! :seon.agent.ctx/escape-clipping?
  ;; Context blocks render FULL, escaping the per-value render clip (#43).
  ;; DEFAULT true (owner leaning yes, core-handoff §B #43).
  ;; REPLACES: the per-value clip default in clip-or-full (ctx.cljs 349) / clip
  ;; (1209) — flips seeded blocks to :seon.render/clip :none. wire-existing.
  :boolean)

(schema/register! :seon.agent.ctx/block-caps
  ;; Per-block render token caps (name→int) — a fine-grained clip override.
  ;; DEFAULT {} (per-block defaults: message 4000, eval 1500, transcript 6000,
  ;; store-edn 16384, result 16384 — the SEON_RENDER_*_CAP family). REPLACES:
  ;; the SEON_RENDER_MESSAGE_CAP / _EVAL_CAP / _TRANSCRIPT_TOKEN_CAP /
  ;; _STORE_EDN_CAP / _RESULT_CAP env family (config.cljs 327-363). to-build
  ;; (the caps exist as env reads; this centralizes them per-agent).
  [:map-of :seon.agent.ctx/name :int])
```

### Category 8 — Capabilities / tools (toolkit, home-requires, fs allowlist)

```clojure
(schema/register! :seon.agent.ctx/toolkit
  ;; The capability nses seeded into the agent's reach (indexed-at-boot +
  ;; rendered full). DEFAULT #{my.ui my.data my.tile my.kb}.
  ;; REPLACES: the split "required in client.cljs + listed in :always" — this
  ;; UNIFIES it. wire-existing-PARTIAL (unifies two existing mechanisms).
  [:set :keyword])

(schema/register! :seon.agent/home-requires
  ;; REAL ns requires for the agent's home ns [[seon.db :as db] …] (#73, #56 —
  ;; no-magic: the aliases are genuinely in the ns form). DEFAULT the 6-spec
  ;; list. REPLACES: the global home-ns-require-specs const (eval.cljs 1274) —
  ;; becomes per-agent config (the const remains the DEFAULT VALUE). wire-existing.
  [:vector :any])   ; leaf :any — validated as require-specs downstream

;; --- fs capability gate (seon.agent.fs — SOFT boundary, allowlisted) ---
(schema/register! :seon.agent.fs/roots
  ;; The filesystem roots the agent may read/list under. DEFAULT [] (no fs
  ;; access unless granted). REPLACES: seon.agent.fs/configure! + the
  ;; SEON_FS_ROOT env var (fs.cljs 8-9). to-build (per-agent grant — today
  ;; configure!/env is process-global, not per-agent).
  [:vector :string])

(schema/register! :seon.agent.fs/read-only?
  ;; Writes require this false; reads always allowed within roots. DEFAULT true.
  ;; REPLACES: the :seon.agent.fs/read-only? grant flag (fs.cljs 9-10, env
  ;; SEON_FS_READ_ONLY). to-build (per-agent).
  :boolean)

(schema/register! :seon.agent.ctx/capabilities
  ;; Extra capability gates beyond fs — :exec (ripgrep/shell) + :http (fetch)
  ;; the "case-2" surfaces. A set of enabled capability keywords. DEFAULT
  ;; #{:grep} (seon.agent.search is bundled). REPLACES: NOTHING structured —
  ;; today grep is unconditionally available (seon.agent.search) and there is
  ;; no exec/http gate. to-build (new capability gate; exec/http don't exist).
  [:set [:enum :grep :exec :http]])
```

### Category 9 — Memory surfaces (canvas, findings, inventory, warnings, cite)

```clojure
(schema/register! :seon.agent.ctx/canvas?
  ;; Seed a live-tile/canvas block. DEFAULT true.
  ;; REPLACES: the hardcoded :live-tile block (default-seed-blocks). wire-existing.
  :boolean)

(schema/register! :seon.agent.ctx/canvas-content
  ;; The live-tile content symbol seeded onto the agent (root → system-view).
  ;; DEFAULT :none (agent sets its own). REPLACES: the hardcoded root branch
  ;; (client.cljs 2367-2379 — THE branch this spec kills). Root-context sets it
  ;; to 'seon.render.system/system-view. wire-existing.
  [:or [:enum :none] :symbol])

(schema/register! :seon.agent.ctx/findings?
  ;; The stored-findings content surface. DEFAULT true. REPLACES: the hardcoded
  ;; :findings block (priority 97, default-seed-blocks). wire-existing.
  :boolean)

(schema/register! :seon.agent.ctx/inventory?
  ;; The one-line-per-ns store overview. DEFAULT false (#45 — owner disabling;
  ;; today omitted from the seed by comment, ctx.cljs 1775-1779). REPLACES:
  ;; that omission-comment. wire-existing (re-add the inventory-block when true).
  :boolean)

(schema/register! :seon.agent.ctx/warnings?
  ;; The reactive warnings block (current problems; vanishes when fixed).
  ;; DEFAULT true. REPLACES: the hardcoded :warnings block (priority 40).
  ;; wire-existing.
  :boolean)

(schema/register! :seon.agent.ctx/relevant-source?
  ;; The SEON_EMBED KNN nearest-entity block. DEFAULT false (env-gated today).
  ;; REPLACES: the SEON_EMBED gate on the :relevant-source block (priority 48) —
  ;; surfaces the gate as config. wire-existing.
  :boolean)

(schema/register! :seon.agent.ctx/cite-card?
  ;; The cite-card fabrication-guard surface (#63). DEFAULT true. REPLACES:
  ;; NOTHING toggleable — cite-card is always-on today. to-build (small toggle).
  ;; FLAG: is a toggle even wanted, or is cite-card unconditionally on?
  :boolean)
```

### Category 10 — Store scope (:seon.db/origin slice)

```clojure
(schema/register! :seon.agent.ctx/origin-scope
  ;; Which :seon.db/origin slice of the shared store the agent's memory
  ;; surfaces (findings/inventory/relevant) read. :all = the whole store;
  ;; a set names origins (e.g. #{:agent :user}). DEFAULT :all (today's
  ;; behavior — no per-agent scoping). REPLACES: NOTHING — findings/inventory
  ;; query the whole store unscoped today. to-build (new; origins exist as tx
  ;; tags :core-seed/:replay/:agent-id but nothing scopes reads by them).
  [:or [:enum :all] [:set :keyword]])
```

### Category 11 — Wake / lifecycle (turn cap, deadline, schedules, terminate)

```clojure
(schema/register! :seon.agent/default-turn-limit
  ;; The run WORK-bound seed (bumpable turn count). DEFAULT 20
  ;; (run.cljs default-turn-limit 93). REPLACES: the SEON_DEFAULT_TURN_LIMIT
  ;; env read (config.cljs 402-408) + the run.cljs const default.
  ;; wire-existing (the attr :seon.agent/default-turn-limit exists,
  ;; agent-bootstrap-attrs 394).
  :int)

(schema/register! :seon.agent/default-deadline-ms
  ;; The run WALL-CLOCK bound seed (ms). DEFAULT from run.cljs
  ;; default-deadline-ms (97). REPLACES: the run.cljs const.
  ;; wire-existing (attr exists, agent-bootstrap-attrs 395).
  :int)

(schema/register! :seon.agent.ctx/wake?
  ;; Arm the message wake trigger at init (an always-armed agent vs a
  ;; scheduled-only / manual one). DEFAULT true. REPLACES: the unconditional
  ;; install-wake-trigger! in init-agent! (client.cljs 2021). to-build (small —
  ;; a guard around the arm step).
  :boolean)

(schema/register! :seon.agent/schedules
  ;; Seed cron schedules (the :seon.agent.schedule/* maps). DEFAULT [].
  ;; REPLACES: NOTHING at init — schedules are added at runtime today. Lets a
  ;; config-defined agent boot with its cron. wire-existing (attr +
  ;; schedule schema exist, schedule.cljs 42-51). FLAG: schedule-as-config
  ;; may be speculative — the owner wanted it listed (cat 11).
  [:vector :map])

(schema/register! :seon.agent.ctx/auto-terminate?
  ;; Terminate the agent when its run closes (a one-shot task agent) vs stay
  ;; idle for the next message. DEFAULT false. REPLACES: NOTHING — no
  ;; auto-terminate exists (terminate is explicit via the lifecycle verb).
  ;; to-build (new). FLAG: wanted, or is explicit terminate enough?
  :boolean)
```

### Category 12 — Selection (root-context vs agent-context, by identity)

Two NAMED configs, selected by the agent's `:seon.agent/id` (§3.2), NOT a stored
`:kind`. No schema key — this is the merge/selection logic. `:seon.config/root-context`
is a SPARSE override over `:seon.config/agent-context`.

### 2.7 Key inventory + additions beyond the 16-key seed

Grepped the whole init+context surface for dials the seed missed (owner mandate:
find EVERYTHING). The 12 categories yield **44 flat registered dial keys** (plus
the umbrella `:seon.config/agent-context` `:map` + the transcript-tier value keys
`from-turn`/`to-turn`/`token-cap`). Count by category:

| Cat | Keys | Count |
|-----|------|-------|
| 1 Blocks | blocks, block-priorities, extra-blocks | 3 |
| 2 Namespaces | include, current-ns?, include-referred-local?, third-party?, render | 5 |
| 3 Skills | skills/load, skills/order | 2 |
| 4 LLM | llm/{provider,model,temperature,max-output-tokens,context-window,thinking,max-retries} | 7 |
| 5 Identity | soul, persona | 2 |
| 6 Transcript/history | transcript/tiers, transcript/turns-retained, transcript/summary-head?, cache-breakpoint | 4 |
| 7 Budget/clip | token-budget, escape-clipping?, block-caps | 3 |
| 8 Capabilities | toolkit, home-requires, fs/roots, fs/read-only?, capabilities | 5 |
| 9 Memory surfaces | canvas?, canvas-content, findings?, inventory?, warnings?, relevant-source?, cite-card? | 7 |
| 10 Store scope | origin-scope | 1 |
| 11 Wake/lifecycle | default-turn-limit, default-deadline-ms, wake?, schedules, auto-terminate? | 5 |
| 12 Selection | (logic, no key) | 0 |
| | **TOTAL** | **44** |

(44 flat dial keys across cats 1-11. Cat 4's 7 LLM keys + several cat 8/9/11
keys are new promotions of hardcodes/env.)

**Real additions promoted from hardcodes/env/per-fn defaults (beyond the seed's
16):** block-priorities, extra-blocks, namespaces/third-party?, skills/order, the
7 llm/* keys, persona, transcript/turns-retained, transcript/summary-head?,
cache-breakpoint, block-caps, fs/roots, fs/read-only?, capabilities, cite-card?,
origin-scope, default-turn-limit, default-deadline-ms, wake?, schedules,
auto-terminate?.

**Flagged speculative / to-build (owner decides keep-vs-drop):** namespaces/render
(signatures dead — OQ-1), persona (soul-file may be enough), cite-card?
(always-on?), origin-scope (no per-agent origin scoping exists), schedules
(cron-as-config), auto-terminate? (explicit terminate may be enough), the whole
llm/* group (OQ-2 — per-agent LLM vs the global `seon.ai` row).

### 2.8 Full FLAT aero example — BOTH named configs

`config/system.edn` (the ONE manifest; flat keys, comment-grouped by category; NO
`#profile`, NO `:minimal`). ns-name values are authored as symbols and coerced to
keywords by `resolve-agent-context`:

```clojure
{;; the DEFAULT agent context (every non-root agent). Merged over the registered
 ;; code defaults; a per-mint override merges over THIS. FLAT keys — the
 ;; keyword-namespace IS the grouping (comments mark the categories).
 :seon.config/agent-context
 {;; 1 blocks
  :seon.agent.ctx/blocks                              :all
  ;; 2 namespaces
  :seon.agent.ctx.namespaces/include                  #{my.kb my.data my.ui my.tile
                                                        seon.agent.todo seon.agent.message
                                                        seon.agent.lifecycle}
  :seon.agent.ctx.namespaces/current-ns?              true
  :seon.agent.ctx.namespaces/include-referred-local?  true
  :seon.agent.ctx.namespaces/third-party?             true
  ;; 3 skills
  :seon.agent.ctx.skills/load                         #{:repl}
  ;; 4 llm — :inherit = use the global :seon.ai/config row (env-seeded)
  :seon.agent.ctx.llm/provider                        :inherit
  :seon.agent.ctx.llm/max-output-tokens               :inherit
  ;; 5 identity
  :seon.agent.ctx/soul                                :file
  ;; 6 transcript / history
  :seon.agent.ctx.transcript/tiers                    [{:seon.agent.ctx.transcript/from-turn 8
                                                        :seon.agent.ctx.transcript/token-cap 1400}]
  :seon.agent.ctx.transcript/turns-retained           8
  :seon.agent.ctx/cache-breakpoint                    20
  ;; 7 budget / clipping
  :seon.agent.ctx/token-budget                        0
  :seon.agent.ctx/escape-clipping?                    true
  ;; 8 capabilities / tools
  :seon.agent.ctx/toolkit                             #{my.ui my.data my.tile my.kb}
  :seon.agent/home-requires                           [[seon.agent.message :as message]
                                                       [seon.agent :as agent]
                                                       [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
                                                       [seon.schema :as schema]
                                                       [seon.db :as db]
                                                       [seon.agent.todo :as todo]]
  :seon.agent.fs/roots                                []
  :seon.agent.fs/read-only?                           true
  :seon.agent.ctx/capabilities                        #{:grep}
  ;; 9 memory surfaces
  :seon.agent.ctx/canvas?                             true
  :seon.agent.ctx/canvas-content                      :none
  :seon.agent.ctx/findings?                           true
  :seon.agent.ctx/warnings?                           true
  :seon.agent.ctx/relevant-source?                    false
  :seon.agent.ctx/inventory?                          false
  ;; 11 wake / lifecycle
  :seon.agent/default-turn-limit                      20
  :seon.agent.ctx/wake?                               true}

 ;; the ROOT agent's context. Selected by IDENTITY (id "root"), NOT a :kind.
 ;; SPARSE override over agent-context — only what differs: root's canvas IS the
 ;; system dashboard, and it watches the fleet inventory.
 :seon.config/root-context
 {:seon.agent.ctx/canvas-content   seon.render.system/system-view
  :seon.agent.ctx/inventory?       true}}
```

A lean cluster ships a shorter `:seon.agent.ctx.namespaces/include` /
`:seon.agent.ctx.skills/load #{}` — the list IS the policy, no profile name to
decode. Every unlisted key falls to its registered code default.

---

## 3. How `init-agent!` consumes it

### 3.1 Merge order (three layers)

```
code-defaults  ←  named-config (from aero)  ←  per-mint override (the request map)
```

1. **code-defaults** — the registered `:default`s (a `merge-defaults` fn over the
   schema, or an explicit `default-agent-context` const in `seon.config`). This
   is the ONE place a default lives (deleting the scattered defaults in
   `default-seed-blocks`, `default-namespaces-policy`, etc.).
2. **named-config** — `:seon.config/agent-context` OR `:seon.config/root-context`
   from the aero manifest (`load-manifest`), selected by identity (§3.3).
3. **per-mint override** — an optional `:seon.agent.ctx/config` map on the
   `init-agent!` request (the `/agents/new` form or a spawn call can override any
   key for that one agent).

`merge` is key-level (each key wholly replaces, matching today's
`resolve-namespaces` semantics 223) — NOT deep. A resolver
`(config/resolve-agent-context id override)` returns the final validated map.

**The resolved config LANDS AS the agent's datoms.** Because every key is flat +
fully-namespaced (§2.0), the resolved map transacts straight onto the agent
entity (`{:seon.agent/id id, :seon.agent.ctx/blocks …, …}`) — no nested
component sub-entities. This makes the config queryable and live-overridable
per-agent: `(with-agent id (ctx/install! …))` edits one datom, and the render
reads the STORED config (reactive-context model — the config is data, not a
frozen boot decision). A resumed agent keeps its own edited config; a fresh
agent gets the seed. (Blocks stay component-refs as today; the scalar dials are
plain attrs on the agent entity — each needs a line in `agent-bootstrap-attrs`.)

### 3.2 Config-by-identity selection (NOT `:kind`)

```clojure
(defn- context-config-for [id manifest]
  (if (= id "root")
    (merge (:seon.config/agent-context manifest)   ; root inherits the base
           (:seon.config/root-context manifest))    ; then its sparse override
    (:seon.config/agent-context manifest)))
```

Root is identified by its `:seon.agent/id` "root" — NO stored `:seon.agent/role`
or `:kind` datom (the invariant; today's `agent-role` selector already honors
this, `seon.config` 439-445 docstring). This one fn REPLACES the hardcoded
`(when (= "root" aid) …)` branch: root-context sets `canvas-content
system-view`, and `init-agent!` seeds it like any other block.

### 3.3 Where each key drives which mechanism (in `init-agent!`)

`init-agent!` gains a step 2.5 (between `setup-agent-ns!` and `boot!`/arm), OR
`seed-default-ctx!` is folded INTO `init-agent!` and reads the resolved config:

| Config key | Drives |
|-----------|--------|
| `home-requires` | `setup-agent-ns!` (replaces the global `home-ns-require-specs` const — passed in). |
| `blocks` + `findings?`/`warnings?`/`inventory?`/`canvas?`/`relevant-source?` | which entries of `default-seed-blocks` are seed-copied (filter the list by the toggles + `:blocks` set). |
| `namespaces/include` + `current-ns?` + `include-referred-local?` + `render` | `namespaces-policy` (the renderer reads the resolved config, not `default-namespaces-policy`). |
| `skills/load` | the `:skill/<name>` always-on blocks (replaces `resolve-loadout`'s `:load`/`:default-load` fork). |
| `soul` | the soul file-block (present/`:none`/path). |
| `escape-clipping?` | each seeded block's `:seon.render/clip`. |
| `transcript/tiers` | the transcript block's eviction bands (turns `:none` → banded). |
| `token-budget` | eviction target + a render warning (to-build). |
| `toolkit` | which `my.*`/tool nses are indexed-at-boot + rendered full (unifies the client-require + `:always`). |
| `canvas-content` | the `:seon.render.live-tile/content` seed (root → system-view; replaces the hardcoded branch). |
| `default-turn-limit`/`default-deadline-ms` | the run-bound seeds (`agent-bootstrap-attrs` 394-395). |
| `block-priorities`/`cache-breakpoint` | the block sort + cache-prefix breakpoint (replaces the inline priorities + `stable-priority-max`). |
| `block-caps` | per-block render clip caps (replaces the `SEON_RENDER_*_CAP` env family). |
| `fs/roots`/`fs/read-only?`/`capabilities` | the `seon.agent.fs` grant + capability gate (per-agent). |
| `llm/*` | (OQ-2) per-agent LLM resolution over the `:seon.ai/config` row. |
| `wake?`/`schedules`/`auto-terminate?` | the wake-trigger arm + cron seed + one-shot terminate. |
| `origin-scope` | (to-build) the `:seon.db/origin` slice the memory blocks read. |

The seed-copy (`install!`) still runs inside the new agent's `with-agent` scope,
exactly as today (ctx.cljs 1892-1894) — only its INPUT changes from
`(resolve-loadout …)` to `(resolve-agent-context id override)`.

---

## 4. Migration / rip-out

### 4.1 Deleted (the "shitshow")

| Deleted | file:line | Replaced by |
|---------|-----------|-------------|
| hardcoded root branch | `seon.client` 2367-2379 | `root-context` config → `canvas-content` |
| `#profile` + `SEON_PROFILE` read | `seon.config` 164 | named configs (`agent-context`/`root-context`) |
| `config/acme.edn` `#profile` block | `config/acme.edn` | explicit acme `agent-context` |
| `:seon.config/include`/`exclude` + `resolve-skill-rows` | `seon.config` 76-78, 447-464 | `skills/load` as an explicit set |
| `:seon.config/default-load` bridge | `seon.config` 108, 528-530 | `skills/load` (one path, no fork) |
| `:seon.config/role` + `agent-role` | `seon.config` 56, 439-445 | identity→named-config (§3.2) |
| `:seon.config/loadout` (role-keyed) + `:strategy :replace` | `seon.config` 105-115, 501-538 | `agent-context` map (no role key) |
| `default-namespaces-policy` const | `seon.config` 195-206 | the aero `agent-context` default `include` |
| `:seon.config/namespaces-spec`/`namespaces-policy` split | `seon.config` 88-103 | folded into `agent-context` |
| `seed-default-ctx!` role indirection | `seon.agent.ctx` 1879-1895 | reads resolved config directly |
| global `home-ns-require-specs` as the sole source | `seon.eval` 1274 | per-agent `home-requires` (const becomes the default value) |
| hardcoded block priorities + `stable-priority-max` const | `seon.agent.ctx` 1744-1786, 1946 | `block-priorities` + `cache-breakpoint` |
| `SEON_DEFAULT_TURN_LIMIT` env read | `seon.config` 402-408 | `default-turn-limit` (config default) |
| `SEON_FS_ROOT` / `SEON_FS_READ_ONLY` + `configure!` (process-global) | `seon.agent.fs` 8-10 | per-agent `fs/roots` / `fs/read-only?` |
| `SEON_RENDER_MESSAGE/EVAL/TRANSCRIPT_TOKEN/STORE_EDN/RESULT_CAP` family | `seon.config` 327-363 | `block-caps` (+ `token-budget`) |
| `SEON_AI_MAX_RETRIES` env read in `call-llm!` | `seon.agent.turn` 344 | `llm/max-retries` (if OQ-2 = per-agent) |

`resolve-loadout`, `resolve-namespaces`, `resolve-routes` collapse into ONE
`resolve-agent-context`. `:seon.config/routes` (route curation) is ORTHOGONAL to
agent-context (it's cluster-level, not per-agent) — KEEP it as its own manifest
section; it is not part of this rip-out.

**Env-var rip-out.** The tuning env vars above (`SEON_DEFAULT_TURN_LIMIT`,
`SEON_FS_*`, the `SEON_RENDER_*_CAP` family, `SEON_AI_MAX_RETRIES`) become config
keys — deletable IFF the owner accepts config-over-env for these (the `SEON_AI_*`
provider row stays env→DB per its own design; the render/agent tuning knobs are
the candidates). Launch-wiring env (ports, sockets, cluster dir) is NOT touched.
FLAG: env-to-config for these is a real behavior move — some (gym `SEON_PROFILE`
steering) are load-bearing for the harness; confirm before deleting the env read.

### 4.2 Behavior parity (nothing silently changes)

Every current default (§1.4) maps to an explicit config default (§2.8). Set
side-by-side, a no-override boot must produce a **byte-identical** seed to today:

- blocks: `:all` = the 11-block list minus inventory (inventory? false).
- namespaces `:include`: the exact 7-ns set.
- skills: `#{:repl}` (system.edn) — acme's `:all`+list becomes acme's explicit
  `agent-context :skills/load`.
- home-requires: the exact 6 specs.
- root canvas: `system-view` (via root-context, not the branch).

The ONE intended CHANGE (owner-endorsed, not silent): `escape-clipping?` default
`true` (#43) and `transcript/tiers` ON (#62 is already live). Both are gym-measured
before/after.

---

## 5. Subsumes — pending tasks folded in

| Task | Folds in as | wire-existing / to-build |
|------|-------------|--------------------------|
| **#42** explicit-listing config | `namespaces/include` + `skills/load` as explicit sets; `#profile`/`:minimal` deleted | wire-existing (the policy seam is built; this reshapes it into agent-context) |
| **#43** escape value-clipping | `escape-clipping?` (default true) | wire-existing (clip fns exist; flip to `:none`) |
| **#45** disable inventory | `inventory?` (default false) | wire-existing (re-add block when true) |
| **#56 / #73** home-ns aliases real, not magic | `home-requires` per-agent (REAL requires in the ns form) | wire-existing (`home-ns-require-specs` → config value); the authored-ns rewrite (#73) is a SEPARATE eval-path fix, NOT this config — flag it stays Core's A2 |
| **#62** transcript tiers | `transcript/tiers` | to-build-PARTIAL (age-banding exists, OFF; this parameterizes + enables) |
| **#74** todo signature-trim | MOOT — signatures retired; `todo` is just a member of `namespaces/include` | n/a (drop from `include` to trim) |

**Wire-existing (config just reshapes / toggles built machinery):** #42, #43,
#45, #56/#73-config-half, #74; plus block-priorities, cache-breakpoint, toolkit
unify, findings/warnings/inventory/canvas/relevant toggles, default-turn/deadline,
extra-blocks.
**Need mechanism built first:** #62 tier parameterization (partial); `token-budget`
enforcement (new); the 7 `llm/*` per-agent keys (new — or defer to seon.ai, OQ-2);
per-agent `fs/roots`+`capabilities` grant (today process-global); `origin-scope`
store slicing (new); `block-caps` centralization; `persona`, `cite-card?`,
`auto-terminate?`, `skills/order`, `wake?` (small toggles); `summary-head?`.

---

## 6. Open questions for the owner

1. **`:seon.agent.ctx.namespaces/render :full | :signature`** — signatures are
   RETIRED (you rejected the trim). Keep the key as a `:full`-only placeholder,
   or DROP it entirely so the schema doesn't carry a dead mode? (Recommend drop.)

2. **Per-agent `:seon.agent/model`** — model is currently a GLOBAL env→DB
   `:seon.ai/config` row (deliberately separate from the loadout manifest,
   `config/system.edn` comment). Do you want per-agent model in this
   context-config (a real new mechanism), or does it stay the `seon.ai` surface
   and this key is dropped? (Registered as a deferred hook for now.)

3. **`token-budget` semantics** — there is NO whole-context budget today
   (ctx.cljs explicitly: "There is NO char budget"). Is `token-budget` a hard
   cap (evict to fit) or a soft advisory (warn + drive tier selection)? Building
   a hard cap is real work; a soft advisory is cheap. (Recommend soft first.)

---

## Appendix — grounding (every claim is at a real file:line)

- `init-agent!` — `src/seon/client.cljs` 1975-2026
- hardcoded root branch + "removed when config loader done" comment —
  `src/seon/client.cljs` 2367-2379
- `default-seed-blocks` — `src/seon/agent/ctx.cljs` 1700-1786
- `seed-default-ctx!` / `install!` / `remove!` — `src/seon/agent/ctx.cljs` 1820-1895
- "no char budget" — `src/seon/agent/ctx.cljs` 1908-1910
- clip fns — `src/seon/agent/ctx.cljs` 349, 1209
- `seon.config` manifest + `resolve-loadout`/`resolve-namespaces`/`agent-role` —
  `src/seon/config.cljs` 55-130, 195-244, 439-538
- `namespaces-policy` render logic — `src/seon/agent/ctx/namespaces.cljs` 117-234
- `home-ns-require-specs` — `src/seon/eval.cljs` 1274-1291
- transcript clip OFF — `src/seon/agent/ctx/transcript.cljs` 46-52, 544
- owner decisions #42/#43/#45/#56/#73/#74 — `docs/prds/agent-fsm/core-handoff-2026-06-29.md`
  138-156, 195-204
