(ns seon.transactor
  "The `:transactor` Flow proc (`:mixed` workload):
   - Single serialized writer of `:main` on LMDB (`d/transact`, `store/branch!`, `merge/three-way`).
   - Hot-loads merged `:form/*` deltas into the Flow process AND sends `:advance-image` to the 1-thread
     System Fork Parent so forked agents & test workers always start 0 commits behind `:main`.
   - Emits `{:prev-commit :commit :tx :reason}` on `:db` so downstream procs run in O(|Delta|) via `d/changes`."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.fork :as fork]
            [seon.git :as git]
            [seon.merge :as merge]
            [seon.store :as store]))

(defn- reply! [msg val]
  (when-let [ch (:reply-ch msg)]
    (a/put! ch val)
    (a/close! ch)))

(defn- sync-runtime-and-fork-parent! [conn fork-out prev-cid new-cid]
  (when (and prev-cid new-cid (not= prev-cid new-cid))
    (let [delta (code/changed-form-ops conn prev-cid new-cid)]
      (when (or (seq (:upserts delta)) (seq (:retracted-keys delta)))
        (fork/apply-form-overlay! delta)
        (when fork-out
          (a/put! fork-out (assoc delta :op :advance-image :commit new-cid)))))))

(defn transactor-proc
  ([] {:params {:conn "Datahike :main connection"
                :cfg "Pure store cfg"
                :repo-dir "Git checkout path (nil for ephemeral test stores)"
                :commit-git? "Whether merges write back & commit to git main"
                :fork-out "Channel to 1-thread System Fork Parent"}
       :ins {:tx "Write operations for :main (:transact, :create-task, :spawn-agent, :send-message, :merge)"}
       :outs {:db "Linear stream of :main commit notices {:prev-commit :commit :tx :reason}"
              :git "Write-back commands for :git proc"}
       :workload :mixed
       :doc "Sole writer of :main; enforces Malli rails, 3-way merges, and O(|Delta|) runtime sync."
       :ping-map-fn (fn [st]
                      {:main-tx (:last-tx st)
                       :commit (str (:last-commit st))
                       :commits-written (:writes st)
                       :msg-seq (:msg-seq st)})})
  ([{:keys [conn cfg repo-dir commit-git? fork-out]}]
   (let [boot-ch (a/chan 1)
         msg-count (count (d/datoms @conn :aevt :message/id))]
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
                 all-forms (->> (d/datoms db :aevt :form/key)
                                (map #(d/pull db '[:form/key :form/ns :form/order :form/src :form/deps] (:e %)))
                                code/sort-forms-topologically)
                 unloaded (filterv #(nil? (find-ns (:form/ns %))) all-forms)]
             (when (seq unloaded)
               (fork/apply-form-overlay! {:upserts unloaded :retracted-keys #{}})
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
              {:db [{:prev-commit prev-cid :commit cid :tx (:max-tx db) :reason (:reason msg :transact)}]}])

           :create-task
           (let [{:keys [id title plan needs tests spawn-agent? agent-id role]} msg
                 aid (or agent-id (keyword (namespace id) (name id)))
                 b (keyword "branch" (str (namespace aid) "." (name aid)))
                 mseq (inc (:msg-seq st))
                 _ (when spawn-agent? (store/branch! conn :main b))
                 tx-data (cond-> [{:task/id id
                                   :task/title (or title (name id))
                                   :task/plan (or plan "")
                                   :task/done? false}
                                  (when (seq needs) {:task/id id :task/needs (vec needs)})
                                  (when (seq tests) {:task/id id :task/tests (vec tests)})]
                           spawn-agent?
                           (conj {:agent/id aid
                                  :agent/branch b
                                  :agent/task id
                                  :agent/role (or role :worker)
                                  :agent/goal (or title (name id))}
                                 {:message/id (random-uuid)
                                  :message/seq mseq
                                  :message/from :user
                                  :message/to aid
                                  :message/type :task
                                  :message/body (str (or title (name id)) "\n" (or plan ""))}))
                 clean-tx (vec (remove nil? tx-data))
                 r (d/transact conn {:tx-data clean-tx})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :task id :agent (when spawn-agent? aid) :branch (when spawn-agent? b) :tx (:max-tx db)})
             [(-> st
                  (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq (if spawn-agent? mseq (:msg-seq st)))
                  (update :writes inc))
              {:db [{:prev-commit prev-cid :commit cid :tx (:max-tx db) :reason [:create-task id]}]
               ::flow/report [(str "[:transactor] created task " id (when spawn-agent? (str " + agent " aid " on " b)))]}])

           :spawn-agent
           (let [{:keys [id parent branch role goal task]} msg
                 b (or branch (keyword "branch" (str (or (namespace id) "agent") "." (name id))))
                 _ (store/branch! conn :main b)
                 mseq (inc (:msg-seq st))
                 tx-data (vec
                          (remove nil?
                                  [(cond-> {:agent/id id
                                            :agent/branch b
                                            :agent/role (or role :worker)
                                            :agent/goal (or goal "")}
                                     parent (assoc :agent/parent parent)
                                     task   (assoc :agent/task task))
                                   (when (seq (str goal))
                                     {:message/id (random-uuid)
                                      :message/seq mseq
                                      :message/from (or parent :user)
                                      :message/to id
                                      :message/type :delegate
                                      :message/body (str goal)})]))
                 r (d/transact conn {:tx-data tx-data})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :agent id :branch b :tx (:max-tx db)})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq mseq) (update :writes inc))
              {:db [{:prev-commit prev-cid :commit cid :tx (:max-tx db) :reason [:spawn-agent id]}]
               ::flow/report [(str "[:transactor] branched " b " for agent " id)]}])

           :send-message
           (let [mseq (inc (:msg-seq st))
                 mid (random-uuid)
                 r (d/transact conn {:tx-data [{:message/id mid
                                                :message/seq mseq
                                                :message/from (:from msg :user)
                                                :message/to (:to msg)
                                                :message/type (:type msg :chat)
                                                :message/body (str (:body msg ""))}]})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :message-id mid :seq mseq :tx (:max-tx db)})
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq mseq) (update :writes inc))
              {:db [{:prev-commit prev-cid :commit cid :tx (:max-tx db) :reason [:message (:from msg) (:to msg)]}]
               ::flow/report [(str "[:transactor] message " (:from msg) " -> " (:to msg) " [" (name (:type msg :chat)) "]")]}])

           :record-test-results
           (let [{:keys [results agent-id tested-tx reply-ch reply-val]} msg
                 tx-data (cond-> (vec results)
                           (and agent-id tested-tx (every? :test/pass? results))
                           (conj {:agent/id agent-id :agent/tested-tx (long tested-tx)}))
                 r (if (seq tx-data) (d/transact conn {:tx-data tx-data}) {:db-after @conn})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (when reply-ch
               (reply! {:reply-ch reply-ch} reply-val))
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
              {:db [{:prev-commit prev-cid :commit cid :tx (:max-tx db) :reason [:test-results agent-id]}]}])

           :merge
           (let [{:keys [branch agent-id branch-tx message]} msg
                 plan (merge/three-way conn cfg branch)]
             (if-not (:ok? plan)
               (do (reply! msg {:ok? false :conflicts (:conflicts plan)})
                   [st {::flow/report [(str "[:transactor] merge CONFLICT on " branch ": " (mapv :entity (:conflicts plan)))]}])
               (let [old-git (:git/commit (d/pull @conn '[:git/commit] [:git/repo :main]))
                     agent-ent (when agent-id (d/pull @conn '[:agent/task] [:agent/id agent-id]))
                     tx-data (cond-> (:tx-data plan)
                               (and agent-id branch-tx)
                               (conj {:agent/id agent-id :agent/ready? true :agent/merged-tx (long branch-tx)})
                               (:agent/task agent-ent)
                               (conj {:task/id (:agent/task agent-ent) :task/done? true}))
                     r (if (seq tx-data) (d/transact conn {:tx-data tx-data}) {:db-after @conn})
                     db (:db-after r)
                     cid (d/commit-id db)
                     _ (sync-runtime-and-fork-parent! conn fork-out prev-cid cid)
                     _ (store/sync! cfg)
                     ans {:ok? true
                          :branch branch
                          :commit cid
                          :tx (:max-tx db)
                          :touched-paths (:touched-paths plan)}]
                 (reply! msg ans)
                 [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
                  (cond-> {:db [{:prev-commit prev-cid :commit cid :tx (:max-tx db) :reason [:merged branch]}]
                           ::flow/report [(str "[:transactor] MERGED " branch " -> :main @ tx " (:max-tx db)
                                               " (" (count (:touched-paths plan)) " files)")]}
                    (and (:commit-git? st) repo-dir (seq (:touched-paths plan)))
                    (assoc :git [{:op :write-back
                                  :commit cid
                                  :expected-sha old-git
                                  :touched-paths (:touched-paths plan)
                                  :message (or message (str "Merge " branch))}]))])))

           :set-git-commit
           (let [r (d/transact conn {:tx-data [{:git/repo :main :git/commit (:sha msg)}]})
                 db (:db-after r)
                 cid (d/commit-id db)]
             [(assoc st :last-commit cid :last-tx (:max-tx db)) nil])

           :toggle-pause-agent
           (let [id (:agent-id msg)
                 cur (boolean (:agent/paused? (d/pull @conn '[:agent/paused?] [:agent/id id])))
                 r (d/transact conn {:tx-data [{:agent/id id :agent/paused? (not cur)}]})
                 db (:db-after r)
                 cid (d/commit-id db)]
             (reply! msg {:ok? true :agent id :paused? (not cur)})
             [(assoc st :last-commit cid :last-tx (:max-tx db))
              {:db [{:prev-commit prev-cid :commit cid :tx (:max-tx db) :reason [:pause id]}]}])

           ;; Unknown op
           (do (reply! msg {:ok? false :error (str "Unknown transactor op: " (:op msg))})
               [st nil]))
         (catch Throwable ex
           (reply! msg {:ok? false :error (.getMessage ex)})
           [st {::flow/error [{:proc :transactor :op (:op msg) :error (.getMessage ex)}]}]))))))
