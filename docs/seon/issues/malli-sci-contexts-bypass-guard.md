---
type: issue
status: active
severity: blocker
tags: [issue, runtime, schema]
---

# Malli constructs unguarded SCI contexts for schema code

## Evidence (containment audit 2026-07-23, verified)

Malli dynamically creates/forks independent SCI contexts to evaluate
schema code ([:fn] predicates etc.) that use none of Seon's guard
holder, policy, output caps, or eval pool. Agent-authored predicate
schemas therefore evaluate outside the one door. Citations:
research/sci-containment-surface-audit-2026-07-23.md (High #4, §4).

## Direction

One door law: Malli's sci-options must thread Seon's guarded
context/holder (the fork owns malli — reference-code/malli — so the
sci-options seam can accept the guarded ctx). Compose with R33 corpus
predicate compilation and P4 admission.

## Acceptance

- A hostile [:fn] predicate halts by interpreter-step budget with the
  steering value during schema validation.
- No SCI context construction site outside the audited one-builder
  inventory.
