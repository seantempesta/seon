---
type: issue
status: resolved
severity: friction
tags: [issue, database]
---

# UDS NIO selector hot loop ran on 21 reflective calls

## Problem

Writer boot logged 21 reflection warnings, all in
`src/seon/db/transport/uds.clj` — the selector event loop
(`selectedKeys` / `interestOps` / `attachment` / `isValid` / `isReadable`
/ `isWritable` / `wakeup` / `cancel` / `close`). Every selector event paid
reflective dispatch on the transport's hottest path.

## Root cause

The reflection was deliberate: babashka's SCI allowlist omits
`java.nio.channels.Selector` and `SelectionKey`, and the operator
(`bin/seon` → `seon.dev.cli` → `seon.dev.branch`) loads this namespace
under bb, where SCI fails at analysis on any type hint naming a
non-allowlisted class. Plain type hints in a bb-loaded `.clj` file are
impossible.

## Fix

Renamed the file to `src/seon/db/transport/uds.cljc` (the established
`#?(:bb …)` pattern already used by `seon.db.branch`, `seon.db.id`, and
`seon.db.protocol`) and routed the 21 sites through private interop
wrappers whose `:bb` branch stays reflective (SCI resolves at runtime
anyway) while the `:clj` branch carries `^Selector` / `^SelectionKey`
hints. No behavior change; the CLJS pod side remains `uds.cljs`.
`bin/lint` gained a computed rule so clj-kondo lints `.cljc` files
shadowed by a sibling `.cljs` as CLJ-only instead of flagging dead
conditional branches.

## Proof

- `clojure -M:writer -e "(require 'seon.db.transport.uds)"` — zero
  reflection warnings (was 21).
- Full `bin/test-writer`: 231 tests, 1891 assertions, 0 failures,
  0 errors; zero reflection warnings in the run log.
- `bb --config bb.edn -e "(require 'seon.dev.branch)"` loads, and
  `bin/seon status` runs the operator end-to-end.
