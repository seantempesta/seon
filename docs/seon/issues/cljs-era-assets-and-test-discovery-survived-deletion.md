---
type: issue
status: open
severity: cleanup
tags: [issue, deletion, cljs, web, testing]
---

# Delete CLJS-era assets and test discovery

## Problem

The CLJ-only runtime still ships an unreferenced browser/Scittle bundle and
retains dead CLJS test and lint discovery. These paths survived after the CLJS
source roots, build, and runner were deleted.

## Evidence

- Current page HTML emits only `datastar.js` at
  `src/seon/render/web.clj:201-204`.
- Exact-name search finds no maintained consumer for `scittle.js`,
  `reactive-demo.js`, `seon-debug.js`, the four `highlight*.js` files, or
  `highlight-github-dark.css`. The eight files total 3,576 lines and
  1,031,359 bytes; `scittle.js` alone is 1,945 lines and 888,214 bytes.
- `script/seon/dev/test_roots.clj:12-19,90-99,119-126` retains CLJS test
  selection. `cljs-test-files` has no caller and the tree contains no test
  `.cljs` file.
- `bin/lint:121-164` retains a sibling-`.cljs` shadowing path even though no
  `src/` or `test/` `.cljs` file exists and the maintained system is CLJ-only.
- `test/seon/dev/fresh_operator_test.clj:527-541` still names Shadow CLJS and
  other retired JVM roles through `legacy-operator-arguments?`; the function
  no longer exists, and a direct `ns-resolve` probe returned `nil`.

## Owner

The Datastar page asset set, JVM test discovery, and the current fresh
operator tests.

## Acceptance

Delete the unreferenced asset bundle, dead CLJS discovery/lint branches, and
the obsolete operator test without restoring any CLJS owner. The JVM `.clj`
and portable `.cljc` test discovery remains green, the current page serves its
declared Datastar and CSS assets, and exact searches find no live Scittle,
Shadow CLJS, or CLJS-runner path.

## Progress

- 2026-08-06 deletion sweep unit 1 removed the eight audited browser assets
  plus the readerless `jetbrains-mono-500.woff2` file after exact-name searches
  across `src/`, `test/`, `script/`, `bin/`, `resources/`, and
  `.agents/skills/`. `datastar.js`, `input.css`, and `output.css` retain exact
  consumers. The issue remains open for the CLJS discovery/lint and obsolete
  operator-test cuts in later ordered units.
