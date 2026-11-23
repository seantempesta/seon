(ns seon.health.metrics
  "Extracted child namespace of `seon.health.efficiency`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store]))

(def learning-metrics-cache (store/lru-cache 8))

(defn classify-eval-error
  "Classifies a REPL eval error string into a stable failure class for trend & hill-climbing tracking."
  {:malli/schema [:=> {:examples [[["Eval exceeded allocation budget (limit 268435456 bytes)."] :alloc-budget-overrun]
                                   [["Wrong number of args (2) passed to: user/callers"] :wrong-arity]]}
                  [:cat [:maybe :string]] :keyword]
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

(def action-tools #{"edit-fn!" "add-fn!" "move-vars!" "remove-var!" "set-meta!" "add-require!" "prune-requires!" "register-schema!" "run-tests!" "ready!" "autofix-health!" "answer!" "raw-defn" "raw-deftest"})

(defn eval-summary-by-agent
  "Summarizes one agent's evals in a single db (branch head or main), using the indexed
   `:seon.agent.eval/agent` attribute (no full-table scan). Reports eval/turn counts, error
   rate, prompt/completion token totals, KV cache hit rate, and max allocation bytes."
  {:malli/schema [:=> {:examples [[nil :ag/x {:agent :ag/x :evals 0 :turns 0 :errors 0 :error-rate-pct 0
                                               :prompt-tokens 0 :completion-tokens 0 :total-tokens 0
                                               :cache-hit-tokens 0 :cache-miss-tokens 0 :cache-hit-rate-pct 0
                                               :max-bytes 0}]]}
                  [:cat :seon.host/db :seon.agent/id] :map]
   :cost/ticks 2000000
   :cost/bytes 1048576}
  [db agent-id]
  (let [eids (mapv :e (d/datoms db :avet :seon.agent.eval/agent agent-id))
        es (mapv #(d/pull db '[:seon.agent.eval/turn :seon.agent.eval/error
                               :seon.agent.eval/bytes
                               :seon.agent.eval/prompt-tokens :seon.agent.eval/completion-tokens
                               :seon.agent.eval/cache-hit-tokens :seon.agent.eval/cache-miss-tokens] %)
                 eids)
        n (count es)
        errs (count (keep :seon.agent.eval/error es))
        hits (reduce + 0 (map #(long (or (:seon.agent.eval/cache-hit-tokens %) 0)) es))
        miss (reduce + 0 (map #(long (or (:seon.agent.eval/cache-miss-tokens %) 0)) es))
        prompt (reduce + 0 (map #(long (or (:seon.agent.eval/prompt-tokens %) 0)) es))
        completion (reduce + 0 (map #(long (or (:seon.agent.eval/completion-tokens %) 0)) es))
        max-bytes (reduce max 0 (map #(long (or (:seon.agent.eval/bytes %) 0)) es))
        toks (+ hits miss)]
    {:agent agent-id
     :evals n
     :turns (count (distinct (keep :seon.agent.eval/turn es)))
     :errors errs
     :error-rate-pct (if (pos? n) (Math/round (* 100.0 (/ (double errs) n))) 0)
     :prompt-tokens prompt
     :completion-tokens completion
     :total-tokens (+ prompt completion)
     :cache-hit-tokens hits
     :cache-miss-tokens miss
     :cache-hit-rate-pct (if (pos? toks) (Math/round (* 100.0 (/ (double hits) toks))) 0)
     :max-bytes max-bytes}))

(defn- skip-agent?
  "True when an agent entity is excluded from learning metrics (repl/example/root)."
  {:malli/schema [:=> [:cat :map] :boolean]
   :cost/ticks 40}
  [a]
  (let [aid (:seon.agent/id a)]
    (boolean
     (or (= :repl (:seon.agent/role a))
         (and aid (= "repl" (namespace aid)))
         (and aid (= "example" (namespace aid)))
         (= :agent/root aid)))))

(defn- summarize-agent-row
  "Build the per-agent metric row from an agent entity and its sorted eval rows."
  {:malli/schema [:=> [:cat :map [:sequential :map]] :map], :cost/ticks 2500}
  [a es]
  (let [es (vec es)
        hits (reduce + 0 (map #(long (or (:seon.agent.eval/cache-hit-tokens %) 0)) es))
        miss (reduce + 0 (map #(long (or (:seon.agent.eval/cache-miss-tokens %) 0)) es))
        busts (max 0 (dec (count (distinct (keep :seon.agent.eval/prefix-hash es)))))]
    {:id (:seon.agent/id a)
     :evals (count es)
     :turns (count (distinct (keep :seon.agent.eval/turn es)))
     :errs (count (keep :seon.agent.eval/error es))
     :merged? (boolean (:seon.agent/merged-tx a))
     :ready? (boolean (:seon.agent/ready? a))
     :hits hits :miss miss :prefix-busts busts
     :eval-rows es}))

(defn- accumulate-tool-call
  "Fold a single tool invocation into the frustration accumulator map."
  {:malli/schema [:=> [:cat :map :string :boolean :boolean :boolean :double] :map], :cost/ticks 600}
  [m t err? oom? ret? mb]
  (let [cur (get-in m [:tools t] {:tool t :calls 0 :errs 0 :ooms 0 :retries 0 :mbs []})]
    (assoc-in m [:tools t]
              (-> cur
                  (update :calls inc)
                  (update :errs + (if err? 1 0))
                  (update :ooms + (if oom? 1 0))
                  (update :retries + (if ret? 1 0))
                  (update :mbs conj mb)))))

(defn- accumulate-eval-frustration
  "Fold one eval row (idx/e) into the discovery-vs-action + tool frustration accumulator."
  {:malli/schema [:=> [:cat :map :int :map [:sequential :map]] :map], :cost/ticks 2500}
  [a idx e es]
  (let [es (vec es)
        ts (extract-tool-names (:seon.agent.eval/code e))
        prev-e (when (and (pos? idx) (< idx (count es))) (nth es (dec idx)))
        prev-ts (when prev-e (extract-tool-names (:seon.agent.eval/code prev-e)))
        err (:seon.agent.eval/error e)
        oom? (= :alloc-budget-overrun (when err (classify-eval-error err)))
        mb (/ (double (long (or (:seon.agent.eval/bytes e) 0))) 1048576.0)
        disc? (boolean (some discovery-tools ts))
        act? (boolean (some action-tools ts))
        a1 (cond-> a
             (and disc? (not act?)) (update :disc (fnil inc 0))
             act? (update :act (fnil inc 0)))]
    (reduce
     (fn [m t]
       (let [ret? (boolean (some #{t} prev-ts))]
         (accumulate-tool-call m t (some? err) oom? ret? mb)))
     a1 ts)))

(defn- accumulate-tool-frustration
  "Fold all agent rows into the {disc act tools} frustration accumulator."
  {:malli/schema [:=> [:cat [:sequential :map]] :map], :cost/ticks 2500}
  [rows]
  (reduce
   (fn [acc r]
     (let [es (:eval-rows r)]
       (reduce
        (fn [a [idx e]] (accumulate-eval-frustration a idx e es))
        acc (map-indexed vector es))))
   {:disc 0 :act 0 :tools {}} rows))

(defn- tool-frustration-row
  "Convert a raw tool accumulator into a scored frustration row."
  {:malli/schema [:=> [:cat :map] :map], :cost/ticks 800}
  [{:keys [tool calls errs ooms retries mbs]}]
  (let [calls (long (or calls 1))
        errs (long (or errs 0))
        ooms (long (or ooms 0))
        retries (long (or retries 0))
        ep (Math/round (* 100.0 (/ (double errs) calls)))
        op (Math/round (* 100.0 (/ (double ooms) calls)))
        rp (Math/round (* 100.0 (/ (double retries) calls)))
        fr (Math/round (+ (* 3.0 ep) (* 2.0 op) (* 0.5 rp)))]
    {:tool tool :calls calls :err% ep :oom% op :ret% rp
     :p95-mb (Math/round (pct-val (vec (or mbs [0.0])) 0.95))
     :frustration fr}))

(defn- top-frustrating-tools
  "Rank tools called >=2 times by a blended error/oom/retry frustration score."
  {:malli/schema [:=> [:cat :map] [:vector :map]]
   :cost/ticks 400}
  [tools]
  (->> (vals tools)
       (filter #(>= (long (or (:calls %) 0)) 2))
       (map tool-frustration-row)
       (sort-by (comp - :frustration))
       (take 6)
       vec))

(defn- learning-metrics-scorecard
  "Assemble the public learning-metrics scorecard map from per-agent rows + frustration acc."
  {:malli/schema [:=> [:cat :int [:sequential :map] :map] :map], :cost/ticks 3000}
  [w rows acc-tools]
  (let [rows (vec rows)
        attempted (count rows)
        merged-rows (filter :merged? rows)
        merged (count merged-rows)
        tot (fn [k] (reduce + 0 (map #(long (or (k %) 0)) rows)))
        tot-turns (tot :turns)
        tot-evals (tot :evals)
        tot-errs (tot :errs)
        tot-hits (tot :hits)
        tot-miss (tot :miss)
        tot-tok (+ tot-hits tot-miss)
        tot-busts (tot :prefix-busts)
        all-err-strs (mapcat #(keep :seon.agent.eval/error (:eval-rows %)) rows)
        err-classes (into (sorted-map) (frequencies (map classify-eval-error all-err-strs)))
        oom-count (get err-classes :alloc-budget-overrun 0)
        disc (long (or (:disc acc-tools) 0))
        act (long (or (:act acc-tools) 0))]
    {:window w
     :attempted attempted
     :merged merged
     :merge-rate-pct (if (pos? attempted) (Math/round (* 100.0 (/ (double merged) attempted))) 0)
     :total-turns tot-turns
     :effective-turns-per-merged (when (pos? merged) (/ (Math/round (* 10.0 (/ (double tot-turns) merged))) 10.0))
     :median-turns-merged (when (pos? merged) (Math/round (pct-val (vec (map #(long (or (:turns %) 0)) merged-rows)) 0.50)))
     :total-evals tot-evals
     :avg-evals-per-turn (if (pos? tot-turns) (/ (Math/round (* 100.0 (/ (double tot-evals) tot-turns))) 100.0) 0.0)
     :eval-error-rate-pct (if (pos? tot-evals) (Math/round (* 100.0 (/ (double tot-errs) tot-evals))) 0)
     :oom-rate-pct (if (pos? tot-evals) (Math/round (* 100.0 (/ (double oom-count) tot-evals))) 0)
     :discovery-to-action-ratio (when (pos? act) (/ (Math/round (* 100.0 (/ (double disc) act))) 100.0))
     :kv-cache-hit-pct (when (pos? tot-tok) (Math/round (* 100.0 (/ (double tot-hits) tot-tok))))
     :prefix-busts tot-busts
     :error-classes err-classes
     :top-frustrating-tools (top-frustrating-tools (:tools acc-tools))
     :recent-agents (mapv #(dissoc % :eval-rows :hits :miss) rows)}))

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
                       (remove skip-agent?)
                       (sort-by (comp - :db/id)))
              chosen (volatile! [])]
          (doseq [a ags
                  :while (< (count @chosen) w)
                  :let [aid (:seon.agent/id a)
                        br (:seon.agent/branch a)]
                  :when (contains? all-brs br)
                  :let [bdb (try (store/head conn br) (catch Throwable _ nil))]
                  :when (and bdb (contains? (:schema bdb) :seon.agent.eval/agent))
                  :let [eids (mapv :e (d/datoms bdb :avet :seon.agent.eval/agent aid))]
                  :when (pos? (count eids))]
            (vswap! chosen conj
                    (summarize-agent-row
                     a
                     (->> eids
                          (map #(d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/code
                                              :seon.agent.eval/error :seon.agent.eval/bytes
                                              :seon.agent.eval/prefix-hash
                                              :seon.agent.eval/cache-hit-tokens
                                              :seon.agent.eval/cache-miss-tokens] %))
                          (sort-by #(long (or (:seon.agent.eval/turn %) 0)))
                          vec))))
          (let [rows (vec (reverse @chosen))]
            (learning-metrics-scorecard w rows (accumulate-tool-frustration rows)))))))))
