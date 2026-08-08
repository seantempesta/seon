---
type: issue
status: open
severity: friction
tags: [issue, runtime, agent, repl, live-drive]
---

# Name the arity when an agent calls its own function with the wrong one

## Problem

An agent calls a function it defined moments earlier with the wrong number
of arguments, and the receipt says the NAMESPACE does not exist. The
namespace does exist — the agent is evaluating inside it, and the previous
form settled a Var in it. The reader is sent to look for a missing
namespace that is not missing, and told nothing about the arity that
actually failed.

## Evidence

Cluster `comp`, isolated root `tmp/lanes/component-ref`, 2026-08-08, the
run that first proved an agent can define a contracted function
([Let an agent define a contracted function](archive/an-agent-cannot-define-a-contracted-function.md)).

| Ordinal | Form | Receipt |
|---:|---|---|
| 8 | `(defn largest …)` with a complete `:malli/schema` | the Var `my.agents.root/largest` |
| 9 | `(largest [{:label "a" :amount 3} …])` | `{:label "b", :amount 9}` |
| 10 | `(largest)` | `#:seon.error{:kind :seon.sci.eval/evaluation-failed, :message "No such namespace: my.agents.root", :data {:seon.sci.eval/throwable "java.lang.IllegalArgumentException"}}` |

Ordinals 8 and 9 prove the namespace and the Var are both present, so the
message at ordinal 10 is false on its face.

`java.lang.IllegalArgumentException` is what sci throws for a wrong-arity
invocation. Something between that throw and the flat error value is
substituting a namespace-resolution message for it.

## Owner

`seon.sci.eval` error projection, plus whatever maps sci's
`IllegalArgumentException` to a flat `:seon.error` value.

## Acceptance

- A wrong-arity call to an agent's own function returns a flat error that
  names the function, the arity supplied, and the arities it declares.
- The message never claims a namespace is missing when the calling form is
  being evaluated inside it.
- One regression drives a wrong-arity call to a just-defined function and
  asserts the returned error names the arity.
