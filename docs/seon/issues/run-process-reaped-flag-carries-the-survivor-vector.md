---
type: issue
status: resolved
severity: defect
tags: [issue, operator, process]
---

# `run-process!` reports `:reaped?` as the vector of survivors

`5b5227bc1` changed `seon.cluster.process/terminate-subprocess!` to return the
identities still alive after cleanup (empty when every exit was observed), but
`run-process!` still assigns that value to
`:seon.operator.subprocess/reaped?` (`src/seon/cluster/process.clj:304-321`).
A fully reaped deadline now reports `:reaped? []`, and any survivor is reported
as a truthy vector, the opposite of the flag's meaning.

Evidence: root run `550de52699b0` (`tmp/orchestrator/verify-1955.out.d/009.txt`),
`seon.cluster.subprocess-test/foreign-process-deadline-covers-output-capture` and
`foreign-process-deadline-reaps-a-late-descendant`: `(true? []`) is false. The
same assertion stands in `test/seon/dev/publication_test.clj:34`.

Fix at the owner: `:reaped? (empty? survivors)` and name the survivors under
their own key, so callers keep owning them (the commit's intent).

## Resolution — 2026-09-25

`run-process!` now reports `:reaped? (empty? survivors)` (lane delete-duplicates round 3). REPL: the edited
namespace, loaded in a scratch namespace on default, answers a 200 ms deadline over `/bin/sleep 5` with
`{:phase :process-exit :reaped? true}` in 207 ms; Babashka's `seon.cluster.process` gives the same.

