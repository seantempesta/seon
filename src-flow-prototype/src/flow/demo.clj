(ns flow.demo
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [flow.ctx :as ctx]
            [flow.driver :as driver]
            [flow.eval :as eval]
            [flow.interrupt :as interrupt]
            [flow.program :as program]
            [flow.store :as store]
            [sci.core :as sci])
  (:import (java.util.concurrent CountDownLatch Executors TimeUnit)))

(def root "/private/tmp/claude-501/-Users-sean-src-seon/ad6e7227-ef9f-4cc7-954e-ea6dbabccdff/scratchpad/flow")
(defn hdr [s] (println (str "\n=== " s " " (apply str (repeat (max 0 (- 66 (count s))) \=)))))
(defn limits [conn] (let [c (driver/config (d/db conn))]
                      {:time-limit-ms (:config/time-limit-ms c)
                       :allocation-limit-bytes (:config/allocation-limit-bytes c)}))

;;; 1. The transform is pure

(defn demo-pure [conn]
  (hdr "1  the transform is PURE: a database VALUE in, tx-data out")
  (let [db (d/db conn)
        step {:index 3 :value {:note "hello" :messages [{:to "a2" :body "ping"}]
                               :facts [[:db/add [:agent/id "a1"] :agent/log "x"]]}}
        out1 (driver/transform db "a1" step)
        out2 (driver/transform db "a1" step)]
    (println "called with NO connection; twice, identical:" (= out1 out2))
    (println "basis before" (:max-tx db) "-> after" (:max-tx (d/db conn))
             "(nothing committed)")
    (doseq [f out1] (println "  tx-data:" (pr-str f)))
    (println "the message is a FACT in this tx-data -- no effects slot exists")))

;;; 2. N agents, step-granular basis, read-your-own-writes

(defn demo-turns [conn n]
  (hdr (str "2  " n " agents, one turn each: step basis = previous step's db-after"))
  (d/transact conn {:tx-data (mapv (fn [i] {:agent/id (str "a" i)}) (range n))})
  (d/transact conn {:tx-data (mapv (fn [i] {:message/id (str "m" i)
                                            :message/to [:agent/id (str "a" i)]
                                            :message/body (pr-str {})}) (range n))})
  (let [t0 (System/nanoTime)
        opening (d/db conn)
        driven (driver/scan! conn "claimant-1" program/reply)
        ms (quot (- (System/nanoTime) t0) 1000000)
        db (d/db conn)]
    (println "drove" (count driven) "runs in" ms "ms")
    (println "\nagent a0 receipts (index, basis :t, outcome, ms):")
    (doseq [[i t o m] (sort (d/q '[:find ?i ?t ?o ?ms :where
                                   [?e :seon.eval/run ?r] [?r :run/agent ?a]
                                   [?a :agent/id "a0"] [?e :seon.eval/index ?i]
                                   [?e :seon.eval/basis-t ?t] [?e :seon.eval/outcome ?o]
                                   [?e :seon.eval/ms ?ms]] db))]
      (println (format "  %d  t=%d  %s  %dms" i t o m)))
    (println "\nbasis :t strictly increases across steps ="
             (let [ts (mapv second (sort (d/q '[:find ?i ?t :where
                                                [?e :seon.eval/run ?r] [?r :run/agent ?a]
                                                [?a :agent/id "a0"] [?e :seon.eval/index ?i]
                                                [?e :seon.eval/basis-t ?t]] db)))]
               (= ts (sort ts)) ))
    (println "a0 counter (one per completed step):"
             (:agent/counter (d/pull db [:agent/counter] [:agent/id "a0"])))
    (println "a0 log:")
    (doseq [l (sort (:agent/log (d/pull db [:agent/log] [:agent/id "a0"])))]
      (println "  " l))
    opening))

