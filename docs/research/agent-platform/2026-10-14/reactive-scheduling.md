---
title: "Reactive scheduling: datom to started graph"
type: research
status: complete; measurement only, no source or main-database writes
basis: "default JVM at HEAD fdc34a9ba plus working tree, 2026-10-14"
---

# Reactive scheduling: from a committed datom to a started graph (measurement lane, 2026-10-14)

Read-only on default (HEAD fdc34a9ba plus the working tree; no source edited, nothing transacted to main, no resets).
Probes ran in `eval_clj` JVM mode on `(seon.cluster.boot/connection "default")`; hypothetical writes used
`datahike.api/with` on the current main value and were discarded. Store handle for `lifecycle-facts`:
`(some :seon.store/store (vals @seon.operator.runtime/root-store-holder))`. "Inferred" marks reading without a run.
Prior map: `docs/research/agent-platform/2026-10-13/context-root-flow.md`; companion:
`docs/research/agent-platform/2026-10-14/chain-on-one-branch.md`.

## Answer in five lines

1. A datom reaches the scheduler only as a payload-free wake. `seon.cluster.wake/route!` looks at each datom's attribute,
   and for a `:seon.wake/starts` attribute does `(async/offer! plan-channel ::wake)`. The datom, its entity and its value
   are thrown away at that line.
2. `seon.cluster.agent/db<->flow` then re-derives everything from the database: all assignments (`lifecycle-facts`),
   all agent branches (`taskless-agents`), all ready leaves (`plan-facts`), all agents (`agents-to-start`). Every hop
   after the listener is proportional to the cluster, none to the change.
3. On the idle default cluster that is cheap (about 18 ms, 25 MB per pass). It is not cheap with many ready leaves:
   `plan-facts` is 355 ms and 341 MB warm for 834 ready leaves, and 1.3 s and 1.4 GB cold, because the obligations cache
   (`seon.cluster.agent/basis-valid?`) is keyed by main's commit id, so every main commit makes it cold.
4. The tx-report already holds what is needed to derive only the implied work. A scratch function over a real
   `d/with` report (section 3) found the affected leaves and agreed with the full `seon.task/ready` diff on both test
   transitions, at 1.5 ms against 5 ms here, and the saving grows with leaf count (the per-leaf cost is in
   `basis-valid?` and `task/relaunched`, which delta-driven work would run only for the affected leaves).
5. `:seon.task/next` does not exist (`(get (:schema db) :seon.task/next)` is nil in the other lane's probe; `rg` finds it
   only in research docs). The same effect exists today as `:seon.task/needs`, which `seon.task/ready-rules` already
   reads. Section 4 names the plug-in point.

## 1. Hop table (datom `:seon.task/execution :running` on a task to a started graph)

Declared wake sources, by schema (`:seon.wake/starts true`): `:seon.task/execution`, `:seon.task/needs`,
`:seon.task.attempt/end`, `:seon.task.attempt/relaunch`, `:seon.task/git-commit`, `:seon.schedule.task/owner`,
`:seon.agent/id`. `:seon.task/outcome` and `:seon.task/merge-base` are NOT wake attributes (see gap G3).
"Prop." = what the hop's work is proportional to.

