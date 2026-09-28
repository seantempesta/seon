(ns seon.cache-test
  "The one process cache: one derivation per key, a byte budget, failures and
  refusals never retained. Each test builds its own cache value; nothing here
  touches the process cache."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cache :as cache])
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
