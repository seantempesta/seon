(ns seon.error-evidence-test
  "A capped fault keeps its WHOLE evidence in the blob tier: the stored
  `data-edn` is only a presentation, and the occurrence's data blob holds the
  complete admitted value (`seon.error/evidence-stage`), on the turn path and
  on the core-fault path alike."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fault :as fault]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(def ^:private tail
  "A marker only the whole evidence carries: it sits past the evidence bound."
  "whole-evidence-tail-marker")

(defn- over-cap-message
  []
  (str (apply str (repeat 30000 "x")) tail))

(defn- occurrence-evidence
  "One occurrence's capped text and its blob's whole text, by `[attribute value]`."
  [connection [attribute value]]
  (let [database (db/db connection)
        occurrence (db/q '[:find (pull ?o [:seon.error/data-edn :seon.error/capped?
                                           {:seon.error.occurrence/data-blob
                                            [:seon.error.occurrence/blob-digest]}]) .
                           :in $ ?attribute ?value
                           :where [?e ?attribute ?value]
                                  [?e :seon.error/occurrences ?o]]
                         database attribute value)
        digest (get-in occurrence [:seon.error.occurrence/data-blob
                                   :seon.error.occurrence/blob-digest])]
    {:capped? (:seon.error/capped? occurrence)
     :inline (:seon.error/data-edn occurrence)
     :whole (when digest (str (blob/get connection digest)))}))

(deftest a-capped-turn-fault-is-recoverable-in-full-from-its-blob
  (support/with-database
   (fn [connection]
     ;; cluster-handle reads its dials from the named cluster's effective
     ;; config (4d163db4b); a member's world holds only "default" until seeded
     (support/seed-cluster! connection "evidence-turn")
     (let [operation 'seon.error-evidence-test/turn-boundary
           cluster (support/cluster-handle
                    {:seon.db/connection connection
                     :seon.cluster/name "evidence-turn"
                     :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                     :seon.db.process/id "evidence-turn-test"})
           started (System/nanoTime)]
       (turn/settle! {:seon.turn.loop/cluster cluster
                      :seon.turn.loop/now (java.util.Date.)
                      :seon.agent/id "evidence-agent"
                      :seon.error/value
                      {:seon.error/at (java.util.Date.)
                       :seon.error/layer ::turn
                       :seon.error/operation operation
                       :seon.error/message (over-cap-message)
                       :seon.turn/rule :seon.turn/agent-namespace-missing}})
       (let [elapsed-ms (/ (- (System/nanoTime) started) 1e6)
             {:keys [capped? inline whole]} (occurrence-evidence connection [:seon.error/operation operation])]
         (is (true? capped?))
         (is (not (str/includes? inline tail)) "the inline text is capped")
         (testing "the blob holds the whole evidence the cap dropped"
           (is (string? whole))
           (is (str/includes? (str whole) tail)))
         (is (< elapsed-ms 1000.0) (str "one turn fault settlement took " elapsed-ms " ms")))))))

(deftest a-capped-core-fault-is-recoverable-in-full-from-its-blob
  (support/with-database
   (fn [connection]
     (support/apply-config! connection "evidence-fault" {})
     (let [operation 'seon.error-evidence-test/core-boundary
           environment (env/environment
                        {:seon.boot/cluster-name "evidence-fault"
                         :seon.db/connection connection
                         :seon.sci.admit/caps (config/result-caps
                                               (config/effective (db/db connection) "evidence-fault"))})
           receipt (fault/fault! environment
                                 (ex-info "core boundary broke" {::evidence (over-cap-message)})
                                 {:seon.error/layer ::core
                                  :seon.error/operation operation
                                  :seon.db.process/id "evidence-fault-test"})
           {:keys [capped? inline whole]} (occurrence-evidence
                                           connection [:seon.error/signature (:seon.error/signature receipt)])]
       (is (true? (:seon.fault/committed? receipt)))
       (is (true? capped?))
       (is (not (str/includes? inline tail)) "the inline text is capped")
       (is (str/includes? (str whole) tail) "the blob holds the whole evidence")))))
