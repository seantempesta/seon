(ns seon.cluster.curate
  "Mechanical proof and append-only adopt for editor-authored revisions.

  An editor supplies a revision as ordered form sources. `prove!` replays it
  without a model call on a fresh database branch at the candidate span's
  opening commit. `adopt!` appends only an accepted proof's run and receipts;
  the superseded history remains queryable and is never rewritten."
  (:require [clojure.string :as str]
            [seon.cluster.loop :as loop]
            [seon.cluster.registry :as registry]
            [seon.cluster.run :as run]
            [seon.cluster.store :as store]
            [seon.cluster.work :as work]
            [seon.db :as db]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.eval :as sci.eval])
  (:import [java.util Date UUID]))

(schema.edn/load! {})

(defn- digest-value [value]
  (schema/sha-256 [(.getBytes (pr-str value) "UTF-8")]))

(defn- error-value [predicate message data]
  {:seon.error/kind ::proof-failed
   :seon.error/message message
   :seon.error/data data
   ::predicate predicate})

(defn- candidate-span [database run-ids]
  (let [runs (mapv #(db/pull
                     database
                     [:seon.cluster.run/id
                      :seon.cluster.run/opening-commit-id
                      :seon.cluster.run/closed-at
                      {:seon.cluster.run/agent [:seon.cluster.agent/id]}
                      {:seon.cluster.run/starting-ns [:seon.ns/name]}]
                     [:seon.cluster.run/id %])
                   run-ids)
        agent-ids (into #{} (map #(get-in % [:seon.cluster.run/agent
                                              :seon.cluster.agent/id])) runs)]
    (cond
      (some #(nil? (:seon.cluster.run/id %)) runs)
      (error-value ::candidate-span "A candidate run is absent."
                   {::run-ids run-ids})

      (not= 1 (count agent-ids))
      (error-value ::candidate-span "Candidate runs have different agents."
                   {::run-ids run-ids})

      (some #(nil? (:seon.cluster.run/closed-at %)) runs)
      (error-value ::candidate-span "Every candidate run must be closed."
                   {::run-ids run-ids})

      (nil? (:seon.cluster.run/opening-commit-id (first runs)))
      (error-value ::candidate-span "The span has no opening commit ID."
                   {::run-ids run-ids})

      :else
      {:seon.cluster.agent/id (first agent-ids)
       :seon.cluster.run/opening-commit-id
       (:seon.cluster.run/opening-commit-id (first runs))
       :seon.cluster.run/starting-ns
       [:seon.ns/name
        (get-in (first runs) [:seon.cluster.run/starting-ns :seon.ns/name])]})))

(def ^:private identity-source-pairs
  (mapv (fn [identity]
          [identity (:seon.program/source-attribute (program/shape identity))])
        program/identity-attributes))

