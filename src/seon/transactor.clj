(ns seon.transactor
  "The `:transactor` Flow proc (`:mixed` workload):
   - Single serialized writer of `:main` on LMDB (`d/transact`, `store/branch!`, `merge/three-way`).
   - Automatically installs derived Datahike `:db/ident` declarations when Malli schemas register or merge!
   - Hot-loads merged `:seon.code.form/*` deltas into Flow AND sends `:advance-image` (including any
     same-ns rebind forms!) to the 1-thread System Fork Parent so forked agents & test workers always start
     0 commits behind `:main`."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.fork :as fork]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.transactor.calls :as tcalls]
            [seon.transactor.merge :as tmerge]))

(defn- reply! [& args] (apply tmerge/reply! args))

(defn- db-notice [& args] (apply tmerge/db-notice args))

(defn- focus-tx [scope]
  (when scope
    (code/ns-parent-chain-tx scope)))

(defn- sync-runtime-and-fork-parent! [& args] (apply tmerge/sync-runtime-and-fork-parent! args))

(defn- handle-merge-op! [& args] (apply tmerge/handle-merge-op! args))

(defn transactor-proc
  {:malli/schema [:function [:=> [:cat] :any] [:=> [:cat :any] :any] [:=> [:cat :any :any] :any] [:=> [:cat :any :any :any] :any]]}
  ([] {:params {:conn "Datahike :main connection"
                :cfg "Pure store cfg"
                :repo-dir "Git checkout path"
                :commit-git? "Whether merges write back & commit to local git"
                :fork-out "Channel to 1-thread System Fork Parent"}
       :ins {:tx "Write operations for :main"}
       :outs {:db "Linear stream of :main commit notices {:prev-commit :commit :tx :reason}"
              :git "Write-back & branch-sync commands for :git proc"}
       :workload :mixed
       :doc "Sole writer of :main; enforces Malli rails, automatic Datahike schema sync, and 3-way merges."
       :ping-map-fn (fn [st]
                      {:main-tx (:last-tx st)
                       :commit (str (:last-commit st))
                       :commits-written (:writes st)
                       :msg-seq (:msg-seq st)})})
  ([{:keys [conn cfg repo-dir commit-git? fork-out]}]
   (let [boot-ch (a/chan 1)
         msg-count (count (d/datoms @conn :aevt :seon.agent.message/id))]
     (a/put! boot-ch {:op :boot-init})
     {:conn conn
      :cfg cfg
      :repo-dir repo-dir
      :commit-git? (boolean commit-git?)
      :fork-out fork-out
      :writes 0
      :msg-seq msg-count
      :last-commit (d/commit-id @conn)
      :last-tx (:max-tx @conn)
      ::flow/in-ports {:boot boot-ch}}))
  ([st transition]
   (when (= transition ::flow/stop)
     (try (store/sync! (:cfg st)) (catch Throwable _)))
   st)
  ([st _ msg]
   (if (nil? msg)
     [st nil]
     (let [{:keys [conn cfg repo-dir fork-out]} st
           prev-cid (d/commit-id @conn)]
       (try
         (case (:op msg)
           :boot-init
           (let [db @conn
                 cid (d/commit-id db)
                 all-forms (->> (d/datoms db :aevt :seon.code.form/key)
                                (map #(d/pull db '[:seon.code.form/key :seon.code.form/ns :seon.code.form/order
                                                   :seon.code.form/src :seon.code.form/deps] (:e %)))
                                code/sort-forms-topologically)
                 unloaded (filterv #(nil? (find-ns (:seon.code.form/ns %))) all-forms)]
             (when (seq unloaded)
               (fork/apply-form-overlay! db {:upserts unloaded :retracted-keys #{}})
               (when fork-out
                 (a/put! fork-out {:op :advance-image :commit cid :upserts unloaded :retracted-keys #{}})))
             [(assoc st :last-commit cid :last-tx (:max-tx db))
              {:db [{:prev-commit nil :commit cid :tx (:max-tx db) :reason :boot-init}]
               ::flow/report [(str "[:transactor] ready on :main @ tx " (:max-tx db))]}])

           :transact
           (let [r (d/transact conn {:tx-data (:tx-data msg)})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (sync-runtime-and-fork-parent! conn fork-out prev-cid cid)
             (reply! msg {:ok? true :commit cid :tx (:max-tx db)})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
              {:db [(db-notice prev-cid db (:tx-data r) (:reason msg :transact))]}])

           :register-schema
           (let [{:keys [sample entity dh-decls]} (schema/validate-schema-registration! @conn msg)
                 r1 (when (seq dh-decls)
                      (d/transact conn {:tx-data dh-decls}))
                 r (d/transact conn {:tx-data [entity]})
                 db (:db-after r)
                 cid (d/commit-id db)
                 all-ds (concat (:tx-data r1) (:tx-data r))]
             (reply! msg {:ok? true
                          :key (:seon.schema/key entity)
                          :sample (pr-str sample)
                          :derived-attributes (mapv :db/ident dh-decls)
                          :tx (:max-tx db)})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
             (cond-> {:db [(db-notice prev-cid db all-ds [:register-schema (:seon.schema/key entity)])]
                      ::flow/report [(str "[:transactor] registered Malli schema " (:seon.schema/key entity)
                                          (when (seq dh-decls) (str " + Datahike attr " (mapv :db/ident dh-decls)))
                                          " (seed=42 sample: " (pr-str sample) ")")]}
               (and (:commit-git? st) repo-dir)
               (assoc :git [{:op :write-back :branch :main :commit cid
                             :touched-paths #{"resources/seon/schemas.edn"}
                             :message (str "Register schema " (:seon.schema/key entity))}]))])

           :set-config
           (let [{:keys [agent-id key value]} msg
                 target (if agent-id
                          {:seon.agent/id agent-id key value}
                          {:seon.config/key :seon.config/root key value})
                 r (d/transact conn {:tx-data [target]})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :target (or agent-id :seon.config/root) :key key :value value :tx (:max-tx db)})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
             (cond-> {:db [(db-notice prev-cid db (:tx-data r) [:set-config (or agent-id :seon.config/root) key])]}
               (and (:commit-git? st) repo-dir (nil? agent-id))
               (assoc :git [{:op :write-back :branch :main :commit cid
                             :touched-paths #{"resources/seon/config/default.edn"}
                             :message (str "Update config " key)}]))])

           :create-task
           (let [{:keys [id scope title plan needs tests spawn-agent? agent-id role auto-merge?
                         parent template? template owner created-by summary parent-agent]} msg
                 db0 @conn
                 aid (or agent-id (keyword (namespace id) (name id)))
                 b (keyword "branch" (str (namespace aid) "." (name aid)))
                 mseq (inc (:msg-seq st))
                 ns-tx (when (and spawn-agent? scope) (focus-tx scope))
                 has-ext? (contains? (:schema db0) :seon.task/parent)
                 tx-data (cond-> (vec (concat ns-tx
                                              [(cond-> {:seon.task/id id
                                                       :seon.task/title (or title (name id))
                                                       :seon.task/plan (or plan "")
                                                       :seon.task/done? false}
                                                scope (assoc :seon.task/scope scope)
                                                (and has-ext? (some? template?)) (assoc :seon.task/template? (boolean template?))
                                                (and has-ext? template (qualified-keyword? template)) (assoc :seon.task/template template)
                                                (and has-ext? (or owner (when spawn-agent? aid))) (assoc :seon.task/owner (or owner aid))
                                                (and has-ext? created-by) (assoc :seon.task/created-by created-by)
                                                (and has-ext? (string? summary)) (assoc :seon.task/summary summary))
                                              (when (seq needs) {:seon.task/id id :seon.task/needs (set needs)})
                                              (when (seq tests) {:seon.task/id id :seon.task/tests (set tests)})
                                              (when (and parent (qualified-keyword? parent) (not= parent id)
                                                         (first (d/datoms db0 :avet :seon.task/id parent))
                                                         (not (:seon.task/template? (d/pull db0 '[:seon.task/template?] [:seon.task/id parent]))))
                                                {:seon.task/id parent :seon.task/needs #{id}})]))
                           spawn-agent?
                           (conj (cond-> {:seon.agent/id aid
                                          :seon.agent/branch b
                                          :seon.agent/task id
                                          :seon.agent/role (or role :worker)
                                          :seon.agent/goal (or title (name id))}
                                   parent-agent (assoc :seon.agent/parent parent-agent)
                                   (some? auto-merge?) (assoc :seon.agent/auto-merge? (boolean auto-merge?))
                                   scope (assoc :seon.agent/scope scope
                                                :seon.agent/focus (str "ns/" scope)))
                                 {:seon.agent.message/id (random-uuid)
                                  :seon.agent.message/seq mseq
                                  :seon.agent.message/from (or parent-agent :user)
                                  :seon.agent.message/to aid
                                  :seon.agent.message/type :task
                                  :seon.agent.message/body (str (or title (name id)) "\n" (or plan ""))}))
                 clean-tx (vec (remove nil? tx-data))
                 r (d/transact conn {:tx-data clean-tx})
                 _ (when spawn-agent? (store/branch! conn :main b))
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :task id :agent (when spawn-agent? aid) :branch (when spawn-agent? b) :tx (:max-tx db)})
             [(-> st
                 (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq (if spawn-agent? mseq (:msg-seq st)))
                 (update :writes inc))
              (cond-> {:db [(db-notice prev-cid db (:tx-data r) [:create-task id])]
                       ::flow/report [(str "[:transactor] created task " id (when spawn-agent? (str " + agent " aid " on " b)))]}
                (and (:commit-git? st) repo-dir)
                (assoc :git [{:op :write-back :branch :main :commit cid
                              :touched-paths #{"resources/seon/tasks.edn"}
                              :message (str "Create task " id)}]))])

           :update-task
           (let [{:keys [id title plan scope parent needs tests summary done? owner]} msg
                 db0 @conn]
             (if-not (and id (first (d/datoms db0 :avet :seon.task/id id)))
               (do (reply! msg {:ok? false :error (str "Task not found: " id)})
                   [st nil])
               (let [has-ext? (contains? (:schema db0) :seon.task/parent)
                     ent (cond-> {:seon.task/id id}
                         (string? title) (assoc :seon.task/title title)
                         (string? plan) (assoc :seon.task/plan plan)
                         scope (assoc :seon.task/scope scope)
                         (some? done?) (assoc :seon.task/done? (boolean done?))
                         (and has-ext? (string? summary)) (assoc :seon.task/summary summary)
                         (and has-ext? owner (qualified-keyword? owner)) (assoc :seon.task/owner owner))
                     tx (vec (remove nil? [ent
                                           (when (seq needs) {:seon.task/id id :seon.task/needs (set needs)})
                                           (when (seq tests) {:seon.task/id id :seon.task/tests (set tests)})]))
                     r (d/transact conn {:tx-data tx})
                     db (:db-after r)
                     cid (d/commit-id db)]
                 (reply! msg {:ok? true :task id :tx (:max-tx db)})
                 [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
                  (cond-> {:db [(db-notice prev-cid db (:tx-data r) [:update-task id])]
                           ::flow/report [(str "[:transactor] updated task " id)]}
                    (and (:commit-git? st) repo-dir)
                    (assoc :git [{:op :write-back :branch :main :commit cid
                                  :touched-paths #{"resources/seon/tasks.edn"}
                                  :message (str "Update task " id)}]))])))

           :spawn-agent
           (let [{:keys [id parent scope branch role goal task config auto-merge?]} msg
                 db0 @conn
                 b (or branch (keyword "branch" (str (or (namespace id) "agent") "." (name id))))
                 mseq (inc (:msg-seq st))
                 ns-tx (when scope (focus-tx scope))
                 matched-task (or task (tcalls/best-task-for-scope db0 scope))
                 auto-sub (when (and (nil? matched-task)
                                     (first (d/datoms db0 :avet :seon.task/id :template/discover-and-plan)))
                            (tcalls/build-subtask-tx db0 (or parent :user) id (random-uuid) scope
                                                     {:title (or (not-empty (str goal)) (str "Discover & plan " (or scope id)))
                                                      :plan (str (or goal ""))
                                                      :from-template :template/discover-and-plan
                                                      :role role}))
                 eff-task (or matched-task (:task-id auto-sub))
                 tx-data (vec
                          (concat
                           ns-tx
                           (:tx auto-sub)
                           (remove nil?
                                   [(cond-> (merge {:seon.agent/id id
                                                    :seon.agent/branch b
                                                    :seon.agent/role (or role :worker)
                                                    :seon.agent/goal (or goal "")}
                                                   config)
                                      (some? auto-merge?) (assoc :seon.agent/auto-merge? (boolean auto-merge?))
                                      parent (assoc :seon.agent/parent parent)
                                      scope  (assoc :seon.agent/scope scope
                                                    :seon.agent/focus (str "ns/" scope))
                                      eff-task (assoc :seon.agent/task eff-task))
                                    (when (seq (str goal))
                                      {:seon.agent.message/id (random-uuid)
                                       :seon.agent.message/seq mseq
                                       :seon.agent.message/from (or parent :user)
                                       :seon.agent.message/to id
                                       :seon.agent.message/type :delegate
                                       :seon.agent.message/body (str goal)})])))
                 r (d/transact conn {:tx-data tx-data})
                 _ (store/branch! conn :main b)
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg (cond-> {:ok? true :agent id :branch b :scope scope :tx (:max-tx db)}
                           eff-task (assoc :task eff-task)))
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq mseq) (update :writes inc))
              {:db [(db-notice prev-cid db (:tx-data r) [:spawn-agent id])]
               ::flow/report [(str "[:transactor] branched " b " for agent " id (when eff-task (str " (task " eff-task ")")))]}])

           :send-message
           (let [mseq (inc (:msg-seq st))
                 mid (random-uuid)
                 r (d/transact conn {:tx-data [{:seon.agent.message/id mid
                                                :seon.agent.message/seq mseq
                                                :seon.agent.message/from (:from msg :user)
                                                :seon.agent.message/to (:to msg)
                                                :seon.agent.message/type (:type msg :chat)
                                                :seon.agent.message/body (str (:body msg ""))}]})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :message-id mid :seq mseq :tx (:max-tx db)})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq mseq) (update :writes inc))
              {:db [(db-notice prev-cid db (:tx-data r) [:message (:from msg) (:to msg)])]
               ::flow/report [(str "[:transactor] message " (:from msg) " -> " (:to msg) " [" (name (:type msg :chat)) "]")]}])

           (:create-call :await-calls :answer-call :fail-call :cancel-call :settle-calls)
           (tcalls/handle-call-op! st msg prev-cid {:reply! reply! :db-notice db-notice :focus-tx focus-tx})

           :show-user
           (let [{:keys [user-id viewing mode sidebar? toggle-sidebar? toggle-expand expand selected-branch as-of-tx theme toggle-theme?]} msg
                 uid (or user-id :user/default)
                 cur (if (first (d/datoms @conn :avet :seon.user/id uid))
                       (d/pull @conn '[:seon.user/id :seon.user/sidebar-open? :seon.user/expanded-agents
                                       :seon.user/as-of-tx :seon.user/theme {:seon.user/viewing [:seon.agent/id]}]
                               [:seon.user/id uid])
                       {})
                 cur-viewing (get-in cur [:seon.user/viewing :seon.agent/id])
                 viewing-ref (when viewing
                               (let [aid (cond
                                           (= viewing :entry)
                                           (or (when (contains? (:schema @conn) :seon.agent/entry?)
                                                 (some-> (first (d/datoms @conn :avet :seon.agent/entry? true))
                                                         :e
                                                         (#(:seon.agent/id (d/pull @conn '[:seon.agent/id] %)))))
                                               :agent/root)
                                           (vector? viewing) (second viewing)
                                           :else viewing)]
                                 (when (first (d/datoms @conn :avet :seon.agent/id aid))
                                   [:seon.agent/id aid])))
                 switching-agent? (and viewing-ref (not= (second viewing-ref) cur-viewing))
                 has-as-of-key? (contains? msg :as-of-tx)
                 clear-as-of? (and (:seon.user/as-of-tx cur)
                                   (or (and has-as-of-key? (or (nil? as-of-tx) (= :live as-of-tx)))
                                       (and switching-agent? (not has-as-of-key?))))
                 retract-ops (cond-> []
                               (and toggle-expand (contains? (set (:seon.user/expanded-agents cur)) toggle-expand))
                               (conj [:db/retract [:seon.user/id uid] :seon.user/expanded-agents toggle-expand])
                               clear-as-of?
                               (conj [:db/retract [:seon.user/id uid] :seon.user/as-of-tx (:seon.user/as-of-tx cur)]))
                 add-exp (cond-> #{}
                           (and toggle-expand (not (contains? (set (:seon.user/expanded-agents cur)) toggle-expand)))
                           (conj toggle-expand)
                           (seq expand) (into expand))
                 nxt-theme (cond
                             (contains? #{:auto :dark :light} theme) theme
                             toggle-theme? (if (= :light (:seon.user/theme cur)) :dark :light)
                             :else nil)
                 ent (cond-> {:seon.user/id uid}
                       viewing-ref (assoc :seon.user/viewing viewing-ref)
                       mode (assoc :seon.user/view-mode mode)
                       (some? sidebar?) (assoc :seon.user/sidebar-open? (boolean sidebar?))
                       toggle-sidebar? (assoc :seon.user/sidebar-open? (not (boolean (:seon.user/sidebar-open? cur))))
                       (seq add-exp) (assoc :seon.user/expanded-agents add-exp)
                       selected-branch (assoc :seon.user/selected-branch selected-branch)
                       (integer? as-of-tx) (assoc :seon.user/as-of-tx (long as-of-tx))
                       nxt-theme (assoc :seon.user/theme nxt-theme))
                 tx-data (vec (concat retract-ops [ent]))
                 r (d/transact conn {:tx-data tx-data})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :user-id uid :tx (:max-tx db)})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
              {:db [(db-notice prev-cid db (:tx-data r) [:show-user uid])]}])

           :record-summary
           (let [rec-fn (requiring-resolve 'seon.render.chronicle/record-summary!)
                 spec (assoc (or (:spec msg) {}) :agent (or (:agent (:spec msg)) (:agent-id msg) :agent/root))
                 res (rec-fn conn spec)
                 db @conn
                 cid (d/commit-id db)]
             (reply! msg (dissoc res :tx-data))
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
              (when (:ok? res)
                {:db [(db-notice prev-cid db (:tx-data res) [:record-summary (:id res)])]})])

           :record-test-results
           (let [{:keys [results branch commit agent-id tested-tx ran cached reply-ch reply-val]} msg
                 all-pass? (every? :seon.test/pass? results)
                 failed-dgs (into #{} (keep #(when-not (:seon.test/pass? %) (:seon.test/digest %))) results)
                 prev-failed (when branch
                               (set (:seon.test.run/failed-digests
                                     (d/pull @conn '[:seon.test.run/failed-digests] [:seon.test.run/branch branch]))))
                 retract-old (when (and branch (seq prev-failed))
                               (mapv (fn [dg] [:db/retract [:seon.test.run/branch branch] :seon.test.run/failed-digests dg])
                                     (remove failed-dgs prev-failed)))
                 run-ent (when branch
                           (cond-> {:seon.test.run/branch branch
                                    :seon.test.run/pass? (boolean all-pass?)
                                    :seon.test.run/ran (long (or ran (count results)))
                                    :seon.test.run/cached (long (or cached 0))}
                             commit (assoc :seon.test.run/commit commit)
                             agent-id (assoc :seon.test.run/agent agent-id)
                             (seq failed-dgs) (assoc :seon.test.run/failed-digests failed-dgs)))
                 tx-data (cond-> (vec (concat retract-old results (when run-ent [run-ent])))
                           (and agent-id tested-tx all-pass?)
                           (conj {:seon.agent/id agent-id :seon.agent/tested-tx (long tested-tx)}))
                 r (if (seq tx-data) (d/transact conn {:tx-data tx-data}) {:db-after @conn :tx-data []})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (when reply-ch
               (reply! {:reply-ch reply-ch} reply-val))
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
              {:db [(db-notice prev-cid db (:tx-data r) [:test-results (or branch agent-id)])]}])

           :record-fault
           (let [fid (or (:id msg) (random-uuid))
                 ent (cond-> {:seon.health.fault/id fid
                              :seon.health.fault/class (or (:class msg) :proc-fault)
                              :seon.health.fault/severity (or (:severity msg) :error)
                              :seon.health.fault/subject (str (or (:subject msg) (:proc msg) :system))
                              :seon.health.fault/message (str (:message msg (:error msg "")))
                              :seon.health.fault/resolved? false}
                       (:proc msg) (assoc :seon.health.fault/proc (:proc msg))
                       (:ns msg) (assoc :seon.health.fault/ns (:ns msg))
                       (:branch msg) (assoc :seon.health.fault/branch (:branch msg)))
                 r (d/transact conn {:tx-data [ent]})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :fault-id fid})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
              {:db [(db-notice prev-cid db (:tx-data r) [:fault fid])]}])

           :retract-fault
           (let [fid (:id msg)
                 exists? (and fid (contains? (:schema @conn) :seon.health.fault/id)
                              (first (d/datoms @conn :avet :seon.health.fault/id fid)))]
             (if-not exists?
               (do (reply! msg {:ok? true :retracted? false :fault-id fid})
                   [st nil])
               (let [r (d/transact conn {:tx-data [[:db/retractEntity [:seon.health.fault/id fid]]]})
                     db (:db-after r)
                     cid (d/commit-id db)]
                 (reply! msg {:ok? true :retracted? true :fault-id fid})
                 [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
                  {:db [(db-notice prev-cid db (:tx-data r) [:retract-fault fid])]}])))

           :resolve-fault
           (let [r (d/transact conn {:tx-data [{:seon.health.fault/id (:id msg)
                                                :seon.health.fault/resolved? true}]})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :fault-id (:id msg)})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
              {:db [(db-notice prev-cid db (:tx-data r) [:resolve-fault (:id msg)])]}])

           :merge
           (handle-merge-op! st msg prev-cid)

           :set-git-commit
           (let [r (d/transact conn {:tx-data [{:seon.git/repo :main :seon.git/commit (:sha msg)}]})
                 db (:db-after r)
                 cid (d/commit-id db)]
             [(assoc st :last-commit cid :last-tx (:max-tx db)) nil])

           :toggle-pause-agent
           (let [id (:agent-id msg)
                 cur (boolean (:seon.agent/paused? (d/pull @conn '[:seon.agent/paused?] [:seon.agent/id id])))
                 r (d/transact conn {:tx-data [{:seon.agent/id id :seon.agent/paused? (not cur)}]})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :agent id :paused? (not cur)})
             [(assoc st :last-commit cid :last-tx (:max-tx db))
              {:db [(db-notice prev-cid db (:tx-data r) [:pause id])]}])

           (do (reply! msg {:ok? false :error (str "Unknown transactor op: " (:op msg))})
               [st nil]))
         (catch Throwable ex
           (reply! msg {:ok? false :error (.getMessage ex)})
           [st {::flow/error [{:proc :transactor :op (:op msg) :error (.getMessage ex)}]}]))))))