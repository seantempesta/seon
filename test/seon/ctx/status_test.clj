(ns seon.ctx.status-test
  "seon.ctx.status's done-rule input, the judge's own (docs/tasks.md#the-done-rule). Run: bin/test seon.ctx.status-test"
  (:require [clojure.test :refer [deftest is]] [seon.reconcile :as r] [seon.ctx.status :as status] [my.test]
            [seon.test-store :as ts] [datahike.api :as d] [seon.db] [seon.config] [seon.proofs]))

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

;; docs/render.md#how-the-work-stands: a proof to write is shown one test of its namespace to copy. A seon-driver copied the shortest,
;; which had no estimate, and its prove then asked for one: a turn lost (2026-11-16). The example is the shortest that states its
;; estimate, as a new test must. Red before: the shortest, unestimated. Estimate: a world (~2M) and one test-shape read (~0.2M): 3,000,000
(deftest ^{:seon.test/estimate {:ticks 3000000 :bytes 30000000}} a-proof-to-write-is-shown-a-test-that-states-its-estimate
  (let [db (my.test/with (my.test/world)
                         (my.test/code 'app.shape-test (str "(ns app.shape-test (:require [clojure.test :refer [deftest is]]))\n"
                                                            "(deftest short (is true))\n"
                                                            "(deftest ^{:seon.test/estimate {:ticks 100000}} with-estimate (is (= 1 1)))\n")))
        shape (status/test-shape db 'app.shape-test/new-proof)]
    (is (clojure.string/includes? (str (:example shape)) ":seon.test/estimate") (str "the example states its estimate: " (pr-str (:example shape))))))

;; Estimate: a test branch and two more (~3M), four verdicts written on the results branch (~0.4M), the read: two proofs' ran-on (two
;; seeks each) and two AVET seeks of the reds (~50k): ~3.5M; 6,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 6000000}} a-proofs-verdict-is-the-newest-proof-fact-at-its-reach
  ;; task seon.ctx.status/the-status-reads-proof-facts: the status reads each proof's verdict, and every test whose newest verdict on
  ;; this branch is not green, from the :seon.proof entities on the results branch that ran on the branch (seon.proofs/ran-on), as a
  ;; page does; a verdict another branch ran is not this branch's. Red before: it read the :seon.test/* facts on the test var, which no
  ;; verdict writes, so it answered nothing
  (ts/test-branch!
   (fn [cfg conn]
     (let [main (:branch cfg)
           rb (seon.db/on-main main (seon.config/value :seon.config.proofs/results-branch))
           ab (seon.db/on-main main :status-agent)
           other (seon.db/on-main main :status-other)
           p 'app.proofs/add-test u 'app.proofs/other-test w 'app.proofs/elsewhere-test
           _ (d/branch! conn main rb)
           _ (d/branch! conn main ab)
           rc (d/connect (assoc cfg :branch rb))
           ac (d/connect (assoc cfg :branch ab))
           verdict (fn [id t st on fs] (cond-> {:seon.proof/id id :seon.proof/test t :seon.proof/status st :seon.proof/route :live :seon.proof/ran-on #{on}}
                                         (seq fs) (assoc :seon.proof/failures fs)))]
       (try
         (d/transact rc [(verdict "v1" p :fail ab [{:message "expected 3"}])])
         (d/transact rc [(verdict "v2" p :pass ab nil)])
         (d/transact rc [(verdict "v3" u :fail ab [{:message "expected 4"}])])
         (d/transact rc [(verdict "v4" w :fail other nil)])
         (let [vs (#'status/verdicts (d/db ac) #{p})]
           (is (= {:status :pass} (get vs p)) "a proof's newest verdict on this branch")
           (is (= {:status :fail :failures [{:message "expected 4"}]} (get vs u)) "a test red here that is no proof, with its failures")
           (is (not (contains? vs w)) "a red another branch ran is not this branch's"))
         (finally (d/release rc) (d/release ac) (d/delete-branch! conn rb) (d/delete-branch! conn ab)))))))
