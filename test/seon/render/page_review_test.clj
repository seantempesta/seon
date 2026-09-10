(ns seon.render.page-review-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.render.transcript :as transcript]
            [seon.render.web :as web]
            [seon.test-support :as support]))

(deftest turn-headers-and-current-evaluations-have-one-place-each
  (support/with-database
   (fn [connection]
     (let [written
           (db/transact! connection
             [{:seon.agent/id "page"}
              {:seon.ns/name 'my.agents.page}
              {:seon.turn/id "old" :seon.turn/agent [:seon.agent/id "page"]
               :seon.turn/opened-tx (java.util.Date. 0)
               :seon.turn/reply "Earlier reply"}
              {:seon.cluster.eval/id "old-eval"
               :seon.cluster.eval/at (java.util.Date. 0)
               :seon.cluster.eval/run [:seon.turn/id "old"]
               :seon.cluster.eval/ordinal 0
               :seon.cluster.eval/source "(+ 1 1)" :seon.eval/shown "2"}])]
       (is (:db-after written) (pr-str written)))
     (let [written
           (db/transact! connection
             [{:seon.turn/id "current" :seon.turn/agent [:seon.agent/id "page"]
               :seon.turn/opened-tx (java.util.Date. 1)
               :seon.turn/reply "Current reply"}
              {:seon.cluster.eval/id "current-a"
               :seon.cluster.eval/at (java.util.Date. 1)
               :seon.cluster.eval/run [:seon.turn/id "current"]
               :seon.cluster.eval/ordinal 0
               :seon.cluster.eval/ns [:seon.ns/name 'my.agents.page]
               :seon.cluster.eval/source "(+ 2 2)" :seon.eval/shown "4"}
              {:seon.cluster.eval/id "current-b"
               :seon.cluster.eval/at (java.util.Date. 1)
               :seon.cluster.eval/run [:seon.turn/id "current"]
               :seon.cluster.eval/ordinal 1
               :seon.cluster.eval/ns [:seon.ns/name 'my.agents.page]
               :seon.cluster.eval/source "(+ 3 3)" :seon.eval/shown "6"}])]
       (is (:db-after written) (pr-str written)))
     (is (:db-after (db/transact! connection
                     [{:seon.turn/id "late-old" :seon.turn/agent [:seon.agent/id "page"]
                       :seon.turn/opened-tx (java.util.Date. -1)}]))
         "An older opening can commit after the current turn.")
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           evaluations (#'web/current-turn-evaluations database "page")
           turns (:seon.turn/_agent
                  (db/pull database '[{:seon.turn/_agent [*]}] [:seon.agent/id "page"]))
           unit (assoc (db/pull database '[*] [:seon.turn/id "current"])
                       :seon.db/db database :seon.sci.eval/ctx ctx)
           headers (pr-str (transcript/render-history-html turns database))
           html (#'web/debug-ai-html "page"
                  {:seon.render.debug/request {}
                   :seon.render.debug/evaluations evaluations})]
       (is (= ["current-a" "current-b"] (mapv :seon.cluster.eval/id evaluations)))
       (is (= "" (transcript/render-history-ai turns database)))
       (is (= "" (transcript/render-run-ai unit)))
       (is (str/includes? headers "Current reply"))
       (is (str/includes? headers "Opened"))
       (is (str/includes? headers "Trigger"))
       (is (str/includes? headers "Evaluations"))
       (is (str/includes? headers "[:dd \"2\"]"))
       (is (str/includes? headers "[:dd \"0\"]"))
       (is (not (str/includes? headers "(+ 2 2)")))
       (is (not (str/includes? headers "Run ")))
       (is (str/includes? html "Context now"))
       (is (str/includes? html "(+ 2 2)"))
       (is (str/includes? html "(+ 3 3)"))
       (is (not (str/includes? html "(+ 1 1)")))
       (is (not (str/includes? html "seon-value-"))
           "Evaluations use their declared pair, not repeated floor wrappers with one root id."))
     (is (:db-after (db/transact! connection
                     [{:seon.turn/id "empty" :seon.turn/agent [:seon.agent/id "page"]
                       :seon.turn/opened-tx (java.util.Date. 2)}])))
     (is (= [] (#'web/current-turn-evaluations @connection "page")))
     (is (:seon.error/kind (#'web/current-turn-evaluations @connection "absent"))))))
