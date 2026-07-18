---
type: issue
status: open
severity: friction
tags: [issue, cljs, flow]
---

# Include new CLJS namespaces in changed-test runtime artifacts

## Problem

The automatic changed-test path selected a newly added `.cljc` namespace and
its test, then launched an immutable CLJS bundle whose entrypoint referenced
the namespace while its `cljs-runtime/` directory omitted the compiled module.
The test door reported a false infrastructure failure until an explicit focused
compile rebuilt the bundle.

## Evidence

- Editing new `src/seon/db/coordinate.cljc` and
  `test/seon/db/coordinate_test.cljc` selected both writer and pod boundaries.
- The writer ran three tests/11 assertions successfully.
- The pod failed before tests with `ENOENT` for
  `cljs-runtime/seon.db.coordinate.js` under the selected content-addressed
  bundle.
- `bin/test-cljs --test=seon.db.branch-test` compiled one new file and then
  passed three tests/11 assertions.
- Later edits widened and compiled normally, confirming the source itself was
  valid and the first retained artifact was incomplete.

## Owner

The one immutable CLJS test artifact builder and fingerprint in
`bin/test-cljs` plus `script/seon/dev/changed_test.clj`. The fix must strengthen
that path rather than add a second runner or an unconditional full compile.

## Acceptance

- Adding a new source namespace and focused test produces a bundle containing
  every runtime module referenced by its entrypoint.
- The artifact fingerprint changes when the selected namespace closure gains a
  previously absent source file.
- A focused new `.cljs` and `.cljc` namespace each pass through
  `bin/seon test changed --path ...` without an explicit prior compile.
- Existing exact-fingerprint reuse remains warm and a genuinely incomplete
  bundle fails before execution with a bounded diagnostic naming the missing
  artifact member.
