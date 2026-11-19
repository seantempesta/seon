(ns seon.transactor
  "The `:transactor` Flow proc (`:mixed` workload):
   - Single serialized writer of `:main` on LMDB (`d/transact`, `store/branch!`, `merge/three-way`).
   - Automatically installs derived Datahike `:db/ident` declarations when Malli schemas register or merge!
   - Hot-loads merged `:seon.code.form/*` deltas into Flow AND sends `:advance-image` to the 1-thread
     System Fork Parent so forked agents & test workers always start 0 commits behind `:main`."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.fork :as fork]
            [seon.merge :as merge]
            [seon.schema :as schema]
            [seon.store :as store]))

(defn- reply! [msg val]
  (when-let [ch (:reply-ch msg)]
    (a/put! ch val)
    (a/close! ch)))

(defn- db-notice [prev-cid db tx-data reason]
  {:prev-commit prev-cid
   :commit (d/commit-id db)
   :tx (:max-tx db)
   :tx-data (vec tx-data)
   :reason reason})

(defn- focus-tx [scope]
  (when scope
    (code/ns-parent-chain-tx scope)))

(defn- sync-runtime-and-fork-parent! [conn fork-out prev-cid new-cid]
  (when (and prev-cid new-cid (not= prev-cid new-cid))
    (let [delta (code/changed-form-ops conn prev-cid new-cid)]
      (when (or (seq (:upserts delta)) (seq (:retracted-keys delta)))
        (fork/apply-form-overlay! @conn delta)
        (when fork-out
          (a/put! fork-out (assoc (select-keys delta [:upserts :retracted-keys])
                                  :op :advance-image
                                  :commit new-cid)))))))

