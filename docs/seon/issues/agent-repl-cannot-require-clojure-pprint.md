---
type: issue
status: open
severity: friction
tags: [issue, sci, agent, repl, live-drive]
---

# Make `clojure.pprint` available in the agent's REPL

## Problem

The agent's SCI context has `clojure.string`, `clojure.set`, `clojure.walk`,
and `clojure.edn`, but not `clojure.pprint`. An agent that reaches for
`pprint` — the ordinary Clojure move for inspecting a value it cannot read —
gets `Could not find namespace clojure.pprint.` and then a cascading
`Unable to resolve symbol: pprint` on the next form.

The context tells the agent it is in a real Clojure REPL. `pprint` is part of
what a Clojure programmer expects that to mean, and it is the specific tool
for the situation the agent was actually in.

## Evidence

Cluster `default` (pid 79576), observer lane, 2026-08-08. Probed directly
against the live base context, `(:seon.sci.eval/ctx instance)`:

```clojure
{:pprint-require {:err "Could not find namespace clojure.pprint."}
 :pprint-ns      {:err "Could not find namespace clojure.pprint."}
 :string-require {:ok "\"OK\""}
 :set-require    {:ok "nil"}
 :walk-require   {:ok "nil"}
 :edn-require    {:ok "nil"}}
```

This was not a hypothetical. Run `a7e24a23-14b7-41ab-8a96-5f3c06a9a8ee`,
form ordinals 4 and 5, unprompted:

```clojure
(require '[clojure.pprint :refer [pprint]])
;; :seon.cluster.eval/error — "Could not find namespace clojure.pprint."

(pprint (seon.db/read-evidence db))
;; :seon.cluster.eval/error — "Unable to resolve symbol: pprint"
```

Two of the six receipts that run produced were this one gap.

## Owner

The namespace set the cluster's base SCI context is built with, in
`src/seon/sci/eval.clj` / `src/seon/sci/kernel.clj`.

## Acceptance

- `(require '[clojure.pprint :refer [pprint]])` succeeds in an agent turn and
  `pprint` prints.
- Whatever set of `clojure.*` namespaces the context provides is a queryable
  fact, not something an agent discovers by hitting a refusal.
- One class regression asserts the declared set is what the live context
  actually resolves, so a namespace cannot silently drop out.

## Note for whoever fixes this

Consider whether the refusal itself should be better. `Could not find
namespace clojure.pprint.` does not say what IS available, so the agent's only
recovery is to guess again. A refusal that names the resolvable set turns a
dead end into one more query.

## Recurrence — live default cluster, 2026-08-10

Root lost a real turn to this. From its captured `:seon.render/ai` context
(pid 31570), root was mid-investigation of a maintenance error and wrote a
multi-form `do` whose first line was the require:

```text
my.agents.root=> (do
  (require '[clojure.pprint :refer [pprint]]
           '[clojure.string :as str])
  …)
Execution error at sci.impl.load/handle-require-libspec (load.cljc:248).
Could not find namespace clojure.pprint.
```

The whole form was discarded, and the next turn re-wrote the same
investigation without `pprint`. The durable fact is recorded on run
`f500af63-bf5d-4c4b-b196-81d85e3c5b32` as
`:seon.sci.eval/evaluation-failed`. This is the "refusal that names nothing
available" cost predicted in the note above, paid live.
