---
type: issue
status: open
severity: blocker
tags: [issue, cljs, flow]
---

# Multiple exact CLJS selectors can report a false-green zero-test run

## Problem

`bin/test-cljs` documents comma-separated exact selectors and also accepts
multiple `--test=` arguments, but neither form currently executes more than
one exact var correctly. Both can compile the intended namespace graph and
then report `Ran 0 tests` as a passing run. That is false-green evidence for a
focused multi-var checkpoint.

## Evidence

Database-browser Slice B supplied ten exact vars. One comma-separated
`--test=` invocation compiled the two intended namespaces but the node runner
reported 0 namespaces and 0 assertions in
`tmp/test-cljs-20260715-024153-54594.log`. Supplying ten separate `--test=`
arguments produced the same result in
`tmp/test-cljs-20260715-024235-56128.log`. `node out/test/test.js --list`
showed all ten names, and each name passed when invoked alone, proving this is
selector composition rather than missing compilation.

`bin/test-cljs` splits comma input to build `:namespaces`, then forwards the
original test arguments to Shadow's node runner. The wrapper must validate
that its compile-graph selector grammar and runtime selector grammar describe
the same requested set; a zero-test result for a nonempty selector set must
fail closed.

## Owner

The one `bin/test-cljs` wrapper and Shadow node-test selection boundary. Do not
add a second runner.

## Acceptance

- Two exact vars in one namespace and two exact vars across namespaces each
  run exactly the requested vars through one documented command.
- The compile graph contains every requested namespace once.
- A nonempty exact-selector request that matches zero tests exits nonzero.
- The retained summary reports the requested and executed selector counts.
