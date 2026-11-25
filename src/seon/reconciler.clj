(ns seon.reconciler
  "The `:reconciler` Flow proc (`:compute` workload):
   - 100% pure state (no I/O, no atoms, no stored `:status` in Datahike).
   - Incremental O(|Delta|) evaluation over `:seon.agent/*` & `:seon.agent.message/*`.
   - Distinguishes `:busy` (in-turn) from `:idle` (warm OS process kept across turns),
     releasing the OS process cleanly once `:merged`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store]
            [seon.task.gate :as gate]
            [seon.reconciler.policy :as policy]
            [seon.task.refine :as refine]))

(defn- latest-prose-after-msg [eval-ents msg-id]
  (when msg-id
    (let [after (drop-while #(not (contains? (:seon.agent.eval/seen-messages %) msg-id)) eval-ents)
          prose (->> after
                     (filter #(and (str/blank? (str (:seon.agent.eval/code %)))
                                   (not (str/blank? (str (:seon.agent.eval/thought %))))))
                     (map :seon.agent.eval/thought)
                     last)]
      prose)))

(defn agent-gate-summary
  "Evaluates declarative `:rule.gate/*` proof gating for `agent-id` across `main-db` and `branch-db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] :map]
   :cost/ticks 15000000}
  [main-db branch-db agent-id]
  (gate/evaluate-agent-gate main-db branch-db agent-id))

(defn- ^:seon.flow/edge safe-commit-changes [conn prev-cid cid]
  (try
    (d/changes conn prev-cid cid)
    (catch Throwable _ nil)))

