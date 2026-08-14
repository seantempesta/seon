---
type: issue
status: resolved
severity: blocker
tags: [issue, database, schema, class/n13, wave/database-codec]
---

# Canonicalize EDN-backed receipt read evidence

## Problem

Wildcard-pulling evaluation receipts from a completed live turn can return
`:seon.db/invalid-read` instead of receipts because the EDN-backed
`:datahike.read/dependency-plan` value is refused as noncanonical. A reader of
durable attempt evidence therefore cannot reliably inspect the terminal turn.

## Evidence

The instrumented scratch-root proof on 2026-08-12 reached the AI stub and
closed its run, but the test's terminal receipt query returned:

```clojure
{:seon.error/kind :seon.db/invalid-read
 :seon.error/message
 "The EDN-backed attribute :datahike.read/dependency-plan has an invalid logical value."
 :seon.error/data
 {:seon.db/operation :seon.db/q
  :seon.db/dependency-data
  {:seon.schema.datahike/rule :seon.schema.datahike/noncanonical-edn
   :seon.schema.datahike/attr :datahike.read/dependency-plan}}}
```

The failure appears only while decoding the wildcard-pulled receipt; it is
separate from prompt acquisition and fault committing, both of which passed
their live boundaries. The proof was narrowed to the requested attempt-ready
seam, and this terminal-read defect was not pursued further.

Drive 1 Attempt 2 reproduced the same codec rule on the other EDN-backed
receipt slot. Error fact `9f54bedb-7424-4857-b862-7fc4a8ab36c2` refused a
stored `:seon.db/read-request` containing the call-preparation query as
`:seon.schema.datahike/noncanonical-edn`. The write-side string began with a
namespace-map literal while the read-side recomputation did not.

## Owner

The EDN-backed transaction codec and read decoder in
`seon.schema.datahike`, exercised through `seon.db/q` wildcard pull decoding.

## Acceptance

- A dependency plan written by the transaction codec is accepted by the same
  codec after storage and readback, independent of map/set iteration order.
- Wildcard-pulling real evaluation receipts returns receipt data rather than
  `:seon.db/invalid-read`.
- One total codec regression proves every generated dependency plan
  round-trips through its canonical storage representation.

## Resolution

This was one database-codec class, not two attribute-specific defects. Both
`:datahike.read/dependency-plan` and `:seon.db/read-request` select the same
heterogeneous-union EDN fallback in `seon.schema.datahike`. Its one
`storage-string` seam used ambient `pr-str`: `*print-namespace-maps*` changed
the representation, and map/set iteration order changed it again. Decode
correctly required the stored string to equal the canonical re-encoding, so a
writer and reader with different ambient bindings refused a valid logical
value.

Commit `8ec96cbf1` fixes the cause at that one seam. Storage printing now binds
the complete readable print policy and recursively orders maps and sets before
printing. The strict reader and its noncanonical refusal are unchanged.

## Verification

The regression uses the production database fixture, `seon.db/transact!`, and
a wildcard `seon.db/pull` of real receipt read-evidence components. It stores
the exact Drive 1 call-preparation query under opposite
`*print-namespace-maps*` bindings and opposite array-map construction orders;
the paired dependency plans additionally use opposite sorted-set iteration
orders. The raw Datahike strings are identical and both wildcard pulls restore
the exact logical request and dependency plan.

Before the fix, that test reproducibly failed six assertions: both raw strings
differed and both wildcard pulls lacked decoded evidence. After the fix,
`bin/test seon.schema.datahike-test` passed **9 tests / 101 assertions** with
zero failures and zero errors. A fresh raw JVM seam probe changed both
`array-map-construction-independent?` and
`namespace-binding-independent?` from false to true.

There is one issue note for the class. The Drive 1 report is a completed
research record, not a second open issue, so resolution archives this note
without inventing another issue authority.
