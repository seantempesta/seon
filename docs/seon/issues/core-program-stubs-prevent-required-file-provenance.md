---
type: issue
status: open
severity: blocker
tags: [issue, schema, program-graph, provenance]
---

# Core program stubs prevent requiring file provenance

The Tier 1 inventory's file requirement is conditional on `:core` provenance,
not applicable to agent definitions. At the 2026-09-17 integration HEAD,
`seon.fn/desired-rows` still manufactures external function identities and
stamps the entire population `:core` (`src/seon/fn.clj`, `desired-rows`). These
are not indexed definitions and have no file. Requiring file on every core
function now refuses canonical population.

Read-only default probe, 2026-09-17, 13 ms, complete returned envelope:

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))
      names (seon.db/q '[:find [?name ...]
                        :where [?f :seon.fn/sym ?name]
                        [?f :seon.schema.admission/source :core]
                        (not [?f :seon.fn/file])] database)]
  {:count (count names) :examples (vec (take 8 (sort names)))})
```

Result: **802**. Examples include `babashka.fs/absolutize`,
`babashka.fs/create-sym-link`, and clj-kondo unknown-namespace observations.
The reset's value-edge seam removes these invented target identities (G1/G2).
Do not exempt arbitrary core rows from the file requirement to retain them.

The canonical fixture helper `test/seon/test_support.clj/program-fn-row`
also creates core rows without files. It is concurrently held, so the
integrator did not edit it. Its writer must supply genuine indexed provenance,
or use the actual agent-definition constructor when modeling an agent row;
merely relabeling an indexed row is not a fix.

Required completion: remove external stubs with the edge retype; enforce file
presence for core definitions while preserving agent evaluation provenance;
fix canonical constructors in the same publication; prove file retraction
refuses and names surviving declarations. Tracked in
[the reset batch](../../prds/steward-platform/plan/reset-batch-2026-09-17.md).
