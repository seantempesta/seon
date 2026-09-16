---
type: issue
status: open
severity: blocker
tags: [issue, publication, program-graph, test-evidence, class/p1]
---

# Adoption refuses forever once stored test evidence names a deleted declaration

Deleting a function makes `bin/seon init --dev NAME` unrunnable on any cluster
whose stored test evidence still refs it. Observed on `default` (pid 45917)
2026-09-16 08:25, repeatedly, for `seon.fn/rooted-source-files`, deleted by
commit `0eba4b8c3`:

```
● current-src: program rows complete
:error datahike.db.utils Nothing found for entity id [:seon.fn/sym "seon.fn/rooted-source-files"]
:error datahike.writer :datahike/write-rejected {:kind :entity-id/missing, ...}
✗ The cluster threw during the prepl operation:
  The rebuilt source could not preserve test evidence.
```

Publication completes the whole program build (90 860 population rows) and
then dies in initialization rows, so every lane's adoption and, through it,
`seon.test/run`'s provenance are blocked by one stale ref. Retrying cannot
help: the evidence is durable and the declaration is gone for good.

Two things are wrong here, and the second is the class:

1. **A ref to a removed declaration is carried into a write as a lookup ref.**
   Program identity rows never retract (ruling 47), so a tombstone identity
   should still resolve — this one does not, which says the evidence rows name
   a declaration that was never minted on this branch rather than one that was
   retracted.
2. **A rebuild treats "the evidence mentions something I cannot resolve" as a
   fatal write** instead of as evidence that no longer applies. Preserving
   test evidence is a best-effort projection over the previous program; the
   correct behaviour for a vanished subject is to drop that evidence with a
   named, counted diagnostic, never to refuse the publication that would make
   the program current.

Repair belongs with the preservation step's owner (`seon.cluster`, which is
protected while a peer edits it); this note is the record, not the fix.
