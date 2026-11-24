(ns seon.health.liveness
  "NASA-style finite-state BFS + iterative Tarjan-SCC liveness model checker directly over
   `seon.reconciler.policy/decide-agent-step`. Proves absence of livelock cycles, orphan paused
   parents, wedged in-flight `:testing`/`:merging`, crashed auto-merges, and stranded ready states."
  (:require [seon.reconciler.policy :as policy]))

(def default-bounds
  "Compact, sound finite bounds for exhaustive state-space verification (< 10 ms)."
  {:max-tool-turns 2 :max-bounces 2 :max-total-turns 3})

(defn initial-states
  "Returns canonical initial abstract states covering workers and `:merge-target` reviewers."
  {:malli/schema [:=> [:cat] [:vector :map]]
   :cost/ticks 50000 :cost/bytes 65536}
  []
  (let [base {:paused? false :busy? false :waiting? false :raw-ready? false
              :tested? false :merged? false :gate-ok? false
              :in-testing? false :in-merging? false :review-notified? false
              :gate-notified? false :wrapup-sent? false
              :unread :external :fail-after-ready? false :last-was-tool? false
              :tool-streak 0 :turns-since-ext 0 :bounce-streak 0
              :trailing-errs 0 :non-retryable-err? false}]
    [(assoc base :kind :worker :parent-paused? false :auto-merge? true)
     (assoc base :kind :reviewer :parent-paused? true :auto-merge? true)
     (assoc base :kind :reviewer :parent-paused? true :auto-merge? false)]))

