(ns seon.signals.scoreboard
  "Empirical Context Precision/Recall Scoreboard & reproducible `d/as-of` Hyperparameter Lab."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.fit :as fit]
            [seon.signals.touches :as touches]
            [seon.store :as store]))

(def default-hyperparam-profiles
  "Default profiles compared by `compare-as-of-hyperparams`."
  [{:label :default       :weights {:urgency 0.50 :recency 0.30 :touches 0.20} :strategy :breadth-first :token-budget 2400}
   {:label :recency-heavy :weights {:urgency 0.20 :recency 0.65 :touches 0.15} :strategy :breadth-first :token-budget 2400}
   {:label :urgency-heavy :weights {:urgency 0.75 :recency 0.15 :touches 0.10} :strategy :breadth-first :token-budget 2400}
   {:label :depth-first   :weights {:urgency 0.50 :recency 0.30 :touches 0.20} :strategy :depth-first   :token-budget 2400}])

(defn extract-eval-touches
  "Extracts touched EIDs, symbols, schemas, and refs per eval turn, separating usage + reasoning mentions from manual lookups."
  {:malli/schema [:=> [:cat :any [:? :any] [:? :any]] :map]
   :cost/ticks 25000000}
  ([evals] (touches/extract-eval-touches evals))
  ([db evals-or-agent] (touches/extract-eval-touches db evals-or-agent))
  ([db evals-or-agent opts] (touches/extract-eval-touches db evals-or-agent opts)))

(defn- round-n [x scale]
  (if (number? x)
    (let [v (double x)]
      (if (and (== v v) (not (Double/isInfinite v)))
        (/ (double (Math/round (* v (double scale)))) (double scale))
        0.0))
    0.0))

