---
type: issue
status: open
severity: blocker
tags: [issue, agent, cljs, pod]
---

# Compile the agent toolkit into every execution child

## Problem

The execution artifact did not compile the public namespaces promised by an
agent's starting namespace. The prompt advertised plan and lifecycle functions,
but the child contained only `my.plan.internal`; fully qualified calls to
`my.plan/active!`, `my.plan/done!`, and `seon.agent.lifecycle/complete` were
undefined.

## Evidence

Fresh agents repeatedly computed and messaged the right answer, then timed out
because every plan and completion call failed. Inspecting the child global
showed only `my.plan.internal`. `seon.execution.runtime`, the execution build's
entry point, required selected prompt/render dependencies but omitted most of
the default `:seon.eval/home-requires` namespaces. The child program loader
correctly loads agent-authored `:seon.db.process/repl` source only; boot-compiled
core functions must therefore be reachable from the execution entry point.

## Owner

The one `seon.execution.runtime` artifact entry point and the configured agent
home namespace functions it promises.

## Acceptance

- Every default home namespace function is compiled into the execution
  artifact.
- Each retained child installs the selected agent's configured home requires
  into its own compiler state before evaluating a batch.
- A clean child resolves plan, message, lifecycle, database, and toolkit
  functions without replaying boot source.
- A fresh real agent sends its answer, closes its plan step, and completes
  without a retry or timeout.
