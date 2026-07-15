---
type: issue
status: resolved
severity: blocker
tags: [issue, database, web, architecture]
---

# Database browser page rejected its resolved temporal database

## Problem

Cursor Slice A made `index-page` call `seon.db/head-coordinate` on every
supplied database value to compare it with the request coordinate. Slice B
correctly resolves a retained cursor through `seon.db/at-coordinate`, which
returns an as-of database. An as-of value deliberately cannot derive the
complete containing commit identity through `head-coordinate`, so the page
boundary rejected the exact temporal value the public resolver supplied.

## Resolution

Resolved on 2026-07-15 by making the public boundary explicit: callers supply
the database value already resolved to the complete coordinate. The web
adapter obtains every retained value through `seon.db/at-coordinate` before
opening its frozen feed. `index-page` still compares the request coordinate
with the coordinate sealed into an opaque cursor before any index read, but no
longer asks a temporal database to reconstruct commit metadata it does not
own. Focused database tests preserve mismatched-coordinate rejection and
immutable old-point replay after the live database advances.
