---
type: issue
status: open
severity: friction
created: 2026-09-22
tags: [program-graph, publication, reporting, class, absence-as-signal]
---

# The unresolved-callers report lists every library callee

## Problem

A from-zero publication at HEAD `7924f4dae` (isolated root, 381 inputs,
613 findings, 175 s) returned an `:seon.program/unresolved-report` with
**42,767** `:seon.program/unresolved-callers` entries. By callee namespace:
`clojure.core` 38,902, `clojure.string` 922, `clojure.java.io` 515,
`clojure.core.async` 426, `datahike.api` 272, `sci.core` 237,
`malli.core` 228 … and, far down, `seon.cluster.source-test` 17 and
`seon.fresh-operator` 12 (`tmp/head-root/init-zero.edn`, produced by
`bin/seon --root tmp/head-root init --result-file …`).

The report treats "callee has no `:seon.fn` row" as "unresolved". A library
function never has a row, so the report is noise at a ratio of about
1,400 : 1 and the first-party names it exists to surface are invisible.
This is the project's recurring class: a check that reads absence of a fact
as a finding, without asking whether the fact could ever exist.

## Fix shape (one Datalog clause, no list)

A callee is unresolved only when its NAMESPACE is indexed and its function
is not: the callee symbol's namespace has a `:seon.ns` row (the program
graph declares that namespace) and no `:seon.fn/sym` row carries the
symbol. A namespace with no `:seon.ns` row is external by construction and
never reported. No hand-maintained library list, no name prefix rule.
The publication result then reports only first-party names, and the
regression asserts a from-zero publication of the canonical fixture
reports zero unresolved callers into `clojure.core`.

`seon.fresh-operator` (a `script/` namespace, not a program input) and the
17 `seon.cluster.source-test` names are the real entries to read once the
noise is gone.
