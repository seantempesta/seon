---
type: issue
status: open
severity: blocker
created: 2026-09-23
tags: [publication, schema, clojure]
---

# Cold publication merges mixed identities into a sorted map

At HEAD `ed6540c17`, own-root `bin/seon --root tmp/one-jvm-redesign-root init`
failed after 115,988 ms with `Keyword cannot be cast to Symbol`. No source edits
from this follow-up were present. The stack enters `Symbol.compareTo`,
`PersistentTreeMap.assoc`, Clojure `merge`, then
`seon.schema/projection-registry` (`src/seon/schema.clj:478`) through
`seon.fn/add-contract-facts` (`src/seon/fn.clj:2473`).

`projection-registry` merges schema forms and function contracts using
`(merge forms contracts)` when retained entries are empty. That operation
retains the first map's comparator; keyword schema keys and symbol function
identities cannot share Clojure's default sorted-map comparison. Verify both
input maps at that seam before selecting the correction. This was observed
in cold publication, not inferred from a unit-test failure.

[Raw operator output](../../prds/steward-platform/research/one-jvm-slice4-activation-before-2026-09-23.txt).

Acceptance: build a projection from the canonical declaration population plus
function contracts regardless of the input map's ordering; cold publication
must reach its committed source result. Preserve the identities and use
ordinary map semantics where ordering is not part of the contract.
