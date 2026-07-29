---
type: issue
status: open
severity: blocker
tags: [issue, reader, program-graph]
---

# Account for declarations inside executable top-level forms

## Problem

An unconditional executable top-level `(do (defn ...))` produces neither a
function row nor a loud refusal. `seon.fn/unadmitted-functions` detects absence
only when the production reader first emits `:seon.fn/arglists`; the same reader
does not lift declaration facts from the nested form, so both the row and its
sentinel disappear together.

That is the exact recurring silent-drop class the indexer change claims to
close.

## Evidence

- `src/seon/sci/reader.cljc:289-304` lifts declaration facts only from the
  current top-level form.
- `src/seon/fn.clj:28-44` treats an arglists-without-symbol event as the complete
  census of unadmitted declarations.
- `src/seon/fn.clj:58-82` therefore has no independent declaration signal
  against which to compare reader output.
- An isolated source file containing
  `(ns audit.nested) (do (defn f [n] n))` returned only its namespace row from
  `seon.fn/rows`. It returned normally; no `:seon.fn/index-refused` was raised.
- The current `src/` and `test/` census contains no such executable nested
  declaration, so this does not change the verified 1,242-row count.

## Owner

`seon.sci.reader` declaration-event production, consumed by `seon.fn/rows`.

## Acceptance

- An executable top-level `do` declaration becomes a complete function row, or
  unsupported declaration-bearing syntax is refused loudly.
- A refusal names file, line, source, and reason.
- Loudness detection does not depend solely on the reader facts whose absence
  is being detected.
- Nested inert declarations under quote or literal data remain inert.
- Recurring proof covers public and private declarations, duplicates, and
  `.cljc` reader conditionals.
