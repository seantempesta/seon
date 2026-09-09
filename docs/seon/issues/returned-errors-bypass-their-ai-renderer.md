---
type: issue
status: open
severity: blocker
tags: [issue, render, sci, wave/context-fixes]
---

# Render returned flat errors through their declared AI projection

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

Exact bytes, script, and verification boundaries are in
[the transaction landing note](../../prds/context-generation/research/transact-feedback-landing-2026-09-09.md).
