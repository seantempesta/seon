;;; Grader for the DeepSeek flash thinking-mode interrogation (2026-08-01).
;;; Usage: clojure -M:dev tmp/flash-quality/grade.clj <task> <candidate-file>
;;; Prints one line per check, then GRADE: PASS|FAIL.
;;; Correctness gates; style does not.

(require '[datahike.api :as d]
         '[malli.core :as m]
         '[malli.generator :as mg])

(def args *command-line-args*)
(def task (first args))
(def code (slurp (second args)))

(def cand (create-ns 'candidate))
(binding [*ns* cand]
  (refer-clojure)
  (require '[malli.core :as m] '[malli.generator :as mg]))

;; t5 asks the model NOT to redefine `log`, so the harness must supply it.
(when (= task "t5-debug")
  (intern cand 'log (atom [])))

(def load-error
  (try (binding [*ns* cand] (load-string code)) nil
       (catch Throwable t (str (.getName (class t)) ": " (ex-message t)))))

(defn v [sym] (some-> (ns-resolve cand sym) deref))

(def results (atom []))
(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (swap! results conj ok)
    (println (if ok "  ok  " "  FAIL") label
             (if ok "" (str "\n        expected: " (pr-str expected)
                            "\n        actual:   " (pr-str actual))))))
(defn safe [f] (try (f) (catch Throwable t (str "THREW " (.getName (class t))
                                                ": " (ex-message t)))))

(if load-error
  (do (println "  FAIL load/parse:" load-error) (println "GRADE: FAIL"))
  (do
    (case task

      "t1-transducer"
      (let [dedupe-by (v 'dedupe-by)
            coll [{:k 1 :v 1} {:k 1 :v 2} {:k 2 :v 3} {:k 2 :v 4} {:k 1 :v 5}]]
        (check! "into" [1 3 5] (safe #(mapv :v (into [] (dedupe-by :k) coll))))
        (check! "sequence" [1 3 5]
                (safe #(mapv :v (sequence (dedupe-by :k) coll))))
        (check! "transduce completion arity" 3
                (safe #(transduce (dedupe-by :k)
                                  (completing (fn [a _] (inc a))) 0 coll)))
        (check! "REUSABLE transducer value" true
                (safe #(let [xf (dedupe-by :k)]
                         (= (into [] xf coll) (into [] xf coll)))))
        (check! "metadata preserved" {:tag :a}
                (safe #(meta (first (into [] (dedupe-by :k)
                                          [(with-meta {:k 1} {:tag :a})])))))
        (check! "consecutive nils are dups" [1 2 3]
                (safe #(mapv :v (into [] (dedupe-by :k)
                                      [{:k nil :v 1} {:k nil :v 9}
                                       {:k 1 :v 2} {:k nil :v 3}]))))
        (check! "early termination via take" [1]
                (safe #(mapv :v (into [] (comp (dedupe-by :k) (take 1)) coll)))))

      "t2-chunking"
      (let [f (v 'map-exactly-n)]
        (check! "first 3 of range 100, exactly 3 calls" [[0 1 4] 3]
                (safe #(let [c (atom 0)
                             out (f 3 (fn [x] (swap! c inc) (* x x)) (range 100))]
                         [(vec out) @c])))
        (check! "n exceeds coll length" [[1 4] 2]
                (safe #(let [c (atom 0)
                             out (f 5 (fn [x] (swap! c inc) (* x x)) [1 2])]
                         [(vec out) @c])))
        (check! "n = 0 calls f zero times" [[] 0]
                (safe #(let [c (atom 0)
                             out (f 0 (fn [x] (swap! c inc) x) (range 100))]
                         [(vec out) @c]))))

      "t3-cas-reduce"
      (let [f (v 'apply-fenced)
            init {:basis 0 :applied [] :rejected []}]
        (check! "halt stops without processing"
                {:basis 2 :applied [:a :c] :rejected [:b]}
                (safe #(f init [{:tx/expect 0 :tx/id :a}
                                {:tx/expect 0 :tx/id :b}
                                {:tx/expect 1 :tx/id :c}
                                {:tx/halt true :tx/expect 2 :tx/id :d}
                                {:tx/expect 2 :tx/id :e}])))
        (check! "no halt runs to completion"
                {:basis 2 :applied [:a :b] :rejected [:c]}
                (safe #(f init [{:tx/expect 0 :tx/id :a}
                                {:tx/expect 1 :tx/id :b}
                                {:tx/expect 9 :tx/id :c}])))
        (check! "empty txs" init (safe #(f init [])))
        (check! "applied/rejected are vectors" [true true]
                (safe #(let [r (f init [{:tx/expect 0 :tx/id :a}
                                        {:tx/expect 9 :tx/id :b}])]
                         [(vector? (:applied r)) (vector? (:rejected r))]))))

      "t4-datalog-malli"
      (let [q (v 'interrupted-token-load-q)
            schema (v 'RunLoad)
            ;; agent 100 has runs 200 (epoch 7) and 201 (epoch 8)
            ;; receipts: 300 interrupted 50, 301 ok 10, 302 interrupted 70
            facts [[100 :seon.cluster.agent/id "ag-1"]
                   [101 :seon.cluster.agent/id "ag-2"]
                   [200 :seon.agent.run/agent 100]
                   [200 :seon.agent.run/epoch 7]
                   [200 :seon.agent.run/receipt 300]
                   [200 :seon.agent.run/receipt 301]
                   [201 :seon.agent.run/agent 100]
                   [201 :seon.agent.run/epoch 8]
                   [201 :seon.agent.run/receipt 302]
                   [202 :seon.agent.run/agent 101]
                   [202 :seon.agent.run/epoch 9]
                   [202 :seon.agent.run/receipt 303]
                   [300 :seon.agent.receipt/status :interrupted]
                   [300 :seon.agent.receipt/tokens 50]
                   [301 :seon.agent.receipt/status :ok]
                   [301 :seon.agent.receipt/tokens 10]
                   [302 :seon.agent.receipt/status :interrupted]
                   [302 :seon.agent.receipt/tokens 70]
                   [303 :seon.agent.receipt/status :interrupted]
                   [303 :seon.agent.receipt/tokens 999]]]
        (check! "query finds interrupted receipts for ag-1 only"
                #{[7 50] [8 70]}
                (safe #(set (d/q q facts "ag-1"))))
        (check! "query isolates the other agent" #{[9 999]}
                (safe #(set (d/q q facts "ag-2"))))
        (check! "schema accepts a valid map" true
                (safe #(m/validate schema {:agent/id "a" :run/epoch 1
                                           :run/interrupted 2 :run/total 5})))
        (check! "schema rejects interrupted > total" false
                (safe #(m/validate schema {:agent/id "a" :run/epoch 1
                                           :run/interrupted 9 :run/total 5})))
        (check! "schema rejects a missing key" false
                (safe #(m/validate schema {:agent/id "a" :run/epoch 1
                                           :run/interrupted 2})))
        (check! "generator produces 20 valid samples" true
                (safe #(every? (fn [s] (m/validate schema s))
                               (repeatedly 20 (fn [] (mg/generate schema)))))))

      "t5-debug"
      (let [running-sums (v 'running-sums)
            logged-sums (v 'logged-sums)
            log (v 'log)]
        (check! "running-sums [1 2 3 4]" [1 3 6 10]
                (safe #(vec (running-sums [1 2 3 4]))))
        (check! "running-sums []" [] (safe #(vec (running-sums []))))
        (check! "running-sums [5]" [5] (safe #(vec (running-sums [5]))))
        (check! "logged-sums result + log" [[1 3 6] [1 2 3]]
                (safe #(let [out (logged-sums [1 2 3])]
                         [(vec out) (vec @log)]))))

      (println "  FAIL unknown task" task))

    (println (if (and (seq @results) (every? true? @results))
               "GRADE: PASS" "GRADE: FAIL"))))
