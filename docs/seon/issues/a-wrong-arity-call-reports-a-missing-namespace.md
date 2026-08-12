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

## Broader than one lane, 2026-08-08 (whole-system-arc observer lane)

Reproduced on the shared `default` cluster (pid 31475), not an isolated lane
root, and in **all four agents** — `root`, `inventory`, `health`, `timeline`.

This raises the severity of the consequence, if not the severity of the bug:
the failing call is part of the shipped bootstrap teaching plan
(`resources/seon/bootstrap.edn`), which deliberately calls `(largest)` with no
arguments to teach an agent what an honest arity error looks like. So this is
not an occasional agent slip — **every fresh agent's first history contains
this false message**, and the lesson it was meant to receive is inverted into a
claim it can disprove from its own transcript.

Run `bootstrap:inventory`, forms joined to receipts:

| Ordinal | Source | Result / error |
|---:|---|---|
| 1 | `(in-ns 'arc.inventory)` | ok — `sci.lang.Namespace` |
| 8 | `(defn largest …)` (corrected schema) | ok — var `arc.inventory/largest` |
| 9 | `(largest [{:label "a" :amount 3} …])` | ok — a map |
| **10** | **`(largest)`** | **`No such namespace: arc.inventory`** |
| 11 | `(largest [])` | ok — `{}` |

Ordinal 11 is a useful addition to the original evidence: a *successful* bare
call to `largest` lands immediately **after** the failing one, so the namespace
is provably intact on both sides of the false message, not merely before it.

Confirmed not to be a placeholder-substitution artifact: these three agents
were created at runtime and received correctly substituted `(in-ns 'arc.…)`
forms which succeeded, unlike `bootstrap:root` (see
[Substitute the bootstrap plan's namespace placeholder before it is evaluated](bootstrap-plan-forms-ship-unsubstituted-namespace-placeholders.md)).

## Recurrence — live default cluster, 2026-08-10

Still live, and now visibly INCONSISTENT across agents in one cluster. Three
core-namespace agents were created on 2026-08-10; querying `:seon.error/run`
on the default cluster (pid 31570) gives:

```text
["bootstrap:root"        :seon.sci.eval/evaluation-failed  "No such namespace: my.agents.root"]
["bootstrap:seon.db"     :seon.instrument/contract-violated  "Wrong number of args (0) passed to: seon.db/largest"]
["bootstrap:seon.fn"     :seon.instrument/contract-violated  "Wrong number of args (0) passed to: seon.fn/largest"]
["bootstrap:seon.render" :seon.instrument/contract-violated  "Wrong number of args (0) passed to: seon.render/largest"]
```

Same shipped form, `(largest)`; three agents get the correct arity message
and one gets the false namespace claim. Root's own captured context still
carries the false line (`Execution error (IllegalArgumentException) at
seon.instrument/violation (instrument.clj:224). No such namespace:
my.agents.root`), immediately followed by a successful `(largest [])`.

Related: these deliberate teaching failures are also committed as core
faults that interrupt the bootstrap run —
[bootstrap-teaching-failures-strand-every-new-agent](bootstrap-teaching-failures-strand-every-new-agent.md).

## Root cause located, 2026-08-12 (HALF re-drive lane)

`seon.instrument/violation` builds the correct message and then throws while
decorating it. `src/seon/instrument.clj:222-224`:

```clojure
(if (= :malli.core/invalid-arity kind)
  (let [function-symbol (:fn-name data)
        arglists (some-> function-symbol find-var meta :arglists)]
```

`clojure.core/find-var` does not return nil for an absent namespace — it
THROWS `IllegalArgumentException: No such namespace: <ns>`. `some->` guards
against a nil input, not against a throwing call, so the throw escapes
`violation` and replaces the arity message the very next form was about to
build.

This explains the inconsistency recorded above exactly. `seon.db`,
`seon.fn`, and `seon.render` are real JVM namespaces, so `find-var` resolves
and those three agents get `Wrong number of args (0) passed to: …`.
`my.agents.root`, `my.agents.w1-history-proof-5`, and `arc.inventory` exist
only as SCI namespaces, so `find-var` throws and those agents get the false
namespace claim. Nothing is substituting a message: the reporter dies before
it can return one.

The class is a diagnostic reaching into JVM Var space for a function that
lives in SCI. The arglists are already available without `find-var` — the
declared arities are `:seon.fn.arity/*` facts in the program graph, and
malli's own report data names the function. A fix that keeps `find-var`
under a `try` would suppress the class rather than remove it.

Reproduced again on 2026-08-12 on two fresh isolated roots
(`tmp/ablate-half3`, `tmp/ablate-half4`), verbatim receipt error for both
`bootstrap:root` and `bootstrap:w1-history-proof-5`:

```text
No such namespace: my.agents.w1-history-proof-5
```

with `:seon.error/data {:seon.sci.eval/throwable "java.lang.IllegalArgumentException"}`,
immediately followed by a clean `(largest [])` returning `{}`.
