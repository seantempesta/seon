(ns seon.reconciler
  "The `:reconciler` Flow proc (`:compute` workload):
   - 100% pure state (no I/O, no atoms, no stored `:status` or `:want` in Datahike).
   - Incremental O(|Delta|) evaluation: on `:db` commit notices, inspects ONLY the agents
     whose entity or inbox messages moved in `d/changes`, and on `:exits` inspects ONLY that 1 agent!
   - Cold boot `init` evaluates all existing agents once -> power-loss reboot resumes seamlessly!"
  (:require [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.store :as store]))

(defn derive-agent-status
  "Pure function deriving an agent's complete state from `(main-db, store, live-pids, in-flight-tests)`."
  [main-db store live-map testing-set merging-set agent-id]
  (when-let [e (:e (first (d/datoms main-db :avet :agent/id agent-id)))]
    (let [ma (d/pull main-db '[:agent/id :agent/parent :agent/branch :agent/task
                               :agent/role :agent/goal :agent/paused?
                               :agent/tested-tx :agent/merged-tx] e)
          b (:agent/branch ma)
          branch-db (or (try (store/head store b) (catch Throwable _ nil)) main-db)
          ba (or (when-let [be (:e (first (d/datoms branch-db :avet :agent/id agent-id)))]
                   (d/pull branch-db '[:agent/waiting? :agent/ready?] be))
                 {})
          seen (into #{}
                     (mapcat (fn [dt]
                               (:eval/seen-messages (d/pull branch-db '[:eval/seen-messages] (:e dt)))))
                     (d/datoms branch-db :avet :eval/agent agent-id))
          inbox (->> (d/datoms main-db :avet :message/to agent-id)
                     (map #(d/pull main-db '[:message/id :message/seq :message/from :message/type :message/body] (:e %)))
                     (sort-by :message/seq)
                     vec)
          unread (filterv #(not (contains? seen (:message/id %))) inbox)
          eval-count (count (d/datoms branch-db :avet :eval/agent agent-id))
          head-tx (:max-tx branch-db)
          paused? (boolean (:agent/paused? ma))
          waiting? (boolean (:agent/waiting? ba))
          ready? (boolean (:agent/ready? ba))
          tested-tx (:agent/tested-tx ma)
          merged-tx (:agent/merged-tx ma)
          live-pid (get live-map agent-id)
          needs-turn? (and (not paused?)
                           (nil? live-pid)
                           (or (seq unread)
                               (and (not ready?) (not waiting?) (pos? (count inbox)))))
          needs-test? (and (not paused?)
                           (nil? live-pid)
                           (empty? unread)
                           ready?
                           (not= tested-tx head-tx)
                           (not (contains? testing-set [agent-id head-tx])))
          needs-merge? (and (not paused?)
                            (nil? live-pid)
                            (empty? unread)
                            ready?
                            (= tested-tx head-tx)
                            (not= merged-tx head-tx)
                            (not (contains? merging-set [agent-id head-tx])))
          status (cond
                   paused? :paused
                   live-pid :running
                   needs-turn? :runnable
                   (not ready?) :waiting
                   (not= tested-tx head-tx) :testing
                   (not= merged-tx head-tx) :merging
                   :else :merged)]
      {:id agent-id
       :parent (:agent/parent ma)
       :branch b
       :task (:agent/task ma)
       :role (:agent/role ma)
       :goal (:agent/goal ma)
       :paused? paused?
       :branch-tx head-tx
       :evals eval-count
       :unread-count (count unread)
       :live-pid live-pid
       :needs-turn? needs-turn?
       :needs-test? needs-test?
       :needs-merge? needs-merge?
       :status status})))

(defn- touched-agent-ids
  "O(|Delta|) extraction of ONLY the `:agent/id`s whose entity or inbox moved between `prev-cid` and `cid`."
  [conn main-db prev-cid cid]
  (if (or (nil? prev-cid) (= prev-cid cid))
    ;; Cold boot init: scan existing agents once
    (into #{} (map :v) (d/datoms main-db :aevt :agent/id))
    (let [ds (d/changes conn prev-cid cid)
          aid-attr (get-in main-db [:ident-ref-map :agent/id])
          to-attr (get-in main-db [:ident-ref-map :message/to])]
      (reduce
       (fn [acc dt]
         (let [a (:a dt)]
           (cond
             (or (= a :agent/id) (= a aid-attr))
             (conj acc (:v dt))

             (or (= a :message/to) (= a to-attr))
             (conj acc (:v dt))

             :else
             ;; Check if `(:e dt)` is an agent entity (e.g. `:agent/tested-tx` or `:agent/paused?` updated!)
             (if-let [id (:v (first (d/datoms main-db :eavt (:e dt) :agent/id)))]
               (conj acc id)
               acc))))
       #{}
       ds))))

(defn reconciler-proc
  ([] {:params {:conn "Read access to store via :main conn"}
       :ins {:db "Commit notices `{:prev-commit :commit :tx}` from `:transactor`"
             :exits "Agent fork/exit events from `:agents`"}
       :outs {:run "Autonomous turn dispatches to `:agents`"
              :batch "Branch test dispatches to `:test`"
              :tx "Verified merge dispatches to `:transactor`"}
       :workload :compute
       :doc "Pure O(|Delta|) reconciler: derives agent turns, test runs, and merges without stored status."
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
    :live {}       ; {agent-id pid-or-:waking}
    :testing #{}   ; #{[agent-id branch-tx]}
    :merging #{}   ; #{[agent-id branch-tx]}
    :views {}      ; {agent-id derived-map}
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
                 :forked [(assoc-in st [:live aid] (:pid msg)) #{aid}]
                 :exited [(update st :live dissoc aid) #{aid}]
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
               to-test (filterv :needs-test? evaluated)
               to-merge (filterv :needs-merge? evaluated)
               st2 (-> st1
                       (update :views into (map (juxt :id identity) evaluated))
                       (update :live into (map (fn [v] [(:id v) :waking]) to-run))
                       (update :testing into (map (fn [v] [(:id v) (:branch-tx v)]) to-test))
                       (update :merging into (map (fn [v] [(:id v) (:branch-tx v)]) to-merge))
                       (update :runs-sent + (count to-run)))]
           [st2
            (cond-> {}
              (seq to-run)
              (assoc :run (mapv (fn [v] {:agent-id (:id v) :branch (:branch v)}) to-run))

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
