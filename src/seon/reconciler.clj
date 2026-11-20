(ns seon.reconciler
  "The `:reconciler` Flow proc (`:compute` workload):
   - 100% pure state (no I/O, no atoms, no stored `:status` in Datahike).
   - Incremental O(|Delta|) evaluation over `:seon.agent/*` & `:seon.agent.message/*`.
   - Distinguishes `:busy` (in-turn) from `:idle` (warm OS process kept across turns),
     releasing the OS process cleanly once `:merged`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store]))

(defn- pull-calls-by-attr [db attr val]
  (if (and db (contains? (:schema db) attr))
    (->> (d/datoms db :avet attr val)
         (map #(d/pull db '[:seon.agent.call/id :seon.agent.call/caller :seon.agent.call/callee
                            :seon.agent.call/message-id :seon.agent.call/result-schema
                            :seon.agent.call/status :seon.agent.call/delivered?] (:e %)))
         vec)
    []))

(defn- pull-call-by-id [db cid]
  (when (and db cid (contains? (:schema db) :seon.agent.call/id)
             (first (d/datoms db :avet :seon.agent.call/id cid)))
    (d/pull db '[:seon.agent.call/id :seon.agent.call/caller :seon.agent.call/callee
                 :seon.agent.call/message-id :seon.agent.call/result-schema
                 :seon.agent.call/status :seon.agent.call/delivered?]
            [:seon.agent.call/id cid])))

(defn- latest-prose-after-msg [eval-ents msg-id]
  (when msg-id
    (let [after (drop-while #(not (contains? (:seon.agent.eval/seen-messages %) msg-id)) eval-ents)
          prose (->> after
                     (filter #(and (str/blank? (str (:seon.agent.eval/code %)))
                                   (not (str/blank? (str (:seon.agent.eval/thought %))))))
                     (map :seon.agent.eval/thought)
                     last)]
      prose)))

