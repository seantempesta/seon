---
type: issue
status: open
tags: [issue, operator, instrument]
---

# The operator bypasses instrumentation to call stop!

Found 2026-07-28 reviewing the fresh operator's landed diff (`6e7b03738`).

## The smell

`script/seon/fresh_operator.clj:382-401` (`stop-form`) resolves
`(malli.instrument/-original (var seon.cluster/stop!))` and calls THAT
instead of the var. Digging the uninstrumented original out of malli's
internals is a workaround shape: if the instrumented var worked, the
form would just call `seon.cluster/stop!`. The likely cause is that
`stop!`'s malli input schema refuses the live instance map (which
carries process-local objects — connection, flow graph — that a
declared schema may not admit), and the lane routed around the
validation instead of fixing the schema or the call.

Symptom-side risk: any boot path that did not instrument leaves
`-original` without a stored original (behavior at that point is
malli-internal), and the operator is now coupled to instrumentation
internals for a plain shutdown.

## Acceptance

`stop-form` calls `seon.cluster/stop!` through the var, and the
instrumented call passes — which means either `stop!`'s input schema
honestly admits the live instance shape, or the schema/caller is fixed
at the owner. One regression: a booted-and-instrumented cluster stops
through the plain var call. Remove the `malli.instrument` require from
the form. (Related magic number in the same form: the
`(Thread/sleep 100)` before `System/exit` is a flush backstop — judge
it when fixing; the prepl :ret has already been written by then.)
