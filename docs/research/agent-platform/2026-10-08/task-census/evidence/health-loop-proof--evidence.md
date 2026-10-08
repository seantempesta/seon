# E1 health loop on live default (lane health-loop-proof-opus, 2026-09-28)

Agent `e1-probe`, branch `:agent-665a30c12282` (MCP `eval_clj` with an explicit agent), cluster `default`.
Live commits: default ran 0f92ebdbc, then fb1437312 from 21:49Z (holds 00fb3ace6 … e171bd9a0; not f06de7f83).

## Breaks found and fixed

| # | Break (observed live) | Owner | Fix |
|---|---|---|---|
| 0 | `:seon.repl/error` came back as ONE pr-str'd string whenever the error held a host object (`#object[…]` has no EDN reader in `seon.repl/data-text`) | seon.repl | 00fb3ace6 (live, verified: same refusal now answers `:error {…}` a map) |
| 1 | An armed input refusal inside an agent evaluation reached the agent with no `:seon.error/producer` (`seon.sci.kernel/failure-value` never attributed an existing refusal) | seon.sci.kernel | 135793d7f |
| 2 | No evaluation failure was ever recorded as a fault (`seon.batch` records only stage diagnostics), so a definition's refusal never reached health | seon.batch | 135793d7f |
| 3 | The health report's open-fault finding names the refuser (`:seon.health/subject :seon.error/operation`), so its task never links | seon.problems + seon.health (held) | f184e5927 lifts `:seon.error/producer` onto the unit; ranking = patch `patches/regression-alarm-opus/01-open-fault-subject-is-the-producer.patch` |
| 4 | The cluster copy of an agent-branch fault stored no `:seon.error/producer-digest` (the cluster lacks the agent's definition), so resolution could never see the change there | seon.fault | e171bd9a0 |
| 5 | The turn path records the fault BEFORE its settlement commits the definition (occurrence tx 536871345 < definition tx 536871346), so even the origin occurrence had no digest | seon.batch | f06de7f83 (needs restart) |

## Step 1 — a real failure of fault 103511's class, recorded with the producer

At HEAD before the fix (0f92ebdbc), same form: the error value named `:seon.instrument/fn seon.turn/record-evaluated-tx`
and no producer; `(seon.error.refusal/producer err nil)` answered `{}` on the reply value, and 0 signatures existed for
`record-evaluated-tx` on branch or cluster.

Live on fb1437312 (21:50Z), one MCP SCI run of agent `e1-probe`:

```clojure
(in-ns 'my.e1probe)
(defn opening-unit
  "Builds the recording request for one opening unit: its admitted form carries no namespace (fault 103511's class)."
  {:malli/schema [:=> [:cat] :map]}
  []
  (seon.turn/record-evaluated-tx {:seon.turn.loop/evaluated-sources [{:seon.turn.loop/admitted-form {:seon.cluster.eval/source "(+ 1 2)"}}]}))
(opening-unit)
```

Reply (23 ms, 21 calls): `:seon.repl/error` a map with `:seon.sci.eval/stacktrace [[seon.turn/record-evaluated-tx 1379 1] [my.e1probe/opening-unit 5 3] …]`
and `:errors [{:seon.error/signature "ad3c7aa4d298…" :seon.error/operation seon.turn/record-evaluated-tx :seon.error.occurrence/branch :agent-665a30c12282 …}]`.

Stored signature on the branch:

```clojure
{:seon.error/producer my.e1probe/opening-unit
 :seon.instrument/fn seon.turn/record-evaluated-tx
 :seon.error/occurrences [{:seon.error/caller my.e1probe/opening-unit :seon.error.occurrence/count 1
                           :seon.error.occurrence/branch :agent-665a30c12282
                           :seon.error.occurrence/evaluation "4d4526929e2b"}]
 :seon.task/_faults [{:seon.task/id "ccd4e875780c" :seon.task/must-read [my.e1probe/opening-unit]}]}
```

Cluster copy on default: same producer and task, occurrence branch `:agent-665a30c12282`. Missing on both:
`:seon.error/producer-digest` (break 5, fixed in f06de7f83, not yet live).

`seon.problems/open-faults` on default (f184e5927 live) names the producer on the unit; with patch 01's subject rule the
finding's subject is `my.e1probe/opening-unit` and `with-tasks` links `#{[my.e1probe/opening-unit "ccd4e875780c"]}`
(simulated at the REPL over the live units, 45 ms). Unpatched, `seon.health/report` (87–129 ms) lists the finding under
subject `seon.turn/record-evaluated-tx` with no `:tasks`; MCP `runtime_status` shows top 5 only (open-fault count 7).

Earlier manual recorder probe (fault! called as the fixed fold calls it, before the restart): 140 ms for `fault!`,
signature `eb5241ff…` producer `my.e1probe/system-row`, producer digest `79659413…` on the origin, task `cb07d384b078`.

## Step 2 — the task is on the queue

```clojure
(filterv #(= "cb07d384b078" (:seon.task/id %)) (my.task/open))
;; => [{:seon.task/id "cb07d384b078" :seon.task/name "faults of system-row" :seon.task/ns my.e1probe
;;      :seon.task/occurrences 1 :seon.task/faults ["eb5241ff…"] :seon.render/operations [(my.task/claim! …)]}]
;; 30 ms, 3,529 armed calls (seon.db/database-value? 774×)
(my.task/claim! {:seon.task/assigned "cb07d384b078"})
;; => {:seon.task/assignment "bcf5853c6401" :seon.agent/id "e1-probe" :seon.task/branch :agent-665a30c12282
;;     :seon.render/operations [(my.program/propose-merge! {})]}   41 ms
```

Finding: after the claim, `(my.task/open)` on the agent's branch still offers `cb07d384b078` (the assignment is written
to the task home, the queue reads the branch). Task file entry: resources/seon/tasks/my.task.edn.

## Step 3 — derived close: BLOCKED

The proof test was declared on the branch:

```clojure
(clojure.test/deftest ^{:seon/tasks #{"cb07d384b078"}} system-row-carries-its-namespace
  (clojure.test/is (= 'my.e1probe (:seon.cluster.eval/ns (system-row)))))
```

Its red run never ran: `(my.test/run-owned {:seon.ns/name 'my.e1probe})` refused three times (2.2–5.1 s,
325k–435k armed calls each) with `no branch or commit 6abae170…/6abae177…/6abae1d6… to branch from`
(`:seon.cluster.registry/source-absent`, request branch `:agent-…` off the fresh checkpoint head). The same
prepare-checkpoint → overlay → branch! sequence run by hand from the default instance's store succeeds (840 ms), so the
break is in how a hosted agent's execution reaches `seon.test/clean-request-handle`. The agent branch's world base is the
stale adopted commit `6abadd62…` (the cluster now records `6abae0f5…`). Every run also carried a diagnostic:
`seon.turn.batch/join-write` refused its own output contract (`:seon.batch/join`). Task: resources/seon/tasks/seon.test.edn.

Without a red-then-green proof and an accepted proposal, `seon.error/resolution` stays nil by design:
`(seon.task/resolution "cb07d384b078")` => `nil` (1 ms).

## Costs

- `produced-failures` on the success path: 1.27 µs per 10-form batch (JVM microbenchmark, 100k iterations).
- `fault!` for one new signature with its cluster copy: 140 ms.
- Store: `du -sk data/store` 546,036 KB at start, 932,900 KB at the end, dominated by other lanes' test rounds in the same
  window; this probe wrote about 25 evaluations, 3 faults with copies, 1 claim.
