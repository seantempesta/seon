---
title: "Task and step lifecycle: commonality inventory"
type: research
status: static inventory of duplicated lifecycle logic with candidate shared functions; no change made, equivalences unproved until run on default
model: Claude Haiku 5.5
basis: "HEAD 2d38bf197 on refactor/agent-platform; grep and direct reads only, no REPL, no tests"
---

# Task and step lifecycle: commonality inventory (2026-10-13)

Research only. No source, test or schema was changed. Evidence is HEAD `2d38bf197` on `refactor/agent-platform`, read by `grep` and direct line reads on 2026-10-13. Line numbers are dated evidence and will drift. Every claim is tagged [V] (quoted or read in this session) or [I] (inferred from the quoted code, not run).

Files in scope: `src/seon/context.clj`, `src/seon/cluster/agent.clj`, `src/seon/cluster/source.clj`, `src/seon/task.clj`, `src/seon/turn.clj`, `src/seon/merge.clj`, `src/my/task.clj`, `src/my/root.clj`, `src/seon/test/runner.clj` (for `designed-red?`), plus `src/seon/test.clj` for the green predicates and `resources/seon/schemas/seon.task*.edn` for the enums.

Not done: no REPL probe, no test run, no measurement. The equivalence claims in section A are read-only and should be proved on default before any change.

## 0. Top 10 duplications, ranked by how many decisions they repeat

1. **The end policy is spread across five files.** Which end merges, which relaunches, which is terminal, and which counts as done. Sets re-list the enum by hand (section C). See A6.
2. **Merge-base resolution has nine readers.** Stored `:seon.task/merge-base`, else the branch's fork commit, computed with slightly different fallbacks. See A7.
3. **The proof set is computed by two identical queries.** `seon.task/obligations` and `seon.task/task-edges` run the same `:seon.task/tasks` / `:seon.test/sym` query. See A1.
4. **"Owed by step" is `(if (= :prove step) product proofs)` in five places.** See A2.
5. **The branch's attempt end is read by an identical query in two namespaces.** `my.root/with-branch-end` and `seon.cluster.agent/branch-end` are the same datalog. See A8.
6. **Two `designed-red?` definitions, one on derived head entries and one on raw results.** Plus three green predicates. See A3 and A4.
7. **"Done" means three things.** Step `:done`, leaf `leaf-done` (merged or satisfied), and `done?` (no open leaf below). See A5.
8. **The hand member list for a task drops `:seon.task/product`.** `create!`'s select-keys and `task-entry-node`'s order vector both omit it, while the schema declares it. See A17.
9. **The parent walk (`:seon.task/children` upward) is written inline four times.** See A11.
10. **"Open assignment" and "open leaf" are each written in two or three dialects.** See A9 and A12.

Next most repeated: handle-to-symbol pulls (A13, nine sites), agent-to-branch (A14, two identical strings in one file), latest attempt (A10).

## A. Duplicate or near-duplicate logic

### A1. Proof set of a task (two identical queries, plus a successor query)

- [V] `seon.task/obligations`, `src/seon/task.clj:515`:
  `proofs (db/q '[:find [?s ...] :in $ ?t :where [?e :seon.task/tasks ?t] [?e :seon.test/sym ?s]] database sym)`
  Returns `{:seon.task/step :seon.task/proofs :seon.task/product}`, lines 519-521.
- [V] `seon.task/task-edges`, `src/seon/task.clj:529-530`:
  `tagged (fn [attribute] (db/q [:find '[?s ...] :in '$ '?t :where ['?e :seon.task/tasks '?t] ['?e attribute '?s]] database sym))` and `proofs (tagged :seon.test/sym)`.
  Same datoms as line 515. Returns `{:seon.task/subjects :seon.task/proofs :seon.task/referrers}`, lines 540-542.
- [V] `seon.context/proofs`, `src/seon/context.clj:148-149`: `(let [edges (task/task-edges database sym)` `names (when-not (:seon.error/at edges) (vec (sort (:seon.task/proofs edges))))]`.
- [V] `seon.turn/task-proofs`, `src/seon/turn.clj:5215`: `:else (set (:seon.task/proofs (task/task-edges database sym)))`.
- [V] `seon.task/assign!`, `src/seon/task.clj:673`: `(obligations database (:seon.task/sym (db/pull ...)))`, and line 679 returns `(:seon.task/proofs edges)` taken from obligations.
- [V] `seon.task/assignment`, `src/seon/task.clj:830` uses `obligations`; line 836 reads `:seon.task/proofs` from it.
- Difference [V]: `obligations` also returns `:seon.task/step` and `:seon.task/product`. `task-edges` also returns subjects and referrers and is the only reader of the `:seon.task/references` referrer set. Same proof set, different projections.
- Product successor query [V] `seon.task/obligations`, `src/seon/task.clj:516-518`: `[?next :seon.task/needs ?task] [?next :seon.task/sym ?n] [?e :seon.task/tasks ?n] [?e :seon.test/sym ?s]`. The chain reader uses the same "successor" relation in `seon.context/chain`, `src/seon/context.clj:293`: `successors (db/q '[:find [?s ...] :in $ ?id :where [?t :seon.task/id ?id] [?n :seon.task/needs ?t] [?n :seon.task/sym ?s]] database id)`. Also [V] `seon.cluster.agent` plan-facts, `src/seon/cluster/agent.clj` (about line 1717): `(db/q '[:find ?next . :in $ ?s :where [?t :seon.task/sym ?s] [?next :seon.task/needs ?t]] database sym)`.

