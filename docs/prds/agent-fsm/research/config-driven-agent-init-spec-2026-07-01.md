---
type: research
status: draft
tags: [research, agent, config]
---

# Config-driven agent initialization + context composition (SPEC v2)

One `init-agent!` takes a Malli-spec'd, aero-loaded config map describing the
ENTIRE agent + its context, and wires everything from it. This is the
CONTEXT-CONFIG half of the `init-agent!` unification (the lifecycle half landed
in `bfac6f50`). SPEC ONLY — no build; the owner reviews before any rip-out.

**v2 (owner review folded in — the 12 settled decisions in
[[config-driven-agent-init-decisions-2026-07-01]]).** Highlights: keep per-agent
`:seon.ai/agent-*` LLM (falls out free as record attrs, decision 6); DROP the
whole-context `token-budget` — no fixed-growth clipping yet, blocks render FULL
(`escape-clipping? true`); growth is bounded by transcript `tiers` +
per-eval-result `result-decay` (decisions 7, 8). Keystone: **reactive
config-on-record** — every dial is a scalar attr on the agent's record and every
renderer sources its config from the agent's datoms AT RENDER TIME, so a single
`db/transact!` re-derives the next context AND the datastar UI, no apply step
(§3, decision 2). **Colocation naming pass** — each key's namespace = the real
CODE ns that operates it, its `register!` moves there, with a malli-native
`:default` on the schema (decisions 3, 4). Two investigate-then-design surfaces:
**eval-result decay** (near-full → partial → clipped over turns, §2.6, decision 8)
and **namespace VIEWS** (per-ns source/tests/signatures aspects, replacing the dead
`:full|:signature` knob, §2.2, decision 9); plus the **skills summary→expand**
trace (already config-on-record, not duplicated — §2.12, decision 10).

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
- **Final key count: 46 flat dial keys across 17 OWNING nses** (+ 6 register-once
  helper shapes + the `:seon.config/*` umbrella). v2 naming pass: each key's
  namespace = the REAL CODE NS that operates it (colocation), each `register!`
  carries its malli-native `:default` (§2.0c), each spec tightened + shared shapes
  referenced (§2.0d). Flat keys → the config lands as the agent's queryable,
  live-overridable DATOMS (§2.0, §3.0 keystone).

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

## 2. The config schema — grouped by OWNING namespace (EXHAUSTIVE, v2)

