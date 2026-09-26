---
type: issue
status: open
severity: blocking
created: 2026-09-26
tags: [issue, publication, program-graph, boot]
---

# An analyzer change publishes incrementally against the prior analyzer's rows

## Problem

`seon.cluster/loaded-analyzer-digest` (src/seon/cluster.clj:2403) derives the
analyzer's identity from the rows of the adopted or published commit, which are
the prior program's rows. The commit that changes the analyzer is compared with
itself, so `analyzer-changed?` (cluster.clj:2005-2013) stays false. The
classification is then incremental rather than `:all`. Only the changed files
and their callers are analyzed with the new code. Every other file keeps rows
derived by the old analyzer.

## Evidence (default pid 76020, 2026-09-26)

P2-A (1a7029d81, reverted as 8fa430cc6) widened `seon.fn/function-definition?`,
and boot then refused as follows:

`Program indexing transaction was refused. Nothing found for entity id [:seon.fn/sym seon.cluster/*boot-progress!*]`

This reproduces on `d/with` against the live db using `seon.fn`'s
caller-findings step (`index!`, "caller findings transaction"). That step
reconciles only the `:seon.lint/id` rows of a caller file (`src/seon/cluster.clj`).

- The new analysis attributes `unused-private-var` to the value def
  `*boot-progress!*` (`:seon.lint/fn [:seon.fn/sym seon.cluster/*boot-progress!*]`).
- That declaration row was never derived, because cluster.clj's bytes did not change.
- The same file's full rows (148 rows, 121 prior identities), reconciled
  together, transact cleanly: 285 ops, 20 ms, and `d/with` gives 2803 datoms.
  The declaration then gets a transaction tempid.

## Owner

`seon.cluster` publication classification: the loaded analyzer's identity has
to come from the loaded analyzer code, not from the prior head's rows.
