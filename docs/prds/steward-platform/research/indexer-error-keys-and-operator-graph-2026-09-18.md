---
type: research
status: landed
created: 2026-09-18
tags: [program-graph, publication, operator]
---

# Indexer error keys and operator program graph

This lane read `AGENTS.md` sections 0–5,
`.agents/skills/data-oriented-clojure/SKILL.md`,
`critical-findings-triage-2026-09-17.md`, and
`boot-and-load-sequence-2026-09-17.md` end to end before changing the two
triage rows. It also read program-facts PRD sections 1o and 1q and used the
additive error manifest already landed at `fba9ed08f`.

## Row 15 — a refused index read refuses publication

The publication path had two absence-as-health failures. `reconcile-tx-in`
read the current entity before exact replacement without preserving a refused
read, and `published-index-rows` traversed query and pull results as ordinary
collections. An error map could therefore become program-row attributes such
as `:seon.error/message` instead of refusing publication.

`src/seon/fn.clj:2727` now returns the exact read refusal from reconciliation.
`src/seon/fn.clj:2798` recursively preserves query, entity, and reference-read
refusals, and retains Datahike cardinality-many values as sets.
`src/seon/fn.clj:2866` refuses before it submits any transaction. The existing
`:seon.error/value` is the honest boundary contract; this repair introduces no
new error facet.

`test/seon/fn_test.clj:1884` poisons the canonical fixture's query and pull
reads at those seams. It positively asserts the exact refusal, zero submitted
transactions, and that neither produced value is a vector of program rows, so
no `:seon.error/*` key can enter produced transaction data. Landed as
`689c5b9d1`; the cardinality-many contract correction landed as `cfd899133`
after concurrent commits made amending the first commit unsafe.

## Row 21 — `seon.operator.state` enters the graph

The read-only live probe at database basis `536871063` found zero
`seon.operator.state` function rows and zero `seon.fresh-operator` function
rows. The development cluster reported no adopted source commit; the probe was
evidence only and this lane did not operate or reset `default`.

`seon.fn/source-roots` remains exactly `["src" "test"]`
(`src/seon/fn.clj:28`). `resources/seon/operator/state.clj` moved to
`src/seon/operator/state.clj`; its namespace name did not change. Literal
source-path consumers in the bounded-boundary census and fresh-operator reset
fixture now name the source-root location. The census also identifies the
Babashka shim's exact `with-operator-lock` forwarding call rather than
mistaking the callee's former resource path for the caller path.

`test/seon/fn_test.clj:2363` proves from the canonical fixture population that
a private `seon.operator.state` function is a `:seon.fn` row rooted at `src`
and that `my.program/callers` finds an in-namespace caller. The former-resource
reload issue is resolved in
`docs/seon/issues/a-first-party-namespace-under-resources-is-never-reloaded-by-development-adoption.md`.

The static index and executable admission are not independent today.
`load-core-namespaces!` queries every core-provenanced namespace and loads it
into the cluster JVM (`src/seon/sci/eval.clj:1234`). Adding `script/` to
`source-roots` would therefore make the Babashka operator executable cluster
input and violate ruling 1n; the apparently small index-only change is not
safe.

### Exactly three options for `script/seon/fresh_operator.clj`

1. **Recommended — move JVM-portable logic into `src/` and retain a thin
   Babashka shim.** Guarantee: portable functions become ordinary contracted,
   armable, queryable program facts while process entry remains outside the
   cluster. Cost: a bounded but substantial extraction with JVM/Babashka
   parity tests. Give up: the current monolithic script.
2. **Index `script/` through a declared non-executable source admission.**
   Guarantee: script functions are queryable without becoming candidates for
   `load-core-namespaces!`. Cost: a new first-class admission distinction
   carried through indexing, loading, adoption, and test selection. Give up:
   the current uniform rule that core program rows are executable inputs.
3. **Leave the script outside and add a declared exclusion fact.** Guarantee:
   graph queries report the coverage boundary positively instead of treating
   absence as health. Cost: schema, population, and query changes while the
   134 private functions remain uncontractable and unarmable. Give up: full
   program-graph coverage of first-party operator logic.

Only the `seon.operator.state` move was implemented in this lane.

## Verification and boundaries

The requested path overlay refused before launching because the published
graph named four dirty callers held by other lanes:
`src/seon/cluster.clj`, `src/seon/test.clj`,
`src/seon/test/runner.clj`, and `test/seon/cluster/source_test.clj`. Per the
assignment, verification continued with the plain fast form.

The final focused run of `seon.fn-test` plus
`seon.bounded-boundary-census-test` ran 68 tests and 455 assertions. Both row
regressions passed; its sole failure was the stale caller-path census rule
found by the move. After correcting that owned rule, the census rerun passed
4 tests and 11 assertions with zero failures and zero errors.

The wider requested run reached two foreign boundaries. `seon.program-test`
observed the held turn-settlement lane's new
`:seon.turn/missing-opening-datom` refusal where the test still expected
`:seon.turn/refused`. `seon.dev.fresh-operator-test` repeatedly exhausted its
4,921 ms subprocess deadline in held `dev_cache.clj` while running
`ensure-dependency-cache!`; the run was stopped after that dependency was
reproduced. This lane did not edit either owner. The orchestrator still owes
the cold path-limited gate and platform gate.

**RESET NEEDED.** Moving the namespace changes published program facts. The
lane did not reset or otherwise operate `default`; the orchestrator should
batch this source-fact change with pending schema work.