Every dial is ONE flat, fully-namespaced, registered Malli key — a scalar attr on
the agent's record. **v2 naming pass (owner directive): each key's namespace is
the REAL CODE NAMESPACE that owns/operates on it** (the Seon "keyword-ns = real
code ns" rule). The `schema/register!` for each key MOVES into its owning ns,
colocated with the fns that read it — so the config attr and its operating code
sit together. Below is grouped BY owning ns to make the colocation visible.

### 2.0 STRUCTURE — flat keys, namespace = owning code ns

**Every dial is ONE flat, fully-namespaced, registered Malli key** — NOT a nested
map. The keyword-namespace does double duty: it groups the dial AND names the code
that operates it.

**Why flat, not `{:namespaces {:include …}}`:**

1. **Datom-native + reactive.** The config IS the agent's STORED config — flat
   namespaced keys transact directly as the agent's datoms, queryable +
   live-overridable per-agent. A nested `{:include …}` would force a component
   sub-entity per group (datahike has no nested maps). Flat = one flat datom set;
   a single `db/transact!` to one attr re-derives the render (§3, the keystone).
2. **No bare keys.** A nested `{:include …}` has a BARE `:include` — breaks "every
   key namespaced". The flat `:seon.agent.ctx.namespaces/include` keeps the rule.
3. **Colocation.** The namespace names the OWNING code, so `register!` +
   reader-fn live in one ns (`:seon.agent.fs/roots` reads in `seon.agent.fs`;
   `:seon.ai/model` in `seon.ai`; `:seon.render.live-tile/content` in
   `seon.render.live-tile`). This is the v2 change from the flat-but-ctx-heavy v1.

Nested VALUES under a flat key are fine (transcript `tiers`, `home-requires`) —
that's DATA, not structure.

### 2.0b The naming pass — where each `register!` MOVES

The umbrella `:seon.config/agent-context` `:map` (registered in `seon.config`, the
manifest reader) references every key below by keyword — but each key's own
`schema/register!` lives in its OWNING ns (confirmed from source, §2.9). `seon.config`
stays a LEAF (references shapes by keyword, never var-requires the owning nses), so
no cycle: it validates the manifest against `:seon.config/agent-context` whose member
keys are registered elsewhere at their owners' ns-load time (registry is global).
`resolve-agent-context` coerces authored ns-name symbols (`my.kb`) to keywords
(`:my.kb`) — same as today's `ns-sym->kw` (config.cljs 208).

### 2.0c Defaults are MALLI-NATIVE (`:default` property), not a code const

**Confirmed in `reference-code/malli` (transform.cljc:484-520 +
README:1323-1332):** a `:default` property on a schema (or `:default/fn` for a
computed default) plus `(m/decode schema v (mt/default-value-transformer
{::mt/add-optional-keys true}))` fills EVERY unspecified key from its declared
default. `::add-optional-keys true` is REQUIRED because our keys are all
`{:optional true}` (without it the transformer skips optional keys). So:

- **Every config key carries its `:default` inline in its `register!`** (colocated
  in the owning ns). Shown below as `{:default …}` on each key's schema.
- **There is NO separate "code-defaults layer / `default-agent-context` const".**
  `resolve-agent-context` = `(-> (merge named-config per-mint-override) (m/decode
  :seon.config/agent-context (mt/default-value-transformer {::mt/add-optional-keys
  true})))` — merge the two explicit layers, then decode to fill the rest from the
  declared defaults. The DEFAULT lives ONCE, on the schema, in the owning ns (the
  single-source-of-truth rule).

### 2.0d Tightened specs + SHARED registered shapes (register once, reference)

Loose `:map`/`:any`/`:int` are tightened per the malli idiom; repeated shapes are
registered ONCE and referenced (never inlined twice):

- `:seon.agent.ctx/block` — the block map shape (already the target of
  `install!`'s `:seon.agent.ctx/block` validation; reference it from
  `extra-blocks`, don't inline `:map`).
- `:seon.agent.schedule/schedule` — the cron map shape (already registered,
  schedule.cljs 44-51; reference from `:seon.agent.schedule/seed`).
- `:seon.agent.ctx.transcript/tier` — the eviction-tier map (register once,
  reference from `tiers`).
- `:seon.agent.ctx.transcript/decay-level` — the decay-level map (register once,
  reference from `result-decay`).
- `:seon.eval/require-spec` — the `[ns :as alias]` / `[ns :refer [..]]` shape
  (register once, reference from `home-requires`).
- `:seon.agent.ctx.namespaces/aspect` / `:seon.ai/provider` /
  `:seon.agent.ctx/capability` — real `:enum` value sets, referenced everywhere.

**Leaf-rule caveat:** where `seon.config` itself would need to reference an owning
ns's rich shape it can't (leaf — no var require), the umbrella
`:seon.config/agent-context` references those keys by their registered KEYWORD
(resolved at the owner's ns-load, registry is global) — so the tight shape still
applies, validated when the owning ns has loaded. The only genuinely-looser spots
are noted per key (`:map`/`:any` → "validated downstream at `install!`/`transact!`").

### 2.1 `seon.agent.ctx` — the composer (blocks, layout, clipping, identity)

```clojure
;; in seon.agent.ctx — use ::keyword (expands to :seon.agent.ctx/*)
(schema/register! ::capability [:enum :grep :exec :http])   ; the capability enum, referenced by ::capabilities

(schema/register! ::blocks
  ;; WHICH context sections render. :all | a set of block names.
  ;; REPLACES: hardcoded default-seed-blocks list (ctx.cljs 1700-1786). wire-existing.
  [:or {:default :all} [:enum :all] [:set :seon.agent.ctx/name]])

(schema/register! ::block-priorities
  ;; Per-block priority overrides (name→int). DEFAULT {} (hardcoded: soul 5,
  ;; agents 8, shared-instructions 10, skills-catalog 12, namespaces 20,
  ;; live-tile 35, warnings 40, open-todos 45, relevant-source 48, findings 97,
  ;; transcript 100). REPLACES: inline priorities in default-seed-blocks. wire-existing.
  [:map-of {:default {}} :seon.agent.ctx/name [:int {:min 0}]])

(schema/register! ::extra-blocks
  ;; Extra/override blocks upserted by name — references the REGISTERED block
  ;; shape (register-once), NOT a bare :map. REPLACES: :seon.config/loadout
  ;; :blocks (config.cljs 112). wire-existing.
  [:vector {:default []} :seon.agent.ctx/block])

(schema/register! ::cache-breakpoint
  ;; Priority ≤ this = the byte-stable cacheable PREFIX. DEFAULT 20
  ;; (stable-priority-max const, ctx.cljs 1946). REPLACES: that const. wire-existing.
  [:int {:default 20 :min 0}])

(schema/register! ::escape-clipping?
  ;; Blocks render FULL, escaping the per-value render clip (#43). DEFAULT true
  ;; (owner v2: full by default, no fixed-growth clipping yet). REPLACES: the
  ;; clip default in clip-or-full (ctx.cljs 349) / clip (1209). wire-existing.
  [:boolean {:default true}])

(schema/register! ::soul
  ;; :none | :file (SOUL.md present→block) | a path string. DEFAULT :file.
  ;; REPLACES: hardcoded soul-file-path file-block (ctx.cljs 1744-1746). wire-existing.
  [:or {:default :file} [:enum :none :file] [:string {:min 1}]])

(schema/register! ::persona
  ;; Inline persona/name string as a block (config-defined agent, no SOUL.md).
  ;; DEFAULT :none. REPLACES: NOTHING (persona = SOUL.md only today). to-build.
  ;; OWNER-flagged: keep, or is soul-file enough? (Lives in ctx beside file-block.)
  [:or {:default :none} [:enum :none] [:string {:min 1}]])

(schema/register! ::capabilities
  ;; Capability gate: which local surfaces the agent may reach. DEFAULT #{:grep}
  ;; (seon.agent.search bundled). :exec/:http don't exist yet. REPLACES: NOTHING
  ;; structured (grep unconditional today). to-build. OWNS-NOTE: no single
  ;; provider ns owns the GATE (it spans search/fs/http), so it lives in ctx —
  ;; the composer that gates them (per the rule: put it where its primary
  ;; operating fn lives when no clean single owner).
  [:set {:default #{:grep}} ::capability])
```

### 2.2 `seon.agent.ctx.namespaces` — the #42 lever + the VIEW model

WHICH nses render (the flat hierarchical set) is unchanged; WHAT ASPECT each
renders is the v2 view model (replaces the dead `:full|:signature` knob).

```clojure
;; in seon.agent.ctx.namespaces — use ::keyword (expands to :seon.agent.ctx.namespaces/*)
(schema/register! ::aspect [:enum :source :tests :signatures])   ; the view-aspect enum, referenced below

(schema/register! ::include
  ;; Always-render ns keywords. REPLACES: default-namespaces-policy :always
  ;; (config.cljs 204). wire-existing.
  [:set {:default #{:my.kb :my.data :my.ui :my.tile
                    :seon.agent.todo :seon.agent.message :seon.agent.lifecycle}}
   :keyword])

(schema/register! ::current-ns?
  ;; Auto-render the agent's CURRENT ns. REPLACES: :seon.config/current-ns
  ;; :full/:off (config.cljs 88-95). wire-existing.
  [:boolean {:default true}])

(schema/register! ::include-referred-local?
  ;; Pull in the current ns's LOCAL requires with stored source. REPLACES:
  ;; required-full-set (namespaces.cljs 197-221, always-on). wire-existing.
  [:boolean {:default true}])

(schema/register! ::third-party?
  ;; Render third-party (acme) ns source. REPLACES: third-party-ns?
  ;; unconditional inclusion (namespaces.cljs 156-176). wire-existing.
  [:boolean {:default true}])

;; --- VIEW MODEL (v2): a ns renders one-or-more ASPECTS, not full|signature.
;; An aspect is a slice render-one-ns-ai (ctx.cljs 1365-1414) already assembles:
;;   :source     — (ns …) form + every :seon.fn/:seon.schema FULL body
;;   :tests      — the ns's colocated :seon.test blocks (test-block-ai, ctx.cljs
;;                 1330) — TODAY the *-test NS is elided (namespaces.cljs
;;                 test-ns-name? 80-90) and only a fn's own :seon.test/_ns rows
;;                 render; this makes tests a first-class selectable aspect (#83)
;;   :signatures — fn arglists/doc head only (the RETIRED signature path, back as
;;                 ONE aspect, not the whole knob)
(schema/register! ::view
  ;; DEFAULT aspect-set every INCLUDED ns renders (#{:source} = byte-identical to
  ;; today). REPLACES: :seon.render/detail :full (ns.cljs 1482, DELETED).
  ;; to-build-PARTIAL (source wire-existing; tests un-elides; signatures rebuilds
  ;; the retired path as an aspect).
  [:set {:default #{:source}} ::aspect])

(schema/register! ::current-ns-view
  ;; The RICHER aspect-set the CURRENT ns renders (workspace shows its TESTS).
  ;; REPLACES: NOTHING (current ns = same view today). to-build (per-scope aspect
  ;; selection: overrides ::view for cur-ns).
  [:set {:default #{:source :tests}} ::aspect])

(schema/register! ::view-overrides
  ;; PER-NS aspect overrides (ns-kw → aspect-set). REPLACES: NOTHING. to-build.
  ;; OWNER-flagged: ship the two scope defaults first; add per-ns only if needed.
  [:map-of {:default {}} :keyword [:set ::aspect]])
```

### 2.3 `my.skills` — the skill corpus (load-set, order)

```clojure
;; in my.skills — ::name is the existing :my.skills/name (skill handle keyword)
(schema/register! ::load
  ;; :all | a set of skill names whose BODY is always-on | #{}. REPLACES:
  ;; :seon.config/skills :load + the DELETED :default-load bridge (config.cljs
  ;; 528-530) + include/exclude (config.cljs 76-78, resolve-skill-rows 447-464)
  ;; + #profile :default/:minimal SETS. Owner: agents rarely load skills, so the
  ;; always-on base is what matters. wire-existing (my.skills owns load/catalog-
  ;; block/skill-block; the key moves from :seon.config/skills here). References
  ;; the registered ::name shape (register-once), NOT a bare :keyword.
  [:or {:default #{:repl}} [:enum :all] [:set :my.skills/name]])

(schema/register! ::order
  ;; Explicit render order for the always-on bodies. DEFAULT [] (priority-16 seed
  ;; order). REPLACES: implicit scan order (my.skills/skill-block). to-build (small).
  [:vector {:default []} :my.skills/name])
```

### 2.4 `seon.ai` — per-agent LLM (KEEP, owner v2)

The `:seon.ai/config` singleton row (`seon.ai` 152-213) already carries
`::provider`/`::model`/`::temperature`/`::max-tokens`/`::thinking`/`::timeout-ms`/
`::base-url`. **Owner v2: KEEP the llm group — it falls out free as attrs on the
agent record.** These keys are per-agent OVERRIDES of that global row, registered
IN `seon.ai` (colocated with the row schema + the resolver), each defaulting to
`:inherit` (use the global row). Per-agent resolution (`effective-config-for id`)
is the one new mechanism.

```clojure
;; in seon.ai — per-agent overrides, each DEFAULT :inherit (use the global row).
;; Each references the EXISTING global-row value shape (register-once): the
;; provider :enum, the temperature :double etc. are already registered on
;; :seon.ai/config (seon.ai 160-213) and reused here, NOT re-inlined.
(schema/register! ::agent-provider       [:or {:default :inherit} [:enum :inherit] :seon.ai/provider])
(schema/register! ::agent-model          [:or {:default :inherit} [:enum :inherit] [:string {:min 1}]])
(schema/register! ::agent-temperature    [:or {:default :inherit} [:enum :inherit] [:double {:min 0.0 :max 2.0}]])
(schema/register! ::agent-max-tokens     [:or {:default :inherit} [:enum :inherit] [:int {:min 1}]])  ; OUTPUT cap
(schema/register! ::agent-context-window [:or {:default :inherit} [:enum :inherit] [:int {:min 1}]])  ; INPUT budget — NEW, nothing enforces it today
(schema/register! ::agent-thinking       [:or {:default :inherit} [:enum :inherit] [:string {:min 1}]])
(schema/register! ::agent-max-retries    [:or {:default :inherit} [:enum :inherit] [:int {:min 0}]])  ; REPLACES SEON_AI_MAX_RETRIES (turn.cljs 344)
```

(Names use the `agent-` prefix so they don't collide with the existing global-row
attrs `:seon.ai/provider` etc. — both live in `seon.ai`; the resolver
`effective-config-for` merges per-agent over global. `:seon.ai/provider` is the
existing `[:enum :deepseek :anthropic :openai-compat :diffusiongemma]`, seon.ai 160.)

### 2.5 `seon.agent.ctx.transcript` — history eviction + eval-result DECAY

```clojure
;; in seon.agent.ctx.transcript. SHARED shapes registered ONCE, referenced below.
(schema/register! ::tier
  ;; ONE age-banded eviction tier.
  [:map
   [::from-turn                :int]
   [::to-turn   {:optional true} :int]
   [::token-cap [:int {:min 0}]]])

(schema/register! ::decay-level
  ;; ONE eval-result decay level (age-offset → cap).
  [:map
   [::from-turn-offset [:int {:min 0}]]
   [::token-cap        [:int {:min 0}]]])

(schema/register! ::tiers
  ;; Age-banded clip tiers. DEFAULT :off (today :seon.render/clip :none,
  ;; transcript.cljs 544). REPLACES: disabled eviction +
  ;; SEON_RENDER_TRANSCRIPT_TOKEN_CAP (config.cljs 357-363). to-build-PARTIAL
  ;; (#62 — age-banding exists, parameterize + enable). References ::tier.
  [:or {:default :off} [:enum :off] [:vector ::tier]])

(schema/register! ::turns-retained
  ;; Past turns rendered verbatim before eviction. REPLACES: implicit "all turns"
  ;; (transcript.cljs 544). to-build.
  [:int {:default 8 :min 0}])

(schema/register! ::summary-head?
  ;; Render the masthead/summary head. REPLACES: always-on masthead. wire-existing.
  [:boolean {:default true}])

;; --- EVAL-RESULT DECAY (owner v2 — investigate-then-design; see §2.6) ---
(schema/register! ::result-decay
  ;; The per-eval-result age→clip schedule: a result renders NEAR-FULL right after
  ;; execution (agent can view a big file it just read), then DECAYS to partial,
  ;; then clipped over subsequent turns. A vector of ::decay-level maps, applied by
  ;; the result's AGE in turns (current-turn − eval's turn). DEFAULT the 3-level
  ;; schedule below. REPLACES: NOTHING — today every eval-result renders at a FIXED
  ;; cap (result-body-render-cap 16384) regardless of age (format-eval-row takes
  ;; only [row prior?], no turn-offset). to-build (NEW mechanism — the decay axis).
  [:vector {:default [{::from-turn-offset 0 ::token-cap 16384}
                      {::from-turn-offset 2 ::token-cap 1500}
                      {::from-turn-offset 5 ::token-cap 200}]}
   ::decay-level])
```

### 2.6 EVAL-RESULT DECAY — today vs the design

**Today (investigated):** `format-eval-row` (ctx.cljs 592-644) renders one eval
with caps SPLIT by component — echoed source + stdout at `eval-render-cap` (1500),
the citable result body at `result-body-render-cap` (16384, = the store ceiling,
so a stored result renders WHOLE). **There is NO age/decay.** The ONLY age
distinction is `prior?` (a previous-process eval → no `result/<id>` handle, its
var died with the restart). A `:seon.render/full?` per-row flag can pin a row
whole past its cap, but NOTHING sets it by recency. So a giant file the agent read
20 turns ago renders at the SAME 16384 as the one it read this turn — the transcript
carries stale bulk until #62's whole-transcript eviction bands it by age.

**The design (owner: near-full → partial → clipped over turns):** a per-result
decay schedule keyed on the result's AGE (turn-offset = current-turn − the eval's
turn), living in the transcript ns beside `format-eval-row`. `result-decay` is a
vector of `{from-turn-offset, token-cap}` levels; the eval-row renderer picks the
cap for the result's current age. Default two-level schedule:

```clojure
[{:seon.agent.ctx.transcript/from-turn-offset 0 :seon.agent.ctx.transcript/token-cap 16384}  ; this turn + next: NEAR-FULL (view the file you just read)
 {:seon.agent.ctx.transcript/from-turn-offset 2 :seon.agent.ctx.transcript/token-cap 1500}   ; 2+ turns old: PARTIAL
 {:seon.agent.ctx.transcript/from-turn-offset 5 :seon.agent.ctx.transcript/token-cap 200}]   ; 5+ turns old: CLIPPED to a stub + result/<id> handle
```

Mechanism notes (grounding the build): (1) `format-eval-row` gains the result's
turn-offset (derivable — the eval's `:seon.eval/at` / its turn vs the current
turn); it selects the cap from `result-decay` and passes it to `cap-result-body`
(which already emits the "bind result/<id> to see the whole value" guide on clip).
(2) BYTE-STABILITY: a decay LEVEL must render byte-identically while the result
sits in that band (the level's cap is fixed per band, only changes at a band
boundary) — same discipline as #62's age-banding (aged clips freeze so the LLM
prompt cache holds; recency-WEIGHTING would re-flow and bust the cache). (3) The
clipped stub always keeps the `result/<id>` handle (live-process results) so the
agent can re-inflate on demand. This is additive to #62 (whole-transcript token
eviction): decay caps INDIVIDUAL result bodies by age; #62 bands the WHOLE
transcript — orthogonal levers, both age-keyed.

