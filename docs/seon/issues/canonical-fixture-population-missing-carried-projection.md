---
type: issue
status: open
severity: friction
tags: [issue, test, schema, wave/test-fixture]
---

# Canonical fixture population refuses its schema transaction in default

## Problem

The live canonical fixture cannot reach a test body after the coalesced
development adoption observed during the batch-3 reaching-tests repair.
This is a fixture-acquisition boundary, not an assertion failure.

## Evidence

On 2026-09-15, default pid 69622 returned this from the direct JVM probe
`(seon.test-support/with-database (fn [_] :fixture-ready))`:

```clojure
{:seon.boot/refused true
 :seon.boot/offense
 {:seon.boot/population :seon.schema/declarations
  :seon.boot/result
  {:seon.error/kind :seon.schema/missing-projection
   :seon.error/message "This operation requires a carried schema projection."
   :seon.error/data {:seon.db/operation seon.db/transact!
                     :seon.schema/missing-projection true}}}}
```

Before that adoption, the corrected reconciliation regression passed 14
assertions in this JVM. Afterward, the new empty-check regression and the
same reconciliation regression could not acquire their fixture. The
reaching API itself still returned the complete empty result in 1.80625 ms.
The stack names `seon.cluster/accrete-schema-population!` calling
`seon.db/transact!` while constructing the canonical base. No causal
attribution to another lane's edit has been established.

## Owner

The canonical fixture population and database projection-custody seam.
`test/seon/test_support.clj` was concurrently edited; this lane did not alter
it or another lane's sessions.

## Acceptance

The direct canonical-fixture probe reaches its body under armed contracts,
and the two namespaces in the reaching-tests gate request run on the same
fixture. Verify both fresh acquisition and the live development JVM.
