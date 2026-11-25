(ns seon.task.review
  "Extracted child namespace of `seon.task.gate`."
  (:require [datahike.api :as d]
            [seon.rule :as rules]
            [seon.store :as store]
            [seon.rule.seed :as rseed]
            [clojure.string :as str]
            [seon.reconciler.policy :as policy]
            [seon.ctx.diff :as diff]
            [seon.task.refine :as refine]))

(defn eid-of
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :keyword :any] :any]}
  [db a v]
  (when (and db (some? v) (get-in db [:schema a]))
    (:e (first (d/datoms db :avet a v)))))

(defn eavt-val
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any :keyword] :any]}
  [db e a]
  (when (and db e (get-in db [:schema a]))
    (:v (first (d/datoms db :eavt e a)))))

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
                     (and fc bc (not= fc bc) (diff/ancestor-within? bdb fc bc 256)) fc
                     (and fc bc (not= fc bc)) (try (d/merge-base bdb fc bc) (catch Throwable _ nil))
                     (and mc bc (not= mc bc)) (diff/fast-merge-base mdb bdb)
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
                                           :message (str "Reviewer must not set :seon.agent/ready? true on target " taid ". Retract it and reply concisely in Markdown to merge back.")}})
                           (empty? actions)
                           (conj {:task :template/review-and-improve
                                  :reason :review-no-op
                                  :rule :rule.gate/review-outcome
                                  :detail {:target taid
                                           :message "Review made zero observable cleanups, error clears, context curation, system fixes, or summary. Clean up or reply concisely with your summary."}}))]
            {:ok? (empty? blockers)
             :target taid
             :actions actions
             :hijacked-target-ready? hijacked?
             :blockers blockers}))))))

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
                  :tx-data (into (cond-> [{:seon.agent/id aid :seon.agent/paused? true
                                     :seon.agent/summary (str "Review aborted; skipped merge (" reason-txt ").")}]
                             (and taid (:seon.agent/paused? ta))
                             (conj {:seon.agent/id taid :seon.agent/paused? false}))
                           (refine/aborted-refiner-cleanup-tx main-db ma reason-txt))
                  :reason [:review-cutoff-release aid taid]}]
          taid (conj {:op :send-message :from aid :to taid :type :review-note :body note}))))))
