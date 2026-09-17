---
type: issue
status: open
severity: friction
tags: [issue, runtime, database]
---

# A throwable-shaped fault keeps no inline evidence at any plausible bound

Measured by the `verify-storage-repair` lane (2026-09-07,
[its note](../../prds/context-generation/research/verify-storage-repair-2026-09-07.md)
§1.3) and left open by `storage-bound-repair-2`.

A fault's evidence is admitted under
`:seon.config.error/max-evidence-bytes` and the fact divides its inline
budget among up to four payload fields, so the share is about 7,750 bytes at
the shipped 16,384. A `Throwable` source admits its whole
`Throwable->map`, stack trace included — **14,311 bytes for a three-key
`ex-info`** — so every exception-shaped fault's `:seon.error/data-edn` is the
missing marker and nothing else. The typed cause survives only as
`:seon.error/kind` on the fact and in full inside the content blob.

This is not a regression (the same fault was 915,655 unbounded bytes before)
and it is not dishonest (the marker says `:over-bound` with the bytes it
reached). It is the old "one member ablates the whole evidence" in byte form:
the fact keeps all of the evidence or none of it, and which one depends on
whether the source was a map or a throwable.

## To do

Decide whether a throwable's evidence should be admitted in PARTS — the
`ex-data` and the typed cause under their own share, the stack trace as blob
content only — so a fault fact keeps the part a steward reads. Raising the
bound is not the fix: the stack trace grows with the stack, not with the
bound.

## Live confirmation, and one thing it costs that the text above did not claim — 2026-09-17

Live trial 1 on `default` (pid 94566) hit this on the fault that killed every
agent turn
([note](../../prds/steward-platform/research/live-trial-1-2026-09-17.md);
[the turn-proc issue](an-agent-turn-proc-dies-on-every-pass-and-oversight-still-reports-it-armed.md)).
The occurrence carries exactly the predicted marker:

```clojure
{:seon.error/capped? true
 :seon.error/data-size 33582
 :seon.error/data-edn "#:seon.print{:face :seon.print/map, :entries
   [[… :seon.sci.admit/reason … :over-bound]
    [… :seon.sci.admit/bytes … 7806]]}"}
```

The correction to this note: **the typed cause did NOT survive on the fact.**
The fault entity reads

```clojure
{:seon.error/kind :seon.error/unclassified
 :seon.error/exception-class "clojure.lang.ExceptionInfo"
 :seon.error/frame ["malli.core$_exception" "invokeStatic" "core.cljc" 203]}
```

with no `:seon.error/message` datom at all, and the occurrence's message is the
bare `":malli.core/invalid-schema"`. The key that actually failed to resolve
(`:seon.test/acquisition`) existed only inside the 33,582-byte content blob.
So a steward reading the fault fact learns the exception class and nothing
else: classification fell back to `unclassified` precisely because the data
that classifies it was the part ablated. That makes the partitioned admission
in "To do" a correctness requirement, not a convenience — the `ex-data`'s typed
keys have to be admitted under their own share before the stack trace is
considered.
