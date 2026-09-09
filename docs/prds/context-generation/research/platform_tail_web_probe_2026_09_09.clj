(ns platform-tail-web-probe-2026-09-09
  (:require [clojure.edn :as edn]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.operator.runtime :as runtime]
            [seon.render.web :as web]
            [seon.schema :as schema]))

; Load through MCP JVM mode on the platform-tail scratch root only.
(defn seed!
  "Seed Juniper without provider calls on the owned scratch cluster."
  []
  (let [instance (get @runtime/running-instances "platform-tail")
        handle (:seon.turn.loop/cluster instance)
        connection (:seon.db/connection handle)]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (assert (true? (:seon.config.ai/no-provider
                      (config/effective @connection "platform-tail"))))
       (load-file "docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj")
       (let [result ((resolve 'juniper-fixture-2026-09-06/install!) "platform-tail")]
         (assert (not (:seon.error/kind result)))
         {:seon.test/no-provider
          (db/q '[:find ?value . :where
                  [?agent :seon.cluster.agent/id "juniper"]
                  [?agent :seon.agent/settings ?settings]
                  [?settings :seon.config.ai/no-provider ?value]] @connection)
          :seon.test/provider-attempts
          (count (db/q '[:find [?a ...] :where [?a :seon.ai.attempt/id]] @connection))})))))

(defn rebind!
  "Rebind the scratch listener and read the advertisement before returning."
  []
  (let [instance (get @runtime/running-instances "platform-tail")
        handle (:seon.turn.loop/cluster instance)
        connection (:seon.db/connection handle)]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [before (:seon.boot/advertisement instance)
             _ (web/stop! (:seon.render.web/served instance))
             rebound (#'cluster/serve!
                      instance (assoc (config/effective @connection "platform-tail")
                                      :seon.config.web/port 0))
             file (:seon.boot/advertisement-file
                   (cluster/cluster-paths
                    (get-in instance [:seon.boot/config :seon.boot/root])
                    "platform-tail"))
             advertisement-text (slurp file)
             advertised (edn/read-string advertisement-text)
             served (:seon.render.web/served rebound)]
         (swap! runtime/running-instances assoc "platform-tail" rebound)
         (assert (= advertised (:seon.boot/advertisement rebound)))
         (assert (= (select-keys served [:seon.render.web/url :seon.render.web/port])
                    (select-keys advertised [:seon.render.web/url :seon.render.web/port])))
         {:seon.test/before before
          :seon.test/after advertised
          :seon.test/advertisement-bytes advertisement-text
          :seon.test/same-prepl (= (:seon.boot/prepl-port before)
                                  (:seon.boot/prepl-port advertised))})))))
