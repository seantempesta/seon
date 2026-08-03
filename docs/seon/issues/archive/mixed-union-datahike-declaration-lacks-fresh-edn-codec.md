---
type: issue
status: resolved
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

- `src/seon/schema/datahike.clj:135-150,268-298,322-468` derives the string
  fallback, recognizes wrapped heterogeneous unions, encodes canonical EDN,
  and decodes plus validates the logical value.
- `src/seon/db.clj:177-378,445-487,563-612` now applies that one decoder to
  query, pull, pull-many, entity, and datom results where the attribute is
  known. There is no render-specific read path.
- The fail-first production-read regression returned the string
  `"example.render/ai"` where it expected the qualified symbol
  `example.render/ai`; the final regression covers both literal arms and two
  mixed populations (`test/seon/db_test.clj:39-96`).
- Malformed EDN and a decoded value outside the registered union return a flat
  `:seon.db/invalid-read` with the decoder's exact rule
  (`test/seon/db_test.clj:98-116`).
- The complete source and live evidence is recorded at
  `docs/prds/sci-execution-runtime/research/union-codec-2026-08-03.md`.

## Blast radius

| Population | Every affected attribute |
|---|---|
| Current canonical database attributes | None. An independent derivation over all 667 registered schema forms returned `:canonical-fallback-attributes []`. |
| Runtime regression attributes | `:seon.db-test/ai-declaration`, `:seon.db-test/html-declaration`. |
| Current render attributes | None. `:seon.render/ai` and `:seon.render/html` alias the native symbol-only `:seon.render/projection` declaration (`resources/seon/schema.edn:360-368,2305-2321,2363-2369`); literals are runtime-only (`resources/seon/schema.edn:2323-2338`). |

Four registered collection value schemas have heterogeneous stored children
but are not database attributes: `:seon.render/surfaces`,
`:seon.render.walk/branch`, `:seon.render.walk/path`, and
`:seon.store/transaction-data`. Persisting one would require an owner decision
about element-wise cardinality-many encoding; this repair changes none of
their behavior.

## Owner

The one `seon.db` read boundary and `seon.schema.datahike` codec, composed with
the render-contract model decision. No caller-specific decode and no attribute
roster.

## Acceptance

- Complete. One derived normalization path covers pull, entity, query, and
  datom projections where the attribute is known
  (`src/seon/db.clj:177-378,445-487,563-612`).
- Complete. A qualified symbol, literal prose, and literal Hiccup round-trip
  through production writes and reads (`test/seon/db_test.clj:39-96`).
- Complete. Malformed and schema-invalid storage becomes one flat read error
  (`test/seon/db_test.clj:98-116`).
- Superseded premise. Current durable render declarations are qualified symbols
  only; the synthetic mixed unions prove the general codec without changing
  the render contract (`resources/seon/schema.edn:360-368,2305-2338`).
- Complete. The fresh isolated `union-codec` cluster observed raw Datahike
  strings and logical `[Symbol String]` results through `seon.db/q`, then was
  taken down. Exact values and the hot-reload qualification are in
  `docs/prds/sci-execution-runtime/research/union-codec-2026-08-03.md`.

## Resolution

Resolved by commit `b2f6ab14d`. The fail-first run was 11 tests / 50
assertions / 1 failure; the final focused gate was 12 / 60 / 0, and the
affected namespaces were 24 / 97 / 0. `bin/issues-index --check` was clean
before archival.

## History

The previous issue was first filed when the declaration bridge existed without
either codec half. It was archived after `encode-transaction` and
`decode-attribute-value` plus a manual round-trip test landed. The 2026-08-02
render vocabulary audit reopened it because the claimed application-read
integration does not exist.
