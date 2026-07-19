---
type: issue
status: resolved
severity: correctness
tags: [issue, database]
---

# Datahike query symbol values were treated as variables

## Problem

Datahike accepted `:db.type/symbol` values in schema and transactions, but both
query engines used broad `symbol?` checks in later planning and execution
stages. A symbol supplied through scalar `:in` was parsed and substituted
correctly, then treated as an unconstrained pattern variable. An indexed query
could return an unrelated entity, and a non-indexed query could omit its value
filter and scan the attribute.

The live agent lookup for missing symbol `my.browser.direct-probe` therefore
returned an existing agent and exceeded Seon's 64-result work budget. Clearing
the result cache reproduced the same wrong result, proving that cache identity
was not the cause.

## Owner

The maintained Datahike analyzer, planned executor, and relational query
engine. Seon's schema, query, and resource budget remain unchanged.

## Resolution

Maintained Datahike commit `6611de27` makes the analyzer the single owner of
free, blank, and ground pattern classification. Planning, estimation, planned
execution, and relational execution now distinguish `?variable` and `_blank`
symbols from ordinary symbol values. Function, rule, source, and pattern
positions retain their grammar-specific handling; this is not a global symbol
rewrite.

The upgraded cache/query regression transacts both indexed identity and
non-indexed `:db.type/symbol` attributes. It proves known, missing, repeated,
planned, relational, persistent-set, and hitchhiker-tree results agree. The
focused query-cache plus planner gate passes 148 tests and 868 assertions, and
the complete ClojureScript Node gate passes 138 tests and 951 assertions.

Live Seon query, work-budget, agent-creation, and browser proof follows the
coordinated rebuild against this maintained revision.
