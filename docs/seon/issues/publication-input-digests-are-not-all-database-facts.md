---
type: issue
status: open
severity: friction
tags: [issue, database, wave/publication-velocity]
---

# Publication input digests are not all database facts

The item-2 ruling requires hashing only the request's changed paths and
reading every unchanged input digest from its stored row. The own-root
probe at `d726468e0` finds **788 publication inputs, 402 stored file digests,
386 missing input rows**. The code file digest agrees between the database
and disk artifact. Missing paths include schema resources, analyzer config,
launchers and pinned dependency directory entries. The seal has the aggregate
`:seon.source/test-input-digest`, not those individual digests.

[Reproducible probe](../../prds/steward-platform/research/one-jvm-input-digest-facts-2026-09-23.clj)
and [complete missing-path evidence](../../prds/steward-platform/research/one-jvm-input-digest-facts-2026-09-23.edn).
`cluster/source-snapshot` merges source-file digests with test input digests
and the merged schema declaration digest (`src/seon/cluster.clj:1640`).
`fn/artifact` stores only analyzed source files (`src/seon/fn.clj:1286`).
The overall digest hashes the combined map; the external aggregate cannot
recover its individual entries. Removing the disk manifest alone does not
supply these facts.

The measured affected spans after item 1 are source build 909.097 ms,
pre-publication snapshot verification 542.996 ms, and post-adoption snapshot
verification 522.576 ms. The latter is independently deletable under item 4;
it is not a reason to retain the manifest. No item-2 production edit was
made before this decision.

## Three options

1. **Recommended: use the existing file identity/digest rows for every
   publication input.** One coherent population/read cut plus regressions;
   no new attribute or cache. A complete publication/reset supplies the
   previously absent rows. Preserves the current content-digest equation
   and permits indexed reads of any changed input. Requires explicitly
   broadening this family's population beyond analyzed files to include
   resource paths, the merged schema declaration entry, and Git pin paths;
   audit file-row consumers so these never become lint inputs.
2. **Compose the publication digest from program file digests and the
   existing external-input aggregate.** One digest/reader cut plus reset
   and identity regressions, no new fact family. Source-only edits reuse
   `:seon.source/test-input-digest`; an external-input edit recomputes that
   aggregate. Preserves content-based identity with a new digest equation,
   but gives up O(edit) processing for external-input edits.
3. **Declare the missing external per-path digest map on the source seal.**
   Schema plus population/reader cut and reset. Deletes the disk copy and
   gives one canonical owner to these otherwise absent observations,
   without duplicating program-file digest rows. Costs a new attribute and
   whole-map decoding; loses the indexed per-input access of option 1.

This is the AGENTS.md §2.2 missing-fact boundary, not a foreign-lane failure.
The owner selects how the absent input observations belong in the database.

## Owner ruling (2026-09-23)

Option 1, with corrections: only real input paths become rows; the merged
schema declaration is derived and gets no row. Analysis membership comes
from a declaration's file reference, never file-row presence. Changed-path
requests hash only their named paths. Aggregate identities do not decide
publication once per-path facts exist; the test-input digest may be removed
only after its readers are converted.

The consumer audit found that existing declaration queries already join
`:seon.fn/file`. Namespace rows currently have no file reference
(`src/seon/fn.clj:293`, `resources/seon/schemas/seon.ns.edn`); namespace-only
inputs therefore need that existing relation populated too. The test
admission owner still reads `:seon.source/test-input-digest` at
`src/seon/test.clj:916`, `:1365`, and `:2161`.

The changed-Git-pin case needs the existing pin reader in
`src/seon/test/cache.clj:190`. It is private, and the public
`toolchain-dependencies` also hashes `deps.edn` even when only a Git pin
changed. The lane requested release of the pin-reader seam rather than
copying Git/snapshot parsing into publication; that file is outside its
assigned ownership.
