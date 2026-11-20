(ns seon.health.metrics
  "Extracted child namespace of `seon.health.efficiency`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store]
            [seon.code.parse :as parse]))

(def learning-metrics-cache (store/lru-cache 8))

(defn classify-eval-error
  "Classifies a REPL eval error string into a stable failure class for trend & hill-climbing tracking."
  {:malli/schema [:=> {:examples [[["Eval exceeded allocation budget (limit 268435456 bytes)."] :alloc-budget-overrun]
                                   [["Wrong number of args (2) passed to: user/callers"] :wrong-arity]]}
                  [:cat :string] :keyword]
   :cost/ticks 200000
   :cost/bytes 524288}
  [err-str]
  (let [e (or err-str "")]
    (cond
      (str/includes? e "allocation budget")         :alloc-budget-overrun
      (str/includes? e "tick budget")               :tick-budget-overrun
      (str/includes? e "Schema contract violation") :schema-contract-violation
      (str/includes? e "Wrong number of args")      :wrong-arity
      (or (str/includes? e "No such var")
          (str/includes? e "Unable to resolve"))    :unresolved-var
      (or (str/includes? e "Reader syntax error")
          (str/includes? e "Unmatched delimiter")
          (str/includes? e "EOF while reading"))    :reader-syntax
      (or (str/includes? e "cannot be cast")
          (str/includes? e "find not supported"))   :type-mismatch
      (str/includes? e "is not public")             :private-var
      :else                                         :other)))

(defn pct-val
  {:malli/schema [:=> [:cat [:sequential [:or :int :double]] [:double {:min 0.0, :max 1.0}]] :double], :cost/ticks 200000, :cost/bytes 524288}
  [xs p]
  (let [s (vec (sort xs)) n (count s)]
    (if (zero? n) 0.0 (double (nth s (min (dec n) (long (* p n))))))))

