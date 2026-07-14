---
type: decision
status: active
date: 2026-02-20
tags: [decision, architecture, schema, cljs]
---

# ADR-007: Always-on runtime instrumentation

## Context

Public function schemas are executable contracts. Agent-authored code and core
code run in the same long-lived CLJS pod, so input, output, and arity violations
must surface at the call boundary rather than corrupt later state.

## Decision

Every public function with a wrappable `:malli/schema` contract is instrumented
from the committed program graph. One runtime reconstruction installs the
complete validated program once. A later definition or schema publication
uninstruments the recorded original and instruments only the changed definition
plus transitive schema dependents.

Structural exceptions are computed from function shape, never a name list or
ad hoc metadata waiver. An async shape that cannot be wrapped validates at its
own boundary and returns an error envelope. Agent birth, resume, render, and
config application perform no global instrumentation pass.

A program candidate publishes only after its registry, declarations, and
wrappers validate as one unit. Failure rejects publication, records one bounded
`:seon.error/fault :core`, and fails readiness/admission according to the core
error policy. Detailed coverage is an on-demand diagnostic; no per-render
instrumentation census enters root context.

## Consequences

- Wrong schemas are runtime bugs, not documentation warnings.
- The active Malli projection remains one immutable authority; Malli's mutable
  collector is not a competing runtime registry.
- Hot reload uses Shadow's actual Node reload selection and reapplies the same
  delta rule.
- The emergency kill switch is recovery-only and does not define normal
  semantics.
- Instrumentation errors become structured values at agent boundaries and core
  faults at publication/readiness boundaries.

## Related

- [[data-model]] — schema authority and error facts.
- [[agent-runtime]] — program publication transition.
- [[context]] — explicit injected dependencies.
- [[observability]] — bounded core-fault diagnosis.
- [[library-grounding]] — Malli source read map.
