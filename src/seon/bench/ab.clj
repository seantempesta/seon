(ns seon.bench.ab
  "Pure paired A/B trial expansion and side-by-side summary extracted from `seon.bench.golden`."
  (:require [seon.render.text :refer [round1]]
            [clojure.string :as str]))

(defn median
  {:malli/schema [:=> {:examples [[[[1 3 2]] 2.0] [[[]] nil]]} [:cat [:maybe [:sequential :any]]] [:maybe :double]], :cost/ticks 500000}
  [xs]
  (let [values (vec (sort (keep #(when (number? %) (double %)) xs)))
        total (count values)]
    (when (pos? total)
      (let [mid (quot total 2)]
        (round1 (if (odd? total)
                  (nth values mid)
                  (/ (+ (nth values (dec mid)) (nth values mid)) 2.0)))))))

(defn trial-merged?
  {:malli/schema [:=> {:examples [[[{:merged? true}] true] [[{:status :merged}] true] [[{:status :failed}] false]]} [:cat [:maybe :map]] :boolean], :cost/ticks 50000}
  [trial]
  (boolean (or (:merged? trial) (= :merged (:status trial)))))

(defn build-ab-tasks
  "Expands `base-tasks` across named `arms` (`{:control {...} :treatment {...}}` or seq of `[arm-kw cfg]`)
   into paired isolated trial specs ready for `seon.bench.cluster/run-parallel-trial!`, preserving `:verify-spec`,
   `:chat-agent`, `:branch`, `:body`, and `:min-delegated-calls`."
  {:malli/schema [:=> {:examples [[[[{:id :t/one :scope 'seon.render.text :title "T1" :plan "Do X."}]
                                     [[:control {:seon.config/auto-refine-tasks? false}]
                                      [:treatment {:seon.config/auto-refine-tasks? true}]]
                                     {:timeout-ms 120000}]
                                    [{:id :ab/one-control :pair-id :t/one :arm :control
                                      :run-tests? true :merge? true :require-merge? true
                                      :auto-merge? true :timeout-ms 120000
                                      :config {:seon.config/auto-refine-tasks? false}
                                      :task {:id :t/one :scope 'seon.render.text :title "T1" :plan "Do X."}}
                                     {:id :ab/one-treatment :pair-id :t/one :arm :treatment
                                      :run-tests? true :merge? true :require-merge? true
                                      :auto-merge? true :timeout-ms 120000
                                      :config {:seon.config/auto-refine-tasks? true}
                                      :task {:id :t/one :scope 'seon.render.text :title "T1" :plan "Do X."}}]]]}
                  [:cat [:sequential :map] :any [:? [:maybe :map]]] [:vector :map]]
   :cost/ticks 500000}
  ([base-tasks arms] (build-ab-tasks base-tasks arms nil))
  ([base-tasks arms opts]
   (let [o (if (map? opts) opts {})
         arm-pairs (cond
                     (map? arms) (vec (sort-by (comp str key) (filter #(keyword? (key %)) arms)))
                     (sequential? arms) (vec (keep #(cond
                               (keyword? %) [% {}]
                               (and (sequential? %) (= 2 (count %)) (keyword? (first %)))
                               [(first %) (if (map? (second %)) (second %) {})]
                               :else nil)
                     arms))
                     :else [])
         explicit-timeout (or (when (number? (:timeout-ms o)) (long (:timeout-ms o)))
                              (when (number? (:task-timeout-ms o)) (long (:task-timeout-ms o))))
         inc-trace? (boolean (:include-trace? o))]
     (vec (for [[idx raw] (map-indexed vector (filter map? base-tasks))
                :let [chat-aid (:chat-agent raw)
                      inner (if (map? (:task raw)) (:task raw) raw)
                      raw-pid (or (:pair-id raw) (:id inner) (:id raw))
                      pair-id (if (keyword? raw-pid) raw-pid (keyword "ab" (str "t" (inc idx))))
                      base-cfg (if (map? (:config raw)) (:config raw) {})]
                [arm-kw arm-cfg] arm-pairs]
            (cond-> {:id (keyword "ab" (str (name pair-id) "-" (name arm-kw)))
                     :pair-id pair-id
                     :arm arm-kw
                     :run-tests? (if (contains? raw :run-tests?) (boolean (:run-tests? raw)) true)
                     :merge? (if (contains? raw :merge?) (boolean (:merge? raw)) true)
                     :require-merge? (if (contains? raw :require-merge?) (boolean (:require-merge? raw)) true)
                     :auto-merge? (if (contains? raw :auto-merge?) (boolean (:auto-merge? raw)) true)
                     :timeout-ms (long (or explicit-timeout (when (number? (:timeout-ms raw)) (:timeout-ms raw)) 240000))
                     :config (merge base-cfg (if (map? arm-cfg) arm-cfg {}))}
              (not chat-aid)              (assoc :task (select-keys inner [:id :scope :title :plan :description :refs :curated :tests :needs]))
              chat-aid                    (assoc :chat-agent chat-aid)
              (:branch raw)               (assoc :branch (:branch raw))
              (:body raw)                 (assoc :body (:body raw))
              (:min-delegated-calls raw)  (assoc :min-delegated-calls (:min-delegated-calls raw))
              (:verify-spec raw)          (assoc :verify-spec (:verify-spec raw))
              (:eval-code raw)            (assoc :eval-code (:eval-code raw))
              (:retest-main? raw)         (assoc :retest-main? (:retest-main? raw))
              (:category raw)             (assoc :category (:category raw))
              inc-trace?                  (assoc :include-trace? true)))))))

(defn summarize-ab
  "Pure side-by-side paired A/B summary over trial result maps tagged with `:arm` and `:pair-id`."
  {:malli/schema [:=> {:examples [[[[{:id :ab/x-control :pair-id :t/x :arm :control :merged? true :turns 10 :total-turns 10 :total-prompt-tokens 50000 :total-kv-hit-pct 85.0 :elapsed-ms 40000}
                                     {:id :ab/x-treatment :pair-id :t/x :arm :treatment :merged? true :turns 6 :child-turns 5 :child-merged? true :total-turns 11 :total-prompt-tokens 60000 :total-kv-hit-pct 88.0 :backward-walk-rows 3 :elapsed-ms 45000}]]
                                    {:arms [:control :treatment]
                                     :by-arm {:control {:total 1 :merged 1 :merge-rate-pct 100.0 :median-worker-turns 10.0 :median-total-turns 10.0 :total-prompt-tokens 50000 :mean-kv-hit-pct 85.0 :child-merged 0 :new-scope-vars 0 :new-test-vars 0}
                                              :treatment {:total 1 :merged 1 :merge-rate-pct 100.0 :median-worker-turns 6.0 :median-total-turns 11.0 :total-prompt-tokens 60000 :mean-kv-hit-pct 88.0 :child-merged 1 :new-scope-vars 0 :new-test-vars 0}}
                                     :pairs [{:pair-id :t/x
                                              :arms {:control {:status nil :merged? true :worker-turns 10 :child-turns 0 :total-turns 10 :prompt-tokens 50000 :kv-hit-pct 85.0 :backward-walk-rows 0 :new-scope-vars 0 :new-test-vars 0 :wall-s 40.0 :last-error nil}
                                                     :treatment {:status nil :merged? true :worker-turns 6 :child-turns 5 :total-turns 11 :prompt-tokens 60000 :kv-hit-pct 88.0 :backward-walk-rows 3 :new-scope-vars 0 :new-test-vars 0 :wall-s 45.0 :last-error nil}}}]
                                     :summary "control: 1/1 merged (100.0%, med W=10.0t Tot=10.0t, KV 85.0%) | treatment: 1/1 merged (100.0%, med W=6.0t Tot=11.0t, KV 88.0%)"}]]}
                  [:cat [:sequential :map]] :map]
   :cost/ticks 500000}
  [results]
  (let [rows (vec (filter map? results))
        arm-order (vec (distinct (keep :arm rows)))
        as-l #(long (or % 0))
        row-view (fn [r]
                   (let [wt (as-l (or (:turns r) (:evals r)))
                         ct (as-l (:child-turns r))
                         tt (as-l (or (:total-turns r) (+ wt ct)))
                         pt (as-l (or (:total-prompt-tokens r) (:prompt-tokens r)))
                         kv (or (:total-kv-hit-pct r)
                                (let [h (as-l (:kv-hit-tokens r)) m (as-l (:kv-miss-tokens r))]
                                  (when (pos? (+ h m)) (round1 (/ (* 100.0 h) (+ h m))))))]
                     {:status (:status r) :merged? (trial-merged? r)
                      :worker-turns wt :child-turns ct :total-turns tt
                      :prompt-tokens pt :kv-hit-pct kv
                      :backward-walk-rows (as-l (:backward-walk-rows r))
                      :new-scope-vars (as-l (:new-scope-vars r))
                      :new-test-vars (as-l (:new-test-vars r))
                      :wall-s (when (number? (:elapsed-ms r)) (round1 (/ (double (:elapsed-ms r)) 1000.0)))
                      :last-error (:last-error r)}))
        by-arm (into {}
                     (for [arm arm-order
                           :let [rs (filterv #(= arm (:arm %)) rows)
                                 n (count rs)
                                 m (count (filter trial-merged? rs))
                                 vs (mapv row-view rs)
                                 kvs (keep :kv-hit-pct vs)
                                 mean-kv (when (seq kvs) (round1 (/ (reduce + 0.0 kvs) (count kvs))))]]
                       [arm {:total n :merged m
                             :merge-rate-pct (if (pos? n) (round1 (* 100.0 (/ (double m) n))) 0.0)
                             :median-worker-turns (median (map :worker-turns vs))
                             :median-total-turns (median (map :total-turns vs))
                             :total-prompt-tokens (reduce + 0 (map :prompt-tokens vs))
                             :mean-kv-hit-pct mean-kv
                             :child-merged (count (filter :child-merged? rs))
                             :new-scope-vars (reduce + 0 (map :new-scope-vars vs))
                             :new-test-vars (reduce + 0 (map :new-test-vars vs))}]))
        pair-order (vec (distinct (keep :pair-id rows)))
        pairs (mapv (fn [pid]
                      {:pair-id pid
                       :arms (into {} (for [r rows :when (and (= pid (:pair-id r)) (:arm r))]
                                        [(:arm r) (row-view r)]))})
                    pair-order)
        summary (clojure.string/join
                 " | "
                 (for [arm arm-order
                       :let [a (get by-arm arm)]]
                   (str (name arm) ": " (:merged a) "/" (:total a) " merged (" (:merge-rate-pct a) "%"
                        (when (:median-worker-turns a) (str ", med W=" (:median-worker-turns a) "t Tot=" (:median-total-turns a) "t"))
                        (when (:mean-kv-hit-pct a) (str ", KV " (:mean-kv-hit-pct a) "%"))
                        ")")))]
    {:arms arm-order :by-arm by-arm :pairs pairs :summary summary}))