**Shared function should be:** one `proof-syms` (database, sym) to `#{test-sym}`. `obligations` and `task-edges` both call it; `assign!`, `assignment`, `task-proofs` and `context/proofs` read through `obligations` or `task-edges` as before. Successors become one `successors-of` (database, sym).

### A2. "Owed by step" selection

- [V] `seon.context/unmet`, `src/seon/context.clj:187-188`: `[owed-clause owed] (if (= :prove step) [:product product] [:proofs proofs])`.
- [V] `seon.context/step-status`, `src/seon/context.clj:216-218`: `:implement` uses `proofs`, `:prove` uses `product` plus `proofs`, in a `case`.
- [V] `seon.task/assign!`, `src/seon/task.clj:678`: `(empty? (get edges (if (= :prove (:seon.task/step edges)) :seon.task/product :seon.task/proofs)))`.
- [V] `seon.turn/step-tests`, `src/seon/turn.clj:5226-5231`: `(= :prove (:seon.task/step owed))` then `(into proofs (filter #(seq (db/datoms database :avet :seon.test/sym %))) (:seon.task/product owed))`.
- [V] `seon.merge/merge-onto`, `src/seon/merge.clj:297`: `(let [product (when (= :prove (:seon.task/step owed)) (:seon.task/product owed))`.
- [V] `seon.cluster.agent` plan-facts gate, about line 1713-1718: `(seq (:seon.task/proofs owed)) true` then `(= :prove (:seon.task/step owed))` with the successor lookup.

**Shared function should be:** `judged-tests` (obligations) to the set a step is judged by: proofs, plus product when the step is `:prove`. `step-tests` filters the product to tests the database holds (turn.clj:5230); that filter belongs in the same function. `context/unmet` keeps its clause split by calling the same selector per clause.

### A3. `designed-red?`: two predicates, two input shapes

- [V] `seon.context/designed-red?`, `src/seon/context.clj:158-163`:
  `(boolean (and (= :red (:seon.context/proof-status entry)) (seq (:seon.context/proof-failures entry))))`. Input: a head entry, which is a derived view.
- [V] `seon.test.runner/designed-red?`, `src/seon/test/runner.clj:2482-2488`:
  `(boolean (and (red-result? result) (seq (:seon.test.failure/reports result))))`. Input: a raw result.
- [V] `seon.cluster.source/accept-merge!`, `src/seon/cluster/source.clj:1119-1123`: `product-results ((requiring-resolve 'seon.test.runner/latest-results) tested (vec named))` then `designed (into #{} (comp (filter #(and (map? %) ((requiring-resolve 'seon.test.runner/designed-red?) %)))`. Calls the runner version through `requiring-resolve`, so the source namespace does not depend on the runner directly.
- [V] `seon.context/step-status` uses the context version at lines 218 and 191 (`designed-red?` on head entries).
- [I] Both return true for the same test at the same reach. The context version adds `:pending` for stale digests (`proof-status`, context.clj:117-118), which makes `designed-red?` false at a stale reach. The runner version has no reach check and relies on the caller passing a current result. Equivalence holds only when callers pass current results. Check on default before collapsing.

**Shared function should be:** one predicate in the runner, `designed-red?` on a result. Context's `proof-status` already reads the same counts; its head entry should carry the result's `red?` verdict computed by that one function, not recompute `proof-failures` non-empty.

### A4. Green and red predicates (three definitions of one count fact)

- [V] `seon.context/proof-status`, `src/seon/context.clj:119-120`: `(and (pos? (:seon.test/pass-count result)) (= 0 (:seon.test/fail-count result) (:seon.test/error-count result))) :green`.
- [V] `seon.test/green?`, `src/seon/test.clj:2525-2531`: `(and (pos? (:seon.test/pass-count result 0)) (zero? (:seon.test/fail-count result 0)) (zero? (:seon.test/error-count result 0)))`. Same rule, with defaults.
- [V] `seon.test.runner/red-result?`, `src/seon/test/runner.clj:2475-2480`: `(and (:seon.test/pass-count result) (pos? (+ (:seon.test/fail-count result) (:seon.test/error-count result))))`. Used in `source/accept-merge!` at source.clj:1127-1129.
- [V] `seon.test/green-members`, `src/seon/test.clj:797-806`: a datom query over `:seon.test.member/completed-tx`, `terminated-tx`, `began?`, `ended?`. A different fact (ran to completion), used by `accept-merge!` at source.clj:1113 (`green-ids`).
- [I] `red-result?` is not the negation of `green?`: a result with pass 0 and no failures is neither. Context calls that `:red` (context.clj:121). That is the intended "pending-ish" case; the lifecycle should name it.

**Shared function should be:** `counts-green?` and `counts-red?` in the test namespace, used by context, `green?` and `red-result?`.