(defn demo-turn-basis-is-wrong [conn opening]
  (hdr "2b DEVIATION: the turn-level signature is unimplementable, shown")
  (let [db-now (d/db conn)
        t0 opening
        src "(count (db/q '[:find ?l :in $ ?a :where [?e :agent/id ?a] [?e :agent/log ?l]] \"a0\"))"
        at-open (:flow/value (eval/evaluate (merge {:source src :db t0} (limits conn))))
        at-step (:flow/value (eval/evaluate (merge {:source src :db db-now} (limits conn))))]
    (println "same form, turn's OPENING basis  ->" at-open)
    (println "same form, this step's basis     ->" at-step)
    (println "a turn-level (db, agent, message) -> [tx-data ...] transform can only")
    (println "offer the first answer. That is why the unit is the FORM.")))

;;; 3. Kills

(defn demo-kills [conn]
  (hdr "3  killed on time-limit and on allocation; flat :seon/error values")
  (doseq [[label src tl cap] [["runaway loop (time-limit 500ms, cap 4GB)" "(loop [] (recur))" 500 (* 4 1024 1024 1024)]
                              ["allocation in interpreted code (time-limit 5000ms, cap 64MB)"
                               "(loop [acc [] i 0] (if (< i 20000000) (recur (conj acc (str \"x\" i)) (inc i)) (count acc)))"
                               5000 (* 64 1024 1024)]
                              ["blocked in ONE host call (time-limit 500ms) -- THE HOLE"
                               "(host/block 900)" 500 (* 64 1024 1024)]]]
    (let [{:flow/keys [value record]} (eval/evaluate {:db (d/db conn) :source src
                                                      :time-limit-ms tl :allocation-limit-bytes cap})]
      (println (format "\n%s:" label))
      (println "  record:" (pr-str (dissoc record :flow/semaphore-wait-ms)))
      (println "  agent sees:" (if (eval/error? value)
                                 (pr-str (select-keys value [:seon.error/kind :seon.error/message]))
                                 (str "NO ERROR -- returned " (pr-str value)
                                      "; the safepoint never fired inside the host call"))))))

;;; 4. Wake through listen!, no ticker

(defn demo-wake [conn]
  (hdr "4  message -> listen! -> scan!: an event-driven chain, zero polling")
  (d/transact conn {:tx-data [{:agent/id "b1"} {:agent/id "b2"} {:agent/id "b3"}]})
  (let [done (CountDownLatch. 1)
        t0 (System/nanoTime)]
    (driver/wake! conn "claimant-wake" program/reply
                  (fn [_] (when (some #(str/starts-with? % "6:")
                                      (d/q '[:find [?l ...] :where [?e :agent/id "b3"] [?e :agent/log ?l]]
                                           (d/db conn)))
                            (.countDown done))))
    (d/transact conn {:tx-data [{:message/id "seed" :message/to [:agent/id "b1"]
                                 :message/body (pr-str {:chain ["b2" "b3"]})}]})
    (println "awaiting the chain b1 -> b2 -> b3 on a latch (no sleep, no poll)")
    (println "chain completed:" (.await done 60 TimeUnit/SECONDS)
             "in" (quot (- (System/nanoTime) t0) 1000000) "ms")
    (d/unlisten conn ::driver/wake)
    (doseq [a ["b1" "b2" "b3"]]
      (println " " a "receipts:" (sort (d/q '[:find ?i ?o :in $ ?a :where
                                              [?e :seon.eval/run ?r] [?r :run/agent ?ae]
                                              [?ae :agent/id ?a] [?e :seon.eval/index ?i]
                                              [?e :seon.eval/outcome ?o]] (d/db conn) a)))
      (println " " a "log:" (sort (:agent/log (d/pull (d/db conn) [:agent/log] [:agent/id a])))))
    (println "claims lost to CAS arbitration (harmless, expected):" @driver/claims-lost)
    (println "messages actually delivered:"
             (sort (d/q '[:find ?f ?t ?b :where [?m :message/from ?fe] [?fe :agent/id ?f]
                          [?m :message/to ?te] [?te :agent/id ?t] [?m :message/body ?b]]
                        (d/db conn))))))

