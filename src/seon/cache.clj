(ns seon.cache
  "The one bounded cache for every derived value this JVM keeps.

  An entry is `[kind key]`. A kind is a qualified keyword its owner names at
  the call; the key names content (a declaration or definition digest, a
  commit id, or the immutable value itself), so nothing is ever invalidated,
  only evicted. One Caffeine `AsyncCache` (3.2.4, `deps.edn`) holds every kind
  under one byte budget, the dial `:seon.config.cache/max-bytes`, and evicts
  by W-TinyLFU. Dependencies keep their own caches (Datahike's node and query
  caches) under their own dials.

  ## Recommendations

  Weigh with a count the value already has times a per-kind calibration;
  never walk the value. A derivation may itself look up other kinds.

  ## Costs

  Unarmed, measured in a bare JVM 2026-09-26 (cache-central design §3): a hit
  is one `getIfPresent` and a completed-future join, about 150-220 ns; eight
  concurrent misses of one key run one derivation.

  ## Known issues

  Weights are calibrated estimates, not retained sizes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [com.github.benmanes.caffeine.cache AsyncCache Caffeine Policy$Eviction
            RemovalCause RemovalListener Weigher]
           [java.util.concurrent CompletableFuture ConcurrentHashMap TimeUnit TimeoutException]
           [java.util.concurrent.atomic LongAdder]
           [java.util.function Function]))

(defn cache?
  "True for a cache [[create]] built."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value) (instance? AsyncCache (::cache value))))

(defn- counters
  "Fresh per-kind counters."
  {:malli/schema [:=> [:cat :seon.schema/value] :map]}
  [_kind]
  {::hits (LongAdder.) ::misses (LongAdder.) ::evictions (LongAdder.)
   ::bytes (LongAdder.) ::load-nanos (LongAdder.)})

(defn- kind-counters
  "The counters of `kind` in `held`, made on first use."
  {:malli/schema [:=> [:cat :map :qualified-keyword] :map]}
  [{::keys [^ConcurrentHashMap stats]} kind]
  (.computeIfAbsent stats kind (reify Function (apply [_ k] (counters k)))))

(defn create
  "A cache under `max-bytes` estimated bytes.

  Each retained entry is `{::value v ::weight n}`: the weight is computed once,
  by the caller's weigh function, when its derivation completes."
  {:malli/schema [:=> [:cat [:int {:min 1}]] :seon.cache/cache]}
  [max-bytes]
  (let [held {::stats (ConcurrentHashMap.)}]
    (assoc held ::cache
           (-> (Caffeine/newBuilder)
               (.maximumWeight (long max-bytes))
               (.weigher (reify Weigher
                           (weigh [_ _ entry] (int (min Integer/MAX_VALUE (::weight entry))))))
               (.removalListener
                (reify RemovalListener
                  (onRemoval [_ k entry cause]
                    (when entry
                      (let [c (kind-counters held (first k))]
                        (when (.wasEvicted ^RemovalCause cause)
                          (.increment ^LongAdder (::evictions c)))
                        (.add ^LongAdder (::bytes c) (- (long (::weight entry)))))))))
               (.buildAsync)))))

(defn- declared-max-bytes
  "The dial's declared default, read from its one declaration: the process
  cache exists before any cluster's configuration is read (schemas load
  through it), and boot applies the configured value with [[configure!]]."
  {:malli/schema [:=> [:cat] [:int {:min 1}]]}
  []
  (-> (io/resource "seon/schemas/seon.config.cache.edn") slurp edn/read-string
      :seon.config.cache/max-bytes second :seon.config/default))

;;; THE process cache: one per JVM, because its kinds are derived values of
;;; immutable inputs every cluster in the JVM shares. It must exist when the
;;; first namespace builds a schema projection, before any store is open, so
;;; the process scope is this Var; status reads it through [[status]].
(defonce ^:private process (delay (create (declared-max-bytes))))

(def ^:dynamic *reached*
  "The branch changes the executing derivation's code reaches, as a set of
  `[name digest]`, or nil where it reaches none: a branch runtime binds it
  around each analysed function whose reach holds a change
  (`seon.sci.runtime/reaching`), so a derivation that code makes is keyed by
  what it is computed with, never served to a runtime that runs other code."
  nil)

