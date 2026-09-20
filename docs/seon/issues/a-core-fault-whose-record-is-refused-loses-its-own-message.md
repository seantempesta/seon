---
type: issue
status: open
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
