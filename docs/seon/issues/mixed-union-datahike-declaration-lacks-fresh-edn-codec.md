---
type: issue
status: open
tags: [issue, database, schema]
severity: blocker
---

# Mixed-union Datahike declaration lacks the fresh EDN codec

## Decision

The `:db.type/string` fallback for a heterogeneous Malli `:or` is intentional,
not a silent degradation. The quarry stored the logical union value as readable
EDN in one Datahike string.

`src-old/seon/db/internal.cljc` supplies both halves of that contract:

- `edn-encoded-attr?` recognizes a mixed union whose derived storage type is
  `:db.type/string`;
- `encode-edn-slot-values` applies `pr-str` before transport; and
- `validation-value` applies `read-string` before validating the logical value.

The fresh `seon.schema.datahike` bridge retained the declaration rule but the
fresh transaction path has not yet acquired the matching encode/decode owner.
The bridge now comments this dependency at the fallback.

## Probe

```clojure
(schema/register! ::mixed [:or :string :int])
(schema.datahike/malli->datahike-attr ::mixed)
;; => {:db/ident ::mixed,
;;     :db/valueType :db.type/string,
;;     :db/cardinality :db.cardinality/one}
```

The focused bridge test locks the deliberate declaration rule. It does not
claim that fresh transaction round-tripping of mixed logical values exists.

## Owner

The B2 schema-EDN transaction boundary; `seon.schema.datahike` remains the
single declaration bridge.

## Acceptance

- One fresh transaction normalization owner derives EDN-slot status from the
  registered schema, without a name list.
- Logical mixed-union values are encoded exactly once before Datahike and
  decoded exactly once before logical validation or application reads.
- A real heterogeneous value round-trips through an in-memory Datahike
  connection and malformed EDN or a schema-invalid decoded value refuses
  loudly.
- The issue closes only when the fresh transaction boundary, not this pure
  declaration bridge, owns that proof.