(defn- reached-key
  "`content-key` as the executing code derives it: itself, or with the branch
  changes that code reaches (`*reached*`)."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [content-key]
  (if-let [reached *reached*] [content-key reached] content-key))

(defn- eviction
  "Caffeine's size-eviction policy of `held`."
  {:malli/schema [:=> [:cat :seon.cache/cache] :seon.schema/value]}
  ^Policy$Eviction [{::keys [^AsyncCache cache]}]
  (.get (.eviction (.policy (.synchronous cache)))))

(defn configure!
  "Apply the configured byte budget `max-bytes` to the process cache.

  Entries over it are evicted by Caffeine's maintenance. Boot calls it with the
  cluster's `:seon.config.cache/max-bytes`; clusters of one checkout share
  the declared default, and the last applied value governs the process."
  {:malli/schema [:=> [:cat [:int {:min 1}]] [:int {:min 1}]]}
  [max-bytes]
  (.setMaximum (eviction @process) (long max-bytes))
  max-bytes)

(defn- join
  "The completed future's value; a derivation's failure rethrows as itself."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [^CompletableFuture pending]
  (let [{::keys [value thrown]} (.join pending)]
    (if thrown (throw thrown) value)))

;; Every retained entry weighs at least its own key, future and map node:
;; Caffeine never evicts a zero-weight entry by size (Caffeine.weigher,
;; 3.2.4), so a weigh function answering 0 (an empty population) would retain
;; without bound (Astra audit F3, 2026-09-26).
(def ^:private entry-bytes 256)

(defn- claim
  "The entry of `k` in `cache` and whether this caller put it: the caller that
  puts the entry runs its one derivation; every other caller joins it."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value] [:tuple :seon.schema/value :boolean]]}
  [^AsyncCache cache k]
  (if-let [prior (.getIfPresent cache k)]
    [prior false]
    (let [mine (CompletableFuture.)]
      (if-let [prior (.putIfAbsent (.asMap cache) k mine)] [prior false] [mine true]))))

(defn- settle!
  "Derive the claimed entry `mine` of `[kind content-key]` and complete it,
  answering what it completed with: `{::value v}`, or `{::thrown t}` for a
  derivation that threw. Neither a throw nor a value `retain?` refuses stays
  in the cache. Completed normally even when carrying a throwable: an
  exceptional completion would log a Caffeine warning per refusal."
  {:malli/schema [:=> [:cat :seon.cache/cache :qualified-keyword :seon.schema/value [:fn clojure.core/ifn?]
                       [:fn clojure.core/ifn?] [:fn clojure.core/ifn?] :seon.schema/value]
                  :map]}
  [{::keys [^AsyncCache cache] :as held} kind content-key weigh derive-fn retain? ^CompletableFuture mine]
  (let [k [kind content-key]
        c (kind-counters held kind)
        started (System/nanoTime)]
    (.increment ^LongAdder (::misses c))
    (let [result (try
                   (let [value (derive-fn)
                         keep? (and (some? value) (boolean (retain? value)))
                         weight (if keep? (+ entry-bytes (long (weigh value))) 0)]
                     (.add ^LongAdder (::load-nanos c) (- (System/nanoTime) started))
                     (if keep?
                       (.add ^LongAdder (::bytes c) weight)
                       (.remove (.asMap cache) k mine))
                     {::value value ::weight weight})
                   (catch Throwable t
                     (.remove (.asMap cache) k mine)
                     {::thrown t ::weight 0}))]
      (.complete mine result)
      result)))

