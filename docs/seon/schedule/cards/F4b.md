---
title: "Card F4b: Reaching tests run after the proofs"
type: reference
status: done (34dac2655)
created: 2026-10-08
---

# F4b: Reaching tests run after the proofs

Depends on: F4a (same step, same files). Group B. Model: Opus (proc step). Hard dependency: LR-CACHEb.

## Files

- `src/seon/turn.clj`
- `resources/seon/schemas/seon.turn.loop.edn`
- `test/seon/cluster/turn_test.clj`

## Functions

```clojure
(defn- run-reaching
  "Run the tests reaching the branch's change, after the proofs, as one request; nil when the branch equals its base."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle]
                  [:or :nil :seon.test/run-result :seon.error/value]]}
  [execution] ...)
```
Body: exactly `my.test/check`'s body without a named change (`src/my/test.clj:22-29`):
`changed` = `(seon.test/changes execution (:seon.db/connection execution))` (`test.clj:1813`). When `changed` is the
`:seon.test/policy-unscoped` refusal (the program equals its base: "there is no change to check"), answer nil; any
other error returns whole; else `(seon.test/run {:seon.test/execution execution :seon.test/recording-connection
(:seon.db/connection execution) :seon.test/policy :named :seon.test/changed (vec changed)})`.

F4a's `run-proofs` acquires `execution` once; change it to take `execution` as an argument and acquire it once in
`step` for both requests (one acquisition per step).

In `step`, after `run-proofs`: `reaching (run-reaching execution)`; the reply's `:seon.turn.loop/tests` becomes the
summary of both: `{:seon.turn/id … :seon.turn.loop/proofs <summary> :seon.turn.loop/reaching <summary>}`.

## Schemas

`:test-summary` (F4a) gains `[:seon.turn.loop/reaching {:optional true} :seon.turn.loop/request-summary]`, where
F4a's summary map becomes `:request-summary` and `:test-summary` is
`[:map [:seon.turn/id :seon.turn/id] [:seon.turn.loop/proofs {:optional true} :seon.turn.loop/request-summary]
[:seon.turn.loop/reaching {:optional true} :seon.turn.loop/request-summary]]`.

## Proof

`seon.cluster.turn-test/a-defn-breaking-a-reaching-test-shows-it-red-on-the-next-call`: as F4a's setup, plus a
second deftest `tq.f4-test/perimeter-uses-area` (not naming the task) calling `tq.f4/area` and green on the branch.
One `eval_clj` as the agent redefines `area` so that test fails. Assert from the reply data: `:seon.turn.loop/tests`
→ `:seon.turn.loop/reaching` → `:seon.turn.loop/red` contains `tq.f4-test/perimeter-uses-area`; and
`seon.test.runner/latest-results` on the branch has it with fail-count 1.

`--test seon.cluster.turn-test seon.turn-test seon.mcp-entrance-test seon.test-reaching-test`.

## Notes (root ruling 2026-10-08)

- The step is the dial `:seon.config.test/step?` (`resources/seon/schemas/seon.config.test.edn`, default true; an absent
  value runs the step). `a-batched-turn-commits-only-queryable-definition-facts` and
  `ns-unmap-retracts-the-owned-function-after-the-terminal-commit` test recording, not testing, so their setup turns
  it off; every other test keeps the default. `agent-dial` returns a declared false (it fell through `or` before).
- `with-cluster` in `seon.cluster.turn-test` defaults the step off (`*test-step?*`): those tests are not about testing and the step's test worlds fill the shared reach cache. The proof deftest uses `with-running-agent`, which keeps it on.
- Known red at landing: `seon.test-reaching-test/two-branches-of-one-program-share-reach-declarations` (task `seon.turn/two-branches-share-reach-declarations-is-red-beside-the-turn-tests`).
- `seon.cluster.agent/evaluate-source!` keeps `:seon.turn.loop/tests` and `seon.mcp/sci-run` prints it as the last
  reply entry, so the proof asserts the reply data.

## Cost

Follows the reaching tests' reach inputs, never the program, except `seon.test/changes`' digest comparison of the
branch's declaration families against its base, which is proportional to the program's declarations: measure it in
`seon.pass-probe/run-check`; over 1 s is a finding naming `seon.test/changes` before this lands.

## Deleted

Nothing (F9 deletes the Var render's check offer).

## Commit

`bin/lane commit f4b -m "F4b: the tests reaching the change run after the proofs" --test seon.cluster.turn-test seon.turn-test seon.mcp-entrance-test seon.test-reaching-test src/seon/turn.clj resources/seon/schemas/seon.turn.loop.edn test/seon/cluster/turn_test.clj`
