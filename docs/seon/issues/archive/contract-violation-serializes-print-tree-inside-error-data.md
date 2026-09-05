---
type: issue
status: resolved
severity: friction
tags: [issue, render, sci, class/n1, wave/instrumentation-error-data]
---

# Keep contract-violation evidence as data

## Problem

An instrumented function's contract violation stores its admitted problem
tree and offending arguments as EDN strings inside `:seon.error/data`. The
agent sees a flat error containing a second serialized face grammar and must
parse strings to recover data the runtime already had structurally.

## Evidence

On 2026-08-04, isolated cluster `edgefaces0804` evaluated this through the
real SCI evaluator:

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

## Recurrence on routine operator faults, 2026-08-10

Observer lane, cluster `default` (pid 91415). `:seon.error/data-edn` sizes for
the seven error facts on a freshly booted cluster:

```clojure
[[:seon.operator/reap-incomplete          177293]
 [:seon.operator/process-census-incomplete 175215]
 [:seon.operator/collection-incomplete      19631]
 [:seon.operator/failed                      9859]
 [:seon.cluster.reply/unreadable             1860]]
```

Two 175 KB print trees serialized into error data, on **routine scheduled
maintenance faults** rather than an exotic contract violation. This is below
the 4.25 MB worst case of the original report but above the 158 KB the
2026-08-08 observer measured, and the shape is the same: evidence stored as
serialized print syntax instead of bounded ordinary data. These faults recur
on every boot, so the cost is not incidental.

## N1 disposition — 2026-08-12

Still open outside this lane. `seon.instrument/violation` must retain bounded
ordinary problem/argument evidence rather than `:seon.error/data-edn`, and
`seon.error/instrumentation-prose` must render those values through the shared
floor. `4bc8104d8` bounds terminal output but deliberately does not repair
already serialized intermediate representation stored inside the error.

## Resolution — 2026-08-13

`seon.instrument/violation` now admits the expected and offending values once
as ordinary semantic data. Its evidence contains one representative problem
map and a total problem count; it no longer stores `:seon.instrument/edn`,
`:seon.instrument/value-edn`, rendered text, or a `:seon.print/face` tree.
`seon.error/instrumentation-prose` consumes the semantic data directly, so no
error consumer calls `edn/read-string` on a newly constructed error field.

Before:

```clojure
{:seon.instrument/problems
 "#:seon.print{:face :seon.print/vector, :items [#:seon.print{...}]}"
 :seon.instrument/args "[42]"}
```

After, read verbatim from the live contract refusal:

```clojure
:seon.error/diagnostic-evidence
#:seon.instrument{:problem-count 1,
                   :problems [#:seon.instrument.problem{:message "should be a string"}]}
:seon.error/diagnostic-offending [seon.cluster.run/open-tx]
```

`contract-problems-are-semantic-once-never-a-serialized-print-tree` retains
the constructor regression, including an assertion that no
`:seon.print/face` survives anywhere in the error data.
