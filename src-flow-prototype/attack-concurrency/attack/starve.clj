(ns attack.starve
  "ATTACK 4 -- cross-agent interference that is not a data race:
   shared :compute capacity, and work that ESCAPES the eval."
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.eval :as eval]
            [flow.store :as store])
  (:import (java.util.concurrent CountDownLatch Executors)))

(defn section [n title] (println (format "\n--- %s. %s" n title)))

(defn- run! [conn id agent sources]
  (let [r (driver/start-run! conn {:run-id id :agent-id agent :sources sources})]
    (when (driver/claim! conn r (str "c" id) 600000)
      (driver/drive-run! conn r (str "c" id)))))

(defn -main [& [root]]
  (let [root (or root "/private/tmp/attack")]

    ;;; ------------------------------------------------------------------
    (section "4a" "STARVATION: 2 permits, 2 agents inside an un-interruptible host call")
    (let [conn (store/fresh! (str root "/s") {:config/compute-permits 2
                                              :config/time-limit-ms 300
                                              :config/lease-ms 600000})]
      (doseq [i (range 8)] (d/transact conn {:tx-data [{:agent/id (str "g" i) :agent/counter 0}]}))
      (println "    permits available:" (eval/available) " time-limit: 300ms")
      (let [p (Executors/newVirtualThreadPerTaskExecutor)
            start (CountDownLatch. 1)
            done (CountDownLatch. 8)
            lat (atom {})]
        (dotimes [i 8]
          (.submit p ^Runnable
                   (fn []
                     (.await start)
                     (let [t0 (System/nanoTime)
                           src (if (< i 2) "(do (host/block 6000) {:note \"hog\"})" "{:note \"innocent\"}")]
                       (run! conn (str "S" i) (str "g" i) [src])
                       (swap! lat assoc i (quot (- (System/nanoTime) t0) 1000000)))
                     (.countDown done))))
        (.countDown start)
        (.await done)
        (println "    latency ms by agent (0,1 = hogs; 2-7 = innocent):" (pr-str (into (sorted-map) @lat)))
        (println "    innocent max:" (apply max (map @lat (range 2 8))) "ms  hog:" (@lat 0) "ms")
        (println "    hogs' receipts:"
                 (pr-str (d/q '[:find ?o (count ?e) :where [?e :seon.eval/outcome ?o]] (d/db conn))))))

    ;;; ------------------------------------------------------------------
    (section "4b" "ESCAPE: a lazy seq returned from the eval, realized in the transform")
    (doseq [[label carrier]
            [["driven on the MAIN platform thread" :main]
             ["driven on an :io VIRTUAL thread (the real scan! path)" :virtual]]]
      (let [path (str root "/x-" (name carrier))
            conn (store/fresh! path {:config/time-limit-ms 300
                                     :config/allocation-limit-bytes (* 32 1024 1024)
                                     :config/lease-ms 600000})]
        (d/transact conn {:tx-data [{:agent/id "esc" :agent/counter 0}]})
        (let [t0 (System/nanoTime)
              body (fn []
                     (try (run! conn "X1" "esc"
                                ;; ~24M interpreted fn entries -- far past a 300ms limit
                                ["{:facts (map (fn [i] [:db/add [:agent/id \"esc\"] :agent/log (str \"line\" (mod i 3))]) (range 8000000))}"])
                          (catch Throwable t
                            [:THREW-INTO-THE-LOOP (.getName (Thread/currentThread))
                             (.getSimpleName (class t)) (.getMessage t)])))
              recs (if (= carrier :main)
                     (body)
                     (.get (.submit (Executors/newVirtualThreadPerTaskExecutor) ^java.util.concurrent.Callable body)))
              ms (quot (- (System/nanoTime) t0) 1000000)]
          (println (format "    %s:" label))
          (println "      result:" (pr-str (if (vector? recs)
                                             recs
                                             (map #(select-keys % [:seon.eval/outcome :seon.eval/ms
                                                                   :seon.eval/fn-entries :seon.eval/allocated-bytes]) recs))))
          (println "      wall clock for the step:" ms "ms   (time-limit was 300ms)")
          (println "      log datoms:" (count (d/q '[:find [?l ...] :where [?e :agent/log ?l]] (d/db conn)))
                   " run still open?" (pr-str (d/q '[:find [?id ...] :where [?r :run/open? true] [?r :run/id ?id]] (d/db conn)))
                   " claim still held?" (pr-str (d/q '[:find [?c ...] :where [?r :run/claimant ?c]] (d/db conn))))
          (println "      receipts:" (pr-str (sort (d/q '[:find ?i ?o :where [?e :seon.eval/index ?i] [?e :seon.eval/outcome ?o]] (d/db conn))))))))

    ;;; ------------------------------------------------------------------
    (section "4c" "ESCAPE: a lazy seq that calls db/q, realized off the :compute thread")
    (let [conn (store/fresh! (str root "/y") {:config/time-limit-ms 2000 :config/lease-ms 600000})]
      (d/transact conn {:tx-data [{:agent/id "esc2" :agent/counter 0}]})
      (let [outcome (try (run! conn "Y1" "esc2"
                               ["{:facts (map (fn [_] [:db/add [:agent/id \"esc2\"] :agent/log (str (db/basis))]) (range 2))}"])
                         (catch Throwable t [:THREW-INTO-THE-LOOP (.getSimpleName (class t)) (.getMessage t)]))]
        (println "    result:" (pr-str outcome))
        (println "    run still open?" (pr-str (d/q '[:find [?id ...] :where [?r :run/open? true] [?r :run/id ?id]] (d/db conn))))
        (println "    claimant still held?" (pr-str (d/q '[:find [?c ...] :where [?r :run/claimant ?c]] (d/db conn))))
        (println "    receipts:" (pr-str (sort (d/q '[:find ?i ?o :where [?e :seon.eval/index ?i] [?e :seon.eval/outcome ?o]] (d/db conn)))))))

    ;;; ------------------------------------------------------------------
    (section "4d" "N agents transacting simultaneously: coalescing curve")
    (doseq [n [1 10 50 200]]
      (let [conn (store/fresh! (str root "/t" n) {:config/lease-ms 600000})]
        (doseq [i (range n)] (d/transact conn {:tx-data [{:agent/id (str "n" i) :agent/counter 0}]}))
        (let [runs (mapv (fn [i] (driver/start-run! conn {:run-id (str "T" i) :agent-id (str "n" i)
                                                          :sources ["{:note \"one\"}"]}))
                         (range n))
              p (Executors/newVirtualThreadPerTaskExecutor)
              start (CountDownLatch. 1) done (CountDownLatch. n)
              t0 (atom nil)]
          (doseq [r runs]
            (.submit p ^Runnable (fn [] (.await start)
                                   (when (driver/claim! conn r "c" 600000) (driver/drive-run! conn r "c"))
                                   (.countDown done))))
          (reset! t0 (System/nanoTime))
          (.countDown start)
          (.await done)
          (let [ms (quot (- (System/nanoTime) @t0) 1000000)
                txs (* n 4)]                ; claim + receipt + step + close
            (println (format "    n=%-3d  %5d ms total   %.2f ms/tx over ~%d transactions   counters ok=%s"
                             n ms (double (/ ms txs)) txs
                             (every? #(= 1 (:agent/counter (d/pull (d/db conn) [:agent/counter] [:agent/id (str "n" %)]))) (range n))))))))

    (println "\nOK")
    (System/exit 0)))