(defn- declarations [database run-ids]
  (if (seq run-ids)
    (->> (db/q
          '[:find ?run-id ?ordinal ?identity ?identity-value ?source-attr ?source
            :in $ [?run-id ...] [[?identity ?source-attr] ...]
            :where
            [?run :seon.cluster.run/id ?run-id]
            [?receipt :seon.cluster.eval/run ?run]
            [?receipt :seon.cluster.eval/ordinal ?ordinal]
            (or-join [?receipt ?tx]
                     [?receipt :seon.cluster.eval/result-edn _ ?tx true]
                     [?receipt :seon.cluster.eval/error _ ?tx true]
                     [?receipt :seon.cluster.eval/interrupted-at _ ?tx true])
            [?entity ?identity ?identity-value ?tx true]
            [?entity ?source-attr ?source ?tx true]]
          (db/history database) run-ids identity-source-pairs)
         (sort-by (juxt first second #(str (nth % 2)) #(pr-str (nth % 3))))
         (mapv (fn [[run-id ordinal identity identity-value
                     source-attribute source]]
                 {:seon.cluster.run/id run-id
                  :seon.cluster.eval/ordinal ordinal
                  :seon.program/identity [identity identity-value]
                  :seon.program/source-attribute source-attribute
                  :seon.program/source source})))
    []))

(defn- drive-seam [symbol]
  (requiring-resolve (symbol "seon.eval.drive" (name symbol))))

(defn- terminal-state [database agent-id process run-ids]
  ((drive-seam 'terminal-state)
   database agent-id process
   {:seon.eval.drive/run-ids run-ids
    :seon.eval.drive/run-cap (max 1 (count run-ids))}))

(defn- run-receipts [database run-ids]
  ((drive-seam 'run-receipts) database run-ids))

(defn- completed-result [receipts]
  ((drive-seam 'completed-result) receipts))

(defn- execute-revision! [connection cluster agent-id process]
  (loop []
    (when-let [next-work
               (work/next-agent-work
                @connection
                {:seon.cluster.agent/id agent-id
                 :seon.cluster.run/process process})]
      (loop/turn {:seon.cluster.loop/cluster cluster
                  :seon.cluster.work/next next-work}
                 (Date.))
      (recur))))

(defn- retire-proof! [store-value branch]
  (registry/retire-branch! {:seon.store/store store-value
                            :seon.store/branch branch}))

(defn prove!
  "Prove an editor's revision at the superseded span's opening commit.

  Success returns receipts, terminal state, and declared content as data.
  Failure returns one flat error naming the first failed acceptance predicate."
  {:malli/schema [:=> [:cat :seon.cluster.curate/proof-request]
                  [:or :seon.cluster.curate/proof :seon.error/value]]}
  [{instance :seon.boot/instance
    run-ids ::run-ids
    revision ::revision}]
  (let [live-connection (:seon.boot/cluster-connection instance)
        live-db @live-connection
        span (candidate-span live-db run-ids)]
    (if (:seon.error/kind span)
      span
      (let [store-value (:seon.store/store instance)
            branch (keyword (str "curation-proof-" (UUID/randomUUID)))
            proof-run-id (str "curated:" (UUID/randomUUID))
            process (str "curation-proof:" (UUID/randomUUID))
            agent-id (:seon.cluster.agent/id span)
            starting-ns (:seon.cluster.run/starting-ns span)
            plan-digest (digest-value revision)]
        (try
          (registry/branch! {:seon.store/store store-value
                             :seon.cluster.registry/from
                             (:seon.cluster.run/opening-commit-id span)
                             :seon.store/branch branch})
          (let [connection (store/open-branch! store-value branch)]
            (try
              (let [ctx (sci.eval/cluster-ctx @connection connection)
                    cluster (assoc (:seon.cluster.loop/cluster instance)
                                   :seon.store/branch-connection connection
                                   :seon.sci.eval/ctx ctx
                                   :seon.cluster.run/process process)
                    request {:seon.cluster.agent/id agent-id
                             :seon.cluster.run/id proof-run-id
                             :seon.cluster.run/process process
                             :seon.cluster.run/opened-at (Date.)
                             :seon.cluster.run/starting-ns starting-ns
                             :seon.cluster.run/plan-digest plan-digest
                             :seon.cluster.run/sources revision}
                    opened (db/transact! connection
                                         {:tx-data
                                          (run/system-run-tx @connection request)})]
                (if (:seon.error/kind opened)
                  (do (retire-proof! store-value branch) opened)
                  (do
                    (execute-revision! connection cluster agent-id process)
                    (let [proof-db @connection
                          proof-receipts (run-receipts proof-db [proof-run-id])
                          original-receipts (run-receipts live-db run-ids)
                          proof-terminal (terminal-state proof-db agent-id process
                                                         [proof-run-id])
                          original-terminal (terminal-state live-db agent-id process
                                                            run-ids)
                          proof-declarations (declarations proof-db [proof-run-id])
                          original-declarations (declarations live-db run-ids)
                          failed
                          (cond
                            (some #(or (not (str/blank?
                                             (:seon.cluster.eval/error %)))
                                       (not= :seon.eval.drive/absent
                                             (:seon.error/kind %)))
                                  proof-receipts)
                            [::zero-error-receipts proof-receipts]

                            (not= (:seon.eval.drive/outcome original-terminal)
                                  (:seon.eval.drive/outcome proof-terminal))
                            [::terminal-equivalent
                             {:original original-terminal :proof proof-terminal}]

                            (not= (completed-result original-receipts)
                                  (completed-result proof-receipts))
                            [::completed-result-equivalent
                             {:original (completed-result original-receipts)
                              :proof (completed-result proof-receipts)}]

                            (not= (mapv #(dissoc % :seon.cluster.run/id)
                                        original-declarations)
                                  (mapv #(dissoc % :seon.cluster.run/id)
                                        proof-declarations))
                            [::declaration-equivalent
                             {:original original-declarations
                              :proof proof-declarations}])]
                      (if failed
                        (do
                          (retire-proof! store-value branch)
                          (error-value (first failed)
                                       "The revision did not satisfy proof acceptance."
                                       (second failed)))
                        {::proof-branch branch
                         ::run-ids run-ids
                         ::revision revision
                         :seon.cluster.agent/id agent-id
                         :seon.cluster.run/id proof-run-id
                         :seon.cluster.run/process process
                         :seon.cluster.run/starting-ns starting-ns
                         :seon.cluster.run/plan-digest plan-digest
                         ::receipts proof-receipts
                         ::terminal proof-terminal
                         ::declarations proof-declarations})))))
              (finally
                (when (store/connection? connection)
                  (store/release-branch! connection)))))
          (catch Throwable failure
            (when (contains? (registry/roster store-value) branch)
              (retire-proof! store-value branch))
            {:seon.error/kind ::proof-fault
             :seon.error/message (or (ex-message failure)
                                     "Session proof failed.")
             :seon.error/data {:seon.cluster.curate/predicate ::proof-fault}}))))))

(def ^:private receipt-selector
  [:seon.cluster.eval/id :seon.cluster.eval/ordinal :seon.cluster.eval/at
   :seon.cluster.eval/result-edn :seon.cluster.eval/result-blob
   :seon.cluster.eval/result-size :seon.cluster.eval/error
   :seon.cluster.eval/triage-edn :seon.cluster.eval/interrupted-at
   :seon.error/kind :seon.cluster.eval/output
   {:seon.cluster.eval/ns [:seon.ns/name]}
   :seon.sci.eval/ending-ns])

(defn- proof-receipts [database run-id]
  (->> (db/q '[:find ?receipt ?ordinal
               :in $ ?run-id
               :where
               [?run :seon.cluster.run/id ?run-id]
               [?receipt :seon.cluster.eval/run ?run]
               [?receipt :seon.cluster.eval/ordinal ?ordinal]]
             database run-id)
       (sort-by second)
       (mapv (fn [[receipt _]] (db/pull database receipt-selector receipt)))))

(defn- adopted-receipt [run-id receipt]
  (-> receipt
      (dissoc :db/id)
      (assoc :seon.cluster.eval/run [:seon.cluster.run/id run-id])
      (update :seon.cluster.eval/ns
              #(when % [:seon.ns/name (:seon.ns/name %)]))))

(defn adopt!
  "Atomically adopt one accepted proof, then retire its proof branch."
  {:malli/schema [:=> [:cat :seon.cluster.curate/adopt-request]
                  [:or :seon.cluster.curate/adoption :seon.error/value]]}
  [{instance :seon.boot/instance proof ::proof}]
  (let [store-value (:seon.store/store instance)
        live-connection (:seon.boot/cluster-connection instance)
        branch (::proof-branch proof)
        run-id (:seon.cluster.run/id proof)
        proof-connection (store/open-branch! store-value branch)]
    (try
      (let [proof-db @proof-connection
            receipts (proof-receipts proof-db run-id)
            closed-at (:seon.cluster.run/closed-at
                       (db/pull proof-db [:seon.cluster.run/closed-at]
                                [:seon.cluster.run/id run-id]))
            request {:seon.cluster.agent/id (:seon.cluster.agent/id proof)
                     :seon.cluster.run/id run-id
                     :seon.cluster.run/process (:seon.cluster.run/process proof)
                     :seon.cluster.run/opened-at (Date.)
                     :seon.cluster.run/starting-ns
                     (:seon.cluster.run/starting-ns proof)
                     :seon.cluster.run/plan-digest
                     (:seon.cluster.run/plan-digest proof)
                     :seon.cluster.run/sources (::revision proof)}
            adoption-tx
            (into (run/system-run-tx @live-connection request)
                  (concat
                   (map (fn [candidate-run-id]
                          [:db/add [:seon.cluster.run/id run-id]
                           :seon.cluster.run/supersedes
                           [:seon.cluster.run/id candidate-run-id]])
                        (::run-ids proof))
                   (map (partial adopted-receipt run-id) receipts)
                   (run/close-tx {:seon.cluster.run/id run-id
                                  :seon.cluster.run/process
                                  (:seon.cluster.run/process proof)
                                  :seon.cluster.run/closed-at closed-at})))
            report (db/transact! live-connection {:tx-data adoption-tx})]
        (if (:seon.error/kind report)
          report
          (let [commit-id (db/commit-id (:db-after report))]
            (store/release-branch! proof-connection)
            (retire-proof! store-value branch)
            {:seon.cluster.run/id run-id
             ::run-ids (::run-ids proof)
             ::adopted-commit-id commit-id})))
      (finally
        (when (store/connection? proof-connection)
          (store/release-branch! proof-connection))))))