| # | Hop (fully qualified) | Reads | Decides | Prop. |
|---|---|---|---|---|
| 1 | Datahike `d/listen`, registered by `seon.cluster.wake/route!` on main (key `:seon.agent/route`, built in `seon.cluster/cluster-graph-definition`) and on each started branch by `seon.cluster.agent/start-branch-flow!` (key `seon.cluster.agent/branch-route-key`) | the tx-report | runs per commit, on the committing thread | commit |
| 2 | `route!` callback: `wake-matchers` (query over wake attributes and runtime listens, recompiled from `:db-after` every report), `starting-attributes`, then `seon.db/logical-datoms report` | each datom's attribute only | `:seon.wake/starts` and `:added` -> `offer!` `::wake`; schema/mailbox attribute -> that agent's mailbox, or the plan channel if the recipient has no routing entry; render interest -> one render wake | datoms in the tx. Measured: `wake-matchers` 0.01-0.12 ms, `starting-attributes` under 0.01 ms per report |
| 3 | plan channel `seon.cluster.wake/channel` = `seon.cluster.agent/wake-channel` (sliding buffer of 1) into the `:seon.flow/plan` in-port of `seon.cluster.agent/db<->flow` | nothing | coalesces any number of wakes into at most one queued pass | constant |
| 4 | `seon.cluster.agent/db<->flow` 4-arity (message arity): `run-pass!` in a try, `failed-pass!` as the one catch. Also primed by `::flow/resume` and called synchronously by boot | the message is ignored (`:else` branch; only `:seon.agent/quiesce` is read) | "do a pass" | constant |
| 5 | `seon.cluster.agent/run-pass!` step A: `lifecycle-facts` then `next-actions` then `apply-plan!`, re-derived after `:stop` and after `:merge` (up to 3 times) | main, the store's branch roster, every assignment's end (pulled; branch head read when main holds no end or head is not the merge base), every taskless agent's end and `source/unmerged-count` | stop / merge-base / merge / publish / close / relaunch / delete-branch of ENDED assignments; stop / delete-branch of taskless agents | all assignments and all agent branches. Measured 12.1 ms, 17.3 MB (1 assignment, 3 taskless agents); 11.4 ms and 13.0 MB of it is `taskless-agents` alone |
| 6 | `run-pass!` step B: `seon.cluster.agent/plan-facts` (`seon.task/ready`, sym query, open-attempt query, attempts-made, `seon.task/relaunched` per leaf, creation order, `basis-valid?` per leaf) then `plan` | all ready leaves | which leaves to attempt, which open attempts to pause/resume/stop/cancel, which leaves are `:invalid` (problem tasks) | ready leaves, per main commit. Measured below: 2.0 ms/2.8 MB with 0 ready; 355 ms/341 MB warm with 834 ready |
| 7 | `seon.cluster.agent/apply-plan!` -> `seon.cluster.agent/write-attempt!` (`:attempt`) | `seon.task/attempt-tx` over main | one transaction on main: agent creation (`creation-tx`) plus the attempt row (`:seon.task/assigned`, `:assignment`, `:branch`, `:agent`). This transaction asserts `:seon.agent/id`, a starting attribute, so it offers hop 3 again; the next pass is a no-op by identity | one attempt |
| 8 | `apply-plan!` -> `seon.cluster.agent/start-attempt!` (`:start-flow`): `fork-attempt!` -> `seon.cluster.registry/fork!` (skips if the branch exists), `write-merge-base!`, `acquire-context!`, `seed-attempt!`, `start-branch-flow!` | main, the store | fork main (about 54 ms, measured by the other lane), write the base on main, open the branch connection, write the agent and row onto the branch if the fork predates them | one attempt |
| 9 | `seon.cluster.agent/start-branch-flow!` -> `route!` on the branch connection (hop 1 for the branch) -> `seon.cluster.agent/start-flow!` -> `start-claimed-flow!` | the branch | build the Flow graph from `graph-definition`, resume it | one agent |
| 10 | `run-pass!` step C: all agents query, `agents-to-start` (a `turn/more-agent-work?` call per unstarted agent) then `start-flow!` per agent with work or a schedule owner | every agent row on main | agents whose branch is the pass's own branch and that have work | all agents. Measured 3.8 ms, 4.1 MB for 4 agents (15.9 ms cold) |

Gaps this exposes (all inferred from the code unless a number is given):

- **G1.** `route!` keeps the datom for mailbox delivery (`(get (channels) agent-eid)`) but for starts it keeps nothing. A
  pass cannot ask "what changed", so it must ask "what is true".
- **G2.** The branch route (hop 1, branch side) runs `wake-matchers` and friends for every transaction an agent makes on
  its branch, and any `:seon.task.attempt/end` or `:seon.agent/id` datom on a branch wakes the cluster's one plan channel.
  The cost per report is small; the consequence is a whole-cluster pass per branch write of a starting attribute.
- **G3.** `:seon.task/outcome :merged` (written by `seon.merge/merge-tx`) is not a wake attribute. A merge run inside a
  pass is followed by `(lifecycle!)` and `plan-facts` in the same pass, so the next task starts there. A merge by hand
  outside a pass would not itself wake `db<->flow` (inferred; its later `:seon.task/git-commit` write would).
