(ns seon.error-recording-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest root-first-turn-records-its-producers-declaration
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "error-recording")
     (cluster/seed-root-agent!
      connection "error-recording" cluster/boot-process-identity)
     (let [run-id (db/q '[:find ?id . :where
                         [?agent :seon.agent/id "root"]
                         [?turn :seon.turn/agent ?agent]
                         [?turn :seon.turn/id ?id]] (db/db connection))
           handle (support/cluster-handle
                   {:seon.db/connection connection
                    :seon.cluster/name "error-recording"
                    :seon.db.process/id cluster/boot-process-identity
                    :seon.env/environment (support/environment "error-recording" connection)
                    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)})]
       (try
         (is (string? run-id) "The production root seed created its first turn.")
         (let [result
               (with-redefs-fn
                 {#'turn/declared-sources
                  (fn [& _] (throw (ex-info "First opening source failed." {})))}
                 #(turn/turn
                   {:seon.turn.loop/cluster handle
                    :seon.turn.work/next {:seon.turn.work/situation :generate
                                          :seon.agent/id "root"
                                          :seon.turn/id run-id}}
                   (java.util.Date.)))
               database (db/db connection)
               declarations
               (db/q '[:find [?name ...] :in $ ?run-id :where
                       [?turn :seon.turn/id ?run-id]
                       [?occurrence :seon.error.occurrence/turn ?turn]
                       [?error :seon.error/occurrences ?occurrence]
                       [?error :seon.error/declared-schema ?name]] database run-id)]
           (is (= :error (:seon.turn.loop/outcome result)) (pr-str result))
           (is (= [:seon.turn.loop/phase-failed-error] declarations))
           (is (some? (:seon.turn/closed-tx
                       (db/pull database [:seon.turn/closed-tx]
                                [:seon.turn/id run-id])))))
         (finally
           (doseq [channel-key [:seon.cluster.wake/channel :seon.render/context-channel
                        :seon.turn.loop/completion]]
             (async/close! (get handle channel-key)))))))))
