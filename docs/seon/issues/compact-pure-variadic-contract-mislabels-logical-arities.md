---
type: issue
status: open
severity: friction
tags: [issue, agent, schema]
---

# Render logical Malli arities for pure-variadic functions

## Problem

The compact namespace projection misstates pure-variadic implementations that
publish several logical Malli arities. `callable-contract` pairs the one source
arglist, such as `[& call-args]`, with logical input schemas by vector position.
It therefore renders the preferred request-map calls for `seon.db/query` and
`seon.db/transact!` as variadic positional calls and loses the logical argument
name.

This affects every consumer of the shared callable projection, including
required-namespace cards, menus, `my.ns/functions`, and autocomplete. It is not
a database-task prompt problem and should not be repaired with prose or an
example special-cased to those functions.

## Dependency ledger

- Projection owner: `src/seon/agent/ctx/namespaces.cljs`, especially
  `callable-contract` and its compact render consumers.
- Function owners: `src/seon/db.cljs`; `query` and `transact!` use pure-
  variadic implementation bodies while their Malli schemas define several
  logical call shapes.
- Program facts: stored source arglists and function Malli schemas remain the
  one database-derived authority; no second registry is needed.
- Preserved evidence:
  `docs/prds/agentic-tool-refinement/research/qwen25-coder-05b-database-diagnostic-2026-07-15.md`.

## Evidence

The retained turn-zero prompt renders:

```clojure
; fn seon.db/transact! — positional [& :seon.db/transact-request] ...
; fn seon.db/query — positional [& [:or :seon.db/query-request
;                                   :seon.db/query-form]] ...
```

The prompt contains the request schemas, but these callable lines imply that
the request map is a repeated positional argument. The preferred one-map call
shape should instead be derived from its Malli input schema. The prompt bytes
are SHA-256 `c3626b3a1a564618cc89f8f53b8be34410fcddbd78ec27db530fea67f1075d6e`.

## Acceptance criteria

- A pure-variadic implementation with multiple logical Malli inputs renders
  every logical call shape from those input schemas without `&` or the private
  accumulator name.
- Ordinary fixed, multi-arity, mixed fixed/variadic, and genuinely variadic
  functions retain their current correct contracts.
- Namespace cards, menus, `my.ns/functions`, and autocomplete consume the same
  corrected structured projection.
- Focused tests assert structured contracts and semantic rendered fragments,
  not a complete prompt snapshot.
- A live ACME REPL render shows correct `query` and `transact!` request-map
  calls from one immutable database value.

## Scheduling

Capture the first admitted P0 database sample before changing this projection,
so the baseline remains interpretable. Then fix the one shared owner and rerun
that exact sample before broad context or model comparisons.
