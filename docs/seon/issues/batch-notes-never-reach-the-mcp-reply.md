---
type: issue
status: open
severity: defect
tags: [issue, reply, mcp, batch, findings]
---

# A form's `:seon.batch/notes` never reach the MCP reply an outside agent reads

`seon.turn.batch/derive-form` collects each write's notes (`:seon.batch/arm-error`, and since lane runtime-fix
`:seon.batch/host-bound-reached`: the host-bound functions that run main's definition of the written name), and
`seon.turn.batch` puts them in the batch reply's `:seon.batch/evaluations`. The MCP `eval_clj` reply is built by
`seon.repl/response` from the evaluation emission, which carries no notes: neither `seon.repl` nor `seon.mcp` reads
`:seon.batch/notes`. So an outside agent never sees that its contract failed to arm, nor that a host-bound function
will not see its change.

Observed 2026-10-19 (lane runtime-fix): the notes are produced (`seon.sci.arm/arm!` answers the write with them);
`grep notes src/seon/repl.clj src/seon/mcp.clj` finds nothing.

Owner: the reply's producer (`seon.repl/response-entries`): the notes are data of the evaluation and belong in the
one reply map beside `:seon.repl/findings`, so the MCP reply and the next context read one derivation.
