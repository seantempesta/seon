# Prove stage done-proof and universal done rule: REPL table (2026-10-14)

Experiment lane. No source, test or spec file edited; nothing committed. Run on default through `eval_clj` as agent
`probe-prove-a` (the tool cannot name a branch, so the agent id became `probe-prove-<x>` and the minted branch was
`:agent-f3e01dace896`; retired with `(my.agent/retire-branch! {:seon.store/branch :agent-f3e01dace896})`, and a
`my.agent/branches` filter shows it gone). The two `ns-unmap!`/retire calls and a `meta` read ran as agent `root`
and recorded evaluations on main (no data transacted by me beyond those records). Tasks are plain maps:
`{:seon.task/plan "..." :seon.task/next {:seon.task/proofs #{...}}}`. Items marked (inferred) were not run.

## Draft code (SCI, namespace `probe.prove`, uncontracted; each defn returned an `:uncontracted` finding)

Helpers `fork-database`, `digests`, `changed`, `sections` are the design table's, unchanged. `judged-fns` excludes the
probe's own namespace (an experiment artifact: the helpers are themselves new functions on the branch).

```clojure
(ns probe.prove
  (:require [clojure.set] [clojure.string :as str] [clojure.test :refer [deftest is]]
            [my.test] [seon.cluster.registry] [seon.cluster.source] [seon.context] [seon.contracts]
            [seon.db] [seon.fn] [seon.profile] [seon.ref] [seon.task] [seon.test] [seon.test.runner]))

(defn fork-database [database]
  (let [branch (get-in database [:config :branch])]
    (seon.db/commit-database database (seon.cluster.registry/base-commit-id database branch))))
(defn digests [database attribute]
  (into {} (seon.db/q [:find '?k '?d :where ['?e attribute '?k] ['?e :seon.program/definition-digest '?d]] database)))
(defn changed [database attribute]
  (let [before (digests (fork-database database) attribute)]
    (into #{} (keep (fn [[k d]] (when (not= d (get before k)) k))) (digests database attribute))))
(defn judged-fns [database]            ; new or changed fns, minus the probe's own helpers
  (into #{} (remove (fn [s] (= "probe.prove" (namespace s)))) (changed database :seon.fn/sym)))
(defn results [database tests] (seon.context/current-results database (set tests)))   ; current reach only
(defn gaps-of [database fns]
  (into {} (keep (fn [f] (let [g (seon.contracts/gaps database f)] (when (seq g) [f g])))) fns))
(defn main-database [database] (or (seon.task/main-head database) database))
(defn deftest? [database sym] (boolean (seq (seon.db/datoms database :avet :seon.test/sym sym))))

(defn prove-verdict [database task]
  (let [proofs (set (:seon.task/proofs (:seon.task/next task)))
        rs (when (seq proofs) (results database (filter (fn [p] (deftest? database p)) proofs)))
        by-sym (into {} (map (juxt :seon.test/sym identity)) rs)
        fns (judged-fns database)
        reach (fn [f] (some proofs (seon.fn/tests-reaching database f)))
        red? (fn [p] (boolean (let [r (get by-sym p)] (and r (seon.test.runner/designed-red? r)))))
        checks [[:no-proofs (boolean (seq proofs))]
                [:not-a-deftest (every? (fn [p] (deftest? database p)) proofs)]
                [:not-designed-red (every? red? proofs)]
                [:unreached (every? reach fns)]]
        failed (into [] (keep (fn [[k ok]] (when-not ok k))) checks)]
    {:green? (empty? failed) :failed failed}))            ; all failures, in order

(defn done-rule [database task]
  (let [main (main-database database)
        pv (prove-verdict database task)                  ; stands in for prove-handoff's own proof
        proofs (set (:seon.task/proofs (:seon.task/next task)))
        fns (judged-fns database)
        tests (into #{} cat [proofs (changed database :seon.test/sym)
                             (mapcat (fn [f] (seon.fn/tests-reaching database f)) fns)])
        red-here (into #{} (comp (filter seon.test.runner/red-result?) (map :seon.test/sym)) (results database tests))
        green-main (into #{} (comp (remove seon.test.runner/red-result?) (map :seon.test/sym)) (results main red-here))
        regress (clojure.set/intersection red-here green-main)
        gaps (gaps-of database fns)
        checks [[:proofs-green (:green? pv)] [:main-green-now-red (empty? regress)] [:contract-gaps (empty? gaps)]]
        failed (into [] (keep (fn [[k ok]] (when-not ok k))) checks)]
    {:done? (empty? failed) :failed failed :prove (:failed pv) :regress regress :gaps gaps}))

;; proposed additions (rows 10 and the bug-fix reach hole); see "Surprises" 4 and 5
(defn cited-fns [database plan]        ; existing functions the plan's Approach names
  (into #{} (filter (fn [s] (and (symbol? s) (seq (seon.db/datoms database :avet :seon.fn/sym s)))))
        (seon.ref/names (get (sections (or plan "")) "approach" ""))))
(defn stray-red [database proofs]      ; red tests the branch defines that are not the next task's proofs
  (let [others (remove (set proofs) (changed database :seon.test/sym))]
    (into #{} (comp (filter seon.test.runner/red-result?) (map :seon.test/sym)) (results database others))))
(defn prove-verdict+ [database task]
  (let [base (prove-verdict database task)
        proofs (set (:seon.task/proofs (:seon.task/next task)))
        cited-unreached (into #{} (remove (fn [f] (some proofs (seon.fn/tests-reaching database f))))
                              (cited-fns database (:seon.task/plan task)))
        stray (stray-red database proofs)
        extra (cond-> [] (seq cited-unreached) (conj :cited-unreached) (seq stray) (conj :stray-red))]
    {:green? (and (:green? base) (empty? extra)) :failed (into (:failed base) extra)
     :cited-unreached cited-unreached :stray stray}))
```

