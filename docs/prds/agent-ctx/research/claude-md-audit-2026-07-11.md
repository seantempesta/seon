---
type: research
status: active
tags: [research, agent]
---

# CLAUDE.md audit — every nested orientation file vs current reality (2026-07-11)

## TL;DR

Ten in-repo `CLAUDE.md` files auto-load into agent context by directory. The
root and the three `src/seon/**` leaf files are broadly CURRENT. **The two
worst offenders are the two PRD-folder indexes** — `docs/prds/agent-fsm/CLAUDE.md`
(a frozen 2026-07-02 snapshot for a MERGED PRD, still listing shipped issues as
"open") and, more damaging, `docs/prds/agent-ctx/CLAUDE.md` (the ACTIVE branch's
always-loaded index, frozen at the 2026-07-02/03 two-lanes/eval phase — it never
mentions the entire context-rebuild arc that is the actual current focus:
repl-mode `:batch`/`:stream`, config→DB, the minimal-context ladder, the
strip/abort fabrication fix).

| File | Role | Rating | Worst stale claim |
|---|---|---|---|
| `CLAUDE.md` (root) | Universal shared instructions | **CURRENT** | Config "one manifest via seon.config … env-var reads" — doesn't mention config now resolves to a `:seon.config` DB singleton (system-text as a datom). Minor. |
| `src/seon/CLAUDE.md` | src orientation + ONE-mechanism table | **MINOR DRIFT** | "the only we-are-here doc is `docs/prds/agent-fsm/roadmap.md`" — active branch is agent-ctx; wrong roadmap. Config row omits the DB singleton. |
| `src/seon/agent/CLAUDE.md` | engine (loop/run/turn/ctx) | **MINOR DRIFT** | "We-are-here: `docs/prds/agent-fsm/roadmap.md`" (wrong PRD). "Known gaps (in flight)" from 2026-07-02 mostly shipped; no repl-mode / cross-turn current-ns invariant. |
| `src/seon/ai/CLAUDE.md` | LLM providers | **CURRENT** | (freshest leaf, updated 07-10; catalog pointer valid) |
| `src/seon/render/CLAUDE.md` | render engine + ui tiles | **CURRENT** | none material (last touched 07-02 but contract is stable) |
| `src/seon/web/CLAUDE.md` | HTTP/SSE front door | **CURRENT** | none material; `/agents/run` + datastar morph descriptions match code |
| `src/my/CLAUDE.md` | agent-owned toolkit | **CURRENT** | shortest + tightest file; matches reality |
| `docs/prds/agent-ctx/CLAUDE.md` | ACTIVE PRD index (auto-loads on branch) | **MISLEADING** | Entire "Current state / Open tensions / Build order" is the 2026-07-02 two-lanes eval phase; ZERO mention of the context-rebuild arc that is the current focus. |
| `docs/prds/agent-fsm/CLAUDE.md` | MERGED PRD index | **MISLEADING** | Lists #42/#73/#74 as "Core-gated / open"; the leaf files say #73/#56 CLOSED. Frozen "Current state (2026-07-02)". 276 lines of it. |
| `docs/prds/diffusion-dynamic-context/CLAUDE.md` | diffusion PRD index | **MINOR DRIFT** | Freshest PRD index (07-11 typeahead state at top), but "How to run it" still leads with the RunPod/`tmp/flash-diffgemma` workflow the 07-05 local-MLX reboot superseded. |

Recent (2026-07-10/11) mechanisms VERIFIED to exist in code and NOT reflected in
any orientation file except the diffusion one: `run/default-form-limit` (=60,
`:stream` work bound), `ctx/repl-mode` reading `:seon.config/repl-mode` off the
singleton, `default-repl-mode` (per-MODEL), `config/system-text` datom +
`resolve-config-singleton`, `my.plan.internal/empty-plan-teaching`,
`SEON-STUB-LLM` boot marker (`client.cljs:2888`), `eval/core-macro-head?`
(prose-gate computed check), cross-turn current-ns seed (`turn.cljs:424-431`,
"rung-1 root cause 2026-07-10"), `minimal-context-ladder.md` (the governing
plan). The neutralizer/marker hacks ARE gone (grep-confirmed deleted).

---

## Per-file detail

### `CLAUDE.md` (root) — CURRENT

