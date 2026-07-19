---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, milestone]
---

# Score reachability from real context transitions

## Problem

The reachability scorer expected database wrappers, rendered wrapper maps, and
captured operation trees that `/agents/run` does not produce. Production does
retain the final database value, rendered/eval transactions, successful eval
sources, and later prompts derived from the database. The scorer therefore
rejected the real interface while passing fixture-only evidence.

## Acceptance

- Every rendered and eval transaction is at or before the final database
  value's basis transaction and belongs to the request's turns.
- Root child creation joins one successful `start!`, the child ID in later
  database-derived context, and a later explicit query over its ID, purpose,
  and parent.
- Namespace discovery proves the requested move and later function definitions
  in context.
- Skill load/unload proves the body appears after load and disappears after
  unload.
- Missing transitions, reordered evidence, failed calls, or a stale final
  database value fail closed.
- A live reachability row passes without synthetic operation evidence.

## Current evidence

The implementation and fixtures now use only production fields. The shared
tagged operation decoder and wrapper checks are deleted; 114 combined
reachability/milestone/solver tests pass. Live proof remains the archival gate.
