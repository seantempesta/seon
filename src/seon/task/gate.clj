(ns seon.task.gate
  "Multi-task assignment (`:seon.agent/tasks`) and declarative proof gating (`:rule.gate/*`)
   over both legacy `:seon.task/tests` and true `:db.type/ref` `:seon.task/proofs`."
  (:require [datahike.api :as d]
            [seon.rules :as rules]
            [seon.store :as store]))

(defn- eid-of [db a v]
  (when (and db (some? v) (get-in db [:schema a]))
    (:e (first (d/datoms db :avet a v)))))

(defn- eavt-val [db e a]
  (when (and db e (get-in db [:schema a]))
    (:v (first (d/datoms db :eavt e a)))))

(defn- agent-ref-tasks [db aid]
  (when (and (get-in db [:schema :seon.agent/tasks]) (get-in db [:schema :seon.task/id]))
    (when-let [eid (eid-of db :seon.agent/id aid)]
      (->> (d/datoms db :eavt eid :seon.agent/tasks)
           (keep #(eavt-val db (:v %) :seon.task/id))
           (filter qualified-keyword?)
           (sort-by str)))))

(defn agent-task-ids
  "Returns vector of ALL task ids assigned to `agent-id` (`:seon.agent/task` + `:seon.agent/tasks`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:vector :qualified-keyword]]
   :cost/ticks 500000}
  [db agent-id]
  (if-let [eid (eid-of db :seon.agent/id agent-id)]
    (let [leg (eavt-val db eid :seon.agent/task)]
      (vec (distinct (concat (when (qualified-keyword? leg) [leg]) (agent-ref-tasks db agent-id)))))
    []))

(defn task-required-proof-syms
  "Returns sorted set of required test var symbols (`:seon.task/tests` + `:seon.task/proofs`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :qualified-keyword]] [:set :symbol]]
   :cost/ticks 500000}
  [db task-id]
  (if-let [teid (eid-of db :seon.task/id task-id)]
    (let [leg (when (get-in db [:schema :seon.task/tests])
                (keep #(when (symbol? (:v %)) (:v %)) (d/datoms db :eavt teid :seon.task/tests)))
          refs (when (get-in db [:schema :seon.task/proofs])
                 (keep #(let [ve (:v %) vn (eavt-val db ve :seon.code.var/name)]
                          (cond (symbol? vn) vn (integer? ve) (symbol "dangling-ref" (str "eid-" ve))))
                       (d/datoms db :eavt teid :seon.task/proofs)))]
      (into (sorted-set) (concat leg refs)))
    (sorted-set)))