### A5. "Done" and "merged" and "finished"

- [V] Step done: `seon.context/step-status`, `src/seon/context.clj:219`: `:seon.task/status (cond satisfied? :satisfied (empty? (unmet observation)) :done :else :working)`.
- [V] `seon.context/task-finished?`, `src/seon/context.clj:428`: `(contains? #{:done :satisfied} (:seon.task/status (step-verdict database agent-id)))`.
- [V] `seon.context/task-done?`, `src/seon/context.clj:435`: `(= :done (:seon.task/status (step-verdict database agent-id)))`.
- [V] `seon.context/current-state` status cond, `src/seon/context.clj:698`: `(cond (#{:done :satisfied} status) status`.
- [V] `seon.turn/task-end`, `src/seon/turn.clj:2161`: `(cond (#{:done :satisfied} status) status`.
- [V] Leaf done, the Datalog rule, `src/seon/task.clj:947-950`: `[(leaf-done ?t) [?a :seon.task/attempt-of ?t] [?a :seon.task/outcome :merged ?tx] (not-join ...)]` and `[(leaf-done ?t) [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end :satisfied ?tx] (not-join ...)]`. Both pass through the same `not-join` on `:sent-back` targeting.
- [V] Merged in `step-before`, `src/seon/context.clj:272-273`: `[?a :seon.task/attempt-of ?t] [?a :seon.task/outcome :merged ?tx]`. Same datoms as the first `leaf-done` clause.
- [V] Done (done?), `src/seon/task.clj:1024-1034`: `(and (int? task) (empty? open))` over leaves not `leaf-done`.
- [V] Merge accepted, `src/seon/cluster/agent.clj:1988-1990`: `merged-ends #{:done :sent-back}`. Different set from `leaf-done`'s `:satisfied` and from task-finished's `:satisfied`.
- [I] Three different questions share "done": the agent's step is complete (step-status `:done`), the leaf is complete for scheduling (leaf-done, including satisfied), and the change reached main (`:merged`). The code is correct but the words collide; a small set of named predicates (`step-complete?`, `leaf-settled?`, `merged?`) would make the mapping explicit.

**Shared function should be:** `task/end-kind` (section A6) and one `merged-attempts` query used by `leaf-done` and `step-before`.

### A6. End-to-action derivations (the lifecycle table is in five places)

- [V] `seon.context/ended`, `src/seon/context.clj:596-600` derives root's operations from the end: `(list 'my.root/merge! ...)` when the assignment is a string, and `(list 'seon.task/create! ...)` when a symbol exists.
- [V] `seon.cluster.agent/assignment-actions`, `src/seon/cluster/agent.clj:2094-2104`:
  `merge? (and (merged-ends end) (or (nil? main-end) (merged-ends main-end)))`, `(and end (nil? main-end) (not (merged-ends end))) (conj (action :close))`, and `(and (= :satisfied end) main-end) (conj (action :delete-branch))`.
- [V] `seon.cluster.agent/close!`, `src/seon/cluster/agent.clj:2265-2272`: `:cancelled` facts; `(= :satisfied end)` sets outcome `:closed`; `unmerged` skipped for `#{:cancelled :satisfied}` (line 2271); relaunch only for `#{:capped :stalled}` (line 2272).
- [V] `seon.cluster.agent/refused-merge-relaunch`, `src/seon/cluster/agent.clj:2187-2206` and `fixable-refusals` at line 2183-2185: a second, different relaunch rule for merge refusals.
- [V] `seon.cluster.agent/failed-pass!`, `src/seon/cluster/agent.clj:2208-2243`: writes `:seon.task.attempt/end :error` (line 2232) for `#{:attempt :start-flow :merge-base :merge}` ops (line 2225).
- [V] `seon.task/ready`, `src/seon/task.clj:1014-1018`: `(not-join [?t] ... [(contains? #{:given-back :capped :stalled :cancelled :error} ?e)] ...)`. This is a fourth copy of "ends that are not relaunchable".
- [V] `seon.turn/task-end`, `src/seon/turn.clj:2152-2166`: produces the end values (`:done`, `:satisfied`, `:sent-back`, `:given-back`, `:capped`, `:stalled`).
- [V] Schema enums: `resources/seon/schemas/seon.task.edn` line 78 `:end` enum: `:done :satisfied :given-back :capped :stalled :sent-back`. `resources/seon/schemas/seon.task.attempt.edn` line 3 `:end` enum: `:done :satisfied :given-back :capped :stalled :sent-back :cancelled :error`. The two enums differ (`:cancelled :error` only on the attempt). [V]

**Shared function should be:** one `task/end-kind` (function of end) returning a small map `{:merge? :satisfied? :relaunchable? :terminal?}`, read by `assignment-actions`, `close!`, `ready`, `task-end` and `step`-related checks. The enum set becomes the single source; the `#{...}` literals go away.

### A7. Merge base resolution (nine readers, three fallbacks)

Resolution is "stored `:seon.task/merge-base` on the assignment, else the commit the branch forked at (`registry/base-commit-id`), else a relaunched predecessor's".

- [V] `seon.merge/assignment-row`, `src/seon/merge.clj:216-217`: `(let [row (db/pull database [:db/id :seon.task/branch :seon.task/merge-base] [:seon.task/assignment key])` then `base (or (:seon.task/merge-base row) (some->> (:seon.task/branch row) (registry/base-commit-id database)))`.
- [V] `seon.merge/merge-tx` (fork arity), `src/seon/merge.clj:194`: `(let [base-commit (or (:v (first (d/datoms database :eavt a :seon.task/merge-base))) fork)]`. Stored, else the `fork` argument the caller passed.
- [V] `seon.task/attempt-tx`, `src/seon/task.clj:727`: `base (or (registry/base-commit-id database branch) (:seon.task/merge-base predecessor))`. Branch first, predecessor second. The reverse order from `assignment-row`.
- [V] `seon.task/relaunched`, `src/seon/task.clj:704-712`: latest attempt's `:seon.task/merge-base` when relaunched.
- [V] `seon.turn/fork-commit`, `src/seon/turn.clj:5429-5436`: `(registry/base-commit-id database (get-in database [:config :branch]))`. The retained base for the agent's own branch.
- [V] `seon.context/contract-gaps`, `src/seon/context.clj:347-349`: `held (db/q '[:find ?m . ... [?a :seon.task/merge-base ?m]])` then `commit (if (uuid? held) held (turn/fork-commit database))`. Same fallback as `attempt-tx` but with the fork commit second.
- [V] `seon.cluster.agent/fork-attempt!` returns `(registry/base-commit-id database branch)`, `src/seon/cluster/agent.clj:1871`. Same as `attempt-tx` branch read.
- [V] `seon.cluster.agent/write-merge-base!`, `src/seon/cluster/agent.clj:1891-1900`: writes `commit` only when `(:seon.task/merge-base row)` is absent (line 1899). Same "stored wins" rule as `assignment-row`.
- [V] `seon.cluster.agent` `:merge-base` action, `src/seon/cluster/agent.clj:2298-2299`: `(or (registry/base-commit-id (db/db connection) branch) (throw ...))`. Branch-only, no predecessor fallback.
- [V] `seon.cluster.source/merge-base`, `src/seon/cluster/source.clj:753-765`: `(if-let [commit (registry/base-commit-id branch candidate)] (d/commit-as-db branch commit) (refused ...))`. Same registry call, source level.

**Shared function should be:** `task/merge-base-of` (database, assignment-key-or-entity, branch) returning the stored value, else `registry/base-commit-id` of the branch, else nil. `attempt-tx` takes the predecessor's value only through `relaunched`, which is already a fact on main; the order should be defined once. Callers that today pass a fork commit (`merge-tx`'s `fork`) receive it from this function instead.

