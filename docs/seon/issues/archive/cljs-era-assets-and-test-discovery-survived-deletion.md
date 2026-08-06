---
type: issue
status: resolved
severity: cleanup
tags: [issue, deletion, cljs, web, testing]
---

# Delete CLJS-era assets and test discovery

## Problem

The CLJ-only runtime still shipped an unreferenced browser/Scittle bundle and
retained dead CLJS test and lint discovery. These paths survived after the CLJS
source roots, build, and runner were deleted.

## Evidence

- Current page HTML emits only `datastar.js` at
  `src/seon/render/web.clj:201-204`.
- Exact-name search found no maintained consumer for the deleted Scittle,
  reactive demo, debug, highlighting, or font assets.
- `script/seon/dev/test_roots.clj` retained a readerless CLJS selector and
  `bin/lint` retained a sibling-`.cljs` shadowing path despite the absence of
  `.cljs` source or tests.
- One operator test called the already-deleted legacy JVM-role classifier and
  named Shadow CLJS.

## Owner

The Datastar page asset set, JVM test discovery, and the current fresh operator
tests.

## Acceptance

Delete the unreferenced asset bundle, dead CLJS discovery/lint branches, and
obsolete operator test without restoring a CLJS owner. Preserve JVM `.clj` and
portable `.cljc` test discovery and the declared Datastar and CSS assets.

## Resolution

Resolved across deletion-sweep units 1, 4, and 8. Commit `68af32ebc` removed
the unconsumed browser assets, unit 4 removed CLJS test/lint discovery, and the
audit-finding-8 commit archiving this issue removes the unresolved legacy-role
test. `datastar.js`, `input.css`, and `output.css` remain consumed, and exact
search finds no live Scittle, Shadow CLJS, or CLJS-runner path.
