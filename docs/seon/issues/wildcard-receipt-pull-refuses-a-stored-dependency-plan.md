---
type: issue
status: open
severity: blocker
tags: [issue, database, schema, class/n13, wave/database-codec]
---

# Make wildcard receipt pulls accept stored read dependency plans

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
