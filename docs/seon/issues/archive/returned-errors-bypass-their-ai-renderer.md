---
type: issue
status: resolved
severity: blocker
tags: [issue, render, sci, wave/context-fixes]
---

# Render returned flat errors through their declared AI projection

Resolved 2026-09-09 by the context-cookbook lane. Returned flat errors now enter
the schema-selected AI pair at evaluation time and emit saved shown text under
:seon.repl/error. The canonical bad-transaction regression retains the complete
diagnostic, leaves the target unchanged, and observes 131 bytes beginning with
Expected:. Isolated error/REPL/value gate: 38 tests / 174 assertions; platform:
83 tests / 490 assertions, all green. A read-only default JVM observation produced
the same shown text. Evidence and exact bytes:
[context cookbook](../../../prds/context-generation/research/context-cookbook-2026-09-09.md).

The transact-feedback scratch proof on 2026-09-09 returned an evidence-complete
:seon.db/invalid-write. Its declared seon.db/render-rejection-ai prints the
authored schema first, then the offending value and path. The actual SCI
evaluation instead produced 754 bytes of structural data under
#:seon.repl{:value …}, with the schema last. The database amount remained 60.

The source boundary is seon.sci.eval/shown-result →
seon.render.value/render-ai: value-node* invokes schema-selected renderers
only for HTML. seon.sci.eval/success-evaluation does not classify a returned
flat error as evaluation error, and seon.repl/response-entries consequently
uses :value. Throwing the refusal in seon.db would violate its flat-value
contract and is not a fix.

Those implementation paths belonged to the concurrent context-blocks lane
when this slice was assigned. Its e915d2de0 checkpoint preserves the boundary.
This slice does not change that lane's rendering or evaluation implementation.

Acceptance: a bad raw transact through the real evaluation point shows the
attribute's authored form and offending value first under :seon.repl/error,
without a stack, while retaining the complete flat diagnostic as data and
preserving historical shown text. Use the existing schema render pair and
single value-renderer profile boundary.

## Recurrent selection boundary — 2026-09-16

`563034709` reintroduced the HTML-only guard in `value-node*`, removing AI
pair selection for every returned map. The default census again found zero
renderer symbols and refs across 78 evaluations. Fixed by `cecfaf428` at that
same owner: declared AI pairs are selected again; explicit structural requests
retain the existing opt-out. The new canonical SCI-to-settlement regression
passes 11 assertions and verifies both renderer identities, plus absence of
both for an ordinary map. Explicit structural printing passes 68 assertions;
declared pair rendering passes 15. Default virtual turn `d0ec099ed5ec` closed
with a directory evaluation carrying both identities and a plain evaluation
carrying neither. See the
[renderer follow-up](../../../prds/steward-platform/research/attempt-and-eval-facts-2026-09-16.md)
for exact evaluations and the separate stale armed-proc boundary.

Exact bytes, script, and verification boundaries are in
[the transaction landing note](../../../prds/context-generation/research/transact-feedback-landing-2026-09-09.md).
