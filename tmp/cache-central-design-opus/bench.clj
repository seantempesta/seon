;; Per-lookup overhead: core.cache wrapped LRU vs Caffeine (bounded, weighted, W-TinyLFU).
;; Bare JVM, no Seon: java -Xmx512m -cp clojure:spec:core.specs:core.cache:priority-map:caffeine clojure.main bench.clj
(require '[clojure.core.cache.wrapped :as cw])
(import '[com.github.benmanes.caffeine.cache Caffeine Cache Weigher]
        '[java.util.function Function]
        '[java.util.concurrent CountDownLatch Executors TimeUnit])

(def n-keys 64)
(def ks (vec (for [i (range n-keys)] [:seon.cache/kind [(java.util.UUID/randomUUID) i]])))

(defn time-ns-per-op [ops f]
  (dotimes [_ 3] (f (quot ops 10)))
  (let [t0 (System/nanoTime)] (f ops) (/ (double (- (System/nanoTime) t0)) ops)))

(defn threaded [threads ops f]
  (let [pool (Executors/newFixedThreadPool threads) latch (CountDownLatch. threads)
        per (quot ops threads)]
    (f (quot per 10))
    (let [t0 (System/nanoTime)]
      (dotimes [_ threads] (.submit pool ^Runnable (fn [] (f per) (.countDown latch))))
      (.await latch) (.shutdown pool)
      (/ (double (- (System/nanoTime) t0)) ops))))

(def lru (cw/lru-cache-factory {} :threshold 256))
(doseq [k ks] (cw/lookup-or-miss lru k (fn [_] (delay k))))

(def ^Cache caff
  (-> (Caffeine/newBuilder)
      (.maximumWeight (* 512 1024 1024))
      (.weigher (reify Weigher (weigh [_ _ v] 1000)))
      (.recordStats)
      (.build)))
(def loader (reify Function (apply [_ k] k)))
(doseq [k ks] (.get caff k loader))

(defn run-lru-lookup-or-miss [ops] (loop [i 0] (when (< i ops) (cw/lookup-or-miss lru (ks (bit-and i 63)) (fn [_] (delay 1))) (recur (inc i)))))
(defn run-lru-lookup [ops] (loop [i 0] (when (< i ops) (cw/lookup lru (ks (bit-and i 63))) (recur (inc i)))))
(defn run-caff-get [ops] (loop [i 0] (when (< i ops) (.get caff (ks (bit-and i 63)) loader) (recur (inc i)))))
(defn run-caff-if-present [ops] (loop [i 0] (when (< i ops) (.getIfPresent caff (ks (bit-and i 63))) (recur (inc i)))))
(defn run-hash [ops] (loop [i 0] (when (< i ops) (hash (ks (bit-and i 63))) (recur (inc i)))))

(def ops 2000000)
(println {:jvm (System/getProperty "java.version")
          :key-hash-ns (time-ns-per-op ops run-hash)
          :core-cache-lookup-or-miss-hit-ns (time-ns-per-op ops run-lru-lookup-or-miss)
          :core-cache-lookup-hit-ns (time-ns-per-op ops run-lru-lookup)
          :caffeine-get-hit-ns (time-ns-per-op ops run-caff-get)
          :caffeine-getIfPresent-hit-ns (time-ns-per-op ops run-caff-if-present)
          :threads-8/core-cache-lookup-or-miss-ns (threaded 8 ops run-lru-lookup-or-miss)
          :threads-8/caffeine-get-ns (threaded 8 ops run-caff-get)
          :caffeine-stats (str (.stats caff))})

;; Weighted eviction check: 3 entries of 300 MB against a 512 MB cap keep 1.
(def ^Cache heavy (-> (Caffeine/newBuilder) (.maximumWeight 512)
                      (.weigher (reify Weigher (weigh [_ _ v] (int v)))) (.executor (reify java.util.concurrent.Executor (execute [_ r] (.run r)))) (.build)))
(.put heavy :a 300) (.put heavy :b 300) (.put heavy :c 300) (.cleanUp heavy)
(println {:weighted-kept (.estimatedSize heavy) :policy-weighted (str (.. heavy policy eviction get weightedSize))})

;; Compute-once under contention: 8 threads miss the same key concurrently.
(def ^Cache once (-> (Caffeine/newBuilder) (.maximumSize 10) (.build)))
(def calls (java.util.concurrent.atomic.AtomicLong.))
(let [pool (Executors/newFixedThreadPool 8)
      fs (doall (for [_ (range 8)] (.submit pool ^java.util.concurrent.Callable (fn [] (.get once :k (reify Function (apply [_ k] (.incrementAndGet calls) (Thread/sleep 50) :v)))))))]
  (run! deref fs) (.shutdown pool))
(def lru-once (cw/lru-cache-factory {} :threshold 10))
(def lru-calls (java.util.concurrent.atomic.AtomicLong.))
(let [pool (Executors/newFixedThreadPool 8)
      fs (doall (for [_ (range 8)] (.submit pool ^java.util.concurrent.Callable (fn [] (cw/lookup-or-miss lru-once :k (fn [_] (.incrementAndGet lru-calls) (Thread/sleep 50) :v))))))]
  (run! deref fs) (.shutdown pool))
(println {:caffeine-derivations-for-8-concurrent-misses (.get calls)
          :core-cache-derivations-for-8-concurrent-misses (.get lru-calls)})
(shutdown-agents)

;; ---------------------------------------------------------------------------
;; The central-cache shape the design proposes: one AsyncCache, compute on the
;; caller's thread outside any map lock (nested derivations allowed), weight
;; taken when the future completes, failures never retained.
(import '[com.github.benmanes.caffeine.cache AsyncCache RemovalListener]
        '[java.util.concurrent CompletableFuture])
(def evicted (java.util.concurrent.ConcurrentHashMap.))
(def ^AsyncCache central
  (-> (Caffeine/newBuilder)
      (.maximumWeight 1000)
      (.weigher (reify Weigher (weigh [_ _ v] (int (:weight v 1)))))
      (.executor (reify java.util.concurrent.Executor (execute [_ r] (.run ^Runnable r))))
      (.removalListener (reify RemovalListener (onRemoval [_ k _ cause] (.merge ^java.util.concurrent.ConcurrentHashMap evicted (first k) 1 (reify java.util.function.BiFunction (apply [_ a b] (+ a b)))))))
      (.buildAsync)))
(defn cached [kind k derive]
  (let [key [kind k]
        held (.getIfPresent central key)
        mine (when-not held (CompletableFuture.))
        prior (or held (.putIfAbsent (.asMap central) key mine))]
    (if prior
      (.join ^CompletableFuture prior)
      (try (let [v (derive)] (.complete mine v) v)
           (catch Throwable t (.remove (.asMap central) key mine) (.completeExceptionally mine t) (throw t))))))
;; nested: a derivation that reads another kind from the same cache
(def nested (cached :outer 1 (fn [] {:weight 10 :inner (cached :inner 1 (fn [] {:weight 5 :x 1}))})))
(def failed (try (cached :refusal 1 (fn [] (throw (ex-info "refused" {})))) (catch Exception e (ex-message e))))
(def after-fail (.getIfPresent central [:refusal 1]))
(dotimes [i 300] (cached :bulk i (fn [] {:weight 10})))
(.cleanUp (.synchronous central))
(def async-hit-ns (time-ns-per-op 2000000 (fn [ops] (loop [i 0] (when (< i ops) (cached :outer 1 (fn [] nil)) (recur (inc i)))))))
(println {:nested-ok (= 1 (get-in nested [:inner :x])) :failure failed :failure-retained? (some? after-fail)
          :weighted-size (.. (.synchronous central) policy eviction get weightedSize getAsLong)
          :evicted-by-kind (into {} evicted)
          :async-hit-ns async-hit-ns})
(shutdown-agents)
