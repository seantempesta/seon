---
type: issue
status: open
severity: friction
tags: [issue, sci, evaluation-context, classloader, test-fixture, wave/sci-base-context-derivation]
---

# Base evaluation-context membership still varies with what happened to load

## Problem

`seon.sci.eval/host-namespace!` (`src/seon/sci/eval.clj:998`) resolves a
core-provenanced program row in two steps:

```clojure
(or (find-ns namespace-name)
    (when (classpath-locatable? namespace-name) (require ...) ...))
```

Its own docstring states the invariant: "Membership of the ctx must not depend
on WHICH namespaces something else happened to load first." The second branch
now honours that — `classpath-locatable?` asks
`(ClassLoader/getSystemClassLoader)`, the process's launch classpath, so a
caller's thread-context loader cannot change the answer
(fixed 2026-09-16, see
[the landing note](../../prds/steward-platform/research/evaluation-context-test-namespaces-2026-09-16.md)).

The FIRST branch does not. `find-ns` succeeds for any namespace anything in
the JVM has already loaded, whether or not this process's own classpath can
serve it. In a development JVM that has run in-process tests, the `:test`
source paths reach the JVM through `seon.test/with-test-loader`
(`src/seon/test.clj:104`), so `test/` namespaces accumulate as loaded state.

Measured on cluster `default`, pid 37572, 2026-09-16: **81 `-test` namespaces
already loaded**, against **214 `-test` namespace rows** out of 429 total
namespace rows. Every one of those 81 is installed into any base context
acquired afterwards, and which 81 depends entirely on which tests an earlier
session happened to run. Two clusters in the same JVM, acquired minutes apart,
therefore bind different program surfaces from the same program graph.

This is not currently a failure — installing an already-loaded namespace
cannot throw — so it is friction, not a blocker. It is the same silent-variance
shape the function was written to remove, at the other door.

## What health looks like

Base-context membership is a function of (program graph, this process's
classpath) alone, with no dependence on load history. The candidate change is
to make `find-ns` a fast path for rows the process can serve rather than an
independent admission branch — i.e. gate both branches on
`classpath-locatable?` — but that must be verified against the cluster boot
case `4eb8c6ab4` (2026-08-08) was protecting before it is made.

## Evidence

- `src/seon/sci/eval.clj:998-1036` (`host-namespace!`),
  `src/seon/sci/eval.clj:975` (`classpath-locatable?`).
- `src/seon/test.clj:104-118` (`test-loader` / `with-test-loader`),
  `deps.edn:135` (`:test :extra-paths ["test" "script" "."]`).
- Live counts above, via `mcp__seon__eval_clj` jvm mode on `default`.
