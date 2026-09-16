---
type: issue
status: resolved
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

## Resolved — 2026-09-16, `64230d4de` / `b375b5dcc`

Both writers now decide absence on the database value they transact into,
inside the transaction function, and MINT the missing function identity as a
tombstone (`seon.cluster.source/absent-program-identities`,
`identity-tombstone-rows`, `identity-ref`; used by
`seon.test.runner/record-tx` and `seon.cluster.source/preserved-evidence-tx`).
Point 1 of this note is right and is the cause: the evidence named a
declaration that the REBUILT branch never minted, not one that was retracted,
so ruling 47's tombstone was not there to resolve. Point 2's remedy is the
stronger one available — the evidence is kept, not dropped, because minting
the identity restores the population invariant by construction. Only a FILE
identity is not minted (`:seon.fn.file/file` requires the digest of the file
the indexer walked); an unresolvable site keeps its line and reports its path
through `:seon.test.failure/reported-file`.

`bin/seon init --dev default` exits 0 on PID 45917, adopting `:current-src`
commit `6aaa5523-5055-5df1-bcd7-d944ce8a43fc`. Evidence and the two
regressions are in
[the reach-closure landing note](../../prds/steward-platform/research/reach-closure-facts-2026-09-16.md).