Deftests (namespace `probe.prove-test`, written one per request, as an agent would):

```clojure
(deftest s3-unresolved-alias (is (= 1 (nope/foo 1))))
(deftest s4-already-green (is (= 1 (+ 0 1))))
(deftest s5-pathless-section-is-a-missing-file          ; BUG FIX, red by failing assertion
  (are [reference] (contains? (try (seon.ref/section reference) (catch Throwable t (ex-data t))) :seon.ref/section-missing)
    "#the-goal" "#what-exists"))
(deftest s6-pathless-section-throws                     ; the bug itself raises
  (is (contains? (seon.ref/section "#the-goal") :seon.ref/section-missing)))
(deftest s8-log-session-returns-the-session             ; NEW FEATURE, red by the stub's throw
  (are [session] (= session (probe.workout/log-session session))
    {:probe.workout/id 1} {:probe.workout/id 2}))
(deftest s9-uses-scratch-helper (is (= 2 (probe.scratch/helper 1))))   ; reaches an uncontracted helper
(deftest s11-defined-not-run (is (= 1 2)))              ; red, never listed (also s12-auto-probe, same body)
```

Feature scaffolding, one request each (a new function is invisible to the database inside the request that defines
it, see design table surprise 1): `(seon.schema/register! :probe.workout/id :int)`, `(seon.schema/register!
:probe.workout/session [:map [:probe.workout/id :probe.workout/id]])`, then
`(ns probe.workout) (defn log-session {:malli/schema [:=> [:cat :probe.workout/session] :probe.workout/session]} [session] (throw (ex-info "not yet" {:seon.error/type :seon.error/unimplemented})))`.
Row 7: `(ns probe.stub) (defn orphan {:malli/schema [:=> [:cat :seon.ref/path] :seon.ref/path]} [path] (throw ...))`, later
removed with `(my.program/ns-unmap! 'probe.stub/orphan)` (468 ms). Row 9: `(ns probe.scratch) (defn helper [x] x)`.

## How results were produced (answers "which entrance records results")

Two entrances, both record results on the agent's branch connection at the test's current reach digest:

1. **The eval request's automatic test step** (`seon.turn/test-step`, reply key `:seon.turn.loop/tests`). It DOES run a
   brand-new deftest in the request after the one that defined it, with no proof tag and no reach: s12 (a new red
   `(is (= 1 2))`) came back `{:seon.test/passed? false, :seon.turn.loop/red [s12]}` and the reply's `:tests` cost row
   showed `{:count 1}`; a throwing test is listed under `:errored`. So the spec's "every test defined or changed on
   the branch" is already true for newly defined deftests. Cost: `:test-step` 190-750 ms per request that defines
   1-2 tests (dominated by building the test world, not the test: the test body itself was 12-50 ms).
