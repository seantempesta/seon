---
type: issue
status: resolved
severity: friction
tags: [issue, test, render, class/n1, wave/dev-tooling-face-hygiene]
---

# The changed-test selector prints its report as one enormous line

## Problem

`seon.dev.changed-test/run-changed!` prints its complete report — including
every repository-wide lint warning in `:findings` — as a single `prn` line
(observed multiple kilobytes long). Reported by the gate-fix-blob lane
(2026-08-06, standing ugly-output order) and hit by the orchestrator the same
day (a ~2 MB tool result). The reader needs boundary status and counts; the
whole-tree lint findings drown them.

## Expected

A readable multi-line face: per-boundary status/counts/log-path lines, then a
BOUNDED findings summary (count by level, first N with file:line) with the
complete findings reachable in the retained report file it already writes
(`tmp/test-changed/latest.report.edn`). Owner:
`script/seon/dev/changed_test.clj` report printing.

## Acceptance

A changed-test run over one file prints a face a human reads at a glance;
complete findings remain in the retained EDN report.

## N1 disposition — 2026-08-12

Still open outside this lane. The changed-test report constructor must retain
its EDN report by identity and send its summary through a declared render
producer/profile; the CLI leaf should print that bounded face rather than the
one-line `pr-str` of the complete report.

## Verified at HEAD (2026-09-16, N1 verification)

**RESOLVED.** `script/seon/dev/changed_test.clj` no longer `prn`s the
complete report. The report face at `:329-378` is built line by line —
per-boundary status and failure lines, a `report:` line naming the retained
EDN file, a dependency-analysis line, and a `widening:` line — and its
findings section is bounded by construction:

```clojure
(when (seq findings)
  (str "\nclj-kondo findings:"
       (apply str
              (for [finding (take 20 findings)]
                (str "\n  " (:filename finding) ":" (:row finding) ":"
                     (:col finding) " [" (name (:level finding)) "/"
                     (name (:type finding)) "] " (:message finding))))
       (when (< 20 (count findings)) "\n  …")))
```

`(take 20 findings)` with an explicit `…` continuation is the bounded
summary this note asked for, and the complete findings remain in the
retained report named by the `report:` line. No test JVM was run for this
verification; the face is read from its one constructor, which is where the
defect lived.
