---
type: issue
status: open
severity: friction
tags: [issue, operator, test, database, wave/operator-status-face]
---

# Operator test status still reads the retired results branch

Observed 2026-09-15 during the test-provenance slice. The canonical result
writer is moving to `:current-src`. `script/seon/fresh_operator.clj:700–714`
still obtains latest results only from `:test-results`, returning an empty
vector when that branch is absent. Thus status misses newly committed evidence
or shows old evidence when the retired branch remains.

The reader should query test definitions and latest linked results from the
same current-src database. Regression: a store with only :db and :current-src,
a recorded result linked to a run, and no :test-results branch must report that
result. This operator reader is outside the test-provenance lane's owned paths.
