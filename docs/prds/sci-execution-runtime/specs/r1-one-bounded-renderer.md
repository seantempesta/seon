---
type: prd
status: active
tags: [prd, architecture, agent]
---

# R1 — one bounded generic renderer with honest semantics

## Grounding preamble (mandatory)

Read the actual source of every file you touch before editing. Report:
(a) a better seam if found; (b) the owners' exact terms. **Stopping
early to report is FREE.** If source contradicts this spec, stop and
report.

Authority: `docs/prds/sci-execution-runtime/research/renderer-quality-audit-2026-07-22.md`
(recs 1+2; every claim file:line-grounded, with real transcripts).

## Goal

1. **One guarded walker in fact**: the recursive generic fallback
   (`render.cljs:899` raw pprint) routes through the SAME bounded
   `render.value` projection the eval block path uses — depth, breadth,
   string, and token safeguards apply to every generic node. No second
   sampler; strengthen the existing one where its API needs a
   non-eval-context entry.
2. **Honest generic AI semantics** (the audit's ordering): schema
   key + stable identity + top-level type/count FIRST; the structural
   sample second; continuation LAST. The identity header derives from
   the registered identity attributes (`:seon.entity/id-attr`), never
   the hard-coded five-attr list (`render.cljs:883`).
3. **Never teach fiction**: `result/inline` dies — continuation either
   carries a REAL retained selector (when the value is addressable) or
   says honestly "partial view; no live continuation." The `each {…}`
   summary becomes an actual key intersection or says "sampled
   columns."
4. Sampling preference: preserve identity, status, title/summary,
   error, and provenance fields over short-printed-size ranking
   (`value.cljc:711`) — a documented field-preference tier in the one
   sampler.

Renders remain derived, never stored; ai views clip to the existing
token budgets; context prose is tuned freely (tests assert structure/
presence, never wording).

## Falsifiers

- The audit's two transcript scenarios re-rendered: the 80-key config
  through the RECURSIVE path is now bounded, identity-first, with an
  honest continuation (paste before/after in the summary).
- A registered entity with a custom identity attr renders its real
  identity header.
- No `result/inline` token can be produced (rg + a unit test on the
  continuation builder).
- Eval-path renders stay byte-stable for unchanged inputs where the
  audit found them already good (regression guard on the bounded
  block path).

## Owned paths

`src/seon/render.cljs`, `src/seon/render/value.cljc`, their tests
(enumerate). Protected: everything else — the handlers family
(bespoke renderers unchanged), `seon.render.schema` slots. Other lanes
own repair/host/operator files. No commits, no lifecycle ops.

## Gates

Focused render/value selectors then full `bin/test-cljs` (baseline
1514/7293 — record after) + `bin/test-writer` (value.cljc is portable;
369/2781).
