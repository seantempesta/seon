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
law) — the card carries the full data model + typed contract.

> **This section was ITERATED with the owner (2026-07-01) and REPLACES the earlier
> draft (which proposed an `::aspect` enum + extending decision 14's `::render`
> `:map-of`). Two things changed and both affect YOUR §2.2 — please absorb or push
> back:**
>
> 1. **Examples are DROPPED.** Real eval examples already live in the transcript,
>    and harvesting them into the cached namespace block would re-flow every turn
>    and bust the prompt cache. No example harvesting, no `::example`,
>    no Malli-generated samples. Gone.
> 2. **The `:map-of ::render` shape is REPLACED by cardinality-many presence-set
>    attrs** — because **datahike has no map value type.** A `{ns → set}` value can
>    only serialize (like `:seon.render.live-tile/content` stores hiccup), which
>    kills per-ns queryability + reactivity. The datahike-native shape already
>    exists in-tree: `:seon.agent.ctx/render-namespaces` is `[:vector :keyword]`
>    (cardinality-many). We extend THAT pattern, we don't invent a map.

### The model — attribute-presence, refs not maps

Two cardinality-many attrs on the **namespaces block entity** (the "ref off the
agent record" — decision 13/16 two-level model). **Presence of a namespace in a
set IS its config**; compact is the ABSENCE. Split along the additive line:
full-vs-compact is a CHOICE (one membership set, absent = compact), tests is
genuinely ADDITIVE (a second independent set).

```clojure
;; colocated register! in seon.agent.ctx.namespaces
(schema/register! ::full-source   [:vector {:default []} :seon.ns/name]) ; ns present → FULL (absent → compact)
(schema/register! ::with-tests    [:vector {:default []} :seon.ns/name]) ; ns present → also show tests
(schema/register! ::current-full?  [:boolean {:default true}])           ; the agent's current ns → full
(schema/register! ::current-tests? [:boolean {:default true}])           ; …and its tests
```

- **No `:compact`/`:aspect`/density token exists** → the `#{:full :compact}`
  conflict is structurally unrepresentable. Compact = "in neither set".
- **Reactive per-datom:** pin one ns full = `[:db/add block-eid ::full-source
  :seon.agent.run]`; retract to revert. No read-modify-write, no map re-serialize.
  Exactly how `render-namespaces` behaves today.
- **Pull is trivial** (no reified entity to index): `(db/pull [::full-source
  ::with-tests] block-eid)` → two vectors → `(set …)` → membership check per ns.
- **Value type = `:seon.ns/name` keyword** (matches `render-namespaces`), NOT a
  `:db.type/ref`: tolerates configuring a ns that isn't indexed yet (the agent's
  dynamic `my.agent.*` home ns, future nses) — a keyword just no-ops in the render
  if unmatched, where a ref lookup-ref would error/stub. Still fully
  relational-not-map (a cardinality-many attr, not a blob). Genuine ref is
  defensible only if you want a config typo to fail LOUD + never config an
  unindexed ns — owner leaned keyword.
- **Current ns = two scalar bools**, NOT a magic `:current` entry in a map. No
  pseudo-namespace key.
- **Likely a rename/extend of `:seon.agent.ctx/render-namespaces`**, not a new
  mechanism — that attr already means "which nses render." Post compact/full split
  it becomes `::full-source` (or the two coexist: one = include, one = pin-full).
  Your call where the include-set axis lives.

### The reify-when-value-carrying rule (so decision 14 doesn't lock in a shape)

Two presence-sets are right precisely because each facet is a BOOLEAN
("is this ns full?"). The moment a per-ns facet carries a VALUE — a per-ns token
cap, an ordering weight, a note — promote to a **reified entry entity** per ns,
`:db/isComponent`-ref'd off the block (cascade-delete), pulled as
`{::entries [{::entry/ns … ::entry/cap …}]}`. Don't reify for booleans; do reify
the day a value appears. Flagging so §2.2 encodes presence-sets now without
foreclosing the entry-entity path.

### Defaults live in the specs (malli-native, decision 4)

Every dial carries its `:default` inline (`{:default []}` / `{:default true}`),
filled by `m/decode` through the `default-value-transformer`. No default-const.
A fresh agent → compact everywhere + full current-ns, purely from the defaults.

### Byte-parity note (decision 12)

Defaults keep the CURRENT rendered set full only where it is today. The broad
"compact-everywhere for the long tail" flip is a SEPARATE owner-gated step AFTER a
live A/B verb-adoption drive (the 0× guardrail) — not part of the atomic v1 parity
build. v1 can register these attrs with parity-preserving defaults and flip later.

### `:signatures` — flag for the spec

Bare `:signatures` IS the render-prominence-law footgun (0× adoption). With
presence-sets there's no density enum to hold it anyway. Recommend DROP it — the
compact card supersedes it, and there's then no way to accidentally ship the view
that failed. Your call as spec owner.

### Cross-lane dependency (not a config key)

The card's quality depends on a docstring convention — line 1 = complete ≤72-char
sentence — enforced by a doc-lint (sibling to `seon.dev.markdown`, via the dev
hook). Corpus audit: 111/671 comply, ~560 need cleanup. The renderer ships against
today's docstrings (soft-clip 78, a hardcoded constant — NOT a config key) and
improves as the sweep lands. No coupling to your build.
