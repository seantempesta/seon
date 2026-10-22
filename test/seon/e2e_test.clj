(ns seon.e2e-test
  "A run ends on what the system observes: quiescence, or a recorded defect (audit FENCES
  #26: a flight whose answer is lost never ends, so quiescence never comes; a root proc's
  defect recorded on main ends the wait instead). No clock."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.e2e :as e2e]
            [seon.fixtures :as fx] [seon.ref :as ref] [seon.reader :as reader] [seon.task :as task] [seon.template :as tpl]
            [clojure.string :as str]))


(deftest await-quiet-ends-on-a-recorded-defect
  (ts/test-branch! (fn [_ c] (let [sys {:conn c}
        t (:max-tx @c)
        start (System/currentTimeMillis)
        waiting (future (e2e/await-quiet sys t))]
    (d/transact c {:tx-data [{:seon.error/signature "0123456789abcdef" :seon.error/class :eval :seon.error/count 1 :seon.error/agents 1
                              :seon.error/first-ms start :seon.error/last-ms start}]})
    (is (not (realized? (do (d/transact c {:tx-data [{:note/text "work goes on"}]}) waiting))) "an agent's eval error is no defect of the run")
    (d/transact c {:tx-data [{:seon.error/signature "fedcba9876543210" :seon.error/class :platform :seon.error/count 1 :seon.error/agents 0
                              :seon.error/first-ms start :seon.error/last-ms start :seon.error/message "seon.reconcile/reconciler-proc: boom"}]})
    (let [r (deref waiting)]
      (is (= :defect (:ended r)))
      (is (= "seon.reconcile/reconciler-proc: boom" (get-in r [:problem :seon.error/message])) "the problem, as recorded"))
    (testing "quiescence ends it as before"
      (ts/test-branch! (fn [_ c] (let [t (:max-tx @c) waiting (future (e2e/await-quiet {:conn c} t))]
        (d/transact c {:tx-data [{:seon.root/id :root :seon.root/quiescent {:t (inc t) :stuck []}}]})
        (is (= {:t (inc t) :stuck []} (deref waiting)))))))))))

;; ---- the first real DeepSeek runs (task seon.e2e/the-scenarios-measure-real-agents) ----------

(defn- proof-vars
  "The vars a spec's proof calls: its form's qualified symbols outside the proofs namespace."
  [spec]
  (->> (tree-seq coll? seq (reader/read-text (:proof spec) 'app.proofs))
       (filter qualified-symbol?) (remove #(= "app.proofs" (namespace %))) distinct vec))

(deftest a-default-plan-cites-the-vars-its-proof-calls
  (let [specs (concat fx/calc (mapcat :specs (vals fx/scenarios)) (fx/swarm-specs 6))
        planless (filter #(and (:name %) (:proof %) (not (:plan %))) specs)
        plan-of #(:seon.task/plan (first (filter (fn [d] (= (:name %) (:seon.task/name d))) (fx/decls [%] 'x nil))))]
    (is (seq planless))
    (doseq [s planless :let [cited (set (ref/names (plan-of s)))]]
      (is (every? cited (proof-vars s)) (str (:name s) " cites what its proof calls")))
    (testing "hairy's task a: four functions, and not its own name as if it were a var"
      (let [a (first (filter #(and (= "a" (:name %)) (= 'app.m (:ns %))) (:specs (:hairy fx/scenarios))))
            cited (set (ref/names (plan-of a)))]
        (is (every? cited '[app.m/f1 app.m/f2 app.m/f3 app.m/f4]))
        (is (not (cited 'app.m/a)))))))

(def ^:private plan-satisfied
  "The least program that does what the :newns task's plan asks: a namespace with triple,
  and a test namespace that refers it and passes."
  (str "(ns app.newns) (defn triple [x] (* 3 x)) "
       "(ns app.newns-test (:require [clojure.test :refer [deftest is]] [app.newns :refer [triple]])) "
       "(deftest triple-test (is (= 9 (triple 3)))) "))

(deftest a-model-run-is-judged-by-what-the-plan-asks
  (let [{:keys [check expect]} (e2e/expectation :newns true)]
    (is (= expect [(pr-str (load-string (str plan-satisfied check)))])
        "a program that does what the plan asks, and nothing more, passes the model-mode verdict"))
  (testing "the scripted model's extras are asked of the scripted model only"
    (is (str/includes? (first (:expect (e2e/expectation :newns false))) "(inc 1)"))
    (is (not-any? #(str/includes? % "(inc 1)") (:expect (e2e/expectation :newns true))))))

(deftest turns-count-the-work-attempts
  (let [decls (tpl/with-merge (vec (for [nm ["a" "b" "c"]]
                                     {:seon.task/ns 'demo :seon.task/name nm :seon.task/plan "Make it true." :seon.task/proofs #{(symbol "demo-test" nm)}})))
        empty-db (ts/test-db)
        db (d/db-with empty-db (task/create-tx empty-db decls))
        att (fn [id n status turns]
              (let [tid (str "demo/" id) branch (task/default-branch (str "demo/" (first (str/split id #"-"))))]
                (conj (task/attempt-start-tx (str tid "#" n) tid "boot-1" (keyword (str "a-" id n)) {:branch branch :model nil :max-turns 4} 1000 "Make it true.")
                      {:seon.attempt/id (str tid "#" n) :seon.attempt/status status :seon.attempt/turns turns})))
        db (d/db-with db (concat (att "a" 1 :closed 3) (att "a" 2 :ended 2) (att "a-merge" 1 :merged 0)
                                 (att "b" 1 :ended 1) (att "b-merge" 1 :merged 0)
                                 (att "c" 1 :ended 4) (att "c-merge" 1 :closed 0)))]
    (is (= 3.0 (double (e2e/turns-per-merged-task db))) "a and b reached main: (3 + 2 + 1) turns over 2 tasks; c did not")))
