(ns seon.cluster.publication-delta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [seon.id :as id]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.program :as program]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest transaction-report-identities-select-only-changed-program-rows
  (support/with-database
   (fn [connection]
     (let [before (db/db connection)
           changed (support/program-fn-row before 'my.note/report-changed "(defn report-changed [] 1)")
           other (support/program-fn-row before 'my.note/report-other "(defn report-other [] 2)")
           _ (support/transacted! connection [changed other])
           report (support/transacted! connection
                                           [{:seon.fn/sym 'my.note/report-changed
                                             :seon.fn/doc "Changed documentation"}])
           identities (seon.fn/report-identities report)
           rows (seon.fn/published-index-rows (:db-after report) (vec identities))]
       (is (= #{[:seon.fn/sym 'my.note/report-changed]} identities))
       (is (= identities (into #{} (keep program/row-identity) rows)))
       (is (= "Changed documentation" (:seon.fn/doc (first rows))))
       (let [removed (support/transacted! connection
                                             [[:db/retractEntity [:seon.fn/sym 'my.note/report-changed]]])
             identities (seon.fn/report-identities removed)]
         (is (contains? identities [:seon.fn/sym 'my.note/report-changed]))
         (is (empty? (seon.fn/published-index-rows (:db-after removed)
                                                  [[:seon.fn/sym 'my.note/report-changed]])))
         (let [history-report {:db-before (:db-before report)
                               :db-after (:db-after removed)
                               :tx-data (vec (d/datoms
                                              (d/since (d/history (:db-after removed))
                                                       (db/basis-t (:db-before report))) :eavt))
                               :tempids {}}]
           (is (= #{[:seon.fn/sym 'my.note/report-changed]}
                  (into #{} (filter #(= :seon.fn/sym (first %)))
                        (seon.fn/report-identities history-report)))
               "catch-up history includes retractions across both transactions")))))))

(deftest ^{:seon.test/long "The first canonical fixture acquisition plus namespace-file analysis measured 12.0 s; fixture projection acquisition remains owned by the test-system lane."
           :seon.test/long-ms 20000}
  changed-declarations-reload-their-namespace-and-dependents
  (let [root (io/file "tmp" (str "publication-delta-" (id/id)))]
    (.mkdirs (io/file root "src"))
    (try
      (doseq [[file source]
              {"leaf.clj" "(ns sample.reload.leaf)"
               "caller.clj" "(ns sample.reload.caller (:require [sample.reload.leaf]))"
               "outer.clj" "(ns sample.reload.outer (:require [sample.reload.caller]))"
               "other.clj" "(ns sample.reload.other)"}]
        (spit (io/file root "src" file) source))
      (support/with-database
       (fn [connection]
         (support/transacted!
          connection
          (filterv :seon.ns/name
                   (seon.fn/rows {:seon.fn/root (.getCanonicalPath root)
                                  :seon.fn/roots ["src"]})))
         (let [selected (cluster/development-namespaces
                         (db/db connection) [[:seon.fn/sym 'sample.reload.leaf/value]])]
           (is (= #{'sample.reload.leaf 'sample.reload.caller 'sample.reload.outer} selected))
           (is (= ['sample.reload.leaf 'sample.reload.caller 'sample.reload.outer]
                  (cluster/reload-order selected
                                        {'sample.reload.caller #{'sample.reload.leaf}
                                         'sample.reload.outer #{'sample.reload.caller}})))
           (is (empty? (cluster/development-namespaces (db/db connection) []))))))
      (finally (support/delete-recursively! root)))))

(deftest reloading-one-file-preserves-unrelated-wrappers
  (support/with-database
   (fn [connection]
     (support/preserving-instrumentation-state
      (fn []
        (require 'my.note)
        (let [projection (db/carried-projection (db/db connection))
              request {:seon.config/on-core-error :panic :seon.schema/projection projection}
              arm (fn [] (schema/call-with-projection projection #(instrument/apply! request)))
              _ (arm)
              unrelated @#'cluster/reload-order
              expected (into #{} (keep (fn [[name candidate]]
                                         (when (:malli/schema (meta candidate))
                                           (symbol "my.note" (str name)))))
                             (ns-interns 'my.note))
              before (into {} (for [namespace (all-ns)
                                    candidate (vals (ns-interns namespace))
                                    :when (and (bound? candidate)
                                               (:seon.instrument/var (meta @candidate)))]
                                [candidate @candidate]))]
          (require 'my.note :reload)
          (arm)
          (let [rearmed (into #{}
                              (keep (fn [[candidate prior]]
                                      (when-not (identical? prior @candidate)
                                        (symbol (str (ns-name (:ns (meta candidate))))
                                                (str (:name (meta candidate)))))))
                              before)]
            (is (seq expected))
            (is (= expected rearmed)))
          (is (identical? unrelated @#'cluster/reload-order))))))))
