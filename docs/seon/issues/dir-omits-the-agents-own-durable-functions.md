---
type: issue
status: open
severity: blocker
tags: [dir, doc, program-graph, sci, live-test]
created: 2026-09-10
---

# `(dir my.agents.juniper)` returns `:functions []` after the agent's own defn

## Observed (live run 2, 2026-09-10; landing `live-run-2-landing-2026-09-10.md`)

Juniper evaluated a `defn largest-customer` with a `:malli/schema` in its
namespace; the evaluation printed `#'my.agents.juniper/largest-customer`
and the auto-check ran its contract. Three later evaluations of
`(dir my.agents.juniper)` returned:

```clojure
{:functions [], :schemas #:example{:amount :int, :customer :string, :order [:string #:seon.db{:identity true}], :order-row [:map …]}}
```

while `(seon.db/q '[:find ?s ?spec :where [?f :seon.fn/sym ?s] [?f :seon.fn/spec ?spec] …])`
found `["my.agents.juniper/largest-customer" "[:=> [:cat [:vector :example/order-row]] [:map [:customer :example/customer] [:total :int]]]"]`.
The model concluded "the defn did NOT persist" and spent about five turns
re-verifying.

## Why

`dir` derives its `:functions` from public program rows (turn PRD §13:
"REPL documentation as DATA from public program rows"); the row exists
(`:seon.fn/sym`, `:seon.fn/spec`), so either `dir` selects rows by a
relation the agent-installed row lacks (e.g. `:seon.fn/ns` ref, a
publication marker) or reads a stale projection. Both are a missing fact
or a wrong query at one seam — find which with a probe before fixing.

## Wanted

- `dir` lists the agent's own installed functions immediately after the
  defn's evaluation, with the same fields as shipped functions.
- Regression on the canonical harness: install a contracted defn through
  the agent path; `(dir <ns>)` in the next evaluation lists it.
