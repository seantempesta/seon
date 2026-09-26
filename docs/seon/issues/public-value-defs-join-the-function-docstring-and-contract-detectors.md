---
type: issue
status: open
severity: friction
created: 2026-09-25
tags: [issue, program-graph, issues, detect]
---

# Public value defs join the function docstring and contract detectors

## Problem

Since P2-A every `def`/`defonce` is a `:seon.fn/sym` row. `seon.issue.detect/declarations`
(`src/seon/issue/detect.clj:143-170`) excludes only `:seon.fn/constant? true`,
so the 78 public computed defs in src/test (measured 2026-09-25 with
`seon.fn.analyzer/analyze` over `src` and `test`) now reach
`public-without-contract` and the "Public function … carries no docstring"
detector. A value def (`(defonce registry (atom {}))`) is not a function.

## Evidence

The row carries the fact: `:seon.fn/defined-by` is `clojure.core/def` or
`clojure.core/defonce` and `:seon.fn/arglists` is `"()"`. Static analysis cannot
tell a point-free function def from a data def; the SCI entry gate can (it
checks the Var's root), and it still requires a contract of the former.

## Owner

`seon.issue.detect`. Candidate: exclude rows with no arglists whose
`:seon.fn/defined-by` is `def`/`defonce`, in the same query that excludes
constants; the docstring standard may still apply to public value defs. Not designed.
