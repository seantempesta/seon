---
type: defect
status: resolved
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

## 2026-09-08 acquisition regression

The current refresh caller passes the cluster database, not the source
snapshot. The armed canonical regression instead reproduced missing
`:seon.schema.admission/source` on acquisition's reconstructed function
rows. That refused both the invalid row and its valid sibling.
The repair carries the cluster-derived provenance, contains typed and
thrown row failures, and uses the existing cluster fault committer during
development acquisition. The scoped gate passed 1 test / 10 assertions,
including a source snapshot without the agent namespace, one durable fault,
and a callable valid sibling. Full live convergence remains to be verified;
the first explicit adoption attempt failed earlier at `seon.fn/exact-source`.
Final in-place adoption converged on commit
`6aa08a34-675a-5c0b-b268-afdbf5d35d21`; both source and cluster commit IDs
were independently read equal. Repair commit: `152f11a68`.
Evidence: [adoption landing note](../../../prds/context-generation/research/adoption-rows-landing-2026-09-08.md).
