---
type: issue
status: resolved
severity: blocker
tags: [schema, admission, publication]
---

# Source progress callback refused schema admission

On 2026-09-17, Stage 2's prospective schema edit was refused with
`Schema population refused :seon.source/progress! (dishonest-generator)`.
The declaration used `[:fn clojure.core/ifn?]` without a generator.
`seon.schema.edn/assert-predicates!` correctly refuses that declaration.

The callback now declares its actual `[:=> [:cat :string] :nil]` contract.
Its caller is `seon.cluster.source/publish!`; the supplied implementation
is `seon.cluster/report-source-progress!`, whose explicit return is nil.
This removes the unconstrained predicate instead of adding a generator for it.

Verification: a HEAD `5dd6ef7cc` worktree plus the Stage 2 draft and this
schema correction completed source publication into an isolated operator
root. It published commit `6aab8585-8b69-5ceb-a1f8-a41c27d399f5`, then
started cluster `stage2` through the ordinary operator. The canonical
fixture in `seon.test-test/resolution-follows-admitted-source-and-acquisition`
also constructed and executed: 4 passes, 2 errors in the draft's diagnostic
requests, unrelated to schema compilation. Logs:
`tmp/stage2-scratch-init.log`, `tmp/stage2-scratch-start-initialized.log`.
No default operation or cold gate was issued.
