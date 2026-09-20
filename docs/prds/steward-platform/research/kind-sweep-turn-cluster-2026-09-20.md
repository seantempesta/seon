---
type: research
status: active
created: 2026-09-20
tags: [error-model, kind-retirement, turn, cluster]
---

# Kind sweep — turn-cluster landing note

## Result so far

The tooling blocker is fixed at `510a9236d9`: JVM-mode exception projection
returns a declared facet carrying its message, exception-class symbol, and
first Seon frame. Its kind-free fallback names the offending value class.
The process-identity refusal facet landed at `61c5c5eb88`.

This note remains active: the whole family is not yet kind-free.

## Source counts

| File | Before | Current |
|---|---:|---:|
| `src/seon/turn.clj` | 72 | 68 |
| `src/seon/cluster.clj` | 33 | 30 |
| `src/seon/cluster/message.clj` | 17 | 17 |
| `src/seon/cluster/agent.clj` | 17 | 17 |
| `src/seon/cluster/prompt.clj` | 10 | 10 |
| `src/seon/cluster/source.clj` | 8 | 6 |
| `src/seon/cluster/wake.clj` | 5 | 5 |
| `src/seon/agent.clj` | 13 | 11 |
| `src/seon/cluster/process.clj` | 1 | 0 |

Counts are literal `:seon.error/kind` occurrences. Changes in other rows came
from concurrently landed HEAD work; this lane's committed reductions are the
MCP exception sites and `seon.cluster.process`.

## Facets declared

- `:seon.dev.mcp/jvm-exception-error` requires exception class and frame.
- `:seon.dev.mcp/projection-failed-error` requires the offending class.
- `:seon.cluster.process/start-instant-unavailable-error` requires the PID.

No new inline base-three checks or callee debt landed.

## Verification boundaries

- `seon.cluster.mcp-test`: 12 executed, 0 unchanged, 59 assertions; 5 failures
  and 1 error. Both exception regressions passed. Foreign boundaries were the
  retired-kind assertion, three render-window expectations after `f5716e841`,
  and a configuration reconciliation refusal before the database-backed
  artifact scenario reached its body.
- `seon.cluster.boot-test`: the first lifecycle test produced no reporter
  progress for 320 seconds. The watchdog placed the main thread in Datahike
  projection derivation (`seon.schema/projection-from-rows`); exit 124.
- The required HEAD load after both commits printed `:loads`.

## Stop conditions

No PRD section 6 semantic stop condition has been reached. This is an active,
incomplete sweep, not a stopped design decision.

## Cold gate owed

The orchestrator still owes the path-limited turn/cluster cold gate and
`bin/test --platform` after the family is kind-free.
