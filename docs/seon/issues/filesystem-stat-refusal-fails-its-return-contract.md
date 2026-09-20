---
type: issue
status: open
severity: friction
created: 2026-09-22
tags: [issue, effect, test, wave/contract-gate]
---

# Filesystem stat refusal fails its return contract

## Problem

An outside-root filesystem refusal fails the armed return contract before
the shell caller can return its working-directory diagnostic.

## Evidence

The slice 1 fast snapshot at `f08558cb5` ran
`seon.shell.jvm-test/cwd-outside-roots-refuses-before-process-start` under
armed contracts. At `2026-09-20T18:42:40.979800Z` it ended with an uncaught
return-contract exception: `seon.fs.jvm/stat` returned a map without
`:my.fs/path`, which Malli required. Its caller is
`seon.shell.jvm/cwd-path` (`src/seon/shell/jvm.clj:41`). The test body and
both production namespaces were unchanged by the slice.

`seon.fs.jvm/path-plan` refuses an outside-root path through `refuse!` and
`flat-error`. That constructor still emits `:seon.error/kind` and places the
path in the diagnostic data. `cwd-path` recognizes the current error shape
with `:seon.error/at`, `/layer`, and `/operation`, but instrumentation rejects
the returned value before that caller can inspect it.

## Owner

The filesystem error constructor and the declared `stat` return contract
must agree on the current flat refusal shape.

## Acceptance

The existing shell regression
must reach its typed working-directory refusal under armed contracts and
prove that no process started. This is outside the operator/publication
slice; no filesystem or schema code was changed here.
