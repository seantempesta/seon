---
type: research
status: active
tags: [research, architecture]
---

# Architecture-docs currency audit — the idealized-system set vs the 2026-07-11 cutover

## TL;DR

The architecture set is in good shape: the recently-touched docs
(`observability.md`, `context.md`, `agent-runtime.md`, `laws.md`) already
carry the fault workflow, the `:batch`/`:stream` datom, config-through-DB, and
the complete-gate. **No doc is wholesale MISLEADING.** The staleness that
exists is localized and clusters around exactly two arcs the docs have NOT
caught up to: (1) the **skills-system retirement** (still documented as a live,
first-class target in `toolkit.md`, `data-model.md`, and `ui.md`), and (2) the
**config→DB / system-text-as-DB-state** migration (the `data-model.md` config
section and the `architecture.md` glossary predate it and one of them actively
contradicts `context.md`). A third, smaller gap: `agent-runtime.md` never
learned the two REPL modes that `context.md` already documents.

The recurring pattern is the CLAUDE.md-audit pattern repeated: the docs updated
most recently for a specific unit are current; the docs that describe a
*mechanism from the outside* (glossary, config schema, toolkit catalog) lag
the mechanism's redesign. Two genuine cross-doc contradictions were found (both
about system-text; see units below).

| doc | rating | worst stale claim |
|---|---|---|
| `architecture.md` | **MINOR DRIFT** | Glossary: system-text is "a code const — not a block, not per-request overridable" — FALSE; it is the `:seon.config/system-text` DB datom with a per-request override (`ai.cljs:653`). Directly contradicts `context.md`. |
| `context.md` | **MINOR DRIFT** | Order band 5 "predicted relevance, last" is presented as an established band; the rebuild demoted relevant-source to pull-first/"on the shelf" (context-rebuild.md). Also describes a render-fn's OUTPUT twin as `:seon.render/html` where the code keys off `:seon.render/hiccup`. |
| `data-model.md` | **MINOR DRIFT (two MISLEADING sections)** | §5.6 config-manifest schema is materially stale (missing system-text/repl-mode/on-core-error/web/spawn-depth-cap/watchdog/agent-context/root-context; no mention of the `:seon.config` singleton or config-through-DB). §5.5 my.skills documents the retiring skills system as target. |
| `agent-runtime.md` | **MINOR DRIFT** | The work-quantity bound section knows only turn-count; never mentions the `:stream` form-denominated bound (`run/default-form-limit` 60, `derive/run-form-count`). Also: "AGENTS.md … a reactive file-block re-read every render" contradicts the rebuild's file-block retirement. |
| `toolkit.md` | **MINOR DRIFT** | §`my.skills` (lines 489–519) documents load/unload + catalog-block + skill-block + `SEON_SKILLS_DIR` as a live target member; the skills SYSTEM is retiring (dissolves into cards + state-gated teaching + pull refs). |
| `ui.md` | **CURRENT** | Only the skills-as-block example (lines 71–73, 326, 383) references the retiring `my.skills` facade; the mechanism it illustrates (`load` = `install!`) is still valid. No material drift. |
| `observability.md` | **CURRENT** | Only: line 44 lists `:relevant-source` embedding-hit ids as a recorded volatile (now-shelved block); the "Build path" para (236–243) is a dated 2026-07-02 status with some "remaining gaps" since shipped. Both cosmetic. |
| `laws.md` | **CURRENT** | None. The "agents rarely load skills; hoist skill guidance into always-on" law actually PRE-endorses the skills retirement. |
| `library-grounding.md` | **CURRENT** | None material; source-line read-map is durable. The "Core-lane phase" framing is a slightly dated migration lens but the `reference-code/…:LINE` anchors hold. |
| `decisions/` (ADRs 001–007) | **CURRENT** | Maintained: 005-flow-adoption carries a "SUPERSEDED — the pod is core.async-free" banner; 007-instrumentation carries a 2026-07-05 revision banner. No action. |

Verified in code (not trusted from the task brief): `:seon.config/system-text`
is a datom seeded at boot and `effective-system-prompt` is a request-override →
datom → shipped-default chain (`ai.cljs:653`, `config.cljs:423/491/703`); the
real `:seon.config/manifest` (config.cljs:491) carries repl-mode, system-text,
on-core-error, web, spawn-depth-cap, watchdog, agent-context, root-context;
`default-repl-mode` + `default-form-limit` (=60) exist; the render-fn twin
detection keys off `#{:seon.render/ai :seon.render/hiccup}`
(render_fns.cljs:55) while the block/pin ATTR is `:seon.render/html`
(render.cljs:91) — both are live, in different roles.

