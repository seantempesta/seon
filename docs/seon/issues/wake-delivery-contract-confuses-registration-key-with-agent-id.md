---
type: issue
status: fix implemented; recorded integration pending
severity: blocker
tags: [issue, wake, contract]
---

# Wake delivery contract confuses registration key with agent id

`route!` registers keyword `:seon.agent/route`; `deliver!` required a string
agent id at that parameter. Armed execution refused before the mailbox offer.
The repair contracts the argument as the existing registration key and retains
that key in undeliverable-route diagnostics. Datahike accepts opaque keys.

The canonical-fixture regression checks an actual listened message datom,
mailbox delivery, render delivery and absence of a contract fault. Admission
and result recording are unavailable through the stale published-base path;
see [the landing note](../../prds/agent-platform/landing/lane-turn-parks-on-boot-2026-09-22.md)
for exact execution evidence and remaining integration requirements.
