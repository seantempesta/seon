(ns r3.admit
  "R3's admission policy for FX1's effect owner (seon.fx/admit), as pure functions over the
  owner's state and the request's attributes, plus a virtual-time run of the owner's
  dispatch to measure throughput under a rate limit and the decision cost.

  The request carries what its task's attributes say (the stand-in reads them once per
  attempt from the task): :prio (chain progress: the task's depth in the needs DAG),
  :budget (the template's :seon.task/budget tokens for its kind, inherited along :needs),
  :budget-key (the task id) and :est (the prompt's estimated tokens). The owner's state
  holds the lanes, the queue, the spend per key and one token bucket per provider.

  jolt -A:test run -m r3.admit"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn- error]]))

(defn refill
  "The bucket at time now-ms: tokens grow at :per-ms up to :cap since :at."
  {:malli/schema [:=> [:cat [:map [:tokens number?] [:cap number?] [:per-ms number?] [:at :int]] :int]
                  [:map [:tokens number?] [:cap number?] [:per-ms number?] [:at :int]]]}
  [{:keys [tokens cap per-ms at] :as b} now-ms]
  (assoc b :tokens (min cap (+ tokens (* per-ms (max 0 (- now-ms at))))) :at (max at now-ms)))

(defn admit
  "What the owner does with request m at now-ms: :run, :wait (no free lane, or the bucket
  holds fewer tokens than the request's estimate: the clock's next :tick re-dispatches),
  or the refusal :seon.fx/budget-spent (its task spent its budget)."
  {:malli/schema [:=> [:cat :map :map :int] [:or [:enum :run :wait] :map]]}
  [st {:keys [budget budget-key est]} now-ms]
  (cond
    (and budget (>= (get-in st [:spent budget-key] 0) budget))
    (error :seon.fx/budget-spent "the task's model budget is spent" {:budget budget :spent (get-in st [:spent budget-key])})
    (>= (:busy st) (:lanes st)) :wait
    (and (:bucket st) (< (:tokens (refill (:bucket st) now-ms)) (or est 0))) :wait
    :else :run))

(defn take-tokens
  "The owner after running m: the bucket debited by m's estimate (its actual cost settles
  at :done: settle)."
  {:malli/schema [:=> [:cat :map :map :int] :map]}
  [st m now-ms]
  (cond-> (update st :busy inc)
    (:bucket st) (update :bucket #(update (refill % now-ms) :tokens - (or (:est m) 0)))))

(defn settle
  "The owner at a lane's :done: the lane is free, the task's spend grows by the actual
  tokens, the bucket gets back (or pays) the difference from the estimate."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st {:keys [budget-key est cost]}]
  (cond-> (-> st (update :busy dec) (update-in [:spent budget-key] (fnil + 0) (or cost 0)))
    (:bucket st) (update-in [:bucket :tokens] + (- (or est 0) (or cost 0)))))

;; ---- virtual-time run of the owner's dispatch ------------------------------------------

(defn- dispatch
  "FX1's dispatch with this admit: take queued requests in priority order while admitted."
  [st now]
  (loop [st st started []]
    (if-let [[qk m] (first (:queue st))]
      (let [d (admit st m now)]
        (cond (= :wait d) [st started]
              (= :run d) (recur (-> (take-tokens st m now) (update :queue dissoc qk)) (conj started m))
              :else (recur (-> st (update :queue dissoc qk) (update :refused inc)) started)))
      [st started])))

(defn simulate
  "n agents each asking k model calls in turn (the next after the reply), lanes, a bucket of
  rate tokens/s (nil: none), lat-ms per call, est tokens per call; tick-ms clock. Answers
  throughput, wall, queue wait and the cost of each admission decision (real ns)."
  [{:keys [n k lanes rate lat-ms est tick-ms] :or {k 4 lat-ms 800 est 1000 tick-ms 10}}]
  (let [st0 {:lanes lanes :busy 0 :queue (sorted-map) :spent {} :refused 0
             :bucket (when rate {:tokens (double rate) :cap (double rate) :per-ms (/ rate 1000.0) :at 0})}
        enq (fn [st [a i] now] (assoc-in st [:queue [(- (mod a 7)) a i]] {:agent a :i i :est est :cost est :budget-key a :budget (* 10 est k) :at now}))
        st0 (reduce #(enq %1 [%2 0] 0) st0 (range n))
        t0 (System/nanoTime)]
    (loop [now 0 st st0 inflight (sorted-map) done 0 waits [] decisions 0]
      (if (= done (* n k))
        (let [ns (- (System/nanoTime) t0)]
          {:agents n :lanes lanes :rate rate :calls done :wall-s (/ now 1000.0)
           :calls-per-s (Math/round (/ done (/ now 1000.0))) :wait-p50-ms (nth (sort waits) (quot (count waits) 2))
           :ns-per-decision (Math/round (/ (double ns) (max 1 decisions)))})
        (let [[st started] (dispatch st now)
              inflight (reduce (fn [f m] (assoc f [(+ now lat-ms) (:agent m) (:i m)] m)) inflight started)
              waits (into waits (map #(- now (:at %)) started))
              ;; next event: a lane finishing or the clock's tick
              nxt (min (+ now tick-ms) (or (ffirst (first inflight)) Long/MAX_VALUE))
              fin (take-while #(<= (first (key %)) nxt) inflight)
              st (reduce (fn [st [_ m]] (let [st (settle st m)] (if (< (inc (:i m)) k) (enq st [(:agent m) (inc (:i m))] nxt) st))) st fin)]
          (recur nxt st (apply dissoc inflight (map key fin)) (+ done (count fin)) waits (+ decisions (count started) 1)))))))

(defn -main [& _]
  (doseq [n [32 256 1000]
          [lanes rate] [[256 nil] [256 200000] [64 nil]]]
    (println (pr-str (simulate {:n n :lanes lanes :rate rate}))))
  (System/exit 0))