---

## Per-doc detail

### `architecture.md` — MINOR DRIFT

The map is overwhelmingly durable target-writing and mostly current. The
deployment-topology section (the read-only replica / trailing applier /
compressed tx-log bootstrap, lines 33–51, 180–220) describes the ENDGAME, not
the current pod (which forwards writes over a UDS to wire-server and reads local
lazy db values) — but the doc is explicitly "Target design," so this is by
design, not drift. Real issues:

- **Glossary, system-text (lines 126–128) — STALE + a cross-doc contradiction.**
  "the assembled context … prefixed by a fixed system role
  (`seon.agent.ctx/system-text`, a code const — not a block, not per-request
  overridable)." Reality (`ai.cljs:653`, `config.cljs`): the system prompt is
  `effective-system-prompt` = request `:seon.ai/system-prompt` override → the
  `:seon.config/system-text` DATOM on the singleton → the shipped
  `seon.agent.ctx/system-text` default. It IS per-request overridable and IS DB
  state. `context.md` §"The system prompt itself is DB state" (lines 396–406)
  describes this correctly — so architecture.md's glossary directly contradicts
  its own sibling doc. This is the highest-value fix in the whole set.
- **Core idea #3 + render-fn twins — key imprecision (line 75).** "A `defn`
  returning `:seon.render/ai` and/or `:seon.render/html` is a block and/or
  tile." A render fn's OUTPUT twin is detected on `:seon.render/hiccup`
  (render_fns.cljs:55), not `:seon.render/html` (which is the stored block/pin
  attr). Minor but it propagates into context.md (same imprecision).
- **Pointer to [[context]] names "the affordance tail" (line 380).** context.md
  no longer has an "affordance tail"; its band-5 is "predicted relevance," and
  the rebuild has demoted even that to pull-first. Cosmetic pointer drift.

### `context.md` — MINOR DRIFT

The best-maintained architecture doc for this arc: it fully carries the
feels-stateful completeness model, the `:batch`/`:stream` REPL-mode datom (with
the per-model default and colocated grammar), config-through-DB, and
system-text-as-DB-state. Issues are narrow:

