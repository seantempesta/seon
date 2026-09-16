---
type: issue
status: open
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