### A8. The attempt's end, read from its branch head (exact duplicate across namespaces)

- [V] `seon.my.root/with-branch-end`, `src/my/root.clj:49-50`:
  `(try (let [end (db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]` ... `head key)]`.
- [V] `seon.cluster.agent/branch-end`, `src/seon/cluster/agent.clj:1982`:
  `(try (let [end (db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]` ... `head key)]`.
- Both wrap `(source/release! [head])` and `(db/branch-database database branch)`. Same query, same release.
- [V] `seon.cluster.agent` `refused-merge-relaunch`, `src/seon/cluster/agent.clj:2200`: `(db/q '[:find ?c . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/commit ?c]] head key)`. Same shape with `commit`.
- [V] `seon.cluster.agent/close!`, `src/seon/cluster/agent.clj:2265-2268`: `(dissoc (db/q '[:find (pull ?a [:seon.task.attempt/end :seon.task.attempt/commit :seon.task.attempt/reason]) . :in $ ?k :where [?a :seon.task/assignment ?k]] head key) :db/id)`. A pull of the same row.
- [V] `seon.context/ended`, `src/seon/context.clj:580-585` reads the branch via `branch-db` and derives the outcome from `branch-end-summary`. Different (calls and proofs), same branch-head source.

**Shared function should be:** `task/branch-attempt` (branch-database, key, attribute list) returning the pulled attempt row, with `branch-end`, `root/with-branch-end` and `refused-merge-relaunch` calling it. The merged `branch-end` in agent.clj is already the most general; export it from `seon.task` and delete the copy.

### A9. "Open assignment" (merge request still open)

- [V] `seon.my.root/merge-requests`, `src/my/root.clj:64-66`: `:where [?a :seon.task/assignment] [?a :seon.task/branch] (not [?a :seon.task/outcome])`.
- [V] `seon.my.root/open-assignment`, `src/my/root.clj:77-80`: `(if (and (:seon.task/branch row) (not (:seon.task/outcome row))) row ...)`.
- [V] `seon.cluster.agent` assignment rows, `src/seon/cluster/agent.clj:2048-2051` (the `held` query): `[?a :seon.task/assignment ?k] [?a :seon.task/assigned ?h] [?a :seon.task/branch ?b]`, with no outcome test. It is the "held" set, not the open set, so it is a different question.
- [V] `seon.merge/merge!` checks the same thing through `open-assignment` (`src/my/root.clj:85-103` calls `(open-assignment database key 'my.root/merge!)`).

