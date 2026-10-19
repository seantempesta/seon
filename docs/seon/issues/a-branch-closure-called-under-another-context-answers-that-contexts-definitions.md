---
type: issue
status: open
severity: latent
tags: [issue, sci, runtime]
---

# A branch's closure called under another context answers that context's definitions

Found by lane review3-opus, 2026-10-20, HEAD `06614f6a8`; evidence in
[runtime-review3-opus](../../research/agent-platform/2026-10-20/runtime-review3-opus.md) (N3).

A branch replaces `seon.print/literal` with `[value] "BRANCH"`, so `(seon.print/form-text '(a 1))` answers
`"(BRANCH BRANCH)"` there. A lazy sequence `(map (fn [x] (seon.print/form-text x)) ['(a 1)])` and a closure
`(fn [] (seon.print/form-text '(a 1)))`, both made on the branch's context `b`:

| Realized or called | Answer |
|---|---|
| under `b` | `("(BRANCH BRANCH)")` |
| on a raw `Thread` (no context bound) | `("(BRANCH BRANCH)")` |
| under main's context bound on the thread (`sci.ctx-store/with-ctx main`) | **`("(a 1)")`**, closure **`"(a 1)"`** |

Root cause: the SCI fork's `sci.impl.fns/in-defining-ctx` re-enters a function's defining context only on a thread
with no context; "a context bound on the calling thread still wins", and an analysed body's Var reads take that
context's bindings. A value outliving its evaluation (a live result, a callback registered with a host listener, a
render of another agent's value in the viewer's context) therefore answers whichever runtime realizes it.

Not reproduced on an entrant path: no installed path is known to realize one agent's live value under another
context. Scope check to do: the HTML render of a live result (the viewer's context), `d/listen` and Flow callbacks
registered by an agent's evaluation.
