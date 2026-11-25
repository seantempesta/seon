(ns seon.repl.context
  "Extracted child namespace of `seon.repl`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
            [seon.health :as health]
            [seon.store :as store]
            [seon.render.wire]))

(defn- first-sentence [doc]
  (when (and (string? doc) (not (str/blank? doc)))
    (let [flat (str/replace (str/trim doc) #"\s+" " ")]
      (loop [from 0]
        (let [idx (.indexOf ^String flat ". " (int from))]
          (cond (neg? idx) flat
                (or (str/ends-with? (subs flat 0 (inc idx)) "e.g.") (str/ends-with? (subs flat 0 (inc idx)) "i.e.")) (recur (+ idx 2))
                :else (subs flat 0 (inc idx))))))))

(defn- helper-detail [sym v]
  (let [m (meta v)]
    (cond-> {:var (symbol "user" (name sym))}
      (:arglists m) (assoc :arglists (:arglists m)) (:doc m) (assoc :doc (:doc m))
      (:malli/schema m) (assoc :malli/schema (:malli/schema m)) (:seon.repl/source m) (assoc :source (:seon.repl/source m)))))

(defn- helper-summary-line [v]
  (let [m (meta v) al (:arglists m) doc (first-sentence (:doc m))]
    (cond (and (seq al) doc) (str (pr-str al) " — " doc) (seq al) (pr-str al) doc doc :else "")))

(defn- user-helper-vars []
  (let [u (or (find-ns 'user) (create-ns 'user))]
    (when-not (ns-resolve u 'help)
      (when-let [inst (requiring-resolve 'seon.fork.helpers/install-agent-helpers!)] (inst)))
    (into {} (filter (fn [[_ v]] (let [m (meta v)] (or (:doc m) (:arglists m) (and (bound? v) (fn? @v)))))) (ns-publics u))))

(defn help
  "Introspects `(ns-publics 'user)`: `(help)` lists helpers, `(help 'fn)` shows details, `(help \"term\")` searches."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :any] :map]] :cost/ticks 25000000}
  ([] (into (sorted-map) (map (fn [[k v]] [k (helper-summary-line v)])) (user-helper-vars)))
  ([x]
   (let [vars (user-helper-vars) avail #(vec (sort (keys vars)))]
     (cond
       (or (symbol? x) (keyword? x))
       (let [nm (symbol (name x))]
         (if-let [v (get vars nm)] (helper-detail nm v) {:error :unknown-helper :sym x :available (avail)}))
       (string? x)
       (let [q (str/trim x) bare (if (str/starts-with? q "user/") (subs q 5) q)
             es (when (and (not (str/blank? bare)) (not (re-find #"\s" bare))) (symbol bare))]
         (if-let [v (and es (get vars es))]
           (helper-detail es v)
           (let [ql (str/lower-case q)
                 ms (when-not (str/blank? ql)
                      (filter (fn [[k v]] (or (str/includes? (str/lower-case (name k)) ql)
                                              (str/includes? (str/lower-case (str (:doc (meta v)))) ql))) vars))]
             (if (seq ms) (into (sorted-map) (map (fn [[k v]] [k (helper-detail k v)])) ms)
                 {:error :no-matching-helpers :query x :available (avail)}))))
       :else {:error :unknown-helper :sym x :available (avail)}))))

(defn- ^:seon.flow/edge repl-dbs [o]
  (let [c (or (:conn o) (:conn *agent-ctx*)) b (or (:db o) (when c @c))]
    {:conn c :bdb b :mdb (or (:main-db o) (when c (store/head c :main)) b)}))

(defn ^:seon.flow/edge simulate-context
  "Projects `aid` on branch vs `:main` (plus wire for agents, and `:walk-text` when `:render-text? true`) with `:diff-vs-main`."
  {:malli/schema [:=> [:cat [:* :any]] [:maybe :map]] :cost/ticks 60000000}
  ([] (simulate-context (:agent-id *agent-ctx*) nil))
  ([aid] (simulate-context aid nil))
  ([aid opts]
   (let [o (if (map? opts) opts {}) {:keys [conn bdb mdb]} (repl-dbs o)]
     (when (and mdb bdb)
       (let [t (or aid (:agent-id *agent-ctx*) [:seon.system/id :root])
             fr (cond (vector? t) t (qualified-keyword? t) [:seon.agent/id t] :else [:seon.system/id :root])
             po (merge {:target :wire :use-cache? false} (dissoc o :db :main-db :conn :wire? :render-text?))
             br (ctx/project-entity mdb bdb fr po) mr (ctx/project-entity mdb mdb fr po)
             bf (:fitted br) mf (:fitted mr) ks [:ref :entity :schema :zone :chosen-fn :tokens :area :rank/score :rules :status]
             ix #(into {} (map (fn [i] [(or (:ref i) [:db/id (:entity i)]) i])) %)
             bm (ix (:selected bf)) mm (ix (:selected mf)) bk (set (keys bm)) mk (set (keys mm))
             wr (when (and (= :seon.agent/id (first fr)) (not= false (:wire? o)))
                  ((requiring-resolve 'seon.render.wire/build-live-wire-request)
                   mdb bdb (second fr) (merge {:conn conn :include-tail? false} (select-keys o [:already-shown :token-budget :strategy :disabled-rules :weights]))))]
         (cond-> {:focus-ref (:focus-ref br fr) :selected (mapv #(select-keys % ks) (:selected bf)) :dropped (mapv #(select-keys % ks) (:dropped bf))
                  :unmatched (vec (:unmatched br)) :tokens (long (:tokens bf 0)) :budget (long (:budget bf 0)) :rule-trace (vec (:rule-trace br))
                  :diff-vs-main {:added (vec (sort-by pr-str (set/difference bk mk))) :removed (vec (sort-by pr-str (set/difference mk bk)))
                                 :changed-fn (vec (keep #(let [a (:chosen-fn (mm %)) b (:chosen-fn (bm %))] (when (not= a b) {:ref % :from a :to b})) (sort-by pr-str (set/intersection bk mk))))
                                 :token-delta (- (long (:tokens bf 0)) (long (:tokens mf 0)))}}
           wr (assoc :wire (cond-> {:messages (count (:messages wr)) :tokens (long (or (get-in wr [:fit-trace :tokens]) (:tokens bf 0)))}
                             (:render-text? o) (assoc :walk-text (:walk-text wr))))))))))

(defn ^:seon.flow/edge check-corpus
  "Runs the 12-scenario Situation Corpus & generality lint on branch vs `:main`."
  {:malli/schema [:=> [:cat [:* :any]] [:maybe :map]], :cost/ticks 80000000, :seon.eval/budget-override {:ticks 600000000, :bytes 1073741824}}
  ([] (check-corpus nil))
  ([opts]
   (let [{:keys [bdb mdb]} (repl-dbs (if (map? opts) opts {})) rr requiring-resolve]
     (when bdb
       (let [cov-fn (rr 'seon.health.coverage/coverage-report)
             br (cov-fn bdb {:include-corpus? true :include-atlas? true})
             mr (if (or (nil? mdb) (= bdb mdb)) br (cov-fn mdb {:include-corpus? true :include-atlas? false}))
             ps #(into #{} (comp (filter :pass?) (map :id)) (:corpus %))
             bp (ps br) mp (ps mr) rg (vec (sort (set/difference mp bp)))]
         {:corpus-score (str (get-in br [:counts :corpus-passed] 0) "/" (get-in br [:counts :corpus-total] 12))
          :delta-vs-main {:newly-passed (vec (sort (set/difference bp mp))) :regressed rg :ok? (empty? rg)}
          :generality-issues ((rr 'seon.health.coverage/lint-rule-generality) ((rr 'seon.rule/load-rules) bdb))
          :redundant-rules (mapv :rule-id (remove :ablation-needed? (:ablation br)))
          :schema-holes (filterv #(not (#{:complete :policy-never} (:status %))) (:schema-matrix br)) :corpus (:corpus br)})))))

(defn ^:seon.flow/edge tune-context!
  "Pauses `aid` & spawns `:tune/<name>-t<tx>` (map delegates to `fcalls/tune-context!`)."
  {:malli/schema [:=> [:cat :any [:* :any]] [:maybe :map]] :cost/ticks 40000000}
  ([x] (if (map? x) (fcalls/tune-context! x) (tune-context! x nil)))
  ([aid opts]
   (if (map? aid) (fcalls/tune-context! aid)
       (let [o (if (map? opts) opts {}) {:keys [conn bdb mdb]} (repl-dbs o) db (or bdb mdb)
             ref [:seon.agent/id aid] tid (or (:tuner-id o) (keyword "tune" (str (name aid) "-t" (long (or (:max-tx db) 0)))))
             ms (or (:misses o) (when db (filterv #(= aid (:agent %)) ((requiring-resolve 'seon.health.coverage/detect-trace-misses) mdb db))))
             g (or (:goal o) (str "Tune context for " aid (when-let [m (first ms)] (str " (missed " (pr-str (:missed-ref m)) ")")) "."))
             ptx [{:seon.agent/id aid :seon.agent/paused? true}]
             sop (cond-> {:op :spawn-agent :id tid :forked-from ref :merge-target ref :tasks [:template/tune-context] :auto-merge? true :pause-parent? true :goal g}
                   (:branch o) (assoc :branch (:branch o))
                   (map? (:config o)) (assoc :config (:config o)))
             plan {:ok? true :target aid :tuner-id tid :goal g :misses (vec ms) :pause-tx ptx :spawn-op sop :ops [{:op :transact :tx-data ptx} sop]}]
         (cond
           (:dry-run? o) (assoc plan :dispatched? false)
           (and (nil? (:conn o)) (:in *agent-ctx*) (:out *agent-ctx*))
           (let [p (fcalls/tx-main-sync! {:op :transact :tx-data ptx}) s (fcalls/tx-main-sync! sop)]
             (assoc plan :dispatched? true :pause p :spawn s :ok? (boolean (and (:ok? p true) (:ok? s true)))))
           conn
           (let [te (:e (first (d/datoms @conn :avet :seon.task/id :template/tune-context)))
                 ent (cond-> {:seon.agent/id tid :seon.agent/branch (keyword "branch" (name tid)) :seon.agent/forked-from ref :seon.agent/merge-target ref :seon.agent/auto-merge? true :seon.agent/goal g} te (assoc :seon.agent/tasks #{te}))]
             (assoc plan :dispatched? true :local-tx (:max-tx (:db-after (d/transact conn {:tx-data (conj ptx ent)})))))
           :else (assoc plan :dispatched? false))))))
