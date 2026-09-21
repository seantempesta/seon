---
type: issue
status: resolved
severity: friction
tags: [issue, test, database, wave/test-fixture]
---

# Preserve failed live test results without walking native Datoms

## Problem

Several `seon.test/run` attempts failed while committing their result, replacing
the test's own diagnostic with `empty is not supported on Datom`.

## Evidence — 2026-09-16

Default PID 69622, MCP JVM mode, during program-provenance verification:

```clojure
(seon.test/run #'seon.fn-test/static-findings-are-replaced-with-their-program-rows
               (seon.operator/connection "default"))
```

The complete cause chain named `datahike.datom.Datom.empty`,
`clojure.walk/postwalk`, `seon.db/jdk-integers->long` (`src/seon/db.clj:2503`),
`seon.db/transact-call` (`:2835`), and
`seon.test.runner/commit-results!` (loaded frame `runner.clj:1497`). This
happened while the canonical fixture had an older program-identity schema;
subsequent runs recorded that fixture refusal successfully, so the triggering
payload still needs isolation. The concurrent runner edits were preserved;
this observation does not attribute their authorship or assert they caused it.

The narrower successful tuple test subsequently recorded 3 passes, zero
failures/errors (run 68282). Thus this is not a claim that all result recording
is unavailable. Exact verification context:
[program-provenance landing](../../prds/steward-platform/research/program-provenance-2026-09-16.md).

## Owner

Test-result transaction construction and the database transaction value
normalizer. Preserve native dependency values at their existing codec boundary;
do not introduce a second test-result path.

## Acceptance

A canonical failing in-process test with dependency Datoms in its failure
evidence records its original failure and returns the stored result; result
recording does not throw a secondary host-layout error.

## Resolution — 2026-09-16

Resolved by `f2d537187` at the existing `commit-results!` transport seam.
The reach-digest prototype carried its native database value into transaction
normalization. The final implementation derives the digest map from that
value, then removes the database from the transported completion. It does
not add a native-Datom codec or change database normalization.

`seon.test-reaching-test/failed-results-with-native-datoms-remain-recordable`
uses an actual canonical fixture Datom as the failing assertion's actual
value. It records the original diagnostic and digest: 4/0/0 before editing
(run 43601) and after hot loading (run 59930, basis 536871399), through
`(seon.test/run #'seon.test-reaching-test/failed-results-with-native-datoms-remain-recordable
(seon.operator/connection "default") options)`. An intervening run reported
worker-global wrapper drift; that failure was retained, not suppressed.
Complete adoption remains bounded by the reach-digest landing note.