(defn- clamp01 [x] (max 0.0 (min 1.0 (round-n x 10000.0))))
(defn- to-set [x] (cond (set? x) x (coll? x) (set x) (some? x) #{x} :else #{}))

(defn- safe-as-of
  {:seon.flow/edge true}
  [db tx]
  (if (and db (integer? tx) (not (instance? datahike.db.AsOfDB db)))
    (try (d/as-of db (long tx)) (catch Throwable _ db))
    db))

(defn- norm-weights [w]
  (when (map? w)
    (let [conv (or (:convergence w) (:touches w))]
      (cond-> (select-keys w [:urgency :recency :convergence :semantic])
        (number? conv) (assoc :convergence (double conv))))))

(defn- item-identities [it]
  (let [d (if (map? (:data it)) (:data it) {})
        eid (first (filter integer? [(:entity it) (:eid it) (:db/id d)]))
        ref (when (and (vector? (:ref it)) (= 2 (count (:ref it)))) (:ref it))
        syms (into #{} (filter symbol?)
                   [(:sym it) (:seon.code.var/name d) (:seon.code.ns/name d)
                    (when (and ref (#{:seon.code.var/name :seon.code.ns/name} (first ref))) (second ref))])
        refs (into (if ref #{ref} #{})
                   (for [a touches/ref-attrs, :let [v (get d a)] :when (some? v)] [a v]))
        sch (or (:seon.schema/key d)
                (when (and (empty? syms) (empty? refs) (qualified-keyword? (:schema it))) (:schema it)))]
    {:key (or eid (:entity it) (first syms) (first refs) (:id it) (:schema it))
     :eids (if eid #{eid} #{}) :syms syms :refs refs
     :ns-syms (into #{} (filter #(and (nil? (namespace %)) (str/includes? (name %) "."))) syms)
     :schema-target (when (qualified-keyword? sch) sch)}))

(defn- matches-targets? [id {:keys [eids syms ns-from-syms schemas refs]}]
  (boolean (or (some eids (:eids id)) (some syms (:syms id)) (some ns-from-syms (:ns-syms id))
               (some refs (:refs id)) (and (:schema-target id) (contains? schemas (:schema-target id))))))

(defn- target-bundle [lt k-e k-s k-sc k-r]
  (let [syms (to-set (get lt k-s))]
    {:eids (to-set (get lt k-e)) :syms syms
     :ns-from-syms (into #{} (keep #(when (and (symbol? %) (namespace %)) (symbol (namespace %)))) syms)
     :schemas (to-set (get lt k-sc)) :refs (to-set (get lt k-r))}))

(defn- item-rules [it]
  (vec (distinct (remove nil? (cond (coll? (:rules it)) (:rules it) (some? (:rule it)) [(:rule it)] :else [])))))

(defn- rollup-by [sevals keys-fn]
  (let [raw (reduce (fn [acc {:keys [item tokens touched?]}]
                      (reduce (fn [a k]
                                (-> a (update-in [k :shown-count] (fnil inc 0))
                                    (update-in [k :touched-count] (fnil + 0) (if touched? 1 0))
                                    (update-in [k :tokens] (fnil + 0) tokens)
                                    (update-in [k :touched-tokens] (fnil + 0) (if touched? tokens 0))))
                              acc (keys-fn item)))
                    {} sevals)]
    (reduce-kv (fn [m k {:keys [shown-count touched-count tokens touched-tokens]}]
                 (assoc m k {:shown-count shown-count :touched-count touched-count
                             :precision (if (pos? shown-count) (clamp01 (/ (double touched-count) shown-count)) 0.0)
                             :tokens tokens :touched-tokens touched-tokens
                             :token-precision (if (pos? tokens) (clamp01 (/ (double touched-tokens) tokens)) 0.0)}))
               {} raw)))

(defn score-shown-blocks
  "Computes Precision, Token Precision, Recall, Recall Gap, F1, and `:by-rule` / `:by-fn` rollups."
  {:malli/schema [:=> [:cat [:vector :map] [:vector :map] [:maybe :map]] :seon.signal/scoreboard]
   :cost/ticks 15000000}
  [shown-blocks candidate-pool later-touches]
  (let [lt (if (map? later-touches) later-touches {})
        tt (target-bundle lt :touched-eids :touched-syms :touched-schemas :touched-refs)
        mt (target-bundle lt :manual-lookup-eids :manual-lookup-syms :manual-lookup-schemas :manual-lookup-refs)
        sevals (mapv (fn [b]
                       (let [id (item-identities b)]
                         {:item b :id id :tokens (touches/nonneg-long (or (:tokens b) (:measured-tokens b) 0))
                          :touched? (matches-targets? id tt)}))
                     shown-blocks)
        skeys (into #{} (map (comp :key :id)) sevals)
        sn (count sevals)
        stn (count (filter :touched? sevals))
        stoks (long (reduce + 0 (map :tokens sevals)))
        ttoks (long (reduce + 0 (map #(if (:touched? %) (:tokens %) 0) sevals)))
        prec (if (pos? sn) (clamp01 (/ (double stn) sn)) 0.0)
        tprec (if (pos? stoks) (clamp01 (/ (double ttoks) stoks)) 0.0)
        pool-map (reduce (fn [m it] (let [id (item-identities it) k (:key id)]
                                      (if (contains? m k) m (assoc m k {:item it :id id}))))
                         {} (concat candidate-pool shown-blocks))
        ptouched (filterv #(matches-targets? (:id %) tt) (vals pool-map))
        ptn (count ptouched)
        rec (if (pos? ptn) (clamp01 (/ (double (count (filter #(skeys (:key (:id %))) ptouched))) ptn)) 0.0)
        f1 (if (pos? (+ prec rec)) (clamp01 (/ (* 2.0 prec rec) (+ prec rec))) 0.0)
        gaps (vec (keep (fn [c]
                          (let [id (item-identities c)]
                            (when (and (not (skeys (:key id))) (matches-targets? id mt))
                              (cond-> {:entity (:entity c) :rules (item-rules c)
                                       :tokens (touches/nonneg-long (or (:tokens c) (:measured-tokens c) 0))
                                       :reason :dropped-from-shown}
                                (first (:syms id)) (assoc :sym (first (:syms id)))
                                (:schema c) (assoc :schema (:schema c))))))
                        candidate-pool))
        gn (count gaps)
        lvl (cond (or (>= gn 3) (and (pos? sn) (< prec 0.25))) :hot
                  (or (pos? gn) (and (pos? sn) (< prec 0.50))) :warm
                  :else :calm)]
    (with-meta
      {:level lvl :precision prec :token-precision tprec :recall rec :f1 f1
       :shown-count sn :shown-touched-count stn :shown-tokens stoks :touched-tokens ttoks
       :pool-size (count pool-map) :pool-touched-count ptn :recall-gap-count gn :recall-gap-items gaps
       :by-rule (rollup-by sevals #(or (seq (item-rules %)) [:unattributed]))
       :by-fn (rollup-by sevals #(vector (or (:chosen-fn %) (:fn %) :unknown)))
       :summary (str "P " (round-n (* 100.0 prec) 10.0) "% · tok-P " (round-n (* 100.0 tprec) 10.0)
                     "% · R " (round-n (* 100.0 rec) 10.0) "% · " gn " recall gap" (when (not= 1 gn) "s"))}
      {:seon/schema :seon.signal/scoreboard})))

(defn- filter-disabled-pool [pool disabled]
  (if (empty? disabled)
    pool
    (filterv (fn [c]
               (let [rs (item-rules c)]
                 (or (empty? rs) (not (every? disabled rs)))))
             pool)))

(defn- eval-profile [pool later prof]
  (let [ws (norm-weights (:weights prof))
        strat (if (= :depth-first (:strategy prof)) :depth-first :breadth-first)
        budget (touches/nonneg-long (or (:token-budget prof) 2400))
        dis (if (coll? (:disabled-rules prof)) (set (:disabled-rules prof)) #{})
        eff-pool (filter-disabled-pool pool dis)
        r-opts (cond-> {}
                 ws (assoc :weights ws)
                 (integer? (:turn prof)) (assoc :turn (long (:turn prof))))
        ladder (fit/upgrade-ladder (fit/rank-candidates eff-pool (when (seq r-opts) r-opts))
                                   (cond-> {:target :wire :strategy strat :token-budget budget}
                                     (integer? (:max-items prof)) (assoc :max-items (long (:max-items prof)))))]
    (cond-> (assoc (score-shown-blocks (:selected ladder) pool later)
                   :label (or (:label prof) :unnamed) :weights (or (:weights prof) fit/default-rank-weights)
                   :strategy strat :token-budget budget
                   :selected-count (count (:selected ladder)) :dropped-count (count (:dropped ladder)))
      (seq dis) (assoc :disabled-rules dis))))

(defn context-scoreboard
  "Live Datahike empirical scoreboard for `agent-id` over its projected context and eval history.
   Supports `{:disabled-rules #{...} :weights {...} :token-budget N :strategy ... :include-reasoning? true}`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :qualified-keyword] :seon.signal/scoreboard]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :seon.signal/scoreboard]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword [:maybe :map]] :seon.signal/scoreboard]]
   :cost/ticks 60000000}
  ([db agent-id] (context-scoreboard db db agent-id nil))
  ([main-db branch-db agent-id] (context-scoreboard main-db branch-db agent-id nil))
  ([main-db branch-db agent-id opts]
   (let [t0 (System/nanoTime)
         o (if (map? opts) opts {})
         bdb (or branch-db main-db)
         tx (when (integer? (or (:tx o) (:as-of-tx o))) (long (or (:tx o) (:as-of-tx o))))
         st (when (integer? (:split-turn o)) (long (:split-turn o)))
         inc-r? (if (contains? o :include-reasoning?) (boolean (:include-reasoning? o)) true)
         ev-db (if (seq (d/datoms bdb :avet :seon.agent.eval/agent agent-id)) bdb main-db)
         pool-opts (cond-> {:include-reasoning? inc-r? :eval-db ev-db}
                     st (assoc :upto-turn st)
                     (and (nil? st) tx) (assoc :upto-tx tx)
                     (seq (:disabled-rules o)) (assoc :disabled-rules (:disabled-rules o))
                     (:weights o) (assoc :weights (:weights o))
                     (:strategy o) (assoc :strategy (:strategy o)))
         pool (touches/build-candidate-pool (if tx (safe-as-of main-db tx) main-db)
                                            (if tx (safe-as-of bdb tx) bdb)
                                            agent-id pool-opts)
         lt-opts (cond-> {:include-reasoning? inc-r?}
                   st (assoc :after-turn st)
                   (and (nil? st) tx) (assoc :after-tx tx))
         lt (touches/extract-eval-touches ev-db agent-id lt-opts)
         sb (eval-profile pool lt (merge {:label :live :token-budget 16000 :strategy :breadth-first}
                                         (dissoc o :disabled-rules)))]
     (with-meta (cond-> (assoc sb :agent agent-id :tx (or tx (long (or (:max-tx bdb) 0)))
                               :eval-count (:eval-count lt)
                               :manual-lookup-count (count (:manual-lookup-calls lt))
                               :reasoning-mention-count (count (set/union (:reasoning-eids lt) (:reasoning-syms lt)))
                               :include-reasoning? inc-r?
                               :ms (round-n (/ (double (- (System/nanoTime) t0)) 1000000.0) 10.0))
                  (seq (:disabled-rules o)) (assoc :disabled-rules (set (:disabled-rules o))))
       {:seon/schema :seon.signal/scoreboard}))))

(defn compare-profiles
  "Pure hyperparameter sweep over pre-built `candidate-pool` and `later-touches` for `profiles`."
  {:malli/schema [:=> [:cat [:vector :map] [:maybe :map] [:maybe [:vector :map]]] :map]
   :cost/ticks 40000000}
  [candidate-pool later-touches profiles]
  (let [profs (if (and (sequential? profiles) (seq profiles)) (vec profiles) default-hyperparam-profiles)
        results (mapv #(eval-profile candidate-pool later-touches %) profs)
        winner (first (sort-by (fn [r] [(- (double (:f1 r 0.0))) (long (:recall-gap-count r 0))
                                        (- (double (:token-precision r 0.0))) (- (double (:recall r 0.0)))
                                        (long (:shown-tokens r 0)) (str (:label r))])
                               results))]
    {:pool-size (count candidate-pool)
     :profiles results
     :winner (:label winner)}))

(defn compare-as-of-hyperparams
  "Reproducible `d/as-of` A/B lab comparing ranking/ladder/rule-toggle profiles against later evals.
   Supports `:split-turn`, `:include-reasoning?` (default `true`), and `:disabled-rules`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :qualified-keyword :int] :map]
                  [:=> [:cat :seon.host/db :qualified-keyword :int [:maybe [:or [:vector :map] :map]]] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword :int [:maybe [:or [:vector :map] :map]]] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword :int [:maybe [:vector :map]] [:maybe :map]] :map]]
   :cost/ticks 80000000}
  ([db agent-id tx] (compare-as-of-hyperparams db db agent-id tx default-hyperparam-profiles nil))
  ([db agent-id tx profiles-or-opts]
   (if (map? profiles-or-opts)
     (compare-as-of-hyperparams db db agent-id tx (:profiles profiles-or-opts) profiles-or-opts)
     (compare-as-of-hyperparams db db agent-id tx profiles-or-opts nil)))
  ([main-db branch-db agent-id tx profiles-or-opts]
   (if (map? profiles-or-opts)
     (compare-as-of-hyperparams main-db branch-db agent-id tx (:profiles profiles-or-opts) profiles-or-opts)
     (compare-as-of-hyperparams main-db branch-db agent-id tx profiles-or-opts nil)))
  ([main-db branch-db agent-id tx profiles opts]
   (let [t0 (System/nanoTime)
         o (if (map? opts) opts {})
         inc-r? (if (contains? o :include-reasoning?) (boolean (:include-reasoning? o)) true)
         bdb (or branch-db main-db)
         ev-db (if (seq (d/datoms bdb :avet :seon.agent.eval/agent agent-id)) bdb main-db)
         all-evals (touches/pull-agent-evals ev-db agent-id)
         explicit-st (when (integer? (:split-turn o)) (long (:split-turn o)))
         tx-later (when-not explicit-st
                    (touches/extract-eval-touches ev-db all-evals {:after-tx (long tx) :include-reasoning? inc-r?}))
         auto-st (when (and (nil? explicit-st) (zero? (:eval-count tx-later)) (>= (count all-evals) 2))
                   (let [turns (vec (sort (distinct (map #(touches/nonneg-long (:seon.agent.eval/turn %)) all-evals))))]
                     (when (>= (count turns) 2)
                       (nth turns (quot (dec (count turns)) 2)))))
         split-turn (or explicit-st auto-st)
         pool-opts (cond-> {:include-reasoning? inc-r? :eval-db ev-db :all-evals all-evals}
                     split-turn (assoc :upto-turn split-turn)
                     (not split-turn) (assoc :upto-tx (long tx))
                     (seq (:disabled-rules o)) (assoc :disabled-rules (:disabled-rules o)))
         pool (touches/build-candidate-pool (safe-as-of main-db tx) (safe-as-of bdb tx) agent-id pool-opts)
         later (if split-turn
                 (touches/extract-eval-touches ev-db all-evals {:after-turn split-turn :include-reasoning? inc-r?})
                 tx-later)
         cmp (compare-profiles pool later profiles)]
     (cond-> (assoc cmp
                    :tx (long tx) :agent agent-id
                    :split-mode (if split-turn :turn :tx)
                    :include-reasoning? inc-r?
                    :reasoning-mention-count (count (set/union (:reasoning-eids later) (:reasoning-syms later)))
                    :actual-touched-count (count (set/union (:touched-eids later) (:touched-syms later) (:touched-schemas later)))
                    :later-eval-count (:eval-count later) :manual-lookup-count (count (:manual-lookup-calls later))
                    :ms (round-n (/ (double (- (System/nanoTime) t0)) 1000000.0) 10.0))
       split-turn (assoc :split-turn split-turn)))))

(defn- resolve-agent-dbs
  {:seon.flow/edge true}
  [ctx-fn aid]
  (let [{:keys [conn branch agent-id]} (ctx-fn)
        eff-aid (or (when (qualified-keyword? aid) aid) agent-id :agent/root)
        bdb @conn
        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
        ag-br (when ((touches/db-schema mdb) :seon.agent/branch)
                (:seon.agent/branch (d/pull mdb '[:seon.agent/branch] [:seon.agent/id eff-aid])))
        tbdb (if (and ag-br (not= ag-br branch)) (or (try (store/head conn ag-br) (catch Throwable _ nil)) bdb) bdb)]
    {:mdb mdb :bdb tbdb :aid eff-aid}))

(defn- run-compare [ctx-fn agent-id tx profiles-or-opts]
  (let [{:keys [mdb bdb aid]} (resolve-agent-dbs ctx-fn agent-id)
        ev-db (if (seq (d/datoms bdb :avet :seon.agent.eval/agent aid)) bdb mdb)
        evs (touches/pull-agent-evals ev-db aid)
        txs (vec (sort (distinct (map #(long (:_tx % 0)) evs))))
        turns (vec (sort (distinct (map #(touches/nonneg-long (:seon.agent.eval/turn %)) evs))))
        eff-tx (if (integer? tx)
                 (long tx)
                 (cond (>= (count txs) 2) (nth txs (quot (dec (count txs)) 2))
                       (and (= 1 (count txs)) (>= (count turns) 2)) (first txs)
                       (= 1 (count txs)) (dec (first txs))
                       :else (long (or (:max-tx bdb) 0))))]
    (compare-as-of-hyperparams mdb bdb aid eff-tx (or profiles-or-opts default-hyperparam-profiles))))

(defn install-user-helpers!
  "Installs `user/context-scoreboard` and `user/compare-hyperparams` into namespace `u`."
  {:seon.flow/edge true :malli/schema [:=> [:cat :any :any] :any]}
  [u ctx-fn]
  (when (and (instance? clojure.lang.Namespace u) (fn? ctx-fn))
    (let [v1 (intern u 'context-scoreboard
                     (fn csb
                       ([] (csb nil {}))
                       ([arg] (if (map? arg) (csb nil arg) (csb arg {})))
                       ([agent-id opts]
                        (let [{:keys [mdb bdb aid]} (resolve-agent-dbs ctx-fn agent-id)]
                          (context-scoreboard mdb bdb aid opts)))))
          v2 (intern u 'compare-hyperparams
                     (fn chp
                       ([] (run-compare ctx-fn nil nil nil))
                       ([agent-id] (run-compare ctx-fn agent-id nil nil))
                       ([agent-id tx] (run-compare ctx-fn agent-id tx nil))
                       ([agent-id tx profiles] (run-compare ctx-fn agent-id tx profiles))))]
      (alter-meta! v1 merge {:arglists '([] [agent-id-or-opts] [agent-id opts])
                             :doc "Compute empirical context Precision, Token Precision, Recall, Recall Gap, and rollups."})
      (alter-meta! v2 merge {:arglists '([] [agent-id] [agent-id tx] [agent-id tx profiles])
                             :doc "Run reproducible d/as-of A/B hyperparameter sweep across rank weights, strategies, budgets, and rule toggles."})
      [v1 v2])))
