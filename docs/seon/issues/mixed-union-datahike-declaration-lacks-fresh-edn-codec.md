---
type: issue
status: open
tags: [issue, database, schema, render]
severity: blocker
---

# Complete mixed-union decoding at application reads

## Problem

Heterogeneous Malli unions are encoded as EDN strings before Datahike, but
ordinary application reads return those storage strings without decoding. A
logical qualified-symbol producer therefore returns from a pull as text and
the render router treats it as literal output instead of invoking it.

This issue was archived as resolved after the encoder and decoder helpers
landed, but the acceptance claim that decoding occurs before application reads
was never wired into production.

## Evidence

- `src/seon/schema/datahike.clj:135-150,268-378` derives the string fallback,
  recognizes heterogeneous unions, and encodes their logical values.
- `src/seon/schema/datahike.clj:432-466` defines the corresponding decoder.
  Repository-wide source search finds no production caller of either decoder.
- `src/seon/db.clj:489` encodes transactions, while its read functions return
  ordinary Datahike results without calling the decoder.
- `test/seon/cluster/store_transact_test.clj:143-155` manually calls
  `decode-attribute-value`; it proves the helper, not transparent application
  reads.
- A read-only probe on 2026-08-02 showed the intended logical render unions
  `[:or :string :qualified-symbol]` and
  `[:or :seon.render/hiccup :qualified-symbol]` both derive
  `:db.type/string` and report EDN-backed storage. Without read decoding, a
  stored renderer symbol such as `demo/render-html` becomes the string
  `"demo/render-html"` at the router and silently changes behavior.

## Owner

The one `seon.db` read boundary and `seon.schema.datahike` codec, composed with
the render-contract model decision. No caller-specific decode and no attribute
roster.

## Acceptance

- One derived read normalization path decodes every EDN-backed attribute before
  logical validation or application use, including pull, entity, query, and
  datom projections where the attribute is known.
- A qualified symbol and a literal value each round-trip through a real
  in-memory Datahike connection via production write and read functions.
- Malformed EDN and schema-invalid decoded values refuse loudly at the one
  boundary.
- A stored render producer remains a qualified symbol after the production
  read and is invoked; literal prose/Hiccup remains literal.
- The issue closes only after a fresh isolated cluster exercises the real read
  path; a test that manually calls the decoder is insufficient.

## History

The previous issue was first filed when the declaration bridge existed without
either codec half. It was archived after `encode-transaction` and
`decode-attribute-value` plus a manual round-trip test landed. The 2026-08-02
render vocabulary audit reopened it because the claimed application-read
integration does not exist.
