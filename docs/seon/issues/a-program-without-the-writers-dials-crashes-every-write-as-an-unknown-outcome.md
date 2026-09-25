---
type: issue
status: open
created: 2026-09-25
owner: seon.db (src/seon/db.clj)
found-by: lane fix-custody-round4-opus, registry-test red (tmp/orchestrator/verify-1955.out.d/001.txt)
---

# A program without the writer's dials crashes every write as an unknown outcome

`seon.db/transact!` reads two dials from the carried projection of the value it writes: its
write bound and its validation bound. When a database declares a program (it has
`:seon.schema/key` rows), that projection holds only the declared rows. If those rows omit
`:seon.config.db/write-time-limit-ms`, every later write on that store and its branches fails.
It fails with Malli's `:malli.core/invalid-schema {:schema nil :form nil}`, carries
`:seon.db/transaction-outcome-unknown true`, and does not name the missing dial.

## Evidence (default JVM, 2026-09-25, disposable file stores under tmp/fix-custody-round4-opus/)

- **The failing store.** It holds the registry-test fixture's three declaration rows:
  `:seon.blob/digest` plus two probe blob attributes.
  - `declares-program?` is true, and `carried-projection` has 3 forms.
  - `(mr/schema registry :seon.config.db/write-time-limit-ms)` gives nil.
  - The next write fails at `transact-call`'s
    `(m/properties (mr/schema registry bound-attribute))` (db.clj:4546), with exactly the
    offending shape above.
- **With the bound declared, the next dial fails.** Adding the write bound gives
  "Complete component validation refused: missing-validation-bound; bound
  :seon.config.db/validation-node-limit=". This refusal is named, from db.clj:3623.
- **With the whole `seon.config.db` family declared, the write commits.** The registry-test
  fixture now declares the family.

## Wanted

- An absent write bound in the value's declared program should refuse by name, like the
  validation bound does. It is not an outcome-unknown crash: the transaction was never
  queued, so its outcome is known.
- Better, a declared program that lacks the writer's dials should be refused when its
  declaration rows are written. At that point the missing members can be named, and the store
  never reaches a state where every later write fails.

The registry-test fixture change (declaring the `seon.config.db` family) is a correct world.
It does not fix this writer diagnostic.