(defn state->policy-input
  "Projects an abstract model state `s` into the exact input map accepted by `seon.reconciler.policy/decide-agent-step`."
  {:malli/schema [:=> [:cat :map :map] :map]
   :cost/ticks 50000 :cost/bytes 65536}
  [s {:keys [max-tool-turns max-bounces max-total-turns]}]
  (let [ur (:unread s :none)
        ext? (= :external ur)
        fail-ur? (contains? #{:test-failure :merge-conflict} ur)
        errs (long (or (:trailing-errs s) 0))
        turns (long (or (:turns-since-ext s) 0))]
    {:branch :branch/model
     :paused? (boolean (:paused? s))
     :busy? (boolean (:busy? s))
     :awaiting-blocked? false
     :waiting? (boolean (:waiting? s))
     :raw-ready? (boolean (:raw-ready? s))
     :ready-tx (if (:raw-ready? s) 10 0)
     :ready-inst nil
     :tested-tx (when (:tested? s) 10)
     :merged-tx (when (:merged? s) 10)
     :head-tx 10
     :same-db? true
     :auto-merge? (boolean (:auto-merge? s))
     :gate-ok? (boolean (:gate-ok? s))
     :has-merge-target? (= :reviewer (:kind s))
     :in-testing? (boolean (:in-testing? s))
     :in-merging? (boolean (:in-merging? s))
     :review-notified? (boolean (:review-notified? s))
     :has-unread? (not= :none ur)
     :has-external-unread? ext?
     :has-unread-failure? fail-ur?
     :fail-after-ready? (boolean (:fail-after-ready? s))
     :newest-external-unread-tx (when ext? 5)
     :newest-external-unread-inst nil
     :latest-failure-tx nil
     :latest-failure-inst nil
     :post-ready-reply? false
     :has-pending-review-ops? false
     :eval-count (max turns errs)
     :last-was-tool? (boolean (:last-was-tool? s))
     :tool-streak (long (or (:tool-streak s) 0))
     :turns-since-external turns
     :bounce-streak (long (or (:bounce-streak s) 0))
     :trailing-model-errors errs
     :last-err-tx (when (pos? errs) 10)
     :last-err-inst nil
     :non-retryable-model-error? (boolean (:non-retryable-err? s))
     :max-tool-turns (long (or max-tool-turns 2))
     :max-bounces (long (or max-bounces 2))
     :max-total-turns (long (or max-total-turns 3))}))

(defn- turn-outcome-states [s {:keys [max-tool-turns max-total-turns]}]
  (let [ext? (= :external (:unread s))
        max-tt (long (or max-tool-turns 2))
        max-tot (long (or max-total-turns 3))
        turns' (min max-tot (if ext? 1 (inc (long (:turns-since-ext s 0)))))
        bounces' (if ext? 0 (long (:bounce-streak s 0)))
        w-sent' (if ext? false (boolean (:wrapup-sent? s)))
        tt' (min (inc max-tt) (if ext? 1 (inc (long (:tool-streak s 0)))))
        errs' (min 3 (inc (long (:trailing-errs s 0))))
        base-ok (assoc s :busy? false :unread :none :turns-since-ext turns'
                       :bounce-streak bounces' :wrapup-sent? w-sent'
                       :trailing-errs 0 :non-retryable-err? false)]
    [(assoc base-ok :waiting? false :raw-ready? false :fail-after-ready? false :tested? false :gate-notified? false :last-was-tool? true :tool-streak tt' :gate-ok? false)
     (assoc base-ok :waiting? false :raw-ready? false :fail-after-ready? false :tested? false :gate-notified? false :last-was-tool? true :tool-streak tt' :gate-ok? true)
     (assoc base-ok :waiting? false :raw-ready? true  :fail-after-ready? false :tested? false :gate-notified? false :last-was-tool? true :tool-streak tt' :gate-ok? false)
     (assoc base-ok :waiting? false :raw-ready? true  :fail-after-ready? false :tested? false :gate-notified? false :last-was-tool? true :tool-streak tt' :gate-ok? true)
     (assoc base-ok :waiting? true  :raw-ready? false :last-was-tool? false :tool-streak 0)
     (assoc s :busy? false :turns-since-ext turns' :trailing-errs errs' :non-retryable-err? false)
     (assoc s :busy? false :turns-since-ext turns' :trailing-errs errs' :non-retryable-err? true :waiting? true)]))

(defn- step-transitions [s d bounds]
  (let [max-b (long (or (:max-bounces bounds) 2))
        env-next (cond-> []
                   (:busy? s)
                   (into (turn-outcome-states s bounds))

                   (:in-testing? s)
                   (into [(assoc s :in-testing? false :tested? true)
                          (assoc s :in-testing? false :tested? false :unread :test-failure
                                 :fail-after-ready? true :bounce-streak (min max-b (inc (long (:bounce-streak s 0)))))])

                   (:in-merging? s)
                   (into [(assoc s :in-merging? false :merged? true
                                 :parent-paused? (if (= :reviewer (:kind s)) false (:parent-paused? s)))
                          (assoc s :in-merging? false :in-testing? false :raw-ready? false
                                 :tested? false :unread :merge-conflict :fail-after-ready? true
                                 :bounce-streak (min max-b (inc (long (:bounce-streak s 0)))))]))
        rec-next (cond-> []
                   (:review-abort? d)
                   (conj (assoc s :paused? true :parent-paused? false :review-notified? true
                                :in-testing? false :in-merging? false))

                   (:needs-turn? d)
                   (conj (assoc s :busy? true))

                   (and (:needs-wrapup? d) (not (:wrapup-sent? s)) (= :none (:unread s)))
                   (conj (assoc s :unread :wrapup :wrapup-sent? true
                                :bounce-streak (min max-b (inc (long (:bounce-streak s 0))))))

                   (and (= :gate-blocked (:status d))
                        (not (:gate-notified? s))
                        (not (:bounce-halted? d))
                        (not (:turn-budget-halted? d))
                        (not (:err-halted? d))
                        (not (:review-should-abort? d))
                        (= :none (:unread s)))
                   (conj (assoc s :unread :gate-blocked :gate-notified? true
                                :bounce-streak (min max-b (inc (long (:bounce-streak s 0))))))

                   (:needs-test? d)
                   (conj (assoc s :in-testing? true))

                   (:needs-merge? d)
                   (conj (assoc s :in-merging? true)))]
    (into #{} (concat env-next rec-next))))

(defn transition-graph
  "Explores the reachable finite-state transition graph via BFS over `decide-fn` (defaults to `seon.reconciler.policy/decide-agent-step`)."
  {:malli/schema [:function
                  [:=> [:cat] :map]
                  [:=> [:cat [:maybe :map]] :map]]
   :cost/ticks 40000000 :cost/bytes 33554432}
  ([] (transition-graph nil))
  ([opts]
   (let [bounds (merge default-bounds (select-keys (or opts {}) [:max-tool-turns :max-bounces :max-total-turns]))
         decide-fn (or (:decide-fn opts) policy/decide-agent-step)
         seeds (vec (or (:initial-states opts) (initial-states)))
         max-states (long (or (:max-states opts) 20000))]
     (loop [q (into clojure.lang.PersistentQueue/EMPTY seeds)
            seen (set seeds)
            edges {}
            decisions {}]
       (if (or (empty? q) (>= (count decisions) max-states))
         {:nodes (set (keys decisions)) :edges edges :decisions decisions :initial seeds :bounds bounds
          :truncated? (boolean (seq q))}
         (let [s (peek q)
               q' (pop q)
               d (decide-fn (state->policy-input s bounds))
               nxts (step-transitions s d bounds)
               fresh (remove seen nxts)]
           (recur (into q' fresh)
                  (into seen fresh)
                  (assoc edges s nxts)
                  (assoc decisions s d))))))))

(defn find-livelock-sccs
  "Iterative Tarjan Strongly Connected Components over directed graph `{node #{next ...}}`.
   Returns all non-trivial SCCs (cycles of length >= 2 or self-loops)."
  {:malli/schema [:=> [:cat :map] [:vector [:vector :any]]]
   :cost/ticks 20000000 :cost/bytes 16777216}
  [edges]
  (let [idx-counter (volatile! 0)
        indices (java.util.HashMap. (max 16 (count edges)))
        lowlink (java.util.HashMap. (max 16 (count edges)))
        on-stack (java.util.HashSet. (max 16 (count edges)))
        tarjan-stack (java.util.ArrayList.)
        livelocks (volatile! [])]
    (doseq [start (keys edges)]
      (when-not (.containsKey indices start)
        (let [call-stack (java.util.ArrayList.)]
          (.add call-stack [start (let [nx (get edges start)] (when (coll? nx) (seq nx))) true])
          (loop []
            (when-not (.isEmpty call-stack)
              (let [top-idx (dec (.size call-stack))
                    [v nbrs entering?] (.get call-stack top-idx)]
                (when entering?
                  (let [i @idx-counter]
                    (vswap! idx-counter inc)
                    (.put indices v i)
                    (.put lowlink v i)
                    (.add tarjan-stack v)
                    (.add on-stack v)
                    (.set call-stack top-idx [v nbrs false])))
                (if-let [w (first nbrs)]
                  (do
                    (.set call-stack top-idx [v (next nbrs) false])
                    (cond
                      (not (.containsKey indices w))
                      (.add call-stack [w (let [nx (get edges w)] (when (coll? nx) (seq nx))) true])

                      (.contains on-stack w)
                      (.put lowlink v (min (long (.get lowlink v)) (long (.get indices w))))))
                  (do
                    (.remove call-stack top-idx)
                    (when (= (.get lowlink v) (.get indices v))
                      (let [comp (loop [acc []]
                                   (let [x (.remove tarjan-stack (dec (.size tarjan-stack)))]
                                     (.remove on-stack x)
                                     (let [acc' (conj acc x)]
                                       (if (= x v) acc' (recur acc')))))]
                        (when (or (> (count comp) 1)
                                (let [nx (get edges v)]
                                  (and (coll? nx) (not (map? nx)) (boolean (some #(= % v) nx)))))
                        (vswap! livelocks conj comp))))
                    (when-not (.isEmpty call-stack)
                      (let [p-idx (dec (.size call-stack))
                            [pv _ _] (.get call-stack p-idx)]
                        (.put lowlink pv (min (long (.get lowlink pv)) (long (.get lowlink v))))))))
                (recur)))))))
    @livelocks))

(defn check-liveness
"Finite-state verifier over `seon.reconciler.policy/decide-agent-step`: proves 0 livelocks, 0 orphan-pauses, 0 wedged inflight, 0 crashed auto-merges, and 0 stranded ready states."
{:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:maybe :map]] :map]]
 :cost/ticks 60000000 :cost/bytes 33554432}
([] (check-liveness nil))
([opts]
 (let [t0 (System/nanoTime)
       {:keys [nodes edges decisions truncated?]} (transition-graph opts)
       livelocks (find-livelock-sccs edges)
       terminals (filterv #(empty? (get edges %)) nodes)
       orphan-pauses (filterv #(and (= :reviewer (:kind %)) (boolean (:parent-paused? %))) terminals)
       wedged-inflight (filterv #(or (:busy? %) (:in-testing? %) (:in-merging? %)) terminals)
       crashed-auto-merges (filterv #(let [d (get decisions %)]
                                       (and (:err-halted? d) (or (:cutoff-auto-merge? d) (:needs-merge? d))))
                                    nodes)
       stranded-ready (filterv #(let [d (get decisions %)]
                                  (and (:ready? d) (:auto-merge? %) (:gate-ok? %)
                                       (not (:merged? d)) (not (:paused? %))))
                               terminals)
       ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
       ok? (and (not truncated?) (empty? livelocks) (empty? orphan-pauses)
                (empty? wedged-inflight) (empty? crashed-auto-merges) (empty? stranded-ready))]
   {:ok? ok? :truncated? (boolean truncated?) :states-explored (count nodes)
    :edges-explored (reduce + 0 (map count (vals edges))) :terminal-states (count terminals)
    :ms ms :livelocks livelocks :orphan-pauses orphan-pauses :wedged-inflight wedged-inflight
    :crashed-auto-merges crashed-auto-merges :stranded-ready stranded-ready})))
