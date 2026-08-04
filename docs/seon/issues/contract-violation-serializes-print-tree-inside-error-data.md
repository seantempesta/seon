---
type: issue
status: open
severity: friction
tags: [issue, instrumentation, error, repl, render]
---

# Keep contract-violation evidence as data

## Problem

An instrumented function's contract violation stores its admitted problem
tree and offending arguments as EDN strings inside `:seon.error/data`. The
agent sees a flat error containing a second serialized face grammar and must
parse strings to recover data the runtime already had structurally.

## Evidence

On 2026-08-04, isolated cluster `edgefaces0804` evaluated this through the
real SCI door:

```clojure
(my.fs/read 42)
```

The bounded agent face included:

```clojure
{:seon.instrument/problems
 "#:seon.print{:face :seon.print/vector, :items [#:seon.print{...}]}"
 :seon.instrument/args "[42]"}
```

The headline was readable, but both evidence fields were strings. The shape
is deliberate in current source: `src/seon/instrument.clj:258-272` stores
`:seon.instrument/edn` and `:seon.instrument/value-edn`; the regression
`contract-problems-have-a-readable-inline-face-and-a-complete-tree` in
`test/seon/instrument_test.clj` explicitly requires a string containing
`seon.print{:face`.

## Owner

`seon.instrument/violation` owns construction of the flat error value;
`seon.error/instrumentation-prose` owns its text projection.

## Acceptance

Contract evidence remains bounded ordinary data inside `:seon.error/data`.
The error producer renders that data for a reader, no consumer calls
`edn/read-string` on an error field, and regressions assert the semantic
problem and argument shapes rather than serialized print-node syntax.
