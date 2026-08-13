---
type: issue
status: open
severity: friction
tags: [issue, sci, class/n13, wave/verification-audit]
---

# Preserve Inst semantics when a value is also collection-like

## Problem

The admission hot-path optimization moved the generic `inst?` case below
collection classification. An object implementing both `clojure.core/Inst`
and `java.util.Collection` is now projected as a vector instead of normalized
to `java.util.Date`. The comment calls the protocol fallback an exotic leaf,
but the protocol has no leaf-only constraint.

## Evidence

- `src/seon/sci/admit.clj:233-238` special-cases Date and Instant.
- `src/seon/sci/admit.clj:281-294` classifies every Java collection before the
  remaining `Inst` implementations.
- `test/seon/sci/admit_test.clj:247-257` covers Date, Instant, and a reified
  Inst that implements no overlapping interface; it cannot falsify ordering.
- `tmp/audit-20260801b/src/inst_collection_probe.clj` constructed a real proxy
  implementing both interfaces. The probe reported
  `{:inst? true :java-collection? true :projected [1 2]}` rather than the
  pre-change `#inst` value at epoch millisecond 43.

## Owner

The total value projection in `seon.sci.admit`.

## Acceptance

- The owner rules explicitly whether `Inst` or structural collection semantics
  win for an overlapping implementation and preserves that rule in one test.
- If Inst wins, the common Date/Instant fast paths remain allocation-conscious
  without changing the protocol's semantics.
- The totality and admission-cap properties remain green.
