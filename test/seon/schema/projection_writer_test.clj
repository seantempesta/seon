(ns seon.schema.projection-writer-test
  (:require [clojure.core.cache.wrapped :as cache]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.db]
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
            derive-projection schema/load-projection
            derivations (atom 0)
            reads (atom 0)]
        ;; The population is memoized under several keys (revisions, commit,
        ;; value, content); an empty cache is the cold start this counts from.
        (cache/seed cache {})
        (with-redefs [schema/load-projection
                      (fn ([value] (derive-projection value))
                        ([value base] (swap! derivations inc) (derive-projection value base)))]
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
      (let [cache @(ns-resolve 'seon.db 'projection-cache)
            database (:db-after (d/with (db/db connection) []))
            derive-projection schema/load-projection
            derivations (atom 0)]
        (cache/seed cache {})
        (is (nil? (datahike.db/committed-value-identity database)))
        (with-redefs [schema/load-projection
                      (fn ([value] (derive-projection value))
                        ([value base] (swap! derivations inc) (derive-projection value base)))]
          (let [left (db/carried-projection database)
                right (db/carried-projection database)]
            (is (identical? left right)
                "a value without committed identity is memoized by its declarations")
            (is (= 1 @derivations))))))))

(defn- unrelated-write
  "A `:db.fn/call` operation writing one row no projection derivation reads."
  {:malli/schema [:=> [:cat :string] [:vector :seon.schema/value]]}
  [message-id]
  [:db.fn/call (fn [_] [{:seon.message/id message-id
                         :seon.message/content "no declaration attribute"}])])

;; #24q consumer (2026-09-23): Datahike gives a `with` value and a
;; transaction function's argument the revision context its effective datoms
;; derive (`datahike.db/speculative-cache-context`). Before, every such value
;; fell to the declaration content key, one pass over the declaration datoms
;; (~10 ms on default) per value; the revision key finds the basis's
;; projection object unless the value wrote a declaration attribute.
(deftest a-speculative-value-reuses-its-basis-projection-unless-it-writes-a-declaration
  (support/with-database
    (fn [connection]
      (let [head (db/db connection)
            committed (db/carried-projection head)
            content-keys (atom 0)
            content-key @#'seon.db/declaration-content-key
            seen (atom nil)
            derivation-bases (atom [])
            derive-projection schema/load-projection
            project! (fn [value] (reset! seen (db/carried-projection value)) [])]
        (with-redefs [seon.db/declaration-content-key
                      (fn [value] (swap! content-keys inc) (content-key value))
                      schema/load-projection
                      (fn ([value] (derive-projection value))
                        ([value base] (swap! derivation-bases conj base) (derive-projection value base)))]
          (let [unrelated (:db-after (d/with head [(unrelated-write "speculative-unrelated")]))]
            (is (nil? (datahike.db/committed-value-identity unrelated)))
            (is (identical? committed (db/carried-projection unrelated))
                "a with value that wrote no declaration attribute reads its basis's projection"))
          (d/with head [(unrelated-write "speculative-in-flight") [:db.fn/call project!]])
          (is (identical? committed @seen)
              "so does a transaction function's argument after an unrelated write")
          (is (zero? @content-keys) "neither reads the declaration datoms")
          (let [declared (:db-after (d/with head [(declaration-call ::speculative :int)]))
                projection (db/carried-projection declared)]
            (is (not (identical? committed projection)))
            (is (contains? (:seon.schema.projection/forms projection) ::speculative)
                "a written declaration misses to its own content")
            (is (= 1 @content-keys))
            (is (= 1 (count @derivation-bases)))
            (is (identical? committed (first @derivation-bases))
                "and derives by replacement from its basis's projection")))))))

;; Cache-invalidation audit item 7 (2026-09-23): the value tier keyed a weak
;; `ValueKey` whose equality died with its referent; core.cache's LRU kept the
;; key a hit re-inserted, never the one it stored, so eviction never matched
;; and the tier held 121-338 entries against its bound of 4, each retaining a
;; whole projection.
(deftest the-value-tier-honours-its-bound-and-releases-evicted-projections
  (support/with-database
    (fn [connection]
      (let [head (db/db connection)
            _ (db/carried-projection head)
            value-cache @(ns-resolve 'seon.db 'value-projection-cache)
            durable-cache @(ns-resolve 'seon.db 'projection-cache)
            bound (:seon.db/value-cache-size db/projection-cache-policy)
            _ (cache/seed value-cache {})
            ;; Declaration datoms written directly: the tier's subject is the
            ;; value, not the row writer (`turn/row-tx` costs ~400 ms each).
            references
            (vec (for [index (range (inc bound))]
                   (let [staged (:db-after (d/with head [{:seon.schema/key
                                                          (keyword "seon.schema.projection-writer-test"
                                                                   (str "bounded-" index))
                                                          :seon.schema/form (pr-str :int)}]))
                         projection (db/carried-projection staged)]
                     (is (identical? projection (db/carried-projection staged))
                         "repeated reads of one speculative value hit the value tier")
                     (java.lang.ref.WeakReference. projection))))]
        (is (<= (count @value-cache) bound))
        ;; Only the tiers retain these projections; release the durable one's
        ;; content entries and collect.
        (cache/seed durable-cache {})
        (loop [attempt 0]
          (System/gc)
          (when (and (< attempt 3) (some? (.get ^java.lang.ref.WeakReference (first references))))
            (recur (inc attempt))))
        (is (nil? (.get ^java.lang.ref.WeakReference (first references)))
            "an evicted value's projection is collectable")))))

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
