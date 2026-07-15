---
type: issue
status: resolved
severity: blocker
tags: [issue, flow, agent]
---

# Decode historical containment records before clean shutdown

## Problem

Per-generation shutdown policy made containment grace and terminal trigger
required in the current process contract. Existing ACME process records and
their truthful terminal output predated both fields. Strict current validation
therefore wedged `status`, `down`, and `restart`; after the old pod drained, the
new matcher still rejected its exact generation/status/exit result because the
trigger was absent.

## Owner

`seon.dev.process` owns the one managed-record decoder, strict publisher,
terminal matcher, and normalized shutdown evidence. Attribute absence is the
historical-shape signal; no state-file rewrite or second migration path exists.

## Acceptance

- Reading an otherwise valid containment record without grace derives the
  historical 2,500 ms control value.
- Publishing that same absent field remains invalid.
- An exact old terminal result without trigger completes cleanup while returned
  terminal evidence preserves the trigger's absence.
- The real stale ACME target drains through `bin/acme down` without manual PID
  signaling or record deletion.

## Resolution

The read boundary derives only the missing historical grace. The matcher accepts
the exact older terminal shape, and normalization conditionally includes a
trigger only when the helper actually observed one. The focused process
namespace passes 34 tests and 175 assertions; the stale ACME pod drained and
the target reached down through its ordinary supervisor.
