---
type: issue
status: open
tags: [issue, schema]
---
# 10 keyword namespace prefixes have no backing code namespace

## Problem

The convention is: every keyword namespace prefix corresponds to a real `.clj` file. Ten entity schema prefixes violate this — they're registered in other namespaces but use prefixes that don't exist as code.

## Violations

| Prefix | Registered in | Entity type |
|--------|--------------|-------------|
| `seon.fn` | `graph/ingest.clj` | Code graph function |
| `seon.call` | `graph/ingest.clj` | Code graph call |
| `seon.spec` | `graph/ingest.clj` | Code graph spec |
| `seon.var` | `graph/ingest.clj` | Code graph var |
| `seon.ns` | `graph/ingest.clj` | Code graph namespace |
| `seon.ns.dep` | `graph/ingest.clj` | Code graph ns-dep |
| `seon.agent.run` | `runtime.clj` | Agent run tracking |
| `seon.flow.snap` | `runtime.clj` | Flow snapshots |
| `seon.agent` | `ctx.clj` | Agent context data |
| `seon.reactive` | `web/reactive/actions.clj` | Reactive actions |

These keywords are used extensively — `seon.fn/*` and `seon.spec/*` alone have 50-60+ references across the graph, render, and namespace lifecycle code.

## Design Question

Two options:

1. **Create owning namespaces** — e.g., `src/seon/fn.clj` holds schema registrations for `:seon.fn/*` keys. Schema lives with the namespace that owns it.
2. **Amend convention** — allow "entity namespace" prefixes for DB schemas that represent data types, not code. Document the exception.

## File Refs

- `src/seon/graph/ingest.clj` — 6 entity prefixes registered here
- `src/seon/runtime.clj` — `seon.agent.run/*`, `seon.flow.snap/*`
- `src/seon/ctx.clj` — `seon.agent/*`
- `src/seon/web/reactive/actions.clj` — `seon.reactive/*`

## Severity

design