2. `(my.test/check {:seon.test/identities #{...} :seon.test/rerun? true})` (identities is a SET; a vector is refused
   by the contract): 372 ms for two tests; the explicit way to force a run, and the only way to get past a stale
   recorded red (Surprise 1).

## Table (actual)

Times: `prove-verdict` / `done-rule` in ms, first call (cold-ish) then warm, with warm allocation. `done-rule` calls
`prove-verdict` inside, so its figure includes it. `seon.profile/measure`, thread allocation.

| # | state | expected | actual `prove-verdict` `:failed` | actual `done-rule` `:failed` | match | prove ms (alloc) | done ms (alloc) |
|---|---|---|---|---|---|---|---|
| 1 | next proofs `#{}` | red :no-proofs | [:no-proofs] | [:proofs-green] | yes | 11.6 / 11.6 (15 MB) | 20.8 / ~26 (27 MB) |
| 2 | proofs `#{probe.prove-test/nope seon.ref/section}` | red :not-a-deftest | [:not-a-deftest :not-designed-red] | [:proofs-green] | yes (plus the knock-on) | 11.9 / 11.9 (15 MB) | 25.9 / 33.9 (33-45 MB) |
| 3 | deftest with unresolved alias | red :not-designed-red | [:not-a-deftest :not-designed-red] | [:proofs-green] | differs: label is :not-a-deftest | 18.4 / 13.6 (15 MB) | 28.6 / 40.5 (51 MB) |
| 4 | already-green proof | red :not-designed-red | [:not-designed-red] | [:proofs-green] | yes | 15.4 / 15.0 (16 MB) | 41.9 / 41.7 (53 MB) |
| 5 | bug fix: table deftest, red by failing assertion (`:f 2`) | green | [] | [] (done? true) | yes | 13.7 / 19.4 (26 MB) | 50.9 / 40.4 (53 MB) |
| 6 | bug itself throws (`:e 1`) | green | [] | [] | yes | 13.6 / 14.0 (16 MB) | 40.5 / 39.9 (53 MB) |
| 7 | contracted stub `probe.stub/orphan` no proof reaches (proof s5) | red :unreached | [:unreached] | [:proofs-green] (gaps `{}`) | yes | 71.4 / 18.2 (17 MB) | 37.5 / 45.9 (54 MB) |
| 8 | feature: registered schemas, contracted stub, deftest red by the stub's throw (`:e 2`) | green | [] | [] | yes | 19.5 / 15.3 (17 MB) | 43.1 / 41.5 (55 MB) |
| 9 | uncontracted `probe.scratch/helper`, reached by proof s9 (proofs s8+s9) | prove green, done red :contract-gaps | [] | [:contract-gaps], gaps `{probe.scratch/helper [missing-spec]}` | yes | 10.6 / 15.4 (18 MB) | 34.0 / 44.2 (58 MB) |
| 10 | red deftests s11, s12 (+ s5, s6) on the branch, only s8 listed | note | [] (green) | [] (done? true) | see below | 37.6 / 14.1 (17 MB) | 33.6 / 41.5 (55 MB) |

