(ns seon.context-test
  "The agent's current-state block says each thing once: a passing proof is its status, a
  red one its source and its failures (expected and actual), a reference already shown as a
  proof is not shown again, a merge task's plan stays its own."
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.db :as pdb] [seon.task :as task] [seon.context :as context] [seon.print :as print]
            [seon.reader :as reader] [seon.proofs :as proofs] [seon.agent :as agent]))

(defn- db-with-program []
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}
        _ (d/create-database cfg)
        db (d/db-with @(d/connect cfg) pdb/schema)]
    ;; recorded definitions, each its form (seon.program's facts), written as data
    (d/db-with db (vec (mapcat (fn [[v src]]
                                 [{:db/id (str v) :var/name v :var/ns (str "ns " (namespace v)) :var/form (str "form " v)}
                                  {:db/id (str "form " v) :form/id (str v) :form/src src :form/ns (str "ns " (namespace v)) :form/order 0
                                   :form/defines [(str v)]}
                                  {:db/id (str "ns " (namespace v)) :ns/name (symbol (namespace v))}])
                               '[[demo/f "(defn f \"Doubles.\" [x] (* 2 x))"]
                                 [demo-test/f-test "(deftest f-test (is (= 4 (f 2))))"]
                                 [demo-test/g-test "(deftest g-test (is (= 1 (g))))"]])))))

(deftest each-thing-is-said-once
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make `demo/f` double."
                                               :seon.task/instructions "Use `demo/f` and `demo-test/g-test`."
                                               :seon.task/proofs #{'demo-test/f-test 'demo-test/g-test}}]))
        t (task/task db "demo/f")
        block (context/current-state db t {:results {'demo-test/f-test :pass 'demo-test/g-test :fail}
                                           :reds [{:test 'demo-test/g-test :type :fail :message nil :expected "(= 1 (g))" :actual "(not (= 1 0))"}]
                                           :turn 0 :max-turns 4})
        by (into {} (map (juxt :test identity)) (:proofs block))]
    (is (= {:test 'demo-test/f-test :status :pass} (by 'demo-test/f-test)) "a passing proof is its status")
    (is (string? (:source (by 'demo-test/g-test))) "a red proof shows its source")
    (is (= [{:expected "(= 1 (g))" :actual "(not (= 1 0))"}] (:failures (by 'demo-test/g-test)))
        "a failure is what was expected and what happened")
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
        reds [{:test 'demo-test/g-test :type :fail :expected "(= 1 (g))" :actual "(not (= 1 0))"}]
        block (context/current-state db t {:results {'demo-test/f-test :fail 'demo-test/g-test :fail} :reds reds
                                           :refusals refusals :turn 1 :max-turns 4
                                           :run-errors ["tests timed out after 30000 ms; killed"]})]
    (testing "every refusal but the task's own red proof (shown with the proofs), each with what explains it"
      (is (= [{:refusal :regression :test 'demo-test/g-test :failures [{:expected "(= 1 (g))" :actual "(not (= 1 0))"}]}
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
    (is (= [{:near ":cat :int] :int]} [x] (* 2 x))," :dropped ")" :inserted ","}] (:repaired (:your-last-reply block)))
        "what was dropped, what replaced it, and the code it was near; the prose made comments is no repair")))

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
