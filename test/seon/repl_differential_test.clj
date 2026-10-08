(ns seon.repl-differential-test
  "The REPL against plain SCI: each row sends its exact input through the MCP
  entrance (`seon.mcp/call`, SCI mode) and asserts the value it answers is the
  value plain SCI gives, or the row's stated value where a policy differs."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(def rows
  "`[label code]` or `[label code expected]`: the value of `code`'s last form
  equals plain SCI's, or `expected` when given. A vector `code` is one pass per
  string, plain SCI running them all in one context. One line adds a row."
  [["add" "(+ 1 2)"]
   ["macro" "(defmacro twice [x] `(do ~x ~x)) (let [a (atom 0)] (twice (swap! a inc)) @a)"]
   ["records" "(defrecord R [x]) (:x (->R 8))"]
   ["alias" "(alias 'cc 'clojure.core) (cc/inc 1)"]
   ["unmap" "(def u 1) (ns-unmap *ns* 'u) (resolve 'u)"]
   ["exception-continues" "(/ 1 0)\n:after"]
   ["later-pass-redefinition-reaches-earlier-callers"
    ["(defn g2 [] 1) (defn h2 [] (g2)) (defn g2 [] 2) [(h2) (g2)]" "(defn g2 [] 3) [(h2) (g2)]"]]
   ["later-pass-redefinition-reaches-a-caller-of-a-later-pass"
    ["(defn g3 [] 1) (defn h3 [] (g3))" "(defn k3 [] (h3))" "(defn g3 [] 2) [(k3) (h3) (g3)]"]]
   ["redefinition-after-a-pass-that-changed-no-program"
    ["(defn g4 [] 1) (defn h4 [] (g4))" ":idle" "(defn g4 [] 2) [(h4) (g4)]"]]
   ;; JVM Clojure refuses an alias retarget (the SCI fork does too)
   ["alias-retarget-refuses"
    "(alias 'fa1 'clojure.string) [(try (alias 'fa1 'clojure.set) :retargeted (catch Exception _ :refused)) (ns-name (get (ns-aliases *ns*) 'fa1))]"
    [:refused 'clojure.string]]
   ["require-as-retarget-refuses"
    "(require '[clojure.string :as fa2]) [(try (require '[clojure.set :as fa2]) :retargeted (catch Exception _ :refused)) (ns-name (get (ns-aliases *ns*) 'fa2))]"
    [:refused 'clojure.string]]
   ["alias-same-target" "(alias 'fa3 'clojure.string) (alias 'fa3 'clojure.string)"]
   ["unalias-then-retarget"
    "(alias 'fa4 'clojure.string) (ns-unalias *ns* 'fa4) (alias 'fa4 'clojure.set) (ns-name (get (ns-aliases *ns*) 'fa4))"]])

(defn- plain
  "The value of `code`'s last form that did not throw, in a fresh plain SCI context."
  {:malli/schema [:=> [:cat [:or :string [:vector :string]]] :seon.schema/value]}
  [code]
  (let [ctx (sci/init {})
        reader (sci/reader (if (string? code) code (apply str (interpose "\n" code))))]
    (loop [value nil]
      (let [form (sci/parse-next ctx reader)]
        (if (= :sci.core/eof form)
          value
          (recur (try (sci/eval-form ctx form) (catch Throwable _ value))))))))

(defn- answered
  "The value the entrance answers for `code` as `agent-id`: the last form's reply value (a same-pass test run answers in a further content item)."
  {:malli/schema [:=> [:cat :string :string :string] :seon.schema/value]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (->> (get result "content")
         (map #(support/reply (get % "text")))
         (filter #(contains? % :seon.repl/value))
         last
         :seon.repl/value)))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-caller-keeps-the-definition-of-an-earlier-pass}
           :seon.test/long "one MCP eval_clj pass per row form (17 forms across 13 rows), each ending in its test step: 7.2 s measured in a check JVM"
           :seon.test/long-ms 15000}
  plain-sci-rows-answer-as-plain-sci
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [[label code :as row] rows]
       (testing label
         (is (= (if (> (count row) 2) (nth row 2) (plain code))
                (last (mapv #(answered % agent-id running)
                            (if (string? code) [code] code))))))))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-caller-keeps-the-definition-of-an-earlier-pass}}
  a-test-of-an-earlier-pass-runs-the-redefined-callee
  (support/with-running-agent
   (fn [running agent-id _]
     (answered (str "(require '[clojure.test :refer [deftest is]]) (defn sq [x] (* x x)) (defn other [x] (sq x))"
                    " (deftest other-squares (is (= 4 (other 2))))")
               agent-id running)
     (answered "(defn sq [x] (+ x 4)) (deftest other-is-six (is (= 6 (other 2))))" agent-id running)
     (let [namespace-name (str "my.agents." agent-id)
           check (answered (str "(my.test/check {:seon.test/changed '[" namespace-name "/other-squares "
                                namespace-name "/other-is-six]})")
                           agent-id running)
           failed (into #{} (map (comp name :seon.test/sym)) (:seon.render.test/failures check))]
       (is (= [2 1 #{"other-squares"}]
              [(:seon.render.test/test check) (:seon.render.test/fail check) failed]))))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-caller-keeps-the-definition-of-an-earlier-pass}}
  another-writer-on-the-cluster-head-neither-hides-nor-freezes-a-callee
  ;; The fixture agent holds the cluster head's custody (its root, as on default);
  ;; a second agent created there is another writer of the same branch.
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [other (str "other-" agent-id)
           in-shared "(in-ns 'scratch.shared-writer) (clojure.core/refer-clojure) "]
       (support/transacted! connection (support/agent-tx @connection other))
       (testing "another writer's program change re-acquires the head; the agent's own callee stays its Var"
         (answered (str in-shared "(defn g6 [] 1) (defn h6 [] (g6))") agent-id running)
         (answered (str in-shared "(defn unrelated [] 0)") other running)
         (is (= [2 2] (answered (str in-shared "(defn g6 [] 2) [(h6) (g6)]") agent-id running))))
       (testing "another writer's redefinition reaches the agent's earlier caller"
         (answered (str in-shared "(defn g5 [] 1) (defn h5 [] (g5)) [(h5) (g5)]") agent-id running)
         (answered (str in-shared "(defn g5 [] 7)") other running)
         (is (= [7 7] (answered (str in-shared "[(h5) (g5)]") agent-id running)))
         (is (= [8 8] (answered (str in-shared "(defn g5 [] 8) [(h5) (g5)]") agent-id running))))))))

