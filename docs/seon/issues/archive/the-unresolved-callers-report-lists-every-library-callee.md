---
type: issue
status: resolved
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

## Slice 4 implementation and evidence — 2026-09-23

`seon.fn/unresolved-callers` now joins the callee symbol's namespace to
`:seon.ns/name` before reporting the missing function. The canonical regression
`seon.fn.unresolved-test/unresolved-calls-require-an-indexed-callee-namespace`
proves both sides: an indexed namespace's missing function is reported and
vanishes after declaration; external `clojure.core` calls are excluded.
The selected fast run `d93d8f6b33d5` passed 16 tests / 80 assertions.

The scratch publication has 6,697 analyzed declarations and reports 124
unresolved calls, rather than the historical 42,767 library-dominated entries.
A query on a newly acquired post-publication database took 3,080.291 ms. A
repeat on the same prior value took 7.397 ms; that repeat is not a cold query
cost. This is still O(program calls + recorded test reach), appropriate only
when asking for the whole report. Incremental publication already omits the
whole report; cold publication includes it.

The regression took 20.543 s after the fixture was acquired, versus 50.036 s
when it was first. Its two synthetic declaration analyses, two writes and two
whole-program reports remain a performance finding, with an explicit long-test
bound and reason. The fast result is not the orchestrator's cold proof.
