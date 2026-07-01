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

<!-- Add your config keys here. For EACH: the fully-namespaced key
(:seon.agent.ctx.namespaces/…), its malli spec + :default, one-line doc, and what
current behavior/hardcode it replaces or enables. New aspects go in the ::aspect
enum. Note whether each is wire-existing or needs a new render mechanism. -->

(to be filled by the namespace-display agent)
