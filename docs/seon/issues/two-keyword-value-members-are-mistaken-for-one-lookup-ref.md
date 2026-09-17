---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, datahike, schema, reset, write-admission]
---

# Two keyword value members are mistaken for one lookup ref

The reset fast run `tmp/reset-edge-fast-8.log` failed canonical population on
`:seon.fn.arity/input-refs #{:seon.agent/id :seon.db/db}`. This is a legitimate
set of observed schema names. Datahike `db/transaction.cljc:718-737`
`maybe-wrap-multival` treats two members beginning with a unique identity
attribute as a lookup ref even on a non-ref attribute. Seon's `write-many-values`
mirrored that guess, refusing a set as if it contained another set.

The reset worktree confines lookup syntax to actual ref attributes during
validation and emits explicit `:db/add` members for ambiguous value collections
at the existing encoding seam. Nested entity maps and expanded transaction
function output cross that same seam. No fake member or stored marker is added.
A canonical regression in `seon.reset-edges-test` exercises nested writer output
and exact stored membership. The armed canonical regression passed in fast 22 (189 tests, 1,811
assertions, zero failures/errors across the selected namespaces). The reset
implementation is still awaiting its coherent publication commit and cold gate. The default cluster and dependency fork are unchanged.