(defn lookup
  "The value `derive-fn` computes for `[kind content-key]`, derived once per key.

  `derive-fn` takes no arguments; its value is retained under the byte budget
  with the weight `(weigh value)`.

  The derivation runs on the first caller's thread outside any map lock, so it
  may look up other kinds; concurrent callers of the same key wait for it. A
  thrown derivation is not retained and rethrows to every waiter. A nil value,
  or one `retain?` refuses (a refusal value), answers without being retained.

  ## Examples

  `(seon.cache/lookup :seon.schema/projection [forms contracts options]
     #(* 3700 (count (:seon.schema.projection/forms %)))
     #(projection-build forms contracts options))`

  ## Edge cases

  A derivation that looks up its own `[kind key]` waits on itself forever:
  keys name content, and content derivations are acyclic."
  {:malli/schema
   [:function
    [:=> [:cat :qualified-keyword :seon.schema/value [:fn clojure.core/ifn?] [:fn clojure.core/ifn?]]
     :seon.schema/value]
    [:=> [:cat :qualified-keyword :seon.schema/value [:fn clojure.core/ifn?] [:fn clojure.core/ifn?]
          [:fn clojure.core/ifn?]]
     :seon.schema/value]
    [:=> [:cat :seon.cache/cache :qualified-keyword :seon.schema/value [:fn clojure.core/ifn?]
          [:fn clojure.core/ifn?] [:fn clojure.core/ifn?]]
     :seon.schema/value]]}
  ([kind content-key weigh derive-fn] (lookup @process kind content-key weigh derive-fn some?))
  ([kind content-key weigh derive-fn retain?] (lookup @process kind content-key weigh derive-fn retain?))
  ([{::keys [cache] :as held} kind content-key weigh derive-fn retain?]
   (let [content-key (reached-key content-key)
         [pending mine?] (claim cache [kind content-key])]
     (if mine?
       (let [{::keys [value thrown]} (settle! held kind content-key weigh derive-fn retain? pending)]
         (if thrown (throw thrown) value))
       (do (.increment ^LongAdder (::hits (kind-counters held kind))) (join pending))))))

