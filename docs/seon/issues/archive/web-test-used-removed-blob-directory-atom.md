---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent, cljs, web]
---

# Keep web tests on the current blob storage view

## Problem

The web capability test fixture still dereferenced and reset the removed
`my.blob/!dir` atom after blob storage became one explicit writable-plus-bases
view. The test suite therefore failed before any web behavior ran.

## Evidence

The complete isolated CLJS gate emitted three undeclared-var warnings at
`test/seon/agent/web_test.cljs` and then two async failures with
`No protocol method IDeref.-deref defined for type undefined`. The current
`my.blob` test seam is `!storage-view`; `test/my/blob_test.cljs` already
demonstrates its hermetic save, replace, and restore lifecycle.

## Resolution

The web fixture now saves and restores `my.blob/!storage-view` and installs a
pid-scoped view with its fixture directory as the sole writable archive. This
strengthens the existing test seam without restoring the retired atom.

## Verification

The focused `seon.agent.web-test` namespace passed 10 tests and 39 assertions
without undeclared `my.blob/!dir` warnings. The complete isolated CLJS
checkpoint then passed 1,330 tests and 6,344 assertions with zero failures or
errors.