- **G4.** `seon.cluster.agent/basis-valid?` caches under `[(db/commit-id database) sym]`
  (`seon.cache/lookup ::basis-valid`), so any main commit, related or not, makes every leaf's obligations cold again.
  CLAUDE.md "Reuse content-valid work; derive without stamps" says the key should be what the derivation read.

## 2. Measurements (default, warm, read-only)

Cluster size on main: 861 `:seon.task/id` tasks (845 parentless, 22 `:seon.task/needs` datoms), 1 assignment,
4 agents, 0 tasks `:running` (845 `:held`), 1 open attempt (held), 645,945 datoms, 13 branches including `base/*`.
Per-thread allocation from `ThreadMXBean.getThreadAllocatedBytes`; times `System/nanoTime`; 4-5 runs each.

| Derivation | Scenario | Time | Alloc |
|---|---|---|---|
| `seon.task/ready` | idle (0 running) | 1.1-1.2 ms | 1.7 MB |
| `seon.cluster.agent/plan-facts` | idle | 1.9-2.1 ms | 2.8 MB |
| `seon.cluster.agent/lifecycle-facts` | 1 assignment + 3 taskless agents, empty registry | 11.8-13.2 ms | 17.3 MB |
| of which `taskless-agents` | 3 taskless agents with branches | 11.3-11.7 ms (15.5 ms first) | 13.0 MB |
| of which assignment-rows query | 1 assignment | 0.07-0.16 ms | 0.1 MB |
| `seon.cluster.agent/next-actions` | over those facts | 0.01 ms | 0.01 MB |
| agents scan + `turn/more-agent-work?` per agent | 4 agents | 3.8-4.2 ms (15.9 ms first) | 4.1 MB |
| `seon.cluster.wake/wake-matchers` per report | main | 0.01-0.12 ms | 0.01 MB |
| **One idle pass** (sum, one `lifecycle!`) | | about 18-20 ms | about 25 MB |

