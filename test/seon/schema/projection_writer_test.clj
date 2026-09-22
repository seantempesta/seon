(ns seon.schema.projection-writer-test
  (:require [clojure.core.cache.wrapped :as cache]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [malli.core :as m]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- declaration-call
  {:malli/schema [:=> [:cat :qualified-keyword :seon.schema/definition]
                  [:vector :seon.schema/value]]}
  [schema-key definition]
  [:db.fn/call #'turn/row-tx {}
   {:seon.schema/key schema-key :seon.schema/form (pr-str definition)}])

(deftest ordered-declarations-use-their-database-and-abort-together
  (support/with-database
    (fn [connection]
      (let [before (db/db connection)
            entering (db/carried-projection before)
            report (support/transacted!
                    connection
                    [(declaration-call ::a :int)
                     (declaration-call ::b [:vector ::a])])
            after (:db-after report)
            resulting (db/carried-projection after)
            options (:seon.schema.projection/compile-options resulting)]
        (is (every? #(not (contains? (:seon.schema.projection/forms entering) %))
                    [::a ::b]))
        (is (identical? entering (db/carried-projection (:db-before report))))
        (is (identical? entering (db/carried-projection before)))
        (is (m/validate ::a 7 options))
        (is (m/validate ::b [7 8] options))
        (is (not (m/validate ::b ["invalid"] options)))
        (is (= [:vector ::a] (get (:seon.schema.projection/forms resulting) ::b)))
        (is (identical? resulting (db/carried-projection (db/db connection))))
        (let [refused (db/transact!
                       connection
                       [(declaration-call ::c :string)
                        (declaration-call ::invalid [:vector ::absent])])]
          (is (true? (:seon.db/transaction-refused refused)))
          (is (nil? (:db-after refused)))
          (is (= (:cache-context after) (:cache-context (db/db connection))))
          (is (identical? resulting (db/carried-projection (db/db connection))))
          (is (nil? (d/pull (db/db connection) [:seon.schema/key]
                           [:seon.schema/key ::c])))
          (is (not (contains? (:seon.schema.projection/forms resulting) ::c))))))))

(deftest committed-reads-derive-once-per-declaration-population
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            cache @(ns-resolve 'seon.db 'projection-cache)
            cache-key @(ns-resolve 'seon.db 'projection-cache-key)
            derive-projection schema/load-projection
            derivations (atom 0)
            reads (atom 0)]
        (cache/evict cache (cache-key database))
        (with-redefs [schema/load-projection
                      (fn [value] (swap! derivations inc) (derive-projection value))]
          (let [read! (fn [value] (swap! reads inc) (db/carried-projection value))
                first-read (read! database)]
            (is (identical? first-read (read! database)))
            (is (= 1 @derivations))
            (is (= 1 (- @reads @derivations)) "one cache hit")
            (is (identical? first-read (read! (d/history database)))
                "a temporal view reads its origin's population")
            (let [unrelated (:db-after (support/transacted! connection []))]
              (is (not= (:cache-context database) (:cache-context unrelated)))
              (is (identical? first-read (read! unrelated))
                  "a commit touching no declaration attribute keys the same population")
              (is (= 1 @derivations)))
            (let [declared (:db-after (support/transacted!
                                       connection
                                       [(declaration-call ::cached :int)]))
                  next-read (read! declared)]
              (is (= 2 @derivations))
              (is (identical? next-read (read! declared)))
              (is (= 2 @derivations))
              (is (contains? (:seon.schema.projection/forms next-read) ::cached))
              (is (identical? first-read (read! database))))))
        (is (<= (count @cache) (:seon.db/projection-cache-size db/projection-cache-policy)))
        (is (string? (:seon.db/projection-cache-reason db/projection-cache-policy)))))))

(deftest speculative-values-derive-without-committed-identity
  (support/with-database
    (fn [connection]
      (let [database (:db-after (d/with (db/db connection) []))
            derive-projection schema/load-projection
            derivations (atom 0)]
        (is (nil? (:cache-context database)))
        (with-redefs [schema/load-projection
                      (fn [value] (swap! derivations inc) (derive-projection value))]
          (let [left (db/carried-projection database)
                right (db/carried-projection database)]
            (is (= (:seon.schema.projection/forms left)
                   (:seon.schema.projection/forms right)))
            (is (= 2 @derivations))))))))

(deftest an-as-of-view-before-a-declaration-change-reads-the-older-population
  (support/with-database
    (fn [connection]
      (let [declared (:db-after (support/transacted!
                                 connection
                                 [(declaration-call ::dated :int)]))
            replaced (:db-after (support/transacted!
                                 connection
                                 [(declaration-call ::dated :string)]))
            forms (fn [database]
                    (get (:seon.schema.projection/forms (db/carried-projection database))
                         ::dated))]
        (is (= :int (forms declared)))
        (is (= :string (forms replaced)))
        (is (= :int (forms (d/as-of replaced (:max-tx declared))))
            "the as-of view derives from its own declaration datoms")
        (is (= :string (forms (d/history replaced)))
            "history decodes with its origin's current population")))))

(deftest the-writer-derives-from-its-database-never-a-stale-stamp
  (support/with-database
    (fn [connection]
      (let [stale (db/carried-projection (db/db connection))
            _ (support/transacted! connection [(declaration-call ::fresh :int)])
            resolve-value @#'seon.db/resolve-database-value
            validate @#'seon.db/write-error
            seen (atom [])]
        (is (not (contains? (:seon.schema.projection/forms stale) ::fresh)))
        (with-redefs [seon.db/resolve-database-value
                      (fn [c] (vary-meta (resolve-value c) assoc :seon.schema/projection stale))
                      seon.db/write-error
                      (fn [database projection transaction]
                        (swap! seen conj projection)
                        (validate database projection transaction))]
          (support/transacted! connection []))
        (is (= 1 (count @seen)))
        (is (not (identical? stale (first @seen))) "the stamp never selects the writer's world")
        (is (contains? (:seon.schema.projection/forms (first @seen)) ::fresh))
        (is (identical? (first @seen) (db/carried-projection (db/db connection))))))))
