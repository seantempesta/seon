---
type: issue
status: resolved
tags: [issue, database, schema]
severity: blocker
---

# Schema alias hid Datahike attribute properties

## Evidence

The R0 bridge was quarried from `src-old/seon/db/internal.cljc` with this
inherited asymmetry:

- value type and cardinality used the recursively resolved Malli form; but
- `attr-form-properties` inspected the raw registered form.

A bare alias keyword has no property map. An attribute aliased to
`[:set {:seon.db/index true :seon.db/no-history? true} :string]` therefore
derived the correct `:db.cardinality/many` but silently omitted `:db/index` and
`:db/noHistory`.

## Probe

```clojure
(schema/register! ::string-set
                  [:set {:seon.db/index true
                         :seon.db/no-history? true}
                   :string])
(schema/register! ::string-set-alias ::string-set)
(schema/register! ::tags ::string-set-alias)
(schema.datahike/malli->datahike-attr ::tags)
;; Before:
;; {:db/ident ::tags,
;;  :db/valueType :db.type/string,
;;  :db/cardinality :db.cardinality/many}
```

This also falsifies the narrower cardinality suspicion: the existing call
already passed `(resolve-malli-form raw)` to `form->cardinality`.

## Resolution

Resolved by `f92525b1e`: `malli->datahike-attr` now resolves the raw definition once and derives the
child value form, cardinality, and persistence properties from that same
resolved form. The one regression covers a two-alias collection chain and
asserts its value type, cardinality, index, and no-history facets together.

The focused test must pass through `bin/test seon.schema.datahike-test`; the
full `bin/test` gate remains the integration proof.
