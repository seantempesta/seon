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

(deftest ^{:seon.test/long "Two declaration analyses and three canonical writer transactions, including deletion validation and history readback; the former refused deletion run measured 6.22 s."
           :seon.test/long-ms 15000}
  transaction-report-identities-select-only-changed-program-rows
  (support/with-database
   (fn [connection]
     (let [changed-symbol (symbol "my.note" (str "report-changed-" (id/id)))
           other-symbol (symbol "my.note" (str "report-other-" (id/id)))
           before (db/db connection)
           changed (support/program-fn-row before changed-symbol (pr-str (list 'defn (symbol (name changed-symbol)) [] 1)))
           other (support/program-fn-row before other-symbol (pr-str (list 'defn (symbol (name other-symbol)) [] 2)))
           _ (support/transacted! connection [changed other])
           report (support/transacted! connection
                                           [{:seon.fn/sym changed-symbol
                                             :seon.fn/doc "Changed documentation"}])
           identities (seon.fn/report-identities report)
           rows (seon.fn/published-index-rows (:db-after report) (vec identities))]
       (is (= #{[:seon.fn/sym changed-symbol]} identities))
       (is (= identities (into #{} (keep program/row-identity) rows)))
       (is (= "Changed documentation" (:seon.fn/doc (first rows))))
       (let [removed (support/transacted! connection
                                             [[:db/retractEntity [:seon.fn/sym changed-symbol]]])
             identities (seon.fn/report-identities removed)]
         (is (contains? identities [:seon.fn/sym changed-symbol]))
         (is (empty? (seon.fn/published-index-rows (:db-after removed)
                                                  [[:seon.fn/sym changed-symbol]])))
         (let [history-report {:db-before (:db-before report)
                               :db-after (:db-after removed)
                               :tx-data (vec (d/datoms
                                              (d/since (d/history (:db-after removed))
                                                       (db/basis-t (:db-before report))) :eavt))
                               :tempids {}}]
           (is (= #{[:seon.fn/sym changed-symbol]}
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
              {"leaf.clj" "(ns sample.reload.leaf)\n(defn value [] 1)\n(defmacro expanded [] 1)"
               "caller.clj" "(ns sample.reload.caller (:require [sample.reload.leaf]))"
               "outer.clj" "(ns sample.reload.outer (:require [sample.reload.caller]))"
               "other.clj" "(ns sample.reload.other)"}]
        (spit (io/file root "src" file) source))
      (support/with-database
       (fn [connection]
         (support/transacted!
          connection
          (seon.fn/rows {:seon.fn/root (.getCanonicalPath root)
                        :seon.fn/roots ["src"]}))
         (let [selected (cluster/development-namespaces
                         (db/db connection) [[:seon.fn/sym 'sample.reload.leaf/value]])]
           (is (= #{'sample.reload.leaf 'sample.reload.caller 'sample.reload.outer}
                  selected)
               "Ordinary edits retain conservative compile-time dependent reload.")
           (is (empty? (cluster/development-namespaces (db/db connection) []))))
         (let [before (db/db connection)
               _ (support/transacted!
                  connection [[:db/retract [:seon.ns/name 'sample.reload.caller]
                               :seon.ns/requires 'sample.reload.leaf]])
               after (db/db connection)]
           (is (= #{'sample.reload.leaf}
                  (cluster/development-namespaces after [[:seon.fn/sym 'sample.reload.leaf/value]])))
           (is (= #{'sample.reload.leaf 'sample.reload.caller 'sample.reload.outer}
                  (cluster/development-namespaces before after
                                                  [[:seon.fn/sym 'sample.reload.leaf/value]])))
           (support/transacted!
            connection [[:db/add [:seon.ns/name 'sample.reload.caller]
                         :seon.ns/requires 'sample.reload.leaf]]))
         (let [database (db/db connection)
               verify (ns-resolve 'seon.cluster 'verify-development-sources!)
               namespaces #{'sample.reload.leaf}]
           (is (nil? (verify database (.getCanonicalPath root) namespaces)))
           (spit (io/file root "src" "leaf.clj") "(ns sample.reload.leaf)\n(defn value [] 2)\n")
           (let [refusal (support/refusal-data
                          #(verify database (.getCanonicalPath root) namespaces))]
             (is (true? (:seon.boot/refused refusal)))
             (is (= :adoption (:seon.cluster.source/phase refusal)))
             (is (= ["src/leaf.clj"]
                    (get-in refusal [:seon.boot/offense :seon.source/changed-paths])))))
         (let [selected (cluster/development-namespaces
                         (db/db connection) [[:seon.fn/sym 'sample.reload.leaf/expanded]])]
           (is (= #{'sample.reload.leaf 'sample.reload.caller 'sample.reload.outer} selected))
           (is (= ['sample.reload.leaf 'sample.reload.caller 'sample.reload.outer]
                  (cluster/reload-order selected
                                        {'sample.reload.caller #{'sample.reload.leaf}
                                         'sample.reload.outer #{'sample.reload.caller}}))))))
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

(deftest schema-referrers-are-selected-for-arming-without-a-namespace-reload
  (support/with-database
   (fn [connection]
     (let [sym (symbol "my.note" (str "arming-probe-" (id/id)))
           row (support/program-fn-row
                (db/db connection) sym
                (pr-str (list 'defn (symbol (name sym))
                              {:malli/schema [:=> [:cat :seon.source/published] :boolean]}
                              '[value] '(boolean value))))
           _ (support/transacted! connection [row])
           selected (#'cluster/development-arming-identities
                     (db/db connection) #{} [[:seon.schema/key :seon.source/commit-id]])]
       (is (some? (:db/id (db/pull (db/db connection) [:db/id] [:seon.fn/sym sym]))))
       (is (contains? selected [:seon.fn/sym sym])
           "The function refers to the changed key through its published input schema.")))))

(deftest ^{:seon.test/long "Two selected-file analyses and canonical reconciliation on a fixture branch."
           :seon.test/long-ms 15000}
  selected-rows-reconcile-without-a-manifest
  (let [root (io/file "tmp" (str "publication-rows-" (id/id)))
        file (io/file root "src/sample/rows.clj")
        request {:seon.fn/root (.getCanonicalPath root) :seon.fn/roots ["src"]}
        path "src/sample/rows.clj"]
    (io/make-parents file)
    (try
      (spit file "(ns sample.rows)\n(defn retained [] 1)\n(defn removed [] 2)\n")
      (support/with-database
       (fn [connection]
         (support/transacted! connection (seon.fn/analyze-rows request))
         (let [before (db/db connection)
               projection (db/carried-projection before)]
           (is (= 'sample.rows/removed
                  (:seon.fn/sym (db/pull before [:seon.fn/sym]
                                        [:seon.fn/sym 'sample.rows/removed]))))
           (spit file "(ns sample.rows)\n(defn retained [] 1)\n")
           (let [selected (assoc request :seon.source/previous-database before
                                         :seon.fn/changed-paths #{path})
                 analyzed (seon.fn/analyze-rows selected)
                 result (schema/call-with-projection
                         projection
                         #(seon.fn/index! (assoc selected :seon.db/connection connection
                                                       :seon.schema/projection projection
                                                       :seon.program/rows analyzed)))]
             (is (nil? (:seon.error/at result)) (pr-str result))
             (is (= 'sample.rows/retained
                    (:seon.fn/sym (db/pull (db/db connection) [:seon.fn/sym]
                                          [:seon.fn/sym 'sample.rows/retained]))))
             (is (nil? (:seon.fn/sym (db/pull (db/db connection) [:seon.fn/sym]
                                            [:seon.fn/sym 'sample.rows/removed]))))))))
      (finally (support/delete-recursively! root)))))