Scale scenario, hypothetical (`d/with` marking all 845 parentless tasks `:running`, discarded; 834 ready leaves,
205 with obligations, 629 `:invalid`; the commit id equals main's, so caches are shared with the live system):

| Derivation | Time | Alloc |
|---|---|---|
| `seon.task/ready` | 6.6-10.7 ms | 6.5-9.8 MB |
| `task/relaunched` over all leaves | 301 ms | 291 MB |
| `basis-valid?` over all leaves, cold | 1,292 ms | 1,432 MB |
| `basis-valid?` over all leaves, warm | 3 ms | 3 MB |
| `plan-facts` total, 1st call (cold) | 2,268 ms | 1,779 MB |
| `plan-facts` total, 2nd and 3rd calls (warm) | 355-370 ms | 341 MB |

Reading: per ready leaf, `basis-valid?` costs 1.55 ms and 1.7 MB cold, `task/relaunched` 0.36 ms and 0.35 MB, every
pass that follows a main commit. With the current workload (one or two ready leaves) none of this shows; a chain
launch of tens of leaves, or the 22-needs backlog going live, would. Per CLAUDE.md "10x over what the work must do is
wrong work": a commit that changes one task's execution should cost work for that task, which is about 1.5 ms (below).
Nothing here exceeds the one-second rule on the live cluster today; the scale scenario does (3.4 s for the three-call
probe, findings from the REPL reply).

## 3. The tx-report and a delta-driven pass

What `seon.cluster.wake/route!` does with the report, in order: `(wake-matchers (:db-after report))`,
`(starting-attributes (:db-after report))`, a loop over `(db/logical-datoms report)` where for each datom only
`(:a datom)`, `(:added datom)`, and for mailbox matchers `(:e datom)` / `(:v datom)` are read, and the loop's one
effect for a starting attribute is `(async/offer! plan-channel ::wake)`. The report's `:tx-data` (datoms with `:e :a :v
:tx :added`), `:db-before`, `:db-after` are all available and unused for scheduling.

The affected-work rule is a function of the datoms:

| Changed datom | Work implied |
|---|---|
| `[task :seon.task/execution v]` | readiness of the leaves at or under `task` (the `execution` rule inherits down `:seon.task/children`) |
| `[task :seon.task/needs n]`, `:seon.task/children` | the same leaves |
| `[attempt :seon.task.attempt/end v]`, `:relaunch`, `:seon.task/outcome`, `:seon.task/refusal`, new `:seon.task/attempt-of` | the attempt's task (via `:seon.task/attempt-of`); every task that `:seon.task/needs` it or any ancestor of it (its dependents become ready or stay blocked); and the assignment's own lifecycle (`assignment-actions` for that one attempt) |
| `[agent :seon.agent/id]` | that agent only (`agents-to-start` for one id) |

Scratch REPL function (not committed anywhere; written against the shape of `seon.task/ready`, run on `d/with` reports).
Two Datahike quirks forced the shape, both already acknowledged in the `seon.task/ready` docstring: a recursive rule under
`not`/`not-join` loses its binding when the call carries a bound collection, so "blocked" is computed from the positive
rule `needs*` and `leaf-done` in Clojure, and ancestors are walked in Clojure rather than by a rule.

```clojure
(let [d datahike.api/q
      rules seon.task/ready-rules
      blocking (var-get #'seon.task/blocking-ends)
      ancestors (fn [db es] (loop [seen (set es) frontier (set es)]
                              (let [ps (set (d '[:find [?p ...] :in $ [?c ...] :where [?p :seon.task/children ?c]] db (vec frontier)))
                                    new (remove seen ps)]
                                (if (empty? new) seen (recur (into seen new) (set new))))))
      ;; tx-report -> leaf eids whose readiness inputs changed (derived from the datoms alone)
      dirty-leaves
      (fn [{:keys [tx-data db-after]}]
        (let [by-attr (group-by :a tx-data)
              eids (fn [attrs] (into #{} (comp (mapcat #(get by-attr %)) (map :e)) attrs))
              own (eids [:seon.task/execution :seon.task/needs :seon.task/children])
              att (eids [:seon.task.attempt/end :seon.task/outcome :seon.task/refusal
                         :seon.task.attempt/relaunch :seon.task/attempt-of])
              att-tasks (if (seq att) (d '[:find [?t ...] :in $ [?a ...] :where [?a :seon.task/attempt-of ?t]] db-after (vec att)) [])
              touched (into own att-tasks)
              dependents (d '[:find [?y ...] :in $ [?n ...] :where [?y :seon.task/needs ?n]] db-after (vec (ancestors db-after touched)))
              roots (into (set touched) dependents)]
          (set (d '[:find [?l ...] :in $ % [?n ...] :where (self-or-under ?n ?l) [?l :seon.task/id] (not [?l :seon.task/children _])]
                  db-after rules (vec roots)))))
      ;; seon.task/ready's own clauses, restricted to the candidates
      ready-among
      (fn [db cands]
        (let [eligible (d '[:find ?t ?h :in $ % ?blocking [?t ...] :where (execution ?t ?x) [(= ?x :running)]
                            [?t :seon.task/id ?h] (not [?t :seon.task/children _]) (not (leaf-done ?t))
                            (not-join [?t ?blocking] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end ?e]
                                      [(contains? ?blocking ?e)] [(missing? $ ?a :seon.task.attempt/relaunch)])
                            (not-join [?t] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end _] [?a :seon.task/refusal _]
                                      (not [?a :seon.task/outcome :merged]) [(missing? $ ?a :seon.task.attempt/relaunch)])]
                          db rules blocking (vec cands))
              ts (mapv first eligible)
              pairs (d '[:find ?t ?l :in $ % [?t ...] :where (needs* ?t ?n) (self-or-under ?n ?l)
                         [?l :seon.task/id] (not [?l :seon.task/children _])] db rules ts)
              done (set (d '[:find [?l ...] :in $ % [?l ...] :where (leaf-done ?l)] db rules (vec (distinct (map second pairs)))))
              blocked (set (keep (fn [[t l]] (when-not (done l) t)) pairs))]
          (set (remove blocked ts))))]
  ...)
```

Result on real main (task T `483857`, `seon.turn/a-held-task-opens-no-model-turn`, needs N `483697`,
`seon.cluster.agent/each-intent-names-one-roster-state`; both parentless leaves), each step a real `d/with` report:

| Step | Report | Candidates found | Became ready (delta) | Became ready (full `seon.task/ready` diff) | Agree |
|---|---|---|---|---|---|
| 1 | `:seon.task/execution :running` on T and N: 3 datoms | 4 leaves | N (`9339e846fa80`) | N | yes |
| 2 | new attempt of N with `:outcome :merged`: 3 datoms | 2 leaves (N, T) | T (`c5dadbd9c588`); N left ready | T; N left ready | yes |

Cost of the readiness decision for step 2 over the same db: delta 1.5-1.6 ms and 1.4 MB against full `seon.task/ready`
5.0-5.7 ms and 6.3 MB. That is a small saving here because only 2 leaves are running. The bigger saving is what the delta
does not need to do: `basis-valid?` and `task/relaunched` for the other leaves (section 2: 1.9 ms and 2.0 MB per leaf per
commit cold), and `lifecycle-facts` for assignments whose attempt did not change.

How a pass would use the report (sketch, no code changed):

1. `route!`, at the line that today offers `::wake`, offers `{::wake true :tx (:tx report) :datoms <the starting-attribute
   datoms>}`; the plan in-port needs a buffer that merges instead of overwriting. Today it is a sliding buffer of 1
   (`seon.cluster.agent/wake-channel`), valid because "a wake says only look"; a delta channel must accumulate the
   dirty eids (a set union per message, still bounded by the number of tasks and attempts, still rederivable). Keep the
   prime on `::flow/resume` and boot as a full derive: facts remain the source of truth, the delta is an optimization of
   which facts to re-read. This keeps the "recover facts, not execution" law and removes no fallback.
2. The pass splits the derivations by key: `lifecycle-facts` for the attempts in the dirty set (one `db/pull` and one
   branch read each) rather than for all; `plan-facts` for the dirty leaves (`ready-among`, then `basis-valid?`,
   `task/relaunched`, `attempts-made` for those only); `agents-to-start` for the dirty agent ids.
3. `taskless-agents` is the biggest idle cost (11.4 of 12.1 ms): it needs its own owner-side change (a taskless agent's
   end is a datom on main's agent row or on its branch head, so it is a delta input too).
4. `basis-valid?` should key on what it read (the task, its ancestors, its needs, its proofs: the `seon.task/obligations`
   read evidence), not on the commit id (G4). That alone removes the 1.3 s cold cost at scale without a delta channel.

Cheapest first move (inferred, not measured): fix G4 and per-leaf `relaunched` before building the delta channel; they
are 1.55 + 0.36 ms of the 1.9 ms per leaf, and each is local to one function.

## 4. Where `:seon.task/next` plugs in (task A's end, task B ready, B's agent on the chain branch)

Current mechanism for ordering: `:seon.task/needs` (task B needs A), read by the `needs*` and `blocked` rules in
`seon.task/ready-rules`; A counts done only by `leaf-done`, i.e. an attempt of A whose `:seon.task/outcome` is `:merged`
on main; `seon.task/chain!` writes each copy needing the one before it. So the path today is: A's agent ends (`:done` on
its branch) -> branch route wakes `db<->flow` (hop 1-4) -> `lifecycle-facts` finds the end -> `assignment-actions` emits
`:stop`, `:merge-base`, `:merge` (gated, `seon.merge/merge!`), `:publish`, `:delete-branch` -> `merge-tx` writes
`:outcome :merged` -> same pass, `plan-facts` finds B ready (its need is `leaf-done`) -> `plan` emits `:attempt` and
`:start-flow` with fork-point `:main` and branch `:attempt-<B key>` -> `fork-attempt!` forks main again.

The `chain-on-one-branch` probe (same day) concluded the chain should keep one branch, sync only status facts to main
between steps with `seon.merge/squash-merge` (8 ms plus 22 ms apply, measured there), and run one gated merge for the last
step. The plug points for a `:seon.task/next` backlink (new attribute: needs a TASK-CHAIN amendment and a red deftest per
CLAUDE.md "Keep the task-chain design locked"):

| Function | Change | Delete |
|---|---|---|
| `seon.task/chain!`, `seon.task/chain-copy` | write `:seon.task/next` (A -> B) on each copy beside, or instead of, `:seon.task/needs` on B | the `before` needs wiring, if next replaces it |
| `seon.task/ready-rules` (`blocked`, `needs*`) | none if `next` also writes the need; or a `predecessor` rule: B is blocked while `[?a :seon.task/next B]` has no `leaf-done` | nothing |
| `seon.cluster.agent/plan` | a ready leaf with a predecessor reuses the predecessor assignment's `:seon.task/branch` and `:seon.flow.plan/fork-point` is nil; only the first step forks main | the main fork for later steps |
| `seon.cluster.agent/plan-facts` | read the predecessor's branch for the ready leaves that have one (one query keyed by the dirty leaves, in the delta form) | |
| `seon.cluster.agent/fork-attempt!` | none: it already forks only when `(registry/branches store)` lacks the branch and returns `base-commit-id` | the `:seon.flow.plan/fork-point` end-commit clause for relaunch if retry continues on the same branch (`seon.task/relaunched` and `seon.cluster.registry/branch-fork-entity`'s inherited-base) |
| `seon.cluster.agent/assignment-actions` | for an ended non-final step emit a facts-only sync instead of `:merge` and `:delete-branch`; delete the branch only after the final step's merge or a chain failure | per-step `:merge`, `:publish`, `:delete-branch` for intermediate steps |
| `seon.merge/merge-onto`, `seon.merge/merge-tx` | a forcing predicate "this assignment has a `:seon.task/next`" routes to the facts-only path and writes `:outcome :merged` | |
| `seon.cluster.agent/lifecycle-facts` | `head = merge base` as "nothing left" must be per-assignment (`:seon.task/merge-base` moved by each sync) | |
| `seon.cluster.agent/start-attempt!`, `acquire-context!` | the previous step's graph must be stopped and its branch connection released before B's `acquire-context!` (`store/open-branch!` refuses a second in-process connection); `assignment-actions` already stops A's graph first | |

In the delta form (section 3), step "A ends" is one datom `[attempt-A :seon.task.attempt/end :done]`: the wake
carries `A`, `lifecycle-facts` runs for A alone, the sync writes `:outcome :merged`, that report's datoms name A's task,
the dependents rule finds B, and `ready-among` plus `basis-valid?` run for B alone. The start path for B is then hops 7-9
with fork 0 (branch exists) and one `seed-attempt!`. No scheduler, timer or retry is added, and nothing derives from
anything but facts.

## 5. Findings to file (not mine to fix)

1. `seon.cluster.agent/basis-valid?` cold on every main commit (key includes `db/commit-id`); 1.55 ms and 1.7 MB per ready
   leaf (measured), 1.3 s and 1.4 GB at 834 leaves. Fix at the producer: key by the read evidence of `seon.task/obligations`.
2. `seon.task/relaunched` costs 0.36 ms and 0.35 MB per leaf in `plan-facts` (measured, 301 ms at 834 leaves); it is
   called for every ready leaf though only a leaf with an ended-and-relaunched attempt needs it.
3. `seon.cluster.agent/taskless-agents` is 94% of `lifecycle-facts` on an idle cluster (11.4 of 12.1 ms) and runs on
   every wake, three times when a stop and a merge follow; a branch-roster-sized read where the change was one datom.
4. `seon.cluster.wake/route!` recompiles `wake-matchers` per report (cheap, 0.01-0.12 ms warm, as its docstring says),
   but the same recompile runs on every branch connection's route for every agent transaction (inferred, count not
   measured).
5. Datahike evaluation gaps hit while probing (record, do not work around in production code): a recursive rule under
   `not`/`not-join` with a bound collection (`Insufficient bindings`); a two-clause rule whose first clause is
   `[(identity ?t) ?x]`, added with `conj` onto `ready-rules` (`self-or-ancestor`), returned `[]` where `self-or-under`
   works in the same query shape (not isolated further);
   both are why the sketch walks ancestors in Clojure.

## 6. Reproduction

All forms were single `eval_clj` JVM evaluations. Measurement helper: `(let [mx (java.lang.management.ManagementFactory/getThreadMXBean)]
(.getThreadAllocatedBytes mx tid))` before and after each call. Scale scenario:
`(datahike.api/with db (mapv (fn [[t]] [:db/add t :seon.task/execution :running]) roots))` with `roots` the parentless
tasks. Delta scenario: `r1 = (with db0 [[:db/add T :seon.task/execution :running] [:db/add N :seon.task/execution :running]])`,
`r2 = (with (:db-after r1) [{:seon.task/attempt-of N :seon.task/outcome :merged}])`. The live cluster was not written; the
two `eval_clj` replies for the scale probe reported `:slow` findings (3.4 s and 1.65 s) from the hypothetical scenario only.
