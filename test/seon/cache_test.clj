(ns seon.cache-test
  "The one process cache: one derivation per key, a byte budget, failures and
  refusals never retained. Each test builds its own cache value; nothing here
  touches the process cache."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cache :as cache]
            [seon.cluster]
            [seon.fn]
            [seon.task])
  (:import [java.util.concurrent CountDownLatch Executors TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(defn- settle
  "Run Caffeine's pending maintenance, then await its removal notifications,
  so eviction is observable now. Caffeine runs the removal listener (which
  counts evictions and bytes) on the cache's executor, by default
  `ForkJoinPool/commonPool`; `cleanUp` only schedules those notifications, so
  under load a status read could miss the last ones (red at 16:31 on
  2026-09-26: 16 evictions, 1,200 bytes)."
  [held]
  (.cleanUp (.synchronous ^com.github.benmanes.caffeine.cache.AsyncCache (::cache/cache held)))
  (.awaitQuiescence (java.util.concurrent.ForkJoinPool/commonPool) 5 java.util.concurrent.TimeUnit/SECONDS))

(deftest concurrent-misses-of-one-key-derive-once
  (let [held (cache/create 1000000)
        derivations (AtomicLong.)
        start (CountDownLatch. 1)
        pool (Executors/newFixedThreadPool 8)
        answers (try
                  (let [futures (doall
                                 (for [_ (range 8)]
                                   (.submit pool ^Callable
                                            (fn []
                                              (.await start)
                                              (cache/lookup held ::k :same (constantly 10)
                                                            #(do (.incrementAndGet derivations)
                                                                 (Thread/sleep 50)
                                                                 :v)
                                                            some?)))))]
                    (.countDown start)
                    (mapv #(.get ^java.util.concurrent.Future % 5 TimeUnit/SECONDS) futures))
                  (finally (.shutdownNow pool)))]
    (is (= (repeat 8 :v) answers))
    (is (= 1 (.get derivations)))
    (is (= {:seon.cache/hits 7 :seon.cache/misses 1}
           (select-keys (get-in (cache/status held) [:seon.cache/kinds ::k])
                        [:seon.cache/hits :seon.cache/misses])))))

(deftest a-derivation-may-look-up-another-kind
  (let [held (cache/create 1000000)]
    (is (= 42 (cache/lookup held ::outer 1 (constantly 10)
                            #(inc (cache/lookup held ::inner 1 (constantly 10) (constantly 41) some?))
                            some?)))))

(deftest failures-and-refusals-are-not-retained
  (let [held (cache/create 1000000)
        calls (atom 0)]
    (testing "a thrown derivation rethrows as itself and the next caller derives again"
      (is (= "boom" (try (cache/lookup held ::boom 1 (constantly 1) #(throw (ex-info "boom" {})) some?)
                         (catch clojure.lang.ExceptionInfo e (ex-message e)))))
      (is (= :ok (cache/lookup held ::boom 1 (constantly 1) (constantly :ok) some?))))
    (testing "a value retain? refuses answers every caller and is derived each time"
      (dotimes [_ 3]
        (is (= {:refused true}
               (cache/lookup held ::refusal 1 (constantly 1)
                             #(do (swap! calls inc) {:refused true})
                             (complement :refused)))))
      (is (= 3 @calls)))
    (testing "nil is never retained"
      (is (nil? (cache/lookup held ::nil 1 (constantly 1) (constantly nil) some?)))
      (is (= 1 (cache/lookup held ::nil 1 (constantly 1) (constantly 1) some?))))
    (testing "only retained values are held, by content key"
      (is (= {1 :ok} (cache/retained held ::boom)))
      (is (= {} (cache/retained held ::refusal))))))

(deftest the-byte-budget-evicts-and-status-reports-it
  (let [held (cache/create 1000)]
    (dotimes [i 20]
      (cache/lookup held ::big i (constantly 300) (constantly i) some?))
    (settle held)
    (let [{:seon.cache/keys [max-bytes weighted-bytes kinds]} (cache/status held)
          big (get kinds ::big)]
      (is (= 1000 max-bytes))
      (is (<= weighted-bytes 1000))
      (is (= 20 (:seon.cache/misses big)))
      (is (<= 17 (:seon.cache/evictions big)))
      (is (= weighted-bytes (:seon.cache/bytes big))
          "the per-kind bytes follow insertions and evictions"))))

(deftest a-zero-weight-value-still-counts-against-the-budget
  ;; Astra audit F3: Caffeine never evicts a zero-weight entry by size, so a
  ;; weigh function answering 0 (an empty population) retained without bound.
  (let [held (cache/create 2000)]
    (dotimes [i 50]
      (cache/lookup held ::empty i (constantly 0) (constantly i) some?))
    (settle held)
    (let [{:seon.cache/keys [weighted-bytes kinds]} (cache/status held)]
      (is (<= weighted-bytes 2000))
      (is (< (count (cache/retained held ::empty)) 50))
      (is (pos? (get-in kinds [::empty :seon.cache/evictions]))))))

(deftest retiring-kinds-removes-only-their-entries
  (doseq [n [1 10 100]]
    (let [held (cache/create 1000000)]
      (doseq [kind [::retired-a ::retired-b ::kept] k (range n)]
        (cache/lookup held kind k (constantly 10) (constantly k) some?))
      (is (= (* 2 n) (cache/evict-kinds! held #{::retired-a ::retired-b})))
      (settle held)
      (is (empty? (cache/retained held ::retired-a)))
      (is (empty? (cache/retained held ::retired-b)))
      (is (= (zipmap (range n) (range n)) (cache/retained held ::kept)))
      (is (zero? (get-in (cache/status held) [:seon.cache/kinds ::retired-a :seon.cache/bytes])))
      (is (= 0 (cache/evict-kinds! held #{::retired-a ::retired-b}))))))

(deftest a-deadline-reader-gets-pending-and-the-one-derivation-completes
  (let [held (cache/create 1000000)
        release (CountDownLatch. 1)
        derivations (AtomicLong.)
        woke (CountDownLatch. 1)
        slow #(do (.incrementAndGet derivations) (.await release 5 TimeUnit/SECONDS) :derived)
        first-read (cache/lookup-within held ::slow :k (constantly 10) slow 10 #(.countDown woke))
        second-read (cache/lookup-within held ::slow :k (constantly 10) slow 10 nil)]
    (is (= ::slow (:seon.cache/pending-kind first-read)) "past its deadline a reader gets pending, not a wait")
    (is (inst? (:seon.cache/since second-read)))
    (.countDown release)
    (is (.await woke 5 TimeUnit/SECONDS) "completion runs the reader's wake")
    (is (= :derived (cache/lookup-within held ::slow :k (constantly 10) slow 10 nil)))
    (is (= 1 (.get derivations)) "readers join the one derivation; none is started twice or cancelled")))

(deftest evicting-one-entry-lets-its-owner-replace-it
  (let [held (cache/create 1000000)]
    (cache/lookup held ::reads :k (constantly 10) (constantly #{:a}) some?)
    (cache/evict! held ::reads :k)
    (is (= #{:a :b} (cache/lookup held ::reads :k (constantly 10) (constantly #{:a :b}) some?)))))

(deftest kind-status-names-its-kind-and-states-hit-and-byte-shares
  (let [held (cache/create 1000000)]
    (cache/lookup held ::big :k (constantly 1000) (constantly :v) some?)
    (dotimes [_ 3] (cache/lookup held ::big :k (constantly 1000) (constantly :v) some?))
    (cache/lookup held ::small :k (constantly 10) (constantly :v) some?)
    (settle held)
    (let [{kinds :seon.cache/kinds} (cache/status held)]
      (is (= ::big (get-in kinds [::big :seon.cache/kind])))
      (is (= 75 (get-in kinds [::big :seon.cache/hit-percent])) "three hits of four lookups")
      (is (< (get-in kinds [::small :seon.cache/byte-percent]) (get-in kinds [::big :seon.cache/byte-percent]))))))

(defn- value-bytes
  "A coarse retained size of a plain value, counted from its leaves: 40 bytes and one per
  character for a string, 24 for any other leaf, 48 per collection and 32 per element."
  [v]
  (cond (string? v) (+ 40 (count v))
        (map? v) (reduce + 48 (map (fn [[k x]] (+ 32 (value-bytes k) (value-bytes x))) v))
        (coll? v) (reduce + 48 (map #(+ 32 (value-bytes %)) v))
        :else 24))

(deftest a-kinds-weight-counts-its-value-not-its-key
  (let [digest (apply str (repeat 64 "a"))
        declarations (into {} (map (fn [i] [(str "t" i) {:seon.task/name (symbol (str "n" i))
                                                          :seon.task/intent (apply str (repeat 200 "x"))
                                                          :seon.task/kind :function}])) (range 200))
        tx (mapv (fn [i] [:db/add i :seon.task/execution :held]) (range 200))
        world {:seon.cluster/key digest :seon.cluster/absent digest
               :seon.cluster/digests (into {} (map (fn [i] [(keyword "k" (str i)) digest])) (range 3000))
               :seon.cluster/shapes (set (map #(keyword "k" (str %)) (range 100)))}]
    (doseq [[kind weigh value] [[:seon.task/task-basis @#'seon.task/task-basis-weight declarations]
                                [:seon.task/tasks-tx @#'seon.task/tasks-tx-weight tx]
                                [:seon.cluster/derivation-world @#'seon.cluster/derivation-world-weight world]
                                [:seon.cluster/branch-attribute-changes @#'seon.cluster/branch-attribute-changes-weight
                                 (mapv #(keyword "a" (str %)) (range 300))]
                                [:seon.fn/population-targets @#'seon.fn/population-targets-weight
                                 (into {} (map (fn [i] [(keyword "a" (str i)) (set (map #(symbol "n" (str %)) (range 5)))])) (range 50))]]]
      (testing kind
        (let [ratio (/ (double (weigh value)) (value-bytes value))]
          (is (<= 0.5 ratio 2.0) "the charge is within 2x of the value's size, whatever the projection"))))
    (is (empty? (cache/retained :seon.task/file-grammar)) "the never-hit grammar digest is not cached")))

(deftest a-presence-probe-counts-no-miss
  (let [held (cache/create 1000000)
        counted (fn [k] (get-in (cache/status held) [:seon.cache/kinds ::probed k] 0))]
    (is (nil? (cache/present held ::probed :k)) "an absent key answers nil")
    (is (zero? (counted :seon.cache/misses)) "the probe counted no miss")
    (is (= :v (cache/lookup held ::probed :k (constantly 10) (constantly :v) some?)))
    (is (= 1 (counted :seon.cache/misses)) "the real lookup counted one miss")
    (is (= :v (cache/present held ::probed :k)) "a completed entry answers its value")
    (is (= 1 (counted :seon.cache/misses)))
    (is (zero? (counted :seon.cache/hits)) "the probe counted no hit")))
