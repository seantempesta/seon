---
type: prd
status: active
tags: [prd, agent, context, architecture]
---

# Context generation — the program

**The mandate (owner, 2026-08-14): overhaul the content rendering
pipeline so ONE system serves both the agents' context and the HTML
views. We are NOT rushing. The next session's first job is to fully
understand the current system and every wart before building
anything.** This folder is the complete record; a fresh session starts
here and reads in the order below.

Successor program to
[sci-execution-runtime](../sci-execution-runtime/README.md) (its README
carries the ruling archive; its
[unsettled.md](../sci-execution-runtime/plan/unsettled.md) working edge
carries session state until this program grows its own).

## Reading order for a fresh session

1. **[The one-renderer PRD](plan/context-generation-prd-2026-08-17.md)** —
   THE document: the eight-stage pipeline, the failure policy (panic
   hard in dev; production never crashes — designed human cards, flat
   error data for agents, three faces one fact), the verified rip-out
   register, the archaeology revivals, the generative property suite,
   the wave plan, and the owner's three open questions. STATUS: draft,
   awaiting the owner's markup. No wave starts before it.
2. **[Where we fucked up](#where-we-fucked-up)** — below. Read it so
   you do not repeat it.
3. The evidence, as needed per PRD section:
   [results-as-data audit](research/results-as-data-audit-2026-08-14.md)
   (30.5% data / 66.7% narrated prose — the worst finding),
   [clipping census](research/context-clipping-census-2026-08-14.md)
   (16 violations),
   [render archaeology](research/render-archaeology-2026-08-14.md)
   (the first implementation had the pipeline; quarry root
   `9e44815f5:src-old/`),
   [gap census](research/one-renderer-gap-census-2026-08-14.md)
   (47 rows + wave plan),
   [transcript view design](research/transcript-view-design-2026-08-14.md),
   [UI verification](research/ui-verification-2026-08-14.md),
   [context ablation](research/context-ablation-2026-08-14.md)
   (live model: weak recency convicted; tail reminder = 2/2 forms),
   [drive-1 report](research/drive-1-report-2026-08-14.md) +
   [observation](research/drive-1-observation-2026-08-14.md)
   (five $0-to-half-cent attempts, each converting a latent defect
   into a landed fix).
4. **UX direction** (conversations first; not yet a PRD):
   [docs/seon/architecture/ui.md](../../seon/architecture/ui.md) is
   the standing design authority — READ IT BEFORE ANY UI OPINION (this
   session failed to, see fuckup #5). The owner's ruled corrections on
   top of it: conversation view is the default; `/` stays the tile
   system-view (one live card per agent) and the place you find your
   way back; new sessions are new agents in `my.agents.<id>` — a
   non-programmer opens a chat, never chooses a namespace, and root
   migrates the agent once its purpose is clear (mechanism open:
   message-to-root sugar vs one creation route); agent-declared html
   render functions appear automatically as right-column panels sorted
   newest-basis, click-to-pin swaps panel and transcript
   (browser-local, never a fact); chat = the transcript's `/html`
   projection with inline-expanding pretty-data chips; debug = the
   `/ai` content formatted (character-faithful modulo whitespace and
   color, raw toggle); message bar at the bottom, auto-expanding, like
   a real chat tool. Ledger rulings 30-32 record these.

## The plan documents

- [one-renderer-prd-2026-08-14.md](plan/context-generation-prd-2026-08-17.md) — active, awaiting markup
- [live-drive-spec-2026-08-13.md](plan/live-drive-spec-2026-08-13.md) — the drive series (drives are the measurement instrument; drive 1 attempts 1-5 complete)
- [plan-context-prd-2026-08-13.md](plan/plan-context-prd-2026-08-13.md) — my.plan + intent membership (T1-T3 LANDED)
- [design-ideas-ledger-2026-08-13.md](plan/design-ideas-ledger-2026-08-13.md) — every idea with status; rulings 17-32
- [instruction-facts-prd-2026-08-13.md](plan/instruction-facts-prd-2026-08-13.md) — PARKED by the owner

## Where we fucked up

Technical — the system's warts, all evidenced in the PRD register:

1. **We let every render path invent itself.** No stage contracts
   anywhere (`render.clj:468-474` returns the unprojected node
   SILENTLY when a face fails its contract). Result: 66.7% of what
   agents got back was narrated English, entity pulls destroyed 98.8%
   of their data, and nobody noticed because nothing checked.
2. **We papered instead of fixing.** Every blow-up got a local bandage:
   hand `subs` truncations (12 sites), CSS `overflow:hidden` (4 rules),
   `renderer unavailable` divs (67 on one page), a `[clipped]` token
   that rewrote real elision markers. Each bandage hid the next
   defect: the fence-splitting truncation mangled the agent's ONE
   teaching demonstration and cost us a live drive attempt.
3. **We reinvented what the archive already had.** The first
   implementation had the coherent pipeline — strict-fail discipline,
   sample→emit bounding, chain-hash invalidation, a 192-line
   tokenizer — and we rebuilt none of it and forgot it existed until
   the owner forced the archaeology. Its lessons were also ignored:
   its strict dial defaulted OFF and eleven catch sites bypassed it —
   absence-as-health inside the guard itself.
4. **The orchestrator (me) trusted reports over bytes.** "Fixed" claims
   went unverified until the owner demanded screenshots and lint
   reports; the same session invented `bounded-text` call sites at
   producers hours after ruling that producers never bound; used
   "producer" all day though the vocabulary table already retired it.
5. **UX was re-envisioned without reading the design authority.**
   `docs/seon/architecture/ui.md` already specified the tile root view,
   newest-basis panels, and the block system; the owner had designed
   it three times. Quarry-first applies to our own docs, not just git
   history.
6. **Two-lane file collisions, twice.** ns.clj deadlock
   (block-coverage × clip-ripout), stop/resume races spawning duplicate
   lanes. Ownership must be per-file explicit at launch, and a stopped
   lane's pid verified dead before resume (memory rules now exist).

Process rules going forward, all now recorded: verify claims at the
bytes (the lint tool `seon.render.lint` exists precisely so page
quality is a REPL query); read the named authority END TO END before
designing; producers/functions hand back whole values; one file, one
lane; no wave starts before the owner marks up the PRD.

## Current state (2026-08-14, session end)

- Landed this cycle: `/data` 500 fixed; CSS hiding removed; 67→12
  placeholders; boilerplate → identity references; SCI pair fix; the
  402 provider failover + OpenRouter backup; the bounded-await owner +
  lock bounds + both wedge census regressions; the settlement chain
  (the "golden" lost-settlement defect) fixed with live proof;
  green-to-install all six steps; T3 `:about` token vectors; the
  prompt-tail recency fix implemented (ablation-proven 2/2); the
  fidelity wave (single-snapshot prompts, accounting exact to 3
  tokens, cost facts recording).
- `clip-ripout` LANDED at session end (`94116765e`, `9c3b4de51`,
  `fd75232f3`, `597d273b7`): whole-value conversions in the ruled
  boundary-only shape, the SCI source cap declared as config, isolated
  proof showing 55 whole notes verbatim. Its one deferred item: the
  live production `render-ai` proof timed out past 60 s — that is the
  KNOWN slow-derivation class (the one-pull restructure's case), not a
  regression; fresh evidence for wave 3. No lanes in flight.
- The live clusters: shared `default` (:7994) and the drive specimen
  root `tmp/drive-1-root` (:55156, attempt-5 evidence preserved).
- Issue index ~190 open / 1125 archived, green.
- NOT started, by design: every PRD wave (awaiting markup); the UX PRD
  (direction recorded above, to be written as a delta against ui.md);
  Drive 2; W3-W5 kind deletion + renames + closing `--all` (the
  deferred R2 spine, in
  [unsettled.md](../sci-execution-runtime/plan/unsettled.md)).
