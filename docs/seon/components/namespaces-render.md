---
type: component
status: active
tags: [component, agent, flow]
---

# Namespaces context render (CLJS pod)

> The `:namespaces` context section — THE BODY of an agent's prompt. Renders the
> agent's CURRENT ns FULL, real requirements and explicit compact selections as
> COMPACT CARDS, explicit full selections in full, and DROPS everything else.
> Config-driven and third-party override-proven. SHIPPED 2026-07-02.

This is the active namespace-context feature. The former JVM context and
namespace-lifecycle mechanisms are archived; they are not alternate paths.

## Namespace

| Namespace | File | Role |
|-----------|------|------|
| `seon.agent.ctx.namespaces` | `src/seon/agent/ctx/namespaces.cljs` | The `:namespaces` block render fn + the compact-card renderer + the boot-storage rule |

Wired into the composer as the `:namespaces` block (priority 20,
`:seon.render/ai seon.agent.ctx.namespaces/namespaces-block`), seed-copied into
each agent's `:seon.agent/ctx`.

## The selection rules

`namespaces-block` renders each INCLUDED ns at one of two detail levels; every
other indexed ns is dropped:

- **FULL** — the agent's CURRENT ns plus any ns in the per-agent `::full-source`
  presence-set. Real full file source, unclipped. `full? ⇔ (nm = current-ns ∧
  ::current-full?) ∨ (nm ∈ ::full-source)` — ONE rule, no second full control.
- **COMPACT CARD** — every namespace the current namespace really requires plus
  every symbol explicitly present in `::compact`, unless full selection wins.
  `render-one-ns-compact` emits reader-commented schema records plus every
  public function as one inert `fn full.ns/name [args] — "doc." —
  :malli/schema …` signature. There are no pseudo-definitions or fake body
  tokens for an agent to echo as code, and runtime predicate objects become
  readable placeholders. Cards are about 3–5× smaller than full source.
- **DROPPED** — everything else. Still indexed (`:seon.ns/name` + `:seon.fn` /
  `:seon.schema` rows), queryable through `my.ns/functions`, and selectable
  through `my.ns/full!` or `my.ns/compact!`—just not resident in the section.

`*-test` namespaces are always excluded. `.internal` namespaces stay hidden
from compact selection but may be revealed by an exact full pin; selecting a
parent never broadens to its internal descendants.

## Inclusion is database data

The include set is `current ns ∪ its :requires ∪ ::compact ∪ ::full-source`.
Full selection wins if a symbol appears in both presence-sets. There is no
`:always` render allow-list, `compact-worthy?` predicate, namespace-to-density
map, or hardcoded `my.*` pinning. Write a real `(:require [x …])` and `x` joins
as a card; drop the require and it vanishes unless explicitly selected. This is
self-healing on real `:seon.ns/require-edges` without fake dependencies.

The DEFAULT function surface is therefore a CONFIG concern:
`:seon.eval/home-requires` in the manifest (`config/system.edn`
`:seon.config/agent-context` + `:seon.config/root-context`) is what a fresh
agent's home ns requires, so it IS what renders as cards. Root additionally gets
`[seon.agent :as agent]`; the default toolbelt is `message` / `todo` /
`lifecycle` / `schema` / `db` / `my.kb` / `my.data` / `my.ui` / `my.canvas` /
`search`.

## Per-agent dials (config-driven-agent-init)

Five attrs on the `:namespaces` BLOCK entity, read reactively via `resolve-cfg`
(block → Malli default; a `db/transact!` re-derives next render):

- `::compact` (`[:vector :seon.ns/name]`, default `[]`) — keep a namespace's
  public schema/function card visible without bodies.
- `::full-source` (`[:vector :seon.ns/name]`, default `[]`) — force a ns FULL.
- `::with-tests` (same) — append the ns's indexed `:seon.test/source`.
- `::current-full?` (`:boolean`, default true) — whether the current ns renders full.
- `::current-tests?` (`:boolean`, default true) — whether it shows its tests.

Presence = config. Ordinary operations keep `::compact` and `::full-source`
disjoint; direct conflicting data remains deterministic because full wins.
Cluster
`:seon.config/namespaces` has one distinct storage option,
`:seon.config/always`: it makes complete framework source available for later
selection but never decides what renders.

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
- **Echo-safe at the parser boundary.** The entire compact body routes through
  the shared context quoter. Parsing a copied card produces no eval forms or
  reader errors; names, signatures, doc line 1, and Malli contracts remain
  visible as documentation.

## Third-party override-proven (acme)

`config/acme.edn` sets its OWN `:seon.eval/home-requires` (a trimmed core subset
PLUS `acme.helpers` / `acme.notes`) and its OWN namespace source-storage set —
with ZERO `src/seon` edits, a fresh acme agent's card surface is acme's toolkit.
Load-bearing gotcha: a manifest that supplies `:seon.agent/ctx`
REPLACES the default block tree wholesale (malli default-fill only fills ABSENT
keys), so acme must re-list every block it wants to keep — including `:namespaces`
at priority 20 — or the section silently vanishes.

## The docstring convention (the seam)

A public fn's docstring first line is a complete, ≤72-char sentence (78 hard cap)
ending in terminal punctuation — the compact card shows ONLY that line; the rest
renders in full view only. Enforced by `seon.dev.docstring` (dev-hook warn). Full
rule + the corpus-cleanup sweep: [[compact-namespace-cards-spec]] +
`docs/conventions.md` "Function Docstrings".
