(ns seon.task.gate
  "Multi-task assignment (`:seon.agent/tasks`) and declarative test gating (`:rule.gate/*`)
   over both legacy `:seon.task/tests` and true `:db.type/ref` `:seon.task/test-vars`."
  (:require [datahike.api :as d]
            [seon.rule :as rules]
            [seon.store :as store]
            [seon.rule.seed :as rseed]
            [clojure.string :as str]
            [seon.reconciler.policy :as policy]
            [seon.ctx.diff :as diff]
            [seon.task.refine :as refine]
            [seon.task.review :as review]
            [seon.render.data :as rdata]))

(defn- eid-of [& args] (apply review/eid-of args))

(defn- eavt-val [& args] (apply review/eavt-val args))

(defn- agent-ref-tasks [db aid]
  (when (and (get-in db [:schema :seon.agent/tasks]) (get-in db [:schema :seon.task/id]))
    (when-let [eid (eid-of db :seon.agent/id aid)]
      (->> (d/datoms db :eavt eid :seon.agent/tasks)
           (keep #(eavt-val db (:v %) :seon.task/id))
           (filter qualified-keyword?)
           (sort-by str)))))

(defn ^{:seon.code/reexport (quote seon.render.data/agent-task-ids)} agent-task-ids
  "Re-exports `seon.render.data/agent-task-ids`."
  [db agent-id]
  (rdata/agent-task-ids db agent-id))

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

(defn- stale-retracts [db aid keep-set]
  (when-let [eid (and (get-in db [:schema :seon.agent/tasks]) (eid-of db :seon.agent/id aid))]
    (keep #(when-let [tid (eavt-val db (:v %) :seon.task/id)]
             (when-not (contains? keep-set tid) [:db/retract [:seon.agent/id aid] :seon.agent/tasks [:seon.task/id tid]]))
          (d/datoms db :eavt eid :seon.agent/tasks))))

