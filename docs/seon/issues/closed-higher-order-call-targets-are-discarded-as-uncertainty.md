---
type: issue
status: open
severity: major
tags: [issue, runtime, schema]
---

# Closed higher-order call targets are discarded as uncertainty

## Problem

`seon.program.edge/analyze-function` computes the exact, statically closed
target set for a var passed as a value (`(map thumbnail ids)`), then throws it
away and records only the `:value-passed-pattern` uncertainty. The target
symbol never reaches `:seon.program.edge/calls`, so the reverse question "is
anyone using this function?" answers no for every higher-order use.

Two shapes are worse than lossy — they are silent. A var referenced as a bare
value, and a var inside a map/vector/set literal, produce neither a call edge
nor an uncertainty.

This contradicts the chunk's own design contract:
`docs/prds/sci-execution-runtime/research/execution-planning-design-2026-07-23.md:38`
requires the analyzer to "Resolve ordinary direct calls, qualified calls,
lexical aliases, and statically closed higher-order targets."

## Evidence

Measured with `edge/analyze-function` directly (JVM, `-M:writer`), resolution
carrying `thumbnail` in `::edge/current-vars`:

| Source form | `::calls` | `::uncertainties` |
|---|---|---|
| `(thumbnail p)` | `["my.photos/thumbnail"]` | `[]` |
| `(let [g thumbnail] (g p))` | `["my.photos/thumbnail"]` | `[]` |
| `(map thumbnail ids)` | `["clojure.core/map"]` | `[:value-passed-pattern]` |
| `(partial thumbnail 1)` | `["clojure.core/partial"]` | `[:value-passed-pattern]` |
| `thumbnail` (returned) | `[]` | `[]` |
| `{:render thumbnail}` | `[]` | `[]` |

Root cause, three sites in `src/seon/program/edge.cljc`:

- `argument-uncertainties` (L371-377) calls `closed-targets`, which returns the
  exact set `#{my.photos/thumbnail}`, and uses only its set-ness to add an
  uncertainty. The computed set is dropped.
- `walk-expression` (L413-419) on a symbol that resolves returns `state`
  unchanged — a resolved var reference contributes nothing.
- `walk-expression` (L421) `(not (seq? form)) state` — map, vector and set
  literals are never descended into, so a var inside a literal collection is
  invisible even in argument position, e.g. `(register! {:render thumbnail})`.

## Consequence

- The reverse call-graph query over `:seon.program.edge/calls` under-reports
  callers, so a change-impact check ("who breaks if I redefine this?") returns
  a false negative for every higher-order user.
- For placement, the first two rows fail closed correctly (`:value-passed-pattern`
  makes `plan-execution` emit an unresolved edge, `plan.cljc:466-471`), but the
  last two rows fail OPEN: no edge and no uncertainty means a function that
  hands a capability-calling var to another tier plans as `:anywhere`.

## Acceptance criteria

- `(map thumbnail ids)` records `my.photos/thumbnail` in `::calls` and drops
  `:value-passed-pattern` for that argument, since the target set is closed.
- A var inside a literal collection in argument position records its edge.
- An open target (a parameter, an `::open` local) still records
  `:open-higher-order` / `:value-passed-pattern` and still fails closed.
- One regression in `test/seon/program_edge_test.cljc` per shape class, not per
  example.

## Owner

The execution-planning unit that owns `seon.program.edge`.
