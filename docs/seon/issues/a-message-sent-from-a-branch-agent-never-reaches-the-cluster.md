---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, message, branch, dogfood]
---

# A message a branch agent sends to root is written on the branch and never reaches the cluster

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive 10d6fdff5, branch `dogfood-repl-opus-5`, 2026-09-26)

With no merge-proposal function (the known D5), the agent did what the help text teaches and asked
root:

```clojure
(my.message/send {:my.message/to "root"
                  :my.message/content "dogfood-repl-opus-5: please merge my.plan-test/current-refuses-an-unknown-agent-by-name (passes, run 7ef033023a77)."})
;; => #:seon.message{:id "13fb0fdf877b", :content "…", :to [:seon.agent/id "root"], :from [:seon.agent/id "mcp/default"]}
```

On the cluster's own branch, `(seon.db/q '[:find ?e . :where [?e :seon.message/id "13fb0fdf877b"]] @(seon.cluster.boot/connection "default"))`
answered `nil`. The message is a fact on the agent's branch only. Root, which lives on the
cluster branch, never sees it, and the reply claimed delivery.

## Which model is wrong

Messages are treated as ordinary branch data, like program rows, which merge. But the laws say
"messages … are data and never merge as program", and an agent's messages to agents on other
branches are **delivery**, not branch state. A message's recipient decides where it must be
visible. When the recipient does not live on the sender's branch, the send must deliver to the
recipient's branch through the ordinary route (the same route core faults use to reach root), or
refuse by name. Recording without delivery is a defect.

## Expected

A branch agent's `(my.message/send {:my.message/to "root" …})` is visible to root on the cluster
branch and wakes it, or it is refused with a message that says the recipient is not reachable
from this branch.

## Regression

A hosted agent on a branch sends to root. The message exists on the cluster branch with
`:seon.message/from` naming the branch agent.