- **Order band 5 "predicted relevance, last" (lines 319–324) — target drift.**
  Presented as an established fifth band ("the only recompute-every-step
  region … embedding neighbors"). The rebuild (context-rebuild.md, "Deliberately
  NOT blocks") demoted relevant-source/predicted-relevance to **pull, not push**
  — "a search the agent CALLS, promoted to a pushed block only if a drive proves
  the need." The target block set no longer guarantees this band. The doc should
  reflect it as conditional/pull-first, not a standing order position. (The
  running tree confirms: `ctx.cljs:42` still defines the `:relevant-source`
  block code but system.edn does not wire it.)
- **Render-fn output twin key (lines 180, 201).** "`{:seon.render/html …}` → a
  tile" and "the block's two renders (`:seon.render/ai` / `:seon.render/html`),
  now emitted by any in-scope `defn`." An in-scope defn emits
  `:seon.render/ai` / `:seon.render/hiccup` (render_fns.cljs:55, inside the
  `:seon.render/html-response` envelope); `:seon.render/html` is the stored
  block ATTR, not the fn-output twin. Same imprecision as architecture.md.
- **Compound (vocab + content) at line 403:** "`config/minimal.edn` … the
  rung-0 world the capability ladder measures from." "rung-0" is retired
  vocabulary (should be the `repl` milestone) AND minimal.edn's role shifted at
  cutover (it now inherits the graduated v3.1 system-text as one source rather
  than carrying its own ~20-line prompt inline). Flagging only because content
  and vocab compound here — leave the pure-vocab sweep to the concurrent agent.

### `data-model.md` — MINOR DRIFT (two MISLEADING sections)

The bulk (the three relationship kinds, the per-entity tables §4.1–4.9, the
kind-by-presence rule, the `:seon/error` model §6, the warnings surface §7) is
excellent and current. Two sections are stale:

- **§5.6 config manifest (lines 668–708) — MISLEADING.** The shown
  `:seon.config/manifest` schema (696–701) carries only `skills` / `loadouts` /
  `routes`. The REAL manifest (config.cljs:491) also carries `repl-mode`,
  `system-text`, `on-core-error`, `web`, `spawn-depth-cap`, `watchdog`,
  `agent-context`, `root-context`. The section still frames config as "a pure
  OPTIONAL override … read by `seon.config`" with **zero** mention of the
  config→DB migration: the `:seon.config` singleton (`[:seon.config/id
  "cluster"]`), `resolve-config-singleton`, runtime-reads-the-db via
  `config-view`, or replay-visible/live-tunable dials. context.md §"Config-through-DB"
  (lines 370–394) has all of this — data-model.md is the schema-of-record and
  should carry the schema, so this is where the gap bites hardest. Also names
  `SEON_PROFILE` `#profile` as a live variant mechanism (line 671) —
  `SEON_PROFILE` is inert per the standing note.
- **§5.5 my.skills (lines 628–666) — MISLEADING as target.** Documents the full
  skills schema (`:my.skills/name`/`description`/`body`), the `SEON_SKILLS_DIR`
  corpus scan, and loaded-skill-as-block as the target design. Per
  context-rebuild.md the skills SYSTEM retires and "dissolves into cards +
  state-gated teaching + pull references." The schema still exists in code, so
  this isn't wrong-about-today — but as the *idealized* system it describes a
  superseded target.
- **§5.3 my.plan roll-up (lines 592–600) — assertion the known bug breaks (note,
  don't fix here).** "A parent is `:done` when every descendant is `:done` …
  the graph is walked by reverse lookups (`:my.plan/_parent`)." The
  datahike-CLJS recursive-rule executor is broken past depth 1 on the pod
  (context-rebuild.md open defects), so descendant roll-ups truncate at depth 1
  in real prompts. Per the audit frame this is a code bug being fixed in
  parallel; the doc's claim is the TARGET-correct behavior. Flag only: when the
  fix lands, this section is the one to re-verify; consider a temporary
  "KNOWN LIMITATION" footnote if the fix slips.

### `agent-runtime.md` — MINOR DRIFT

Very current on the hard parts: complete-gate-backed-by-real-green-test,
durable-result + outcome-routing, heartbeat watchdog + `:crashed`,
schedule-wake circuit breaker, the fault dial, the isolation tiers, and the
real-REPL movement semantics (§"REPL forms — namespaces are places"). Gaps:

- **The two REPL modes / form-denominated work bound is absent.** §"The two
  bounds, the lease, the heartbeat" (lines 71–94) defines the work-quantity
  bound purely as `default-turn-limit + inbound-message-count`, and "the current
  turn is a derived count of `:seon.agent.turn/run` datoms." Under `:stream` the
  work bound is **form-denominated** (`derive/run-form-count`,
  `run/default-form-limit` 60 — verified in code); context.md carries this
  (lines 154–159) but agent-runtime.md, the runtime doc-of-record, does not. The
  `:batch`/`:stream` mode itself is never named here either. The task brief
  explicitly expected this doc to carry the modes.
- **AGENTS.md as a live reactive file-block (lines 412–413).** "SOUL.md /
  AGENTS.md are NOT seed steps — they are reactive `file-block`s re-read every
  render." Per the cutover the identity file-blocks (`file-block`/`-ai`/`-html`,
  `config/identity-file-blocks`) are DEPRECATED and OUT of the running tree;
  AGENTS.md's content is being MINED per-line into system-text / block teaching,
  and `soul` returns as a milestone (DB state, possibly inside system-text), not
  a re-read file. The claim describes a mechanism the rebuild is retiring.

### `toolkit.md` — MINOR DRIFT

The `my.*` catalog, the two-tier floor/toolkit split, the four shared shapes,
and every other tool entry (files/search/shell/web/plan/test/kb/code/schedule/
recall/tile/blob) are current and match the code surface. One stale entry:

- **§`my.skills` (lines 489–519) — MISLEADING as target.** A full first-class
  catalog entry: `load`/`unload`/`list`, the always-on `catalog-block`
  (priority 12), the loaded `skill-block`, the `SEON_SKILLS_DIR` scan, the
  `default-load` seed. This is the skills SYSTEM the rebuild retires. Note the
  internal inconsistency: the doc's own opening namespace list (line 14–15) does
  NOT include `my.skills`, yet §`my.skills` gives it a full entry — the retirement
  is half-reflected already.

### `ui.md` — CURRENT

Matches the code and the CURRENT-rated `src/seon/web/CLAUDE.md`: reitit-from-
datoms, the gzip whole-element morph, slots/layouts, the canvas =
last-updated-tile (derived, pin to override), `system-header = f(db)`, the two
error seams, the acme total-override table. Uses `:seon.render/html` correctly
as the block/pin ATTR throughout. Only soft spot: the "Skills are blocks"
example (lines 71–73), the override-table "seed/skill loadout" row (326), and
the [[loadable-skills]] pointer (383) reference the retiring `my.skills` facade
— but they illustrate the still-valid `install!`/block mechanism, so this is a
low-priority ride-along with the skills-retirement sweep, not independent drift.

### `observability.md`, `laws.md`, `library-grounding.md`, `decisions/` — CURRENT

- **observability.md**: the turn record, blob store, `inspect/turn`/`turn-diff`,
  the full error-recording + fault-dial + `errors`/`error`/`repro` + `cluster
  fork` triage chain, `watch-faults`, and the forensic-agent design all match
  the shipped workflow (drill-proven per the roadmap). Only cosmetic: line 44
  cites `:relevant-source` embedding-hit ids (a shelved block) among recorded
  volatiles, and the "Build path" para (236–243) is a dated 2026-07-02 status
  whose "remaining gaps" partly shipped.
- **laws.md**: measurements, durable. No stale claim; the "rarely load skills"
  law pre-endorses the skills retirement.
- **library-grounding.md**: `reference-code/…:LINE` read-map; anchors are
  durable. The per-phase migration framing is a slightly dated lens but not
  wrong.
- **decisions/**: ADRs are maintained — 005 (flow) banners SUPERSEDED for the
  core.async-free pod; 007 (instrumentation) carries a 2026-07-05 revision.

---

## Recommended update units (opus-sized, dispatchable)

Three coherent units plus one small footnote. Ordered by value.

### Unit 1 — config→DB / system-text reconciliation (2 docs, 1 cross-doc contradiction)

**Scope:** `architecture.md` glossary + `data-model.md` §5.6. Source of truth =
`context.md` §"Config-through-DB" and §"The system prompt itself is DB state"
(already correct) + `config.cljs`.

- `architecture.md` glossary "prompt" entry: strike "a code const — not a block,
  not per-request overridable"; state system-text is the `:seon.config/system-text`
  datom resolved via `effective-system-prompt` (request override → datom →
  shipped default).
- `data-model.md` §5.6: replace the `:seon.config/manifest` schema with the real
  one (add repl-mode, system-text, on-core-error, web, spawn-depth-cap,
  watchdog, agent-context, root-context); add the config→DB paragraph (the
  `:seon.config` singleton, `resolve-config-singleton`, runtime-reads-db via
  `config-view`, replay-visible + live-tunable). Fix the `SEON_PROFILE`-is-live
  claim (it's inert).

Highest value: kills a real cross-doc contradiction that misleads the mandated
first-read.

### Unit 2 — skills-system retirement sweep (3 docs, one theme)

**Scope:** `toolkit.md` §my.skills, `data-model.md` §5.5, `ui.md` skills-as-block
references (71–73, 326, 383). Source of truth = context-rebuild.md ("The idea
inventory" + "Deliberately NOT blocks").

Reframe the skills SYSTEM (catalog + loadable bodies) as retiring: its job
dissolves into cards + state-gated block teaching + pull references. Keep the
`install!`/block mechanism (ui.md illustrates it validly); demote the `my.skills`
load/unload facade + catalog-block/skill-block + `SEON_SKILLS_DIR` from "target
member" to "retiring, see context-rebuild." Reconcile the [[loadable-skills]]
pointer. (toolkit.md's own ns list already omits my.skills — finish the job.)

### Unit 3 — two-REPL-modes + minimal-tree completeness (2 docs)

**Scope:** `agent-runtime.md` + small touches to `context.md` / `architecture.md`.

- `agent-runtime.md` §"The two bounds": add the `:stream` form-denominated work
  bound (`derive/run-form-count`, `run/default-form-limit` 60) and name the
  `:batch`/`:stream` modes (link context.md as depth). Reconcile the
  "AGENTS.md as reactive file-block" claim to the rebuild (identity file-blocks
  OUT; AGENTS.md mined per-line; soul = a milestone).
- `context.md`: demote order-band 5 "predicted relevance" to pull-first/
  conditional (per the rebuild); fix the render-fn OUTPUT twin key to
  `:seon.render/hiccup` (lines 180, 201).
- `architecture.md`: fix core-idea #3 twin key (line 75) to `:seon.render/hiccup`
  for fn-output; fix the "affordance tail" pointer (line 380).

### Unit 4 (small, optional / fold into the bug fix) — my.plan roll-up limitation note

`data-model.md` §5.3 asserts descendant roll-up / `_parent` recursive walks work
— broken past depth 1 on the pod (known datahike recursive-rule bug). Either add
a temporary "KNOWN LIMITATION (recursive-rule depth>1, fix in flight)" footnote
now, or leave §5.3 to be re-verified by the bug-fix unit when it lands. Not a
target-doc defect — the target behavior is correct — just a landmine to flag.
