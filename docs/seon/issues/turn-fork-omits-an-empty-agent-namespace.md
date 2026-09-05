---
type: issue
status: open
severity: blocker
tags: [issue, sci, agent, runtime, class/p3, wave/per-run-fork-context]
---

# Register an empty agent namespace in its turn fork

## Problem

A freshly assigned agent namespace exists as a database fact but is absent
from both the acquired program context and the agent's turn fork until the
agent has a stored definition. Evaluating a form inside that namespace does
not register it. A generated opening that asks for `(dir my.agents.root)`
therefore fails instead of returning an empty directory.

This is distinct from the resolved arity-reporting defect in
[`archive/a-wrong-arity-call-reports-a-missing-namespace.md`](archive/a-wrong-arity-call-reports-a-missing-namespace.md):
the current failure is SCI's own `clojure.repl/dir` lookup of a namespace that
the turn fork never installed.

## Evidence

On 2026-09-05 the freshly restarted shared `default` cluster, process 1170,
recorded `bootstrap:root` ordinal 8 with source `(dir my.agents.root)`, form
namespace `my.agents.root`, a terminal evaluation result, and error
`No namespace: my.agents.root found`. This proves the earlier startup failure
that left no evaluation is no longer the observed blocker; this different
bootstrap evaluation error remains.

A read-only JVM probe against the live instance and a disposable
`seon.sci.eval/fork-for-turn` result measured:

```clojure
{:base-has-root? false
 :fork-has-root? false
 :root-def-count 0
 :base-has-message? true
 :fork-has-message? true}
```

The base's omission follows acquisition: `seon.sci.eval/acquire!` installs
only selected program and agent-authored namespaces
(`src/seon/sci/eval.clj:1515-1539`). The turn fork then creates namespaces
only while walking stored `:seon.def` rows
(`src/seon/sci/eval.clj:1736-1757`), so an agent with no definitions adds
nothing. Evaluation constructs a namespace object with `sci/create-ns`
(`src/seon/sci/eval.clj:2209-2214`) and binds `sci/ns` to it, but does not add
it to the context.

That distinction is explicit in SCI: `create-ns` only constructs a namespace
object (`reference-code/sci/src/sci/core.cljc:374-378`), while
`add-namespace!` mutates the context's namespace map
(`reference-code/sci/src/sci/core.cljc:679-684`). `clojure.repl/dir` resolves
through that map (`reference-code/sci/src/sci/impl/namespaces.cljc:2267-2278`)
and `sci-the-ns*` throws the observed message when the entry is absent
(`reference-code/sci/src/sci/impl/namespaces.cljc:458-464`).

The generated opening deliberately turns namespace subjects into `dir` forms
at `src/seon/bootstrap.clj:196-200`, so an empty assigned namespace is a normal
input to this path.

## Owner

`seon.sci.eval/fork-for-turn`, the owner that combines the program context
with one agent's durable state. It already registers namespaces required by
restored definitions; it must also register the agent's assigned namespace,
derived through `seon.sci.eval/agent-namespace`, when that namespace is empty.

## Acceptance

- A turn fork for an agent with zero stored definitions contains the
  namespace assigned by `:seon.cluster.agent/namespace`.
- Evaluating `(dir <assigned-namespace>)` in that fork returns the empty
  directory without an evaluation error.
- The acquired program context remains program-only; the regression inspects
  the actual turn fork rather than treating the base context as the agent
  context.
- A fresh root bootstrap records a successful terminal evaluation for its
  own namespace directory form.