### 2.7 `seon.eval` — home ns wiring + toolkit

```clojure
;; in seon.eval. SHARED require-spec shape registered ONCE (the [ns :as alias] /
;; [ns :refer [..]] form) — replaces the no-`:any` violation with a real shape.
(schema/register! ::require-spec
  ;; A (require …)-style spec: [ns :as alias] | [ns :refer [verbs…]]. A vector
  ;; whose head is a ns symbol; validated structurally (symbol head + a keyword
  ;; directive), not :any.
  [:cat :symbol [:enum :as :refer] [:or :symbol [:vector :symbol]]])

(schema/register! ::home-requires
  ;; REAL ns requires for the agent's home ns (#73, #56 — no-magic). References
  ;; ::require-spec (register-once), NOT :any. REPLACES: the global
  ;; home-ns-require-specs const (eval.cljs 1274 — becomes per-agent config; the
  ;; const IS the default value). wire-existing (seon.eval owns setup-agent-ns!).
  [:vector {:default '[[seon.agent.message :as message]
                       [seon.agent :as agent]
                       [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
                       [seon.schema :as schema]
                       [seon.db :as db]
                       [seon.agent.todo :as todo]]}
   ::require-spec])

(schema/register! ::toolkit
  ;; Capability nses seeded into reach (indexed-at-boot + rendered full).
  ;; REPLACES: the split "required in client.cljs + listed in :always".
  ;; wire-existing-PARTIAL. OWNS-NOTE: seon.eval owns the ns-setup/lookup path the
  ;; toolkit is reached through, so the toolkit set lives here beside home-requires.
  [:set {:default #{:my.ui :my.data :my.tile :my.kb}} :keyword])
```

