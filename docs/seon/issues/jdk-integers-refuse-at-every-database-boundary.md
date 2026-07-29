---
type: issue
status: open
severity: friction
tags: [issue, database, architecture]
---

# JDK Integers refuse at every database boundary

Datahike's `:db.type/long` is exactly `java.lang.Long`; any JDK API
returning `Integer` (`HttpResponse.statusCode()`, sizes, counts) refuses
the whole transaction if a call site forgets `(long …)`. This has now
bitten at least three times (the standing memory rule; the 2026-07-29
`:seon.ai/http-status` blocker fixed at its call site in `ff9dde36e`).
Per-site coercion is whack-a-mole — each new JDK integration re-arms it.

## Dissolution

ONE coercion at the one boundary every write crosses: integral values
narrower than Long coerce to Long where tx-data is prepared (the
Malli→Datahike bridge's value path or the `seon.db` transact entry —
judge the honest owner; it must be the choke point, not a helper callers
may skip). The `ff9dde36e` per-site fix then deletes in favor of the
boundary rule; one regression proves an `Integer` anywhere in tx-data
commits.

## Acceptance

A transaction containing a JVM `Integer` under a `:db.type/long`
attribute commits; the per-site `(long …)` coercions are removed; the
class is unrepresentable and this issue + the memory scar both close.

## Triage 2026-07-29

**REAL-BUT-QUEUED — database transaction normalization.** Current write paths
still rely on per-site `long` coercion, but the known live HTTP-status site is
fixed and no active corruption is present. The natural owner is the eventual
single tx-data normalization boundary.
