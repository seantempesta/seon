---
title: "SR-STATUS: cluster status sums stored sizes, never blob contents"
type: reference
status: ready
created: 2026-10-08
---

# SR-STATUS: Cluster status reads size facts, never blob contents

Ledger row SR-STATUS (group E). Source: `storage-audit/storage-audit-astra.md` finding 5. Model: Sonnet.

`seon.cluster.status/agents` reads every referenced blob whole (`blob/get`) only to measure it
(`src/seon/cluster/status.clj:134-142`): status cost follows total stored bytes, repeated per agent. Every one of those
blob references already has a sibling size fact written by its producer: `:seon.turn/reply-size` beside
`:seon.turn/reply-blob` (`seon.turn.edn:25-30`), `:seon.ai.attempt/reasoning-size` beside `reasoning-blob`
(`seon.ai.edn:299-304`), `:seon.error/shown-size` beside `:seon.error/shown-blob` (`seon.error.occurrence.edn:46-47`).

## Files

- `src/seon/cluster/status.clj`
- `test/seon/cluster/status_test.clj`

## Functions

Change the per-agent body of `seon.cluster.status/agents` (contract unchanged):

- `fault-blobs` (`:134-136`) becomes `fault-sizes`, digest and size pairs:
  `'[:find ?digest ?size :in $ ?a :where [?o :seon.error.occurrence/agent ?a] [?o :seon.error/shown-blob ?digest] [?o :seon.error/shown-size ?size]]`.
- `digests` and `blob-sizes` (`:137-140`) become one map from digest to size, so a blob shared by several facts counts
  once, as the old `set` of digests did:
  `(into {} (concat (keep (fn [t] (when-let [d (:seon.turn/reply-blob t)] [d (:seon.turn/reply-size t)])) turns) (keep (fn [a] (when-let [d (:seon.ai.attempt/reasoning-blob a)] [d (:seon.ai.attempt/reasoning-size a)])) (mapcat :seon.turn/attempts turns)) fault-sizes))`,
  and `storage` sums `(vals that-map)` with the shown-text sizes, through `sum-known` as today.
  Add `:seon.turn/reply-size` to the turns pull (`status.clj:119`, beside `:seon.turn/reply-blob`); attempts are pulled
  with `[*]` and already carry `:seon.ai.attempt/reasoning-size`.
- A blob reference with no size fact contributes `nil`; `sum-known` (already used here) turns that into its unknown
  member, as an unavailable blob does today.
- Drop the `seon.blob` require if nothing else in the file uses it.

Known limit (recorded, not fixed here): `:seon.turn/reply-size` is written as `(count text)` (characters) by
`turn/batch.clj:691` and `bootstrap.clj:310`, not UTF-8 bytes; the sum is exact for ASCII. RP-10 deletes the
`turn/batch.clj` writer; SR-G's extension converts the reasoning writer.

## Proof

New `seon.cluster.status-test/storage-bytes-read-size-facts-not-blob-contents`: in `test-support/with-running-agent`,
record one turn with a reply over the blob threshold (5,000 ASCII characters through the fixture model, or the turn
writer the existing status tests use); then delete that blob's konserve key on the test store
(`konserve.core/dissoc`), and call `seon.cluster.status/agents`. Assert the agent's
`:seon.cluster.status/storage-bytes` still counts the reply's 5,000 bytes (the old code would answer unknown: it read
the blob). Read the existing `status_test.clj` first and reuse its fixture.

`--test seon.cluster.status-test`

## Cost

Expected: one query per agent over its own facts; no blob reads (was: every referenced blob read whole and decoded).

## Commit

`bin/lane commit <lane> -m "SR-STATUS: cluster status sums size facts instead of reading blobs" --test seon.cluster.status-test src/seon/cluster/status.clj test/seon/cluster/status_test.clj`

## Dependencies

None; no lane holds these files at `3f69939e4`.
