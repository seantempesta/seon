---
title: "Card SP-3 Shell output and web bodies take the one threshold"
type: reference
status: active
created: 2026-10-08
---

# SP-3 Shell output and web bodies take the one threshold

Design: `docs/research/agent-platform/2026-10-08/storage-point/storage-point.md` §1 (rows
`:seon.config.shell/inline-output-bytes`, `:seon.config.web/max-inline-bytes`). Supersedes the shell and web half of
ledger row SR-T. Lane: Sonnet.

`seon.blob/stage-binary!` (`src/seon/blob.clj:272-334`) already decides by `:seon.config.eval.result/blob-threshold`:
content at or under it is kept whole in memory as `:seon.blob/staged-octets` with no staging file; over it,
`:seon.blob/staged-path` names the staging file. Shell (`output-descriptor`, `src/seon/shell/jvm.clj:303-322`) and web
(`capture-body!`, `src/seon/web/jvm.clj:193-208`) each decide a second time by their own dial (4,096 and 8,192).
Both read the staged write's decision instead, and their dials are deleted.

## Files

`src/seon/shell/jvm.clj`, `src/seon/web/jvm.clj`, `resources/seon/schemas/seon.config.shell.edn`,
`resources/seon/schemas/seon.config.web.edn`, `config/default.edn` (`:226`, `:383`), `test/seon/shell/jvm_test.clj`
(`:74`, `:236`), `test/seon/web/jvm_test.clj` (`:189`, `:412`, `:450-458`).

## Functions

- CHANGE `seon.shell.jvm/output-descriptor` (`:303-322`): `inline?` is `(contains? captured :seon.blob/staged-octets)`;
  delete `inline-limit`. The preview branch (`preview-limit`, `:my.shell.output/preview`) is unchanged.
- CHANGE `seon.web.jvm/capture-body!` (`:193-208`): drop the `max-inline` parameter; always
  `(blob/stage-binary! connection bounded)`; when `force-blob?` is false and the staged write holds
  `:seon.blob/staged-octets`, return `{:seon.web.jvm/body (inline-body octets content-type) :seon.web.jvm/octet-array octets}`
  with no staged write; else the blob body and `:seon.blob/staged-write` as today. When `force-blob?` is true and the
  content is under the threshold the staged write (holding its octets) is published as today. Convert both callers
  (`:328`, `:415-418`) and `fetch`'s `max-inline` binding (`:284`).
- SCHEMA: delete `:inline-output-bytes` from `seon.config.shell.edn` and `:max-inline-bytes` from
  `seon.config.web.edn`; delete their lines in `config/default.edn`.
- TESTS: fixtures that set the deleted dials to 8 to force the blob path set
  `:seon.config.eval.result/blob-threshold 8` instead (the same fixture map).

## Proof

`seon.shell.jvm-test/binary-output-is-byte-exact-on-both-sides-of-the-inline-ceiling` (existing, fixture switched to
`blob-threshold 8`): the 7-byte output is inline, the 9-byte output is a blob, both byte-exact.
`seon.web.jvm-test/oversized-bodies-spill-byte-exactly-through-the-blob-tier` (existing, same switch).
`seon.web.jvm-test/a-body-between-the-old-ceilings-is-a-blob` (new): with the shipped threshold 4,096, a 6,000-byte
response body is returned with `:my.web.body/blob` and `:my.web.body/bytes` 6000 and no `:my.web.body/text`.

## Other tests reaching it

`seon.shell.jvm-test`, `seon.web.jvm-test`, `seon.config-test`.

## Cost

Web: one capture pass instead of a prefix read then a staging pass; shell unchanged (one comparison). Same stored
form for the same size across shell, web, error text, replies and test failures.

## Deleted

`:seon.config.shell/inline-output-bytes`, `:seon.config.web/max-inline-bytes`, web's second size decision.

## Commit

`bin/lane commit <lane> -m "SP-3: shell output and web bodies take the one threshold" --test seon.shell.jvm-test seon.web.jvm-test seon.config-test src/seon/shell/jvm.clj src/seon/web/jvm.clj resources/seon/schemas/seon.config.shell.edn resources/seon/schemas/seon.config.web.edn config/default.edn test/seon/shell/jvm_test.clj test/seon/web/jvm_test.clj`

## Dependencies

None: every file is unheld on 2026-10-08 (`bin/lane status`). `config/default.edn` is also touched by SP-2 (`:107`):
serialize by claim. The values they return are stored by the effect writer; SR-EFFECT makes that store once.

## Reset

None.