;;; 5. Concurrency

(defn demo-concurrency [conn n]
  (hdr (str "5  write throughput: " n " transactions, serial vs concurrent"))
  (let [serial (let [t0 (System/nanoTime)]
                 (dotimes [i n] (d/transact conn {:tx-data [{:agent/id (str "s" i)}]}))
                 (/ (- (System/nanoTime) t0) 1e6 n))
        pool (Executors/newVirtualThreadPerTaskExecutor)
        latch (CountDownLatch. n)
        t0 (System/nanoTime)
        _ (dotimes [i n]
            (.submit ^java.util.concurrent.ExecutorService pool
                     ^Runnable (fn [] (try (d/transact conn {:tx-data [{:agent/id (str "c" i)}]})
                                           (finally (.countDown latch))))))
        _ (.await latch)
        concurrent (/ (- (System/nanoTime) t0) 1e6 n)]
    (.shutdown pool)
    (println (format "serial      %.2f ms/tx" serial))
    (println (format "concurrent  %.2f ms/tx  (%.1fx, Datahike coalesces)"
                     concurrent (/ serial concurrent)))))

;;; 6. :compute vs :io

(defn demo-thread-kinds [conn]
  (hdr "6  :compute is PLATFORM because allocation is only measurable there")
  (let [platform (Executors/newCachedThreadPool)
        virtual (Executors/newVirtualThreadPerTaskExecutor)
        probe (fn [] (let [a (interrupt/allocated-bytes)] (reduce + (range 100000))
                       [a (interrupt/allocated-bytes)]))]
    (println "platform thread getCurrentThreadAllocatedBytes:"
             (.get (.submit ^java.util.concurrent.ExecutorService platform ^java.util.concurrent.Callable probe)))
    (println "virtual  thread getCurrentThreadAllocatedBytes:"
             (.get (.submit ^java.util.concurrent.ExecutorService virtual ^java.util.concurrent.Callable probe)))
    (.shutdown platform) (.shutdown virtual))
  (println "semaphore permits (from the CONFIG FACT):" (eval/available))
  (let [n (+ 4 (eval/available))
        pool (Executors/newVirtualThreadPerTaskExecutor)
        latch (CountDownLatch. n)
        waits (atom [])
        t0 (System/nanoTime)]
    (dotimes [_ n]
      (.submit ^java.util.concurrent.ExecutorService pool
               ^Runnable (fn [] (try (let [r (:flow/record
                                              (eval/evaluate (merge {:source "(reduce + (map inc (range 300000)))"
                                                                     :db (d/db conn)} (limits conn))))]
                                       (swap! waits conj (:flow/semaphore-wait-ms r)))
                                     (finally (.countDown latch))))))
    (.await latch)
    (.shutdown pool)
    (println (format "%d concurrent evals, %d permits: %d queued on the semaphore, max wait %dms, total %dms"
                     n (eval/available) (count (filter pos? @waits)) (apply max @waits)
                     (quot (- (System/nanoTime) t0) 1000000)))
    (println "exhaustion QUEUES (blocks the :io caller); it never bounces the claim")))

;;; 7. The hole, demonstrated

(defn demo-hole [conn]
  (hdr "7  the hole: one un-overridden host allocation past the cap")
  (let [{:flow/keys [value record]}
        (eval/evaluate (merge {:source "(alength (byte-array 100000000))" :db (d/db conn)}
                              (limits conn)))]
    (println "cap is" (:allocation-limit-bytes (limits conn)) "bytes; (byte-array 100000000) ->"
             (pr-str value))
    (println "record:" (pr-str (dissoc record :flow/semaphore-wait-ms)))
    (println "NOT killed: a single host allocation reaches no fn entry, so no sample happens."))
  (let [{:flow/keys [value]} (eval/evaluate (merge {:source "(alength (byte-array 2000000000))"
                                                    :db (d/db conn)} (limits conn)))]
    (println "\n(byte-array 2000000000) with -Xmx512m ->" (pr-str (:seon.error/raw value)))
    (println "writer still alive after the OOM:"
             (some? (d/transact conn {:tx-data [{:agent/id "after-oom"}]})))
    (println "BLAST RADIUS: with the writer co-located, this heap is the writer's heap.")))