(deftest ^{:seon/tasks #{'seon.sci.eval/an-inherited-caller-sees-a-redefinition-only-after-the-pass}}
  an-inherited-caller-sees-a-same-pass-redefinition
  ;; The fixture agent holds the cluster head's custody; the other agents
  ;; created there are other writers of the same branch.
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [other (str "other-" agent-id)
           fresh (str "fresh-" agent-id)
           in-shared "(in-ns 'scratch.shared-writer) (clojure.core/refer-clojure) "]
       (support/transacted! connection (support/agent-tx @connection other))
       (support/transacted! connection (support/agent-tx @connection fresh))
       (testing "a caller another writer defined runs the agent's redefinition in the redefining pass"
         (answered (str in-shared "(defn g7 [] 1)") agent-id running)
         (answered (str in-shared "(defn h7 [] (g7)) (defn k7 [] (h7))") other running)
         (is (= [2 2 2] (answered (str in-shared "(defn g7 [] 2) [(k7) (h7) (g7)]") agent-id running))))
       (testing "an agent's first pass (a fresh fork) redefines a callee the base interpreted"
         (is (= [3 3 3] (answered (str in-shared "(defn g7 [] 3) [(k7) (h7) (g7)]") fresh running))))))))

(defn- step-red
  "The tests the step of `agent-id`'s pass over `code` ran red, proofs and reaching tests, with the last form's value."
  {:malli/schema [:=> [:cat :string :string :string] [:map [:value :seon.schema/value] [:red [:set :symbol]] [:passed? [:maybe :boolean]]]]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))
        items (map #(support/reply (get % "text")) (get result "content"))
        tests (some :seon.turn.loop/tests items)]
    {:value (:seon.repl/value (last (filter #(contains? % :seon.repl/value) items)))
     :passed? (get-in tests [:seon.turn.loop/reaching :seon.test/passed?])
     :red (into #{} (mapcat #(get-in tests [% :seon.turn.loop/red]))
                [:seon.turn.loop/proofs :seon.turn.loop/reaching])}))

(deftest ^{:seon/tasks #{'seon.sci.eval/an-inherited-test-never-sees-the-branch-redefinition-of-its-callee}}
  an-inherited-test-sees-the-branch-redefinition
  ;; The fixture agent holds the cluster head's custody (main); a new agent id
  ;; gets its own branch off it on first use.
  (support/with-running-agent
   (fn [running agent-id _]
     (let [brancher (str "branch-" agent-id)]
       (answered "(in-ns 'scratch.inherited-test) (clojure.core/refer-clojure) (defn g9 [] 1)" agent-id running)
       (answered (str "(ns scratch.inherited-test-test (:require [clojure.test :refer [deftest is]]"
                      " [scratch.inherited-test :as it])) (deftest g9-is-two (is (= 2 (it/g9))))")
                 agent-id running)
       (testing "the redefining pass runs the inherited test against the branch's definition"
         (let [{:keys [value red passed?]} (step-red "(in-ns 'scratch.inherited-test) (defn g9 [] 2) (g9)" brancher running)]
           (is (= 2 value))
           (is (not (contains? red 'scratch.inherited-test-test/g9-is-two)))
           (is (true? passed?) "the step ran the reaching tests green")))
       (testing "a later pass still runs it against the branch's definition"
         (let [{:keys [red passed?]} (step-red "(+ 1 1)" brancher running)]
           (is (not (contains? red 'scratch.inherited-test-test/g9-is-two)))
           (is (true? passed?) "the step ran the reaching tests green")))))))
