---
title: "Card F4a: Proofs run after a code-changing pass"
type: reference
status: active
created: 2026-10-08
---

# F4a: Proofs run after a code-changing pass

**Q-B answered (owner 2026-10-08 late):** the step runs after any eval request that evaluated a form; unchanged proofs
answer from their record. Option 1 as written below. **In flight (lane f4a).** Group B (`turn.clj`). Model: Opus (the proc step is the one REPL path).
Hard dependency: LR-CACHEb landed (a branch's first reaching check is GB without it).

## Files

- `src/seon/turn.clj`
- `resources/seon/schemas/seon.turn.loop.edn`
- `test/seon/cluster/turn_test.clj`

## Where

`seon.turn/step` (`turn.clj:5877`), the 4-arity, right after `report` is built from `(turn …)` and before
`(async/offer! reply …)` (the `let` binding `_ (when reply …)`, about `turn.clj:6050`). The step therefore runs
serially inside the pass; the reply and the next pass see its results.

## Functions

```clojure
(defn- step-due?
  "Whether the pass `report` changed something the agent's tests could see: it evaluated at least one form (Q-B option 1)."
  {:malli/schema [:=> [:cat :seon.turn.loop/turn-report] :boolean]}
  [report] (pos? (:seon.turn.loop/forms-run report)))

(defn- task-proofs
  "The proofs of the task `agent-id` holds, read at the task home: empty when it holds none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or [:set :seon.test/sym] :seon.error/value]]}
  [database agent-id] ...)
```

`task-proofs`: home = `(or (some-> (seon.task/home database) db/db) database)` (`task.clj:381`, as
`seon.context/current-state` does); handle = `(seon.task/agent-task home agent-id)` (`task.clj:600`); nil → `#{}`;
sym = pull `:seon.task/sym`; proofs = union of `(:seon.task/proofs (task-edges home sym))` and
`(:seon.task/proofs (task-edges database sym))` (the branch may hold new proofs; `my.program/request-merge!` unions
the same two, `src/my/program.clj:437-438`). Require `[seon.task :as task]` in `seon.turn`: probed at the REPL, `seon.task`'s transitive alias closure does not
contain `seon.turn` (and `seon.turn` already reaches `seon.task` transitively), so no load cycle.

```clojure
(defn- run-proofs
  "Run the held task's proofs on the agent's branch as one request carrying the pass id; nil when there are none."
  {:malli/schema [:=> [:cat :map :seon.agent/id :seon.turn/id]
                  [:or :nil :seon.test/run-result :seon.error/value]]}
  [cluster agent-id pass-id] ...)
```

Body: `execution` = `(@cluster-agent-acquire-context! cluster agent-id)` (`turn.clj:59`, `seon.cluster.agent/acquire-context!`
`cluster/agent.clj:1022`); proofs from `task-proofs` on `(db/db (:seon.db/connection execution))`; empty → nil; else
`(seon.test/run {:seon.test/execution execution :seon.test/recording-connection (:seon.db/connection execution)
:seon.test/policy :named :seon.test/identities proofs})` — exactly the request `request-merge!` makes
(`my/program.clj:446`). The pass id is NOT put on the request or the run: it is carried in the reply's summary only;
recording it with the run waits on EN-6, which rewrites run recording. `seon.test.edn` is not touched.

In `step`: `tests (when (and (step-due? report) (not (:seon.turn.loop/refusal report))) (run-proofs cluster agent-id (:seon.turn/id report)))`,
then the reply carries `(cond-> report tests (assoc :seon.turn.loop/tests (summary (:seon.turn/id report) tests)))`, where

```clojure
(defn- summary
  "The test step's result as the reply shows it: counts and the red tests' names."
  {:malli/schema [:=> [:cat :seon.turn/id [:or :seon.test/run-result :seon.error/value]] :seon.turn.loop/test-summary]}
  [pass-id run] ...)
```

answers `{:seon.turn/id pass-id :seon.test/passed? … :seon.turn.loop/red [syms…]}` from the run result (its member results' fail/error
counts), or the error value whole under `:seon.turn.loop/test-error`.

## Schemas (`seon.turn.loop.edn`)

```clojure
:test-summary [:map [:seon.turn/id :seon.turn/id]
               [:seon.test/passed? {:optional true} :boolean]
               [:seon.turn.loop/red {:optional true} [:vector :seon.test/sym]]
               [:seon.turn.loop/test-error {:optional true} :seon.error/value]]
:tests :seon.turn.loop/test-summary
```

and `[:seon.turn.loop/tests {:optional true} :seon.turn.loop/tests]` on `:turn-report`.

## Proof

`seon.cluster.turn-test/a-defn-reaching-a-proof-records-its-result-without-a-check-call`: `with-running-agent`; root
creates task `tq.f4/area`, defines on the agent's branch through `seon.mcp/call` `(ns tq.f4) (defn area [r] (* r r))`
and `(deftest ^{:seon/tasks #{'tq.f4/area}} area-test (is (= 4 (tq.f4/area 2))))`, assigns the task; then one
`eval_clj` as the agent: `(in-ns 'tq.f4) (defn area [r] (* 3 r))`. Assert, with no `my.test/check` call anywhere:
`(seon.test.runner/latest-results (db/db branch-conn) ['tq.f4/area-test])` has one result with fail-count 1, and the
latest result's run was created after the pass's settlement (its `:seon.test/run-basis-t` ≥ the pass's basis).

`--test seon.cluster.turn-test --test seon.turn-test --test seon.mcp-entrance-test`.

## Cost

Follows the proofs' reach inputs: a proof whose reach is unchanged answers from the record (a reach-digest lookup);
an executed proof costs one test world (F1's 400 ms interim allowed while under GB). Measure with
`seon.pass-probe/run-mcp` SCI on parent and change: the added time must equal the request's own time.

## Deleted

Nothing.

## Commit

`bin/lane commit f4a -m "F4a: the proofs run after a pass that changed code" --test seon.cluster.turn-test --test seon.turn-test --test seon.mcp-entrance-test src/seon/turn.clj resources/seon/schemas/seon.turn.loop.edn test/seon/cluster/turn_test.clj`

Dependencies: LR-CACHEb, Q-B.
