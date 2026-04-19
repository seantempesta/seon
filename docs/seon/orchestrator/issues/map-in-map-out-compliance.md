---
type: issue
status: open
severity: friction
milestone: M2
tags: [issue, schema]
---
# Map-In Map-Out Compliance

## Problem

Gemini review consistently flags functions using positional args in public APIs. The convention is that every public function takes one map and returns one map, with all keys namespaced. Many public functions still use positional arguments, making them inconsistent with the codebase convention and harder for agents to discover and use.

## Where

- Codebase-wide — needs a systematic audit
- `ai/datalevin.clj` is a known offender

## Acceptance Criteria

- All public functions follow map-in/map-out convention
- Functions that genuinely need positional args are made private
- No regressions in tests
- Gemini review stops flagging positional-arg violations

## Related

- [[components/code-graph]]
