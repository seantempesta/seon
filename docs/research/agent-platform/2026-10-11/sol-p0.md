---
type: evidence
status: implemented
---

# Sol P0 landing evidence

Entity schemas own the partition. `seon.program/cluster-attributes` derives the attributes and their conflict rules
from their compiled maps, retaining the answer through `seon.schema/projection-cache-value` and `seon.cache/lookup`.
Messages remain data. No runtime partition roster or copy operation is added.

## State

The partition checker prerequisite is committed as `7d7da5865`. Root restarted default onto HEAD including that
prerequisite, resolving the old checker's publication refusal. The observed default is pid 57399, started at
2026-10-11T12:27:12.102Z, from archive `d884d4c96bb3989017082a131bf4aa247c11b26e`. This lane did not restart default.
The remaining declaration slice uses the same three named proofs and the ordinary commit gate to prove publication,
adoption and candidate boot.

Root stopped P1 before landing. Its uncommitted files were moved to `tmp/sol-p0-p1/parked-p1/`; none is a P0 input.

## Proof

Red first: run `3d04183bdd66` failed the new P0 proof on the absent derivation and absent identity refusal.
The prerequisite passed run `3669f22352db`: two tests, nine assertions, no failures or errors, 5,890 ms total.
Its candidate publication and adoption passed, in 7,402 ms and 388 ms respectively; the entire check took 46,617 ms.

The final slice passed run `618c9f36e245`: three tests, 16 assertions, no failures or errors, 6,668 ms total,
including the warm derivation bound of 5 ms:
`seon.program-test/cluster-attributes-are-the-declared-cluster-entities`,
`seon.program-test/identity-bearing-entity-refuses-an-undeclared-partition`, and
`seon.program-test/program-partition-selects-roots-and-components-with-one-query`.
Before root's restart, the commit gate refused live publication under default's old compiled checker, with
`:seon.schema/identity :seon.error.occurrence/occurrence`, `:seon.schema/member :seon.program/partition` and
`:seon.schema/expected #{:seon.program :seon.data}`. The candidate checker accepts the third partition.
The operator diagnostic is `data/clusters/default/logs/operator-check-20261011T192631.111.edn`.

Full `clj-kondo --lint src script test --cache false` reported zero errors and 1,515 warnings in 6,679 ms.
The warnings include existing source findings; this is not a zero-warning claim.

The whole namespace was not run. Historical reds named by `seon.test/reds-on-head-178aa24e0` include
`seon.program-test/a-declaration-added-after-the-first-call-is-a-cache-miss`,
`seon.program-test/declaring-an-attribute-on-a-program-entity-schema-is-sufficient`,
`seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`,
`seon.program-test/opening-basis-divergence-is-only-claimed-when-it-is-measurable`,
`seon.program-test/runtime-schema-declarations-project-namespaced-properties`, and
`seon.program-test/typed-cross-namespace-deletion-retracts-function-and-test`.
Their current status is unverified; no namespace-wide green is claimed.

## Foreign boundaries and limits

Astra holds the benchmark and runner files. Those working edits were excluded from the candidate and not touched.
Default began degraded with two cache warnings. Its edit hook later failed in
`seon.sci.eval/interpret-source-namespaces!` at the `seon.db/q` contract; recording that error also refused
`:seon.instrument/arm :guard` in `seon.error/prepare`. Those faults remain observable after root's restart and
have existing task handles `29b1aeb3708d` and `6afb6d0d67e4`. Successful SCI admission is not claimed; the
platform commit gate supplies publication and adoption evidence. These failures are outside the owned slice.

Malli is pinned at `dd13ff81139159778edb0a67b491eccc0baa991d`; entity traversal uses
`seon.schema.internal/entity-properties` and `seon.schema.internal/entity-entries`, over `malli.registry/schema`.
Datahike is pinned at `b2ffa4af0f555483e1a146a98c2546b0a42debad`; no Datahike implementation changes are made.
