---
type: issue
status: open
severity: friction
tags: [issue, schema, runtime, agent]
---

# Instrument report omits Malli spell-checking

## Problem

The instrumentation error projection humanizes Malli explanations but does not
apply `malli.error/with-spell-checking`. R31 requires the instrument report and
the new schema-admission gates to emit spell-checked, bounded steering rather
than raw explain walls.

The existing custom `hint-for` remains useful for same-name,
wrong-namespace keys. It does not replace Malli's structural extra-key
classification, and the two paths need one deliberate composition order.

## Evidence

- `src/seon/error/instrument.cljc:231-260` computes `exp` with `m/explain` and
  passes it directly to `me/humanize`.
- The namespace contains no call to `me/with-spell-checking`.
- `docs/seon/issues/archive/dual-code-paths-registry.md:128-131` records that
  Malli's threshold did not catch the same-name, wrong-namespace case in an
  earlier probe; the current `hint-for` was retained for that case.
- R31's accepted research now explicitly requires
  `with-spell-checking` in the instrument report path while keeping steering
  bounded through `seon.error.sci/steering-head`.

## Owner

`seon.error.instrument` and its focused JVM/CLJS instrumentation tests. Compose
Malli spell-checking before humanization, retain the existing wrong-namespace
fallback where Malli produces no likely match, and keep the full explanation
as structured data.

## Acceptance

- Instrument input/output explanations pass through
  `me/with-spell-checking` and then `me/humanize`.
- A closed-map input with a near miss emits a bounded “did you mean” steering
  headline and preserves the structured explain data.
- The existing same-name, wrong-namespace regression remains green.
- No raw unbounded explain wall reaches the agent-facing message.
