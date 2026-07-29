---
type: issue
status: resolved
severity: blocker
tags: [issue, rendering, agent]
---

# Namespace walk reintroduced absent overrides as nil

## Failure

`seon.render.agent/namespace-ai` honestly omitted its optional
`:seon.render/overrides` request key, but `seon.render.walk/neighborhood`
unconditionally rebuilt the downstream resolution map with that key. Its nil
value violated the registered `:seon.render/overrides` map schema before the
agent's namespace could render.

## Resolution

`neighborhood` now preserves the request's omission through projection
resolution. A present override map still rides every hop unchanged; an absent
map stays absent instead of becoming nil.

## Evidence

- `bin/test seon.render.agent-test`: 6 tests, 22 assertions, zero failures or
  errors.
- `namespace-ai-renders-without-overrides-test` renders an otherwise idle agent
  whose unit carries no override map.
