---
type: issue
status: open
severity: friction
tags: [issue, database, query, test]
---

# A heterogeneous identity query refuses during reach acquisition

Test-system run `5c57d018183e`, 2026-09-23, failed the canonical row-parity
regression through `seon.db/q` with this attempted query:

```clojure
[:find ?entity ?attribute ?value
 :in $ [[?identity ?token]] ?attributes
 :where [?entity ?identity ?token]
        [?entity ?attribute ?value]
        [(contains? ?attributes ?attribute)]]
```

The walk starts from `seon.id/id`,
`seon.id-test/data-shape-and-explicit-length-determine-identity`, and schema
key `:seon.fn/calls`, then follows calls/schema references. Its identity pairs
mix `:seon.fn/sym`, `:seon.test/sym` and `:seon.schema/key`.
The complete refusal was `:seon.db/invalid-read`, operation `:seon.db/q`,
exception class `java.lang.NullPointerException`, message
`Cannot read field "sym" because "o" is null`. It is retained in
`tmp/test-system-reach-refusal.edn`. The exact failing hop was not captured;
no planner, comparator or decoding cause is established by this observation.

Grouping tokens by identity attribute, selecting entity ids, then reading
those EAVT ranges passes the same row-parity regression. That is the shipped
reach query, not a swallowed refusal. Acceptance for this open query class:
reproduce the combined form on the canonical fixture, identify the dependency
seam, and either return the same facts or a substantive supported-query refusal.