(defn- ^:seon.flow/edge try-resolve [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn task-required-test-syms
  "Returns sorted set of required test var symbols (`:seon.task/tests` + `:seon.task/test-vars` + `:seon.task/proofs`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :qualified-keyword]] [:set :symbol]]
   :cost/ticks 500000}
  [db task-id]
  (if-let [teid (eid-of db :seon.task/id task-id)]
    (let [leg (when (get-in db [:schema :seon.task/tests])
                (keep #(when (symbol? (:v %)) (:v %)) (d/datoms db :eavt teid :seon.task/tests)))
          pull-var-refs (fn [attr]
                          (when (get-in db [:schema attr])
                            (keep #(let [ve (:v %) vn (eavt-val db ve :seon.code.var/name)]
                                     (cond (symbol? vn) vn (integer? ve) (symbol "dangling-ref" (str "eid-" ve))))
                                  (d/datoms db :eavt teid attr))))]
      (into (sorted-set) (concat leg (pull-var-refs :seon.task/test-vars) (pull-var-refs :seon.task/proofs))))
    (sorted-set)))

(defn- seed-gate-wm [tdbs aid tids opts]
  (let [needs-of #(when-let [e (and (get-in %1 [:schema :seon.task/needs]) (eid-of %1 :seon.task/id %2))]
                    (keep (fn [dt] (when (qualified-keyword? (:v dt)) (:v dt))) (d/datoms %1 :eavt e :seon.task/needs)))
        all-p (volatile! #{}) all-n (volatile! #{})
        base (reduce (fn [acc tid]
                       (let [ns (distinct (mapcat #(needs-of (second %) tid) tdbs))
                             ps (into (sorted-set) (mapcat #(task-required-test-syms (second %) tid) tdbs))]
                         (vswap! all-n into ns) (vswap! all-p into ps)
                         (-> acc (conj [aid :gate/assigned-task tid])
                             (into (map #(vector tid :gate/needs %) ns))
                             (into (map #(vector tid :gate/requires-test %) ps)))))
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

(defn- format-gate-blocker [b]
  (let [{:keys [task reason detail]} b]
    (case reason
      :test-missing (str "- " task ": required test " (:var detail) " (" (name (or (:cause detail) :missing)) ")")
      :test-failing (str "- " task ": required test " (:var detail) " FAILED"
                         (when (and (string? (:report detail)) (not (str/blank? (:report detail))))
                           (str " — " (first (str/split-lines (:report detail))))))
      (:review-hijacked-ready :review-no-op :rule-generality-violation :corpus-regression
      :refinement-missing-parent :refinement-missing-proofs :refinement-missing-refs)
      (str "- " task ": " (or (:message detail) (name reason)))
      (str "- " task ": " (name (or reason :blocked)) " " (pr-str detail)))))

(defn gate-blocked-notify-op
  "Returns a `:gate-blocked` message op when `status` is `:gate-blocked` with fixable test/refinement blockers not yet notified."
  {:malli/schema [:=> [:cat :keyword :map :keyword :any [:set :any]] [:maybe :map]]
   :cost/ticks 500000}
  [status gate agent-id head-tx gate-notified-set]
  (when (and (= status :gate-blocked)
             (not (contains? gate-notified-set [agent-id head-tx])))
    (let [fixable (filterv #(contains? #{:test-missing :test-failing :review-hijacked-ready :review-no-op :rule-generality-violation :corpus-regression
                                       :refinement-missing-parent :refinement-missing-proofs :refinement-missing-refs}
                                       (:reason %))
                           (:blockers gate))]
      (when (seq fixable)
        {:op :send-message :from :reconciler :to agent-id :type :gate-blocked
         :body (str "Merge gate blocked at branch tx " head-tx
                    " — fix these required proof blocker(s) and reply concisely when done:\n"
                    (str/join "\n" (map format-gate-blocker fixable)))}))))

(defn ^:seon.flow/edge branch-latest-edit-info
  "Delegates to `seon.ctx.diff/branch-latest-edit-info`."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] :map]
                            [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :map]] :map]]
   :cost/ticks 6000000}
  ([main-db branch-db] (diff/branch-latest-edit-info main-db branch-db nil))
  ([main-db branch-db ma] (diff/branch-latest-edit-info main-db branch-db ma)))

(defn ^:seon.flow/edge branch-has-code-edits?
  "True when `branch-db` contains domain/rule/schema/task edits relative to merge-base with `main-db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] :boolean]
   :cost/ticks 6000000}
  [main-db branch-db]
  (boolean (:has-edits? (diff/branch-latest-edit-info main-db branch-db nil))))

(defn wrapup-grace-notify-op
  "Returns a `:turn-cap-wrapup` message op when a branch hits its turn limit with unmerged edits."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :keyword :any [:sequential :map] [:set :any]] [:maybe :map]]
   :cost/ticks 5000000}
  [main-db branch-db b agent-id head-tx inbox gate-notified-set]
  (when (and (not= b :main) (not (contains? gate-notified-set [agent-id head-tx :wrapup])))
    (let [last-real (reduce #(if (policy/system-bounce-type? (:seon.agent.message/type %2)) %1
                                 (max %1 (long (or (:seon.agent.message/seq %2) 0))))
                            0 inbox)
          sent? (some #(and (= :turn-cap-wrapup (:seon.agent.message/type %))
                            (> (long (or (:seon.agent.message/seq %) 0)) last-real))
                      inbox)]
      (when (and (not sent?) (branch-has-code-edits? main-db branch-db))
        {:op :send-message :from :reconciler :to agent-id :type :turn-cap-wrapup
         :body "Turn limit reached with unmerged edits on branch. Run final test check if needed and reply concisely (no tool calls) to trigger tests & merge."}))))

(defn ^{:seon.code/reexport 'seon.task.review/has-active-reviewer?} has-active-reviewer? [& args] (apply review/has-active-reviewer? args))

(defn ^{:seon.code/reexport 'seon.task.review/compute-review-ops} compute-review-ops [& args] (apply review/compute-review-ops args))

(defn ^:seon.flow/edge ^{:seon.code/reexport 'seon.task.review/evaluate-review-outcome} evaluate-review-outcome [& args] (apply review/evaluate-review-outcome args))

(defn- ^:seon.flow/edge safe-main-head [conn fallback]
  (or (try (store/head conn :main) (catch Throwable _ nil)) fallback))

(defn- assign-tasks-impl [get-ctx raw-args]
  (when-let [{:keys [conn agent-id in out]} (get-ctx)]
    (let [step (fn s [x] (cond (nil? x) nil
                               (and (vector? x) (= :seon.task/id (first x))) [(second x)]
                               (coll? x) (mapcat s x) :else [x]))
          tids (->> raw-args (mapcat step) (filter qualified-keyword?) distinct vec)]
      (if (empty? tids)
        {:ok? false :error "Provide at least one :seon.task/id keyword."}
        (let [bdb @conn mdb (safe-main-head conn bdb)
              pull-t #(when-let [e (eid-of %1 :seon.task/id %2)] (d/pull %1 '[:seon.task/id :seon.task/title :seon.task/scope] e))
              found (mapv #(vector % (or (when-let [e (pull-t bdb %)] [:branch e]) (when-let [e (pull-t mdb %)] [:main e]))) tids)
              missing (vec (keep (fn [[tid f]] (when-not f tid)) found))]
          (if (seq missing)
            {:ok? false :error (str "Task(s) not found: " (pr-str missing)) :missing missing}
            (let [seed-tx (for [[_ [src e]] found :when (= :main src)]
                            (cond-> {:seon.task/id (:seon.task/id e) :seon.task/title (or (:seon.task/title e) (name (:seon.task/id e)))}
                              (:seon.task/scope e) (assoc :seon.task/scope (:seon.task/scope e))))
                  scope (:seon.task/scope (second (second (first found)))) kset (set tids)
                  mk (fn [ts] (cond-> {:seon.agent/id agent-id :seon.agent/tasks (mapv #(vector :seon.task/id %) ts)} scope (assoc :seon.agent/scope scope)))
                  r (d/transact conn {:tx-data (vec (concat seed-tx (stale-retracts bdb agent-id kset) [(mk tids)]))})]
              (when-let [rf (try-resolve 'seon.ctx/refresh-active!)] (rf (:db-after r) (:tx-data r)))
              (when (and in out (eid-of mdb :seon.agent/id agent-id))
                (when-let [sf (try-resolve 'seon.fork.calls/tx-main-sync!)]
                  (when-let [mt (seq (filter #(eid-of mdb :seon.task/id %) tids))]
                    (sf {:op :transact :reason [:assign-tasks agent-id (vec mt)]
                         :tx-data (vec (concat (stale-retracts mdb agent-id kset) [(mk mt)]))}))))
              {:ok? true :agent-id agent-id :task (first tids) :tasks tids :scope scope})))))))

(defn- gate-starter-rules []
  (filterv #(= "rule.gate" (namespace (:seon.rule/id %))) (rseed/starter-rules)))

(defn gate-rules
  "Declarative `:rule.gate/*` rules projected from `resources/seon/rules.edn` (via `seon.rule/load-rules`)."
  {:malli/schema [:function
                  [:=> [:cat] [:vector :map]]
                  [:=> [:cat [:maybe :seon.host/db]] [:vector :map]]]
   :cost/ticks 2000000}
  ([] (gate-rules nil))
  ([db]
   (filterv #(= "rule.gate" (namespace (:seon.rule/id %)))
            (rules/load-rules db (gate-starter-rules)))))

(defn evaluate-agent-gate
  "Evaluates `:rule.gate/*`, review outcome, and refinement proof gates over all assigned tasks."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] :map]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword] [:maybe :map]] :map]]
   :cost/ticks 15000000}
  ([main-db branch-db agent-id] (evaluate-agent-gate main-db branch-db agent-id nil))
  ([main-db branch-db agent-id opts]
   (let [tdbs (tagged-dbs main-db branch-db)
         tids (if (seq (:tasks opts))
                (vec (distinct (:tasks opts)))
                (vec (sort-by str (distinct (mapcat #(agent-ref-tasks (second %) agent-id) tdbs)))))]
     (if (empty? tids)
       {:ok? true :tasks [] :passed-tasks [] :blocked-tasks [] :blockers []}
       (let [aid (or agent-id :gate/anonymous)
             wm0 (seed-gate-wm tdbs aid tids opts)
             rs (or (seq (:rules opts))
                    (if (and branch-db main-db (not (identical? branch-db main-db)))
                      (filterv #(= "rule.gate" (namespace (:seon.rule/id %)))
                               (rules/load-rules branch-db (gate-rules main-db)))
                      (gate-rules (or branch-db main-db))))
             dbs (cond-> {} main-db (assoc :main main-db) (or branch-db main-db) (assoc :branch (or branch-db main-db)))
             wm (:wm (rules/step-rules nil wm0 dbs (vec rs) nil))
             prov (:provenance wm)
             ab (rules/wm-vals wm aid :gate/blocker)
             rev-out (when (some #{:template/review-and-improve :template/tune-context} tids)
                       (evaluate-review-outcome main-db branch-db agent-id))
             tuner-gen-bs (when (and branch-db (some #{:template/tune-context} tids))
             (let [gen-b (when-let [lint (requiring-resolve 'seon.health.coverage/lint-rule-generality)]
                           (let [iss (lint (rules/load-rules branch-db))]
                             (when (seq iss)
                               [{:task :template/tune-context :reason :rule-generality-violation :rule :rule.gate/tuner-generality
                                 :detail {:issues (vec (take 5 iss)) :message (str "Context Tuner introduced non-general hardcoded rule literal(s): " (pr-str (mapv :literal iss)))}}])))
                   corp-b (when (and main-db (not (identical? branch-db main-db))
                                     (contains? (:schema main-db) :seon.rule/id)
                                     (not= (rules/rules-db-sig main-db) (rules/rules-db-sig branch-db)))
                            (when-let [cr (requiring-resolve 'seon.health.corpus/corpus-report)]
                              (let [mc (get-in (cr main-db) [:counts :corpus-passed] 0)
                                    bc-rep (cr branch-db)
                                    bc (get-in bc-rep [:counts :corpus-passed] 0)]
                                (when (< (long bc) (long mc))
                                  (let [failed-ids (vec (keep #(when-not (:pass? %) (:id %)) (:corpus bc-rep)))]
                                    [{:task :template/tune-context :reason :corpus-regression :rule :rule.gate/tuner-corpus
                                      :detail {:main-passed mc :branch-passed bc :failed failed-ids
                                               :message (str "Context Tuner regressed situation corpus (" mc " -> " bc ", failed: " (pr-str failed-ids) ")")}}])))))]
               (vec (concat gen-b corp-b))))
             ref-outs (vec (keep #(refine/evaluate-refinement-gate main-db branch-db %) tids))
             all-bs (concat
                     (mapv #(if-let [rid (:rule (first (get prov [(:task %) :gate/blocker %])))] (assoc % :rule rid) %) ab)
                     (:blockers rev-out)
                     tuner-gen-bs
                     (mapcat :blockers ref-outs))
             bset (into #{} (keep :task) all-bs)
             blockers (->> all-bs
                           (sort-by #(vector (str (:task %)) (str (:reason %)) (pr-str (:detail %))))
                           vec)]
         (cond-> {:ok? (empty? bset) :tasks tids
                  :passed-tasks (filterv #(not (contains? bset %)) tids)
                  :blocked-tasks (filterv #(contains? bset %) tids)
                  :blockers blockers}
           rev-out (assoc :review-outcome rev-out)
           (seq ref-outs) (assoc :refinement-outcomes ref-outs)))))))

(defn- gate-status-impl [get-ctx target-aid opts]
  (when-let [{:keys [conn branch agent-id]} (get-ctx)]
    (let [bdb @conn mdb (if (= branch :main) bdb (safe-main-head conn bdb))]
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

(defn ^{:seon.code/reexport 'seon.task.review/review-cutoff-ops} review-cutoff-ops [& args] (apply review/review-cutoff-ops args))