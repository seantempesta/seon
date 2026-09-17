(ns seon.reset-edge-parity-test
  "Equal-population comparison of reference and indexed-symbol reverse walks."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(defn- timed [f]
  (let [start (System/nanoTime)
        result (f)]
    {:seon.reset-parity/value result
     :seon.reset-parity/ms (/ (double (- (System/nanoTime) start)) 1000000.0)}))

(defn- closure [seed parents]
  (loop [pending [seed] visited #{}]
    (if-let [node (peek pending)]
      (if (visited node)
        (recur (pop pending) visited)
        (recur (into (pop pending) (parents node)) (conj visited node)))
      visited)))

(deftest indexed-symbol-reverse-walk-matches-the-same-reference-population
  (let [mirrors {:seon.fn/calls ::reference-call
                 :seon.fn/references ::reference-reference
                 :seon.test/subject ::reference-subject}
        projection (schema/build-projection
                    (into (schema.edn/packaged-forms)
                          (map #(vector % [:set :seon.db/ref])) (vals mirrors)))]
    (support/with-database
     {:seon.test-support/extra-schema
      (schema.datahike/malli->datahike-schema-in projection (vec (vals mirrors)))}
     (fn [connection]
       (db/carry-connection-projection-state!
        connection (evaluation/projection-state @connection projection))
       (schema/call-with-projection
        projection
        (fn []
          (let [database (db/db connection)
                identities (db/q '[:find ?e ?symbol :where
                                   (or [?e :seon.fn/sym ?symbol]
                                       [?e :seon.test/sym ?symbol])] database)
                by-symbol (into {} (map (fn [[e sym]] [sym e])) identities)
                attributes [:seon.fn/calls :seon.fn/references :seon.test/subject]
                edges (into #{}
                            (keep (fn [[caller attribute target]]
                                    (when-let [target-id (get by-symbol target)]
                                      [caller (get mirrors attribute) target-id])))
                            (db/q '[:find ?caller ?attribute ?target :in $ [?attribute ...]
                                    :where [?caller ?attribute ?target]]
                                  database attributes))]
            (support/transacted!
             connection
             (mapv (fn [[caller attribute target]] [:db/add caller attribute target]) edges))
            (let [database (db/db connection)]
              (doseq [seed ['seon.turn/open? 'seon.db/q 'seon.id/id]]
                (let [reference
                      (timed
                       (fn []
                         ;; Include the old representation's identity-map acquisition.
                         (let [identities (db/q '[:find ?e ?symbol :where
                                                (or [?e :seon.fn/sym ?symbol]
                                                    [?e :seon.test/sym ?symbol])] database)
                               names (into {} identities)
                               ids (into {} (map (fn [[e sym]] [sym e])) identities)]
                           (into #{} (map names)
                                 (closure (get ids seed)
                                          (fn [target]
                                            (mapcat (fn [attribute]
                                                      (map :e (db/datoms database :avet attribute target)))
                                                    (vals mirrors))))))))
                      symbolic
                      (timed
                       (fn []
                         ;; gate-sets already acquires these identities once;
                         ;; include that acquisition, as for the ref walk.
                         (let [names (into {} (db/q '[:find ?e ?symbol :where
                                                     (or [?e :seon.fn/sym ?symbol]
                                                         [?e :seon.test/sym ?symbol])] database))]
                           (closure
                            seed
                            (fn [target]
                              (into #{} (map names)
                                    (distinct (mapcat (fn [attribute]
                                                        (map :e (db/datoms database :avet attribute target)))
                                                      attributes))))))))]
                  (is (pos? (count edges)))
                  (is (= (:seon.reset-parity/value reference) (:seon.reset-parity/value symbolic)))
                  (is (not (contains? (:seon.reset-parity/value symbolic) nil)))
                  (println
                   (pr-str {:seon.reset-parity/seed seed
                            :seon.reset-parity/edges (count edges)
                            :seon.reset-parity/reached (count (:seon.reset-parity/value symbolic))
                            :seon.reset-parity/reference-ms (:seon.reset-parity/ms reference)
                            :seon.reset-parity/symbol-ms (:seon.reset-parity/ms symbolic)}))))))))))))
