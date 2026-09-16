---
type: research
status: reviewed
created: 2026-09-17
tags: [review, orchestrator, seon.db, write-admission, datahike-fork, F2]
---

# Orchestrator review — write admission (Seon `35c5d2fa8`, fork `73afe782`)

Read: the research note and its options (accepted option 2 earlier today),
the Seon diff stat, the `src/seon/db.clj` hunks (removal of the
pre-transaction entity-schema pass; `write-entity-value`,
`write-entity-error`, the validator installed at connection open with the
connection's projection), the fork commit stat
(`src/datahike/db/transaction.cljc` +24, the API spec, an 81-line test).

**Accepted.**
- The pre-read entity validation (`write-map-error`'s whole-map pass against
  `(d/db connection)`) is deleted; the cheap per-attribute pre-validation
  stays as an early refusal and is no longer the only validation for any
  grammar.
- One optional callback in the fork runs after expansion and before the
  writer accepts the report; absent, Datahike behaves exactly as before
  (the fork's own suite: 94 tests / 614 assertions green). The Seon
  validator receives the report and reads each affected identity-bearing
  entity's resulting datoms from `:db-after` (`write-entity-value`, EAVT,
  multivalued attributes as sets), validates against the entity schemas its
  identities select, and rejects the whole transaction with a flat
  `:seon.error` naming the entity identities, the key and the offending
  value. Nothing partial is written; `:max-tx` is unchanged on refusal (the
  regression checks it).
- The projection is handed to the validator at connection open (values
  carry their world), never fetched per transaction.
- Regressions cover: one-attribute update of an existing entity succeeds;
  a creation missing a required key is refused with nothing written; a
  `[:db/retract …]` that would leave an identity-bearing entity incomplete
  is refused; a mixed transaction writes nothing; the refusal names entity,
  key and value; a nested `:db.fn/call` whose output completes the entity is
  accepted (composition survives) and the function ran once.

**Boundary accepted:** the in-process Seon proofs were refused because
default's adopted program lacked `:seon.fn/destroys` facts (the shared
unconverged adoption); the fork suite ran; the dependency cache was rebuilt.
The cold gate is the proof.

**For the owner:** the fork commit `73afe782` is on this laptop only until
pushed, with the other Datahike fork commits.

**Gate requested:** batch 107 = platform, then `seon.db-test seon.schema-test
seon.maintenance-schema-test seon.turn-test`.
