(ns seon.render.page-review-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.render.transcript :as transcript]
            [seon.render.web :as web]
            [seon.test-support :as support]))

(deftest turn-headers-and-all-evaluations-have-one-place-each
  (support/with-database
   (fn [connection]
     (let [written
           (db/transact! connection
             [{:seon.agent/id "page"}
              {:seon.ns/name 'my.agents.page}
              {:seon.turn/id "old" :seon.turn/agent [:seon.agent/id "page"] :seon.turn/opened-tx "datomic.tx" :seon.turn/reply "Earlier reply"}
              {:seon.cluster.eval/id "old-eval"
               :seon.cluster.eval/at (java.util.Date. 0)
               :seon.cluster.eval/run [:seon.turn/id "old"]
               :seon.cluster.eval/ordinal 0
               :seon.cluster.eval/source "(+ 1 1)" :seon.eval/shown "2"}])]
       (is (:db-after written) (pr-str written)))
     (let [written
           (db/transact! connection
             [{:seon.turn/id "current" :seon.turn/agent [:seon.agent/id "page"] :seon.turn/opened-tx "datomic.tx" :seon.turn/reply "Current reply"}
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
                       :seon.turn/opened-tx
                       (get-in (db/pull @connection '[{:seon.turn/opened-tx [:db/id]}]
                                        [:seon.turn/id "old"])
                               [:seon.turn/opened-tx :db/id])}]))
         "An older opening can commit after the current turn.")
     (is (:db-after (db/transact! connection
                     [{:seon.agent/id "page"
                       :seon.agent/runtime
                       {:seon.runtime/agent [:seon.agent/id "page"]
                        :seon.runtime/turns (mapv #(vector :seon.turn/id %) ["old" "current" "late-old"])}}
                      [:db/retract [:seon.turn/id "current"] :seon.turn/agent [:seon.agent/id "page"]]])))
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           evaluations (evaluation/of-agent database "page")
           turns (get-in (db/pull database '[{:seon.agent/runtime [{:seon.runtime/turns [*]}]}]
                                 [:seon.agent/id "page"])
                         [:seon.agent/runtime :seon.runtime/turns])
           unit (assoc (db/pull database '[*] [:seon.turn/id "current"])
                       :seon.db/db database :seon.sci.eval/ctx ctx)
           headers (pr-str (transcript/render-history-html turns database))
           html (#'web/debug-ai-html "page"
                  {:seon.render.debug/request {}
                   :seon.render.debug/evaluations evaluations})]
       (is (= ["old-eval" "current-a" "current-b"] (mapv :seon.cluster.eval/id evaluations)))
       (is (= "" (transcript/render-history-ai turns database)))
       (is (= "" (transcript/render-run-ai unit)))
       (let [linked (db/transact! connection
                      [{:seon.agent/id "page"
                        :seon.agent/runtime
                        {:seon.runtime/agent [:seon.agent/id "page"]
                         :seon.runtime/turns (mapv #(vector :seon.turn/id %) ["old" "current" "late-old"])}}])
             runtime-unit {:seon.db/db @connection
                           :seon.render/value (db/pull @connection '[*] [:seon.runtime/agent [:seon.agent/id "page"]])}
             runtime-html (pr-str (transcript/render-runtime-html runtime-unit))]
         (is (:db-after linked))
         (is (str/includes? (transcript/render-runtime-ai runtime-unit) "[:seon.agent/id \"page\"]"))
         (is (:seon.error/kind (transcript/render-runtime-ai {:seon.db/db @connection})))
         (is (str/includes? runtime-html "Current reply"))
         (is (str/includes? runtime-html "Turns (3)"))
         (is (not (str/includes? runtime-html "(+ 2 2)"))))
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
       (is (str/includes? html "(+ 1 1)"))
       (is (not (str/includes? html "seon-value-"))
           "Evaluations use their declared pair, not repeated floor wrappers with one root id."))
     (is (:db-after (db/transact! connection
                     [{:seon.turn/id "empty" :seon.turn/agent [:seon.agent/id "page"] :seon.turn/opened-tx "datomic.tx"}
                      {:seon.runtime/agent [:seon.agent/id "page"]
                       :seon.runtime/turns [[:seon.turn/id "empty"]]}])))
     (is (= ["old-eval" "current-a" "current-b"]
            (mapv :seon.cluster.eval/id (evaluation/of-agent @connection "page"))))
     (is (:seon.error/kind (evaluation/of-agent @connection "absent"))))))
