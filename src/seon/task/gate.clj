(ns seon.task.gate
  "Multi-task assignment (`:seon.agent/tasks`) and declarative test gating (`:rule.gate/*`)
   over both legacy `:seon.task/tests` and true `:db.type/ref` `:seon.task/test-vars`."
  (:require [datahike.api :as d]
            [seon.rule :as rules]
            [seon.store :as store]
            [seon.rule.seed :as rseed]
            [clojure.string :as str]
            [seon.reconciler.policy :as policy]))

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
    (vec (distinct (agent-ref-tasks db agent-id)))
    []))

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
  "Returns sorted set of required test var symbols (`:seon.task/tests` + `:seon.task/test-vars`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :qualified-keyword]] [:set :symbol]]
   :cost/ticks 500000}
  [db task-id]
  (if-let [teid (eid-of db :seon.task/id task-id)]
    (let [leg (when (get-in db [:schema :seon.task/tests])
                (keep #(when (symbol? (:v %)) (:v %)) (d/datoms db :eavt teid :seon.task/tests)))
          refs (when (get-in db [:schema :seon.task/test-vars])
                 (keep #(let [ve (:v %) vn (eavt-val db ve :seon.code.var/name)]
                          (cond (symbol? vn) vn (integer? ve) (symbol "dangling-ref" (str "eid-" ve))))
                       (d/datoms db :eavt teid :seon.task/test-vars)))]
      (into (sorted-set) (concat leg refs)))
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
      (:review-hijacked-ready :review-no-op) (str "- " task ": " (or (:message detail) (name reason)))
      (str "- " task ": " (name (or reason :blocked)) " " (pr-str detail)))))

(defn gate-blocked-notify-op
  "Returns a `:gate-blocked` message op when `status` is `:gate-blocked` with fixable test blockers not yet notified."
  {:malli/schema [:=> [:cat :keyword :map :keyword :any [:set :any]] [:maybe :map]]
   :cost/ticks 500000}
  [status gate agent-id head-tx gate-notified-set]
  (when (and (= status :gate-blocked)
             (not (contains? gate-notified-set [agent-id head-tx])))
    (let [fixable (filterv #(contains? #{:test-missing :test-failing :review-hijacked-ready :review-no-op} (:reason %))
                  (:blockers gate))]
      (when (seq fixable)
        {:op :send-message :from :reconciler :to agent-id :type :gate-blocked
         :body (str "Merge gate blocked at branch tx " head-tx
                    " — fix these required proof blocker(s) and call (user/ready!) again:\n"
                    (str/join "\n" (map format-gate-blocker fixable)))}))))

