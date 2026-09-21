---
type: issue
status: open
severity: blocker
created: 2026-09-21
tags: [issue, schema, maintenance, write-admission]
---

# Dead process census identities require live observation fields

Fresh default PID 19386 recorded signature
`399900708dc925ff405ca412d44b68d13884bbf3d5d4975a64dde090dd766063`
at 20:05:01Z. Schedule settlement was refused because component entity 41566
lacked required `:seon.operator.process-census/alive?`.
Evidence: `data/clusters/default/logs/seon.log`; occurrence data-blob entity
41560, digest `b50bec0e2ee156739f9db9baa60ab0e29babe94a293f6a80d2491ea79c065e1f`.
A read-only MCP inspection found nine projected dead-process identity rows,
including PID 62533, with generation, pid, start instant and root only.

`seon.operator.state/process-census` intentionally maps dead processes through
`public-process-identity`. The public `:seon.operator.process-census/dead`
contract declares those identities, and
`seon.maintenance/project-process-census-result` preserves that contract.
The stored `:seon.maintenance.result/process-census-dead` relation instead
declared the full observation component, whose required liveness fields the
producer never promises. Old temporary-root claims exposed the mismatch;
deleting those claims would only hide it.

The repair points this relation at the existing
`:seon.maintenance.result/process-census-identity` component schema, matching
the unresponsive and unclaimed identity projections. Actual process
observations still require both boolean fields. Ref type, cardinality and
component ownership are unchanged. No validator exemption or fabricated
observation is introduced.

The existing canonical fixture and database regression
`seon.maintenance-test/result-projection-is-declared-and-keeps-census-evidence-queryable`
now includes a dead identity and asserts its stored pid/root with no fabricated
liveness datoms. This also exercises nested census projection in the existing
reap regression. Root must gate `seon.maintenance-test` and verify subsequent
live census settlement after publication. No test JVM, operator action or
adoption was performed in this bounded repair. Status remains open pending
those proofs. The separate empty-many schema issue does not explain this
nonempty component's missing scalar fields.
