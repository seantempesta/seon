(ns seon.turn.request-view-test
  "A later form of an eval request reads the request's own definitions: it unmaps a function an earlier form
  defined, and a test it runs executes the definition the request made."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- eval-request
  "The parsed MCP result of `code` evaluated as `agent-id` in the running cluster `running`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :map]}
  [running agent-id code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"code" code "agent" agent-id "cluster" running}}))))

(defn- last-value
  "The value of the last form an MCP result shows."
  {:malli/schema [:=> [:cat :map] :seon.schema/value]}
  [result]
  (->> (get result "content")
       (keep #(let [item (support/reply (get % "text"))]
                (when (and (map? item) (contains? item :seon.repl/value)) [(:seon.repl/value item)])))
       last
       first))

(deftest ^{:seon.test/long "Two eval requests of an own-branch agent, each with its automatic test step: about 1 s each measured on default's JVM (agent request-view, 2026-10-14)."
           :seon.test/long-ms 15000}
  a-function-defined-earlier-in-the-request-is-unmapped-by-a-later-form
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "request-view-unmap-" (random-uuid))
           _ (support/own-branch-agent! running agent-id "(ns rv.unmap)")
           answer (last-value
                   (eval-request running agent-id
                                 (str "(in-ns 'rv.unmap)\n"
                                      "(defn scratch \"Returns x.\" {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
                                      "(:seon.program/subject (my.program/ns-unmap! 'rv.unmap/scratch))")))
           after (last-value
                  (eval-request running agent-id
                                "[(some? (resolve 'rv.unmap/scratch)) (seon.db/q '[:find ?e . :where [?e :seon.fn/sym rv.unmap/scratch]] (seon.db/db))]"))]
       (is (= 'rv.unmap/scratch answer) "the later form's unmap names the function the earlier form defined")
       (is (= [false nil] after) "after the request neither the Var nor the declaration remains")))))

(def ^:private green-reaches
  "The query of how many green members f-is-inc has recorded."
  (str "(seon.db/q '[:find (count ?m) . :where [?m :seon.test.member/symbol rv.rerun/f-is-inc]"
       " [?m :seon.test.member/fail-count 0] [?m :seon.test.member/error-count 0]] (seon.db/db))"))

(deftest ^{:seon.test/long "Three eval requests of an own-branch agent, each with its automatic test step: about 1 s each measured on default's JVM (agent request-view, 2026-10-14)."
           :seon.test/long-ms 15000}
  a-test-rerun-after-a-defn-in-the-same-request-records-no-green-for-the-replaced-definition
  ;; f-is-inc is green for the first f; the request replaces f with one that makes it red, then reruns it.
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "request-view-rerun-" (random-uuid))
           _ (support/own-branch-agent!
              running agent-id
              (str "(ns rv.rerun (:require [clojure.test :refer [deftest is]]))\n"
                   "(defn f \"Returns x plus one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"
                   "(deftest f-is-inc (is (= 2 (f 1))))"))
           before (last-value (eval-request running agent-id green-reaches))
           passed? (last-value
                    (eval-request running agent-id
                                  (str "(in-ns 'rv.rerun)\n"
                                       "(defn f \"Returns x.\" {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
                                       "(:seon.test/passed? (my.test/check {:seon.test/identities #{'rv.rerun/f-is-inc} :seon.test/rerun? true}))")))
           after (last-value (eval-request running agent-id green-reaches))]
       (is (false? passed?) "the in-request rerun ran the definition the request made")
       (is (pos-int? before) "the first f's green is recorded")
       (is (= before after) "the request recorded no green member: none for the replaced definition")))))

(deftest ^{:seon.test/long "One eval request of an own-branch agent with its automatic test step: about 1 s measured on default's JVM (agent rvland-probe, 2026-10-14)."
           :seon.test/long-ms 15000}
  a-schema-registered-earlier-in-the-request-is-found-by-a-later-form
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "request-view-schema-" (random-uuid))
           _ (support/own-branch-agent! running agent-id "(ns rv.schema)")
           answer (last-value
                   (eval-request running agent-id
                                 (str "(seon.schema/register! :rv.order/qty :int)\n"
                                      "(:key (my.program/source :rv.order/qty))")))]
       (is (= :rv.order/qty answer) "the later form's source finds the key the earlier form registered")))))
