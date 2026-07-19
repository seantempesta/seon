---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, architecture]
---

# Decode persisted agent values before incomplete-agent recovery

## Problem

Incomplete-agent recovery passed a raw pulled agent entity to the transaction
projection. `:seon.eval/home-requires` is stored in a mixed-union EDN string
slot, so recovery iterated that string as require specs and projected
individual characters as `:seon.ns.require/target` values. Strict symbol
validation then correctly rejected initial root birth.

## Owner

The agent-entity database acquisition boundary in `seon.agent`.

## Acceptance

- Pulled agent entities decode mixed-union values once before recovery.
- Initial-root and historical missing-namespace repair project symbol require
  targets from the persisted vector.
- Namespace reassignment uses the same decode boundary.
- The namespace and require-target schemas remain symbol-only.

## Resolution

Resolved by decoding pulled agent entities through one helper before known-id
creation recovery, root reconciliation, missing-namespace repair, and
namespace reassignment. The persistence-shaped regression passed 15 tests and
96 assertions in `seon.agent.multiagent-test`, including exact symbol-edge and
rendered-source checks for the incomplete-root and historical-agent paths.
