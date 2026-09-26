(ns seon.cache-test
  "The one process cache: one derivation per key, a byte budget, failures and
  refusals never retained. Each test builds its own cache value; nothing here
  touches the process cache."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cache :as cache])
  (:import [java.util.concurrent CountDownLatch Executors TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(defn- settle
  "Run Caffeine's pending maintenance so eviction is observable now."
  [held]
  (.cleanUp (.synchronous ^com.github.benmanes.caffeine.cache.AsyncCache (::cache/cache held))))

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
      (is (= 1 (cache/lookup held ::nil 1 (constantly 1) (constantly 1) some?))))))

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
