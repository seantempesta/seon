---
type: issue
status: open
severity: friction
tags: [issue, schema, contracts]
---

# Gate-selection refusal contracts only distinguish markers

Observed by ops-effects-2 on 2026-09-20 during the kind-retirement sweep.
`src/seon/fn.clj:1487` declares `seon.fn/gate-sets` to return
`:seon.db/invalid-read-error` or `:seon.schema/missing-projection-error`.
The former requires `:seon.db/invalid-read` and `:seon.error/message`;
the latter requires `:seon.schema/missing-projection` and that same message.
Both distinguishing members are declared `[:= true]`.

The owned consumer `seon.issue.detect/public-without-reaching-test` propagates
these failures instead of treating them as a map of selected tests. The
kind-retirement PRD §1.3 requires a substantive member required by the
callee's declared error schema. Neither `:seon.db/read-operation` nor
`:seon.schema/expected-value` is required by this declared output. A base-three
check is not authorized by §1.3 here: gate-sets does not declare
`:seon.error/value`. Recognizing the message is expressly forbidden by §1.4.

Retiring the boolean members therefore leaves the two declared error schemas
with the same
required set, `#{:seon.error/message}`. The lane invokes §6 before introducing
that collision and leaves this one consumer kind branch explicitly pending.
This is a declaration boundary, independent of dirty foreign files or the
test recorder's current admission failure. The assignment holds fn.clj and
the schema owners read-only.

Reproducible declaration probe:
`docs/prds/steward-platform/research/kind-sweep-ops-effects-gate-facet-probe-2026-09-21.clj`.
It reads the loaded Var contract and canonical packaged forms, derives required
members and boolean markers, asserts two observations and equal substantive
sets. It exited zero; this is declaration evidence, not a test execution.

Recommended resolution: the owning lane corrects gate-sets and its helper
contracts to the actual substantive database/schema refusal schemas. Then this
consumer branches on those required observations and the canonical detector
regressions prove propagation. Temporary generic-output permission or an
explicit scope deferral require the orchestrator's ruling.
