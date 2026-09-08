---
type: defect
status: open
severity: blocker
tags: [dev-cluster, adoption, sci, acquisition, class/p1]
---

# Development adoption refuses the whole cluster when an agent-installed row's namespace is read from the source database

Observed 2026-09-08 15:48: every `bin/seon init --dev default --changed …`
ended with "Development SCI acquisition refused committed definitions."
The refusal names `seon.sci.eval/install-row!` at `eval.clj:1651` with
`:seon.error/diagnostic-offending [#:seon.program{:row #:seon.ns{:name nil}}]`
for `my.agents.concurrency.concurrency-b/largest`, a function an agent
installed during the multi-cluster-concurrency lane's live probe on
`default`.

In the live `default` database that function's namespace entity IS named
(`my.agents.concurrency.concurrency-b`, steward set, aliases present). The
adoption path reads the row's namespace from the published source snapshot
(`:current-src` fork), where agent-installed namespaces do not exist, so
the name is nil and the contract refuses. One agent-installed function then
takes down the entire development loop: no edit can be adopted until the
rows are retracted by hand (done at 15:55 to unblock).

Related: `development-adoption-can-mix-host-and-sci-generations.md`.

## Fix

- Acquisition resolves an agent-installed row (`:seon.schema.admission/source
  "agent"`) against the CLUSTER's database, never the source snapshot; the
  source snapshot supplies only first-party program rows.
- A single row that cannot be installed is a typed fault naming the row,
  committed through the fault committer, and the acquisition continues;
  it never refuses the cluster. Regression: one agent row with a missing
  namespace in a canonical fixture; adoption succeeds and one fault names it.