892 lines, 57 KB — by far the heaviest auto-load, but it is the universal file
and was updated 2026-07-11 (a50a2769). Its "Current focus" (CLJS pod active, JVM
paused), Token Reporting, DO-NOT-WRITE-HACKS (with the anti-fabrication arc as
the cautionary tale), Errors-as-data fault workflow, Testing (three surfaces),
and process-management sections all match reality.

Drift, minor:
- **Config framing predates config→DB.** The "Data Rules / Schema Registration"
  and process sections still describe config purely as `config/system.edn` via
  `seon.config` with `SEON_CONFIG`. Reality (`config.cljs`): the manifest now
  resolves to a `:seon.config` **DB singleton** (`resolve-config-singleton`,
  `:seon.config/system-text` stored as a datom, `:seon.config/repl-mode` read at
  runtime off the entity). The manifest is still the boot entry, so this is not
  wrong, just incomplete — the root never says "config lands in the DB at boot;
  runtime reads the DB."
- No mention of the two REPL modes or the minimal-context ladder — arguably out
  of scope for the universal file (belongs in the agent-ctx PRD index), so not
  counted against it.

### `src/seon/CLAUDE.md` — MINOR DRIFT

The ONE-mechanism table is the single highest-value section in the whole set
(see Observations) and is almost entirely current. Issues:
- **Wrong we-are-here pointer (line 7):** "the only we-are-here doc is
  `docs/prds/agent-fsm/roadmap.md`." The active branch is `feature/agent-ctx`;
  the live roadmap is `docs/prds/agent-ctx/roadmap.md` (+ `minimal-context-ladder.md`).
  agent-fsm merged to main 2026-07-02.
- **Config row (line 26)** omits the DB singleton: "ONE manifest
  `config/system.edn` via `seon.config` … `SEON_PROFILE` is inert / Never:
  env-var reads." True as far as it goes, but the row predates config→DB and
  doesn't tell an agent that runtime config now reads a `:seon.config` entity.
- Everything else (DB access, schema, context unit, rendering, errors, token
  counts, blob store, provenance, evals row) matches. The "#73/#56 CLOSED" note
  (line 75) is CORRECT and directly contradicts agent-fsm/CLAUDE.md (see below).

### `src/seon/agent/CLAUDE.md` — MINOR DRIFT

"Systems at play" and "Invariants that bite" are current and load-bearing
(never-throw, byte-identical transcript aging, provenance-on-tx,
`augment-ns-source` alias injection with #73/#56 CLOSED, `^:async`/await).
Issues:
- **Wrong we-are-here pointer (line 5):** same `docs/prds/agent-fsm/roadmap.md`
  staleness as the parent.
- **"Known gaps (in flight)" (lines 63-76) is a 2026-07-02 snapshot.** "Embedding
  hits … being made a recorded turn input" and "shell.cljs / my.blob / web.cljs …
  live-drive verification in flight" describe work that has since shipped
  (`shell.cljs` grants are live and documented in its own docstrings). A "known
  gaps" section dated five weeks stale reads as current status and misleads.
- **Missing NEW invariants:** the cross-turn current-ns seed (batches now start
  from the derived current-ns, not home — `turn.cljs:424`, the rung-1 root-cause
  fix) and repl-mode `:batch`/`:stream` are engine-level invariants that belong
  here and are absent.

### `src/seon/ai/CLAUDE.md` — CURRENT

Freshest leaf (updated 07-10, fd00627b). The contract (providers return values,
`call-llm!` sole retry authority, per-agent routing via manifest, `::max-tokens`
= output cap, one token estimator, prompt-cache discipline), the model-catalog
pointer to `docs/seon/reference/llm-adapters.md` (verified present), and the
`:openai-compat` `reasoning_effort` vs `:thinking` note all match. No drift found.

### `src/seon/render/CLAUDE.md` — CURRENT

Contract holds: one guarded walker/two views, renders never stored, render-fn
guarded, `live_tile.cljs` canvas, render-prominence law, presence-sets not
map-of, tokens-not-chars. The dir changed since the file (transcript-render
redesign 17c6ff5b, 07-09) but that touched `ctx/transcript`, not the render
engine contract this file describes. Stable.

### `src/seon/web/CLAUDE.md` — CURRENT

`POST /agents/run` door (`handle-agent-run!`), reitit-from-datoms router, the
datastar whole-element gzip morph with `!last-tree` explicitly dead, debug page
via `inspect/ctx-preview`, no-agent-touches-SSE rule — all verified against
`serve.cljs`/`router.cljs`/`datastar.cljs`. NOTE: this file (correctly) treats
`/world` as live ("the converged human surface" — `serve.cljs` redirects `/` →
`/world`), which **contradicts** agent-fsm/CLAUDE.md's "/world retired" (see
below). web/CLAUDE.md is the correct one.

