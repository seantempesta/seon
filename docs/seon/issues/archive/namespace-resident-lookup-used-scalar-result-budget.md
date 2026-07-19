---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database]
---

# Namespace resident lookup used scalar result budget

## Problem

The first live `delegate!` call for `my.graduation.orders` failed before birth
with `datahike query-results budget exceeded`: the resident join allowed one
result node because its final find shape was scalar, but Datahike charged its
second intermediate relation row. The namespace-existence query repeated the
same invalid assumption.

## Resolution

Both point lookups retain one scalar final result and a 4 KiB result-weight
limit, while allowing 64 bounded intermediate result nodes. Focused
multi-agent proof passes 7 tests/55 assertions and covers the absent-resident
and existing-resident branches;
the live namespace-targeted call supplies the cold-path regression.

## Acceptance

- An absent namespace creates one resident and initial message atomically.
- An existing namespace resident receives the message without another birth.
- Concurrent absent-resident calls converge on one unique namespace ref.
