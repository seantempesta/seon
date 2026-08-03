---
type: issue
status: open
severity: friction
tags: [issue, mcp, repl, context]
---

# Stop returning the generated MCP wrapper as the evaluated form

## Problem

Every successful `eval_clj` response includes the complete server-generated
remote wrapper in the terminal event's `form`. A trivial door `(+ 20 22)`
therefore returns the user's result beside a long implementation form containing
`project-next-prepl-value!`, `running-instances`, cluster lookup, the degraded
cluster error branch, and the complete `seon.sci.eval/evaluate` request.
JVM mode similarly exposes the generated `in-ns`/`refer`/`eval` wrapper.

This is not the form the agent asked to evaluate. It dominates routine probe
responses, leaks bridge implementation into the normal face, and makes visual
comparison of successive results harder. The user source is already present
at the bridge before the wrapper is constructed.

## Owner

The MCP response projection in `script/seon/dev/mcp.clj`, after io-prepl has
returned the event and before it enters the caller's context.

## Acceptance

- The normal event face reports the exact user-supplied source, or omits a
  redundant form when the response already identifies the request.
- The generated remote form is available only through an explicit diagnostic
  projection when debugging the bridge itself.
- JVM and door modes preserve their raw io-prepl semantics, including named
  session `*1`, while routine results no longer carry the wrapper bulk.
- Focused bridge tests assert exact user-source fidelity in both modes.
