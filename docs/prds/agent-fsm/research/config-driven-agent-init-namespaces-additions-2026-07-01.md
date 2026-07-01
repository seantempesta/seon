---
type: research
status: active
tags: [research, agent, config]
---

# Namespaces-block config — additions from the namespace-display work

**For the agent improving the namespace context display.** We are building ONE
config-driven agent-init where the ENTIRE context is spec'd, reactive datoms on
the agent's record. Your namespace-display config options belong in this model —
add them HERE (this is a conflict-free note; the spec doc itself is mid-rework, so
don't edit it directly). The spec agent will fold your additions into the
namespaces section (§2.2).

## Read first (the canonical references)

- **Decision log** — `docs/prds/agent-fsm/research/config-driven-agent-init-decisions-2026-07-01.md`
  (the settled decisions; your area is decisions 2, 3, 13, 14).
- **Spec** — `docs/prds/agent-fsm/research/config-driven-agent-init-spec-2026-07-01.md`
  (the full design; your area is §2.2 `seon.agent.ctx.namespaces`).

## The model your options must fit

1. **Reactive config-on-record.** Every dial is a datom; the namespaces block is
   its own entity carrying its config; changing a datom re-derives the render (no
   apply step). Your options are config keys on the namespaces block, read by the
   render fn at render time.

2. **Colocation.** Keys live in the ns that operates them — for you that's
   `seon.agent.ctx.namespaces` (`register!` there, use `::keyword`).

3. **Flat, fully-namespaced keys** — the keyword-namespace IS the grouping, NOT
   nested maps. Nested VALUES (a map-of ns→config) are fine as DATA.

4. **The per-namespace render map (the current shape).** Which nses render + what
   ASPECT of each is ONE map:
   ```clojure
   :seon.agent.ctx.namespaces/render
   {:my.kb       #{:source}                 ; ns → aspect-set
    :acme.orders #{:source}                 ; a third-party ns is just an entry
    :current     #{:source :tests}}         ; :current = the agent's current ns
   ```
   Aspects so far: `:source` (ns form + full fn/schema bodies), `:tests`
   (colocated test blocks), `:signatures` (arglist/doc head only). Plus a
   `:seon.agent.ctx.namespaces/include-referred-local?` bool (auto-add the current
   ns's local requires to the render map).

5. **Malli-native defaults** — every key carries its `:default` inline in
   `register!`; tighten specs (real `:enum` for aspects, `:map-of`/`:set`, no
   `:any`); register a shared shape ONCE and reference it.

## Additions from the namespace-display agent

**Full design:** [[compact-namespace-cards-spec]]. TL;DR: most nses render as a
CARD — the `register!` schema block + every public fn condensed to a one-line
head `(defn name "<docstring line 1>" {:malli/schema …} [args] …)`, body elided.
3–5× smaller than full source (`my.kb` 3719→664 tok, `todo` 3696→1179), so the
agent can see its WHOLE verb surface instead of ~11 full nses. This is NOT the
dead `:signatures` view (bare signatures drove 0× adoption, render-prominence
law) — the card carries the full data model + typed contract + a runnable
example, which is the hypothesis for why it works where signatures starved.

**It fits your model with ZERO new top-level keys — two new aspect values on the
`::render` map's aspect-set.** Everything I need is per-ns aspects, so it's pure
extension of decision 14.

### New aspects (extend `::aspect`)

Register in `seon.agent.ctx.namespaces` (colocation):

```clojure
(schema/register! ::aspect [:enum {:default :source}
                            :source :compact :signatures :tests :example])
```

- **`:compact`** — render the ns as a CARD: its owned `register!` block
  (reconstructed from the registry, `::`-abbreviated) + one-line `(defn …)` heads
  (docstring line 1 + `:malli/schema` + arglist + `…`). NEW render mechanism —
  `render-one-ns-compact` in `seon.agent.ctx`, built from indexed rows
  (`:seon.schema/_ns`, `:seon.fn/_ns` spec/doc/arglists), NEVER a file read.
  ENABLES the coverage play: many nses as `#{:compact}` for the budget of a few
  `#{:source}`.
- **`:example`** — append a RUNNABLE worked example per verb: the most-recent
  `:seon.eval/ok? true` call + real `:seon.eval/result-edn`, harvested from the
  eval log. NEW mechanism (bounded query). Additive — composes with `:compact` OR
  `:source`. NEVER Malli-generated (mg/generate yields semantically-poison
  samples — `:from 3`, `ok? false`); omit when no real usage exists.

### Shape rule this forces — DENSITY vs ADDITIVE aspects

`:compact` makes an ambiguity in the current aspect-set explicit and worth nailing
in §2.2: the set mixes two kinds of aspect.

- **Density (mutually exclusive — pick ONE):** `:source` (full bodies) |
  `:compact` (card) | `:signatures` (bare head). A set with two of these is
  contradictory. Rule: exactly one density aspect per entry; validator picks the
  richest if >1, or reject at spec time.
- **Additive (compose freely):** `:tests`, `:example`. Layer onto whichever
  density is chosen — `#{:compact :example}`, `#{:source :tests}`,
  `#{:compact :tests :example}` all valid.

Recommend encoding this so the `::render` value is `[:set ::aspect]` with a
registered predicate/`:and` guard "≤1 density member", rather than a free `:set`.

### Defaults — byte-parity preserved (decision 12)

I change NO defaults. A no-override boot stays all-`#{:source}`, byte-identical to
today. `:compact`/`:example` are OPT-IN per ns until the live A/B drive proves
cards match-or-beat full-source verb adoption (the 0× guardrail — validate, don't
assume). The eventual default render map (broaden to `#{:compact}` for the long
tail, keep `#{:source}` for the `my.*` exemplars + `:current`) is a SEPARATE
owner-gated flip AFTER that drive — not part of the atomic v1 parity build.

### `:signatures` — flag for the spec

Bare `:signatures` IS the render-prominence-law footgun (0× adoption). Options for
§2.2: (a) drop it entirely — `:compact` supersedes it; or (b) keep it but document
it as known-weak "bare head, no schemas/example — prefer `:compact`". I lean (a):
one non-full density aspect (`:compact`), no way to accidentally ship the view that
failed. Your call as spec owner.

### Cross-lane dependency (not a config key, but you should know)

The card's quality depends on a docstring convention — line 1 = complete ≤72-char
sentence — enforced by a doc-lint (sibling to `seon.dev.markdown`, via the dev
hook). Corpus audit: 111/671 already comply, ~560 need cleanup. The renderer ships
against today's docstrings (soft-clip 78, a hardcoded constant — NOT a config key)
and improves as the sweep lands. So no coupling to your build: `:compact` can land
independently; it just looks better as docstrings clean up.

### Deferred (phase-2, not v1 keys — parked per decision 21)

- `::example-source` — pin a stable canonical example vs harvest-latest (the
  card-churn / prompt-cache tradeoff), and cross-cluster leakage policy (harvested
  examples carry real user data → owning-cluster only). Documented so it's not
  lost; not a v1 dial.
