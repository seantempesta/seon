(ns seon.repl-differential-test
  "The REPL against plain SCI: each row sends its exact input through the MCP
  entrance (`seon.mcp/call`, SCI mode) and asserts the value it answers is the
  value plain SCI gives, or the row's stated value where a policy differs."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.db :as db]
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

(deftest ^{:seon.test/long "one MCP eval_clj pass per row form (17 forms across 13 rows), each ending in its test step: 7.2 s measured in a check JVM"
           :seon.test/long-ms 15000}
  plain-sci-rows-answer-as-plain-sci
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [[label code :as row] rows]
       (testing label
         (is (= (if (> (count row) 2) (nth row 2) (plain code))
                (last (mapv #(answered % agent-id running)
                            (if (string? code) [code] code))))))))))

(deftest a-test-of-an-earlier-pass-runs-the-redefined-callee
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

(deftest another-writer-on-the-cluster-head-neither-hides-nor-freezes-a-callee
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

(deftest an-inherited-caller-sees-a-same-pass-redefinition
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
  "The tests the step of `agent-id`'s pass over `code` ran red, proofs and reaching tests, with the last form's value;
  `:errored` is the red tests that ended in an error (no executable Var, a thrown body), not a failed assertion."
  {:malli/schema [:=> [:cat :string :string :string] [:map [:value :seon.schema/value] [:red [:set :symbol]] [:errored [:set :symbol]] [:passed? [:maybe :boolean]]]]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))
        items (map #(support/reply (get % "text")) (get result "content"))
        tests (some :seon.turn.loop/tests items)]
    {:value (:seon.repl/value (last (filter #(contains? % :seon.repl/value) items)))
     :passed? (get-in tests [:seon.turn.loop/reaching :seon.test/passed?])
     :red (into #{} (mapcat #(get-in tests [% :seon.turn.loop/red]))
                [:seon.turn.loop/proofs :seon.turn.loop/reaching])
     :errored (into #{} (mapcat #(get-in tests [% :seon.turn.loop/errored]))
                    [:seon.turn.loop/proofs :seon.turn.loop/reaching])}))

(deftest an-inherited-test-sees-the-branch-redefinition
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
       (testing "a later pass answers it from its green record, never main's definition"
         (let [{:keys [red passed?]} (step-red "(+ 1 1)" brancher running)]
           (is (not (contains? red 'scratch.inherited-test-test/g9-is-two)))
           (is (nil? passed?) "nothing changed, so the step executed nothing and shows nothing")))))))

(deftest an-inherited-test-sees-the-branch-redefinition-of-a-contracted-callee
  ;; The live shape: main's callee has a contract, so the branch's base arms
  ;; its own copy of it; main's test must still call the branch's definition.
  (support/with-running-agent
   (fn [running agent-id _]
     (let [brancher (str "branch-" agent-id)]
       (answered (str "(in-ns 'scratch.contracted) (clojure.core/refer-clojure)"
                      " (defn slug {:malli/schema [:=> [:cat :string] :string]} [title] \"\")")
                 agent-id running)
       (answered (str "(ns scratch.contracted-test (:require [clojure.test :refer [deftest is]]"
                      " [scratch.contracted :as c])) (deftest slug-lowers (is (= \"ab\" (c/slug \"AB\"))))")
                 agent-id running)
       (testing "the redefining pass runs main's test against the branch's definition"
         (let [{:keys [value red passed?]}
               (step-red (str "(in-ns 'scratch.contracted)"
                              " (defn slug {:malli/schema [:=> [:cat :string] :string]} [title]"
                              " (clojure.string/lower-case title)) (slug \"AB\")")
                         brancher running)]
           (is (= "ab" value))
           (is (not (contains? red 'scratch.contracted-test/slug-lowers)))
           (is (true? passed?) "the step ran the reaching tests green")))
       (testing "a later eval request in the test namespace evaluates the branch's definition and answers the test from its green record"
         (let [{:keys [value red passed?]} (step-red "(in-ns 'scratch.contracted-test) (c/slug \"AB\")" brancher running)]
           (is (= "ab" value))
           (is (not (contains? red 'scratch.contracted-test/slug-lowers)))
           (is (nil? passed?) "nothing changed, so the step executed nothing and shows nothing")))))))

(def ^:private branch-doc-line
  "A branch definition of the file function `seon.render.ns/first-doc-line`
  that every doc line reads as \"branch\"."
  (str "(in-ns 'seon.render.ns)"
       " (defn- first-doc-line {:malli/schema [:=> [:cat [:maybe :string]] :string]} [doc] \"branch\")"))

(def ^:private shown-doc-line
  "The doc line the file function `seon.render.ns/render-ai`, a compiled caller of `first-doc-line`, shows."
  (str "(:seon.ns/doc (seon.render.ns/render-ai {:seon.render/value {:seon.ns/name 'seon.example :seon.ns/doc \"One. Two.\"}"
       " :seon.db/db (seon.db/db) :seon.render/distance 0}))"))

(def ^:private descending-member-problem
  "A branch definition of the file function `seon.error/collection-member-problem`
  that descends into any collection, so a map given for `[:sequential :map]`
  is refused as one of its entries (its definition before 982a4e1ad)."
  (str "(in-ns 'seon.error)"
       " (defn- collection-member-problem {:malli/schema [:=> [:cat :map] [:or :nil :map]]} [problem]"
       " (let [schema (m/deref-all (:schema problem)) schema-type (m/type schema)"
       " child (when (#{:set :vector :sequential} schema-type) (first (m/children schema))) value (:value problem)]"
       " (when (and child (coll? value))"
       " (when-let [[member] (reduce (fn [_ member] (when-not (m/validate child member) (reduced [member]))) nil value)]"
       " {:schema child :value member ::collection-type schema-type}))))"))

(deftest a-file-test-reaching-a-change-only-through-a-compiled-caller-is-not-the-branchs-to-run
  ;; `seon.error-test/a-map-for-a-sequential-is-refused-as-the-map` reaches the
  ;; helper only through the compiled `seon.error/explain-problem`: on the branch
  ;; it would run the compiled behavior, so the merge's platform run proves it.
  (support/with-running-agent
   (fn [running agent-id _]
     (let [{:keys [red errored]} (step-red descending-member-problem (str "branch-" agent-id) running)]
       (is (not (contains? red 'seon.error-test/a-map-for-a-sequential-is-refused-as-the-map))
           "the step leaves it to the platform run")
       (is (= #{} errored) "no reaching test errors on the harness")))))

(def ^:private hub-spelling-change
  "`seon.db/as-of-key` with `AsOfDB` spelled fully qualified: equal behavior, another behavior digest."
  (str "(in-ns 'seon.db)"
       " (defn as-of-key {:malli/schema [:=> [:cat :seon.db/database-value]"
       " [:or :nil [:tuple :qualified-keyword :uuid [:or :int :inst]]]]} [database]"
       " (when (instance? datahike.db.AsOfDB database)"
       " (when-let [identity (datahike.db/committed-value-identity (dbi/-origin database))]"
       " [::as-of (:datahike.value/commit-id identity) (dbi/-time-point database)])))"))

(deftest ^{:seon.test/long "the running-agent fixture and two hub redefinitions on fresh branches, each running the hub's direct test"
           :seon.test/long-ms 20000}
  a-hub-change-runs-its-direct-test-on-the-branch-with-no-harness-error
  ;; A behavior change to `seon.db/as-of-key` bound its 2,345 src callers to
  ;; interpreted roots and 130 of 137 reaching tests errored on the harness
  ;; (`seon.test.runner/an-agents-reaching-tests-error-on-the-harness-after-a-hub-defn`).
  (support/with-running-agent
   (fn [running agent-id _]
     (let [direct 'seon.db-test/only-an-as-of-view-has-an-as-of-key]
       (testing "an equal behavior in other source runs the direct test green, no harness error"
         (let [{:keys [red errored passed?]} (step-red hub-spelling-change (str "spelling-" agent-id) running)]
           (is (= #{} red errored))
           (is (true? passed?))))
       (testing "a changed behavior is seen by the test calling the hub directly"
         (let [{:keys [red errored]} (step-red "(in-ns 'seon.db) (defn as-of-key {:malli/schema [:=> [:cat :seon.db/database-value] :nil]} [database] nil)"
                                               (str "nil-" agent-id) running)]
           (is (= #{direct} red) "the direct test fails its assertion against the branch's definition")
           (is (= #{} errored))))))))

(def ^:private equal-doc-line
  "A branch definition of the file function `seon.render.ns/first-doc-line`
  answering what the loaded one answers, in other source."
  (str "(in-ns 'seon.render.ns)"
       " (defn- first-doc-line {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]} [doc]"
       " (let [lines (->> (str/split-lines (or doc \"\")) (map str/trim) (drop-while str/blank?)"
       " (take-while (complement str/blank?)) (str/join \" \"))"
       " stop (str/index-of lines \". \")]"
       " (not-empty (if stop (subs lines 0 (inc stop)) lines))))"))

(deftest ^{:seon.test/long "the running-agent fixture (2.9 s) and one redefining eval request (2.8 s: test step 1.4 s running the 5 reaching tests, settle 0.5 s; seon.sci.eval/promoting-a-closure-allocates-megabytes-per-caller-and-runs-twice) measured 5.7 s in a check JVM"
           :seon.test/long-ms 12000}
  a-file-proof-aliasing-its-subject-runs-green-on-the-branch
  ;; `seon.render.ns-test` requires its subject as `sut` and tags its deftests
  ;; with tasks; the cluster base interns those tests, so the branch's
  ;; interpreted copy is the member context's own binding of the same Var.
  (support/with-running-agent
   (fn [running agent-id _]
     (let [{:keys [red passed?]} (step-red equal-doc-line (str "branch-" agent-id) running)]
       (is (not (contains? red 'seon.render.ns-test/a-doc-line-is-the-docstrings-first-sentence))
           "the file proof reaching it through compiled `render-ai` is not red on the branch")
       (is (not (false? passed?)) "no test reaching the redefinition ran red; a partial verdict claims no pass")))))

(deftest a-compiled-src-caller-runs-the-compiled-helper-on-a-branch
  ;; The loaded src program is the platform (owner, 2026-10-18): a branch's
  ;; change is seen by what the branch evaluates from source; the compiled
  ;; caller `seon.render.ns/render-ai` sees it only after merge and reload.
  (support/with-running-agent
   (fn [running agent-id _]
     (let [brancher (str "branch-" agent-id)]
       (testing "the branch's own call sees its definition"
         (is (= "branch" (answered (str branch-doc-line " (first-doc-line \"One. Two.\")") brancher running))))
       (testing "the compiled caller runs the compiled helper, in that eval request and the next"
         (is (= "One." (answered (str "(in-ns 'seon.render.ns) " shown-doc-line) brancher running)))
         (is (= "One." (answered shown-doc-line brancher running))))))))

(deftest a-require-closing-a-namespace-cycle-is-refused-and-the-agent-still-answers
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [requires #(set (:seon.ns/requires (db/pull @connection [:seon.ns/requires] [:seon.ns/name %])))]
       (answered "(in-ns 'scratch.cycle) (clojure.core/refer-clojure) (defn c1 [] 1)" agent-id running)
       (answered "(ns scratch.cycle-test (:require [scratch.cycle :as c])) (defn c2 [] (c/c1))" agent-id running)
       (testing "the require that closes the cycle is refused at its form and stores nothing"
         (is (= :after (answered "(in-ns 'scratch.cycle) (require 'scratch.cycle-test) :after" agent-id running)))
         (is (not (contains? (requires 'scratch.cycle) 'scratch.cycle-test))))
       (testing "the next eval request answers"
         (is (= 3 (answered "(+ 1 2)" agent-id running))))
       (testing "an agent acquiring a stored cycle still answers"
         (support/transacted! connection [[:db/add [:seon.ns/name 'scratch.cycle] :seon.ns/requires 'scratch.cycle-test]])
         (is (= 3 (answered "(+ 1 2)" (str "fresh-" agent-id) running))))))))
