---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, test-fixture, instrumentation]
---

# Fault injection drops an existing callable arity

The Stage 2 expanded fast run (`tmp/stage2-resolution-sixth-current-fast.log`)
reached `live-init-reloads-schema-runtime-and-moved-predicate-owners-before-admission`
and refused with `Wrong number of args (2)` instead of the intended
`:capability-without-request`. The fixture replaces
`seon.program/canonical-row` with a one-argument function, although the owner
also exposes the two-argument supplied-shapes arity (`src/seon/program.cljc`).
Publication now uses that arity.

The fixture correction preserves both arities and applies the same deliberate
call-fact removal in each. Expanded fast verification is pending; no cold
proof is claimed. This is the same class as the earlier
[lifecycle-arity injection defect](archive/fault-injection-replaces-the-procs-lifecycle-arities.md).
It is not evidence against admitted-identity test resolution.
