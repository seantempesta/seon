(ns seon.ctx.status-test
  "seon.ctx.status's done-rule input, the judge's own (docs/tasks.md#the-done-rule). Run: bin/test seon.ctx.status-test"
  (:require [clojure.test :refer [deftest is]] [seon.reconcile :as r] [datahike.api :as d] [seon.test-store :as ts] [seon.task :as task]
            [seon.db :as pdb] [seon.ctx] [seon.ctx.status :as status]))

(deftest ^{:seon.test/estimate {:ticks 200000}} the-status-reads-the-judges-done-input
  ;; the status built the done rule's input by hand when no test outside the proofs was red, beside the judge's
  ;; seon.reconcile/done-input, so the two could drift (a key the judge adds the status lacked). Both call seon.reconcile/input now:
  ;; for a branch with a proof red, one green, a contract gap and no stray red, the status's input and its reasons are the judge's
  (let [id "app/t" proofs '#{app.t-test/red app.t-test/green}
        results '{app.t-test/red :fail app.t-test/green :pass}
        gaps '#{app.t/f} unmerged [] deferred #{}
        idx {:root nil :tasks {id {:seon.task/id id :seon.task/kind :work}} :keys {} :needed-by {} :acc {id proofs} :depth {} :claims {}
             :agents {} :done {} :ready #{} :ready-key {} :live #{} :reap #{} :kill {} :judge #{} :merging #{}}
        judge (r/done-input idx id results gaps unmerged nil nil deferred)
        status (r/input {:proofs proofs :kind :work} results gaps unmerged deferred (constantly false) (constantly false))]
    (is (= judge status) (str "one input, whoever reads it: " (pr-str judge) " " (pr-str status)))
    (is (= (r/done-explain judge) (r/done-explain status)))
    (is (= #{:proof-red :contract-gap} (into #{} (map :reason) (r/done-explain status)))
        (str "the red proof and the gap are its reasons: " (pr-str (r/done-explain status))))))

;; docs/render.md#how-the-work-stands: round 2 (2026-11-15): an agent whose change reddened another task's test spent four turns of db
;; queries learning whose it was. A red test outside the task's proofs carries the tasks claiming it. Estimate: one refused call
;; over two reasons, ~50k ticks; 1,000,000
(deftest ^{:seon.test/estimate {:ticks 1000000 :bytes 1000000}} the-status-names-each-reached-tests-claiming-task
  (let [refused @#'seon.ctx.status/refused
        reasons [{:reason :stray-red :symbol 'app.b-test/other} {:reason :stray-red :symbol 'app.c-test/nobodys}]
        claims {'app.b-test/other [{:task "app.b/the-other-task" :outcome :merged}]}
        rows (refused reasons [] (constantly nil) (constantly nil) (constantly false) #(get claims % []))
        by (into {} (map (juxt :test identity)) rows)]
    (is (= [{:task "app.b/the-other-task" :outcome :merged}] (:claimed-by (by 'app.b-test/other))) (pr-str rows))
    (is (not (contains? (by 'app.c-test/nobodys) :claimed-by)) "a test nobody claims says nothing of claims")))


(deftest ^{:seon.test/estimate {:ticks 200000}} an-open-problem-blocker-carries-the-form-that-clears-it
  ;; docs/health.md#a-key-with-no-schema: round 4's runs reached their fix and stopped on open problems told in words; the status's
  ;; :open-problem blocker carries each of its problems' clearing forms (the caused rows' :do, joined by message), as a red proof its own
  (let [clearing (deref #'seon.ctx.status/clearing-forms)
        rows [{:message "key :a/b has no schema" :do '(my.schema/register! :a/b [:int])}
              {:message "key :a/c has no schema" :do '(my.schema/register! :a/c [:int])}
              {:message "told in words only"}]
        [one several none red] (clearing rows [{:reason :open-problem :problems ["key :a/b has no schema"]}
                                               {:reason :open-problem :problems ["key :a/b has no schema" "key :a/c has no schema"]}
                                               {:reason :open-problem :problems ["told in words only"]}
                                               {:reason :proof-red :problems ["key :a/b has no schema"]}])]
    (is (= '(my.schema/register! :a/b [:int]) (:do one)) (pr-str one))
    (is (= '(do (my.schema/register! :a/b [:int]) (my.schema/register! :a/c [:int])) (:do several)) "several, one do of their forms")
    (is (not (contains? none :do)) "a problem with no form says none")
    (is (not (contains? red :do)) "only an open problem's")))

;; docs/render.md#how-the-work-stands, plan item 8: a red
;; outside the task's proofs is judged by the tasks claiming it. The turn listed the reconciler's whole index for that (its agent's,
;; advanced by what differs, else one built cold: 14.8M ticks on a demo branch) where the judge reads the same claims. Now the turn
;; reads only the tasks claiming a test (one scan of :seon.task/proofs) through the judge's one input producer, so its reasons are the
;; judge's on the same results, at less than the listing it replaced (red before: the status paid that listing). Estimate: 42 tasks;
;; the status of a 4-verdict branch ~2M (its diff-free reads: verdicts 4 seeks, claims-of one scan of 42 datoms and 3 entity reads,
;; done-explain, refused); the judge's index listing for the comparison ~42 x 100k = 4.2M; the world ~3M: 10,000,000
(deftest ^{:seon.test/estimate {:ticks 10000000 :bytes 60000000}} a-turn-and-the-judge-give-the-same-reasons
  (let [db0 (ts/test-db)
        proof (fn [n] (symbol "demo-test" (str n "-test")))
        ;; demo/f, the agent's task; demo/g merged, so its proof red here is a regression; demo/o1 open, so its proof red here is
        ;; allowed; 40 more tasks, the listing's n
        decls (into [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p" :seon.task/proofs #{(proof "f")}}
                     {:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "p" :seon.task/proofs #{(proof "g")}}]
                    (for [i (range 1 41)] {:seon.task/ns 'demo :seon.task/name (str "o" i) :seon.task/plan "p" :seon.task/proofs #{(proof (str "o" i))}}))
        db1 (d/db-with db0 (task/create-tx db0 decls))
        db (d/db-with db1 [{:seon.task/id "demo/g" :seon.task/outcome :merged}
                           {:seon.agent/key "a1" :seon.agent/task [:seon.task/id "demo/f"]}
                           {:var/name (proof "f") :seon.test/status :green}
                           {:var/name (proof "g") :seon.test/status :red}
                           {:var/name (proof "o1") :seon.test/status :red}
                           {:var/name 'user/stray-test :seon.test/status :red}])
        rec (:tree (seon.ctx/build (pdb/index) db (pdb/eid db :seon.agent/key "a1") :b))
        t0 (jolt.host/ticks)
        st (status/status db rec {})
        turn (- (jolt.host/ticks) t0)
        results {(proof "f") :pass (proof "g") :fail (proof "o1") :fail 'user/stray-test :fail}
        t1 (jolt.host/ticks)
        idx (r/index db)
        listing (- (jolt.host/ticks) t1)
        judge (r/done-explain (r/done-input idx "demo/f" results #{} [] nil nil #{}))]
    (is (= judge (:reasons st)) (str "the judge's reasons, on the same results: " (pr-str judge) " " (pr-str (:reasons st))))
    (is (= [:regression :stray-red] (mapv :reason (:reasons st))) "a merged task's proof red is a regression, an open task's is allowed, the rest stray")
    (is (< turn listing) (str "the turn reads the claims, never the listing: " turn " ticks against the listing's " listing))))
