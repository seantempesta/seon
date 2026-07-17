---
type: issue
status: resolved
tags:
  - database
  - startup
  - llm
---

# LLM config pull used an entity count as a retained-node budget

## Failure

Startup pulled one LLM configuration entity with
`:datahike.resource/max-results 1`. Datahike pull charges that budget for each
retained result node, not for each root entity, so an ordinary multi-attribute
configuration exceeded the budget. Startup remained available by design, but
provider configuration failed to synchronize and logged a core error.

## Resolution

The bounded singleton pull now allows 100,000 units of query work, 256 retained
result nodes, and one MiB of shallow result weight. The selector remains the
closed configuration attribute set, and the surrounding `execute-many` frame
keeps its independent four-MiB transport bound.

## Acceptance

- The focused AI synchronization test proves the explicit resource bounds.
- A clean supervised start synchronizes or cleanly no-ops without a Datahike
  resource-budget error.