(defn derive-agent-status
  {:malli/schema [:function [:=> [:cat :any :any :map [:set :any] [:set :any] :keyword] [:maybe :map]] [:=> [:cat :any :any :map [:set :any] [:set :any] [:set :any] :keyword] [:maybe :map]]]}
  ([main-db store live-map testing-set merging-set agent-id]
   (derive-agent-status main-db store live-map testing-set merging-set #{} agent-id))
  ([main-db store live-map testing-set merging-set settling-set agent-id]
   (when-let [e (:e (first (d/datoms main-db :avet :seon.agent/id agent-id)))]
     (let [ma (d/pull main-db '[:seon.agent/id :seon.agent/parent :seon.agent/scope :seon.agent/branch
                                :seon.agent/task :seon.agent/role :seon.agent/goal :seon.agent/paused?
                                :seon.agent/auto-merge? :seon.agent/tested-tx :seon.agent/merged-tx
                                :seon.agent/awaiting] e)
           b (:seon.agent/branch ma)
           branch-db (or (try (store/head store b) (catch Throwable _ nil)) main-db)
           be (:e (first (d/datoms branch-db :avet :seon.agent/id agent-id)))
           ba (or (when be (d/pull branch-db '[:seon.agent/waiting? :seon.agent/ready?] be))
                  {})
           ready-tx (or (when be (:tx (first (d/datoms branch-db :eavt be :seon.agent/ready?)))) 0)
           seen (store/agent-seen-message-ids branch-db agent-id)
           inbox (->> (d/datoms main-db :avet :seon.agent.message/to agent-id)
                      (map #(d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq
                                              :seon.agent.message/from :seon.agent.message/type
                                              :seon.agent.message/body] (:e %)))
                      (sort-by :seon.agent.message/seq)
                      vec)
           unread (filterv #(not (contains? seen (:seon.agent.message/id %))) inbox)
           eval-ents (->> (d/datoms branch-db :avet :seon.agent.eval/agent agent-id)
                          (map #(d/pull branch-db '[:seon.agent.eval/turn :seon.agent.eval/call-index
                                                    :seon.agent.eval/call-id :seon.agent.eval/code
                                                    :seon.agent.eval/thought :seon.agent.eval/error
                                                    :seon.agent.eval/seen-messages] (:e %)))
                          (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0)))
                          vec)
           eval-count (count eval-ents)
           model-tool-eval? (fn [ev]
                              (and (some? (:seon.agent.eval/call-id ev))
                                   (not (str/blank? (str (:seon.agent.eval/code ev))))))
           last-eval (peek eval-ents)
           last-was-tool? (boolean (and last-eval (model-tool-eval? last-eval)))
           tool-streak (loop [es (reverse eval-ents) turns #{}]
                         (let [ev (first es)]
                           (if (and ev (model-tool-eval? ev))
                             (let [turns' (conj turns (:seon.agent.eval/turn ev))]
                               (if (seq (:seon.agent.eval/seen-messages ev))
                                 (count turns')
                                 (recur (rest es) turns')))
                             (count turns))))
           awaiting-ids (set (:seon.agent/awaiting ma))
           awaiting-calls (vec (keep #(pull-call-by-id main-db %) awaiting-ids))
           pending-awaiting (filterv #(= :pending (:seon.agent.call/status %)) awaiting-calls)
           awaiting-blocked? (boolean (seq pending-awaiting))
           awaiting-ready-to-deliver (when (and (seq awaiting-calls) (empty? pending-awaiting))
                                       (vec (keep (fn [c]
                                                    (let [cid (:seon.agent.call/id c)]
                                                      (when (and (not (:seon.agent.call/delivered? c))
                                                                 (not (contains? settling-set [:deliver cid])))
                                                        cid)))
                                                  awaiting-calls)))
           outgoing-calls (pull-calls-by-attr main-db :seon.agent.call/caller agent-id)
           has-pending-outgoing? (boolean (some #(= :pending (:seon.agent.call/status %)) outgoing-calls))
           async-ready-to-deliver (vec (keep (fn [c]
                                               (let [cid (:seon.agent.call/id c)]
                                                 (when (and (not (contains? awaiting-ids cid))
                                                            (not= :pending (:seon.agent.call/status c))
                                                            (not (:seon.agent.call/delivered? c))
                                                            (not (contains? settling-set [:deliver cid])))
                                                   cid)))
                                             outgoing-calls))
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
           live-entry (get live-map agent-id)
           live-pid (if (map? live-entry) (:pid live-entry) live-entry)
           busy? (boolean (if (map? live-entry) (:busy? live-entry) live-entry))
           max-tool-turns (let [v (when (contains? (:schema main-db) :seon.config/max-tool-turns)
                                    (store/config-val main-db agent-id :seon.config/max-tool-turns))]
                            (if (number? v) (long v) 24))
           under-turn-cap? (or (<= max-tool-turns 0) (< tool-streak max-tool-turns))
           needs-turn? (and (not paused?)
                            (not busy?)
                            (not awaiting-blocked?)
                            (or (seq unread)
                                (and (not raw-ready?) (not waiting?) last-was-tool? under-turn-cap?)))
           needs-test? (and (not paused?)
                            (not busy?)
                            (not awaiting-blocked?)
                            (empty? unread)
                            eligible-to-merge?
                            (not= tested-tx head-tx)
                            (not (contains? testing-set [agent-id head-tx])))
           needs-merge? (and (not paused?)
                             (not busy?)
                             (not awaiting-blocked?)
                             (empty? unread)
                             eligible-to-merge?
                             (= tested-tx head-tx)
                             (not= merged-tx head-tx)
                             (not (contains? merging-set [agent-id head-tx])))
           merged? (and raw-ready? (empty? unread) (some? merged-tx) (= tested-tx head-tx) (= merged-tx head-tx))
           needs-release? (and merged? (some? live-pid))
           status (cond
                    paused? :paused
                    busy? :running
                    awaiting-blocked? :awaiting
                    needs-turn? :runnable
                    (and eligible-to-merge? (not= tested-tx head-tx)) :testing
                    (and eligible-to-merge? (not= merged-tx head-tx)) :merging
                    merged? :merged
                    :else :waiting)
           incoming-pending (filterv #(= :pending (:seon.agent.call/status %))
                                     (pull-calls-by-attr main-db :seon.agent.call/callee agent-id))
           auto-settle-ops
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
                            {:op :fail-call
                             :call-id cid
                             :error {:class :callee-paused
                                     :callee agent-id
                                     :message (str "Callee " agent-id " is paused")}}

                            (and (= status :merged) mid (contains? seen mid) (nil? rsch))
                            {:op :answer-call
                             :call-id cid
                             :result {:merged? true :branch b :merged-tx head-tx}}

                            (and mid (contains? seen mid))
                            (let [prose (latest-prose-after-msg eval-ents mid)]
                              (if (and prose (nil? rsch))
                                {:op :answer-call
                                 :call-id cid
                                 :result {:answer prose :implicit-prose? true}}
                                {:op :fail-call
                                 :call-id cid
                                 :error (cond-> {:class (if rsch :schema-mismatch :callee-stalled)
                                                 :callee agent-id
                                                 :evals eval-count
                                                 :message (if rsch
                                                            (str "Callee stopped without calling (user/answer! call-id value) matching " rsch)
                                                            (str "Callee " agent-id " stopped after " eval-count " eval(s) without answering call"))}
                                          (:seon.agent.eval/error last-eval) (assoc :last-error (:seon.agent.eval/error last-eval))
                                          prose (assoc :prose prose))}))))))
                    incoming-pending)))]
       {:id agent-id
        :parent (:seon.agent/parent ma)
        :scope (:seon.agent/scope ma)
        :branch b
        :task (:seon.agent/task ma)
        :role (:seon.agent/role ma)
        :goal (:seon.agent/goal ma)
        :paused? paused?
        :branch-tx head-tx
        :evals eval-count
        :unread-count (count unread)
        :awaiting-count (count pending-awaiting)
        :live-pid (when-not merged? live-pid)
        :busy? busy?
        :needs-turn? needs-turn?
        :needs-test? needs-test?
        :needs-merge? needs-merge?
        :needs-release? needs-release?
        :awaiting-ready-to-deliver awaiting-ready-to-deliver
        :async-ready-to-deliver async-ready-to-deliver
        :auto-settle-ops auto-settle-ops
        :status status}))))

(defn- touched-agent-ids [conn main-db prev-cid cid]
  (if (or (nil? prev-cid) (= prev-cid cid))
    (into #{} (map :v) (d/datoms main-db :aevt :seon.agent/id))
    (let [ds (d/changes conn prev-cid cid)
          aid-attr (get-in main-db [:ident-ref-map :seon.agent/id])
          to-attr (get-in main-db [:ident-ref-map :seon.agent.message/to])
          caller-attr (get-in main-db [:ident-ref-map :seon.agent.call/caller])
          callee-attr (get-in main-db [:ident-ref-map :seon.agent.call/callee])
          call-status-attr (get-in main-db [:ident-ref-map :seon.agent.call/status])
          call-deliv-attr (get-in main-db [:ident-ref-map :seon.agent.call/delivered?])]
      (reduce
       (fn [acc dt]
         (let [a (:a dt)]
           (cond
             (or (= a :seon.agent/id) (= a aid-attr))
             (conj acc (:v dt))

             (or (= a :seon.agent.message/to) (= a to-attr))
             (conj acc (:v dt))

             (or (= a :seon.agent.call/caller) (= a caller-attr)
                 (= a :seon.agent.call/callee) (= a callee-attr))
             (conj acc (:v dt))

             (or (= a :seon.agent.call/status) (= a call-status-attr)
                 (= a :seon.agent.call/delivered?) (= a call-deliv-attr))
             (let [c (d/pull main-db '[:seon.agent.call/caller :seon.agent.call/callee] (:e dt))]
               (cond-> acc
                 (:seon.agent.call/caller c) (conj (:seon.agent.call/caller c))
                 (:seon.agent.call/callee c) (conj (:seon.agent.call/callee c))))

             :else
             (if-let [id (:v (first (d/datoms main-db :eavt (:e dt) :seon.agent/id)))]
               (conj acc id)
               acc))))
       #{}
       ds))))

(defn reconciler-proc
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
   {:conn conn
    :last-commit nil
    :main-tx 0
    :live {}
    :testing #{}
    :merging #{}
    :settling #{}
    :views {}
    :runs-sent 0})
  ([st _] st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (let [conn (:conn st)
           [st1 candidate-ids]
           (case cid
             :db
             (let [main-db (d/commit-as-db conn (:commit msg))
                   ids (touched-agent-ids conn main-db (:prev-commit msg) (:commit msg))]
               [(assoc st :last-commit (:commit msg) :main-tx (:tx msg))
                ids])

             :exits
             (let [aid (:agent-id msg)]
               (case (:ev msg)
                 :forked       [(assoc-in st [:live aid] {:pid (:pid msg) :busy? (:busy? msg true)}) #{aid}]
                 :turn-busy    [(assoc-in st [:live aid] {:pid (:pid msg) :busy? true}) #{aid}]
                 :turn-idle    [(assoc-in st [:live aid] {:pid (:pid msg) :busy? false}) #{aid}]
                 :exited       [(if (= (:pid msg) (get-in st [:live aid :pid]))
                                  (update st :live dissoc aid)
                                  st)
                                #{aid}]
                 :branch-moved [st #{aid}]
                 [st #{}]))

             [st #{}])]
       (if (or (nil? (:last-commit st1)) (empty? candidate-ids))
         [st1 nil]
         (let [main-db (d/commit-as-db conn (:last-commit st1))
               store (:store main-db)
               evaluated (keep #(derive-agent-status main-db store (:live st1) (:testing st1) (:merging st1) (:settling st1) %)
                               (sort candidate-ids))
               to-run (filterv :needs-turn? evaluated)
               to-release (filterv :needs-release? evaluated)
               to-test (filterv :needs-test? evaluated)
               to-merge (filterv :needs-merge? evaluated)
               deliver-txs (vec (mapcat (fn [v]
                                          (cond-> []
                                            (seq (:awaiting-ready-to-deliver v))
                                            (conj {:op :settle-calls
                                                   :caller (:id v)
                                                   :call-ids (:awaiting-ready-to-deliver v)
                                                   :clear-awaiting? true})
                                            (seq (:async-ready-to-deliver v))
                                            (conj {:op :settle-calls
                                                   :caller (:id v)
                                                   :call-ids (:async-ready-to-deliver v)
                                                   :clear-awaiting? false})))
                                        evaluated))
               auto-settle-txs (vec (mapcat :auto-settle-ops evaluated))
               new-settling (into #{}
                                  (concat (for [tx deliver-txs, cid (:call-ids tx)] [:deliver cid])
                                          (for [tx auto-settle-txs] [:incoming (:call-id tx)])))
               merge-txs (mapv (fn [v] {:op :merge
                                        :branch (:branch v)
                                        :agent-id (:id v)
                                        :branch-tx (:branch-tx v)
                                        :message (str "Merge " (:branch v) " (" (:id v) ")")})
                               to-merge)
               all-txs (vec (concat auto-settle-txs deliver-txs merge-txs))
               st2 (-> st1
                       (update :views into (map (juxt :id identity) evaluated))
                       (update :live (fn [lm]
                                       (let [lm1 (reduce (fn [m v]
                                                           (assoc m (:id v)
                                                                  {:pid (or (get-in m [(:id v) :pid]) :waking)
                                                                   :busy? true}))
                                                         lm to-run)]
                                         (reduce (fn [m v] (dissoc m (:id v))) lm1 to-release))))
                       (update :testing into (map (fn [v] [(:id v) (:branch-tx v)]) to-test))
                       (update :merging into (map (fn [v] [(:id v) (:branch-tx v)]) to-merge))
                       (update :settling into new-settling)
                       (update :runs-sent + (count to-run)))
               run-msgs (into (mapv (fn [v] {:agent-id (:id v) :branch (:branch v)}) to-run)
                              (map (fn [v] {:op :release-agent :agent-id (:id v)}) to-release))]
           [st2
            (cond-> {}
              (seq run-msgs)
              (assoc :run run-msgs)

              (seq to-test)
              (assoc :batch (mapv (fn [v] {:branch (:branch v)
                                           :agent-id (:id v)
                                           :branch-tx (:branch-tx v)})
                                  to-test))

              (seq all-txs)
              (assoc :tx all-txs))]))))))