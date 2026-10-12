---
title: Acquisition and recording cost
type: research
status: in progress
---

# Cost acquire, 2026-10-12

Default runs `94f03ca5c`, including E4. Both status entrances answer but
report existing acquisition errors, cache warnings, 15 errored evaluations,
one file without a namespace and one unowned namespace. This lane leaves
default running. The task for acquisition is
`seon.mcp/a-fresh-agents-first-eval-request-acquires-its-branch-cheaply`.

## Fresh acquisition

Two concurrent pairs, released by one promise inside root's JVM MCP request,
evaluate `(+ 1 2)` under distinct fresh agent ids. Each pair is measured by
`seon.profile/measure`. Pair one: 329.93 ms, 399.70 MB allocated JVM-wide,
522.60 ms process CPU; individual `:custody` stages 221.4 and 237.1 ms.
Pair two: 278.61 ms, 390.63 MB, 517.44 ms CPU; individual stages 201.9 and
165.6 ms. The 48.4-second outlier did not reproduce. These windows include
both requests and any other concurrent activity.

Each pair calls `seon.cluster.registry/branch!` four times,
`seon.cluster.store/open-branch!` four times, and `seon.db/transact-call`
eight times. Per agent, the fork, parent admission and branch admission
precede the evaluation's recording transaction. SCI still forks a branch
base and an agent context; E3's dependency on E5 remains as described in
program-context-notes.md.

### Datahike branch head

Datahike pin `ec907fb51e10b5562652c2ab33b8b460abe0e6f7` already provides
`datahike.writing/compact-head` and `datahike.writing/resolve-head`. Ordinary
commits use the pointer representation, but `datahike.versioning/branch!`
serializes the complete selected database again. Its indexes and commit
already exist. The smaller composition uses the existing commit pointer
when the branch has no newly derived secondary-index state and the store
retains commits. Stores without a commit graph and branches with derived
secondary-index state keep their complete stored value.

This is equivalent for readers: `resolve-head` restores the destination
branch in configuration. Work is proportional to a commit pointer rather
than the selected database's fused index roots. No new state or encoding.

On default, Fressian encoding the retained base of `cost-acquire-fresh-a`
with the dependency's index, root and datom handlers costs 0.70 ms,
611,136 allocated bytes, and writes 38,782 bytes. Encoding its existing
compact-head equivalent costs 0.04 ms, 6,368 allocated bytes, and writes
55 bytes. This isolates serialization; it does not establish an end-to-end
acquisition improvement. An earlier probe omitted the index handlers and
failed; it is excluded from the comparison.

## Schema derivation

A subsequent request on `cost-acquire-fresh-a` measures 51.92 ms overall,
47.7 ms inside the request and 33.4 ms recording. It calls
`seon.schema.datahike/compiled-storage` 45 times and calls neither
`seon.schema.datahike/storable-attribute-in?`,
`seon.schema.datahike/declared-storable?` nor
`seon.schema.internal/reference-id`. The reported whole-schema counts do
not reproduce in this warm request. Further evidence must distinguish
first use of a declaration registry from repeated use.

## Verification

The edit hook cannot install the changed dependency acceptance namespace in
the lane's SCI context: acquisition reports errors, as the preceding cost
lane observed. This dependency slice uses the candidate JVM path.
