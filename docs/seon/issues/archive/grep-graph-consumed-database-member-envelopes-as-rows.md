---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, cljs]
---

# Grep graph consumed database member envelopes as rows

## Problem

Live agent `violet-emus-create` called `seon.agent.search/grep-graph` while
checking a function published by another child. `seon.db/execute-many` returned
one typed member envelope per graph query, but `graph-search` passed those
envelopes directly to the row transformers. Map iteration treated
`:datahike.query/result` as a schema member, and runtime instrumentation
correctly rejected the response because `:seon.agent.search/member` promises a
string. The replaceable execution child exited after persisting the core fault.

## Resolution

`graph-search` now requires every member to report
`:seon.db.protocol/success? true`, unwraps each member's query result, and only
then builds graph hits. Member failure becomes the existing error envelope.
The focused graph fixture now returns the real database protocol shape rather
than an impossible vector of bare rows.

## Acceptance

- Graph search transforms only unwrapped query rows.
- A failed database member returns an ordinary search error value.
- The complete focused search, execution, and host matrix passes.
- A live execution child can call `grep-graph` without a core fault.
