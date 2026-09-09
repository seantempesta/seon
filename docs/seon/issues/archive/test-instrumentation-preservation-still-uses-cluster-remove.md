---
type: issue
status: resolved
severity: blocker
tags: [issue, test, runtime]
---

# Instrumentation preservation still uses cluster removal as fixture teardown

2026-09-08 snapshot platform run: 80 tests / 443 assertions, 2 failures.
The registry lane fixed the schema caller-diagnostic failure. The remaining
failure is `test/seon/test_support_test.clj:74`, which asserts zero wrappers
after `instrument/remove!`. That assertion requests the 949-wrapper removal
defect the assignment retires. `test/seon/test_support.clj:631` similarly calls
production removal in its preservation fixture, leaving newly armed Vars behind.
Both files had concurrent owner edits and were preserved by the registry lane.

Fixture teardown must directly restore callable roots: unwrap current wrappers
using Malli `mi/-f->original`, then restore the entering roots and saved Malli
function-schema atom. Its regression should unwrap an entering root directly,
throw, and verify exact restoration; production removal must preserve wrappers.
The registry and instrument tests already follow this ownership rule.

Evidence and exact protected handoffs:
[registry landing](../../../prds/context-generation/research/cluster-scoped-registry-landing-2026-09-08.md).

## Resolution — 2026-09-09 issues sweep

Fixed by `2531b2e70`: the preservation fixture unwraps through Malli and
restores the exact entering callable roots and function-schema registry.
The owning [support restoration landing](../../../prds/context-generation/research/support-restore-landing-2026-09-08.md)
records the class regression, scoped gate (9 tests / 82 assertions), and
platform gate (82 tests / 486 assertions), all green. The current fixture
retains that implementation. This note was left open after its fix.
