---
type: issue
status: resolved
severity: friction
tags: [issue, database, datahike]
---

# Three smaller defects in the vendored Datahike, found beside the card-many scan bug

All three surfaced while tracking
`archive/booted-block-derivation-returns-one-of-four.md`. None of them produces
a wrong answer today; each is a live trap. Owner is our fork,
`reference-code/datahike` — the roster-race precedent (`357ffc87`) says we fix
these ourselves.

## 1. Plan selection depends on variable-symbol identity

For two clause sets identical except for variable names, the planner picks a
different pattern for the entity-group's scan position. Against one booted
cluster and one database value:

```text
[?agent :…/id "root"] [?agent :…/blocks ?block]  → scan = the :blocks pattern
[?a     :…/id "root"] [?a     :…/blocks ?b]      → scan = the :id pattern
```

Same costs, same schema, same data; the plan cache was cleared explicitly and
the rebuilt plans still differed. Something in plan construction (`logical` →
`lower` → `plan/dp-order-fuse-ops`) is ordered by a hash-ordered collection
keyed by the variable symbols, so a cost tie is broken by symbol hash. A plan
that changes with the reader's choice of variable name is unreviewable and
makes every planner bug look intermittent — this is what made the card-many
scan bug read as "probabilistic across boots" for a whole session.

Acceptance: for a fixed database value and schema, `create-plan-via-ir` returns
the same plan structure under a consistent renaming of the query's variables.
A property test over alpha-renamed queries is the natural shape.

## 2. `*query-result-cache?*` is a dial that does nothing

