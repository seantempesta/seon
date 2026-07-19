---
type: issue
status: resolved
severity: friction
tags: [issue, agent]
---

# Report Invalid Sources in Multi-File Post-Edit Feedback

## Problem

When any resulting Clojure source in a multi-file edit failed to parse, the
post-edit hook silently skipped changed-test enqueueing. Its advisory also
pointed at a mutable latest-report path without explaining that later
generations could replace the result.

## Evidence

The namespace-docstring repair exposed syntactically valid namespace-clause
damage that required a separate structural check. Hook inspection also found
that invalid source suppressed changed-test enqueueing without an explicit
message and that non-Clojure build inputs could enter the syntax-check path.

Commit `86a96ddf` makes the hook list every invalid resulting source, explains
why changed tests were not queued, excludes non-Clojure build inputs from
source parsing, and labels `latest.report.edn` as mutable. Its regression suite
also proves that multi-file syntax and docstring findings are aggregated.

## Owner

`bin/seon-hook` owns immediate edit feedback; `seon.dev.changed-test` owns the
asynchronous affected-test generation.

## Acceptance

- Every resulting Clojure source in a multi-file patch receives syntax and
  docstring validation.
- Invalid sources produce explicit advisory feedback and suppress enqueueing.
- Build inputs are not parsed as Clojure merely because they affect tests.
- Feedback identifies the queued generation and describes the mutable report.
- Focused hook tests pass.

All acceptance criteria are satisfied by commit `86a96ddf`; the focused gate
passed 7 tests with 23 assertions and no failures or errors.
