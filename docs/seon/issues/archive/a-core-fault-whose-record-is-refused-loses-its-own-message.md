---
type: issue
status: resolved
severity: blocker
created: 2026-09-22
tags: [fault-committer, errors, boot, class, silence-on-failure]
---

# A core fault whose durable record is refused loses its own message

## Evidence

Fresh cluster at HEAD `7924f4dae` on an isolated root
(`bin/seon --root tmp/head-root init` → `init head` → `start head`; boot
43 s, ready, 1,497 vars instrumented). The cluster log's only fault line
(`tmp/head-root/data/clusters/head/logs/seon.log:18`):

```text
SEON CORE FAULT (dev panic): A core fault could not be normalized. [signature ; durable record refused: The attribute has no registered schema. Run (schema/register! :seon.error/offending :string) with the intended concrete type before transacting it.]
```

Two defects in one line.

1. **The raw member reached the transaction.** `:seon.error/offending` is
   declared `:seon.schema/value` (`resources/seon/schemas/seon.error.edn:20`),
   an in-memory carrier with no Datahike type, and the fault committer's
   transaction data carried it. The owner's ruling of 2026-09-22 (working
   edge, ~12:50): nothing outside the schema reaches `seon.db/transact!`;
   the offending value is a `result/e<id>` reference. The error lane's
   queued slice retires the member; until then every core fault whose map
   carries it is unrecordable.
2. **The refusal erased the fault.** `seon.cluster/commit-fault!`
   (`src/seon/cluster.clj`, the outer `catch` returning `[nil failure false]`)
   drops the source fault when preparation or recording throws, and
   `emit-core-fault!` (`:3266`) then prints the placeholder "A core fault
   could not be normalized." with an empty signature. We do not know what
   fault happened at boot. This is the project's named class: a failure
   path that reports less than the failure it is handling.

## Fix shape

The last-resort shape carries the SOURCE fault: its `:seon.error/message`
(or the throwable's message and class) and the recording refusal, both
printed by `emit-core-fault!`. One regression: a fault whose map is
refused by the writer still prints the fault's own message and the
refusal. Lands with the fault-committer conversion the error lane owns
(the hunk is held by the publication lane at this writing).

## Second sighting, HEAD `0f5f849bd` (step 2 landed)

Same boot sequence on `tmp/head-root2`, `seon.log:18`:

```text
SEON CORE FAULT (dev panic): A core fault could not be normalized. [signature ; durable record refused: :malli.core/invalid-schema]
```

The refusal changed (a schema that does not compile at fault-recording
time, after the compiled-node bridge landed) and the fault's own message is
still erased. Whoever fixes the last-resort shape gets this schema's name
for free; until then the boot fault at HEAD is unknown.

## Slice 0 fast-test observation — 2026-09-22

The guard-deletion snapshot at `a70995402` reproduced the same erased fault
while `seon.cluster.publication-adoption-test/freshly-booted-host-adopts-its-own-tree`
ran in PID 31591. Its isolated cluster `publication-8551532f5f9e` reached the
web-view announcement at `2026-09-20T17:14:56.997Z`, then emitted the exact
second-sighting line above (`:malli.core/invalid-schema`). Removing the
publication guard does not fix this independent fault-recording path; the
owner's slice 4 assignment retains it.

## Error recording correction and boot boundary — 2026-09-23

`b2095ca4b` corrects `stored-observation`'s registry lookup: inline transient
members have no standalone schema, so component discovery must not pass nil
to Malli. `fb41a5244` corrects the corresponding writer discovery case and
completes its unknown write-error return. The combined canonical run passes
48 tests / 388 assertions, including complete stored error components.

A fresh isolated root published commit
`6ab05683-a7b8-5b5c-bf46-1e422662402b` and forked cluster `e`. Boot then failed
at the config-phase event bound: no READY event for 30,000 ms, child PID
91993. `tmp/error-fresh-seon.log` preserves the child log; it contains neither
`invalid-schema` nor `durable record refused`, but boot never reached READY,
so this does **not** establish a clean completed boot. The config/adoption
owner must resolve or measure that boundary before the boot claim is green.
No timeout was increased. `down` found no live child and a free store flock;
the isolated root was removed. The original last-resort message-loss defect
remains outside the bounded recording correction.

## Resolution — 2026-09-23

`commit-fault!` now returns the source message in its last-resort fact,
alongside the recording refusal in the existing outcome position. Map,
flow-wrapped Throwable and bare Throwable inputs all retain their message;
Throwable fallback also names its class. `emit-core-fault!` prints both
through its existing output. Fast run `34dfd601d3b6`: 1 test, 12 assertions,
0 failures/errors, 3.100 s. The canonical fixture deliberately names an
absent cluster, making the real recording preparation refuse before a fact
exists. No preparation or writer is mocked. This closes message loss, not
every possible recording refusal's independent cause.
