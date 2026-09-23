(ns seon.cluster.publication-serialize-test
  "One publication of the store at a time, and no unpublished bytes reloaded."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.test-support :as support])
  (:import [java.util.concurrent CountDownLatch]))

(defn- publication-thread
  "A thread calling `refresh-source!` whose progress goes to `progress!`; its
  answer (or refusal) lands in `outcome` before `done` counts down."
  [progress! outcome done]
  (Thread. ^Runnable
           (fn [] (try (reset! outcome
                               (try (with-bindings {#'cluster/*source-progress!* progress!}
                                      (cluster/refresh-source! "tmp/publication-serialize-unused-root"))
                                    (catch clojure.lang.ExceptionInfo stop stop)))
                       (finally (.countDown ^CountDownLatch done))))))

(defn- await-blocked!
  "Spin, within the event backstop, until `thread` blocks on a monitor."
  [^Thread thread]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (cond (= Thread$State/BLOCKED (.getState thread)) true
            (< deadline (System/nanoTime))
            (throw (ex-info "Second publication never blocked on the monitor."
                            {::state (str (.getState thread))}))
            :else (do (Thread/onSpinWait) (recur))))))

(deftest a-second-publication-waits-for-the-first-before-reading-the-head
  (let [events (atom [])
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        stop #(throw (ex-info "stop before store work" {::at %}))
        [first-outcome second-outcome] [(atom nil) (atom nil)]
        [first-done second-done] [(CountDownLatch. 1) (CountDownLatch. 1)]
        first-thread
        (publication-thread (fn [phase]
                              (swap! events conj [:first phase])
                              (when (= "bootstrap configuration" phase)
                                (.countDown entered)
                                (support/await-event! release "first publication released")
                                (stop :first)))
                            first-outcome first-done)
        second-thread
        (publication-thread (fn [phase]
                              (swap! events conj [:second phase])
                              (when (= "bootstrap configuration" phase) (stop :second)))
                            second-outcome second-done)]
    (try
      (.start first-thread)
      (support/await-event! entered "first publication holds the monitor")
      (.start second-thread)
      ;; The second request is accepted, then blocks on the monitor: its head
      ;; capture (inside `full-source-refresh!`) cannot start yet.
      (await-blocked! second-thread)
      (is (= [[:first "request accepted"] [:first "bootstrap configuration"]
              [:second "request accepted"]] @events))
      (finally (.countDown release)))
    (support/await-event! first-done "first publication exits")
    (support/await-event! second-done "second publication exits")
    (is (= :first (::at (ex-data @first-outcome))))
    (is (= :second (::at (ex-data @second-outcome))))
    (is (= [:second "bootstrap configuration"] (last @events))
        "the waiter runs only after the first releases the monitor")))

(deftest adoption-refuses-a-namespace-whose-disk-bytes-were-never-published
  (let [database (db/db (:seon.db/connection (support/execution-handle nil)))
        directory (io/file "tmp" (str "publication-serialize-" (random-uuid)))
        file (io/file directory "src/seon/id.clj")]
    (try
      (io/make-parents file)
      (spit file "(ns seon.id)\n;; another lane's unpublished edit\n")
      (let [refusal (try (#'cluster/verify-development-sources!
                          database (.getPath directory) #{'seon.id})
                         (catch clojure.lang.ExceptionInfo refused refused))]
        (is (= "Source changed during development adoption." (ex-message refusal)))
        (is (= ["src/seon/id.clj"] (get-in (ex-data refusal) [:seon.boot/offense :seon.source/changed-paths]))))
      (finally (.delete file) (.delete (.getParentFile file))
               (.delete (.getParentFile (.getParentFile file))) (.delete directory)))))
