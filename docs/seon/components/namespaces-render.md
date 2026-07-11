---
type: component
status: active
tags: [component, agent, flow]
---

# Namespaces context render (CLJS pod)

> The `:namespaces` context section — THE BODY of an agent's prompt. Renders the
> agent's CURRENT ns FULL, its `:require`s as COMPACT CARDS, and DROPS everything
> else. Config-driven and third-party override-proven. SHIPPED 2026-07-02.

This is the CLJS-pod (active) feature. The `.clj` notes [[components/context]] and
[[components/namespace-lifecycle]] describe the PAUSED JVM main-app track — a
different mechanism.

## Namespace

| Namespace | File | Role |
|-----------|------|------|
| `seon.agent.ctx.namespaces` | `src/seon/agent/ctx/namespaces.cljs` | The `:namespaces` block render fn + the compact-card renderer + the boot-storage rule |

Wired into the composer as the `:namespaces` block (priority 20,
`:seon.render/ai seon.agent.ctx.namespaces/namespaces-block`), seed-copied into
each agent's `:seon.agent/ctx`.

## The three rules

`namespaces-block` renders each INCLUDED ns at one of two detail levels; every
other indexed ns is dropped:

- **FULL** — the agent's CURRENT ns plus any ns in the per-agent `::full-source`
  presence-set. Real full file source, unclipped. `full? ⇔ (nm = current-ns ∧
  ::current-full?) ∨ (nm ∈ ::full-source)` — ONE rule, no second full control.
- **COMPACT CARD** — every ns the CURRENT ns `:require`s (`required-ns-set`), via
  `render-one-ns-compact`: the ns's `register!` schema block (verbatim) + every
  public fn as a one-line `(defn name "doc." {:malli/schema …} [args] …)` head,
  body elided (`…`). ~3–5× smaller than full.
- **DROPPED** — everything else. Still indexed (`:seon.ns/name` + `:seon.fn` /
  `:seon.schema` rows), grep-able, and full on demand via
  `seon.agent.ctx/render-namespace` — just not resident in the section.

`*.internal` / `*-test` nses are excluded outright (`included-ns?`); empty cards
are dropped.

## Inclusion = `:require`, driven by config

The include set is `current ns ∪ its :requires ∪ ::full-source pins`. There is
**no `:always` allow-list, no `compact-worthy?` predicate, and no hardcoded
`my.*` pinning** in the render (all retired). Write a real `(:require [x …])` on
the current ns and `x` joins as a card; drop the require and it vanishes —
self-healing on the `:seon.ns/require-edges` rows.

The DEFAULT function surface is therefore a CONFIG concern:
`:seon.eval/home-requires` in the manifest (`config/system.edn`
`:seon.config/agent-context` + `:seon.config/root-context`) is what a fresh
agent's home ns requires, so it IS what renders as cards. Root additionally gets
`[seon.agent :as agent]`; the default toolbelt is `message` / `todo` /
`lifecycle` / `schema` / `db` / `my.kb` / `my.data` / `my.ui` / `my.tile` /
`search`.

## Per-agent dials (config-driven-agent-init)

Four attrs on the `:namespaces` BLOCK entity, read reactively via `resolve-cfg`
(block → agent datom → malli default; a `db/transact!` re-derives next render):

- `::full-source` (`[:vector :seon.ns/name]`, default `[]`) — force a ns FULL.
- `::with-tests` (same) — append the ns's indexed `:seon.test/source`.
- `::current-full?` (`:boolean`, default true) — whether the current ns renders full.
- `::current-tests?` (`:boolean`, default true) — whether it shows its tests.

Presence = config; compact = absence. No `:compact` token exists, so the
`#{:full :compact}` conflict is unrepresentable. Per-cluster full-source policy
also rides `:seon.config/namespaces` (`:seon.config/always` + `:seon.config/current-ns`).

## Invariants

- **Never a render-time file read.** The boot indexer (`seon.client/ns-row`) is
  the ONE reader; both the full renderer and the compact card read only indexed
  rows (code-as-data). `full-source-ns?` is the boot-STORAGE rule (which rows keep
  real file text) — a SUPERSET of what any one agent renders full, NOT the
  per-agent selection.
- **The current ns always renders**, even empty — a fresh home ns
  (`my.agent.<id>`) becomes a `cur-ns-workspace-stub` showing its real
  `(ns … (:require …))` form, so the "YOUR OWN namespace renders in full" promise
  holds on turn 0.
- **Cache-stable ordering.** Stable `seon.*` required cards render first
  (name-sorted PREFIX); the churning `my.*` / current-ns BODY renders
  recency-ordered nearest the tail.
- **Errors-as-values.** A bad indexed row degrades one card line; it never throws
  into the render.

## Third-party override-proven (acme)

`config/acme.edn` sets its OWN `:seon.eval/home-requires` (a trimmed core subset
PLUS `acme.helpers` / `acme.notes`) and its OWN `:seon.config/namespaces`
full-source set — with ZERO `src/seon` edits, a fresh acme agent's card surface
is acme's toolkit. Load-bearing gotcha: a manifest that supplies `:seon.agent/ctx`
REPLACES the default block tree wholesale (malli default-fill only fills ABSENT
keys), so acme must re-list every block it wants to keep — including `:namespaces`
at priority 20 — or the section silently vanishes.

## The docstring convention (the seam)

A public fn's docstring first line is a complete, ≤72-char sentence (78 hard cap)
ending in terminal punctuation — the compact card shows ONLY that line; the rest
renders in full view only. Enforced by `seon.dev.docstring` (dev-hook warn). Full
rule + the corpus-cleanup sweep: [[compact-namespace-cards-spec]] +
`docs/conventions.md` "Function Docstrings".