State 10, with the final merge simulated: after `log-session` was implemented (s8 green), `seon.cluster.source/
results-verdict` over the branch and main, `judged` built by hand:
- members = proofs only: `[]`, i.e. ACCEPTED, with five red tests the branch defines (s5, s6, s11, s12 and the
  probe's s15) sitting unseen;
- members = proofs plus `(changed database :seon.test/sym)`: `[:seon.source/red-test-error]`.
So the spec's "no red test the branch defines" does not hold today: `results-verdict` judges only the run's members.
Adding the stray-red check to Prove (extended verdict below) catches it at the stage that caused it.

Extended `prove-verdict+` (cited-existing-function reach, stray red), on the polluted branch (so :unreached and
:stray-red also fire for the scratch residue): `cited-fns` of the bug plan = `#{seon.ref/section}`; proofs s5 or s6:
`:cited-unreached #{}` (reached); proof s12 (red, reaches nothing): `:cited-unreached #{seon.ref/section}`;
s8 with the stray reds present: `[:not-designed-red :stray-red]` (s8 had been implemented and was green by then).
Cost 29-43 ms warm, 35-52 MB.

## Cost summary

`prove-verdict` 11-19 ms warm, 15-26 MB (design's verdict was 2 ms because it skipped `changed :seon.fn/sym`, 7 ms,
and the fork database; Prove cannot skip them). `done-rule` 34-51 ms, 51-58 MB: it adds `changed :seon.test/sym`,
the tests reaching every changed function, `current-results` on both databases, and `gaps`. Both are far under the
5 s test bound; relative to a request they are noise next to the 190-750 ms the test step spends building the
test world. Where the time goes (inferred from the profile call counts, not isolated): `changed` builds the fork
database and reads every digest twice; the 70 ms first-call outlier in row 7 was a cold `tests-reaching`.

## Surprises

1. **A world-refusal is recorded as a result, and `designed-red?` accepts it; the reuse then pins it.** Four times
   the test step could not build the test world ("The test world's program overlay (48..112 entries) was refused:
   The stored definition digest of [:seon.fn/sym probe.prove/...] is not the one its definition produces"). The
   members then recorded `{:pass-count 0 :error-count 1 :seon.test.failure/reports [{:type :error :message
   "Interrupted: request ... threw before this member finished. ..."}]}`. `seon.test.runner/designed-red?` of that
   map is `true` (checked directly: red-result? and a non-empty reports vector), so a proof that never ran
   passes `:not-designed-red`. Worse, the recorded red is answered from the record at the unchanged reach digest, so
   fixing the cause did not clear it; only `my.test/check ... :seon.test/rerun? true` did. Fix at the producer:
   a world refusal must not be recorded as a member result (it is the request's error, delivered as one), or
   `designed-red?` must require a `:fail`/`:error` report from the test's own body, not an `Interrupted` one.
2. **Why the digests were refused is NOT explained.** Incidents: `prove-verdict` at its first definition (`def` of a
   `(fn ...)` containing `#(some-> ...)`), the other `def`-of-fn helpers, then `brief` and `done-rule` after I added
   `(require '[clojure.test :refer ...])` to the namespace and two deftests. Refuted by experiment: an auto-required
   namespace (`clojure.walk`) and an explicit `(require '[clojure.set :refer [union]])` did NOT break the world once
   the namespace form listed its requires up front and every function was redefined in one request. Mitigation that
   held: one `ns` form with all requires and all defns in one request. File as a task with the first two incidents
   as repro (Prove agents will add deftests and requires after helpers exist).
3. **An unloadable deftest has no entity at all.** `(deftest s3 (is (= 1 (nope/foo 1))))` raised "Unable to resolve
   symbol: nope/foo" at definition; the Var exists in SCI but there is no `:seon.test/sym` entity and no result. So
   row 3 is `:not-a-deftest` (together with `:not-designed-red`), never `:not-designed-red` alone. The state
   "entity exists, no result at current reach" arises only when the test step failed (Surprise 1) or reach moved.
   The failed `deftest` still returned an error in the reply, so the agent is told; the proof says the same.
4. **Bug fix: `:unreached` is vacuous.** Prove changes no function in a bug fix, so `(changed database :seon.fn/sym)` is
   empty and rule 4 cannot fail; a red proof that never touches `seon.ref/section` passes (row 5 with s12 instead
   of s5 would be green). Judge also the existing functions the plan's Approach names (`cited-fns`): that is the
   `:cited-unreached` check, which red-flagged s12 correctly and passed s5/s6.
5. **Red tests the branch defines are invisible to both rules** (row 10, above). Three tests in my session were red,
   unlisted and never touched by anything; both the done rule and today's final merge accepted. Judge them at
   Prove (`:stray-red`) and make the final merge's run include `(changed database :seon.test/sym)` as members.
6. **The spec's proof body cannot compute what it asserts from inside the test world.** In a deftest run by the
   runner, `(seon.db/db)` is the member's own fresh branch (`:agent-8ce444c237dc`): its fork-point base commit is its
   own head, `seon.task/main-head` is nil, and it holds the program rows (so `s8` and `log-session` exist) but NO
   results and no agents but itself. A deftest calling my `changed`/`results` saw: changed fns empty, changed tests
   empty, results empty (all four assertions failed, 937 ms, 2.8 GB allocated for one test). `seon.test/task` is nil
   outside a task-bound run; `seon.test/*member*` holds `:seon.db/connection`, `:seon.agent/branch`, but names the
   member's branch, not the requesting agent's. So `prove-handoff` and any rule needing "the branch since it forked",
   "the proofs' recorded results" or "main" must be handed the requesting agent's database (its branch at the
   request's captured commit) and main's, by the runner, as explicit bindings beside `*task*`. That is a runner
   change inside S2, not a helper in `seon.step-test`.
7. **A proof that judges other tests' results must run after them.** The rule reads results of the proofs, so within
   one request the runner must run `prove-handoff` last (or in a second pass); the same ordering holds for the
   done rule. Today the step runs tests as one batch (inferred from the request shape; not tested).
8. **Contract gaps for tests defined in the same request.** A function defined in a request is not in the database
   until the request records (design table surprise 1); the test step runs after recording, so proofs see it, but a
   hand-written verdict called in the defining form does not. Stub schemas must be registered in an earlier request.
9. `seon.profile/measure` returns `:seon.profile/alloc-bytes` (thread scope) and `:seon.profile/ms`; the reply's
   `:alloc-mb` is request-wide and swamps small calls. Use `measure`.
10. `my.program/ns-unmap!` costs 110-470 ms each and answers a `{:seon.program/affected ...}` map; fine for cleanup,
    but Prove's "unmap scratch helpers" instruction costs a request per helper.
11. Not verified here (needs the platform): turn budget and `:capped`; the real `:seon.task/proofs`/`:next` fields;
    restart durability; whether `seon.cluster.source/results-verdict`'s members for a real merge include branch-defined
    tests (I only fed it hand-built members).

## Proposed edits to TASK-CHAIN

Section 2, step 3 (Work): replace "the runner runs: the task's proofs, every test defined or changed on the branch
since it forked, and the tests reaching changed functions" with the measured fact: a newly defined deftest is already
run by the eval request's test step (`seon.turn/test-step`); S2 only adds the task's proofs by field. Add: "a world
that cannot be built is the request's error, never a member's recorded result (`designed-red?` accepts one)."

Section 2, step 4 (Done): "the task's proofs are all green, no test green on main is red on the branch, no test
red unless it is one of the next task's proofs (`stray-red`), and every function new or changed has empty
`seon.contracts/gaps`." Keep the note that for Prove the judge is `prove-handoff`, for the others `results-verdict`.

Section 2, step 6 (Finish): "no red test the branch defines" needs the final merge run's members to include
`(changed database :seon.test/sym)`; as written (`results-verdict` over the run's members) it accepts hidden reds
(row 10). Name that change in S3.

Section 4, helpers: `fork-database`/`changed`/`results` must receive the requesting agent's database, not
`(seon.db/db)` of the member (Surprise 6). Add the runner binding to S2: `seon.test/branch-database` (the requesting
branch's value at the request's commit) and `seon.test/main-database`, bound beside `*task*`; the deftest reads them,
never the world. Add `deftest?` (a `:seon.test/sym` entity) and `stray-red` to the shared helpers.

Section 4, Prove Done and `prove-handoff` assertions, replace 1-4 with:
1. The next task's `:seon.task/proofs` is non-empty.
2. Each names a deftest on the branch (`:seon.test/sym`). (A deftest that does not load has no entity: say "a deftest
   that fails to load is not a deftest; read the diagnostics in the reply".)
3. Each has a result at current reach (`results`) with `designed-red?`, and that red comes from the test's own
   assertion or the code under test (not an `Interrupted:` report, Surprise 1).
4. Every function in `(changed database :seon.fn/sym)` is reached by one proof, AND every existing function the plan's
   Approach names (`cited-fns`) is reached by one proof (otherwise a bug-fix Prove has nothing to judge).
5. No test in `(changed database :seon.test/sym)` that is red is outside the proofs.
Report every failed assertion, as for Design. Order: `prove-handoff` runs after the proofs in the request.

Instructions (Prove, Work): add "Write your stub schemas in one request and your stubs in the next; define helpers and
deftests with one `ns` form that lists every require (an unexplained digest refusal followed changes of the namespace
form)", and "delete a deftest you do not intend as a proof (`my.program/ns-unmap!`): a red test outside the next
task's proofs fails Prove".

Section 8 answers: Q1 no: add `stray-red` (row 10). Q2: newly defined tests already run; S2 adds only the field
binding and the explicit database bindings. Cost budget: each verdict 10-50 ms, 15-60 MB; no concern.
