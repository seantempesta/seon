---
type: issue
status: open
severity: blocker
created: 2026-09-19
tags: [issue, operator, publication, observability, class/absence-as-health]
---

# An aborted publication leaves no record, and the silence bound spans a phase with no event

## Problem and evidence

`seon.cluster/report-source-progress!` (`src/seon/cluster.clj:93`) aborts a
running publication once the prepl `PrintWriter` has recorded an error, so a
client that times out takes the server-side work down with it. That is the
intended containment. What is missing is any record of the abort: the four
`bin/seon init --dev default` runs on 2026-09-19 (operator logs
`init-init-42258`, `-45646`, `-56561`, `-59084`) each ended in the client's
`:seon.fresh-operator/prepl-response-silent`, and on the server side there is
no fault fact, no `data/clusters/default/logs/seon.log` line (it holds only
boot phases), and no result file. Diagnosing required sampling the JVM's
threads with `jcmd` during a fifth run
(`docs/prds/steward-platform/research/adoption-silence-diagnosis-2026-09-19.md`).

The bound itself fires inside phases that legitimately exceed it with no event
of their own: the population commit (`seon.fn/commit-index-phase!`,
`src/seon/fn.clj:2547`, one transaction of 74,366 operations whose progress
event is emitted only after it returns; 26–33 s measured) and the development
acquisition (`seon.cluster/acquire-development!`, `src/seon/cluster.clj:2386`).
`:seon.config.operator/event-silence-backstop-ms` is 30,000
(`config/default.edn:323`). A bound firing is a bug report naming what never
arrived (AGENTS.md §2.3); today it names the last event before the phase.

## Acceptance

1. When a publication is aborted because its observer departed, the outcome
   is a durable fact or, at minimum, an operator operation log line naming the
   phase, the elapsed time and the reason; `bin/seon status` can show it.
2. Progress events are emitted when the population transaction is submitted
   and when development acquisition starts, so the silence bound measures the
   writer and the acquisition separately from the compile.
3. A regression on the canonical fixture: a publication whose observer's
   writer errors mid-phase records the abort; a phase longer than the bound
   with events inside it does not fire the bound.

## Owner

The publication-repair owner (parallel session, 2026-09-19,
`publication-projection-repair-2026-09-19.md`) for items 1–2 if in scope of
that repair; otherwise the operator lane of wave 0 in
`docs/prds/steward-platform/plan/namespace-agents-plan-2026-09-19.md`.
