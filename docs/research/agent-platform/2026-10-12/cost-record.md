---
title: Recording cost
type: research
status: in progress
---

# Recording cost, 2026-10-12

The recording transaction passes through `seon.db/transact-call`, Datahike's
writer, and konserve. The first change writes datom fields through Fressian's
`Writer` and `StreamingWriter`, removing the intermediate list and vector.
The operation remains proportional to the datoms written. The stored tag and
its single list component remain readable by the existing reader.

## Reproduced evidence

Default runs `015673f475eb78eeaf4b5a9231c187399a4e4bfe`. Both runtime status
entrances reported degraded health before this work: 15 untested hot warnings,
one agent-error signature, 13 errored evaluations, one file without a namespace,
and one unowned namespace. Default was not restarted or reloaded.

The prescribed warm MCP probe, second call, returned 105.65 ms, 40 calls of
`seon.db/q`, 63 of `seon.db/pull`, 12 of `seon.config/effective`, six of
`seon.turn/agent-dial`, and 695 of `seon.profile/cost-entity`. The inner request
reported 100.2 ms, writer allocation 8.0523 MB and commit allocation 17.4915 MB.
`seon.db/transact-call` had 50 ms self time. These reproduce the audit's work
counts; the transaction's time and allocation vary with the dirty index path.

The datom allocation regression was red through `bin/seon check`: 1,284,656
bytes for 1,000 datoms, against a 1 MB assertion. The live probe returned
1,284,432 bytes. Its serialized output was 15,915 bytes.

## Tempid replay

A prefix transaction function increments a local `LongAdder` and returns no
datoms. On the immutable database of `cost-probe`, `datahike.api/with` of that
prefix and `seon.turn/system-run-tx` executes the prefix twice: **one retry**.
The resolved existing tempid is `namespace:user`. This counts transaction
restarts without replacing or instrumenting any JVM Var.

Moving the namespace declarations before the run which references their
tempids, in the transaction data returned by `seon.turn/system-run-call`,
changes the count from one retry to zero. Both results contain 14 datoms.
The measured pair was 9.90 ms / 3,234,160 bytes before and 3.52 ms / 1,351,176
bytes after. This measures the opening portion, not the full recording
transaction. The protected producer belongs to lane program-context; its
proposed edit is in `cost-record-turn.patch`.

## Dependency seams and limits

Datahike pin: `42309184021bd24d0f9c0fd17f514c320ab037fd`.
Konserve pin: `e4334756413917305968333bf305e658c177765d`.

`datahike.index.persistent-set/datom-write-handler` writes the datom tag and
one list containing entity, attribute, value, transaction and added flag.
Fressian 0.6.8 supplies `StreamingWriter.beginClosedList` and `endList`; its
separate `Writer` interface supplies the field-writing methods.

LZ4 Java 1.8.0 has frame block sizes 64 KB, 256 KB, 1 MB and 4 MB. The audit's
suggested 4 KB frame size does not exist. `LZ4FrameOutputStream` has no public
reset or buffer-reuse method. Buffer reuse needs a dependency seam or a
separately named encoding; neither has been added in this slice.

Fressian's `WriteHandlerLookup.createLookupChain` already uses `CachingLookup`
for class lookup. `getWriteHandler` still calls `Fns.soloEntry` on every
object. Caching the class lookup again would not remove that allocation.

## Verification findings

The edit hook cannot acquire the changed test namespace on the lane's SCI
branch: it reports `Unable to resolve var: datahike.writer/write-error-log`.
The dependency change is therefore checked in a candidate JVM.

One candidate attempt failed before boot in tools.deps/Maven with a
`HashMap$Node` to `HashMap$TreeNode` cast error. Its diagnostic is
`data/clusters/default/logs/operator-check-20261012T103015.787.edn`.
A check-slot probe made without verifying its owner is excluded: its archive
was not this candidate. Later probes verify the check process and its slot.

Full `clj-kondo --lint src script test --cache false`: zero errors, 1,574
warnings. Other lanes' changes in the four protected files were not edited.

## Status

Serialization slice verified on candidate `eb8df0418647ab2d4b7e60c9b9537520899a5823`:
run `178e4afd67f2`, two assertions, zero failures/errors, 886 ms body. The
allocation regression is below 1 MB. Full check 30.219 s: boot 18.199 s,
preparation 3.891 s, publication 1.164 s, test entrance 975 ms. These platform
costs exceed the ordinary-operation budget; no claim of a sub-second check.

Matched candidate probes use the same parent program and differ only in the
Datahike pin. Second-call measurements: 192.53 → 153.35 ms; recording phase
72.4 → 48.5 ms; commit allocation 7.3599 → 5.8650 MB; commit CPU 7.27 →
6.40 ms. Startup and test activity overlap both windows (the global read
counts are contaminated), so these timings do not establish isolated causal
savings. Raw replies: [before](cost-record-before.txt), [after](cost-record-after.txt).
The repeatable driver is `script/seon/cost_record.clj`, invoked with
`bb --classpath script:src:resources -m seon.cost-record ROOT`.

Datahike branch `seon/cost-record`, commit `ec907fb5`, pushed to the maintained
fork. The recording target of 15–20 ms is **not met**. The namespace-order
patch is not applied. Items 2–4 and LZ4 buffer reuse remain.