### `src/my/CLAUDE.md` — CURRENT

35 lines — the tightest file and a model for the standard. Every claim (full
source renders into context, docstring line-1 rule, keep `register!` in file,
token weight, new nses must be required into `client.cljs`, full-qualification
not home aliases, the current ns list `data/ui/tile/kb/skills/blob`) matches.
The concurrently-edited `my.plan.internal` isn't named in the ns list — worth a
glance once that lands, but not stale today.

### `docs/prds/agent-ctx/CLAUDE.md` — MISLEADING (highest-impact)

This is the **active branch's always-in-context index**, so its staleness costs
the most. It is frozen at the **2026-07-02/03 "two lanes" (tooling vs eval)
phase**:
- "Current state" doesn't exist as such; the file opens with the two-lanes
  framing and its entire "Open tensions & issues — LIVE" section is
  2026-07-02/03 items, most already struck through as ✅ RESOLVED (bench-pod
  isolation, frozen-bundle, my.kb empty render, SCI-bounding fallback, skip-syms,
  ref direction). "LIVE" it is not.
- **Zero mention of the context-rebuild arc that is the current focus:** no
  repl-mode `:batch`/`:stream`, no config→DB singleton, no
  `minimal-context-ladder.md` (which exists, 07-11, and is described in MEMORY as
  "the governing plan"), no strip/abort fabrication mechanics, no milestone names
  (repl/namespaces/plan/db/warnings/live-tile/subagents/soul). An agent landing
  on this branch reads a five-week-old eval-lane charter and learns nothing about
  what is actually being built.
- "Build order" (lines 308-321) lists tooling-lane and eval-lane sequences that
  completed weeks ago.
- Some pointers are stale-by-move: it references `docs/seon/orchestrator/issues/…`
  paths as the live registry.

The file is well-STRUCTURED (contract, settled, runbook, pointer index) — the
problem is purely that its content describes a superseded phase of its own PRD.

### `docs/prds/agent-fsm/CLAUDE.md` — MISLEADING

A 276-line frozen snapshot titled **"Current state (2026-07-02)"** for a PRD that
**merged to main on 2026-07-02** (per MEMORY). It auto-loads for anyone working
under `docs/prds/agent-fsm/`. Concrete contradictions of newer files:
- **#73 home-ns alias listed as an open "Core-gated" item** (lines 248-250) and
  "#42 explicit-listing config" / "#74 todo signature-trim" as pending — while
  `src/seon/CLAUDE.md` and `src/seon/agent/CLAUDE.md` both state **#73/#56
  CLOSED**. Directly contradictory status for the same issue.
- **Settled claim "/world retired … root's view IS the dashboard at /"**
  (line 230) is FALSE against `serve.cljs` (which redirects `/` → `/world`, "the
  converged human surface") and against `web/CLAUDE.md`. (Root CLAUDE.md and
  agent-ctx/CLAUDE.md independently call `/world` "unrelated legacy naming" — a
  three-way muddle about what `/world` is.)
- Large "Load-bearing findings" and "Core lane charter" sections are agent-fsm-era
  and duplicate rules now better stated in the leaf `src/seon/**` files.
Because the PRD is done, this file arguably should be `status: completed` /
archived rather than maintained.

### `docs/prds/diffusion-dynamic-context/CLAUDE.md` — MINOR DRIFT

The best-maintained PRD index: it was updated 07-11 and carries a genuine dated
"Current state (2026-07-11)" (typeahead P1–P5) on top of a reverse-chronological
"Prior state" stack — a good pattern (see Observations). Drift:
- The "How to run it" runbook still leads with the **RunPod / `tmp/flash-diffgemma`
  / `flash deploy`** workflow, which the **2026-07-05 local-first reboot**
  (documented two sections up in the same file) superseded — "GPU gating is
  OBSOLETE; the local MLX worker runs everything free; `src-diffusion/` is the
  home." The runbook and the current-state contradict each other on where work
  happens; a reader could follow the stale RunPod path.
- The reverse-chron "Prior state" stack (07-11 → 07-05 → 07-02) is honest but
  long (~300 lines) — token weight is real for an auto-load.

---

## Observations toward a universal standard (evidence, not a design)