(defn lookup-within
  "[[lookup]] that waits at most `deadline-ms` for the value: a reader that
  must not block (a page's render pass) gets `:seon.cache/pending` instead.

  A miss starts the one derivation of the key on its own virtual thread; a
  reader that stops waiting never cancels it (an interrupt is not an exit),
  and later readers of the key join it rather than starting another. When the
  answer is pending, `on-complete` (no arguments) runs once the derivation
  completes, so the reader can offer itself a wake. A thrown derivation
  rethrows as [[lookup]]'s does; a value `retain?` refuses answers its
  readers and is not retained, as [[lookup]]'s.

  ## Examples

  `(seon.cache/lookup-within ::family key count #(derive db) some? 50 #(async/offer! wake {}))`"
  {:malli/schema [:function
                  [:=> [:cat :qualified-keyword :seon.schema/value [:fn clojure.core/ifn?] [:fn clojure.core/ifn?]
                        [:fn clojure.core/ifn?] [:int {:min 0}] [:maybe [:fn clojure.core/ifn?]]]
                   [:or :seon.cache/pending :seon.schema/value]]
                  [:=> [:cat :seon.cache/cache :qualified-keyword :seon.schema/value [:fn clojure.core/ifn?] [:fn clojure.core/ifn?]
                        [:fn clojure.core/ifn?] [:int {:min 0}] [:maybe [:fn clojure.core/ifn?]]]
                   [:or :seon.cache/pending :seon.schema/value]]]}
  ([kind content-key weigh derive-fn retain? deadline-ms on-complete]
   (lookup-within @process kind content-key weigh derive-fn retain? deadline-ms on-complete))
  ([{::keys [cache] :as held} kind content-key weigh derive-fn retain? deadline-ms on-complete]
   (let [content-key (reached-key content-key)
         [^CompletableFuture pending mine?] (claim cache [kind content-key])]
     (if mine?
       ;; the derivation's own lookups are keyed as the reader's
       (Thread/startVirtualThread (bound-fn [] (settle! held kind content-key weigh derive-fn retain? pending)))
       (.increment ^LongAdder (::hits (kind-counters held kind))))
     (try
       (let [{::keys [value thrown]} (.get pending (long deadline-ms) TimeUnit/MILLISECONDS)]
         (if thrown (throw thrown) value))
       (catch TimeoutException _
         (when on-complete (.thenRun pending ^Runnable on-complete))
         {:seon.cache/pending-kind kind :seon.cache/since (java.util.Date.)})))))

(defn present
  "The completed value of `[kind content-key]`, or nil when it is absent,
  pending or refused. A held value read is a hit of its kind, as [[lookup]]
  counts one; an absent read counts nothing, since no derivation ran."
  {:malli/schema [:function
                  [:=> [:cat :qualified-keyword :seon.schema/value] :seon.schema/value]
                  [:=> [:cat :seon.cache/cache :qualified-keyword :seon.schema/value] :seon.schema/value]]}
  ([kind content-key] (present @process kind content-key))
  ([{::keys [^AsyncCache cache] :as held} kind content-key]
   (when-let [^CompletableFuture pending (.getIfPresent cache [kind (reached-key content-key)])]
     (when (and (.isDone pending) (not (.isCompletedExceptionally pending)))
       (let [value (::value (.join pending))]
         (when (some? value) (.increment ^LongAdder (::hits (kind-counters held kind))))
         value)))))

(defn evict!
  "Remove the one entry `[kind content-key]`: a value its owner has replaced
  (a derivation that learned more of what it reads). Answers nil."
  {:malli/schema [:function
                  [:=> [:cat :qualified-keyword :seon.schema/value] :nil]
                  [:=> [:cat :seon.cache/cache :qualified-keyword :seon.schema/value] :nil]]}
  ([kind content-key] (evict! @process kind content-key))
  ([{::keys [^AsyncCache cache]} kind content-key]
   (.remove (.asMap cache) [kind (reached-key content-key)])
   nil))

(defn retained
  "The retained values of `kind`, by content key: the completed entries only.

  For diagnosis and retention tests; it walks every entry of the cache."
  {:malli/schema [:function
                  [:=> [:cat :qualified-keyword] [:map-of :seon.schema/value :seon.schema/value]]
                  [:=> [:cat :seon.cache/cache :qualified-keyword]
                   [:map-of :seon.schema/value :seon.schema/value]]]}
  ([kind] (retained @process kind))
  ([{::keys [^AsyncCache cache]} kind]
   (into {}
         (keep (fn [[[entry-kind content-key] ^CompletableFuture pending]]
                 (when (and (= kind entry-kind) (.isDone pending) (not (.isCompletedExceptionally pending)))
                   (let [{::keys [value thrown]} (.join pending)]
                     (when-not thrown [content-key value])))))
         (.asMap cache))))

(defn evict-kinds!
  "Remove the entries of retired producer kinds, leaving other kinds intact.

  Reload calls this when it deletes a cache producer. This is O(cache entries)
  once at retirement, not an invalidation on reads or writes. In-flight values
  may finish for their existing callers but are no longer retained."
  {:malli/schema [:function
                  [:=> [:cat [:set :qualified-keyword]] nat-int?]
                  [:=> [:cat :seon.cache/cache [:set :qualified-keyword]] nat-int?]]}
  ([kinds] (evict-kinds! @process kinds))
  ([{::keys [^AsyncCache cache]} kinds]
   (let [entries (.asMap cache)
         removed (reduce (fn [n [k pending]]
                           (if (and (kinds (first k)) (.remove entries k pending)) (inc n) n))
                         0 entries)]
     (.cleanUp (.synchronous cache))
     removed)))

(defn status
  "The byte budget, the estimated bytes held, and per-kind counters.

  Per kind: hits, misses, evictions, estimated bytes and derivation
  milliseconds since the JVM began, with its hit rate and its share of the
  weighed bytes as whole percents."
  {:malli/schema [:function
                  [:=> [:cat] :seon.cache/status]
                  [:=> [:cat :seon.cache/cache] :seon.cache/status]]}
  ([] (status @process))
  ([{::keys [^ConcurrentHashMap stats] :as held}]
   (let [policy (eviction held)
         weighted (.getAsLong (.weightedSize policy))
         percent (fn [part whole] (if (pos? whole) (quot (* 100 part) whole) 0))]
     {:seon.cache/max-bytes (.getMaximum policy)
      :seon.cache/weighted-bytes weighted
      :seon.cache/kinds
      (into (sorted-map)
            (map (fn [[kind c]]
                   (let [sum #(.sum ^LongAdder (get c %))
                         [hits misses held] (map sum [::hits ::misses ::bytes])]
                     [kind {:seon.cache/kind kind :seon.cache/hits hits :seon.cache/misses misses
                            :seon.cache/evictions (sum ::evictions) :seon.cache/bytes held
                            :seon.cache/load-ms (quot (sum ::load-nanos) 1000000)
                            :seon.cache/hit-percent (percent hits (+ hits misses))
                            :seon.cache/byte-percent (percent (max 0 held) weighted)}])))
            stats)})))
