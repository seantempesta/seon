(ns seon.context-test
  "The agent's current-state block says each thing once: a passing proof is its status, a
  red one its source and its failures (expected and actual), a reference already shown as a
  proof is not shown again, a merge task's plan stays its own."
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.db :as pdb] [seon.task :as task] [seon.context :as context] [seon.print :as print]
            [seon.reader :as reader] [seon.proofs :as proofs] [seon.agent :as agent]
            [seon.print.select :as select] [clojure.string :as str] [clojure.walk :as walk]))

(defn- db-with-program []
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}
        _ (d/create-database cfg)
        db (d/db-with @(d/connect cfg) pdb/schema)]
    (d/db-with db [{:db/id "ns" :ns/name 'demo}
                   {:var/name 'demo/f :var/ns "ns" :var/src "(defn f \"Doubles.\" [x] (* 2 x))"}
                   {:var/name 'demo-test/f-test :var/ns "ns" :var/src "(deftest f-test (is (= 4 (f 2))))"}
                   {:var/name 'demo-test/g-test :var/ns "ns" :var/src "(deftest g-test (is (= 1 (g))))"}])))

(deftest each-thing-is-said-once
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make `demo/f` double."
                                               :seon.task/instructions "Use `demo/f` and `demo-test/g-test`."
                                               :seon.task/proofs #{'demo-test/f-test 'demo-test/g-test}}]))
        t (task/task db "demo/f")
        block (context/current-state db t {:results {'demo-test/f-test :pass 'demo-test/g-test :fail}
                                           :reds [{:test 'demo-test/g-test :type :fail :message nil :expected "(= 1 (g))" :actual "(not (= 1 0))" :got "(= 1 0)"}]
                                           :turn 0 :max-turns 4})
        by (into {} (map (juxt :test identity)) (:proofs block))]
    (is (= {:test 'demo-test/f-test :status :pass} (by 'demo-test/f-test)) "a passing proof is its status")
    (is (string? (:source (by 'demo-test/g-test))) "a red proof shows its source")
    (is (= [{:assert "(= 1 (g))" :got "(= 1 0)"}] (:failures (by 'demo-test/g-test)))
        "a failure is the assertion and what it evaluated to")
    (is (= ['demo/f] (mapv :symbol (:references block))) "a proof named in the plan is not shown again as a reference")
    (is (nil? (:doc (first (:references block)))) "a program reference's source already holds its doc")))

(deftest a-merge-plan-stays-its-own
  (let [db (d/db-with (db-with-program) [])
        decls [{:seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "Write a."}
               {:seon.task/ns 'demo :seon.task/name "a-merge" :seon.task/kind :merge :seon.task/plan "Merge a." :seon.task/needs #{"demo/a"}}
               {:seon.task/ns 'demo :seon.task/name "b" :seon.task/plan "Write b." :seon.task/needs #{"demo/a-merge"}}]
        db (d/db-with db (task/create-tx db decls))]
    (is (= "Write a.\n\nMerge a." (:plan (:seon.task/accumulated (task/task db "demo/a-merge")))))
    (is (= "Write a.\n\nWrite b." (:plan (:seon.task/accumulated (task/task db "demo/b")))) "b is not told to merge")))

(deftest a-shown-value-without-a-handle-names-no-form
  (let [{:keys [text cuts]} (print/print-value (vec (range 5000)) {:budget 60})]
    (is (re-find #"…\d+" text))
    (is (not (re-find #"result/" text)) "no result handle the reader does not hold")
    (is (nil? (:seon.print/continuation (first cuts))))))

(deftest a-cut-reply-is-told-to-the-agent
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p"}]))
        t (task/task db "demo/f")]
    (is (re-find #"at the model's token limit" (:cut (:your-last-reply (context/current-state db t {:turn 1 :max-turns 4 :truncated? true})))))
    (is (nil? (:your-last-reply (context/current-state db t {:turn 1 :max-turns 4}))))))

(defn- task-f [db] (task/task (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p"
                                                                  :seon.task/proofs #{'demo-test/f-test}}]))
                              "demo/f"))

(deftest a-refusal-and-a-run-error-reach-the-context
  (let [db (db-with-program)
        t (task-f db)
        ;; the done rule refused beyond the task's own proof: a regression, a stray red, a
        ;; contract gap; the run named one failing assertion of each red test
        refusals [{:refusal :proof-red :symbol 'demo-test/f-test} {:refusal :regression :symbol 'demo-test/g-test}
                  {:refusal :stray-red :symbol 'user/scratch-test} {:refusal :contract-gap :symbol 'demo/helper}]
        reds [{:test 'demo-test/g-test :type :fail :expected "(= 1 (g))" :actual "(not (= 1 0))" :got "(= 1 0)"}]
        block (context/current-state db t {:results {'demo-test/f-test :fail 'demo-test/g-test :fail} :reds reds
                                           :refusals refusals :turn 1 :max-turns 4
                                           :run-errors ["tests timed out after 30000 ms; killed"]})]
    (testing "every refusal but the task's own red proof (shown with the proofs), each with what explains it"
      (is (= [{:refusal :regression :test 'demo-test/g-test :failures [{:assert "(= 1 (g))" :got "(= 1 0)"}]}
              {:refusal :stray-red :test 'user/scratch-test}
              {:refusal :contract-gap :var 'demo/helper}]
             (:refused block))))
    (testing "a run that failed as a whole is said, not read as tests not run"
      (is (= {:failed ["tests timed out after 30000 ms; killed"]} (:test-run block)))
      (is (= ["tests timed out after 30000 ms; killed" "test process died: 9"]
             (proofs/run-errors {:reports [{:type :error :message "tests timed out after 30000 ms; killed"}
                                           {:type :fail :var "#'demo-test/f-test" :message "x"}
                                           {:type :error :var nil :message "test process died: 9"}]}))))
    (testing "the printed context carries them"
      (let [text (:content (second (context/messages block)))]
        (is (re-find #":regression" text))
        (is (re-find #"timed out" text))))
    (testing "nothing refused, nothing failed: no section"
      (let [b (context/current-state db t {:results {'demo-test/f-test :pass} :refusals [] :turn 1 :max-turns 4 :run-errors []})]
        (is (not-any? #(contains? b %) [:refused :test-run :flagged :your-last-reply]))))))

(deftest a-repair-is-shown-next-turn
  (let [db (db-with-program)
        t (task-f db)
        reply "Here is f.\n```clojure\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x)))\n```"
        {:keys [text repairs]} (reader/forgive reply)
        block (context/current-state db t {:turn 1 :max-turns 4 :source text :repairs repairs})]
    (is (seq repairs) "the reader repaired the extra close")
    (is (= [{:line 3 :code (nth (str/split-lines text) 2) :dropped ")" :inserted ","}] (:repaired (:your-last-reply block)))
        "what was dropped, what replaced it, and its line; the prose made comments is no repair")
    (is (str/ends-with? (nth (str/split-lines text) 2) "(* 2 x)),") "the line is the repaired definition's")
    (testing "flagged definitions are named"
      (let [db2 (d/db-with db [{:var/name 'demo/h :var/ns [:ns/name 'demo] :var/flagged true}])]
        (is (= ['demo/h] (context/flagged db2)))
        (is (= ['demo/h] (:flagged (context/current-state db2 t {:turn 1 :max-turns 4 :flagged (context/flagged db2)}))))))))

(deftest only-program-tests-judge
  ;; a deftest in a scratch namespace never merges, so it never judges the branch
  (binding [*ns* *ns*]
    (eval '(ns ctx-prog-ns (:require [clojure.test :refer [deftest is]])))
    (eval '(deftest p-test (is true)))
    (eval '(ns ctx-scratch-ns (:require [clojure.test :refer [deftest is]])))
    (eval '(deftest s-test (is false)))
    ;; scratch as the platform declares the agent's own namespace (seon.agent/make-ns!);
    ;; Jolt's (ns ^:scratch x) does not carry the metadata yet (reported to root)
    (alter-meta! (find-ns 'ctx-scratch-ns) assoc :scratch true)
    (let [db (d/db-with (db-with-program) [{:ns/name 'ctx-prog-ns} {:ns/name 'ctx-scratch-ns} {:ns/name 'user}])]
      (is (= ['ctx-prog-ns] (mapv ns-name (agent/test-namespaces db)))))))

(deftest a-failure-reads-as-assert-and-got
  (binding [*ns* *ns*]
    (eval '(ns ctx-calc))
    (eval '(defn square "Squares x." {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x)))
    (eval '(ns ctx-calc-test (:require [clojure.test :refer [deftest is]] [ctx-calc :refer [square]])))
    (eval '(deftest square-test (is (= 9 (square 3))))))
  (let [db (d/db-with (db-with-program)
                      [{:db/id "c" :ns/name 'ctx-calc}
                       {:var/name 'ctx-calc/square :var/ns "c" :var/src "(defn square \"Squares x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))"}
                       {:db/id "ct" :ns/name 'ctx-calc-test}
                       {:var/name 'ctx-calc-test/square-test :var/ns "ct" :var/src "(deftest square-test (is (= 9 (square 3))))"}
                       {:var/name 'ctx-calc-test/empty-test :var/ns "ct" :var/src "(deftest empty-test (square 3))"}])
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'ctx :seon.task/name "sq" :seon.task/plan "p"
                                               :seon.task/proofs #{'ctx-calc-test/square-test 'ctx-calc-test/cube-test 'ctx-calc-test/empty-test}}]))
        ;; the test child's report of the failing assertion, as clojure.test makes it
        rep (agent/test-report {:type :fail :expected '(= 9 (square 3)) :actual '(not (= 9 6))} "#'ctx-calc-test/square-test")
        block (context/current-state db (task/task db "ctx/sq")
                                     {:results {'ctx-calc-test/square-test :fail} :turn 1 :max-turns 4
                                      :reds (proofs/red-reports {:reports [rep]} #{'ctx-calc-test/square-test})})
        by (into {} (map (juxt :test identity)) (:proofs block))]
    (is (= [{:assert "(= 9 (square 3))" :got "(= 9 6)"}] (:failures (by 'ctx-calc-test/square-test)))
        "the assertion once, and what it evaluated to")
    (is (= [{:var 'ctx-calc/square :arglists '([x]) :contract [:=> [:cat :int] :int] :doc "Squares x."}]
           (:calls (by 'ctx-calc-test/square-test)))
        "the program function the red proof calls, at summary level")
    (is (= {:test 'ctx-calc-test/cube-test :status :undefined} (by 'ctx-calc-test/cube-test))
        "a proof not written yet says so")
    (is (= :no-assertions (:status (by 'ctx-calc-test/empty-test))) "a proof that ran and asserted nothing says so")
    (let [b (context/current-state db (task/task db "ctx/sq") {:results {} :turn 1 :max-turns 4 :run-errors ["tests timed out"]})]
      (is (= {'ctx-calc-test/square-test :not-run 'ctx-calc-test/empty-test :not-run 'ctx-calc-test/cube-test :undefined}
             (into {} (map (juxt :test :status)) (:proofs b)))
          "when the run failed as a whole, a written proof did not run"))))

(defn- evals
  "n evaluations cycling over k distinct forms, each answering a large value and printing
  a large output (inv3's edit/forms: 259 items, 11 distinct)."
  [n k]
  (let [kinds (vec (for [j (range k) :let [v (vec (range (* 2000 (inc j))))]]
                     {:tag :ret :form (str "(code-of " j ")") :handle (keyword (str "h" j))
                      :printed (print/text v) :value v
                      :out (apply str (repeat 400 (str "line " j " of pprint output\n")))}))]
    (vec (for [i (range n)] (assoc (nth kinds (mod i k)) :id i)))))

(deftest repeated-evals-fold-and-every-item-fits-its-share
  (let [db (db-with-program)
        t (task-f db)
        text-of (fn [items] (:content (second (context/messages (context/current-state db t {:turn 1 :max-turns 4 :last-items items})))))]
    (testing "259 items, 11 distinct: 11 folded items whose times sum to 259"
      (let [le (context/fold-evals (evals 259 11))]
        (is (= 11 (count le)))
        (is (= (range 11) (map :k le)) "each folded item is the first of its kind")
        (is (= [24 24 24 24 24 24 23 23 23 23 23] (mapv :times le)) "259 = 6x24 + 5x23")))
    (testing "the whole context stays inside its budget at 259 items and at 10x that, every distinct item shown"
      (doseq [n [259 2590]]
        (let [items (evals n 11) text (text-of items)]
          (is (<= (select/estimate text) (:context print/budgets)) (str n " items"))
          (is (every? #(str/includes? text (str "\"(code-of " % ")\"")) (range 11)) "no item starves another")
          (is (str/includes? text (str ";; " (count (filter #(= "(code-of 0)" (:form %)) items)) "x, the same form and answer"))))))
    (testing "each item within its equal share; every cut's form reads exactly the rest from (my.turn/evals)"
      (let [items (evals 259 11)
            shown (context/shown-evals (context/fold-evals items))
            share (quot context/last-evals-budget 11)]
        (is (every? #(<= (:tokens %) share) shown))
        (is (= 22 (count (mapcat :cuts shown))) "each of 11 items cuts its value and its output")
        (doseq [c (mapcat :cuts shown)
                :let [f (:seon.print/continuation c)
                      rest ((eval (list 'fn '[items] (walk/postwalk-replace {'(my.turn/evals) 'items} f))) items)
                      whole (get-in items (into [(nth (:seon.print/requery-id c) 2)] (:seon.render.data/path c)))]]
          (is (= (:seon.render.data/next-offset c) (- (count whole) (count rest))) (pr-str f))
          (is (= (seq whole) (seq (concat (take (:seon.render.data/next-offset c) whole) rest))) (pr-str f)))))))
