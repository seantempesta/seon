---
type: issue
status: resolved
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

[Raw operator output](../../../prds/steward-platform/research/one-jvm-slice4-activation-before-2026-09-23.txt).

Acceptance: build a projection from the canonical declaration population plus
function contracts regardless of the input map's ordering; cold publication
must reach its committed source result. Preserve the identities and use
ordinary map semantics where ordering is not part of the contract.

## Correction and verification, test-system lane

Verified both inputs at `seon.fn/add-contract-facts`: schema forms are built
into a keyword-keyed sorted map, and function contracts into a symbol-keyed
sorted map. Both identities are legitimate. The correction is
`(merge {} forms contracts)` in `projection-registry`; the retained-root
reduction already starts with `{}`. No comparator is widened.

Ordering is explicit at the two compilation loops (`sort-by str`), and the
provider performs key lookup only. Malli's `lazy-registry` stores answers in
an ordinary map (`reference-code/malli/src/malli/registry.cljc:81`), and
`fast-registry` copies them to a Java HashMap (`:17`). Neither lookup contract
depends on the input map's order. The new canonical-fixture regression
compares every declaration and compiled form, and the complete registry key
set, against the projection acquired from indexed rows.

Fast run `1888eb5cccc3`: five tests, 30 assertions, zero failures/errors;
the new parity test took 691 ms. Cold
`bin/seon --root tmp/test-system-fork-root init` succeeded from an empty root,
exit 0, 254.81 s total, commit `6ab065e5-78f2-56c3-ad52-f69eaaf1eb01`.
Its 3333-schema/1527-function contract projection took 357 ms.
The root was downed (zero recorded JVMs, store lock free) and deleted.
[Successful operator output](../../../prds/steward-platform/research/test-system-cold-publication-2026-09-23.txt).

## Introducing change verified by publication lane

`git blame` and `git show dc1efaf3c9 -- src/seon/schema.clj` identify
`dc1efaf3c9` as the commit introducing `(merge forms contracts)` at this seam.
`30baf050a` was the latest schema edit when the failure was reported, not the
introducing change. The publication lane did not edit `src/seon/schema.clj`;
the test-system lane corrected it in `2d9984a50`.
