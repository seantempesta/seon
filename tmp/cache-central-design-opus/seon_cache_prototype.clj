;; PROTOTYPE ONLY (lane cache-central-design-opus): the proposed `seon.cache` owner, written to
;; price the migration. Not on any classpath; the compute/weigh/evict behaviour it relies on is
;; exercised in bench.clj against Caffeine 3.2.4. Contracts, docstring headings and the config
;; schema row are left to the implementing slice.
(ns seon-cache-prototype
  "One bounded, weighted cache for every derived value the JVM keeps.

  Keys are `[kind key]`. A kind is a qualified keyword its owner registers with
  a weigher. Keys name content: a definition or declaration digest, a commit id,
  or the immutable value itself; nothing is ever invalidated, only evicted."
  (:import [com.github.benmanes.caffeine.cache AsyncCache Caffeine RemovalListener Weigher]
           [java.util.concurrent CompletableFuture ConcurrentHashMap]
           [java.util.concurrent.atomic LongAdder]))

;;; The one cache per process, built from the byte dial at boot. It lives in the
;;; process roster beside the store (CLAUDE.md "Live host objects ... exactly one roster").
(defn- counters [] {::hits (LongAdder.) ::misses (LongAdder.) ::evictions (LongAdder.)
                    ::weight (LongAdder.) ::load-nanos (LongAdder.)})

(defn create
  "The process cache under `max-bytes`, with `kinds` {kind weigh-fn}."
  [max-bytes kinds]
  (let [stats (ConcurrentHashMap.)
        weigh (fn [[kind] value] (int (min Integer/MAX_VALUE ((get kinds kind (constantly 1)) value))))]
    (doseq [kind (keys kinds)] (.put stats kind (counters)))
    {::stats stats
     ::kinds kinds
     ::cache
     (-> (Caffeine/newBuilder)
         (.maximumWeight (long max-bytes))
         (.weigher (reify Weigher (weigh [_ k v] (weigh k v))))
         (.removalListener
          (reify RemovalListener
            (onRemoval [_ [kind :as k] v cause]
              (when-let [c (.get stats kind)]
                (when (.wasEvicted cause) (.increment ^LongAdder (::evictions c)))
                (when v (.add ^LongAdder (::weight c) (- (weigh k v))))))))
         (.buildAsync))}))

(defn lookup
  "The value `derive` computes for `[kind key]`, derived once per key.

  The derivation runs on the caller's thread outside any map lock, so it may
  itself look up other kinds. A thrown derivation is not retained and rethrows
  to every waiter; `retain?` false (a refusal value) answers without retaining."
  ([cache kind key derive] (lookup cache kind key derive some?))
  ([{::keys [^AsyncCache cache ^ConcurrentHashMap stats kinds]} kind key derive retain?]
   (let [k [kind key]
         c (.get stats kind)
         held (.getIfPresent cache k)
         mine (when-not held (CompletableFuture.))
         prior (or held (.putIfAbsent (.asMap cache) k mine))]
     (if prior
       (do (.increment ^LongAdder (::hits c)) (.join ^CompletableFuture prior))
       (let [started (System/nanoTime)]
         (.increment ^LongAdder (::misses c))
         (try
           (let [value (derive)]
             (.add ^LongAdder (::load-nanos c) (- (System/nanoTime) started))
             (if (retain? value)
               (do (.add ^LongAdder (::weight c) ((get kinds kind) value)) (.complete mine value))
               (do (.remove (.asMap cache) k mine) (.complete mine value)))
             value)
           (catch Throwable t
             (.remove (.asMap cache) k mine)
             (.completeExceptionally mine t)
             (throw t))))))))

(defn status
  "Per-kind hits, misses, evictions, retained weight (bytes, estimated) and load time; the dial."
  [{::keys [^AsyncCache cache ^ConcurrentHashMap stats]}]
  {::max-bytes (.. cache synchronous policy eviction get getMaximum)
   ::weighted-bytes (.. cache synchronous policy eviction get weightedSize getAsLong)
   ::kinds (into (sorted-map)
                 (map (fn [[kind c]] [kind (update-vals c #(.sum ^LongAdder %))]))
                 stats)})