(defn- touched-agent-ids
  ([conn main-db prev-cid cid] (touched-agent-ids conn main-db prev-cid cid nil))
  ([conn main-db prev-cid cid tx-data]
   (let [ds (cond (seq tx-data) tx-data
                  (or (nil? prev-cid) (= prev-cid cid)) nil
                  :else (safe-commit-changes conn prev-cid cid))
         all-aids #(into #{} (map :v) (d/datoms main-db :aevt :seon.agent/id))
         attrs-of (fn [ks] (into (set ks) (keep #(get-in main-db [:ident-ref-map %])) ks))]
     (if (nil? ds)
       (all-aids)
       (let [gate-attrs (attrs-of [:seon.task/done? :seon.task/needs :seon.task/tests :seon.task/test-vars :seon.test/pass?])]
         (if (some #(contains? gate-attrs (:a %)) ds)
           (all-aids)
           (let [direct-attrs (attrs-of [:seon.agent/id :seon.agent.message/to :seon.agent.call/caller :seon.agent.call/callee])
                 call-attrs (attrs-of [:seon.agent.call/status :seon.agent.call/delivered?])]
             (reduce
              (fn [acc dt]
                (let [a (:a dt)]
                  (cond
                    (contains? direct-attrs a) (conj acc (:v dt))
                    (contains? call-attrs a)
                    (let [c (d/pull main-db (quote [:seon.agent.call/caller :seon.agent.call/callee]) (:e dt))]
                      (cond-> acc
                        (:seon.agent.call/caller c) (conj (:seon.agent.call/caller c))
                        (:seon.agent.call/callee c) (conj (:seon.agent.call/callee c))))
                    :else
                    (if-let [id (:v (first (d/datoms main-db :eavt (:e dt) :seon.agent/id)))]
                      (conj acc id)
                      acc))))
              #{}
              ds))))))))

(defn- needs-post-ready-reply? [raw-ready? waiting? last-was-tool? under-turn-cap? ready-tx seen inbox eval-ents has-target?]
  (boolean
   (when (and raw-ready? (not waiting?) last-was-tool? under-turn-cap?)
     (when-let [last-prompt (last (filter #(or (and (contains? #{:user :user/default} (:seon.agent.message/from %))
                                                    (= :chat (:seon.agent.message/type %)))
                                               (and has-target? (= :delegate (:seon.agent.message/type %))))
                                          inbox))]
       (let [mid (:seon.agent.message/id last-prompt)
             post-turns (into #{}
                              (keep (fn [ev]
                                      (when (> (long (or (:tx ev) 0)) (long ready-tx))
                                        (:seon.agent.eval/turn ev))))
                              eval-ents)]
         (and (contains? seen mid)
              (nil? (latest-prose-after-msg eval-ents mid))
              (<= (count post-turns) 1)))))))

(defn- msg-recipients-by-type [main-db ds target-types]
  (if (empty? ds)
    #{}
    (let [type-attr (get-in main-db [:ident-ref-map :seon.agent.message/type])]
      (reduce (fn [acc dt]
                (if (and (:added dt)
                         (or (= (:a dt) :seon.agent.message/type) (= (:a dt) type-attr))
                         (contains? target-types (:v dt)))
                  (if-let [to (:v (first (d/datoms main-db :eavt (:e dt) :seon.agent.message/to)))]
                    (conj acc to)
                    acc)
                  acc))
              #{}
              ds))))

(defn- ^:seon.flow/edge safe-branch-head [store main-db b]
  (if (or (nil? b) (= b :main))
    main-db
    (or (try (store/head store b) (catch Throwable _ nil)) main-db)))

(def ^:private call-pull-pat
  (quote [:seon.agent.call/id :seon.agent.call/caller :seon.agent.call/callee
          :seon.agent.call/message-id :seon.agent.call/result-schema
          :seon.agent.call/status :seon.agent.call/delivered?]))

(defn- pull-call-by-id [db cid]
  (when (and db cid (contains? (:schema db) :seon.agent.call/id)
             (first (d/datoms db :avet :seon.agent.call/id cid)))
    (d/pull db call-pull-pat [:seon.agent.call/id cid])))

(defn- prune-reconciler-sets [st main-db store ds]
  (let [{:keys [testing merging gate-notified settling]} st]
    (if (and (empty? testing) (empty? merging) (empty? gate-notified) (empty? settling))
      st
      (let [fail-aids (msg-recipients-by-type main-db ds policy/failure-bounce-types)
            aids (into #{} (map first) (concat testing merging gate-notified))
            info (into {}
                       (keep (fn [aid]
                               (when-let [e (:e (first (d/datoms main-db :avet :seon.agent/id aid)))]
                                 (let [ma (d/pull main-db (quote [:seon.agent/branch :seon.agent/tested-tx :seon.agent/merged-tx]) e)
                                       bdb (safe-branch-head store main-db (:seon.agent/branch ma))]
                                   [aid {:head-tx (:max-tx bdb)
                                         :tested-tx (:seon.agent/tested-tx ma)
                                         :merged-tx (:seon.agent/merged-tx ma)}]))))
                       aids)
            keep-by (fn [s pred]
                      (into #{} (filter (fn [[aid tx :as entry]]
                                          (when-let [i (get info aid)]
                                            (and (= (:head-tx i) tx) (pred aid tx i entry)))))
                            s))
            keep-settling (into #{}
                                (filter (fn [[kind cid]]
                                          (when-let [c (pull-call-by-id main-db cid)]
                                            (case kind
                                              :deliver (not (:seon.agent.call/delivered? c))
                                              (:incoming :nudge) (= :pending (:seon.agent.call/status c))
                                              false))))
                                settling)]
        (assoc st
               :testing (keep-by testing (fn [aid tx i _] (and (not= (:merged-tx i) tx) (not= (:tested-tx i) tx) (not (contains? fail-aids aid)))))
               :merging (keep-by merging (fn [aid tx i _] (and (not= (:merged-tx i) tx) (not (contains? fail-aids aid)))))
               :gate-notified (keep-by gate-notified (fn [_ tx i entry] (or (= :review (nth entry 2 nil)) (not= (:merged-tx i) tx))))
               :settling keep-settling)))))

(defn- pull-calls-by-attr [db attr val]
  (if (and db (contains? (:schema db) attr))
    (mapv #(d/pull db call-pull-pat (:e %)) (d/datoms db :avet attr val))
    []))

(defn- compute-auto-settle-ops
  [main-db agent-id b head-tx status eval-count eval-ents last-eval seen inbox settling-set has-pending-outgoing? under-review?]
  (let [incoming-pending (filterv #(= :pending (:seon.agent.call/status %))
                                  (pull-calls-by-attr main-db :seon.agent.call/callee agent-id))]
    (when (and (seq incoming-pending)
               (not has-pending-outgoing?)
               (contains? #{:paused :waiting :merged} status))
      (vec
       (keep (fn [c]
               (let [cid (:seon.agent.call/id c)
                     mid (:seon.agent.call/message-id c)
                     rsch (:seon.agent.call/result-schema c)]
                 (when-not (contains? settling-set [:incoming cid])
                   (cond
                     (= status :paused)
                     (when-not under-review?
                       {:op :fail-call :call-id cid
                        :error {:class :callee-paused :callee agent-id :message (str "Callee " agent-id " is paused")}})

                     (and (= status :merged) mid (contains? seen mid) (nil? rsch))
                     {:op :answer-call :call-id cid :result {:merged? true :branch b :merged-tx head-tx}}

                     (and mid (contains? seen mid))
                     (let [prose (latest-prose-after-msg eval-ents mid)
                           nudge-tag (str "[call:" cid "]")
                           nudged? (boolean (some #(and (= :protocol-nudge (:seon.agent.message/type %))
                                                        (str/includes? (str (:seon.agent.message/body %)) nudge-tag))
                                                  inbox))]
                       (cond
                         (and prose (nil? rsch))
                         {:op :answer-call :call-id cid :result {:answer prose :implicit-prose? true}}

                         (and (not nudged?) (not (contains? settling-set [:nudge cid])))
                         {:op :send-message :from :system :to agent-id :type :protocol-nudge :call-id cid
                          :body (str "Protocol reminder " nudge-tag ": open call " (pr-str cid)
                                     " is still pending and requires evaluating (user/answer! " (pr-str cid) " <value>)"
                                     (when rsch (str " matching Malli schema " (pr-str rsch)))
                                     " (or (user/fail! " (pr-str cid) " \"reason\")). "
                                     "Do not print the answer in prose alone — call (user/answer! ...) now.")}

                         :else
                         {:op :fail-call :call-id cid
                          :error (cond-> {:class (if rsch :schema-mismatch :callee-stalled)
                                          :callee agent-id :evals eval-count :nudged? nudged?
                                          :message (if rsch
                                                     (str "Callee stopped without calling (user/answer! call-id value) matching " rsch)
                                                     (str "Callee " agent-id " stopped after " eval-count " eval(s) without answering call"))}
                                   (:seon.agent.eval/error last-eval) (assoc :last-error (:seon.agent.eval/error last-eval))
                                   prose (assoc :prose prose))}))))))
             incoming-pending)))))

(defn- call-delivery-state [main-db agent-id ma settling-set]
  (let [awaiting-ids (set (:seon.agent/awaiting ma))
        awaiting-calls (vec (keep #(pull-call-by-id main-db %) awaiting-ids))
        pending-awaiting (filterv #(= :pending (:seon.agent.call/status %)) awaiting-calls)
        outgoing-calls (pull-calls-by-attr main-db :seon.agent.call/caller agent-id)]
    {:awaiting-count (count pending-awaiting)
     :awaiting-blocked? (boolean (seq pending-awaiting))
     :has-pending-outgoing? (boolean (some #(= :pending (:seon.agent.call/status %)) outgoing-calls))
     :awaiting-ready-to-deliver
     (when (and (seq awaiting-calls) (empty? pending-awaiting))
       (vec (keep (fn [c]
                    (let [cid (:seon.agent.call/id c)]
                      (when (and (not (:seon.agent.call/delivered? c))
                                 (not (contains? settling-set [:deliver cid])))
                        cid)))
                  awaiting-calls)))
     :async-ready-to-deliver
     (vec (keep (fn [c]
                  (let [cid (:seon.agent.call/id c)]
                    (when (and (not (contains? awaiting-ids cid))
                               (not= :pending (:seon.agent.call/status c))
                               (not (:seon.agent.call/delivered? c))
                               (not (contains? settling-set [:deliver cid])))
                      cid)))
                outgoing-calls))}))

(defn- live-release-state [live-entry cur-eval-eids paused? merged?]
  (let [live-pid (if (map? live-entry) (:pid live-entry) live-entry)
        busy? (boolean (if (map? live-entry) (:busy? live-entry) live-entry))
        prev-eids (when (map? live-entry) (:eval-eids live-entry))
        retracted? (boolean (and (not busy?) (some? live-pid) (seq prev-eids)
                                 (some #(not (contains? cur-eval-eids %)) prev-eids)))]
    {:live-pid live-pid :busy? busy?
     :needs-release? (boolean (and (some? live-pid) (or merged? (and (not paused?) retracted?))))}))

(defn- pull-agent-inbox-and-evals [main-db branch-db agent-id]
  (let [seen (store/agent-seen-message-ids branch-db agent-id)
        inbox (->> (d/datoms main-db :avet :seon.agent.message/to agent-id)
                   (map #(assoc (d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq :seon.agent.message/from :seon.agent.message/type :seon.agent.message/body] (:e %))
                                :tx (:tx %) :tx-inst (store/tx-instant-ms main-db (:tx %))))
                   (sort-by :seon.agent.message/seq) vec)
        unread (filterv #(not (contains? seen (:seon.agent.message/id %))) inbox)
        eval-ents (->> (d/datoms branch-db :avet :seon.agent.eval/agent agent-id)
                       (map #(assoc (d/pull branch-db '[:seon.agent.eval/turn :seon.agent.eval/call-index :seon.agent.eval/call-id :seon.agent.eval/code :seon.agent.eval/thought :seon.agent.eval/error :seon.agent.eval/seen-messages] (:e %))
                                    :db/id (:e %) :tx (:tx %) :tx-inst (store/tx-instant-ms branch-db (:tx %))))
                       (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0))) vec)]
    {:seen seen :inbox inbox :unread unread :eval-ents eval-ents}))

(defn- config-long [main-db agent-id k dflt]
  (let [v (when (contains? (:schema main-db) k) (store/config-val main-db agent-id k))]
    (if (number? v) (long v) (long dflt))))

(defn- build-policy-input
  [main-db branch-db ma ba ready-dt unread act live-entry awaiting-blocked? gate-ok? testing-set merging-set gate-notified-set seen inbox eval-ents]
  (let [aid (:seon.agent/id ma) b (:seon.agent/branch ma) htx (:max-tx branch-db)
        same? (or (identical? main-db branch-db) (= b :main) (nil? b))
        rtx (or (:tx ready-dt) 0) rr? (boolean (:seon.agent/ready? ba)) w? (boolean (:seon.agent/waiting? ba))
        mtt (config-long main-db aid :seon.config/max-tool-turns policy/default-max-tool-turns)
        mb (config-long main-db aid :seon.config/max-bounces policy/default-max-bounces)
        mtot (config-long main-db aid :seon.config/max-total-turns (if (pos? mtt) (+ mtt 8) policy/default-max-total-turns))
        ext (filterv #(not (policy/system-bounce-type? (:seon.agent.message/type %))) unread)
        einst (keep #(when (integer? (:tx-inst %)) (long (:tx-inst %))) ext)
        ed (when (and (not same?) (not rr?) (:last-was-prose? act) (empty? unread))
             (gate/branch-latest-edit-info main-db branch-db ma))]
    {:branch b :paused? (boolean (:seon.agent/paused? ma))
     :busy? (boolean (if (map? live-entry) (:busy? live-entry) live-entry))
     :awaiting-blocked? awaiting-blocked? :waiting? w? :raw-ready? rr?
     :ready-tx rtx :ready-inst (store/tx-instant-ms branch-db (:tx ready-dt)) :tested-tx (:seon.agent/tested-tx ma)
     :merged-tx (:seon.agent/merged-tx ma) :head-tx htx :same-db? same?
     :auto-merge? (if (some? (:seon.agent/auto-merge? ma)) (boolean (:seon.agent/auto-merge? ma)) (boolean (store/config-val main-db aid :seon.config/auto-merge?)))
     :gate-ok? gate-ok? :has-merge-target? (some? (:seon.agent/merge-target ma))
     :in-testing? (contains? testing-set [aid htx]) :in-merging? (contains? merging-set [aid htx])
     :review-notified? (contains? gate-notified-set [aid htx :review])
     :has-unread? (boolean (seq unread)) :has-external-unread? (boolean (seq ext))
     :has-unread-failure? (boolean (some #(contains? policy/failure-bounce-types (:seon.agent.message/type %)) unread))
     :newest-external-unread-tx (when (seq ext) (reduce max 0 (keep #(when (integer? (:tx %)) (long (:tx %))) ext)))
     :newest-external-unread-inst (when (seq einst) (reduce max einst))
     :latest-failure-tx (:latest-failure-tx act) :latest-failure-inst (:latest-failure-inst act)
     :latest-block-tx (:latest-block-tx act) :latest-block-inst (:latest-block-inst act)
     :has-branch-edits? (boolean (:has-edits? ed)) :last-edit-tx (:last-edit-tx ed) :last-edit-inst (:last-edit-inst ed)
     :last-was-prose? (boolean (:last-was-prose? act))
     :post-ready-reply? (needs-post-ready-reply? rr? w? (:last-was-tool? act) (or (<= mtt 0) (< (:tool-streak act) mtt)) rtx seen inbox eval-ents
                          (and (str/blank? (str (:seon.agent/summary ba))) (boolean (or (:seon.agent/target ma) (:seon.agent/forked-from ma)))))
     :has-pending-review-ops? false :last-was-tool? (:last-was-tool? act)
     :tool-streak (:tool-streak act) :turns-since-external (:turns-since-external act)
     :bounce-streak (:bounce-streak act) :trailing-model-errors (:trailing-model-errors act)
     :last-err-tx (:last-err-tx act) :last-err-inst (:last-err-inst act)
     :non-retryable-model-error? (:non-retryable-model-error? act)
     :max-tool-turns mtt :max-bounces mb :max-total-turns mtot}))

(defn derive-agent-status
  {:malli/schema [:function
                  [:=> [:cat :any :any :map [:set :any] [:set :any] :keyword] [:maybe :map]]
                  [:=> [:cat :any :any :map [:set :any] [:set :any] [:set :any] :keyword] [:maybe :map]]
                  [:=> [:cat :any :any :map [:set :any] [:set :any] [:set :any] [:set :any] :keyword] [:maybe :map]]]}
  ([main-db store live-map testing-set merging-set agent-id]
   (derive-agent-status main-db store live-map testing-set merging-set #{} #{} agent-id))
  ([main-db store live-map testing-set merging-set settling-set agent-id]
   (derive-agent-status main-db store live-map testing-set merging-set settling-set #{} agent-id))
  ([main-db store live-map testing-set merging-set settling-set gate-notified-set agent-id]
   (when-let [e (let [eid (:e (first (d/datoms main-db :avet :seon.agent/id agent-id)))]
                  (when (and eid
                             (not (and (contains? (:schema main-db) :seon.agent/archived?)
                                       (:v (first (d/datoms main-db :eavt eid :seon.agent/archived?)))))
                             (let [br (:v (first (d/datoms main-db :eavt eid :seon.agent/branch)))]
                               (or (nil? store) (nil? br) (= br :main) (store/branch? store br))))
                    eid))]
     (let [ma (d/pull main-db (quote [:seon.agent/id :seon.agent/parent :seon.agent/scope :seon.agent/branch
                                      {:seon.agent/tasks [:seon.task/id]} :seon.agent/target :seon.agent/forked-from :seon.agent/fork-commit
                                      :seon.agent/merge-target :seon.agent/router? :seon.agent/interactive? :seon.agent/goal
                                      :seon.agent/paused? :seon.agent/auto-merge? :seon.agent/tested-tx :seon.agent/merged-tx
                                      :seon.agent/awaiting]) e)
           b (:seon.agent/branch ma)
           branch-db (safe-branch-head store main-db b)
           be (:e (first (d/datoms branch-db :avet :seon.agent/id agent-id)))
           ba (or (when be (d/pull branch-db (quote [:seon.agent/waiting? :seon.agent/ready? :seon.agent/summary]) be)) {})
           ready-dt (when be (first (d/datoms branch-db :eavt be :seon.agent/ready?)))
           {:keys [seen inbox unread eval-ents]} (pull-agent-inbox-and-evals main-db branch-db agent-id)
           cur-eval-eids (into #{} (keep :db/id) eval-ents)
           act (policy/summarize-activity eval-ents inbox)
           {:keys [awaiting-count awaiting-blocked? has-pending-outgoing? awaiting-ready-to-deliver async-ready-to-deliver]}
           (call-delivery-state main-db agent-id ma settling-set)
           head-tx (:max-tx branch-db)
           gate (agent-gate-summary main-db branch-db agent-id)
           gate-ok? (boolean (:ok? gate true))
           live-entry (get live-map agent-id)
           step-in (build-policy-input main-db branch-db ma ba ready-dt unread act live-entry awaiting-blocked?
                   gate-ok? testing-set merging-set gate-notified-set seen inbox eval-ents)
           d0 (policy/decide-agent-step step-in)
           has-unread-rev-note? (boolean (some #(= :review-note (:seon.agent.message/type %)) unread))
           review-ops (cond
                        (:review-abort? d0)
                        (gate/review-cutoff-ops main-db ma
                          {:cutoff-reached? (:cutoff-reached? d0) :review-abort? true :err-halted? (:err-halted? d0)
                           :bounce-streak (:bounce-streak d0) :turns-since-external (:turns-since-external d0)
                           :gate-ok? gate-ok? :blockers (:blockers gate) :inbox inbox :unread unread :last-error (:last-err-msg act)})
                        (or (:review-notified? step-in) has-unread-rev-note? (:review-should-abort? d0)) []
                        :else (gate/compute-review-ops main-db branch-db ma
                                {:agent-eid e :active? (:active? d0) :raw-ready? (:raw-ready? step-in)
                                 :waiting? (:waiting? step-in) :merged? (:merged? d0)
                                 :tool-streak (:tool-streak act) :head-tx head-tx}))
           d (if (and (seq review-ops) (not (:review-abort? d0)))
               (policy/decide-agent-step (assoc step-in :has-pending-review-ops? true))
               d0)
           {:keys [live-pid busy? needs-release?]} (live-release-state live-entry cur-eval-eids (:paused? step-in) (:merged? d))
           wrapup-notify-op (when (:needs-wrapup? d)
                              (gate/wrapup-grace-notify-op main-db branch-db b agent-id head-tx inbox gate-notified-set))
           gate-notify-op (when-not (or (:bounce-halted? d) (:turn-budget-halted? d) (:err-halted? d) (:review-should-abort? d))
                            (gate/gate-blocked-notify-op (:status d) gate agent-id head-tx gate-notified-set))
           auto-settle-ops (compute-auto-settle-ops main-db agent-id b head-tx (:status d) (:eval-count act)
                                                    eval-ents (:last-eval act) seen inbox settling-set has-pending-outgoing?
                                                    (and (:paused? step-in) (gate/has-active-reviewer? main-db e)))]
       {:id agent-id :parent (:seon.agent/parent ma) :scope (:seon.agent/scope ma) :branch b
        :tasks (vec (keep :seon.task/id (:seon.agent/tasks ma))) :task (first (keep :seon.task/id (:seon.agent/tasks ma)))
        :gate gate :router? (boolean (:seon.agent/router? ma)) :goal (:seon.agent/goal ma)
        :paused? (:paused? step-in) :branch-tx head-tx :evals (:eval-count act) :eval-eids cur-eval-eids
        :unread-count (count unread) :awaiting-count awaiting-count :live-pid (when-not (:merged? d) live-pid) :busy? busy?
        :needs-turn? (:needs-turn? d) :needs-test? (:needs-test? d) :needs-merge? (:needs-merge? d) :needs-release? needs-release?
        :bounce-streak (:bounce-streak d) :turns-since-external (:turns-since-external d)
        :err-halted? (:err-halted? d) :bounce-halted? (:bounce-halted? d) :turn-budget-halted? (:turn-budget-halted? d)
        :awaiting-ready-to-deliver awaiting-ready-to-deliver :async-ready-to-deliver async-ready-to-deliver
        :gate-notify-op gate-notify-op :wrapup-notify-op wrapup-notify-op
        :auto-settle-ops auto-settle-ops :review-ops review-ops :status (:status d)}))))

(defn- reconcile-candidates [st1 main-db store candidate-ids]
  (let [evaluated (keep #(derive-agent-status main-db store (:live st1) (:testing st1) (:merging st1)
                                              (:settling st1) (:gate-notified st1) %)
                        (sort candidate-ids))
        to-run (filterv :needs-turn? evaluated)
        to-release (filterv :needs-release? evaluated)
        to-test (filterv :needs-test? evaluated)
        to-merge (filterv :needs-merge? evaluated)
        deliver-txs (vec (mapcat (fn [v]
                                   (cond-> []
                                     (seq (:awaiting-ready-to-deliver v))
                                     (conj {:op :settle-calls :caller (:id v) :call-ids (:awaiting-ready-to-deliver v) :clear-awaiting? true})
                                     (seq (:async-ready-to-deliver v))
                                     (conj {:op :settle-calls :caller (:id v) :call-ids (:async-ready-to-deliver v) :clear-awaiting? false})))
                                 evaluated))
        auto-settle-txs (vec (mapcat :auto-settle-ops evaluated))
        gate-notify-txs (vec (concat (keep :gate-notify-op evaluated) (keep :wrapup-notify-op evaluated) (mapcat :review-ops evaluated)))
        new-gate-notified (into #{} (concat (keep #(when (:gate-notify-op %) [(:id %) (:branch-tx %)]) evaluated)
                                            (keep #(when (:wrapup-notify-op %) [(:id %) (:branch-tx %) :wrapup]) evaluated)
                                            (keep #(when (seq (:review-ops %)) [(:id %) (:branch-tx %) :review]) evaluated)))
        new-settling (into #{} (concat (for [tx deliver-txs, cid (:call-ids tx)] [:deliver cid])
                                       (for [tx auto-settle-txs] [(if (= :send-message (:op tx)) :nudge :incoming) (:call-id tx)])))
        merge-txs (mapv (fn [v] {:op :merge :branch (:branch v) :agent-id (:id v) :branch-tx (:branch-tx v)
                                 :message (str "Merge " (:branch v) " (" (:id v) ")")}) to-merge)
        all-txs (vec (concat auto-settle-txs deliver-txs gate-notify-txs merge-txs))
        st2 (-> st1
                (update :views into (map (juxt :id identity) evaluated))
                (update :live (fn [lm]
                                (let [lm1 (reduce (fn [m v] (dissoc m (:id v))) lm to-release)
                                      lm2 (reduce (fn [m v]
                                                    (if (and (contains? m (:id v)) (not (get-in m [(:id v) :busy?])))
                                                      (assoc-in m [(:id v) :eval-eids] (:eval-eids v))
                                                      m))
                                                  lm1 evaluated)]
                                  (reduce (fn [m v]
                                            (assoc m (:id v) {:pid (or (get-in m [(:id v) :pid]) :waking)
                                                              :busy? true :eval-eids (:eval-eids v)}))
                                          lm2 to-run))))
                (update :testing into (map (fn [v] [(:id v) (:branch-tx v)]) to-test))
                (update :merging into (map (fn [v] [(:id v) (:branch-tx v)]) to-merge))
                (update :settling into new-settling)
                (update :gate-notified into new-gate-notified)
                (update :runs-sent + (count to-run)))
        run-msgs (into (mapv (fn [v] {:op :release-agent :agent-id (:id v)}) to-release)
                       (map (fn [v] {:agent-id (:id v) :branch (:branch v)}) to-run))]
    [st2 (cond-> {}
           (seq run-msgs) (assoc :run run-msgs)
           (seq to-test) (assoc :batch (mapv (fn [v] {:branch (:branch v) :agent-id (:id v) :branch-tx (:branch-tx v)}) to-test))
           (seq all-txs) (assoc :tx all-txs))]))

(defn reconciler-proc
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map] [:=> [:cat :map :any] :map] [:=> [:cat :map :any :any] [:tuple :map :any]]]}
  ([] {:params {:conn "Read access to store via :main conn"}
       :ins {:db "Commit notices from :transactor" :exits "Agent events from :agents"}
       :outs {:run "Turns/releases to :agents" :batch "Tests to :test" :tx "Merges/triggers to :transactor"}
       :workload :compute
       :doc "Pure O(|Delta|) reconciler for turns, tests, merges, `:rule.trigger/*`, and warm process release."
       :ping-map-fn (fn [st] {:main-tx (:main-tx st) :tracked-agents (count (:views st)) :live (:live st) :testing (count (:testing st)) :dispatched-runs (:runs-sent st)})})
  ([{:keys [conn]}]
   {:conn conn :last-commit nil :main-tx 0 :live {} :testing #{} :merging #{} :settling #{} :gate-notified #{} :views {} :runs-sent 0})
  ([st _] st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (let [conn (:conn st)
           [st1 cands ds]
           (case cid
             :db (let [mdb (d/commit-as-db conn (:commit msg)) tds (:tx-data msg)]
                   [(assoc st :last-commit (:commit msg) :main-tx (:tx msg))
                    (touched-agent-ids conn mdb (:prev-commit msg) (:commit msg) tds) tds])
             :exits (let [aid (:agent-id msg)]
                      (case (:ev msg)
                        :forked       [(update-in st [:live aid] merge {:pid (:pid msg) :busy? (:busy? msg true)}) #{aid} nil]
                        :turn-busy    [(update-in st [:live aid] merge {:pid (:pid msg) :busy? true}) #{aid} nil]
                        :turn-idle    [(update-in st [:live aid] merge {:pid (:pid msg) :busy? false}) #{aid} nil]
                        :exited       [(if (= (:pid msg) (get-in st [:live aid :pid])) (update st :live dissoc aid) st) #{aid} nil]
                        :branch-moved [st #{aid} nil]
                        [st #{} nil]))
             [st #{} nil])]
       (if (nil? (:last-commit st1))
         [st1 nil]
         (let [mdb (d/commit-as-db conn (:last-commit st1))
               st1 (prune-reconciler-sets st1 mdb (:store mdb) ds)
               trigs (when (and (= cid :db) (refine/trigger-attrs-touched? mdb ds))
                       (refine/compute-trigger-ops mdb))]
           (let [cands (refine/defer-triggered-agents cands trigs)]
           (if (and (empty? cands) (empty? trigs))
             [st1 nil]
             (let [[st2 outs] (if (seq cands) (reconcile-candidates st1 mdb (:store mdb) cands) [st1 {}])
                   outs' (if (seq trigs) (update outs :tx #(vec (concat % trigs))) outs)]
               [st2 (when (seq outs') outs')])))))))))