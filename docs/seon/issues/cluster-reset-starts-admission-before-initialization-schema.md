---
type: issue
status: open
severity: blocker
tags: [issue, operator, database, flow]
---

# Make cluster reset stop before explicit apply

## Problem

`bin/seon cluster reset default` deletes the database and then launches ordinary
startup. The startup start gate attempts to acquire the applied initialization
identity before the identity attribute has been installed, so reset fails
instead of leaving a fresh database ready for explicit apply.

## Evidence

- On 2026-07-24, a source-frozen timed reset failed after 74.15 seconds.
- The pod fault was `Applied identity acquisition failed.`
- The database error was `Lookup ref attribute should be marked as :db/unique:
  [:seon.db.initialization/id "database"]`.
- After the failed reset was cleaned with `bin/seon down`, the explicit
  `bin/seon cluster apply default` initialized the same fresh database
  successfully in 37.41 seconds.

## Owner

The reset/apply sequencing in the `bin/seon` operator. Initialization pages and
explicit cluster apply own first-database creation; ordinary start-gate
admission must not query an identity whose schema cannot yet exist.

## Acceptance

- A fresh `bin/seon cluster reset default` completes without launching ordinary
  runtime admission against the empty database.
- The following explicit `bin/seon cluster apply default` owns initialization
  and succeeds.
- No lookup or schema validation is weakened.
