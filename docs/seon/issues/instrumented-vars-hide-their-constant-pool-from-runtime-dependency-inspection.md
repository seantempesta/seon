---
type: issue
status: open
severity: friction
tags: [seon.instrument, program-graph, orchard, call-graph]
opened: 2026-09-17
---

# Instrumented Vars hide their constant pool from runtime dependency inspection

## Problem

`seon.instrument/arm-var!` (`src/seon/instrument.clj:617-651`) replaces a
Var's root with a wrapper whose class is `clojure.lang.AFunction$1` and holds
the original as a constructor field. A runtime dependency inspection in the
style of `orchard.xref/fn-deps` (`reference-code/orchard/src/orchard/xref.clj:27-83`)
reads the fn class's `const__` Var fields; on the wrapper there are none, so
1,112 of 4,646 first-party Vars on `default` report no dependencies unless
`:malli.instrument/original` is unwrapped first
(`docs/prds/steward-platform/research/call-graph-sources-and-storage-2026-09-17.md` §1).

## Wanted

The one inspection seam Seon uses for a runtime dependency view (the
constant-pool drift check recommended there) unwraps the armed original
before reading; instrumentation keeps the original addressable through the
metadata key it already sets. No inspection ever reads an armed wrapper as
"no dependencies".
