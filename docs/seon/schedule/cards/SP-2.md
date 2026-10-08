---
title: "Card SP-2 The writer refuses stored text over the blob threshold"
type: reference
status: active
created: 2026-10-08
---

# SP-2 The writer refuses stored text over the blob threshold

Design: `docs/research/agent-platform/2026-10-08/storage-point/storage-point.md` §2. Lane: Opus (checked writer,
boot-critical).

Every stored-text attribute is declared with one registered schema whose property names the dial; the checked writer
refuses an asserted value whose UTF-8 bytes exceed the dial on the database the write leaves. A step that re-copies a
full text into storage then fails at its source. The rule is a schema property the writer checks, read the way the
writer already reads `:seon.config.db/validation-node-limit` (`src/seon/db.clj:5411-5443`) and derives
`retention-rules` from `:seon.db/append-only-after` (`db.clj:5445-5455`); not a Malli `:fn` (it sees only the value,
so it would need an ambient database, and an inline predicate is an anonymous contract function) and not a literal
`:max` (a hardcoded number).

## Files

`resources/seon/schemas/seon.blob.edn`, `resources/seon/schemas/seon.config.eval.result.edn`, `config/default.edn`
(`:107`), `resources/seon/schemas/seon.db.write.edn`, `src/seon/db.clj`, and the attribute declarations below:
`resources/seon/schemas/seon.error.edn` (`:shown`, `:message`), `seon.error.occurrence.edn` (`:message`),
`seon.turn.edn` (`:reply`, `:134`), `seon.test.failure.edn` (`:message`, `:expected`, `:actual`);
`test/seon/db_write_text_bound_test.clj` (new).

## Functions

- SCHEMA `seon.blob.edn`: add
  `:inline-text [:string {:seon.db/max-bytes-dial :seon.config.eval.result/blob-threshold :description "Text stored inline; over the dial it is a blob digest and size (seon.blob/inline-or-stage)."}]`.
- SCHEMA `seon.config.eval.result.edn`: `:blob-threshold [:int {:min 1 :seon.config/dial true :seon.config/default 4096}]`
  (the default moves onto the declaration, as `seon.config.blob.edn`'s dials hold theirs); delete
  `:seon.config.eval.result/blob-threshold 4096` from `config/default.edn:107`.
- SCHEMA `seon.db.write.edn`: add the refusal variant
  `:seon.db.write/text-over-bound [:and {:seon.db/attributes false} :seon.error/base [:map [:seon.db/attribute :qualified-keyword] [:seon.blob/size :seon.blob/size] [:seon.db.write/bound-dial :qualified-keyword] [:seon.db.write/bound :int]]]`
  with `:seon.db.write/bound-dial :qualified-keyword` and `:seon.db.write/bound [:int {:min 1}]` declared beside it.
- ADD `seon.db/inline-text-bounds` (private)
  `{:malli/schema [:=> [:cat :seon.schema/projection] [:map-of :qualified-keyword :qualified-keyword]]}` `[projection]`:
  `(schema/projection-cache-value projection ::inline-text-bounds (fn [] ...))` over
  `(:seon.schema.projection/forms projection)`: for each attribute key whose
  `(m/properties (m/deref-all (mr/schema registry k)))` holds `:seon.db/max-bytes-dial`, the entry attribute → dial.
  Once per projection.
- ADD `seon.db/text-bound-error` (private)
  `{:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report] [:or :nil :seon.db/error-result]]}`
  `[projection report]`: for each datom in `(:tx-data report)` with `:added`, whose `:a` (attribute ident) is in
  `inline-text-bounds` and whose `:v` is a string, compare `(seon.blob/utf8-size v)` with
  `(get projection dial)`; the first over the bound returns
  `(seon.error.refusal/diagnostic at :seon.db/database-write 'seon.db/transact! {:seon.error/type :seon.db.write/text-over-bound :seon.error/message "Stored text is over the blob threshold; store it with seon.blob/inline-or-stage." :seon.db/attribute a :seon.blob/size n :seon.db.write/bound-dial dial :seon.db.write/bound b})`.
  When the projection carries no value for the dial, nothing is checked (no configuration yet means no evaluation has
  written text).
- CHANGE `validate-write-report` (`db.clj:5411-5443`): read each dial in `(set (vals (inline-text-bounds projection)))`
  from the database it leaves exactly as the node limit is read (`d/datoms database :aevt dial`, minimum over
  values, else `(:seon.config/default (m/properties (mr/schema registry dial)))`) and `assoc` it onto the projection
  passed to `write-report-error`.
- CHANGE `write-report-error` (`db.clj:5324`): add `(text-bound-error projection report)` to its `or` chain after
  `retention-report-check`.
- SCHEMA declarations: `:seon.error/shown`, `:seon.error/message`, `:seon.error.occurrence/message`,
  `:seon.turn/reply` (keep its `:seon.db/no-history?` and context-inert properties with
  `[:and {...} :seon.blob/inline-text]`), `:seon.test.failure/message`, `/expected`, `/actual` become
  `:seon.blob/inline-text`.

## Proof

`seon.db-write-text-bound-test/an-oversized-stored-text-is-refused-at-the-writer` (new): on a `test-support`
database, `seon.db/transact!` of `{:seon.test.failure/id "x" :seon.test.failure/message (apply str (repeat 4097 "a"))}`
(plus the entity's required members, built through the test failure writer's own helper) returns an error whose
`:seon.error/type` is `:seon.db.write/text-over-bound`, `:seon.db/attribute` `:seon.test.failure/message`,
`:seon.blob/size` 4097, `:seon.db.write/bound` 4096; the same write with 4096 characters commits.
`seon.db-write-text-bound-test/the-bound-follows-the-dial` (new): after setting the cluster's
`:seon.config.eval.result/blob-threshold` to 8192 through `seon.config` apply, the 4097-character write commits.

## Other tests reaching it

`seon.blob-threshold-test`, `seon.error-result-test`, `seon.error-storage-bytes-test`, `seon.db-test`,
`seon.db-transact-shape-test`, `seon.test.one-request-test`, `seon.cluster.turn-test`.

## Cost

Per write: one set lookup per asserted datom and a byte count of covered strings (proportional to the bytes the
write already carries), one AEVT read per dial (one dial). Attribute set derived once per projection. Time the
`seon.db-transact-shape-test` probe on parent and change and report both.

## Deleted

The threshold's default line in `config/default.edn` (moved onto its declaration).

## Commit

`bin/lane commit <lane> -m "SP-2: the writer refuses stored text over the blob threshold" --test seon.db-write-text-bound-test seon.blob-threshold-test seon.error-result-test seon.error-storage-bytes-test seon.db-test seon.db-transact-shape-test seon.test.one-request-test <every file above>`

## Dependencies

`src/seon/db.clj` is held by lane `led-cacheb`; `resources/seon/schemas/seon.blob.edn` by `blob-konserve`;
`seon.error.edn` by SP-1a/SP-1b: start after all three release. Lands before SR-C, SR-F, SP-3's effect follow-up,
SP-4 and SR-EFFECT, each of which declares its own attribute `:seon.blob/inline-text` in the same commit that makes
its producer stage over the threshold. Schema and writer change: published and adopted on a branch of the live store
by `bin/lane commit`; root restarts default after landing.

## Reset

None.
