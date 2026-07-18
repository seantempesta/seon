---
type: issue
status: closed
severity: blocker
tags: [issue, database, web, cljs]
---

# Root agent count confused scalar output with query budget

## Evidence

The root gzip feed returned a visible `datahike query-results budget exceeded`
card on a populated 30-agent database. The agent/context pull succeeded at its
existing allowance. Running the scalar count query with its production limits
reported 18 retained query-result nodes against an allowance of 16, even
though its semantic output was the single number 30.

## Resolution

The existing scalar query remains the one owner. Its finite `max-results`
allowance now budgets Datahike's retained matching relation independently of
the scalar output shape. No renderer, feed, query, or cache path was added.
The affected test checkpoint passes 59 tests and 362 assertions.

After a clean supervised restart, a retrying gzip SSE client reconnected and
received a complete healthy `#app-view` containing the 30-agent system header
and root canvas with no render error.

## Acceptance

- The root view renders on the populated database.
- The scalar count retains one semantic output.
- Its query resource allowance covers the bounded matching relation.
- Gzip feed reconnect returns a complete healthy patch after restart.
