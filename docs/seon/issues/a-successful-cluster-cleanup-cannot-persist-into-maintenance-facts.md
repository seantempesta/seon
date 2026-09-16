---
type: issue
status: open
severity: friction
tags: [issue, operator, maintenance, schema, class/absence-as-health]
---

# A successful cluster cleanup cannot persist into maintenance facts

Found by the operator-test repair on 2026-09-16 (`ec260cd2e`): the cleanup
result's `:seon.operator.cluster-cleanup/collection` slot was declared
`:seon.error/value` only, while `collect-store!` returns a verified
`:seon.operator.collect/result` or throws — so an armed output contract
refused the function's own honest return. That key is now
`[:or :seon.operator.collect/result :seon.error/value]`. The maintenance
mirror of the same slot,
`:seon.maintenance.result/cluster-cleanup-collection-component`
(`resources/seon/schemas/seon.maintenance*.edn`), still models it as
`[:map [:seon.error/kind] [:seon.error/message]]`, so persisting a SUCCESSFUL
cluster-cleanup result into `:seon.maintenance.result/*` facts refuses the
same way — the root maintenance portfolio can record only failed cleanups
(absence of success read as health).

Fix: the maintenance component declares the same `[:or result error]`
shape (one declaration referenced twice per §2.5, never a second copy), and
one regression persists a successful cleanup result through the maintenance
writer.