(defn transactor-proc
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
              {:db [(db-notice prev-cid db all-ds [:register-schema (:seon.schema/key entity)])]
               ::flow/report [(str "[:transactor] registered Malli schema " (:seon.schema/key entity)
                                   (when (seq dh-decls) (str " + Datahike attr " (mapv :db/ident dh-decls)))
                                   " (seed=42 sample: " (pr-str sample) ")")]}])

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
              {:db [(db-notice prev-cid db (:tx-data r) [:set-config (or agent-id :seon.config/root) key])]}])

           :create-task
           (let [{:keys [id scope title plan needs tests spawn-agent? agent-id role auto-merge?]} msg
                 aid (or agent-id (keyword (namespace id) (name id)))
                 b (keyword "branch" (str (namespace aid) "." (name aid)))
                 mseq (inc (:msg-seq st))
                 ns-tx (when (and spawn-agent? scope) (focus-tx scope))
                 tx-data (cond-> (vec (concat ns-tx
                                              [(cond-> {:seon.task/id id
                                                        :seon.task/title (or title (name id))
                                                        :seon.task/plan (or plan "")
                                                        :seon.task/done? false}
                                                 scope (assoc :seon.task/scope scope))
                                               (when (seq needs) {:seon.task/id id :seon.task/needs (set needs)})
                                               (when (seq tests) {:seon.task/id id :seon.task/tests (set tests)})]))
                           spawn-agent?
                           (conj (cond-> {:seon.agent/id aid
                                          :seon.agent/branch b
                                          :seon.agent/task id
                                          :seon.agent/role (or role :worker)
                                          :seon.agent/goal (or title (name id))}
                                   (some? auto-merge?) (assoc :seon.agent/auto-merge? (boolean auto-merge?))
                                   scope (assoc :seon.agent/scope scope
                                                :seon.agent/focus (str "ns/" scope)))
                                 {:seon.agent.message/id (random-uuid)
                                  :seon.agent.message/seq mseq
                                  :seon.agent.message/from :user
                                  :seon.agent.message/to aid
                                  :seon.agent.message/type :task
                                  :seon.agent.message/body (str (or title (name id)) "\n" (or plan ""))}))
                 clean-tx (vec (remove nil? tx-data))
                 r (d/transact conn {:tx-data clean-tx})
                 ;; Branch AFTER transacting agent + focus namespace so `b` inherits its own entity & focus cleanly!
                 _ (when spawn-agent? (store/branch! conn :main b))
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :task id :agent (when spawn-agent? aid) :branch (when spawn-agent? b) :tx (:max-tx db)})
             [(-> st
                  (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq (if spawn-agent? mseq (:msg-seq st)))
                  (update :writes inc))
              {:db [(db-notice prev-cid db (:tx-data r) [:create-task id])]
               ::flow/report [(str "[:transactor] created task " id (when spawn-agent? (str " + agent " aid " on " b)))]}])

           :spawn-agent
           (let [{:keys [id parent scope branch role goal task config auto-merge?]} msg
                 b (or branch (keyword "branch" (str (or (namespace id) "agent") "." (name id))))
                 mseq (inc (:msg-seq st))
                 ns-tx (when scope (focus-tx scope))
                 tx-data (vec
                          (concat
                           ns-tx
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
                                      task   (assoc :seon.agent/task task))
                                    (when (seq (str goal))
                                      {:seon.agent.message/id (random-uuid)
                                       :seon.agent.message/seq mseq
                                       :seon.agent.message/from (or parent :user)
                                       :seon.agent.message/to id
                                       :seon.agent.message/type :delegate
                                       :seon.agent.message/body (str goal)})])))
                 r (d/transact conn {:tx-data tx-data})
                 ;; Branch AFTER transacting agent + focus namespace so `b` inherits its own entity & focus cleanly!
                 _ (store/branch! conn :main b)
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :agent id :branch b :scope scope :tx (:max-tx db)})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq mseq) (update :writes inc))
              {:db [(db-notice prev-cid db (:tx-data r) [:spawn-agent id])]
               ::flow/report [(str "[:transactor] branched " b " for agent " id)]}])

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
                              :seon.health.fault/subject (str (or (:subject msg) (:proc msg) :system))
                              :seon.health.fault/message (str (:message msg (:error msg "")))
                              :seon.health.fault/resolved? false}
                       (:proc msg) (assoc :seon.health.fault/proc (:proc msg))
                       (:branch msg) (assoc :seon.health.fault/branch (:branch msg)))
                 r (d/transact conn {:tx-data [ent]})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :fault-id fid})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
              {:db [(db-notice prev-cid db (:tx-data r) [:fault fid])]}])

           :resolve-fault
           (let [r (d/transact conn {:tx-data [{:seon.health.fault/id (:id msg)
                                                :seon.health.fault/resolved? true}]})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :fault-id (:id msg)})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
              {:db [(db-notice prev-cid db (:tx-data r) [:resolve-fault (:id msg)])]}])

           :merge
           (let [{:keys [branch agent-id branch-tx message]} msg
                 plan (merge/three-way conn cfg branch)]
             (if-not (:ok? plan)
               (do (reply! msg {:ok? false :conflicts (:conflicts plan)})
                   [st {::flow/report [(str "[:transactor] merge CONFLICT on " branch ": " (:conflicts plan))]}])
               (let [old-git (:seon.git/commit (d/pull @conn '[:seon.git/commit] [:seon.git/repo :main]))
                     agent-ent (when agent-id (d/pull @conn '[:seon.agent/task] [:seon.agent/id agent-id]))
                     r1 (when (seq (:schema-tx plan))
                          (d/transact conn {:tx-data (:schema-tx plan)}))
                     schema-ops (filterv #(and (map? %) (contains? % :seon.schema/key)) (:tx-data plan))
                     rest-ops   (filterv #(not (and (map? %) (contains? % :seon.schema/key))) (:tx-data plan))
                     r2 (when (seq schema-ops)
                          (d/transact conn {:tx-data schema-ops}))
                     tx-data (cond-> rest-ops
                               (and agent-id branch-tx)
                               (conj {:seon.agent/id agent-id
                                      :seon.agent/ready? true
                                      :seon.agent/merged-tx (long branch-tx)})
                               (:seon.agent/task agent-ent)
                               (conj {:seon.task/id (:seon.agent/task agent-ent) :seon.task/done? true}))
                     r3 (when (seq tx-data)
                          (d/transact conn {:tx-data tx-data}))
                     ;; Step 3: Update co-located `resources/seon/schemas/*.edn` file text if any schema moved
                     touched-sch-paths (->> (:entity-diffs plan)
                                            (keep #(when (= :seon.schema/key (first (:entity %))) (:path %)))
                                            distinct)
                     r4 (doall
                         (for [sp touched-sch-paths
                               :let [rendered (code/render-schema-file @conn sp)]
                               :when rendered]
                           (d/transact conn {:tx-data [{:seon.code.file/path sp
                                                        :seon.code.file/text rendered
                                                        :seon.code.file/exec? false}]})))
                     all-ds (concat (:tx-data r1) (:tx-data r2) (:tx-data r3) (mapcat :tx-data r4))
                     db @conn
                     cid (d/commit-id db)
                     _ (sync-runtime-and-fork-parent! conn fork-out prev-cid cid)
                     _ (store/sync! cfg)
                     ans {:ok? true
                          :branch branch
                          :commit cid
                          :tx (:max-tx db)
                          :touched-paths (:touched-paths plan)
                          :entity-diffs (:entity-diffs plan)}]
                 (reply! msg ans)
                 [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
                  (cond-> {:db [(db-notice prev-cid db all-ds [:merged branch])]
                           ::flow/report [(str "[:transactor] MERGED " branch " -> :main @ tx " (:max-tx db)
                                               " (" (count (:entity-diffs plan)) " entities, "
                                               (count (:touched-paths plan)) " files)")]}
                    (and (:commit-git? st) repo-dir (seq (:touched-paths plan)))
                    (assoc :git [{:op :write-back
                                  :branch branch
                                  :commit cid
                                  :expected-sha old-git
                                  :touched-paths (:touched-paths plan)
                                  :message (or message (str "Merge " branch))}]))])))

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