**Which sections earn their keep (appear in the good files, always current):**
- **The ONE-mechanism table** (`src/seon/CLAUDE.md`) — the single most valuable
  artifact in the set. "Mechanism | the one owner | Never" is dense, scannable,
  and directly prevents the repo's #1 failure mode (parallel-system builds). It
  aged well because it names owners, not status.
- **"Invariants that bite" / "Rules that bite"** (`agent/`, `web/`) — imperative,
  falsifiable rules tied to code (never-throw, byte-identical transcript aging,
  `!last-tree` is dead). These rot slowly because they describe contracts, not
  progress.
- **"Systems at play"** (`agent/`, `web/`) — one line per file naming its job.
  Stable and orienting.
- **"Read before editing" pointer headers** + vendored-grounding pointers — cheap,
  stable, high-value.

**Which sections rot fastest (the misleading ones, every time):**
- **"Current state (DATE)" / "Known gaps — in flight" / "Open tensions — LIVE" /
  "Build order."** Every MISLEADING and MINOR-DRIFT finding above traces to a
  dated status/in-flight/roadmap-y section that was written once and not
  re-touched. Status belongs in `roadmap.md` (the designated we-are-here), not
  duplicated into an auto-loading index where it silently ages.
- **Issue-number references (#42/#73/#74)** — they become stale the moment the
  issue closes and then actively contradict the leaf files.
- **Runbooks that accrete** (diffusion) — a new "current" workflow gets added on
  top while the old runbook block stays.

**Best-structured file vs worst:**
- BEST short form: `src/my/CLAUDE.md` (35 lines, zero status, all durable
  teaching) and the `src/seon/**` leaves (owner-table + bite-rules + pointers).
- BEST PRD-index form: `diffusion-dynamic-context` — a dated "Current state" that
  is actually re-dated, over a reverse-chron "Prior state" stack, over a stable
  spine/runbook/settled/research-index. Its one flaw (the stale runbook block)
  shows even this pattern needs the runbook re-touched, not just prepended.
- WORST: `agent-fsm/CLAUDE.md` — a frozen dated snapshot for a merged PRD, 276
  lines, contradicting the leaves. `agent-ctx/CLAUDE.md` is worst-by-impact
  because it's the ACTIVE one and describes a superseded phase.

**Cross-file duplication / drift risk observed:**
- The **#73 home-ns alias** rule is stated in three files with two different
  statuses (CLOSED in `src/seon/` + `src/seon/agent/`, still-open in `agent-fsm/`).
- The **"/world"** status is stated three ways (live/converged in `web/`; "retired"
  in `agent-fsm/`; "unrelated legacy naming" in root + agent-ctx).
- **Render-prominence law, docstring line-1 rule, tokens-not-chars, no-`:kind`,
  home-ns-alias rule, "one mechanism / no foo-v2"** each appear in 3–5 files.
  Some repetition is intentional (each leaf wants to stand alone), but each copy
  is an independent drift surface.
- **"verbs" vs "functions" vocabulary:** the living arch docs and every CLAUDE.md
  still say "verb(s)" heavily (root uses it 9×; the leaves throughout). MEMORY
  notes a "verbs→functions" retirement in living docs is underway; the CLAUDE.md
  files are not yet aligned. Low-priority but a coherence item if the retirement
  is real.

**Existing stated meta-guidance about maintaining these files (quoted):**
- Root `CLAUDE.md`, "PRD folder context" section:
  > "**Keep it tight and current** (it loads into context every time you work
  > there)." … "Update it as the PRD's reality changes — same discipline as
  > component notes. … `docs/prds/diffusion-dynamic-context/CLAUDE.md` is the
  > worked example."
- Root `CLAUDE.md`, "System Documentation":
  > "**After a change:** update the architecture doc it touches … AND the active
  > PRD's `roadmap.md` … The `src/seon/CLAUDE.md` ONE-mechanism table auto-loads
  > on any `src/` edit; check it before building a second version of anything."
- `docs/prds/agent-ctx/CLAUDE.md` header:
  > "**Auto-loads for BOTH lanes. This is the shared, LIVE coordination surface —
  > update it when a tension resolves or a new one appears, so the other lane sees
  > it.**" (The instruction is right; the file was not kept to it.)

The evidence points one way: the guidance to "keep it tight and current" already
exists, and the durable sections (owner-table, bite-rules, systems-at-play) obey
it while the dated status/in-flight/roadmap sections do not — because status was
duplicated into an auto-load instead of left in `roadmap.md`. The reliably-current
files are the ones that carry NO status.
