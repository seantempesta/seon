---
type: prd
status: active
tags: [prd, architecture, naming]
---

# Vocabulary unification PRD

## Rulings so far (2026-07-20)

- **"pod" is retired**: running unit → **cluster**; the supervised CLJS
  process → **client** (confirmed; matches `:client` build +
  `seon.client/-main`). Execution plan and complete 3257-site inventory:
  [[../database-authority-mesh/research/pod-term-retirement-plan-2026-07-20]].
  This is stage 2 of the cleanup roadmap — one atomic rename under a lane
  freeze.
- Doc scope: current branch PRDs + `docs/seon/` only; dated research and
  archives stay as written.

## Remaining problems

Evidence: [[../database-authority-mesh/research/cleanup-audit-vocabulary-2026-07-20]].

1. "tile" survives only in tests (~30 fixture names/prose across six test
   files). Safe mechanical rename.
2. Verb migration is done in src; two fixture prose strings remain
   (`repl/internal_test.cljc:999,1005`). Keep the load-bearing
   `:seon.eval/repl-verb` comment in `error.cljs:227` (persisted datoms).
3. `render/canvas.cljs:528` prose says "shared store" for the database.
4. `bin/acme:106-126` still names `gym-diffusion` / `acme/gym/scenarios/`.
5. Unlegislated drift needing a ruling (below): "panel", feed/stream.

Confirmed correct, no action: konserve "store" at its seam; cljs.test
`:type` report keys; `:seon.error/kind` / `:seon.repl/kind` closed enums
(add a one-line comment at each `register!`).

## Recommended solution

Items 1-3 ride along in stage 2's sweep commit series (same freeze, same
gates). Item 4 is downstream-owned: rename during a quiet acme window.
Update `CLAUDE.md`/`AGENTS.md` §Vocabulary with the revised glossary from
the audit report in the same series.

## Owner rulings 2026-07-20 (second round)

1. **"panel" is not legislated — it is a design smell.** The owner wants ONE
   schema-aware data inspector: a component that detects the registered
   Malli schemas present in a structure (a single structure may carry more
   than one) and renders values with that understanding, used everywhere a
   value is shown to a person. Research lane owns the design
   ([[value-inspector-research assignment|research/]] — report lands under
   `research/`). Until it lands, no panel renames.
2. **feed/stream resolved by seam research**
   ([[research/reactive-seam-names-2026-07-20]], all names grounded
   file:line): database side uses the fork's own nouns — transaction
   report, committed report, interest; `seon.reactive`'s "registration"
   stays; Datastar side adopts `sse-gen` / patch vocabulary and
   `:seon.web.feed/*` is renamed accordingly (process atom, no persisted
   datoms); datastar.cljs "subscription" → registration (stage 1, rides the
   async-facade files); dead `feed-behind-status` deleted after
   `bin/test-writer` proof; "stream" survives only at seams that own it
   (LLM stream/chunk/delta, SSE transport, Web Streams). **Owner ruled
   2026-07-20: the public URL `/agent/{id}/feed` renames to `/sse`** in the
   stage-2 freeze (route facts + shipped shim + downstream acme in the same
   series).
3. `docs/seon/pod/REPL-WORKFLOW.md` folds into `docs/seon/architecture/`
   (ruled).