(defn extract-tool-names
  {:malli/schema [:=> [:cat :any] [:vector :string]], :cost/ticks 200000, :cost/bytes 524288}
  [code]
  (let [s (str code)
        ms (distinct (map second (re-seq #"user/([a-zA-Z0-9\-\!\?\*]+)" s)))]
    (cond
      (seq ms) (vec ms)
      (str/includes? s "(d/q ") ["d/q"]
      (str/includes? s "(d/datoms ") ["d/datoms"]
      (str/includes? s "(defn ") ["raw-defn"]
      (str/includes? s "(deftest ") ["raw-deftest"]
      :else ["other-clj"])))

(def discovery-tools #{"source" "grep-src" "ns-outline" "ns-requires" "search" "callers" "callees" "tests-for" "db" "main-db" "d/q" "d/datoms" "past-evals" "history" "health" "help"})

(def action-tools #{"edit-fn!" "add-fn!" "move-vars!" "remove-var!" "set-meta!" "add-require!" "prune-requires!" "register-schema!" "run-tests!" "ready!" "autofix-health!" "canvas!" "answer!" "raw-defn" "raw-deftest"})

(defn agent-learning-metrics
  "Derived anti-gaming sliding-window task completion & tool-frustration scorecard over the latest
   `window` (default 10) autonomous agents in Datahike. Cached per `(store-id, max-tx, window)`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/conn :seon.host/db] :map]
                  [:=> [:cat :seon.host/conn :seon.host/db :map] :map]]}
  ([conn db] (agent-learning-metrics conn db {}))
  ([conn db {:keys [window] :or {window 10}}]
   (let [w (long (max 1 (min 30 (or window 10))))
         ck [(get-in db [:config :store :id]) (:max-tx db) w]]
     (store/lru-get!
      learning-metrics-cache 8 ck
      (fn []
        (let [all-brs (set (d/branches conn))
              ags (->> (d/datoms db :aevt :seon.agent/id)
                       (map #(d/pull db '[:db/id :seon.agent/id :seon.agent/role :seon.agent/branch
                                          :seon.agent/ready? :seon.agent/merged-tx] (:e %)))
                       (remove #(or (= :repl (:seon.agent/role %))
                                    (= "repl" (namespace (:seon.agent/id %)))
                                    (= "example" (namespace (:seon.agent/id %)))
                                    (= :agent/root (:seon.agent/id %))))
                       (sort-by (comp - :db/id)))
              chosen (volatile! [])
              _ (doseq [a ags
                        :while (< (count @chosen) w)
                        :let [aid (:seon.agent/id a)
                              br (:seon.agent/branch a)]
                        :when (contains? all-brs br)
                        :let [bdb (try (store/head conn br) (catch Throwable _ nil))]
                        :when (and bdb (contains? (:schema bdb) :seon.agent.eval/agent))
                        :let [eids (mapv :e (d/datoms bdb :avet :seon.agent.eval/agent aid))]
                        :when (pos? (count eids))]
                  (let [es (->> eids
                                (map #(d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/code
                                                    :seon.agent.eval/error :seon.agent.eval/bytes
                                                    :seon.agent.eval/prefix-hash
                                                    :seon.agent.eval/cache-hit-tokens
                                                    :seon.agent.eval/cache-miss-tokens] %))
                                (sort-by #(long (or (:seon.agent.eval/turn %) 0)))
                                vec)
                        turns (count (distinct (keep :seon.agent.eval/turn es)))
                        errs (keep :seon.agent.eval/error es)
                        hits (reduce + 0 (map #(long (or (:seon.agent.eval/cache-hit-tokens %) 0)) es))
                        miss (reduce + 0 (map #(long (or (:seon.agent.eval/cache-miss-tokens %) 0)) es))
                        phashes (distinct (keep :seon.agent.eval/prefix-hash es))
                        busts (max 0 (dec (count phashes)))]
                    (vswap! chosen conj
                            {:id aid :evals (count es) :turns turns :errs (count errs)
                             :merged? (boolean (:seon.agent/merged-tx a))
                             :ready? (boolean (:seon.agent/ready? a))
                             :hits hits :miss miss :prefix-busts busts
                             :eval-rows es})))
              rows (vec (reverse @chosen))
              attempted (count rows)
              merged-rows (filter :merged? rows)
              merged (count merged-rows)
              tot-turns (reduce + 0 (map :turns rows))
              tot-evals (reduce + 0 (map :evals rows))
              tot-errs (reduce + 0 (map :errs rows))
              tot-hits (reduce + 0 (map :hits rows))
              tot-miss (reduce + 0 (map :miss rows))
              tot-tok (+ tot-hits tot-miss)
              tot-busts (reduce + 0 (map :prefix-busts rows))
              all-err-strs (mapcat #(keep :seon.agent.eval/error (:eval-rows %)) rows)
              err-classes (into (sorted-map) (frequencies (map classify-eval-error all-err-strs)))
              oom-count (get err-classes :alloc-budget-overrun 0)
              acc-tools
              (reduce
               (fn [acc r]
                 (let [es (:eval-rows r)]
                   (reduce
                    (fn [a [idx e]]
                      (let [ts (extract-tool-names (:seon.agent.eval/code e))
                            prev-ts (when (pos? idx) (extract-tool-names (:seon.agent.eval/code (nth es (dec idx)))))
                            err (:seon.agent.eval/error e)
                            oom? (= :alloc-budget-overrun (when err (classify-eval-error err)))
                            mb (/ (double (long (or (:seon.agent.eval/bytes e) 0))) 1048576.0)
                            disc? (boolean (some discovery-tools ts))
                            act? (boolean (some action-tools ts))
                            a1 (cond-> a
                                 (and disc? (not act?)) (update :disc inc)
                                 act? (update :act inc))]
                        (reduce
                         (fn [m t]
                           (let [ret? (boolean (some #{t} prev-ts))
                                 cur (get-in m [:tools t] {:tool t :calls 0 :errs 0 :ooms 0 :retries 0 :mbs []})]
                             (assoc-in m [:tools t]
                                       (-> cur
                                           (update :calls inc)
                                           (update :errs + (if err 1 0))
                                           (update :ooms + (if oom? 1 0))
                                           (update :retries + (if ret? 1 0))
                                           (update :mbs conj mb)))))
                         a1 ts)))
                    acc (map-indexed vector es))))
               {:disc 0 :act 0 :tools {}} rows)
              top-tools (->> (vals (:tools acc-tools))
                             (filter #(>= (:calls %) 2))
                             (map (fn [{:keys [tool calls errs ooms retries mbs]}]
                                    (let [ep (Math/round (* 100.0 (/ (double errs) calls)))
                                          op (Math/round (* 100.0 (/ (double ooms) calls)))
                                          rp (Math/round (* 100.0 (/ (double retries) calls)))
                                          fr (Math/round (+ (* 3.0 ep) (* 2.0 op) (* 0.5 rp)))]
                                      {:tool tool :calls calls :err% ep :oom% op :ret% rp
                                       :p95-mb (Math/round (pct-val mbs 0.95))
                                       :frustration fr})))
                             (sort-by (comp - :frustration))
                             (take 6)
                             vec)]
          {:window w
           :attempted attempted
           :merged merged
           :merge-rate-pct (if (pos? attempted) (Math/round (* 100.0 (/ (double merged) attempted))) 0)
           :total-turns tot-turns
           :effective-turns-per-merged (when (pos? merged) (/ (Math/round (* 10.0 (/ (double tot-turns) merged))) 10.0))
           :median-turns-merged (when (pos? merged) (Math/round (pct-val (map :turns merged-rows) 0.50)))
           :total-evals tot-evals
           :avg-evals-per-turn (if (pos? tot-turns) (/ (Math/round (* 100.0 (/ (double tot-evals) tot-turns))) 100.0) 0.0)
           :eval-error-rate-pct (if (pos? tot-evals) (Math/round (* 100.0 (/ (double tot-errs) tot-evals))) 0)
           :oom-rate-pct (if (pos? tot-evals) (Math/round (* 100.0 (/ (double oom-count) tot-evals))) 0)
           :discovery-to-action-ratio (when (pos? (:act acc-tools))
                                        (/ (Math/round (* 100.0 (/ (double (:disc acc-tools)) (:act acc-tools)))) 100.0))
           :kv-cache-hit-pct (when (pos? tot-tok) (Math/round (* 100.0 (/ (double tot-hits) tot-tok))))
           :prefix-busts tot-busts
           :error-classes err-classes
           :top-frustrating-tools top-tools
           :recent-agents (mapv #(dissoc % :eval-rows :hits :miss) rows)}))))))
