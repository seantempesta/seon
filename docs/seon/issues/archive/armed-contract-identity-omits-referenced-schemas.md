---
type: issue
status: resolved
severity: friction
tags: [issue, instrumentation, schema, class/n3]
---

# Host wrapper identity must include referenced canonical declarations

## Problem

The authored-schema comparison added by 5ffc491ae preserves a host wrapper
when a referenced canonical schema changes without changing the function's
authored form. Its unscoped validator retains the declarations captured when
the wrapper was armed.

## Resolution — 2026-09-16

Commit 98b5f2afe captures the transitive canonical definitions through the
projection's dependency graph and Malli's direct-ref reader, records their
contract digest, and compares the captured definitions at apply!/arm-var!.
No second arming path or global freshness registry was introduced.

The canonical regression
seon.instrument-test/referenced-contract-changes-rearm-only-dependent-wrappers
passes eight assertions in default (runs 49938 before file edits, 50142 after
hot reload): a declaration changed behind an alias replaces its wrapper,
accepts the new value, rejects an invalid value, and preserves an unrelated
wrapper and repeat-arming identity. The existing authored-contract regression
also passes seven assertions (50143).

Default's armed seon.eval/of-agent returns ten Juniper evaluations, including
three renderer refs with :db/id. Full development adoption and the batched
gate remain pending; these are explicitly host-hot-reload proofs.

The assigned web-debug refusal was independently traced to its selector
omitting :db/id, not this freshness defect. Its remaining observable is
tracked in
[the fixture issue](../web-debug-fixture-transactions-and-budget-expectations-fail.md).
The broader N3 class remains open for its other owners. Complete evidence and
gate request: [landing note](../../../prds/steward-platform/research/arming-includes-referenced-schemas-2026-09-16.md).

## Cold-worker correction — 2026-09-16

Commit eeafb9dba fixes the cold arming regression triggered by 98b5f2afe:
direct-references passed an omitted packaged-projection predicate map as
nil to compilable-form. It now supplies {} for absence, as the existing
compiled-wrapper path already did. The constructor emitted no nil key.
A fresh thread with zero bindings arms successfully and derives the same
digest as the bound case. The new cold regression passes 5 assertions;
all 27 instrument-test tests pass 132 assertions in-process on default,
including the original class regression's 8 assertions. Exact run facts
and the independent publication boundary are in the landing note above.