**Shared function should be:** `task/open-assignment?` on a pulled row, used by `merge-requests` (as a pull predicate) and `open-assignment`.

### A10. Latest attempt and relaunch count

- [V] `seon.task/next-attempt`, `src/seon/task.clj:688-700`: `made` from `attempts-made`, `latest` pulled by `(id/id [handle made])`, then `number` is `(inc (or made 0))` unless the latest is unended.
- [V] `seon.task/relaunched`, `src/seon/task.clj:704-712`: `(db/q '[:find (max ?n) . :in $ ?h ...])` then a pull of the max attempt's `:seon.task.attempt/relaunch`.
- [V] `seon.cluster.agent/relaunch-reason`, `src/seon/cluster/agent.clj:2164`: `(db/q '[:find (count ?b) . :in $ ?h :where [?b :seon.task/assigned ?h] [?b :seon.task.attempt/relaunch _]] database h)`.

**Shared function should be:** `task/latest-attempt` (database, handle) returning `{:number n :row pulled}`. `next-attempt` and `relaunched` read it; `relaunch-reason` counts from the same attempt set.

### A11. Parent walk (`:seon.task/children` upward)

- [V] `seon.task/effective`, `src/seon/task.clj:981`: `(let [parent (db/q '[:find ?p . :in $ ?c :where [?p :seon.task/children ?c]] database eid)`.
- [V] `seon.task/goal-owner`, `src/seon/task.clj:997`: `parent (when (:db/id task) (db/q '[:find ?p . :in $ ?c :where [?p :seon.task/children ?c]] database (:db/id task)))]`. Byte-identical query.
- [V] `seon.context/chain` subject, `src/seon/context.clj:289`: `(db/q '[:find ?s . :in $ ?id :where [?t :seon.task/id ?id] [?p :seon.task/children ?t] [?p :seon.task/sym ?s]] database id)`. Same walk plus the symbol pull.
- [V] Datalog rules, `src/seon/task.clj:946` (`execution`) and `:951` (`under`): the same relation as a recursive rule.

**Shared function should be:** `task/parent-of` (database, eid) and `task/root-of`. `effective`, `goal-owner` and `chain` call it; the recursive rules stay as they are (they are the Datalog form of the same relation).

### A12. Open leaves (readiness and done)

- [V] `seon.task/ready`, `src/seon/task.clj:1014-1015`: `(db/q '[:find ?t ?h :in $ % :where (execution ?t ?x) [(= ?x :running)] [?t :seon.task/id ?h] (not [?t :seon.task/children _]) (not (leaf-done ?t))` ...
- [V] `seon.task/done?`, `src/seon/task.clj:1033`: `(db/q '[:find [?l ...] :in $ % [?l ...] :where (not [?l :seon.task/children _]) (not (leaf-done ?l))] ...)`.
- [V] Both express "a leaf that is not `leaf-done`". `ready` adds the `:running` execution filter; `done?` adds the subtree (line 1031).
- [V] `seon.task/resolution`, `src/seon/task.clj:1051-1054`: calls `done?` for a parent. Good reuse.

**Shared function should be:** `task/open-leaves` (database, subtree handles) returning the leaves not leaf-done, used by `done?` and by `ready`'s blocked/eligible split.

### A13. Handle to symbol (nine sites, one shape)

- [V] `seon.turn/task-proofs`, `src/seon/turn.clj:5208-5212`: `(let [handle (task/agent-task database agent-id) sym (when (string? handle) (:seon.task/sym (db/pull database [:seon.task/sym] [:seon.task/id handle])))]`.
- [V] `seon.turn/step-tests`, `src/seon/turn.clj:5222-5224`: same expression, and `task-proofs` is called again at line 5223.
- [V] `seon.turn` `record-broken!`, `src/seon/turn.clj:5440-5446` (about): same expression.
- [V] `seon.context/ended`, `src/seon/context.clj:578`: `sym (db/q '[:find ?s . :in $ ?id :where [?t :seon.task/id ?id] [?t :seon.task/sym ?s]] database task-id)`.
- [V] `seon.context/end-summary-tx`, `src/seon/context.clj:562`: same with `(when (string? handle) ...)`.
- [V] `seon.merge/assigned-task`, `src/seon/merge.clj:202-206`: assignment to sym: `[?a :seon.task/assigned ?h] [?t :seon.task/id ?h] [?t :seon.task/sym ?sym]`.
- [V] `seon.cluster.agent` plan-facts `syms`, `src/seon/cluster/agent.clj:1733`: `(db/q '[:find ?h ?s :in $ [?h ...] :where [?t :seon.task/id ?h] [?t :seon.task/sym ?s]] ...)` (batch form).
- [V] `seon.task/assign!` `src/seon/task.clj:673`: `(:seon.task/sym (db/pull database [:seon.task/sym] [:seon.task/id handle]))`.

**Shared function should be:** `task/sym-of` (database, handle) and a batch form, `task/syms-of`. The merge `assigned-task` is handle-by-assignment; it composes `task/assigned-handle` plus `sym-of`.

