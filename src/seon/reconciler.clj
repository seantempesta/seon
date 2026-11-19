(ns seon.reconciler
  "The `:reconciler` Flow proc (`:compute` workload):
   - 100% pure state (no I/O, no atoms, no stored `:status` in Datahike).
   - Incremental O(|Delta|) evaluation over `:seon.agent/*` & `:seon.agent.message/*`.
   - Distinguishes `:busy` (in-turn) from `:idle` (warm OS process kept across turns),
     releasing the OS process cleanly once `:merged`."
  (:require [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.store :as store]))

(defn derive-agent-status
  [main-db store live-map testing-set merging-set agent-id]
  (when-let [e (:e (first (d/datoms main-db :avet :seon.agent/id agent-id)))]
    (let [ma (d/pull main-db '[:seon.agent/id :seon.agent/parent :seon.agent/scope :seon.agent/branch
                               :seon.agent/task :seon.agent/role :seon.agent/goal :seon.agent/paused?
                               :seon.agent/auto-merge? :seon.agent/tested-tx :seon.agent/merged-tx] e)
          b (:seon.agent/branch ma)
          branch-db (or (try (store/head store b) (catch Throwable _ nil)) main-db)
          ba (or (when-let [be (:e (first (d/datoms branch-db :avet :seon.agent/id agent-id)))]
                   (d/pull branch-db '[:seon.agent/waiting? :seon.agent/ready?] be))
                 {})
          seen (into #{}
                     (mapcat (fn [dt]
                               (:seon.agent.eval/seen-messages
                                (d/pull branch-db '[:seon.agent.eval/seen-messages] (:e dt)))))
                     (d/datoms branch-db :avet :seon.agent.eval/agent agent-id))
          inbox (->> (d/datoms main-db :avet :seon.agent.message/to agent-id)
                     (map #(d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq
                                             :seon.agent.message/from :seon.agent.message/type
                                             :seon.agent.message/body] (:e %)))
                     (sort-by :seon.agent.message/seq)
                     vec)
          unread (filterv #(not (contains? seen (:seon.agent.message/id %))) inbox)
          eval-count (count (d/datoms branch-db :avet :seon.agent.eval/agent agent-id))
          head-tx (:max-tx branch-db)
          paused? (boolean (:seon.agent/paused? ma))
          waiting? (boolean (:seon.agent/waiting? ba))
          ready? (boolean (:seon.agent/ready? ba))
          auto-merge? (if (some? (:seon.agent/auto-merge? ma))
                        (boolean (:seon.agent/auto-merge? ma))
                        (boolean (store/config-val main-db agent-id :seon.config/auto-merge?)))
          eligible-to-merge? (and (not= b :main)
                                  (or ready? (and auto-merge? (pos? eval-count) (not waiting?))))
          tested-tx (:seon.agent/tested-tx ma)
          merged-tx (:seon.agent/merged-tx ma)
          live-entry (get live-map agent-id)
          live-pid (if (map? live-entry) (:pid live-entry) live-entry)
          busy? (boolean (if (map? live-entry) (:busy? live-entry) live-entry))
          needs-turn? (and (not paused?)
                           (not busy?)
                           (or (seq unread)
                               (and (not ready?) (not waiting?) (pos? (count inbox)) (zero? eval-count))))
          needs-test? (and (not paused?)
                           (not busy?)
                           (empty? unread)
                           eligible-to-merge?
                           (not= tested-tx head-tx)
                           (not (contains? testing-set [agent-id head-tx])))
          needs-merge? (and (not paused?)
                            (not busy?)
                            (empty? unread)
                            eligible-to-merge?
                            (= tested-tx head-tx)
                            (not= merged-tx head-tx)
                            (not (contains? merging-set [agent-id head-tx])))
          merged? (and ready? (empty? unread) (= tested-tx head-tx) (= merged-tx head-tx))
          needs-release? (and merged? (some? live-pid))
          status (cond
                   paused? :paused
                   busy? :running
                   needs-turn? :runnable
                   (and eligible-to-merge? (not= tested-tx head-tx)) :testing
                   (and eligible-to-merge? (not= merged-tx head-tx)) :merging
                   (not ready?) :waiting
                   :else :merged)]
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
       :live-pid (when-not merged? live-pid)
       :busy? busy?
       :needs-turn? needs-turn?
       :needs-test? needs-test?
       :needs-merge? needs-merge?
       :needs-release? needs-release?
       :status status})))

(defn- touched-agent-ids [conn main-db prev-cid cid]
  (if (or (nil? prev-cid) (= prev-cid cid))
    (into #{} (map :v) (d/datoms main-db :aevt :seon.agent/id))
    (let [ds (d/changes conn prev-cid cid)
          aid-attr (get-in main-db [:ident-ref-map :seon.agent/id])
          to-attr (get-in main-db [:ident-ref-map :seon.agent.message/to])]
      (reduce
       (fn [acc dt]
         (let [a (:a dt)]
           (cond
             (or (= a :seon.agent/id) (= a aid-attr))
             (conj acc (:v dt))

             (or (= a :seon.agent.message/to) (= a to-attr))
             (conj acc (:v dt))

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
               evaluated (keep #(derive-agent-status main-db store (:live st1) (:testing st1) (:merging st1) %)
                               (sort candidate-ids))
               to-run (filterv :needs-turn? evaluated)
               to-release (filterv :needs-release? evaluated)
               to-test (filterv :needs-test? evaluated)
               to-merge (filterv :needs-merge? evaluated)
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

              (seq to-merge)
              (assoc :tx (mapv (fn [v] {:op :merge
                                        :branch (:branch v)
                                        :agent-id (:id v)
                                        :branch-tx (:branch-tx v)
                                        :message (str "Merge " (:branch v) " (" (:id v) ")")})
                               to-merge)))]))))))
