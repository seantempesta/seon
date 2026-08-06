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

Resolved by deletion-sweep commits `68af32ebc`, `6de64fdb2`, and `b41728539`.
They removed the unconsumed browser assets, CLJS test/lint discovery, and the
unresolved legacy-role test respectively. `datastar.js`, `input.css`, and
`output.css` remain consumed, exact search finds no live Scittle, Shadow CLJS,
or CLJS-runner path, and the focused changed-test owner suite ran 3 tests / 6
assertions with zero failures and errors.