### A14. Agent to branch

- [V] `seon.context/end-summary-tx`, `src/seon/context.clj:560`: `(let [branch (db/q '[:find ?b . :in $ ?id :where [?a :seon.agent/id ?id] [?a :seon.agent/branch ?b]] database agent-id)`.
- [V] `seon.context/ended`, `src/seon/context.clj:577`: the same string, byte for byte.
- [V] `seon.cluster.agent` `works-on`, `src/seon/cluster/agent.clj:169-170`: `(db/q '[:find ?branch . :in $ ?id :where [?agent :seon.agent/id ?id] [?agent :seon.agent/branch ?branch]] ...)`. Same datoms, variable renamed.
- [V] Root's branch, `src/seon/context.clj:457`: `[?root :seon.agent/id "root"] [?root :seon.agent/branch ?branch]`. Same relation with a constant.

**Shared function should be:** `task/agent-branch` (database, agent-id).

### A15. Already shared (build on these, do not duplicate)

- [V] `seon.context/held-task`, `src/seon/context.clj:224-233`: the effective task an agent holds. Used by `step-verdict` (line 394), `task-sections` (line 640).
- [V] `seon.task/agent-task`, used by `held-task`, `step-tests`, `task-proofs`, `record-broken!`, `refine!`, `end-summary-tx`.
- [V] `seon.task/effective`, `src/seon/task.clj:967-987`, the merged goal; `seon.task/goal-owner`, `src/seon/task.clj:989-1004`.
- [V] `seon.context/step-observation` (line 357-387) is the one place a step is observed; `step-verdict` (389-399) is the one place it is judged. `turn/record-broken!` calls `context-step-observation` and `context-step-status` (turn.clj 5443-5468), so the judge is shared.

### A16. "What changed since the merge base" (two change sets)

- [V] `seon.context/contract-gaps`, `src/seon/context.clj:350-355`: `(program/changed-identities (db/basis-t base) database source/merge-bound)` then `(source/contract-gaps database changed)`.
- [V] `seon.cluster.source/unmerged-count`, `src/seon/cluster/source.clj:875-887`: `(compare-branch store branch database (get-in database [:config :branch]))` then sums `three` sets.
- [I] Both compute "declarations the branch changed relative to a base". One uses `program/changed-identities` against the merge base commit, the other `compare-branch` of branch against main head. They may be the same set in the steady state and differ when main moved. Not proved here.

**Shared function should be:** `source/changed-since-base` (branch database, base commit) returning the identities; `unmerged-count` and `contract-gaps` both consume it. Prove equivalence on default first.

### A17. Hand member lists of task keys (the product drop)

- [V] Schema, `resources/seon/schemas/seon.task.edn:18-19` `:entry`: `[:seon.task/name ...] [:seon.task/step ...] [:seon.task/intent ...] [:seon.task/instructions ...] [:seon.task/children ...] [:seon.task/needs ...] [:seon.task/product {:optional true} :seon.task/product]`. Closed map.
- [V] Schema, `resources/seon/schemas/seon.task.edn:22-25` `:declaration`: the same members plus `:seon.task/ns`. Product present.
- [V] `seon.task/create!` select-keys, `src/seon/task.clj:654-655`: `(select-keys request [:seon.task/ns :seon.task/name :seon.task/step :seon.task/intent :seon.task/instructions :seon.task/children :seon.task/needs])`. Product absent.
- [V] `seon.task/create!` malli schema, `src/seon/task.clj:643-647`: the same seven keys, product absent.
- [V] `seon.cluster.source/task-entry-node`, `src/seon/cluster/source.clj:1596-1598`: the `order` vector `[:seon.task/name :seon.task/step :seon.task/intent :seon.task/instructions :seon.task/children :seon.task/needs]` (product absent). `value` only keeps keys in `order` (line 1599-1600), so product is dropped when a task file entry is rewritten.
- [V] `seon.task/tree-attributes`, `src/seon/task.clj:147-148`: `[:seon.task/children :seon.task/needs]`, a third hand list of the tree keys.
- [V] `seon.task/create!` consumes `:seon.task/product` nowhere; the `obligations` read (task.clj:514) uses it from the database. So a product declared through `create!` never reaches the entity.

**Shared function should be:** derive the member set from the `:seon.task/declaration` schema (one `task/member-keys`) and use it in `create!`'s select-keys, `task-entry-node`'s order and `tree-attributes`. Product then cannot be dropped by a copy. This matches the narrow-check finding recorded in the lifecycle hybrid (commit 2b2d71818), and the source here is [V].

## B. Datalog queries with the same shape

Table: shape, sites. [V] for each quoted site above; the counts are read from `rg` output.

