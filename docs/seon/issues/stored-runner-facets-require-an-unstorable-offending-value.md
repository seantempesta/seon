---
type: issue
status: open
severity: blocker
created: 2026-09-20
tags: [issue, schema, testing, publication, wave/publication-velocity]
---

# Stored runner facets require an unstorable offending value

Publication and fast admission both refuse the committed declaration
`:seon.test.runner/invalid-marker-reason-error`. It has
`:seon.db/attributes true` and requires `:seon.error/offending` with
`:seon.schema/value`, which is not a registered storable attribute.
The same declaration pattern appears in `unknown-worker-command-error`
and `worker-launch-failure-error` in
`resources/seon/schemas/seon.test.runner.edn:81-109`.

This is current source, introduced by `e8ca8aa42` and `b5e3a5e40`, not a
hypothesis about historical schema acquisition. The owned snapshot at
`86c3a73d49596488ab5623b39dd3b8fb6791d73e` excludes the bridge lane's
uncommitted changes. Fast run `f0c441c8d377` loaded and armed, then refused
before executing tests. Ordinary root-scoped `bin/seon init` reproduced the
same declaration/member refusal before analysis, exit 1, init phase
68,894 ms. Raw evidence:
`tmp/publication-dissolution/common-publication-adapters-fast.log` and
`tmp/publication-dissolution/current-facet-publication.log`.
The complete named refusal is retained in those logs.

The validator is doing its declared job: `assert-error-declaration!` in
`src/seon/schema/internal.cljc` checks every stored error member. That file
is held by the bridge lane and was not edited. A reset cannot repair invalid
current source. The earlier
[missing-diagnostic issue](fast-admission-refuses-on-live-error-facet-schema.md)
fixed evidence loss; it did not repair these schemas.

The storage choice cannot be hidden in publication. `seon.error/facet-keys`
(`src/seon/error.clj:1875`) discovers base extensions irrespective of their
storage property; `commit-call` selects their declared members into the
occurrence (`:1597-1606`). Merely removing `:seon.db/attributes` can therefore
move the refusal from acquisition to recording. Generic evidence already
has its bounded normalization path (`:723-782`), and the runner already
owns `:seon.test.runner/worker-observation` as an error-evidence component
(`resources/seon/schemas/seon.test.runner.edn:127`).

Decision options, estimates excluding publication validation time:

1. **Recommended: stored facets require only their existing typed domain
   members.** Remove generic offending values from the three stored facet
   definitions; keep the raw value as extra observation evidence through the
   existing normalizer. Three declaration edits plus one recording/read
   regression, roughly 30–60 minutes. Preserves queryable test/worker/phase
   facts and bounded diagnostic evidence; gives up treating an arbitrary
   offending object as an independently queryable datom.
2. **Declare an owned evidence component on each facet.** Use the existing
   runner evidence relation and its normalization owner; convert the three
   constructors and verify recording/readback. Roughly 1–2 hours across
   declarations, constructors and tests. Preserves structured queryable
   evidence under the declared bounds, at the cost of a larger error-model
   change outside publication.
3. **Have the error owner repair the declarations before this lane resumes.**
   No publication-side storage change; one owner handoff plus the repair and
   validation time. Preserves the owner's intended storage contract, but
   leaves common-base export validation and final live measurements pending.

Done: current canonical schema acquisition admits these facets, a real
refusal records and reads back its required domain facts and diagnostic
value under the selected contract, then publication and fast admission
proceed without weakening validation.


## Ruling applied; bridge admission still pending

The orchestrator selected option 1 with a precise declaration rule: raw
`:seon.error/offending` is OPTIONAL on stored facets; typed domain members
remain required and raw evidence uses the occurrence's existing durable
projection/data path. `6dae626e0` makes the three runner members optional and
adds the canonical-publication/producer-validation regression.

This issue remains open because `owned-storage!` still rejects optional
non-storable members in both HEAD and the held bridge draft; merely editing
the facet declarations does not implement the storage distinction there.
That owner is held and coordination is pending. The first fast check after
the declaration commit reached a different earlier refusal at the live
recording authority: the concurrently edited SCI install-mismatch facet's
required `:seon.program/identity`. It ran zero tests, so it proves neither
this regression nor a repaired publication. See the
[publication landing note](../../prds/steward-platform/research/publication-dissolution-2026-09-20.md)
for the exact current held paths and evidence.