### 2.8 `seon.agent.fs` — filesystem grant

```clojure
;; in seon.agent.fs
(schema/register! ::roots
  ;; FS roots the agent may read/list under. REPLACES: configure! + SEON_FS_ROOT
  ;; (fs.cljs 8-9, process-global). to-build (per-agent grant).
  [:vector {:default []} [:string {:min 1}]])

(schema/register! ::read-only?
  ;; Writes require this false. REPLACES: the read-only? grant flag + SEON_FS_READ_ONLY
  ;; (fs.cljs 9-10). to-build (per-agent).
  [:boolean {:default true}])
```

### 2.9 The remaining owning nses (memory surfaces, run bounds, lifecycle)

Each block ns owns its own toggle (colocated with the block fn); the run/schedule/
lifecycle/render nses own their bounds.

```clojure
;; each ::keyword is registered INSIDE the named owning ns (shown as the comment).

;; in seon.agent.ctx.findings — the findings block (findings.cljs 145)
(schema/register! ::enabled? [:boolean {:default true}])  ; :seon.agent.ctx.findings/enabled? — REPLACES hardcoded :findings block. wire-existing.

;; in seon.agent.ctx.warnings — the warnings block (warnings.cljs 10)
(schema/register! ::enabled? [:boolean {:default true}])  ; :seon.agent.ctx.warnings/enabled? — REPLACES hardcoded :warnings block. wire-existing.

;; in seon.agent.ctx.inventory — the store-overview block (inventory.cljs 141)
(schema/register! ::enabled? [:boolean {:default false}]) ; :seon.agent.ctx.inventory/enabled? — DEFAULT false (#45; today omitted, ctx.cljs 1775-1779). wire-existing.

;; in seon.agent.ctx.relevant — the SEON_EMBED KNN block (relevant.cljs 99)
(schema/register! ::enabled? [:boolean {:default false}]) ; :seon.agent.ctx.relevant/enabled? — DEFAULT false (env-gated today). wire-existing.

;; in seon.render.live-tile — the canvas (attr ::content ALREADY lives here)
(schema/register! ::enabled? [:boolean {:default true}])  ; :seon.render.live-tile/enabled? — REPLACES hardcoded :live-tile block (was canvas?). wire-existing.
(schema/register! ::content  [:or {:default :none} [:enum :none] :symbol]) ; widen the EXISTING attr to allow :none; DEFAULT :none. REPLACES the hardcoded root branch (client.cljs 2367-2379 — THE branch this kills; was canvas-content).

;; in seon.agent.run — the run bounds (run.cljs 93-97)
(schema/register! ::default-turn-limit  [:int {:default 20 :min 1}])   ; REPLACES SEON_DEFAULT_TURN_LIMIT (config.cljs 402-408) + the const.
(schema/register! ::default-deadline-ms [:int {:default 900000 :min 1}]) ; DEFAULT = run.cljs const (confirm the live value). REPLACES the const.
;; (these carry the SEED value; the run's own :seon.agent.run/turn-limit attr is
;; the live bumpable bound — the config seeds it, per-agent.)

;; in seon.agent.schedule — cron seed; references the EXISTING schedule map shape
;; (schedule.cljs 44-51), register-once, NOT a bare :map.
(schema/register! ::seed [:vector {:default []} :seon.agent.schedule/schedule]) ; DEFAULT []. Seed cron at init. REPLACES NOTHING (runtime today). OWNER-flagged (speculative).

;; in seon.agent.lifecycle — one-shot terminate (owns wait/complete/terminate)
(schema/register! ::auto-terminate? [:boolean {:default false}]) ; REPLACES NOTHING (terminate is explicit). to-build. OWNER-flagged.

;; in seon.client — the wake-trigger arm (owns the install-wake-trigger! call)
(schema/register! ::wake? [:boolean {:default true}]) ; Arm the message wake trigger at init. REPLACES the unconditional arm (client.cljs 2021). to-build (small guard).

;; in seon.db — the store slice the memory surfaces read (owns :seon.db/origin)
(schema/register! ::origin-scope [:or {:default :all} [:enum :all] [:set :keyword]]) ; REPLACES NOTHING (unscoped reads today). to-build. OWNER-flagged.

;; in seon.agent.ctx.transcript — the fabrication cite-card guard (#63)
(schema/register! ::cite-card? [:boolean {:default true}]) ; :seon.agent.ctx.transcript/cite-card? — REPLACES NOTHING toggleable (always-on today). to-build. OWNER-flagged: is a toggle wanted?
```

