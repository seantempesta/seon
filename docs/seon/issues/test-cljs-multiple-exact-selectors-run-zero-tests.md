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

## Implementation evidence

The dependency-owned hash-promotion defect was fixed on 2026-07-15. Shadow
3.4.11 constructed each
registered var symbol with `(symbol ns name)`, even though metadata `:ns` and
`:name` are already symbols. Exact CLI symbols compared equal while the
selection set used its linear representation, but after nine qualified
selectors promoted it to a hash set their hashes no longer agreed and every
lookup missed. The maintained `seantempesta/shadow-cljs` fork reconstructs the
candidate from canonical strings and carries a ten-selector promotion
regression at commit `d39b6fd3`; commit `9b73a5db` adds standard
`:deps/prep-lib` Java preparation for fresh git consumers.

Root `:cljs` now pins exact fork SHA
`9b73a5db33e5077cb9622fd9eadff5022d66578a`. The source is upstream 3.4.11;
the npm `shadow-cljs` 3.4.10 package remains only the deps-mode CLI shim, while
the Clojure CLI basis selects the forked JVM implementation. The unchanged
shim successfully launched the pinned compiler and exact runner, so no npm
artifact change is required for this JVM-classpath repair.

Fresh-cache proof removed the exact gitlib checkout, ran
`clojure -X:deps prep :aliases '[:cljs]'`, and then resolved
`clojure -P -M:cljs` without manual Leiningen or checked-in classes. The
unchanged `bin/test-cljs` then executed the original ten selectors in one Node
process: 10 tests and 72 assertions, zero failures/errors, compile zero
warnings. Retained log:
`tmp/test-cljs-20260715-031618-3190.log`. This replaces both false-green
zero-test logs without chunking, retrying, or stitching multiple runs.

This proof closes the observed greater-than-eight selector failure, but the
issue remains open at the wrapper boundary. `bin/test-cljs` still accepts a
complete `Ran 0 tests` summary as green for a nonempty selector request, and
its retained report does not compare requested and executed selector counts.
Closure requires the remaining fail-closed validation and reporting acceptance
above, including focused regressions for an unmatched exact selector.
