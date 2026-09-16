---
type: issue
status: resolved
severity: friction
tags: [seon.error, seon.cluster, fault, blob, test, occurrence, class/stale-expectation]
opened: 2026-09-17
resolved: 2026-09-17
---

# Fault-evidence tests pull a fault entity that no longer carries its evidence

## Problem

`seon.cluster.fault-storage-test/lossy-subthreshold-fault-evidence-is-retrievable`
and `…/oversized-fault-evidence-is-bounded-and-retrievable` fail in the cold
gate (batch 101 on `0f23d6fb6`, both as errors) with

```
seon.blob/get refused content-digest at []: expected a value satisfying
should match regex, got nil
```

The digest is `nil` because the test pulls it off the FAULT entity:

```clojure
(db/pull @connection
         [:seon.error/id :seon.error/data-edn
          :seon.error/data-blob :seon.error/data-size]
         [:seon.error/id id])          ; test/seon/cluster/fault_storage_test.clj:65-74
```

and the fault entity stopped carrying those three attributes in
**`2066b8c20` "Commit error occurrences and notifications at the transaction
writer"**. That commit introduced `error-row` in `src/seon/error.clj:1345`:

```clojure
error-row (assoc (select-keys fact [:seon.error/signature :seon.error/id
                                    :seon.error/kind :seon.error/fn
                                    :seon.error/frame
                                    :seon.error/exception-class])
                 :seon.error/occurrences #{occurrence})
```

The evidence moved to the OCCURRENCE: `data-edn` and `data-size` ride the
occurrence's `evidence` map (`src/seon/error.clj:1321`), and the blob rides as
`:seon.error.occurrence/data-blob` (`:1341`). A separate row keyed by
`:seon.error.occurrence/blob-digest` carries `:seon.error/data-blob`
(`:1360`), and that row has no `:seon.error/id`, so the test's query
(`[?fault :seon.error/id ?id]`) never reaches it.

## Evidence that the producing side is healthy

Called directly on `default` (pid 53320) with the tests' own payload and
`(config/result-caps (config/defaults))`, `seon.error/prepare` still produces
what staging needs: `:seon.error/data-size` 372,256 against a 4,096-byte
`:seon.config.eval.result/blob-threshold`, and `data-content` ≠ `data-edn`
(306 bytes fitted). So `seon.cluster/commit-fault!` does stage the blob
(`src/seon/cluster.clj:2833`); only the READ is looking in the old place.

## Not the collector slice

`ba2986d72` / `0f23d6fb6` changed `seon.operator`, `seon.cluster.registry` and
two schema resources. Neither failing test calls either `collect!`; the fault
path they exercise does not reach them.

## Decision and resolution

> DECISION (orchestrator, 2026-09-17): the fault entity stays evidence-free —
> the signature aggregates, the occurrence carries the evidence (`data-edn`,
> `data-size` on its evidence map; the blob on
> `:seon.error.occurrence/data-blob`). The two failing tests
> `lossy-subthreshold-fault-evidence-is-retrievable` and
> `oversized-fault-evidence-is-bounded-and-retrievable` are stale: they pull
> `:seon.error/data-blob` off the fault entity and hand nil to `seon.blob/get`,
> and they take `(first (fault-facts connection))` of an unordered query
> result, which is how a model change became a nil instead of a named refusal.

Resolved in `bdda3cd5d`: `fault-facts` is replaced by `fault-evidence`, which
finds the fault by its SIGNATURE (never an arbitrary member of an unordered
result) and follows `:seon.error/occurrences` to the evidence, plus
`occurrence-digest` and `published-blob-digests`. The blob read is guarded on
`(string? digest)`, so a future model change fails the named assertion instead
of throwing a contract refusal out of a read.

The storm assertion now states what the aggregating model promises: 500
identical faults are one failure class, one occurrence counted 500, and one
published blob row — the old `(= 500 (count facts))` counted fault entities
that the signature model no longer mints.

Proven live on `default` (pid 53320) against a speculative database built with
`datahike.api/with` from the real `seon.error/commit-call` output on the tests'
own oversized payload (nothing committed): the pull resolves, the fault's
`:seon.error/id` equals the returned fact's id, there is exactly one
occurrence, `occurrence-digest` yields the digest, inline is 306 bytes,
`:seon.error/data-size` is 372,256, `:seon.error/capped?` is true, and
`published-blob-digests` returns exactly one row. `seon.error/prepare` on the
lossy payload likewise reports `capped?` true, 48,000 bytes of content and a
305-byte face, so its blob is staged too.

The two tests could NOT be run in process: `seon.test/run` returns the typed
unknown

```
No function in this program declares :seon.fn/destroys, so an in-process run
cannot tell whether a test deletes a filesystem path it did not create.
```

because the `:seon.fn/destroys` indexing (`src/seon/fn.clj:590`, `:686`) is an
uncommitted working-tree edit that the published program does not yet carry.
The storm count of 500 and the blob's survival across `registry/collect!`
therefore remain for the cold gate (`tmp/orchestrator/gate-requests/fault-storage.txt`).
