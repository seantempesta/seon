---
type: issue
status: resolved
severity: friction
tags: [issue, database, agent]
---

# Recent message pull used row limit for nested values

## Problem

The recent-message readers bounded message entity IDs before `pull-many`, then
also passed that entity limit as `:seon.db/max-results`. Datahike pull results
include retained nested values such as the message sender and recipient, so the
writer correctly charged more results than the number of requested entities
and rejected otherwise bounded reads.

## Resolution

The existing reverse index reads remain the message-count bound. The following
`pull-many` calls retain their byte-weight bound and no longer reinterpret the
number of message entities as a limit on nested result values.

## Evidence

Focused message and plan proof passes 35 tests and 142 assertions. Both message
reader tests assert the bounded pull request omits `:seon.db/max-results`.