;;; 8. The fork invariant, as a negative test

(defn demo-fork [_conn]
  (hdr "8  fork invariant: new defs isolated, shared mutable state is NOT")
  (let [ctx-a (ctx/fork nil) ctx-b (ctx/fork nil)]
    (sci/eval-form ctx-a '(def only-in-a 42))
    (println "fork A defined only-in-a; fork B sees:"
             (try (sci/eval-form ctx-b 'only-in-a)
                  (catch Exception e (str "unresolved -- " (first (str/split-lines (ex-message e)))))))
    (let [shared (sci/fork @ctx/base)]
      (sci/eval-form shared '(def ^:dynamic shared-atom (atom 0)))
      (let [c1 (sci/fork shared) c2 (sci/fork shared)]
        (sci/eval-form c1 '(swap! shared-atom inc))
        (println "fork A swapped an atom held by a BASE var; fork B reads:"
                 (sci/eval-form c2 '@shared-atom))
        (println "=> the invariant 'base vars hold only fns and immutable values' is load-bearing")))))

;;; main

(defn demo-containment [conn]
  (hdr "9  containment under concurrency: two bad actors, 18 healthy agents")
  (let [good "(reduce + (map inc (range 200000)))"
        one (fn [out latch [tag src tl cap]]
              (fn [] (try (let [t0 (System/nanoTime)
                                r (eval/evaluate {:db (d/db conn) :source src :time-limit-ms tl
                                                  :allocation-limit-bytes cap})]
                            (swap! out conj [tag (quot (- (System/nanoTime) t0) 1000000)
                                             (:seon.eval/outcome (:flow/record r))]))
                          (finally (.countDown ^CountDownLatch latch)))))
        run-batch (fn [srcs]
                    (let [pool (Executors/newVirtualThreadPerTaskExecutor)
                          out (atom [])
                          latch (CountDownLatch. (count srcs))]
                      (doseq [s srcs]
                        (.submit ^java.util.concurrent.ExecutorService pool
                                 ^Runnable (one out latch s)))
                      (.await latch) (.shutdown pool) @out))
        base (run-batch (repeat 18 [:good good 5000 (* 512 1024 1024)]))
        mixed (run-batch (concat (repeat 18 [:good good 5000 (* 512 1024 1024)])
                                 [[:runaway "(loop [] (recur))" 500 (* 4 1024 1024 1024)]
                                  [:hog "(loop [acc [] i 0] (if (< i 20000000) (recur (conj acc (str \"x\" i)) (inc i)) 0))" 5000 (* 64 1024 1024)]]))
        ms (fn [rs tag] (let [v (sort (map second (filter #(= tag (first %)) rs)))]
                          [(first v) (nth v (quot (count v) 2)) (last v)]))]
    (println "18 healthy agents alone         [min median max] ms:" (ms base :good))
    (println "same 18 + a runaway + a heap hog             ms:" (ms mixed :good))
    (println "bad actors' outcomes:" (mapv (juxt first last) (remove #(= :good (first %)) mixed)))))

(defn -main [& _]
  (let [conn (store/fresh! (str root "/store-demo"))]
    (demo-pure conn)
    (demo-turn-basis-is-wrong conn (demo-turns conn 5))
    (demo-kills conn)
    (demo-wake conn)
    (demo-thread-kinds conn)
    (demo-concurrency conn 200)
    (demo-fork conn)
    (demo-containment conn)
    (demo-hole conn)
    (println "\ndone.")
    (shutdown-agents)
    (System/exit 0)))
