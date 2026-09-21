(ns seon.error-result-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [malli.registry :as mr]
            [sci.core :as sci]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.id :as id]
            [seon.render :as render]
            [seon.render.value :as render.value]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest a-canonical-result-declarations-are-installed
  (support/with-database
   (fn [connection]
     (let [projection (db/carried-projection (db/db connection))
           registry (:seon.schema.projection/registry projection)]
       (doseq [attribute [:seon.error/result-id :seon.error/shown]]
         (is (some? (mr/schema registry attribute))
             (str "Canonical fixture's compiled registry is missing " attribute)))))))

(defn- request [connection source]
  (let [dials config/defaults]
    {:seon.error/source source
     :seon.error/id (id/id)
     :seon.error/at (java.util.Date.)
     :seon.error/process "error-result-test"
     :seon.schema/projection (db/carried-projection (db/db connection))
     :seon.sci.admit/caps (config/result-caps dials)
     :seon.config.error/max-evidence-bytes (:seon.config.error/max-evidence-bytes dials)
     :seon.config.error/recurrence-limit (:seon.config.error/recurrence-limit dials)
     :seon.db/connection connection
     :seon.render/profile (render/agent-render-profile dials)}))

(defn- observation [value]
  {:seon.error/at (java.util.Date.)
   :seon.error/layer ::recording
   :seon.error/operation 'seon.error-result-test/observation
   :seon.error/message "A recorded observation."
   :seon.error/offending value})

(defn- measured [stage f]
  (let [started (System/nanoTime)]
    (try
      (f)
      (finally
        (println "ERROR-RESULT-TIMING" stage
                 (/ (- (System/nanoTime) started) 1e6) "ms")))))

(defn- verify-stored! [connection request value]
  (let [started (System/nanoTime)
        recording (measured :recording #(error/recording (db/db connection) request))
        report (measured :transaction #(support/transacted! connection (:seon.db/tx-data recording)))
        elapsed-ms (/ (- (System/nanoTime) started) 1e6)
        projection (:seon.schema/projection request)
        row (db/pull (:db-after report) (error/observation-selector projection)
                     (:seon.error/ref recording))
        stored (error/latest-fact row)
        result-id (:seon.error/result-id stored)
        unit {:seon.render/value value
              :seon.repl/handle (admit/result-handle result-id)
              :seon.render.call/id (admit/result-handle result-id)
              :seon.schema/projection projection
              :seon.render/profile (:seon.render/profile request)}
        complete (render.value/render-ai-data (render.value/prepare unit :seon.render/html))
        shown (render.value/render-ai-data (render.value/prepare unit))]
    (is (< elapsed-ms 1000) (str "error recording and transaction: " elapsed-ms " ms"))
    (is (string? result-id))
    (is (= complete (blob/get connection (:seon.error/data-blob stored))))
    (is (= shown (:seon.error/shown stored)))
    (is (schema/valid-candidate-value? projection :seon.error/base stored))
    (is (str/includes? (error/render-ai {:seon.render/value row}) shown))
    (is (:seon.error/data-edn stored) "Legacy evidence remains written during accretion.")
    {:stored stored :complete complete :shown shown}))

(deftest core-results-use-the-printer-for-arbitrary-objects
  (support/with-database
   (fn [connection]
     (let [value [(atom 42) identity (Object.)]]
       (verify-stored! connection (request connection (observation value)) value)))))

(deftest large-result-keeps-complete-and-profile-capped-printer-text
  (support/with-database
   (fn [connection]
     (let [value (mapv (fn [n] {:error-result-test/n n :error-result-test/items (vec (range 20))}) (range 20))
           request (-> (request connection (observation value))
                       (assoc-in [:seon.render/profile :seon.render.profile/max-children] 2))
           {:keys [complete shown]} (verify-stored! connection request value)]
       (is (not= complete shown))
       (is (< (count shown) (count complete)))))))

(deftest ^{:seon.test/long
           "First real SCI acquisition loads the canonical program namespaces and installs their declarations; subsequent agent forks reuse that context. The result writer retains its separate one-second assertion."
           :seon.test/long-ms 60000}
  agent-contract-refusal-retains-its-live-offending-result
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "error-result")
     (support/transacted! connection [{:seon.agent/id "error-result-agent"}])
     (support/transacted! connection
                         (turn/open-tx {:seon.turn/id "error-result-turn"
                                        :seon.turn/agent [:seon.agent/id "error-result-agent"]
                                        :seon.turn/opened-tx "datomic.tx"}))
     (let [source "(seon.id/symbol-in 42 \\e \"id\")"
           _ (support/transacted! connection
                                  (turn/receipt-start-tx
                                   {:seon.turn/id "error-result-turn"
                                    :seon.cluster.eval/ordinal 0
                                    :seon.cluster.eval/at (java.util.Date.)
                                    :seon.cluster.eval/source source}))
           base (measured :cluster-context #(support/fork-cluster-ctx connection "error-result"))
           ctx (:seon.sci.eval/ctx
                (measured :agent-context #(sci.eval/fork-for-turn {:seon.sci.eval/ctx base
                                         :seon.db/db (db/db connection)
                                         :seon.agent/id "error-result-agent"})))
           dials config/defaults
           profile (render/agent-render-profile dials)
           evaluation (sci.eval/evaluate
                       {:seon.cluster.eval/source source
                        :seon.sci.eval/ctx ctx
                        :seon.agent/id "error-result-agent"
                        :seon.turn/id "error-result-turn"
                        :seon.cluster.eval/ordinal 0
                        :seon.sci.admit/caps (config/result-caps dials)
                        :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms dials)
                        :seon.config/on-core-error (:seon.config/on-core-error dials)
                        :seon.render/profile profile})
           value (:seon.sci.admit/value evaluation)
           result-id (:seon.error/result-id value)
           handle (when result-id (admit/result-handle result-id))]
       (is result-id (pr-str value))
       (when handle
         (is (= 42 @(sci/resolve ctx handle)))
         (let [{:keys [shown]}
               (verify-stored! connection (assoc (request connection value)
                                                :seon.agent/id "error-result-agent"
                                                :seon.turn/id "error-result-turn") 42)]
           (support/transacted! connection
                                (turn/receipt-settle-tx
                                 {:seon.turn/id "error-result-turn"
                                  :seon.cluster.eval/ordinal 0
                                  :seon.eval/shown (:seon.eval/shown evaluation)
                                  :seon.cluster.eval/error (:seon.cluster.eval/error evaluation)}))
           (let [history (walk/history {:seon.db/db (db/db connection)
                                        :seon.sci.eval/ctx ctx
                                        :seon.sci.admit/caps (config/result-caps dials)
                                        :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms dials)
                                        :seon.config/on-core-error (:seon.config/on-core-error dials)
                                        :seon.schema/projection (db/carried-projection (db/db connection))
                                        :seon.render/profile profile
                                        :seon.render.walk/lookup [:seon.agent/id "error-result-agent"]})]
             (is (= 1 (count history)))
             (is (str/includes? (:seon.render.history/bytes (first history)) shown)))))))))