**NB on `::` inside a shared block ns:** four memory-surface nses each register a
`::enabled?` — they DON'T collide because each expands to its OWN namespace
(`:seon.agent.ctx.findings/enabled?` vs `…warnings/enabled?` …). The `::` form is
correct AND unambiguous precisely because the keyword-ns = the code ns.

**Colocation summary — each `register!` MOVES into its owning ns:**

| Owning ns | Keys |
|-----------|------|
| `seon.agent.ctx` | blocks, block-priorities, extra-blocks, cache-breakpoint, escape-clipping?, soul, persona, capabilities |
| `seon.agent.ctx.namespaces` | include, current-ns?, include-referred-local?, third-party?, view, current-ns-view, view-overrides |
| `my.skills` | load, order |
| `seon.ai` | agent-provider, agent-model, agent-temperature, agent-max-tokens, agent-context-window, agent-thinking, agent-max-retries |
| `seon.agent.ctx.transcript` | tiers, turns-retained, summary-head?, result-decay, cite-card? |
| `seon.eval` | home-requires, toolkit |
| `seon.agent.fs` | roots, read-only? |
| `seon.agent.ctx.findings` | enabled? |
| `seon.agent.ctx.warnings` | enabled? |
| `seon.agent.ctx.inventory` | enabled? |
| `seon.agent.ctx.relevant` | enabled? |
| `seon.render.live-tile` | enabled?, content |
| `seon.agent.run` | default-turn-limit, default-deadline-ms |
| `seon.agent.schedule` | seed |
| `seon.agent.lifecycle` | auto-terminate? |
| `seon.client` | wake? |
| `seon.db` | origin-scope |
| `seon.config` | (umbrella `:seon.config/agent-context` + `root-context` only) |

**Total: 46 dial keys across 17 owning nses.** Plus 6 register-once HELPER shapes
(not dials, referenced by the dials): `:seon.agent.ctx/capability`,
`:seon.agent.ctx.namespaces/aspect`, `:seon.agent.ctx.transcript/tier` +
`/decay-level`, `:seon.eval/require-spec`, and the reused existing
`:seon.agent.ctx/block` / `:seon.agent.schedule/schedule` / `:seon.ai/provider`.
Plus the `:seon.config/agent-context` + `root-context` umbrella (in `seon.config`).
Every dial is a scalar attr on the agent's record; the namespace names the code
that reads it, and its `:default` lives on the schema (§2.0c).

Two keys DROPPED from v1: `:seon.agent.ctx/token-budget` (decision 7 — no
whole-context budget yet) and `:seon.agent.ctx.namespaces/render` (decision 9 —
replaced by the view model). Keys RE-HOMED from v1's ctx-heavy naming:
toolkit→`seon.eval`, skills→`my.skills`, llm→`seon.ai`, canvas→`seon.render.live-tile`,
run bounds→`seon.agent.run`, wake→`seon.client`, origin-scope→`seon.db`, the four
memory toggles→their block nses.

### 2.10 CONFIRMED owning nses (from source)

- **skills** → `my.skills` (`src/my/skills.cljs`): owns `load`, `catalog-block`,
  `skill-block`, `seed-skills-tx-data`. The key moves from `:seon.config/skills` here.
- **cite-card** → `seon.agent.ctx.transcript` (the only ref site; a transcript
  fabrication guard, #63). Lives beside `format-eval-row`.
- **soul / persona** → `seon.agent.ctx` (`file-block` / `file-block-ai`, ctx.cljs
  131-215). No separate soul ns exists; ctx is the file-block owner.
- **capabilities gate** → `seon.agent.ctx` (NO single provider ns owns a gate that
  spans `seon.agent.search` [grep] + `seon.agent.fs` [fs] + a future http; per the
  rule, it goes where its primary gating fn lives — the composer, ctx).
- **memory toggles** → each block's own ns (`seon.agent.ctx.findings`/`.warnings`/
  `.inventory`/`.relevant`), colocated with the `*-block` fn.

### 2.11 Full aero example — grouped by owning ns

