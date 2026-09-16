---
type: issue
status: open
severity: friction
tags: [seon.error, seon.cluster, fault, blob, test, occurrence, class/stale-expectation]
opened: 2026-09-17
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

## Wanted

One decision by the owner of the occurrence model, then the tests follow it:

- if the fault entity is meant to stay evidence-free, both tests are stale and
  must read the occurrence (`:seon.error.occurrence/data-blob` →
  `:seon.error.occurrence/blob-digest`), asserting the CURRENT shape; or
- if a fault is meant to answer for its own evidence, `error-row` is missing
  those keys and the fix is at `src/seon/error.clj:1345`.

Either way the test's `(first (fault-facts connection))` is worth replacing:
it takes an arbitrary member of an unordered query result, which is how a
model change turns into a nil rather than a named refusal.
