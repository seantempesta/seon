---
type: issue
status: open
severity: blocker
tags: [issue, source, build]
---

# Keep trusted static analysis sequential

## Problem

The program-index gate forces clj-kondo's shared analysis context through its
parallel mode. A corrupted var usage can therefore refuse a clean source tree,
and rerunning the affected file sequentially does not reproduce the finding.

## Evidence

The full analysis reported the same `:invalid-arity` finding twice at
`test/seon/sci/session_image_test.clj:360:23`: it claimed that
`sci.core/namespace-interns` received two arguments. Column 23 is the outer
`(get ...)` call, which does receive two arguments; the inner
`sci/namespace-interns` begins at column 28 and receives one. The finding
therefore combined the outer call's location and arity with the inner call's
resolved var identity and contract. The duplicate cross-wired finding occurred
only in `seon.fn.analyzer`'s forced `:parallel true` whole-tree invocation; a
sequential file lint was clean.

The pinned clj-kondo `run!` creates one shared namespaces atom and findings atom
before passing that context to parallel analysis
(`reference-code/clj-kondo/src/clj_kondo/core.clj:156-199,235-236`;
`reference-code/clj-kondo/src/clj_kondo/impl/core.clj:385-403`). Its later
var-usage lint joins each recorded call's location and arity to the resolved
definition (`reference-code/clj-kondo/src/clj_kondo/impl/linters.clj:581-652,
734-749`). The impossible mixed record is direct evidence that the parallel
shared analysis did not preserve that join.

## Owner

`seon.fn.analyzer` owns one coherent, trusted clj-kondo invocation for static
program indexing.

## Acceptance

- Complete static analysis runs sequentially and reports no blocking finding
  for the clean first-party source tree.
- A real external invalid-arity call remains an error, proving that the repair
  does not weaken the gate.
- The full `bin/test` gate reaches test execution instead of refusing on a
  cross-wired analyzer finding.
