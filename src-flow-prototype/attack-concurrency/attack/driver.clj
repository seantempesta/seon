(ns attack.driver
  "ATTACK 2 -- many claimants, many runs, one database. Looking for lost
   updates, double execution, and torn reads."
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.eval :as eval]
            [flow.store :as store])
  (:import (java.util.concurrent CountDownLatch Executors)))

(defn- pool [] (Executors/newVirtualThreadPerTaskExecutor))

(defn- race!
  "Run f over items simultaneously; every thread waits on one latch first."
  [items f]
  (let [start (CountDownLatch. 1)
        done (CountDownLatch. (count items))
        p (pool)]
    (doseq [x items]
      (.submit p ^Runnable (fn [] (try (.await start) (f x)
                                       (catch Throwable t (println "  thread threw:" (.getMessage t)))
                                       (finally (.countDown done))))))
    (.countDown start)
    (.await done)))

(defn- agent! [conn id] (d/transact conn {:tx-data [{:agent/id id :agent/counter 0}]}))

(defn counter [conn id]
  (:agent/counter (d/pull (d/db conn) [:agent/counter] [:agent/id id])))

(defn section [n title] (println (format "\n--- %s. %s" n title)))

(defn -main [& [root]]
  (let [root (or root "/private/tmp/attack")]

    ;;; ------------------------------------------------------------------
    (section "2a" "LOST UPDATE: N concurrent runs for ONE agent, each +1 to :agent/counter")
    (let [conn (store/fresh! (str root "/a") {:config/lease-ms 60000})
          n 40]
      (agent! conn "solo")
      (let [runs (mapv (fn [i]
                         (driver/start-run! conn {:run-id (str "r" i) :agent-id "solo"
                                                  :sources ["{:note \"tick\"}"]}))
                       (range n))]
        (race! runs (fn [r] (when (driver/claim! conn r (str "c" (rand-int 999)) 60000)
                              (driver/drive-run! conn r "c"))))
        (let [got (counter conn "solo")
              receipts (d/q '[:find (count ?e) . :where [?e :seon.eval/outcome :ok]] (d/db conn))]
          (println (format "    runs=%d  :ok receipts=%s  :agent/counter=%s  LOST=%s"
                           n receipts got (- n (or got 0))))
          (println "    every run closed?"
                   (empty? (d/q '[:find ?r :where [?r :run/open? true]] (d/db conn)))))))

    ;;; ------------------------------------------------------------------
    (section "2b" "CAS: 60 claimants race for ONE run")
    (let [conn (store/fresh! (str root "/b"))]
      (agent! conn "one")
      (let [r (driver/start-run! conn {:run-id "solo" :agent-id "one"
                                       :sources ["{:note \"a\"}"]})
            winners (atom [])]
        (reset! driver/claims-lost 0)
        (race! (range 60) (fn [i] (when (driver/claim! conn r (str "c" i) 60000)
                                    (swap! winners conj i))))
        (println (format "    winners=%d %s  lost=%d  final epoch=%s"
                         (count @winners) (pr-str @winners) @driver/claims-lost
                         (:run/epoch (d/pull (d/db conn) [:run/epoch] r))))))

    ;;; ------------------------------------------------------------------
    (section "2c" "STALE CLAIMANT: lease expires while A is still driving")
    (let [conn (store/fresh! (str root "/c") {:config/lease-ms 1 :config/time-limit-ms 5000})]
      (agent! conn "victim")
      (let [src ["{:note \"s0\"}" "(do (host/block 400) {:note \"s1\"})"
                 "{:note \"s2\"}" "{:note \"s3\"}"]
            r (driver/start-run! conn {:run-id "leaky" :agent-id "victim" :sources src})
            seen (atom [])
            drive (fn [me]
                    (when (driver/claim! conn r me 1)
                      (try (driver/drive-run! conn r me
                                              (fn [i s] (swap! seen conj [me i])))
                           (catch Throwable t [:threw (.getMessage t)]))))]
        (let [p (pool)
              f1 (.submit p ^Runnable (fn [] (drive "A")))]
          (Thread/sleep 250)                  ; A is inside step 1's host/block
          (println "    claimable while A drives?"
                   (pr-str (driver/claimable (d/db conn))))
          (let [f2 (.submit p ^Runnable (fn [] (drive "B")))]
            (.get f1) (.get f2)))
        (println "    step executions [claimant index]:" (pr-str @seen))
        (println (format "    distinct steps=%d executions=%d  :agent/counter=%s (plan had %d steps)"
                         (count (distinct (map second @seen))) (count @seen)
                         (counter conn "victim") (count src)))
        (println "    final :ok receipts:"
                 (pr-str (sort (d/q '[:find [?i ...] :where [?e :seon.eval/index ?i]
                                      [?e :seon.eval/outcome :ok]] (d/db conn)))))
        (println "    log lines:" (pr-str (sort (d/q '[:find [?l ...] :where [?e :agent/log ?l]] (d/db conn)))))))

    ;;; ------------------------------------------------------------------
    (section "2d" "THROUGHPUT: 100 agents x 3 steps, 100 claimants at once")
    (let [conn (store/fresh! (str root "/d") {:config/lease-ms 60000})
          n 100]
      (doseq [i (range n)] (agent! conn (str "a" i)))
      (let [runs (mapv (fn [i] (driver/start-run!
                                conn {:run-id (str "R" i) :agent-id (str "a" i)
                                      :sources ["{:note \"p\"}" "{:note \"q\"}" "{:note \"r\"}"]}))
                       (range n))
            t0 (System/nanoTime)]
        (race! runs (fn [r] (when (driver/claim! conn r "c" 60000) (driver/drive-run! conn r "c"))))
        (let [ms (quot (- (System/nanoTime) t0) 1000000)
              txs (- (:max-tx (d/db conn)) 536870912)]
          (println (format "    %d runs x 3 steps in %d ms  (%.2f runs/s)" n ms (/ (* 1000.0 n) ms)))
          (println (format "    counters all 3? %s   basis tx ordinal=%d"
                           (every? #(= 3 (counter conn (str "a" %))) (range n)) txs))
          (println "    :ok receipts:"
                   (d/q '[:find (count ?e) . :where [?e :seon.eval/outcome :ok]] (d/db conn))
                   " expected" (* 3 n)))))

    ;;; ------------------------------------------------------------------
    (section "2e" "SHARED-AGENT LOG: cardinality-many set semantics under concurrency")
    (let [conn (store/fresh! (str root "/e") {:config/lease-ms 60000})]
      (agent! conn "logger")
      (let [runs (mapv (fn [i] (driver/start-run!
                                conn {:run-id (str "L" i) :agent-id "logger"
                                      :sources ["{:note \"same-note\" :facts [[:db/add [:agent/id \"logger\"] :agent/log \"opened\"]]}"]}))
                       (range 10))]
        (race! runs (fn [r] (when (driver/claim! conn r "c" 60000) (driver/drive-run! conn r "c"))))
        (println "    10 runs each appending the same two log strings ->"
                 (count (d/q '[:find [?l ...] :where [?e :agent/log ?l]] (d/db conn)))
                 "distinct datoms:"
                 (pr-str (sort (d/q '[:find [?l ...] :where [?e :agent/log ?l]] (d/db conn)))))
        (println "    :agent/counter =" (counter conn "logger") "(10 runs)")))

    (println "\nOK")
    (System/exit 0)))
