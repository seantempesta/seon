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
limit, while using the existing 4,096-node agent-creation allowance for
bounded intermediate work. The earlier 64-node allowance failed again after
the program graph grew: a real browser `POST /agents` observed 66 intermediate
nodes and returned HTTP 500. The namespace-assignment path uses the same
allowance rather than retaining another scale-sensitive failure. Focused
multi-agent proof passes 13 tests/86 assertions and covers the absent-resident
and existing-resident branches; the live namespace-targeted call supplies the
cold-path regression.

## Acceptance

- An absent namespace creates one resident and initial message atomically.
- An existing namespace resident receives the message without another birth.
- Concurrent absent-resident calls converge on one unique namespace ref.
