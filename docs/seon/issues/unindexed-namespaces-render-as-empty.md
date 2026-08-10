---
type: issue
status: open
severity: friction
tags: [issue, render, context, honesty]
---

# Do not tell an agent an unindexed namespace is empty

## Problem

The namespace renderer turns "this cluster holds no `:seon.fn` facts for
this namespace" into the statement "no definitions yet". For a namespace we
deliberately do not index — `clojure.string`, `clojure.edn` — that statement
is false, and it is false in the one place an agent trusts most: its own
context.

An agent reading it would conclude `clojure.string` is unusable. Root's very
next forms in the same captured context call `str/includes?` and
`str/starts-with?`.

## Evidence

Producer: `src/seon/render/ns.clj:341-345` (`empty-comment`), reached from
`compact-ai-text` at `:424-425` when both `functions` and `own-schemas` are
empty.

Verbatim from root's live `:seon.render/ai` context, default cluster
pid 31570, 2026-08-10:

```text
;; d2 · [:seon.ns/name clojure.edn]
(ns clojure.edn)

;; no definitions yet.
;; d2 · [:seon.ns/name clojure.string]
(ns clojure.string)

;; no definitions yet.
```

The cost is small (39 estimated tokens) — the defect is the false claim, not
the size. A docstring or render that claims something untrue poisons the
agent that reads it.

Full measurement:
[context quality audit 2026-08-10](../../prds/sci-execution-runtime/research/context-quality-audit-2026-08-10.md),
finding 6.

## Owner

`seon.render.ns` owns both projections of one namespace representation.

## Acceptance

A namespace with no indexed members says exactly that, distinguishing
absence of facts from absence of definitions, or is not rendered as a walk
unit at all. No rendered namespace unit asserts emptiness it has not
established. One regression covers a namespace that is required but not
first-party-indexed.