| Shape | Sites |
|---|---|
| `[?t :seon.task/id ?h] [?t :seon.task/sym ?s]` (handle to sym) | turn.clj:5208-5212, 5222-5224; context.clj:562, 578; agent.clj:1733; merge.clj:202-206; task.clj:673 (pull form) |
| `[?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]` (end of assignment) | root.clj:49-50; agent.clj:1982 (exact) |
| `[?a :seon.task/assignment ?k] [?a :seon.task.attempt/commit ?c]` | agent.clj:2200 |
| `[?p :seon.task/children ?c]` (parent) | task.clj:981, 997 (exact), 223 (all children), 1031 (under); context.clj:289 |
| `[?a :seon.task/attempt-of ?t] [?a :seon.task/outcome :merged ?tx]` | context.clj:272-273; task.clj:947-948 |
| `[?e :seon.task/tasks ?t] [?e :seon.test/sym ?s]` (proofs) | task.clj:515, 529-530 (exact) |
| `[?n :seon.task/needs ?t]` (successors) | task.clj:517; context.clj:293; agent.clj:1717-1719 |
| `[?agent :seon.agent/id ?id] [?a :seon.agent/branch ?b]` (agent branch) | context.clj:560, 577 (exact); agent.clj:169-170 |
| `[?a :seon.task/agent ?g] [?g :seon.agent/id ?id]` (assignment's agent) | agent.clj:1735-1738, 2048-2051; merge.clj:227-230; context.clj:584 |
| `[?b :seon.task/assigned ?h] [?b :seon.task.attempt/relaunch _]` | agent.clj:2164 (count); task.clj:704-712 (max) |
| `[?turn :seon.turn/agent ?agent] [?turn :seon.turn/id _ ?t] [?turn :seon.turn/closed-tx _]` (latest turn) | turn.clj:2081-2086, 2118-2121 (pattern repeated with `(max ?t)`) |

[V] The exact-string duplicates are context.clj:560 and 577, agent.clj:1982 and root.clj:49, task.clj:981 and 997, and task.clj:515 and 529-530. Those are the first candidates for a single function.

## B2. Attribute-list pulls

- [V] `context.clj:590-591` landing pull: `[:seon.task/landed :seon.task/landed-git :seon.task/refusal :seon.task/refused-tests]`.
- [V] `agent.clj:2058-2059` next-actions pull: `[:seon.task.attempt/end :seon.task/merge-base :seon.task/outcome :seon.task/merge-commit :seon.task/merged-declarations :seon.task/git-commit :seon.task/refusal]`.
- [I] Overlap on `:seon.task/refusal`. `:seon.task/landed-git` and `:seon.task/git-commit` both name the merge's git commit; one is probably a retired name (not proved).
- [V] `root.clj:64-65` merge-requests pull: `[:seon.task/assignment :seon.task/assigned :seon.task/branch :seon.task/merge-base :seon.task.attempt/end]`.
- [V] `merge.clj:216` assignment-row pull: `[:db/id :seon.task/branch :seon.task/merge-base]`; `agent.clj:1898` pull: `[:db/id :seon.task/merge-base]`.
- [V] `task.clj:749` attempt-rows-tx pull: `[:db/id :seon.task/assignment :seon.task/assigned :seon.task/revision :seon.task/branch :seon.task.attempt/number :seon.task/merge-base {:seon.task/agent [:seon.agent/id]}]`.
- [V] `context.clj:541-544` recorded end pull: `[:seon.agent/end-calls :seon.agent/end-prompt-tokens :seon.agent/end-completion-tokens :seon.agent/end-red-proofs]` plus `(vals proof-count-attributes)`. A hand list beside the map at line 524.
- [V] `task.clj:514`: `[:seon.task/step :seon.task/product]`; `context.clj:271`: `[:seon.task/step]`; `context.clj:298`: `[:seon.task/sym :seon.task/step :seon.task/instructions]`.

## C. Hand-written keyword sets that duplicate a schema

| Set | Site | Relation to the schema |
|---|---|---|
| `#{:done :satisfied}` | context.clj:428, 698; turn.clj:2161 | subset of task.edn `:end` (line 78) |
| `#{:done :sent-back}` (`merged-ends`) | agent.clj:1988-1990 | subset of `:end` |
| `#{:cancelled :satisfied}` | agent.clj:2271 | `:cancelled` only on attempt.edn `:end` |
| `#{:capped :stalled}` | agent.clj:2272 | subset of `:end` |
| `#{:given-back :capped :stalled :cancelled :error}` | task.clj:1017 | `:error` and `:cancelled` only on attempt.edn `:end` |
| `#{:attempt :start-flow :merge-base :merge}` | agent.clj:2225 | db<->flow op names, no schema found in this session [I] |
| `#{:held :paused}`, `#{:running :paused :held}` | agent.clj:1675, 1680 | subset of task.edn `:execution` enum (line 49) [I] |
| `#{:seon.source/uncontracted-error :seon.source/lint-error [...]}` | agent.clj:2185 (`fixable-refusals`) | error-type set, no schema found here [I] |
| `[:seon.task/children :seon.task/needs]` | task.clj:147-148 (`tree-attributes`) | schema members |
| 7-key `select-keys` | task.clj:654-655 | schema `:declaration`, missing product (A17) |
| 6-key `order` vector | source.clj:1596-1598 | schema `:entry`, missing product (A17) |
| `failure-keys` map | context.clj:94-99 | rename map from runner result keys to context keys |
| `merged-families` map | context.clj:245-248 | attribute to family key; identities in `source` [I] |
| `proof-count-attributes` map | context.clj:522-524 | agent end attributes, duplicated by the pull at 541-544 |

[V] The end enum is declared twice with different members: `seon.task.edn:78` (6 values) and `seon.task.attempt.edn` (8 values). The task-level `:seon.task/end` does not list `:cancelled` or `:error`, although `ready` and `close!` handle them. Whether the task-level enum is meant to omit them is not decided in code [I].

## D. Candidate shared vocabulary (small pure functions over data)

Each entry: name (inputs to outputs), closest existing function, callers it would replace. Inputs are schema names already in use (`:seon.db/database-value`, `:seon.task/handle`, `:seon.task/sym`, `:seon.agent/id`, `:seon.task/assignment`, `:seon.task/end`, `:seon.test/sym`, `:seon.test/result`). The new names are proposals, not decisions.

1. `task/sym-of` : (db, handle) to `:seon.task/sym` or nil. Closest: none general (inline everywhere). Replaces A13 (about nine sites).
2. `task/agent-branch` : (db, agent-id) to `:seon.task/branch` or nil. Closest: none (inline). Replaces context.clj:560, 577; agent.clj:169.
3. `task/proof-syms` : (db, sym) to `#{:seon.test/sym}`. Closest: `seon.task/obligations` proofs, line 515 (exact same query as `task-edges` 529-530). Replaces A1 duplicate; callers of both readers keep their projections.
4. `task/judged-tests` : (db, sym) to the set a step is judged by, proofs plus product for `:prove`, filtered to tests the db holds. Closest: `seon.turn/step-tests` (turn.clj:5217-5231). Replaces `(if prove product proofs)` at context.clj:187, 216-218; task.clj:678; merge.clj:297.
5. `task/successors-of` : (db, sym) to successor syms. Closest: `seon.context/chain` successors (context.clj:293). Replaces task.clj:517; agent.clj:1717-1719.
6. `task/parent-of` : (db, eid) to parent eid or nil. Closest: inline at task.clj:981, 997. Replaces context.clj:289 subject walk too.
7. `task/merge-base-of` : (db, assignment-key or entity, branch) to commit or nil. Closest: `seon.merge/assignment-row` (merge.clj:216-217). Replaces A7: merge.clj:194; task.clj:727; context.clj:347-349; agent.clj:1871, 1898-1899, 2298-2299; source.clj:753-765 (source-level).
8. `task/branch-attempt` : (branch-db, key, attributes) to the pulled attempt row. Closest: `seon.cluster.agent/branch-end` (agent.clj:1978-1989). Replaces root.clj:49-50; agent.clj:2200, 2265-2268.
9. `task/end-kind` : (end) to `{:merge? :satisfied? :relaunchable? :terminal?}`, derived from the enum. Closest: `merged-ends` (agent.clj:1988-1990). Replaces the literal sets in C (agent.clj:2094, 2271-2272; task.clj:1017; context.clj:428, 698; turn.clj:2161).
10. `task/open-assignment?` : (row) to bool (branch present, no outcome). Closest: `seon.my.root/open-assignment` (root.clj:77-80). Replaces root.clj:64-66 (as a predicate); agent.clj:2048-2051 held rows (not the same question, keep separate).
11. `task/latest-attempt` : (db, handle) to `{:number :row}`. Closest: `seon.task/next-attempt` (task.clj:688-700) and `relaunched` (704-712). Replaces agent.clj:2164 count and task.clj:704-712.
12. `task/open-leaves` : (db, subtree handles) to the leaves not `leaf-done`. Closest: `seon.task/done?` (task.clj:1031-1034). Replaces task.clj:1015 and 1033 (readiness and done).
13. `task/member-keys` : () to the declaration key set, derived from `:seon.task/declaration` (`resources/seon/schemas/seon.task.edn`). Closest: none (three hand lists). Replaces task.clj:654-655, 147-148; source.clj:1596-1598. Makes product impossible to drop silently.
14. `test/failing?` : (result) to bool (`red-result?` and reports non-empty). Closest: `seon.test.runner/designed-red?` (runner.clj:2482-2488). Replaces context.clj:158-163 (`designed-red?` on head entries) and the `red-result?` use at source.clj:1127.
15. `test/counts-green?` : (result) to bool. Closest: `seon.test/green?` (test.clj:2525-2531). Replaces context.clj:119-120 in `proof-status`.
16. `source/changed-since-base` : (branch database, base commit) to changed identities. Closest: `seon.context/contract-gaps` (context.clj:350-353) and `source/unmerged-count` (source.clj:875-887). Needs equivalence proof first (A16).

Order of work [I]: items 13 and 3 first (they fix a dropped member and a duplicate query, both small); then 9 (the end policy, which has the largest blast radius); then 7 and 8 (merge base and branch attempt); then the rest.

## E. Verification limits

- Static reads only. The equivalences in A3, A4 and A16 are [I] until run on default.
- The `:seon.task/end` enum disagreement in C is a question for the owner, not a bug proved here.
- Line numbers are from HEAD `2d38bf197`; the working tree had uncommitted changes outside these files (`tmp/test-data-assertions/proofs.edn`).
