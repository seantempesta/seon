---
type: issue
status: open
severity: blocker
tags: [issue, testing, reader, program-graph]
---

# Make function coverage independent and cardinality-preserving

## Problem

`every-declared-function-in-the-tree-becomes-one-row` is described as an
independent, per-file, one-row-per-declaration invariant. It consumes the same
`seon.sci.reader/read` event stream as production, trusts that reader's lifted
namespace, and converts both expected declarations and actual rows to sets.

The test can therefore agree with a reader omission and cannot detect
duplicate declarations collapsed to one symbol. The current source tree is
complete, but the recurring proof can still pass for the named failure class:
absence of the production signal is read as health.

## Evidence

- `test/seon/fn_test.clj:63-67` obtains the expectation's forms from
  `seon.sci.reader/read`, the production reader used by
  `src/seon/fn.clj:82-89`.
- `test/seon/fn_test.clj:69-84` counts names from those events with `into #{}`.
- `test/seon/fn_test.clj:94-120` groups all rows by namespace into sets and
  compares a file's set with a global namespace set. File identity, declaration
  location, and multiplicity are absent.
- An independent `clojure.tools.reader` scan selected the JVM branch of `.cljc`
  conditionals and found 1,242 declarations, 1,242 unique symbols, and no
  duplicates in the current tree. A fresh database held the same 1,242
  symbols, with zero per-file or per-namespace differences. That calibrates
  the current result; it does not make the recurring proof honest.
- Deliberately breaking the reader so that it emits no event for an executable
  nested declaration also removes that declaration from this test's expected
  set.

## Owner

`seon.fn-test/every-declared-function-in-the-tree-becomes-one-row` and its
declaration census.

## Acceptance

- The expected declaration census parses source independently of
  `seon.sci.reader/read`, lifted `:seon.fn/*` facts, and `seon.fn/rows`.
- It preserves file, namespace, line or source occurrence, and multiplicity.
- Duplicate same-symbol declarations cannot pass through set collapse; either
  exact occurrence accounting or an explicit, tested last-definition rule
  owns them.
- Reader conditionals use the selected JVM branch and remain independently
  checked.
- A deliberately broken reader attribution or omitted declaration produces a
  mismatch.
