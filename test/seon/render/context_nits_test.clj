(ns seon.render.context-nits-test
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.note :as note]
            [seon.render.transcript :as transcript]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(deftest generated-runtime-and-notes-reads-return-useful-values
  (support/with-database
   (fn [connection]
     (is (:db-after
          (db/transact! connection
            [{:seon.agent/id "root"}
             {:seon.agent/id "juniper"}
             {:seon.message/id "trigger" :seon.message/content "Read the orders."
              :seon.message/from [:seon.agent/id "root"]
              :seon.message/to [:seon.agent/id "juniper"]}
             {:seon.agent/id "juniper"
              :seon.agent/runtime
              {:seon.runtime/agent [:seon.agent/id "juniper"]
               :seon.runtime/trigger [:seon.message/id "trigger"]}}])))
     (let [ctx (support/fork-cluster-ctx connection)
           run (fn [source]
                 (evaluation/evaluate
                  {:seon.sci.eval/ctx ctx :seon.cluster.eval/source source
                   :seon.sci.admit/caps (config/result-caps config/defaults)
                   :seon.sci.eval/time-limit-ms 10000
                   :seon.config/on-core-error :panic}))
           runtime-source (transcript/render-runtime-ai
                           {:seon.db/db @connection
                            :seon.render/value
                            (db/pull @connection '[*]
                                     [:seon.runtime/agent [:seon.agent/id "juniper"]])})
           notes-source (note/render-notes-ai {:seon.agent/id "juniper"})
           runtime (run runtime-source)
           notes (run notes-source)]
       (is (nil? (:seon.cluster.eval/error runtime)) (pr-str runtime))
       (is (= {:seon.message/id "trigger" :seon.message/content "Read the orders."
               :seon.message/from {:seon.agent/id "root"}}
              (get-in runtime [:seon.sci.admit/value :seon.agent/runtime :seon.runtime/trigger])))
       (is (= [] (:seon.sci.admit/value notes)) (pr-str notes))
       (doseq [[label source result] [[:runtime runtime-source runtime] [:notes notes-source notes]]]
         (println "CONTEXT-NITS" label (pr-str source)
                  (pr-str (:seon.sci.admit/value result))
                  :shown (pr-str (:seon.eval/shown result))))
       (is (:db-after (db/transact! connection
                       [{:my.note/id "first" :my.note/content "Keep this."
                         :my.note/agent [:seon.agent/id "juniper"]}])))
       (is (= [{:my.note/id "first" :my.note/content "Keep this."}]
              (:seon.sci.admit/value (run notes-source))))))))
