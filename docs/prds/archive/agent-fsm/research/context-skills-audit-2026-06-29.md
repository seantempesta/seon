---
type: research
status: active
tags: [research, agent, context]
---

# Context + skills audit — toward kill-signatures / full-source / config-driven curation (2026-06-29)

> The owner SETTLED the rendering forks (see `coordination.md` "Needs (Core → UI):
> owner SETTLED the rendering forks (2026-06-29)"): KILL `:signature` rendering
> ENTIRELY, every rendered ns renders FULL real source, the rendered-namespaces
> set is FULLY config-driven (initial `seon.config` AND live DB datoms), and
> `db`/`todo`/toolkit aliases must be REAL `(:require …)` in the ns form (no
> magic). This doc is the named prereq: a full audit of the always-on agent-facing
> surface so the A1+A3+A2 implementation lands consistently. Every claim cites
> file:line against the live `feature/agent-fsm` tree.

## 1. TL;DR — the 5 highest-leverage findings

1. **The config curation seam is BUILT BUT DEAD.** `seon.config` already has
   `:seon.config/namespaces-spec` / `resolve-namespaces` / `namespaces-policy` /
   `default-namespaces-policy` (`config.cljs:80-260`). But `namespaces-policy` is
   referenced ONLY inside `config.cljs` — **neither the renderer
   (`namespaces.cljs`) nor the boot indexer (`client.cljs:1187`) consume it.** The
   live decision is still the three hardcoded sets (`full-source-whitelist`,
   `verb-signature-whitelist`, `canonical-full-my-ns`) via `body-detail`
   (`namespaces.cljs:121/145/168/240`). A3 is therefore mostly *wiring an
   already-built resolver + deleting the hardcoded sets*, plus simplifying the
   resolver's schema (it still encodes `:signature` + `:current-ns :signature`).

2. **There is NO live-DB override path for the rendered set — it needs NEW
   plumbing.** `namespaces-block` (`namespaces.cljs:419-512`) queries every
   `:seon.ns/name` row and applies the hardcoded `body-detail`. No datom says
   "render this ns." The owner's "live updates from the database (datoms)"
   requirement has zero existing mechanism. This is the single biggest design gap
   (§5). Everything else is delete/rewire.

3. **Signature machinery is pervasive — ~8 sites across 3 files**, and crucially
   even the `:full` and navigation paths secretly clip authored code:
   `render-namespace`'s DEFAULT detail is `:signature` (`ctx.cljs:1585`), and
   `fn-block-ai`'s `body?` gate renders a fn signature-only when its source > 240
   chars *even at `:full`* (`ctx.cljs:1279-1282`, `fn-source-inline-threshold`
   `:1159`). Killing signatures means flipping the navigation default to `:full`
   AND removing the size gate.

4. **One skill DIRECTLY contradicts the new model.**
   `seon-skills/data-oriented-clojure/SKILL.md:182-202` teaches "Home-ns aliases
   are HOME-ONLY — fully-qualify… **the fix is to qualify, not to re-`require`
   aliases into every `my.*` ns**." The new A2 model is the opposite: write the
   REAL `(:require …)` with short aliases into the authored ns form. This skill
   (and the `system-text` "BUILD YOUR ENVIRONMENT" example `(ns my.<domain>.<thing>)`
   with no requires, `ctx.cljs:1016-1023`) must be rewritten to model proper
   requires, or it will silently re-teach the rejected pattern.

5. **The require gap is real and narrow.** `setup-agent-ns!` writes the canonical
   requires into the HOME ns only (`eval.cljs:1289-1340` via `home-ns-form`
   `:1276` + `home-ns-require-specs` `:1204`). A NEW agent-authored `(ns my.foo)`
   gets whatever requires the agent typed — usually none — so `db/`/`todo/` are
   "not defined" there (#73/#56). A2 = rewrite a newly-authored ns form to merge
   the standard aliases the code uses, additively, into its REAL `(ns …)` form.

## 2. Full inventory — always-on context blocks + skills

### Always-on context blocks (`default-seed-blocks`, `ctx.cljs:1718-1800`)

Order = priority (cached prefix ≤ 20, volatile tail > 20). The provider-cache
boundary (`stable-boundary`, `ctx.cljs:1684`) sits after `:namespaces`.

| Priority | Block `:name` | Source fn | Renders | Clip/signature applied |
|---|---|---|---|---|
| 5 | `:soul` | `file-block` (SOUL.md) | file verbatim if present | none (whole file) |
| 8 | `:agents` | `file-block` (AGENTS.md) | file verbatim if present | none |
| 10 | `:shared-instructions` | `my.kb.shared/instructions-block` (`my/kb/shared.cljs:87`) | cluster KB instruction lines; **empty by default** (reactive, runtime appends) | none; vanishes when empty |
| 12 | `:skills-catalog` | `my.skills/catalog-block` (`my/skills.cljs:281`) | one name+desc line per loadable skill | none |
| 16 | `:skill/<name>` | `my.skills/skill-block` (seeded per `:default-load`) | a loaded skill BODY | none |
| **20** | **`:namespaces`** | **`seon.agent.ctx.namespaces/namespaces-block`** | **THE prompt body — curated ns source** | **HEAVY: signatures, member-doc-clip, fn-size gate, required-api signature dump — the whole A1 target** |
| 35 | `:live-tile` | `seon.agent.ctx.live-canvas/live-tile-block` | the agent's current canvas | (separate concern) |
| 40 | `:warnings` | `seon.agent.ctx.warnings/warnings-block` | current derived problems | (separate) |
| 45 | `:open-todos` | `seon.agent.todo.internal/open-todos-block` | open work items | none |
| 48 | `:relevant-source` | `seon.agent.ctx.relevant/relevant-source-block` (`relevant.cljs`) | KNN hits, **default-OFF (SEON_EMBED)** | `source-char-cap` 1500/hit, loud marker |
| 97 | `:findings` | `seon.agent.ctx.findings/findings-block` (`findings.cljs`) | top-10 recent stored claims + provenance | `content-char-cap` 220/row, read-back footer |
| 97 | `:inventory` | `seon.agent.ctx.inventory/inventory-block` (`inventory.cljs`) | per-attr-ns counts + sample lookup values | `value-token-char-cap` 48 (blob→count-only) |
| 100 | `:transcript` | `seon.agent.ctx.transcript/transcript-block` | the live REPL transcript | eval/result/message caps (below) |

The hardcoded `system-text` (`ctx.cljs:881-1140`) rides the LLM **system role**
(not a ctx block), byte-stable. It already teaches `render-namespace` as the
navigate verb (`:1012`) and "YOUR OWN namespace renders in FULL … your my.* world
and set-up tools (todo)" (`:1001-1023`). It does NOT mention signatures (good) but
its "BUILD YOUR ENVIRONMENT" example shows `(ns my.<domain>.<thing>)` with **no
requires** (`:1016-1023`) — an A2 example-fix site.

### Skills corpus (`seon-skills/*/SKILL.md`)

Six agent skills: `clojurescript`, `data-modeling`, `data-oriented-clojure`,
`datahike`, `repl`, `ui-canvas`. Default-loaded = `config/system.edn`
`:seon.config/loadouts` → `:default-load [:repl]` (only `repl` body always-on;
`:minimal` profile loads none). Standing law: **agents rarely load skills → the
always-on base is what matters.**

| Skill | Default-loaded? | Matches new target? |
|---|---|---|
| `repl` | YES (`:default-load [:repl]`) | OK (REPL mechanics) — verify no signature/alias teaching |
| `data-oriented-clojure` | no | **CONTRADICTS** — L182-202 teaches "qualify, not re-require" (rejected). Also L204-215 HAS the `deftest` worked example (relevant to writes-tests). |
| `ui-canvas` | no | borderline — L82-85 "Fully-qualify inside a `my.*` ns" (the floor, compatible) but should also mention real requires now |
| `data-modeling` | no | OK (schema design); uses `(require '[seon.schema :as schema])` top-level examples — fine |
| `datahike` | no | OK; `(require '[seon.db :as db] …)` examples — fine |
| `clojurescript` | no | OK (async/self-host) |

## 3. Every truncation / signature / clip site — classified

Owner rule: **REMOVE** clips of AUTHORED CODE/CONTEXT (namespace source,
docstrings, schema/spec source); **KEEP** (with a loud marker) genuinely-unbounded
EXTERNAL dumps (eval result bodies, inbound message payloads, KNN hit bodies);
**CONVERT** = the path survives but must emit full source instead of a clip.

### Signature sites — all REMOVE (kill `:signature` entirely)

| File:line | What | Action |
|---|---|---|
| `namespaces.cljs:145-166` `verb-signature-whitelist` | seon.* verb nses → signatures | **REMOVE** (the whole def) |
| `namespaces.cljs:240-264` `body-detail` `:signature` branch | "every OTHER my.* ns → :signature" | **REMOVE branch** → every selected ns `:full` |
| `namespaces.cljs:336-417` `required-api-header` / `required-api-rows` / `required-api-blocks` | renders required deps as a CAPPED signature manifest | **REMOVE** (replaced by full-source curation of required nses, §4 A1) |
| `namespaces.cljs:488-498` `sig-rows` / `sig-blocks` | the verb-sig prefix | **REMOVE** |
| `ctx.cljs:1241-1288` `fn-block-ai` `:signature` path + `body?` size gate | member render signature-only / size-gated | **CONVERT** → always emit full source (drop the gate) |
| `ctx.cljs:1338-1381` `render-one-ns-ai` `:signature` branch | `(signatures)` manifest view | **REMOVE branch** |
| `ctx.cljs:1481` `(register! :seon.render/detail [:enum :full :signature])` | the detail enum | **CONVERT** → drop `:signature` (keep `:full`; `:full-body` is internal to render-member) |
| `ctx.cljs:1585` `render-namespace` `:or {… detail :signature}` | **navigation verb DEFAULTS to signature** | **CONVERT** → default `:full` (the owner's navigate-to-full verb) |
| `ctx.cljs:1555/1563-1564/1598` docstrings + signature default plumbing | | update to `:full` default |
| `config.cljs:86` `:seon.config/current-ns [:enum :full :signature :off]` | | **CONVERT** → `[:enum :full :off]` |
| `config.cljs:88-93` `:seon.config/signature-entry` + `:95-102` the `:signature` key in `namespaces-spec` + `:107-111` policy + `:203-244` default+resolver `:signature` handling | the whole signature half of the config schema | **REMOVE** the `:signature` machinery; keep `:always` + `:current-ns` |

### Authored-CODE clips — REMOVE / CONVERT

| File:line | What it clips | Authored? | Action |
|---|---|---|---|
| `ctx.cljs:1159` `fn-source-inline-threshold` (240) | fn source shown signature-only if > 240 chars, **even at :full** | YES (agent/my.* fn body) | **REMOVE** the threshold gate (full source always) |
| `ctx.cljs:1165` `member-doc-clip` (280) | docstring per member | YES (docstrings render into context; flagged in `namespaces-trim-validation` as dropping the my.data worked chain) | **REMOVE** the clip |
| `ctx.cljs:1169` `clip` helper | used for doc/spec(80)/schema-error(80)/schema-source(200)/test-source | mixed | **CONVERT** the per-member fallback (no-source nses) to render full; the spec/schema-error clips live only in the dying signature path |
| `ctx.cljs:1298` `schema-block-ai` `(clip … 200)` | schema malli form/source | YES | **CONVERT** → full (per-member path for no-source nses) |
| `ctx.cljs:1303-1313` `test-block-ai` `(clip … fn-source-inline-threshold)` | deftest source | YES | **CONVERT** → full (also relevant to A4: keep deftest visible) |

> NOTE: for a FULL-SOURCE ns the renderer uses the whole-file `src` path
> (`ctx.cljs:1398-1412`, unclipped), so these per-member clips bite only
> runtime-created nses with no stored `:seon.ns/source` and the (dying) signature
> path. After killing signatures, removing the gates makes every no-source ns
> render its members in full too — consistent.

### External/unbounded dumps — KEEP (loud marker, with a re-reference escape)

| File:line | What | Why keep |
|---|---|---|
| `ctx.cljs:341-356` `truncate-edn` (2KB) | pr-str of an eval-log display value | external value; loud marker present |
| `ctx.cljs:382-430` `eval-render-cap` / `cap-result` | echoed eval SOURCE + STDOUT | transcript runtime output; loud marker; config knob `SEON_RENDER_EVAL_CAP` |
| `ctx.cljs:392-482` `result-body-render-cap` / `cap-result-body` | eval RESULT body | external data; loud marker + `result/<id>` escape |
| `ctx.cljs:432-439` `message-render-cap` (4000) | inbound message content | could be a huge paste; full in db |
| `relevant.cljs:37/51-58` `source-char-cap` (1500) | KNN hit body | external; default-OFF; loud marker + pull escape |
| `inventory.cljs:28-34` `value-token-char-cap` (48) | sample lookup-value (blob→count-only) | NOT code — a discovery hint; the cap prevents dumping blobs AS filter keys |

### Stored-content clip — KEEP but FLAG for owner

| File:line | What | Note |
|---|---|---|
| `findings.cljs:35-40/94-102` `content-char-cap` (220) | stored finding claim text, top-10 rows | This clips stored CONTENT (knowledge), not code. It is a bounded SALIENCE surface with a read-back footer + `pull '[*]` escape. Defensible as a discovery summary, but it IS content-clipping the owner's rule touches. **Owner decision: leave as a bounded salience surface, or raise the cap / show full?** |

## 4. The consistent change-list to reach the target

### A1 — kill `:signature`, every selected ns renders `:full`

1. Delete `verb-signature-whitelist` (`namespaces.cljs:145`), `canonical-full-my-ns`
   (`:168`), `full-source-whitelist` (`:121`) — they become config (A3).
2. `body-detail` (`namespaces.cljs:240`) → returns `:full` for any ns in the
   resolved render-set, `nil` otherwise. No `:signature`.
3. Delete `required-api-rows` / `required-api-blocks` / `required-api-header`
   (`:336-417`) and the `sig-rows`/`sig-blocks` machinery in `namespaces-block`
   (`:488-498`). Required-non-third-party nses instead enter the FULL render-set
   (resolve via `:seon.ns/requires` of `cur-ns` — the query already exists at
   `:357-376`, repurpose it to ADD to the full set, not to a signature dump).
4. `ctx.cljs`: drop the `:signature` branch of `render-one-ns-ai` (`:1365-1381`);
   in `fn-block-ai` (`:1256-1288`) remove the `body?` size gate + `member-doc-clip`
   so members render full source + full docstring; `schema-block-ai`/`test-block-ai`
   render uncliped. Change `:seon.render/detail` enum (`:1481`) to drop `:signature`.
5. **Flip the navigate verb to full:** `render-namespace` default detail
   `:signature`→`:full` (`ctx.cljs:1585`, + docstrings `:1555/1563`). Keep
   `(render-namespace {:seon.ns/name … :seon.render/detail :full})` as the
   discoverable "change to a namespace" affordance (already taught in
   `system-text:1012`).
6. Selection set per owner = `cur-ns ∪ my.* toolkit ∪ required-non-third-party
   ∪ config :always` — long tail absent until navigated.

### A3 — config-driven curation (initial `seon.config` + live DB)

**(a) Initial config — wire the already-built resolver.** The seam exists
(`config.cljs:80-260`); it is just not consumed. Steps:

- Simplify the schema: drop `:seon.config/signature` + `:seon.config/signature-entry`
  + `current-ns :signature` enum (`config.cljs:86/88-102/107-111`); keep
  `:seon.config/always [:vector :symbol]` + `:seon.config/current-ns [:enum :full :off]`.
- `default-namespaces-policy` (`config.cljs:203-213`) → `{:always '[my.kb my.data
  my.ui my.canvas seon.agent.todo seon.agent.message seon.agent.lifecycle seon.agent]
  :current-ns :full}` (the former signature nses move into `:always` as full, or
  stay out and surface via requires).
- **CONSUME it:** `namespaces.cljs` `body-detail` reads
  `(config/namespaces-policy)` `:always` set; `client.cljs:1187`
  (`full-source-ns?` decision) reads the same policy so the boot indexer stores
  full source for exactly the rendered set. This is the "one policy, two readers"
  contract `namespaces-policy`'s docstring already promises (`config.cljs:248-253`)
  but nobody honors yet.
- Mirror the default list verbatim in `config/system.edn` (currently has NO
  `:seon.config/namespaces` section — only `:loadouts`/`:skills`) for visibility;
  a lean cluster overrides `:always` with a short list (the curation lever).
- **Skills policy (retire the rejected profile sets):** `config/system.edn` uses
  `:loadouts #profile {:default … :minimal []}` with `:default-load`. The
  `:seon.config/skills-spec` already has the owner-approved `:load` key
  (`config.cljs:72`: `:all` | explicit `[:repl …]` | `[]`). Migrate
  `config/system.edn` from the `#profile` loadout sets to an explicit
  `:seon.config/skills {:load [:repl]}` (or `:all`), and make `my.skills` honor
  `:load` instead of `:default-load`. (Per standing law, skills are rarely
  loaded — so this knob is low-stakes; the always-on base matters more.)

**(b) Live DB override — NEW plumbing (design).** No mechanism exists today. Two
clean options, both reactive (derive-don't-store-compatible — the set is a query,
not a mutated registry):

- **Option 1 (recommended) — a render-set attribute on the agent.** Register
  `:seon.agent.ctx/render-namespaces [:vector :keyword]` (or a cardinality-many
  set) on the agent entity. `namespaces-block` unions the config `:always` set
  with `(db/pull … :seon.agent.ctx/render-namespaces)` for `cur-id`. An agent (or
  a human, or another agent) transacts a ns keyword onto its own row →
  next render that ns appears full; retract → it vanishes. Pure reactive, scoped
  per-agent, no new event system. This is the literal "live updates from the
  database (datoms)" the owner named.
- **Option 2 — a marker attr on the ns row.** Register `:seon.ns/render? :boolean`
  on `:seon.ns` entities; the query in `namespaces-block` adds any ns with
  `:seon.ns/render? true`. Global (all agents) rather than per-agent. Simpler but
  coarser.

Prefer Option 1 (per-agent, matches the install!/remove! per-agent override
philosophy at `ctx.cljs:1834`). Either way: `namespaces-block`'s row query
(`namespaces.cljs:464-471`) gains a union with the DB-driven set, and
`body-detail` returns `:full` for members of `config-always ∪ db-render-set ∪
cur-ns ∪ required`. Self-healing: drop the datom → the ns leaves the set → it
stops rendering.

### A2 — real `(:require …)` in the authored ns form (#73/#56)

- The home ns already carries the canonical requires (`home-ns-require-specs`
  `eval.cljs:1204` → `home-ns-form` `:1276` → `setup-agent-ns!` `:1289`). REUSE
  this spec list as the "standard alias set."
- **Mechanism:** when the agent evals an `(ns my.foo …)` form for a NEW (or
  existing) authored ns, rewrite that form to MERGE — additively, never clobbering
  the agent's own requires — the canonical `[seon.db :as db]` / `[seon.agent.todo
  :as todo]` / `[seon.agent.message :as message]` / `[seon.schema :as schema]` /
  `[seon.agent :as agent]` / the `my.*` toolkit aliases the code uses (or all of
  them, since the owner wants the short forms to "just work"). Persist the
  rewritten form as `:seon.ns/source` and eval it. This rides the existing
  detect-and-tee path (`eval.cljs:1405+`) and `parse-require-syms`
  (`ctx.cljs:1175`). The requires are REAL and inspectable — the alias resolves
  because it is actually `:require`d, not injected. (Lifecycle `:refer` verbs
  `wait`/`complete`/… — owner flagged as a separate micro-question; default leave
  home-only.)
- **Keep** the existing error-hint nudge `home-ns-alias-hint` (`eval.cljs:1249`)
  as a fallback.
- **Update every rendered EXAMPLE to model proper requires / full paths:**
  - `system-text` "BUILD YOUR ENVIRONMENT" (`ctx.cljs:1016-1023`): show
    `(ns my.<domain>.<thing> (:require [seon.db :as db] [seon.schema :as schema]))`.
  - `seon-skills/data-oriented-clojure/SKILL.md:182-202`: **rewrite** the
    "qualify, not re-require" section to "write the real require + short alias"
    (this is the load-bearing contradiction).
  - `seon-skills/ui-canvas/SKILL.md:82-85`: add the real-require option
    alongside the full-qualify floor.
  - `my.kb` recipes (the always-full DB manual) and `my.data`/`my.ui`/`my.canvas`
    worked examples: prefer full namespace paths in shown code per the owner's
    "full paths in examples" rule.

### A4 (note only — gym concern, not context) — writes-tests is ENCOURAGED, not REQUIRED

Per the settled decision, Core does NOT hoist a "must test" cue; U demotes the gym
`:wrote-a-test-for-the-fn` predicate from a gate to a soft axis. Relevant context
fact: the `deftest` worked example already exists in
`data-oriented-clojure/SKILL.md:204-215`, and `test-ns-name?`
(`namespaces.cljs:87-97`) still elides `*-test` nses from the prompt — consistent
with "encouraged not required" (no need to surface tests in the always-on base).
No always-on change required.

## 5. Risks / unknowns / things I could NOT verify

1. **The live-DB render-set has NO existing mechanism — it is net-new plumbing.**
   The §4 A3(b) designs are proposals, not existing seams. This is the only part of
   the change that isn't "delete + rewire an already-built resolver." Risk: getting
   the reactive union right in `namespaces-block` without busting the cache-prefix
   stability (the `:namespaces` block is in the CACHED prefix ≤ priority 20, so a
   per-turn-changing render-set would bust the provider cache every time the set
   changes — acceptable when an agent deliberately navigates, but a churning
   DB-driven set would hurt cache hit-rate). Recommend: DB-driven additions are
   fine (rare, deliberate); just document the cache trade.

2. **Does a REAL `(:require [seon.db :as db])` in a self-host agent ns reliably
   resolve the alias? — PARTIALLY VERIFIED, needs a live check.** The home ns proves
   `:as` aliases work in self-host (the agent uses `db/`/`todo/` in its home ns
   today). `seed-toolkit-refers!` (`eval.cljs:448`) shows `:refer` needs seeded
   analyzer `:defs` but **`:as` aliases do NOT validate members at parse time**
   (stated `eval.cljs:443-445`), so an `:as` require of a host-bundled ns should
   analyze cleanly in any new ns. UNVERIFIED: whether a freshly-authored `(ns
   my.foo (:require [seon.db :as db]))` evaluated via the agent eval path resolves
   `db/query` at RUNTIME (the ns object must be on globalThis — it is, for
   host-bundled seon nses). **Recommend a 30-second live REPL check** before
   building A2: mint an agent, eval `(ns my.t (:require [seon.db :as db]))` then
   `(db/query …)` in `my.t`, confirm it resolves. Reference: `reference-code/clojurescript/`
   self-host + `clojurescript` skill.

3. **`namespaces-policy` memoization** (`config.cljs:246-260`) is keyed on
   `[SEON_CONFIG SEON_PROFILE]`. If the live-DB render-set (A3b) is layered on top,
   it must NOT be folded into this memoized value (it changes per-tx, per-agent) —
   keep the config policy memoized and union the DB set fresh each render.

4. **Skill silent-contradiction sweep.** Confirmed contradiction:
   `data-oriented-clojure/SKILL.md:182-202`. Lower-risk: `ui-canvas/SKILL.md:82`
   (full-qualify floor — compatible but incomplete under the new model). The
   `repl` skill (the only default-loaded one) was not fully read for alias/signature
   teaching — **recommend a quick grep of `seon-skills/repl/SKILL.md`** for any
   signature/alias guidance before shipping (it is the one body every agent sees).

5. **`config/system.edn` has no `:seon.config/namespaces` section at all** — the
   default currently lives only in `config.cljs:203-213` `default-namespaces-policy`.
   A1+A3 must add the section to the manifest for the curation lever to be visible
   and overridable, AND keep `default-namespaces-policy` as the absent-config
   fallback (byte-identical boot contract, `config.cljs:204-209`).

6. **`fn-source-inline-threshold` removal interacts with no-source nses.** Today a
   runtime-created ns with no stored `:seon.ns/source` renders its members via
   `fn-block-ai`; removing the size gate makes large agent fns render full there —
   intended, but verify it doesn't blow a no-source ns with many large fns (the
   curation set bounds WHICH nses render, so a non-selected no-source ns won't
   render at all — low risk).

## Files / entry points

- Renderer + selection: `src/seon/agent/ctx/namespaces.cljs` (hardcoded sets
  `:121/:145/:168`, `body-detail :240`, required-api `:336-417`, `namespaces-block
  :419-512`).
- Render engine: `src/seon/agent/ctx.cljs` (`fn-block-ai :1241`,
  `render-one-ns-ai :1338`, `render-namespace :1496+` default-detail `:1585`,
  clips `:341/:382/:392/:432/:1159/:1165/:1169/:1298`, `system-text :881-1140`,
  `default-seed-blocks :1718`).
- Config seam (BUILT, DEAD): `src/seon/config.cljs:80-260`; manifest
  `config/system.edn`.
- Require setup: `src/seon/eval.cljs` (`home-ns-require-specs :1204`,
  `home-ns-form :1276`, `setup-agent-ns! :1289`, `seed-toolkit-refers! :448`).
- Boot indexer: `src/seon/client.cljs:1164-1192` (`ns-row` → `full-source-ns?`).
- Other blocks: `src/seon/agent/ctx/{inventory,findings,relevant}.cljs`;
  `src/my/{skills,kb/shared}.cljs`.
- Skills: `seon-skills/*/SKILL.md` (contradiction at `data-oriented-clojure:182-202`).
- Settled decision: `coordination.md` "owner SETTLED the rendering forks (2026-06-29)";
  superseded experiment framing in `core-handoff-2026-06-29.md` A1.
