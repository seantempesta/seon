(ns seon.reconciler
  "The `:reconciler` Flow proc (`:compute` workload):
   - 100% pure state (no I/O, no atoms, no stored `:status` in Datahike).
   - Incremental O(|Delta|) evaluation over `:seon.agent/*` & `:seon.agent.message/*`.
   - Distinguishes `:busy` (in-turn) from `:idle` (warm OS process kept across turns),
     releasing the OS process cleanly once `:merged`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store]
            [seon.task.gate :as gate]))

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

(defn- eval-activity-summary
  ([eval-ents] (eval-activity-summary eval-ents #{}))
  ([eval-ents ignore-reset-mids]
   (let [model-tool-eval? (fn [ev]
                            (and (some? (:seon.agent.eval/call-id ev))
                                 (not (str/blank? (str (:seon.agent.eval/code ev))))))
         last-eval (peek eval-ents)
         tool-streak (loop [es (reverse eval-ents), turns #{}]
                       (let [ev (first es)]
                         (if (and ev (model-tool-eval? ev))
                           (let [turns' (conj turns (:seon.agent.eval/turn ev))
                                 real-seen (remove ignore-reset-mids (:seon.agent.eval/seen-messages ev))]
                             (if (seq real-seen)
                               (count turns')
                               (recur (rest es) turns')))
                           (count turns))))]
     {:eval-count (count eval-ents)
      :last-eval last-eval
      :last-was-tool? (boolean (and last-eval (model-tool-eval? last-eval)))
      :tool-streak tool-streak})))

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

(defn- pull-agent-inbox-and-evals [main-db branch-db agent-id]
  (let [seen (store/agent-seen-message-ids branch-db agent-id)
        inbox (->> (d/datoms main-db :avet :seon.agent.message/to agent-id)
                   (map #(d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq
                                           :seon.agent.message/from :seon.agent.message/type
                                           :seon.agent.message/body] (:e %)))
                   (sort-by :seon.agent.message/seq)
                   vec)
        unread (filterv #(not (contains? seen (:seon.agent.message/id %))) inbox)
        eval-ents (->> (d/datoms branch-db :avet :seon.agent.eval/agent agent-id)
                       (map (fn [dt]
                              (assoc (d/pull branch-db '[:seon.agent.eval/turn :seon.agent.eval/call-index
                                                         :seon.agent.eval/call-id :seon.agent.eval/code
                                                         :seon.agent.eval/thought :seon.agent.eval/error
                                                         :seon.agent.eval/seen-messages] (:e dt))
                                     :db/id (:e dt)
                                     :tx (:tx dt))))
                       (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0)))
                       vec)]
    {:seen seen :inbox inbox :unread unread :eval-ents eval-ents}))

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
      (let [test-fails (msg-recipients-by-type main-db ds #{:test-failure})
            merge-fails (msg-recipients-by-type main-db ds #{:merge-conflict :health-block})
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
               :testing (keep-by testing (fn [aid tx i _] (and (not= (:merged-tx i) tx) (not= (:tested-tx i) tx) (not (contains? test-fails aid)))))
               :merging (keep-by merging (fn [aid tx i _] (and (not= (:merged-tx i) tx) (not (contains? merge-fails aid)))))
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
              {:seon.agent/tasks [:seon.task/id]} :seon.agent/target :seon.agent/forked-from :seon.agent/router? :seon.agent/interactive? :seon.agent/goal :seon.agent/paused?
              :seon.agent/auto-merge? :seon.agent/tested-tx :seon.agent/merged-tx :seon.agent/awaiting]) e)
           b (:seon.agent/branch ma)
           branch-db (safe-branch-head store main-db b)
           be (:e (first (d/datoms branch-db :avet :seon.agent/id agent-id)))
           ba (or (when be (d/pull branch-db (quote [:seon.agent/waiting? :seon.agent/ready? :seon.agent/summary]) be)) {})
           ready-tx (or (when be (:tx (first (d/datoms branch-db :eavt be :seon.agent/ready?)))) 0)
           {:keys [seen inbox unread eval-ents]} (pull-agent-inbox-and-evals main-db branch-db agent-id)
           cur-eval-eids (into #{} (keep :db/id) eval-ents)
           wrapup-mids (into #{} (keep #(when (= :turn-cap-wrapup (:seon.agent.message/type %)) (:seon.agent.message/id %))) inbox)
           {:keys [eval-count last-eval last-was-tool? tool-streak]} (eval-activity-summary eval-ents wrapup-mids)
           {:keys [awaiting-count awaiting-blocked? has-pending-outgoing? awaiting-ready-to-deliver async-ready-to-deliver]}
           (call-delivery-state main-db agent-id ma settling-set)
           head-tx (:max-tx branch-db)
           paused? (boolean (:seon.agent/paused? ma))
           waiting? (boolean (:seon.agent/waiting? ba))
           tested-tx (:seon.agent/tested-tx ma)
           merged-tx (:seon.agent/merged-tx ma)
           raw-ready? (boolean (:seon.agent/ready? ba))
           ready? (and raw-ready? (or (nil? merged-tx) (> (long ready-tx) (long merged-tx))))
           auto-merge? (if (some? (:seon.agent/auto-merge? ma))
                         (boolean (:seon.agent/auto-merge? ma))
                         (boolean (store/config-val main-db agent-id :seon.config/auto-merge?)))
           eligible-to-merge? (and (not= b :main) ready? auto-merge?)
           gate (agent-gate-summary main-db branch-db agent-id)
           gate-ok? (boolean (:ok? gate true))
           merged? (and raw-ready? (empty? unread) (some? merged-tx) (= tested-tx head-tx) (= merged-tx head-tx))
           {:keys [live-pid busy? needs-release?]} (live-release-state (get live-map agent-id) cur-eval-eids paused? merged?)
           active? (and (not paused?) (not busy?) (not awaiting-blocked?))
           max-tool-turns (let [v (when (contains? (:schema main-db) :seon.config/max-tool-turns) (store/config-val main-db agent-id :seon.config/max-tool-turns))] (if (number? v) (long v) 24))
           under-turn-cap? (or (<= max-tool-turns 0) (< tool-streak max-tool-turns))
           review-ops (if (or (contains? gate-notified-set [agent-id head-tx :review]) (some #(= :review-note (:seon.agent.message/type %)) unread))
                      []
                      (gate/compute-review-ops main-db branch-db ma
                        {:agent-eid e :active? active? :raw-ready? raw-ready? :waiting? waiting?
                         :merged? merged? :tool-streak tool-streak :head-tx head-tx}))
           wrapup-notify-op (when (and active? (empty? review-ops) (pos? max-tool-turns) (= tool-streak max-tool-turns)
                                       (not raw-ready?) (not waiting?) last-was-tool? (empty? unread))
                              (gate/wrapup-grace-notify-op main-db branch-db b agent-id head-tx inbox gate-notified-set))
           post-ready-reply? (needs-post-ready-reply? raw-ready? waiting? last-was-tool? under-turn-cap? ready-tx seen inbox eval-ents (and (str/blank? (str (:seon.agent/summary ba))) (boolean (or (:seon.agent/target ma) (:seon.agent/forked-from ma)))))
           needs-turn? (boolean (and active? (empty? review-ops)
                                     (or (seq unread) post-ready-reply?
                                         (and (not raw-ready?) (not waiting?) last-was-tool? under-turn-cap?))))
           ready-idle? (and active? (empty? unread) (not post-ready-reply?) eligible-to-merge?)
           needs-test? (and ready-idle? (not= tested-tx head-tx) (not (contains? testing-set [agent-id head-tx])))
           needs-merge? (and ready-idle? (= tested-tx head-tx) gate-ok?
                             (not= merged-tx head-tx) (not (contains? merging-set [agent-id head-tx])))
           status (cond paused? :paused busy? :running awaiting-blocked? :awaiting needs-turn? :runnable
                        (and eligible-to-merge? (not= tested-tx head-tx)) :testing
                        (and eligible-to-merge? (not gate-ok?)) :gate-blocked
                        (and eligible-to-merge? (not= merged-tx head-tx)) :merging
                        merged? :merged :else :waiting)
           gate-notify-op (gate/gate-blocked-notify-op status gate agent-id head-tx gate-notified-set)
           auto-settle-ops (compute-auto-settle-ops main-db agent-id b head-tx status eval-count
                           eval-ents last-eval seen inbox settling-set has-pending-outgoing? (and paused? (gate/has-active-reviewer? main-db e)))]
       {:id agent-id :parent (:seon.agent/parent ma) :scope (:seon.agent/scope ma) :branch b
        :tasks (vec (keep :seon.task/id (:seon.agent/tasks ma))) :task (first (keep :seon.task/id (:seon.agent/tasks ma))) :gate gate :router? (boolean (:seon.agent/router? ma)) :goal (:seon.agent/goal ma)
        :paused? paused? :branch-tx head-tx :evals eval-count :eval-eids cur-eval-eids :unread-count (count unread)
        :awaiting-count awaiting-count :live-pid (when-not merged? live-pid) :busy? busy?
        :needs-turn? needs-turn? :needs-test? needs-test? :needs-merge? needs-merge? :needs-release? needs-release?
        :awaiting-ready-to-deliver awaiting-ready-to-deliver :async-ready-to-deliver async-ready-to-deliver
        :gate-notify-op gate-notify-op :wrapup-notify-op wrapup-notify-op
        :auto-settle-ops auto-settle-ops :review-ops review-ops :status status}))))

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
       :ins {:db "Commit notices `{:prev-commit :commit :tx}` from `:transactor`"
             :exits "Agent fork/idle/exit events from `:agents`"}
       :outs {:run "Autonomous turn & release dispatches to `:agents`"
              :batch "Branch test dispatches to `:test`"
              :tx "Verified merge dispatches to `:transactor`"}
       :workload :compute
       :doc "Pure O(|Delta|) reconciler: derives agent turns, test runs, merges, and warm process release."
       :ping-map-fn (fn [st]
                      {:main-tx (:main-tx st)
                       :tracked-agents (count (:views st))
                       :live (:live st)
                       :testing (count (:testing st))
                       :dispatched-runs (:runs-sent st)})})
  ([{:keys [conn]}]
   {:conn conn :last-commit nil :main-tx 0 :live {} :testing #{} :merging #{} :settling #{} :gate-notified #{} :views {} :runs-sent 0})
  ([st _] st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (let [conn (:conn st)
           [st1 candidate-ids ds]
           (case cid
             :db
             (let [main-db (d/commit-as-db conn (:commit msg))
                   tx-ds (:tx-data msg)
                   ids (touched-agent-ids conn main-db (:prev-commit msg) (:commit msg) tx-ds)]
               [(assoc st :last-commit (:commit msg) :main-tx (:tx msg)) ids tx-ds])
             :exits
             (let [aid (:agent-id msg)]
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
         (let [main-db (d/commit-as-db conn (:last-commit st1))
               store (:store main-db)
               st1 (prune-reconciler-sets st1 main-db store ds)]
           (if (empty? candidate-ids)
             [st1 nil]
             (reconcile-candidates st1 main-db store candidate-ids))))))))