`config/system.edn` (ONE manifest; keys grouped by owner via comments; NO
`#profile`). NOTE: every value below EQUALS its schema `:default`, so a real
manifest could omit ALL of them (the `default-value-transformer` fills them,
§2.0c) — it's shown FULLY-POPULATED here for visibility. A real cluster writes
only what DIFFERS from the defaults:

```clojure
{:seon.config/agent-context
 {;; seon.agent.ctx — composer
  :seon.agent.ctx/blocks                              :all
  :seon.agent.ctx/escape-clipping?                    true
  :seon.agent.ctx/soul                                :file
  :seon.agent.ctx/capabilities                        #{:grep}
  ;; seon.agent.ctx.namespaces
  :seon.agent.ctx.namespaces/include                  #{my.kb my.data my.ui my.tile
                                                        seon.agent.todo seon.agent.message
                                                        seon.agent.lifecycle}
  :seon.agent.ctx.namespaces/current-ns?              true
  :seon.agent.ctx.namespaces/view                     #{:source}
  :seon.agent.ctx.namespaces/current-ns-view          #{:source :tests}
  ;; my.skills
  :my.skills/load                                     #{:repl}
  ;; seon.ai — per-agent LLM (:inherit → global :seon.ai/config row)
  :seon.ai/agent-provider                             :inherit
  ;; seon.agent.ctx.transcript
  :seon.agent.ctx.transcript/turns-retained           8
  :seon.agent.ctx.transcript/result-decay
    [{:seon.agent.ctx.transcript/from-turn-offset 0 :seon.agent.ctx.transcript/token-cap 16384}
     {:seon.agent.ctx.transcript/from-turn-offset 2 :seon.agent.ctx.transcript/token-cap 1500}
     {:seon.agent.ctx.transcript/from-turn-offset 5 :seon.agent.ctx.transcript/token-cap 200}]
  ;; seon.eval
  :seon.eval/toolkit                                  #{my.ui my.data my.tile my.kb}
  :seon.eval/home-requires                            [[seon.agent.message :as message]
                                                       [seon.agent :as agent]
                                                       [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
                                                       [seon.schema :as schema]
                                                       [seon.db :as db]
                                                       [seon.agent.todo :as todo]]
  ;; seon.agent.fs
  :seon.agent.fs/roots                                []
  :seon.agent.fs/read-only?                           true
  ;; memory-surface block toggles (each in its block ns)
  :seon.agent.ctx.findings/enabled?                   true
  :seon.agent.ctx.warnings/enabled?                   true
  :seon.agent.ctx.inventory/enabled?                  false
  :seon.agent.ctx.relevant/enabled?                   false
  ;; seon.render.live-tile
  :seon.render.live-tile/enabled?                     true
  :seon.render.live-tile/content                      :none
  ;; seon.agent.run
  :seon.agent.run/default-turn-limit                  20
  ;; seon.client
  :seon.client/wake?                                  true}

 ;; the ROOT agent — SPARSE override, selected by IDENTITY (id "root"), NOT a :kind
 :seon.config/root-context
 {:seon.render.live-tile/content     seon.render.system/system-view
  :seon.agent.ctx.inventory/enabled? true}}
```

A lean cluster ships a shorter `:seon.agent.ctx.namespaces/include` /
`:my.skills/load #{}`, or `:seon.agent.ctx.namespaces/view #{:signatures}`. Every
unlisted key is filled from its schema `:default` by the
`default-value-transformer` (§2.0c) — the manifest need only carry what DIFFERS
from the declared defaults.

### 2.12 SKILLS unification — summary→expand IS already config-on-record

**Investigated (`src/my/skills.cljs`).** The owner suspected a duplicated
expand-mechanism. It is NOT duplicated — it is already the reactive-config-on-record
model, and it is the SAME shape as the namespaces summary-vs-full:

- **Summary (L0 catalog)** — `catalog-block` (my.skills 281) renders one `;`-line
  per skill (name + description + a DERIVED ●/○ loaded marker), always-on at
  priority 12 (cached prefix). Pure derivation over the `:my.skills/*` rows; marks
  `::loaded?` per agent.
- **Full (L2 body)** — `skill-block` renders the whole SKILL.md, present ONLY when
  a `:skill/<name>` block sits on the agent's `:seon.agent/ctx` (priority 30,
  volatile band).
- **The "expand state" is a DATOM, not a separate mechanism.** "Loaded" is DERIVED
  — `loaded-skill-names` (my.skills 176) queries the agent's `:skill/<name>` ctx
  blocks; there is **NO stored flag, NO atom, NO separate expand-state code path**.
  `(my.skills/load :x)` = `install!` a `:skill/x` block; `(unload :x)` = `remove!`
  it. It is one `:seon.agent/ctx` component datom — the same substrate as every
  other block.

**So it's already on the unified path** — nothing to de-duplicate. What v2 ADDS is
that the always-on expansion is a CONFIG KEY on the record (`:my.skills/load`,
§2.3): the boot seed transacts a `:skill/<name>` block for each named skill (the
config seeds the same datom the runtime `load` verb writes). Change
`:my.skills/load` (or transact a `:skill/<name>` block directly) → the next render
reacts, no manual state tweak. The agent reconfigures its own loadout by
transacting its own ctx datoms — identical to `namespaces/view` changing which
aspect renders.