(defn gate-rules
  "Declarative `:seon.rule/*` rules (`:rule.gate/task-needs-open`, `:rule.gate/proof-missing`,
   `:rule.gate/proof-failing`, `:rule.gate/agent-blocked`) deriving `:gate/blocker` facts."
  {:malli/schema [:=> [:cat] [:vector :map]]
   :cost/ticks 500000}
  []
  (let [b {:seon.rule/category :health :seon.rule/enabled? true :seon.rule/target :any}]
    [(assoc b :seon.rule/id :rule.gate/task-needs-open :seon.rule/stratum 20
            :seon.rule/doc "Block task when any :seon.task/needs prerequisite is open."
            :seon.rule/when '[[?t :gate/needs ?need] [:not [?need :gate/task-done? true]]]
            :seon.rule/assert '[[?t :gate/blocked? true]
                                [?t :gate/blocker {:task ?t :reason :task-needs-open :detail {:need ?need}}]])
     (assoc b :seon.rule/id :rule.gate/proof-missing :seon.rule/stratum 20
            :seon.rule/doc "Block task when a required proof var or verdict is missing."
            :seon.rule/when '[[?t :gate/requires-proof ?sym] [:optional [?sym :gate/var-exists? ?exists?]]
                              [:optional [?sym :gate/latest-pass? ?pass?]]
                              [:guard (or (not ?exists?) (nil? ?pass?))]
                              [:bind ?cause (if (not ?exists?) :var-missing :verdict-missing)]]
            :seon.rule/assert '[[?t :gate/blocked? true]
                                [?t :gate/blocker {:task ?t :reason :proof-missing :detail {:var ?sym :cause ?cause}}]])
     (assoc b :seon.rule/id :rule.gate/proof-failing :seon.rule/stratum 20
            :seon.rule/doc "Block task when a required proof var's latest digest failed."
            :seon.rule/when '[[?t :gate/requires-proof ?sym] [?sym :gate/var-exists? true] [?sym :gate/latest-pass? false]
                              [:optional [?sym :gate/latest-digest ?dg]] [:optional [?sym :gate/latest-report ?rep]]
                              [:bind ?detail (if (some? ?rep) (hash-map :var ?sym :digest ?dg :report ?rep)
                                                 (hash-map :var ?sym :digest ?dg))]]
            :seon.rule/assert '[[?t :gate/blocked? true]
                                [?t :gate/blocker {:task ?t :reason :proof-failing :detail ?detail}]])
     (assoc b :seon.rule/id :rule.gate/agent-blocked :seon.rule/stratum 30
            :seon.rule/doc "Block agent whenever any assigned task is blocked."
            :seon.rule/when '[[?ag :gate/assigned-task ?t] [?t :gate/blocked? true] [?t :gate/blocker ?b]]
            :seon.rule/assert '[[?ag :gate/blocked? true] [?ag :gate/blocker ?b]])]))

(defn- tagged-dbs [mdb bdb]
  (cond-> [] mdb (conj [:main mdb]) (and bdb (not= bdb mdb)) (conj [:branch bdb])))

(defn- latest-scalar [tdbs id-a id-v a]
  (let [cs (for [[src db] tdbs
                 :let [e (eid-of db id-a id-v)
                       dt (when (and e (get-in db [:schema a])) (first (d/datoms db :eavt e a)))]
                 :when dt]
             {:v (:v dt) :tx (long (or (:tx dt) 0)) :src src})]
    (:v (first (sort-by (fn [x] [(- (:tx x)) (if (= :branch (:src x)) 0 1)]) cs)))))

(defn- latest-verdict [tdbs psym]
  (let [cs (for [[src db] tdbs
                 :let [s (:schema db)] :when (and psym (:seon.test/var s) (:seon.test/pass? s))
                 dt (d/datoms db :avet :seon.test/var psym)
                 :let [e (:e dt) pdt (first (d/datoms db :eavt e :seon.test/pass?))] :when pdt]
             {:pass? (boolean (:v pdt)) :tx (long (or (:tx pdt) (:tx dt) 0)) :src src :eid e
              :digest (eavt-val db e :seon.test/digest) :report (eavt-val db e :seon.test/report)})]
    (first (sort-by (fn [v] [(- (:tx v)) (if (= :branch (:src v)) 0 1) (if (:pass? v) 1 0) (- (:eid v))]) cs))))

(defn- seed-gate-wm [tdbs aid tids opts]
  (let [needs-of #(when-let [e (and (get-in %1 [:schema :seon.task/needs]) (eid-of %1 :seon.task/id %2))]
                    (keep (fn [dt] (when (qualified-keyword? (:v dt)) (:v dt))) (d/datoms %1 :eavt e :seon.task/needs)))
        all-p (volatile! #{}) all-n (volatile! #{})
        base (reduce (fn [acc tid]
                       (let [ns (distinct (mapcat #(needs-of (second %) tid) tdbs))
                             ps (into (sorted-set) (mapcat #(task-required-proof-syms (second %) tid) tdbs))]
                         (vswap! all-n into ns) (vswap! all-p into ps)
                         (-> acc (conj [aid :gate/assigned-task tid])
                             (into (map #(vector tid :gate/needs %) ns))
                             (into (map #(vector tid :gate/requires-proof %) ps)))))
                     [[aid :gate/agent aid]] tids)
        need-fs (for [nid @all-n :when (true? (latest-scalar tdbs :seon.task/id nid :seon.task/done?))]
                  [nid :gate/task-done? true])
        up (set (:upserted-vars opts)) ret (set (:retracted-vars opts))
        proof-fs (mapcat (fn [s]
                           (let [ex? (boolean (and (not (ret s)) (or (up s) (some #(eid-of (second %) :seon.code.var/name s) tdbs))))
                                 vd (when ex? (latest-verdict tdbs s))]
                             (cond-> [[s :gate/var-exists? ex?]]
                               vd (conj [s :gate/latest-pass? (:pass? vd)])
                               (:digest vd) (conj [s :gate/latest-digest (:digest vd)])
                               (:report vd) (conj [s :gate/latest-report (:report vd)]))))
                         @all-p)]
    (rules/add-facts (rules/empty-wm) (concat base need-fs proof-fs) {:rule :seed/gate :stratum 0})))

(defn evaluate-agent-gate
  "Evaluates `:rule.gate/*` via `seon.rules/step-rules` over all assigned tasks."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] :map]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword] [:maybe :map]] :map]]
   :cost/ticks 15000000}
  ([main-db branch-db agent-id] (evaluate-agent-gate main-db branch-db agent-id nil))
  ([main-db branch-db agent-id opts]
   (let [tdbs (tagged-dbs main-db branch-db)
         tids (if (seq (:tasks opts))
                (vec (distinct (:tasks opts)))
                (let [sc (when agent-id (latest-scalar tdbs :seon.agent/id agent-id :seon.agent/task))
                      refs (sort-by str (distinct (mapcat #(agent-ref-tasks (second %) agent-id) tdbs)))]
                  (vec (distinct (concat (when (qualified-keyword? sc) [sc]) refs)))))]
     (if (empty? tids)
       {:ok? true :tasks [] :passed-tasks [] :blocked-tasks [] :blockers []}
       (let [aid (or agent-id :gate/anonymous)
             wm0 (seed-gate-wm tdbs aid tids opts)
             pull-r #(when (eid-of %1 :seon.rule/id %2)
                       (let [r (dissoc (d/pull %1 '[*] [:seon.rule/id %2]) :db/id)] (when (:seon.rule/id r) r)))
             rules (or (seq (:rules opts))
                       (mapv #(or (some (fn [[_ db]] (pull-r db (:seon.rule/id %))) (rseq (vec tdbs))) %) (gate-rules)))
             dbs (cond-> {} main-db (assoc :main main-db) (or branch-db main-db) (assoc :branch (or branch-db main-db)))
             wm (:wm (rules/step-rules nil wm0 dbs (vec rules) nil))
             prov (:provenance wm)
             ab (rules/wm-vals wm aid :gate/blocker)
             bset (into #{} (keep :task) ab)
             blockers (->> ab
                           (mapv #(if-let [rid (:rule (first (get prov [(:task %) :gate/blocker %])))] (assoc % :rule rid) %))
                           (sort-by #(vector (str (:task %)) (str (:reason %)) (pr-str (:detail %))))
                           vec)]
         {:ok? (empty? bset) :tasks tids
          :passed-tasks (filterv #(not (contains? bset %)) tids)
          :blocked-tasks (filterv #(contains? bset %) tids)
          :blockers blockers})))))

(defn- stale-retracts [db aid keep-set]
  (when-let [eid (and (get-in db [:schema :seon.agent/tasks]) (eid-of db :seon.agent/id aid))]
    (keep #(when-let [tid (eavt-val db (:v %) :seon.task/id)]
             (when-not (contains? keep-set tid) [:db/retract [:seon.agent/id aid] :seon.agent/tasks [:seon.task/id tid]]))
          (d/datoms db :eavt eid :seon.agent/tasks))))

(defn- try-resolve [sym] (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn- assign-tasks-impl [get-ctx raw-args]
  (when-let [{:keys [conn agent-id in out]} (get-ctx)]
    (let [step (fn s [x] (cond (nil? x) nil
                               (and (vector? x) (= :seon.task/id (first x))) [(second x)]
                               (coll? x) (mapcat s x) :else [x]))
          tids (->> raw-args (mapcat step) (filter qualified-keyword?) distinct vec)]
      (if (empty? tids)
        {:ok? false :error "Provide at least one :seon.task/id keyword."}
        (let [bdb @conn mdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb)
              pull-t #(when-let [e (eid-of %1 :seon.task/id %2)] (d/pull %1 '[:seon.task/id :seon.task/title :seon.task/scope] e))
              found (mapv #(vector % (or (when-let [e (pull-t bdb %)] [:branch e]) (when-let [e (pull-t mdb %)] [:main e]))) tids)
              missing (vec (keep (fn [[tid f]] (when-not f tid)) found))]
          (if (seq missing)
            {:ok? false :error (str "Task(s) not found: " (pr-str missing)) :missing missing}
            (let [seed-tx (for [[_ [src e]] found :when (= :main src)]
                            (cond-> {:seon.task/id (:seon.task/id e) :seon.task/title (or (:seon.task/title e) (name (:seon.task/id e)))}
                              (:seon.task/scope e) (assoc :seon.task/scope (:seon.task/scope e))))
                  scope (:seon.task/scope (second (second (first found)))) kset (set tids)
                  mk (fn [ts] (cond-> {:seon.agent/id agent-id :seon.agent/task (first ts)
                                       :seon.agent/tasks (mapv #(vector :seon.task/id %) ts)}
                                scope (assoc :seon.agent/scope scope)))
                  r (d/transact conn {:tx-data (vec (concat seed-tx (stale-retracts bdb agent-id kset) [(mk tids)]))})]
              (when-let [rf (try-resolve 'seon.ctx/refresh-active!)] (rf (:db-after r) (:tx-data r)))
              (when (and in out (eid-of mdb :seon.agent/id agent-id))
                (when-let [sf (try-resolve 'seon.fork.calls/tx-main-sync!)]
                  (when-let [mt (seq (filter #(eid-of mdb :seon.task/id %) tids))]
                    (sf {:op :transact :reason [:assign-tasks agent-id (vec mt)]
                         :tx-data (vec (concat (stale-retracts mdb agent-id kset) [(mk mt)]))}))))
              {:ok? true :agent-id agent-id :task (first tids) :tasks tids :scope scope})))))))

(defn- gate-status-impl [get-ctx target-aid opts]
  (when-let [{:keys [conn branch agent-id]} (get-ctx)]
    (let [bdb @conn mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))]
      (evaluate-agent-gate mdb bdb (or target-aid agent-id) opts))))

(defn install-user-helpers!
  "Interns `user/assign-tasks!` and `user/gate-status`."
  {:malli/schema [:=> [:cat :seon.host/ns :seon.host/fn] :any]
   :cost/ticks 1000000}
  [u get-agent-ctx-fn]
  (let [v1 (intern u 'assign-tasks! (fn [& args] (assign-tasks-impl get-agent-ctx-fn args)))
        v2 (intern u 'gate-status (fn ([] (gate-status-impl get-agent-ctx-fn nil nil))
                                    ([aid] (gate-status-impl get-agent-ctx-fn aid nil))
                                    ([aid opts] (gate-status-impl get-agent-ctx-fn aid opts))))]
    (alter-meta! v1 merge {:arglists '([task-ids] [t1 t2 & more]) :doc "Assign 1..N :seon.task/id refs."})
    (alter-meta! v2 merge {:arglists '([] [agent-id] [agent-id opts]) :doc "Inspect :rule.gate/* proof gating."})
    [v1 v2]))
