---
type: issue
status: resolved
tags: [issue, database, web, flow]
---

# Agent view query budgets masked database errors

## Failure

The execution child's agent-page acquisition assigned
`:datahike.resource/max-results 1` to both a scalar agent-count query and a full
configuration pull. Datahike charges retained result nodes, not root entities.
The count query retained four nodes and the configuration pull retained an
ordinary multi-attribute tree, so the authority correctly returned a resource
error. The UI host then passed that error map to the page formatter, which
obscured the database error with a secondary `name` failure.

## Resolution

The count query now has a 16-node bound, based on live resource evidence showing
four retained nodes. The complete configuration pull has a 4,096-node and
64-KiB shallow-weight bound. The selector and grouped transport weight remain
bounded. A child-returned database error is rendered directly and never enters
the ordinary page formatter.

## Verification

- Focused execution-runtime and Datastar proof passes 22 tests and 89
  assertions without warnings.
- A live count query reports result count 4, work 5, result weight 8, and a
  successful Datahike cache miss-owner insertion under the new bound.
- After a clean supervised restart, the root feed returns a complete valid
  Datastar element containing the system header, agent grid, canvas, plan, and
  transcript surfaces. The fresh pod log contains no query-budget or render
  error.
