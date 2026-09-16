# The agent's own test run needs the agent's own environment (2026-09-17)

Lane: `run-owned-env`. Subject: the cold-gate red
`my.test-test/an-agents-own-test-reaches-its-cluster-through-the-elided-arity`
(batch 87, `tmp/orchestrator/gate-results/batch-87/named.log:295-330`).

## Cause

Two fixture omissions, both of the same class: facts a production caller
hands over were expected to be ambient.

1. **No agent in the environment.** `seon.test-support/fork-cluster-ctx`
   builds a production-shaped cluster environment (cluster name, connection,
   basis, projection) but no `:seon.agent/id`; production puts the agent id
   there by scoping it onto the ctx's environment for each evaluation
   (`src/seon/sci/eval.clj:2273`, `seon.env/scope`). Call preparation reads
   `:seon.agent/id` through `seon.env/supplied-agent-id`
   (`src/seon/env.clj:303`), so `(my.test/run)` answered
   `:seon.call-preparation/unavailable` with cause `:seon.env/agent-id-absent`
   for `seon.test/owned-symbols` — the exact cold message, reproduced in
   process before any edit.
2. **No roster row.** With the agent id supplied, `owned-symbols` answered
   `[]`: an agent's `deftest` becomes a `:seon.test` row only when the turn
   writer commits the evaluation's `:seon.program/row`
   (`src/seon/turn.clj:1775`), and `seon.test-support/agent-value` crosses
   `seon.sci.eval/evaluate` without a turn. The run then had nothing to run
   and three assertions read the absence as a result.

No production code was wrong; `seon.test/run-owned` already takes the
connection as a value and needed no new arity.

## Diff

`test/my/test_test.clj` only (+33 lines):

- scope the agent onto the ctx's carried environment —
  `(env/scope (env/of ctx) {:seon.agent/id "owner"})` installed with
  `env/replace-environment!`;
- commit the roster row the turn writer would commit, through
  `seon.test-support/transacted!`: `:seon.test/sym`, `:seon.test/ns`
  (the agent's namespace), `:seon.schema.admission/source :agent`. No
  `:seon.test/source`: the declaration's source names the basis this write
  advances;
- read `basis` AFTER that last fixture write, so the agent's own assertion
  still names the exact database value its cluster holds.

Assertions are unchanged and unweakened: the agent's `deftest` is admitted,
`my.test/run` answers its declared roster, that one test ran, and its elided
`(seon.db/db)` read reached the agent's own cluster (`pass-count` 1,
`fail-count` + `error-count` 0).

## Measured, in process on `default` (adopted commit
`6aaab871-4c0d-57a3-83bc-79c1e128b59f`)

`(seon.test/run (#'seon.test/resolve-test 'ns/test) (seon.operator/connection "default") {... :seon.test/remaining-ms 120000})`,
each after reloading only the test namespace through `#'seon.test/with-test-loader`:

| run | result |
|---|---|
| `my.test-test/...elided-arity` BEFORE the fix | pass 2, fail 3, error 1 — `:seon.agent/id is unavailable` |
| same, agent id supplied, no roster row | pass 2, fail 2, error 1 — `results` `[]` |
| same, roster row before the basis read | **pass 5, fail 0, error 0** |
| `seon.test-support-test/a-test-body-inherits-no-ambient-cluster-custody` | pass 3, fail 0, error 0 |
| `seon.test.accretion-test` (all 9 tests, each run) | 39 assertions, fail 0, error 0 |

The declared 20 s `seon.test/run` bound is too small for this fixture in the
development JVM (both 2-arity runs ended in the bound); the runs above used
an explicit 120 s `:seon.test/remaining-ms`.

Verification boundary: in process on `default` only. The cold proof is the
orchestrator's batched gate (request appended to
`tmp/orchestrator/gate-requests/run-owned-env.txt`).
