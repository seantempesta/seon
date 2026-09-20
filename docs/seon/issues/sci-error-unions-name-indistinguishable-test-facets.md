---
type: issue
status: open
severity: friction
created: 2026-09-21
tags: [issue, error-model, contracts, sci, test]
---

# SCI error unions name indistinguishable test facets

The kind-retirement PRD section 6 requires a lane to stop when two facets in
one declared union share every required member. Both
`seon.sci.kernel/failure-value` and `seon.sci.admit/semantic-value` declare
`:seon.test/unknown-error` and `:seon.test/expired` in their output unions.
The latter is a conjunction that adds only a description to the former:
`resources/seon/schemas/seon.test.edn:247` and `:260`.

The canonical packaged-form inspection at HEAD `86c3a73d49596488ab5623b39dd3b8fb6791d73e`
expanded both required-member sets to exactly
`#{:seon.error/at :seon.error/layer :seon.error/operation :seon.test/unknown}`.
The source union sites are `src/seon/sci/kernel.clj:568-570` and
`src/seon/sci/admit.clj:582-584`. This is an existing alias relationship,
not evidence of a runtime failure or a missing marker replacement.

The reproducible probe and three options are in the
[sci-program landing note](../../prds/steward-platform/research/kind-sweep-sci-program-2026-09-21.md).
No production edit or test run was made for this finding. The four assigned
namespaces load; the probe exits zero and lints without errors or warnings.

The test-schema owner and error-conversion PRD owner must resolve whether
these unions should name only the existing general test-unknown facet,
whether expiry needs distinct substantive evidence, or whether equivalent
aliases are exempt from section 6 at polymorphic pass-through boundaries.
Acceptance is the selected rule applied consistently to the two SCI output
contracts, with unchanged preservation of the original observation and the
remaining sweep's canonical fast and orchestrator cold verification.

## Owner ruling and implementation dependency

The orchestrator chose option 2: expiry carries the declared bound key and
elapsed milliseconds; unknown retains its current members. That choice is
settled, but implementation is not yet landed. The producer conversion exposed
a separate, measured [await distinction defect](test-check-classifies-completed-errors-as-expiry.md):
the current caller classifies completed failures as expiry, and its await
callee declares no timeout facet. This issue stays open until the coherent
producer/schema/consumer slice lands; the former choice is not reopened.