`reference-code/datahike/src/datahike/query.cljc:72` declares and documents
`*query-result-cache?*` ("Bind to false for benchmarking raw query
execution"). Nothing ever reads it — the only two occurrences in the file are
the `def` and its docstring. Binding it false silently changes nothing, so any
benchmark that used it measured the cache. `clear-query-cache!` is the working
operation.

Acceptance: either the binding suppresses cache reads and writes, or the var is
deleted and the docstring points at `clear-query-cache!`. Not both, and not a
dial that lies.

## 3. `execute-card-many-merge`'s CLJ branch runs both merge paths

`reference-code/datahike/src/datahike/query/execute.cljc`, in
`execute-card-many-merge`: the CLJS branch is `(if card-many? <slice-path>
<lookupGE-path>)`, while the CLJ branch is `(if card-many? <slice-path>)`
followed by the `lookupGE` path as a second body form of the enclosing `let`.
So on the JVM a card-many merge does its correct cross-product recursion AND
then the card-one cursor probe, emitting one extra tuple per scan datom and
advancing a shared forward cursor it had no business touching. The results
happen to be deduplicated into the same set, which is why nothing is red.

Acceptance: the two branches have the same shape, and a test asserts the emit
count (not just the result set) for a card-many merge.

## Resolution

Fixed in the maintained Datahike fork at `19f5cdd9` and pinned from Seon's
discovered test suite by `test/seon/datahike_fork_test.clj`.

### 1. Stable equal-cost selection

The live cluster's original `:id`/`:blocks` pair no longer varied on the
current database value, so it was not an honest reproducer. A smaller
`create-plan-via-ir` probe did reproduce the same class of failure: three
independent, equal-cost function clauses planned in source order `3, 2, 1`,
while an alpha-renamed copy planned in normalized order `2, 1, 3`.

The leak was `datahike.query.plan/order-plan-ops`. It converted the input
operations to a set before greedy selection. The set's iteration order included
the hashes of variable symbols, and stable cost sorting could only preserve
that already-name-dependent order. The group/non-group interleave had the same
defect, plus `min-key` selected the later argument on equal keys.

The honest tie-break is source order. Cost remains the primary decision; when
the cost model says two operations are indistinguishable, retaining the
logical input order is the only existing semantic order and makes the plan
reviewable. The planner now keeps a vector, removes exactly the chosen
operation, and uses stable cost sorting in both paths. The Seon regression runs
100 fixed-seed alpha-renamings against one immutable database value and compares
the complete normalized plans.

### 2. Cache dial acceptance

The issue's diagnosis was stale before this repair began. Current
`datahike.query/query` already reads `*query-result-cache?*`; that behavior
landed in fork commit `caf52685`. A new fork regression nevertheless pins the
acceptance boundary: a false binding returns `:uncacheable`, performs real
query work instead of reading an existing hit, writes no new cache bucket, and
does not replace an existing bucket.

### 3. Single cardinality-many merge path

The JVM executor now makes the cardinality-one cursor probe the `else` branch
of the cardinality-many test, matching the ClojureScript shape. The regression
drives `execute-group-direct` directly and inspects its raw `ArrayList`, where
the failure emitted `[2 3 4 5 2]`; the fixed path emits `[2 3 4 5]`. This
asserts emission count before public set semantics can hide the duplicate.

### Evidence

- Live/raw REPL before the edit: normalized alpha-renamed plans differed;
  direct cardinality-many execution emitted five rows for a four-row
  cross-product.
- Same REPL after namespace reload: normalized plans were identical and raw
  emissions were `[2 3 4 5]`.
- `bin/test seon.datahike-fork-test`: 1 test, 1 assertion, zero failures.
- Datahike query-planner focus: 96 tests, 396 assertions, zero failures.
- Datahike cache acceptance focus: 3 tests, 21 assertions, zero failures.
- The complete root gate reached all 557 tests and 2,380 assertions, including
  this regression, but exited with eight failures in the concurrently edited,
  protected cluster run/agent lane. The first failure was
  `seon.cluster.agent-test/unheld-resume-regression` at
  `test/seon/cluster/agent_test.clj:974` (expected one ordinal-1 eval, found
  zero); further failures were in `armed_test.clj`, `boot_test.clj`, and
  `turn_test.clj`. No failure was in this issue's owners.

## Skill evaluation

This was deliberately evaluated as a hard REPL/fork-maintenance task. “No
wrong claim found” below is literal, not praise: the missing scope was often
the more important defect.

### `datahike`

- **Right:** the immutable-database-value guidance
  (`.agents/skills/datahike/SKILL.md:21-40`) kept the alpha-renaming property
  pure and repeatable. Its explicit instruction to read our fork rather than
  guess dependency semantics (`:437-456`) led directly to the bad set in
  `order-plan-ops`.
- **Wrong or stale:** no factual claim used in this task proved wrong.
- **Missing:** it contains almost nothing about maintaining Datahike itself:
  no planner entry point, no `create-plan-via-ir`, no cache evidence surface,
  no private-var REPL probing, and no fork test command. For this task, most of
  its EAV/application material was irrelevant; all decisive mechanics had to
  be derived from source and the fork's aliases.
- **Trigger:** it triggered at the correct initial moment both explicitly and
  from the Datahike subject.

### `data-oriented-clojure`

- **Right:** “read vendored source and test in the REPL first”
  (`.agents/skills/data-oriented-clojure/SKILL.md:24-41`) prevented coding
  against the issue's stale cache diagnosis. Its discovered-`deftest` rule
  (`:260-272`) kept the acceptance property in the real gate.
- **Wrong or stale:** no checked factual claim proved wrong.
- **Missing:** deterministic transformations are a core data-oriented concern,
  but the skill never warns that unordered collections must not control
  equal-cost or otherwise tied decisions. That omission is exactly this bug:
  a set was acceptable as membership data but dishonest as planner order.
- **Trigger:** it triggered because the user required it. Its normal trigger
  says Seon Clojure; this edit was primarily dependency Clojure, so the
  description alone would not reliably load it.

### `seon-flow-architecture`

- **Right:** the scratch-cluster isolation rule
  (`.agents/skills/seon-flow-architecture/SKILL.md:252-260`) prevented any
  mutation or restart of the owner's default cluster. The source-first rule
  (`:18-33`) also correctly identifies our Datahike fork as the query-planning
  authority.
- **Wrong or stale:** no factual claim used here proved wrong.
- **Missing:** the skill has no degraded-start runbook for the case where a
  scratch cluster fails partway through the boot sequence during shared-tree
  churn. I had to inspect advertisements, prove that no scratch cluster
  survived, and fall back to an isolated in-memory JVM manually. That is the
  only flow-related hard part of this task, and the skill did not help with it.
- **Trigger:** it did not naturally fit a pure planner repair; the user
  correctly forced it for the live-cluster boundary.

### `clojure-testing`

- **Right:** the discovered-suite and test-count guidance
  (`.agents/skills/clojure-testing/SKILL.md:22-54`) caught the difference
  between a hand-run probe and coverage. The fixed-seed `quick-check` guidance
  (`:180-191`) produced a replayable alpha-renaming property.
- **Wrong or stale:** the database-property language is overbroad.
  Lines 163-166 say database properties use a fresh connection, and lines
  201-207 command one database per trial. That is necessary for mutating
  state-transition properties, not for a pure property over one immutable
  database value. Applied literally here it would add 100 database setups
  without increasing isolation or honesty.
- **Missing:** there is no guidance for tests split across the root project and
  an owned vendored submodule, nor how to derive the submodule's Kaocha focus
  command. Both suites are required to cover this ownership boundary.
- **Trigger:** it loaded at the right moment when the REPL falsifier became a
  regression.

### `repl`

- **Right:** “one form, then read the result”
  (`.agents/skills/repl/SKILL.md:98-102`) was useful discipline while comparing
  planner and executor internals.
- **Wrong or stale:** its facts describe Seon's agent-form parser, not a JVM
  REPL. In particular, bare values being dropped and parinfer repairing forms
  (`:8-23`, `:61-73`) did not match this exercise: raw `clojure -M:dev`
  evaluated ordinary values and used the normal Clojure reader without that
  repair layer. The claims may be true in their stated surface, but loading the
  skill for a generic “REPL problem” creates the wrong expectation.
- **Missing:** it needs a sharp first paragraph distinguishing the Seon
  agent-form parser from `io-prepl` and raw JVM REPLs. It also lacks the two
  techniques this task needed: invoking an internal var with `#'ns/var` and
  reloading edited namespaces before rerunning the same probe.
- **Trigger:** it did not trigger at the right moment from its own description;
  there was no parse failure. The explicit evaluation requirement was the only
  reason to load it.
