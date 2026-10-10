(ns seon.sci.lazy-member-test
  "A value definition in the closure of an agent's change is interpreted when acquired, never bound to a lazy function root."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- agent-value
  "The value of the last form of `code` evaluated as `agent-id` through `seon.mcp/call`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :seon.schema/value]}
  [running agent-id code]
  (->> (get (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                       "arguments" {"code" code "agent" agent-id "cluster" running}})))
            "content")
       (keep #(let [item (support/reply (get % "text"))]
                (when (and (map? item) (contains? item :seon.repl/value)) [(:seon.repl/value item)])))
       last
       first))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-value-definition-in-a-changes-closure-is-bound-to-a-lazy-function}
           :seon.test/long "Two eval requests of an own-branch agent, each with its automatic test step, and one checked file test: about 4 s, as seon.test.interpreted-fixture-test's."
           :seon.test/long-ms 20000}
  a-value-definition-reaching-a-changed-function-is-its-value
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "lazy-value-" (random-uuid))
           ;; seon.problems/families, a vector of the family Vars, reaches the changed function
           _ (support/own-branch-agent!
              running agent-id
              (str "(in-ns 'seon.problems)\n"
                   "(defn- files-without-namespace\n"
                   "  \"Analyzed Clojure files that publish no namespace.\"\n"
                   "  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :seon.problems/file-without-namespace]]}\n"
                   "  [database]\n"
                   "  (->> (db/q '[:find [?path ...]\n"
                   "               :where [?file :seon.fn.file/relative-root _]\n"
                   "                      [?file :seon.fn.file/relative-path ?path]\n"
                   "                      (not [?namespace :seon.fn/file ?file]\n"
                   "                           [?namespace :seon.ns/name _])]\n"
                   "             database)\n"
                   "       (filter #(or (str/ends-with? % \".clj\") (str/ends-with? % \".cljc\")))\n"
                   "       sort\n"
                   "       (mapv (fn [path] {:seon.fn.file/relative-path path}))))"))
           checked (agent-value running agent-id
                                "(select-keys (my.test/check {:seon.test/identities #{'seon.problems-test/an-error-two-agents-hit-is-one-problem-with-two-agents} :seon.test/rerun? true}) [:seon.test/passed? :seon.test/executed-count])")]
       (is (= 1 (:seon.test/executed-count checked)))
       (is (true? (:seon.test/passed? checked)) "the test reads seon.problems/families as its vector")))))
