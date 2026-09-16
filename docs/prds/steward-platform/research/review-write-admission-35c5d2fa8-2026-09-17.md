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

## Addendum — `b1508dc8a` reviewed (orchestrator, 2026-09-16 20:55Z)

Read in full: the diff to `src/seon/db.clj`, `src/seon/fn.clj`, both test
files, and the landing note's repair section.

**Approved.** The root fix is the right one: the final-report validator was
handing each expanded member of a cardinality-many attribute to
`write-value`, which implements Datahike's *submission* lookup-ref heuristic,
so a stored set of two keywords became one nested set and failed `[:set …]`.
`write-entity-error` now rebuilds the declared set/vector from the resolved
EAVT values and never treats a resolved collection as transaction syntax.
The refusal now carries attribute, offending value and entity through
`require-committed!` into the exception message, which is the "flat refusal
travels" requirement. The tombstone validator is derived from the same
authored `:seon.program/row-schema` / `:seon.program/written-by` properties
the indexer uses; a bare new identity is refused, removing one required
definition fact is refused, and a retired row validates — that is validation
of the real retired-row shape (ruling 47), not a grammar exception.
Regressions run on the canonical fixture through the real writer and assert
unchanged `:max-tx` on refusal.

**What it exposed next (fixed by the orchestrator, `b023e93a9`).** With the
validator no longer refusing, a complete publication ran past the operator's
180 s bound three times. `jstack 53320` showed the writer inside
`seon.cluster.source/identity-ref` → `seon.schema.edn/packaged-forms` →
`read-schema-resource` (a full EDN parse of every schema resource) once per
evidence ref, under seven nested `retry-with-tempid` frames. Law 2.1 defect
(fetch at call time inside the transaction). Forms are now acquired once in
`preserved-evidence-tx`. The lane was stopped after its commit; its scope
question is answered by that fix.

**Still open from this slice:** the recorder writing test entities without
`:seon.schema.admission/source` (batch 105's refusal at `[47871 …]`) is not
addressed by `b1508dc8a`; it is proven or refuted by the first cold gate on
the converged base (batch 107 first).
