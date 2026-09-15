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

## Confirmed cause (dir-own-fns, 2026-09-10)

The live `[*]` pulls show that the agent-installed function has
`:seon.fn/ns`, `:seon.fn/private? false`, arglists, documentation, spec,
and contract relations. The current `program-documentation` query includes
it. Juniper's retained `clojure.repl/dir` macro nevertheless expands to
the literal `:functions (quote [])`; the newer cluster base macro includes
the function. `program-dir-var` and `program-doc-var` capture the
acquisition-time documentation map in `src/seon/sci/eval.clj:1105–1248`.
The installation seam already writes the required facts.

Exact rows, forms, expansions, and timings are recorded in
[the lane landing note](../../prds/context-generation/research/dir-own-fns-landing-2026-09-10.md).

## Repair (2026-09-14)

The owner expanded the scope to the documentation owner. Commit `5081a11fb`
deletes the acquisition-time capture: `dir` and `doc` query current program
facts through the evaluation's database custody and ordinary read-evidence
path. Shipped and agent-installed functions use the same namespace relation
and query; both expose arglists and contracts.

Commit `b28ccc1f8` repairs the existing retained-context base update for aliases.
SCI's inherited Var update follows the Var's metadata namespace; the core
aliases of the repl macros therefore kept old roots. Base updates now install
aliases at their actual binding paths. No context replacement or cache is added.

Canonical regressions cover contracted function installation after an empty
directory, read-evidence invalidation, local and qualified documentation, and
macro updates in the same retained SCI context. Final fast, isolated, and
platform gates passed respectively 9/111, 9/115, and 84/505 tests/assertions,
with zero failures or errors. The isolated selection includes
seon.repl-grammar-test and seon.help-trial-test.

The JVM-mode live probe on default retained Juniper's context and returned
largest-customer with sym, arglists, first doc line, and input/output contract
in 896 ms. Full source-adoption convergence is recorded separately in the
landing note before closure.

## Acceptance

- `dir` lists the agent's own installed functions immediately after the
  defn's evaluation, with the same fields as shipped functions.
- Regression on the canonical harness: install a contracted defn through
  the agent path; `(dir <ns>)` in the next evaluation lists it.
