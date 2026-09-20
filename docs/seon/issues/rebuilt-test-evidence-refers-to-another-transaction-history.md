---
type: issue
status: open
severity: blocker
tags: [issue, test, wave/publication-provenance]
---

# Rebuilt test evidence refers to another transaction history

The source publisher copies run rows from the previous head into a rebuild
from `:db` (`src/seon/cluster/source.clj:350`, `:606`). Besides wildcard
pull truncating a member collection at 1,000, it retains numeric selection
and execution transaction refs from the previous database history.

The execution reader compares membership at `selection-tx` with current
membership (`src/seon/test/runner.clj:2332`). A read-only immutable-database
probe on 2026-09-20 preserved one of one members but this reader refused
the copied admission: selected transaction 536870931, rebuilt basis
536870934. No live connection was changed.

The exact probe and three proposed rulings are in
[the results reuse landing note](../../prds/steward-platform/research/results-reuse-everywhere-2026-09-20.md#publication-history-gate-after-ownership-extension).
Datahike branch parents do not merge temporal indexes
(`reference-code/datahike/src/datahike/versioning.cljc:323`).

Acceptance: a real publication preserves all 1,001 admitted members, their
coverage and original confidence, and the execution/result readers accept
the retained evidence without weakening immutable admission or claim retries.
The orchestrator ruled on 2026-09-20 that publication must reconcile on the
current published lineage. The publication-dissolution lane owns that repair
in `src/seon/cluster.clj:1741`; results-reuse-everywhere must leave that held
region untouched. See item 1 of
[the publication dissolution spec](../../prds/steward-platform/plan/publication-dissolution-spec-2026-09-20.md).

Commit `44b51bab0` replaces the run-preservation wildcard pull with complete
EAV queries in `src/seon/cluster/source.clj:323`. This removes the collection
cut; it does not repair the temporal references. The lineage owner must preserve
the meaning of `:seon.test.run/selection-tx`, the `covered-by` membership at that
transaction, and member claim/completion/termination transaction refs used by
`src/seon/test/runner.clj:2334-2360`.
