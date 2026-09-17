---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, schema, instrumentation]
---

# Instant vector admission and instrumentation disagree

The schema hook accepted a new `[:inst {:description "..."}]` declaration,
but the canonical fast loop refused its public function contract with
`:malli.core/infinitely-expanding-schema`, offending `inst?`, before tests.
Exact evidence: `tmp/stage2-claims-first-fast.log`, function
`seon.test.runner/claim-member`.

The Stage 2 declarations now use an `:and` with the existing
`:seon.test.run/at` alias. The next run compiled the contract and reached its
regression. No primitive normalization owner was changed.

`src/seon/schema/form.cljc` declares `:inst` as `inst?`; schema admission and
runtime instrumentation must agree on the accepted form. The earlier
[registration diagnostic issue](archive/malli-registration-errors-hide-the-offending-var.md)
reported the same nested Malli failure. That issue fixed diagnostic identity,
not this prospective-admission/runtime disagreement. A regression should run
the same candidate population through both boundaries.
