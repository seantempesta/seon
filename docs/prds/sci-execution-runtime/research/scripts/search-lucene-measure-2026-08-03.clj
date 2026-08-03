(ns search-lucene-measure-2026-08-03
  (:require [seon.db :as db]
            [seon.search :as search]
            [seon.test-support :as test-support]))

(defn- elapsed-ms
  [f]
  (let [started (System/nanoTime)
        value (f)]
    {:milliseconds (/ (double (- (System/nanoTime) started)) 1000000.0)
     :value value}))

(defn- percentile
  [samples proportion]
  (let [ordered (vec (sort samples))
        index (min (dec (count ordered))
                   (dec (long (Math/ceil (* proportion (count ordered))))))]
    (nth ordered (max 0 index))))

(defn- distribution
  [samples]
  {:samples (count samples)
   :minimum-ms (first (sort samples))
   :median-ms (percentile samples 0.50)
   :p95-ms (percentile samples 0.95)
   :maximum-ms (last (sort samples))})

(defn- query-request
  [query family match]
  {:seon.search/query query
   :seon.search/families #{family}
   :seon.search/match match
   :seon.search/limit 20})

(def ^:private synthetic-message-count 1000)
(def ^:private synthetic-instruction-count 10)

(defn- synthetic-rows
  []
  (into
   [{:db/id "search-measure-agent"
     :seon.cluster.agent/id "search-measure-agent"}]
   (concat
    (map
     (fn [index]
       {:seon.cluster.message/id (str "search-measure-message-" index)
        :seon.cluster.message/to "search-measure-agent"
        :seon.cluster.message/content
        (str "syntheticmessagesearchneedle " index " "
             "A deterministic message makes Lucene rebuild cost grow with "
             "database facts rather than only the fixed program graph.")
        :seon.cluster.message/at (java.util.Date. 0)})
     (range synthetic-message-count))
    (map
     (fn [index]
       {:seon.cluster.instruction/id
        (keyword (str "search-measure-instruction-" index))
        :seon.cluster.instruction/text
        (str "syntheticinstructionsearchneedle " index " "
             "A deterministic instruction is indexed as declared prose.")})
     (range synthetic-instruction-count)))))

(defn- indexed-value-census
  [database]
  (into
   (sorted-map)
   (map
    (fn [{field :seon.search/field}]
      (let [values
            (db/q '[:find [?value ...]
                    :in $ ?field
                    :where [_ ?field ?value]]
                  database field)]
        [field {:values (count values)
                :characters (reduce + (map (comp count str) values))}])))
   (#'search/document-specs database)))

(defn- measure
  [connection]
  (db/transact! connection (synthetic-rows))
  (let [paths (mapv #(str "tmp/search-measure-" % "-" (random-uuid))
                    (range 3))
        builds
        (mapv
         (fn [path]
           (let [{:keys [milliseconds value]}
                 (elapsed-ms #(search/open! connection path))]
             (search/close! value)
             (test-support/delete-recursively! path)
             milliseconds))
         paths)
        live-path (str "tmp/search-measure-live-" (random-uuid))
        index (search/open! connection live-path)]
    (try
      (let [report
            (db/transact!
             connection
             [{:seon.cluster.message/id "search-measure-message-incremental"
               :seon.cluster.message/to
               [:seon.cluster.agent/id "search-measure-agent"]
               :seon.cluster.message/content "incrementalmessagesearchneedle"
               :seon.cluster.message/at (java.util.Date. 0)}])
            message-update
            (:milliseconds (elapsed-ms #(search/apply-report! index report)))
            instruction-report
            (db/transact!
             connection
             [{:seon.cluster.instruction/id :search-measure-instruction-0
               :seon.cluster.instruction/text
               "incrementalinstructionsearchneedle"}])
            instruction-update
            (:milliseconds
             (elapsed-ms #(search/apply-report! index instruction-report)))]
        (binding [db/*conn* connection]
          (doseq [_ (range 10)]
            (search/search
             (assoc (query-request "database" :seon.fn/sym :token)
                    :seon.search/namespace-prefix 'seon.db))
            (search/search
             (query-request "syntheticmessagesearchneedle"
                            :seon.cluster.message/id :token)))
          (let [query-samples
                (fn [request]
                  (mapv
                   (fn [_]
                     (:milliseconds
                      (elapsed-ms #(search/search request))))
                   (range 100)))]
            {:synthetic-data
             {:messages synthetic-message-count
              :instructions synthetic-instruction-count}
             :declared-document-specs (#'search/document-specs @connection)
             :indexed-values (indexed-value-census @connection)
             :full-build-ms (distribution builds)
             :message-update-ms message-update
             :instruction-update-ms instruction-update
             :program-token-query-ms
             (distribution
              (query-samples
               (assoc (query-request "database" :seon.fn/sym :token)
                      :seon.search/namespace-prefix 'seon.db)))
             :message-token-query-ms
             (distribution
              (query-samples
               (query-request "syntheticmessagesearchneedle"
                              :seon.cluster.message/id :token)))
             :indexed-basis-t
             (:seon.search/basis-t
              (search/search
               (query-request "syntheticmessagesearchneedle"
                              :seon.cluster.message/id :token)))})))
      (finally
        (search/close! index)
        (test-support/delete-recursively! live-path)))))

(defn -main
  [& _]
  (test-support/with-database
   (fn [connection]
     (prn (measure connection)))))
