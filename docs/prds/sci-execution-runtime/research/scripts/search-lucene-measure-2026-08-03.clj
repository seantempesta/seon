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
  [match]
  {:seon.search/query "database"
   :seon.search/families #{:function}
   :seon.search/namespace-prefix 'seon.db
   :seon.search/match match
   :seon.search/limit 20})

(defn- measure
  [connection]
  (db/transact! connection [{:seon.ns/name 'fixture.search.measurement}])
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
             [{:seon.fn/sym "fixture.search.measurement/measured-needle"
               :seon.fn/ns [:seon.ns/name 'fixture.search.measurement]
               :seon.fn/source "(defn measured-needle [])"
               :seon.fn/doc "measuredincrementalneedle"}])
            incremental
            (:milliseconds (elapsed-ms #(search/apply-report! index report)))]
        (binding [db/*conn* connection]
          (doseq [_ (range 10)]
            (search/search (query-request :token))
            (search/search (query-request :substring)))
          (let [query-samples
                (fn [match]
                  (mapv
                   (fn [_]
                     (:milliseconds
                      (elapsed-ms #(search/search (query-request match)))))
                   (range 100)))]
            {:full-build-ms (distribution builds)
             :incremental-update-ms incremental
             :token-query-ms (distribution (query-samples :token))
             :substring-query-ms (distribution (query-samples :substring))
             :indexed-basis-t
             (:seon.search/basis-t (search/search (query-request :token)))})))
      (finally
        (search/close! index)
        (test-support/delete-recursively! live-path)))))

(defn -main
  [& _]
  (test-support/with-database
   (fn [connection]
     (prn (measure connection)))))
