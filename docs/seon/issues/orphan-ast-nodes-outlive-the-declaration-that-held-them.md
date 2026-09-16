---
type: issue
status: open
severity: cleanup
tags: [issue, program-graph, database, class/p3]
---

# Orphan AST nodes outlive the declaration that held them

On `default` 2026-09-16 (basis 536871281), 9,023 entities hold only
`:seon.fn.ast/*` attributes and **542** of them are the value of no attribute
declared `:seon.db/component` — no `:seon.fn/ast`, `:seon.fn.ast/child`,
`/children`, `/key`, `/keys`, `/input`, `/output`, `/value`, `/values`,
`/guard`, `/properties` or `/registry` datom names them. Two samples:
`{:seon.fn.ast/children 53451 :seon.fn.ast/type :cat}` and
`{:seon.fn.ast/ref 2632 :seon.fn.ast/type :malli.core/schema :seon.fn.ast/value 49045}`.

Datahike retracts components with their holder, so an unreachable node means
some path replaced a contract's AST without retracting the previous root, or
attached a node it never linked. The nodes are invisible to every query that
starts from a function and are counted by every query that starts from the
attribute — including the component exclusion in
`seon.issue.detect/entity-map-without-pair`, which is why that detector
witnesses reachability instead of requiring it of every instance.

Acceptance: the contract-AST writer leaves no node unreachable from a
`:seon.fn/ast` root after a republication of a changed contract, proven by a
regression that republishes one function's contract and asserts the orphan
count is zero; the 542 existing orphans go with the next reset (database data
is disposable).
