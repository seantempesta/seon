---
type: issue
status: resolved
severity: friction
tags: [issue, runtime, wave/no-crash]
---

# Keep `delete-recursively!`'s two-arity call valid when instrumented

## Problem

The public two-arity `seon.fs/delete-recursively!` calls the instrumented Var's
three-arity form with `nil`, but that form's contract requires an options map.
Every otherwise-valid two-argument deletion therefore refuses after
`seon.instrument/apply!`.

## Evidence

An isolated instrumented `reapnil` cluster reproduced the scheduled reaper's
exact diagnostic on 2026-09-03. The complete io-prepl trace reaches
`src/seon/fs.clj:81`, where `(delete-recursively! root target nil)` re-enters
the wrapped public Var. Malli reports the three-arity options member as the
offending `[nil]`; both string paths were present. The exception occurs before
the deletion body and is independent of whether an operator claim has a root.

The two-arity operator call sites are `src/seon/operator.clj:294` and
`src/seon/operator.clj:571`; other namespaces also use the public two-arity
surface.

## Owner

`seon.fs/delete-recursively!` owns the two- and three-arity contract and the
shared implementation those arities enter.

## Acceptance

After `seon.instrument/apply!`, a two-argument recursive deletion removes an
in-root directory, preserves a symlink target, and returns `nil`; the direct
three-argument form retains its required bounded-progress options contract.

## Resolution

Resolved 2026-09-03. Both public arities now enter the private
`delete-recursively-impl!` directly, so the two-argument arm never re-enters
the wrapped public Var with an undeclared third argument. The direct
three-argument arm still requires the bounded-progress options map.

`bin/test seon.instrument-test seon.fs-test seon.context-test` ran 26 tests
containing 178 assertions with 0 failures and 0 errors. A fresh reforked
`instrument-absent` cluster then returned `nil` from the instrumented
two-argument call, removed its owned root, and preserved the symlink target.