(defn ^:seon.flow/edge branch-has-code-edits?
  "True when `branch-db` contains code, doc, or schema edits relative to its merge-base with `main-db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] :boolean]
   :cost/ticks 5000000}
  [main-db branch-db]
  (boolean
   (when (and main-db branch-db (not (identical? main-db branch-db)))
     (try
       (let [mc (d/commit-id main-db) bc (d/commit-id branch-db)]
         (when (and mc bc (not= mc bc))
           (let [base (or (d/merge-base branch-db mc bc) mc)]
             (when (not= base bc)
               (let [kws [:seon.code.form/key :seon.code.form/src :seon.code.form/hash
                          :seon.code.var/name :seon.code.ns/name :seon.code.doc/id
                          :seon.code.file/path :seon.schema/key]
                     attrs (into (set kws) (keep #(get-in branch-db [:ident-ref-map %])) kws)]
                 (boolean (some #(contains? attrs (:a %)) (d/changes branch-db base bc))))))))
       (catch Throwable _ false)))))

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
         :body "Turn limit reached with unmerged edits on branch. Run final test check if needed and call (user/ready!) now."}))))

(defn has-active-reviewer?
  "True when `agent-eid` has an unarchived, unpaused, unmerged reviewer pointing at it via `:seon.agent/forked-from` or `:seon.agent/target`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :int]] :boolean]
   :cost/ticks 500000}
  [main-db agent-eid]
  (boolean
   (and (integer? agent-eid)
        (let [dh (or (:schema main-db) {})
              dts (concat (when (contains? dh :seon.agent/forked-from) (d/datoms main-db :avet :seon.agent/forked-from agent-eid))
                          (when (contains? dh :seon.agent/target) (d/datoms main-db :avet :seon.agent/target agent-eid)))]
          (some (fn [dt]
                  (let [ra (d/pull main-db (quote [:seon.agent/archived? :seon.agent/paused? :seon.agent/merged-tx]) (:e dt))]
                    (and (not (:seon.agent/archived? ra))
                         (not (:seon.agent/paused? ra))
                         (nil? (:seon.agent/merged-tx ra)))))
                dts)))))

(defn compute-review-ops
  "Pure rule-driven review trigger & target auto-resume ops derived from `:seon.rule/*` entities asserting `:agent/needs-review` and `:seon.agent/target`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map :map] [:vector :map]]
   :cost/ticks 15000000}
  [main-db branch-db ma {:keys [agent-eid active? raw-ready? waiting? merged? tool-streak head-tx]}]
  (let [aid (:seon.agent/id ma)
        b (:seon.agent/branch ma :main)
        target (:seon.agent/target ma)
        dh (or (:schema main-db) {})]
    (cond
      ;; 1. If a reviewer finished/merged and has not yet sent a :review-note to its paused target, atomically sync :main, unpause, and deliver :review-note!
      (and merged? target (nil? (:seon.agent/forked-from ma)) (contains? dh :seon.agent/id))
      (let [teid (if (map? target) (:db/id target) target)
            ta (when (integer? teid) (d/pull main-db '[:seon.agent/id :seon.agent/branch :seon.agent/paused?] teid))
            taid (:seon.agent/id ta)
            already-resumed? (boolean
                               (and taid (contains? dh :seon.agent.message/to)
                                    (some (fn [dt]
                                            (let [m (d/pull main-db '[:seon.agent.message/from :seon.agent.message/type] (:e dt))]
                                              (and (= aid (:seon.agent.message/from m))
                                                   (= :review-note (:seon.agent.message/type m)))))
                                          (d/datoms main-db :avet :seon.agent.message/to taid))))]
        (if (and taid (:seon.agent/paused? ta) (not already-resumed?))
          [{:op :resume-reviewed-target
            :reviewer aid
            :reviewer-branch b
            :target taid
            :target-branch (:seon.agent/branch ta :main)}]
          []))

      ;; 2. For active non-main worker agents with :seon.config/review-every-turns > 0 (and not already under review), evaluate :agent/needs-review rules!
      (and active? (not= b :main) (not raw-ready?) (not waiting?) (and (nil? target) (nil? (:seon.agent/forked-from ma)))
           (contains? dh :seon.config/review-every-turns)
           (pos? (long (or (seon.store/config-val main-db aid :seon.config/review-every-turns) 0))))
      (let [rev-every (long (seon.store/config-val main-db aid :seon.config/review-every-turns))]
        (if (has-active-reviewer? main-db agent-eid)
          []
          (let [all-rs (rules/load-rules (or branch-db main-db) (rseed/starter-rules))
                rev-rs (filterv (fn [r]
                                  (and (:seon.rule/enabled? r true)
                                       (some #(and (vector? %) (= :agent/needs-review (second %))) (:seon.rule/assert r))))
                                all-rs)
                wm (when (seq rev-rs)
                     (:wm (rules/step-rules nil {:main main-db :branch branch-db :code branch-db :task main-db}
                                            {:agent-id aid} rev-rs nil :wire)))
                rule-reasons (when wm (vec (sort-by pr-str (rules/wm-vals wm aid :agent/needs-review))))
                reasons (cond-> (vec rule-reasons)
                          (and (empty? rule-reasons) (>= (long (or tool-streak 0)) rev-every))
                          (conj [:periodic-review (long tool-streak)]))]
            (if (seq reasons)
              (let [rid (keyword "review" (str (name aid) "-t" (long (or head-tx 0))))]
              [{:op :transact :tx-data [{:seon.agent/id aid :seon.agent/paused? true}] :reason [:review-pause aid]}
               {:op :spawn-agent :id rid :parent aid :forked-from aid
               :merge-target [:seon.agent/id aid] :tasks [:template/review-and-improve] :auto-merge? true
               :config {:seon.config/max-tool-turns 6}
               :goal (str "Review " aid " (triggered by " (pr-str reasons) ")")}] )
              []))))

      :else [])))

(defn ^:seon.flow/edge evaluate-review-outcome
  "Verifies a forked review agent's Datahike mutations since `:seon.agent/fork-commit`:
   records observable `:actions` (`:compacted-evals`, `:cleared-errors`, `:curated-context`,
   `:improved-system`, `:wrote-review-summary`), blocks target `:ready?` hijacking
   (`:review-hijacked-ready`), and blocks empty no-op reviews (`:review-no-op`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] [:maybe :map]]
   :cost/ticks 10000000}
  [main-db branch-db agent-id]
  (let [mdb (or main-db branch-db)
        bdb (or branch-db main-db)]
    (when (and mdb bdb agent-id)
      (let [ma (when (eid-of mdb :seon.agent/id agent-id)
                 (d/pull mdb '[:seon.agent/id :seon.agent/forked-from :seon.agent/target
                               :seon.agent/fork-commit :seon.agent/summary] [:seon.agent/id agent-id]))
            ba (when (eid-of bdb :seon.agent/id agent-id)
                 (d/pull bdb '[:seon.agent/id :seon.agent/forked-from :seon.agent/target
                               :seon.agent/fork-commit :seon.agent/summary] [:seon.agent/id agent-id]))
            tref (or (:seon.agent/forked-from ma) (:seon.agent/target ma)
                     (:seon.agent/forked-from ba) (:seon.agent/target ba))
            teid (cond (integer? tref) tref (map? tref) (:db/id tref) :else nil)]
        (when (integer? teid)
          (let [taid (or (eavt-val mdb teid :seon.agent/id) (eavt-val bdb teid :seon.agent/id))
                b-teid (or (when taid (eid-of bdb :seon.agent/id taid)) teid)
                self-beid (eid-of bdb :seon.agent/id agent-id)
                bc (when (instance? datahike.db.DB bdb) (d/commit-id bdb))
                mc (when (instance? datahike.db.DB mdb) (d/commit-id mdb))
                fc (or (:seon.agent/fork-commit ma) (:seon.agent/fork-commit ba))
                base (cond
                       (and fc bc (not= fc bc)) fc
                       (and mc bc (not= mc bc)) (try (d/merge-base bdb mc bc) (catch Throwable _ nil))
                       :else nil)
                raw-ch (if (and base bc (not= base bc))
                         (try (vec (d/changes bdb base bc)) (catch Throwable _ []))
                         [])
                res-a (fn [a] (if (keyword? a) a (or (get-in bdb [:ref-ident-map a]) a)))
                curate-attrs #{:seon.agent/summary :seon.agent/pinned :seon.agent/curated
                               :seon.agent/context-prefs :seon.task/plan :seon.task/summary}
                improve-attrs #{:seon.code.form/src :seon.code.form/hash :seon.code.doc/body
                                :seon.rule/query :seon.rule/assert :seon.schema/form :seon.fix/id}
                {:keys [compacted? cleared-err? curated? improved? hijacked?]}
                (reduce (fn [acc dt]
                          (let [a (res-a (:a dt))
                                e (:e dt)
                                v (:v dt)
                                added? (boolean (:added dt))]
                            (cond-> acc
                              (and (not added?) (= a :seon.agent.eval/agent) (= v taid))
                              (assoc :compacted? true)

                              (or (and (not added?) (= a :seon.error/id))
                                  (and added? (= a :seon.error/status) (= v :resolved)))
                              (assoc :cleared-err? true)

                              (and added? (contains? curate-attrs a)
                                   (or (= e b-teid) (= e teid) (not= e self-beid)))
                              (assoc :curated? true)

                              (and added? (contains? improve-attrs a))
                              (assoc :improved? true)

                              (and added? (= a :seon.agent/ready?) (true? v)
                                   (or (= e b-teid) (= e teid)))
                              (assoc :hijacked? true))))
                        {:compacted? false :cleared-err? false :curated? false :improved? false :hijacked? false}
                        raw-ch)
                rev-summary (or (:seon.agent/summary ba) (:seon.agent/summary ma))
                wrote-sum? (boolean (and (string? rev-summary) (not (str/blank? rev-summary))))
                actions (cond-> #{}
                          compacted? (conj :compacted-evals)
                          cleared-err? (conj :cleared-errors)
                          curated? (conj :curated-context)
                          improved? (conj :improved-system)
                          wrote-sum? (conj :wrote-review-summary))
                blockers (cond-> []
                           hijacked?
                           (conj {:task :template/review-and-improve
                                  :reason :review-hijacked-ready
                                  :rule :rule.gate/review-outcome
                                  :detail {:target taid
                                           :message (str "Reviewer must not set :seon.agent/ready? true on target " taid ". Retract it and return via (user/finish-review! summary).")}})
                           (empty? actions)
                           (conj {:task :template/review-and-improve
                                  :reason :review-no-op
                                  :rule :rule.gate/review-outcome
                                  :detail {:target taid
                                           :message "Review made zero observable cleanups, error clears, context curation, system fixes, or summary. Clean up or call (user/finish-review! summary)."}}))]
            {:ok? (empty? blockers)
             :target taid
             :actions actions
             :hijacked-target-ready? hijacked?
             :blockers blockers}))))))

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
  "Evaluates `:rule.gate/*` via `seon.rule/step-rules` over all assigned tasks."
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
             rev-out (when (some #{:template/review-and-improve} tids)
                       (evaluate-review-outcome main-db branch-db agent-id))
             all-bs (concat
                     (mapv #(if-let [rid (:rule (first (get prov [(:task %) :gate/blocker %])))] (assoc % :rule rid) %) ab)
                     (:blockers rev-out))
             bset (into #{} (keep :task) all-bs)
             blockers (->> all-bs
                           (sort-by #(vector (str (:task %)) (str (:reason %)) (pr-str (:detail %))))
                           vec)]
         (cond-> {:ok? (empty? bset) :tasks tids
                  :passed-tasks (filterv #(not (contains? bset %)) tids)
                  :blocked-tasks (filterv #(contains? bset %) tids)
                  :blockers blockers}
           rev-out (assoc :review-outcome rev-out)))))))

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

(defn- resolve-review-target-eid [main-db ma]
  (let [resolve-ref (fn [r]
                      (cond
                        (integer? r) r
                        (and (map? r) (integer? (:db/id r))) (:db/id r)
                        (and (vector? r) (= :seon.agent/id (first r))) (eid-of main-db :seon.agent/id (second r))
                        (keyword? r) (or (eid-of main-db :seon.agent/id r)
                                         (when (get-in main-db [:schema :seon.agent/branch])
                                           (some #(when (= r (:v %)) (:e %))
                                                 (d/datoms main-db :aevt :seon.agent/branch))))
                        :else nil))]
    (some resolve-ref [(:seon.agent/merge-target ma)
                       (:seon.agent/forked-from ma)
                       (:seon.agent/target ma)
                       (:seon.agent/parent ma)])))

(defn review-cutoff-ops
  "When a bounded `:seon.agent/merge-target` reviewer aborts (`err-halted?`, bounce limit, total-turn limit,
   or unmergeable cutoff), atomically pauses the reviewer and unpauses the target parent with a `:review-note`."
  {:malli/schema [:=> [:cat :seon.host/db :map :map] [:vector :map]]
   :cost/ticks 2000000}
  [main-db ma {:keys [cutoff-reached? review-abort? err-halted? bounce-streak turns-since-external
                      gate-ok? blockers inbox unread last-error]}]
  (let [aid (:seon.agent/id ma)
        teid (resolve-review-target-eid main-db ma)
        msgs (if (seq inbox) inbox unread)
        trailing-bounces (vec (take-while #(policy/system-bounce-type? (:seon.agent.message/type %))
                                          (reverse msgs)))
        fail-msg (some #(when (contains? policy/failure-bounce-types (:seon.agent.message/type %)) %)
                       trailing-bounces)
        trigger? (or review-abort?
                     (and cutoff-reached? (or (not gate-ok?) (some? fail-msg) (pos? (long (or bounce-streak 0))))))]
    (if-not (and aid trigger?)
      []
      (let [ta (when (integer? teid) (d/pull main-db (quote [:seon.agent/id :seon.agent/paused?]) teid))
            taid (:seon.agent/id ta)
            reason-txt (cond
                         err-halted? (str "model error halt: " (or (not-empty (str last-error)) "non-retryable or repeated model error"))
                         fail-msg (str (name (:seon.agent.message/type fail-msg)) ": "
                                       (first (str/split-lines (str (or (:seon.agent.message/body fail-msg) "")))))
                         (seq blockers) (str/join "; " (map #(str (name (or (:reason %) :blocked))) blockers))
                         (pos? (long (or bounce-streak 0))) (str "repeated system bounce limit (" bounce-streak ")")
                         (pos? (long (or turns-since-external 0))) (str "turn budget exhausted (" turns-since-external " turns)")
                         :else "turn cutoff reached without mergeable proof")
            note (str "Review " aid " stopped and skipped merging (" reason-txt
                      "). Resuming your branch intact without unverified edits.")]
        (cond-> [{:op :transact
                  :tx-data (cond-> [{:seon.agent/id aid :seon.agent/paused? true
                                     :seon.agent/summary (str "Review aborted; skipped merge (" reason-txt ").")}]
                             (and taid (:seon.agent/paused? ta))
                             (conj {:seon.agent/id taid :seon.agent/paused? false}))
                  :reason [:review-cutoff-release aid taid]}]
          taid (conj {:op :send-message :from aid :to taid :type :review-note :body note}))))))