**The deeper unification (owner's core aim).** Skills catalog-vs-body and
namespaces summary-vs-full are the SAME pattern: *a cheap always-on summary line +
a full body gated by a per-agent datom.* Both are `:seon.agent/ctx` blocks (or
derived from them); both react to a datom change; neither has a manual expand-state.
The config keys just seed the gating datoms. No new mechanism — the observation is
that the whole context (blocks, skills, namespace aspects, eval-result decay level)
is ONE reactive-derivation-from-the-agent's-record, and every config key is a dial
on that record. That is the point of the flat-keys-on-the-record design (§2.0, §3.0).

---

## 3. How `init-agent!` consumes it — REACTIVE config-on-record (the keystone)

### 3.0 The keystone: every renderer sources config from the agent's datoms

**This is the structural heart of v2.** The config dials are scalar attrs ON the
agent's record. **EVERY renderer reads its config from the agent's config datoms
AT RENDER TIME** — never from a hardcoded default (`default-seed-blocks`,
`default-namespaces-policy`), never from a boot-frozen value. So a single
`db/transact!` to a config attr → the agent's NEXT context AND the datastar UI
re-derive automatically (the reactive-context model). **No apply step.** The agent
can reconfigure ITSELF by transacting its own config attrs
(`(db/with-agent id (fn [] (db/transact! {:seon.db/tx-data [{:seon.agent/id id :seon.agent.ctx.namespaces/view #{:source :tests}}]})))`),
and its next turn renders under the new view.

This is why the keys are flat record attrs (§2.0), not a nested config blob: a
renderer reads ONE attr off the pulled agent entity, the same entity it already
pulls. The boot seed just writes the resolved defaults; from then on the datoms
are the source of truth, and editing a datom IS reconfiguring.

**The actual refactor — hardcoded-default reads that BECOME agent-datom reads:**

| Renderer / fn | Reads today (hardcoded/frozen) | Becomes (agent datom) |
|---------------|-------------------------------|------------------------|
| `default-seed-blocks` (ctx.cljs 1700) | the fixed block list + inline priorities | seeds from `:seon.agent.ctx/blocks` + `/block-priorities`; render reads the agent's own `:seon.agent/ctx` (already datom-backed) |
| `namespaces-block` / `render?` (ns.cljs 296/223) | `config/namespaces-policy` (a memoized manifest read) | the agent's `:seon.agent.ctx.namespaces/include` + `/current-ns?` + `/view` + `/current-ns-view` datoms |
| `render-namespace` detail (ctx.cljs 1531) | `:seon.render/detail :full` (fixed) | the ns's resolved aspect-set (`view`/`current-ns-view`/`view-overrides`) |
| `format-eval-row` (ctx.cljs 592) | fixed `result-body-render-cap` (16384) | the agent's `:seon.agent.ctx.transcript/result-decay` schedule × the result's age |
| `transcript-block` clip (transcript.cljs 544) | `:seon.render/clip :none` (frozen off) | `:seon.agent.ctx.transcript/tiers` + `/turns-retained` datoms |
| skill seed (config.cljs `resolve-loadout`) | `resolve-loadout` over the manifest | `:my.skills/load` datom (+ the always-on `:skill/<name>` blocks it seeds) |
| soul/agents file-blocks (ctx.cljs 1744) | hardcoded `soul-file-path` presence | `:seon.agent.ctx/soul` datom |
| clip gate (ctx.cljs 349 `clip-or-full`) | per-value cap default | `:seon.agent.ctx/escape-clipping?` datom → `:seon.render/full?` |
| run bounds (run.cljs 93) | the `default-turn-limit` const / `SEON_DEFAULT_TURN_LIMIT` | `:seon.agent.run/default-turn-limit` datom |
| LLM call (turn.cljs) | the global `:seon.ai/config` row | `effective-config-for id` = per-agent `:seon.ai/agent-*` over the global row |
| root canvas (client.cljs 2374 branch) | the hardcoded `(when (= "root" aid) …)` transact | `:seon.render.live-tile/content` seeded from `root-context` config |

The pattern is uniform: a renderer that reads a module-level const/frozen value
instead reads the attr off the agent entity it already has in hand. Where a
renderer today calls `config/namespaces-policy` (a manifest read), it instead
reads the agent's datoms — the manifest is consulted ONCE at seed time, not per
render.

### 3.1 Merge order — malli-native, at SEED time only

Two EXPLICIT layers, then malli fills the rest from the schema `:default`s
(decision 4 — there is NO separate code-defaults const):

```clojure
(defn resolve-agent-context [id override]
  (-> (merge (context-config-for id (load-manifest))   ; named-config (aero)
             override)                                  ; per-mint override
      ;; fill EVERY unspecified key from its schema :default (§2.0c). The
      ;; ::add-optional-keys is REQUIRED — our keys are all {:optional true}.
      (->> (m/decode :seon.config/agent-context))       ; via default-value-transformer
      ))
;; where the transformer is (mt/default-value-transformer {::mt/add-optional-keys true})
```

1. **named-config** — `:seon.config/agent-context` OR `:seon.config/root-context`
   from the aero manifest (`load-manifest`), selected by identity (§3.2).
2. **per-mint override** — an optional `:seon.agent.ctx/config` map on the
   `init-agent!` request (the `/agents/new` form or a spawn call can override any
   key for that one agent).
3. **schema `:default` fill** — the `default-value-transformer` decode step fills
   every key the two layers didn't set (§2.0c). NOT a merge layer the caller
   writes — the defaults live on the schemas, in their owning nses.

`merge` of layers 1-2 is key-level (each key wholly replaces, matching today's
`resolve-namespaces` semantics 223) — NOT deep. The final `m/decode` fills the
rest. `(config/resolve-agent-context id override)` returns the final validated map.

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
`(when (= "root" aid) …)` branch: root-context sets
`:seon.render.live-tile/content seon.render.system/system-view`, and `init-agent!`
seeds it like any other datom.

### 3.3 Where each key drives which mechanism (in `init-agent!`)

`init-agent!` gains a step 2.5 (between `setup-agent-ns!` and `boot!`/arm), OR
`seed-default-ctx!` is folded INTO `init-agent!` and reads the resolved config:

| Config key | Drives |
|-----------|--------|
| `:seon.eval/home-requires` | `setup-agent-ns!` (replaces the global `home-ns-require-specs` const). |
| `:seon.eval/toolkit` | which `my.*` nses are indexed-at-boot + rendered full (unifies client-require + `:always`). |
| `:seon.agent.ctx/blocks` + the `*/enabled?` toggles + `:seon.render.live-tile/enabled?` | which blocks seed-copy (filter by toggles). |
| `:seon.agent.ctx.namespaces/include` + `/current-ns?` + `/include-referred-local?` + `/third-party?` | which nses render (the renderer reads the agent's datoms, not `default-namespaces-policy`). |
| `:seon.agent.ctx.namespaces/view` + `/current-ns-view` + `/view-overrides` | which ASPECT (source/tests/signatures) each ns renders. |
| `:my.skills/load` + `/order` | the always-on `:skill/<name>` blocks (replaces `resolve-loadout`). |
| `:seon.agent.ctx/soul` / `/persona` | the soul/persona file-block. |
| `:seon.agent.ctx/escape-clipping?` | each block's `:seon.render/clip` → `full?`. |
| `:seon.agent.ctx.transcript/tiers` + `/turns-retained` | the transcript eviction bands. |
| `:seon.agent.ctx.transcript/result-decay` | the per-eval-result near-full→partial→clipped age schedule. |
| `:seon.render.live-tile/content` | the tile content seed (root → system-view; replaces the hardcoded branch). |
| `:seon.agent.run/default-turn-limit` / `/default-deadline-ms` | the run-bound seeds. |
| `:seon.agent.ctx/block-priorities` / `/cache-breakpoint` | block sort + cache-prefix breakpoint. |
| `:seon.agent.fs/roots` / `/read-only?` + `:seon.agent.ctx/capabilities` | the fs grant + capability gate (per-agent). |
| `:seon.ai/agent-*` | per-agent LLM resolution over the global `:seon.ai/config` row (`effective-config-for id`). |
| `:seon.client/wake?` + `:seon.agent.schedule/seed` + `:seon.agent.lifecycle/auto-terminate?` | wake-arm + cron seed + one-shot terminate. |
| `:seon.db/origin-scope` | (to-build) the `:seon.db/origin` slice the memory blocks read. |

The seed-copy (`install!`) still runs inside the new agent's `with-agent` scope,
exactly as today (ctx.cljs 1892-1894) — only its INPUT changes from
`(resolve-loadout …)` to `(resolve-agent-context id override)`. After the seed,
the datoms are the source of truth (§3.0) — the renderers read them, not the
manifest.

---

## 4. Migration / rip-out

### 4.1 Deleted (the "shitshow")

| Deleted | file:line | Replaced by |
|---------|-----------|-------------|
| hardcoded root branch | `seon.client` 2367-2379 | `root-context` config → `:seon.render.live-tile/content` |
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
| `SEON_RENDER_TRANSCRIPT_TOKEN_CAP` | `seon.config` 357-363 | `:seon.agent.ctx.transcript/tiers` (transcript eviction) |
| `SEON_RENDER_RESULT_CAP` (fixed eval-result cap) | `seon.config` 334-339 | `:seon.agent.ctx.transcript/result-decay` (age-keyed, §2.6) |
| `SEON_AI_MAX_RETRIES` env read in `call-llm!` | `seon.agent.turn` 344 | `:seon.ai/agent-max-retries` (per-agent, decision 6) |

(The other `SEON_RENDER_*_CAP` per-value display caps — message/eval/store-edn —
stay as their env defaults for now; v2 drops the whole-context `token-budget` and
the `block-caps` centralization key, per decision 7. Blocks render full
[`escape-clipping?` true]; growth is bounded by transcript `tiers` + eval
`result-decay`, both age-keyed, not a per-block cap map.)

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
| **#45** disable inventory | `:seon.agent.ctx.inventory/enabled?` (default false) | wire-existing (re-add block when true) |
| **#56 / #73** home-ns aliases real, not magic | `home-requires` per-agent (REAL requires in the ns form) | wire-existing (`home-ns-require-specs` → config value); the authored-ns rewrite (#73) is a SEPARATE eval-path fix, NOT this config — flag it stays Core's A2 |
| **#62** transcript tiers | `transcript/tiers` | to-build-PARTIAL (age-banding exists, OFF; this parameterizes + enables) |
| **#74** todo signature-trim | MOOT — signatures retired; `todo` is just a member of `namespaces/include` | n/a (drop from `include` to trim) |

| **#83** writes-tests / tests-in-context | `:seon.agent.ctx.namespaces/view :tests` aspect (un-elides deftests as a selectable aspect; current-ns-view includes tests) | to-build (tests-as-aspect) |

**Wire-existing (config just reshapes / toggles built machinery):** #42, #43,
#45, #56/#73-config-half, #74; plus block-priorities, cache-breakpoint, toolkit
unify, the findings/warnings/inventory/live-tile/relevant `enabled?` toggles,
default-turn/deadline, extra-blocks.
**Need mechanism built first:** #62 tier parameterization (partial); eval-result
`result-decay` (new — the age→cap axis, §2.6); the namespace VIEW model —
`:tests`/`:signatures` aspects (source is wire-existing); the 7 `:seon.ai/agent-*`
per-agent LLM keys (new resolver `effective-config-for`); per-agent
`:seon.agent.fs/roots` + `:seon.agent.ctx/capabilities` grant (today
process-global); `:seon.db/origin-scope` store slicing (new); the small toggles
`persona`/`cite-card?`/`auto-terminate?`/`:my.skills/order`/`:seon.client/wake?`/
`summary-head?`.

---

## 6. Open questions — the original three RESOLVED by owner decisions

The v1 OQs are now settled by the decision log
([[config-driven-agent-init-decisions-2026-07-01]]):

1. **`namespaces/render :full|:signature`** → **RESOLVED (decision 9):** DROPPED;
   replaced by the VIEW model — `:signatures` is one selectable aspect of
   `:seon.agent.ctx.namespaces/view`, not a whole knob.
2. **Per-agent model** → **RESOLVED (decision 6):** KEEP per-agent LLM; the
   `:seon.ai/agent-*` keys override the global `:seon.ai/config` row, `:inherit` by
   default. Falls out free as record attrs.
3. **`token-budget` semantics** → **RESOLVED (decision 7):** DROPPED; no
   whole-context budget yet. Blocks render full; growth is managed by transcript
   `tiers` + eval-result `result-decay` (age-keyed), not a whole-context cap.

### Remaining OWNER-flagged keys (keep vs drop — low-stakes, ship-or-defer)

These are registered but flagged in §2 as decide-later; none blocks the build:

- **`:seon.agent.ctx.namespaces/view-overrides`** — per-ns aspect override. Ship
  the two SCOPE defaults (`view` + `current-ns-view`) first; add per-ns only if a
  drive needs it. (Recommend defer.)
- **`:seon.agent.ctx/persona`** — inline persona string. Keep, or is the SOUL.md
  file-block (`:seon.agent.ctx/soul`) enough? (Recommend drop unless a
  config-defined-agent-without-a-file case is real.)
- **`:seon.agent.ctx.transcript/cite-card?`** — is a toggle even wanted, or is the
  fabrication guard unconditionally on? (Recommend keep-on, no toggle → drop the key.)
- **`:seon.agent.lifecycle/auto-terminate?`** — one-shot task agents, or is
  explicit `terminate` enough? (Recommend defer.)
- **`:seon.agent.schedule/seed`** — cron-as-config vs runtime-only. (Recommend
  defer — schedules are self-managed data.)
- **`:seon.db/origin-scope`** — per-agent store slicing (new mechanism; no origin
  scoping exists). (Recommend defer until a multi-agent memory-isolation need.)

### Exact-default confirmations (to pin at build)

- `:seon.agent.run/default-deadline-ms` default — the spec shows `900000`; CONFIRM
  the live `run.cljs` `default-deadline-ms` const value at build.
- `result-decay` levels (0→16384, 2→1500, 5→200) and `transcript/turns-retained`
  (8) are proposed starting points — gym-measure and tune (decision 12 parity: the
  ONLY intended behavior changes are `escape-clipping?` true + tiers on).

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
