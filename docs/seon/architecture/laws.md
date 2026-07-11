---
type: architecture
status: active
tags: [architecture, agent]
---

# Empirical laws — drive-measured findings that constrain design

These are not principles we chose; they are results we MEASURED by driving
live agents and paying for the failures. Each one earned its place by
contradicting something that "looked right." Design against them. When one
stops holding, re-measure before deleting it — and delete it here, not
around it.

## Context

- **Render-prominence: a composition function's value IS its worked example.**
  A function agents must COMPOSE (`my.data`, `my.ui`, `my.canvas`) rendered as a
  bare signature is undiscoverable — a signature-trim drove adoption of a
  built toolkit to zero and the agents hand-rolled the broken path instead.
  Simple-call functions may render compact; composition functions render FULL.
- **Agents succeed from the always-on context; they rarely load skills.**
  The lean always-on base is what matters — hoist the highest-value skill
  guidance into it rather than growing the loadable corpus.
- **Cache stability: aged transcript clips render byte-identical, always.**
  Eviction bands by AGE, not recency-weight — re-flowing old text busts the
  LLM prompt cache every turn. Any renderer change that reorders or
  re-flows stable-prefix content is a silent cost regression; check
  cached-token counts in `llm-usage` after render changes.
- **Full qualification, not home-ns aliases.** Aliases resolve only in the
  home ns; agent-authored nses using them fail (~60 identical errors per
  drive). Context and worked examples must model full qualification.

## Honesty

- **Canvas-first mitigates fabrication.** A derived tile is computed from
  data; prose is where agents lie. The same agent fabricated a number in
  prose while its `my.data`-derived canvas showed the correct one — moving
  the agent onto the canvas flipped the judge to PASS. Prefer surfaces that
  are functions of the DB over narration.
- **Honest termination beats a plausible answer.** A timed-out run must
  report timeout + an empty reply, never a stale/greeting completion — this
  was load-bearing for the external benchmark bridge and is the general
  contract: never let a bound-hit masquerade as success.

## Measurement

- **Measure the RIGHT thing — aggregate metrics hide adoption collapses.**
  Total-token scores missed a regression that a single paid composition
  drive caught immediately. Free axes for cheap iteration; a paid drive +
  a dedicated axis for what free axes can't see (e.g. toolkit adoption).
- **`pass^k` — single-sample drives are noise.** Weak-model variance flips
  scenarios run-to-run; average k samples before believing a pass OR a
  regression.
- **Guards must be alias-tolerant.** A pass-detector matching only the
  namespace's full name scored a perfect aliased run 0/14. Predicates over
  agent output match the mechanism, never the exact spelling.
- **Keep-iff-it-lifts-the-battery.** Every context/engine change is judged
  by the whole scenario battery, not the scenario it targeted —
  accretive-or-revert prevents overfitting the last drive.
- **Hermetic fixtures or flaky truth.** Concurrent runs sharing fs/DB state
  flake; pid-scoped scratch state per test is the price of parallelism.

## Process

- **Live-drive beats inference.** Server-side "it renders correctly" has
  repeatedly contradicted what the driven agent actually used or
  understood. Every context/UI/function unit ships with a real drive + an
  observer reading the agent-facing output.
- **The system is the test substrate.** Per-agent context experiments
  (mint a child, `install!`/`remove!` in its scope) beat cluster resets —
  A/B a context change without disturbing anyone.

Depth and the original evidence: the dated drive reports under
`docs/prds/agent-fsm/research/` (overnight-2026-06-28 and the
toolkit/canvas/namespaces/facet validation files).
