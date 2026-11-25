(ns seon.transactor
  "The `:transactor` Flow proc (`:mixed` workload): sole serialized writer of `:main` on LMDB,
   automatic Malli -> Datahike schema sync, hot-load overlay, and 3-way merges."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.error :as serr]
            [seon.fork :as fork]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.transactor.calls :as tcalls]
            [seon.transactor.merge :as tmerge]
            [seon.transactor.user :as user]
            [seon.transactor.spawn :as spawn]))

(defn- focus-tx [& args] (apply spawn/focus-tx args))

(defn- ^:seon.flow/edge sync-store-best-effort! [cfg] (try (store/sync! cfg) (catch Throwable _ nil)))

(defn- msg-ent [& args] (apply spawn/msg-ent args))

(defn- commit-res [& args] (apply spawn/commit-res args))

(defn- git-wb [& args] (apply spawn/git-wb args))

(defn- show-user-tx [& args] (apply user/show-user-tx args))

(defn- handle-boot-init! [st]
  (let [{:keys [conn fork-out]} st db @conn cid (d/commit-id db)
        all-forms (->> (d/datoms db :aevt :seon.code.form/key)
                       (map #(d/pull db '[:seon.code.form/key :seon.code.form/ns :seon.code.form/order
                                          :seon.code.form/src :seon.code.form/deps] (:e %)))
                       code/sort-forms-topologically)
        unloaded (filterv #(nil? (find-ns (:seon.code.form/ns %))) all-forms)]
    (when (seq unloaded)
      (fork/apply-form-overlay! db {:upserts unloaded :retracted-keys #{}})
      (when fork-out (a/put! fork-out {:op :advance-image :commit cid :upserts unloaded :retracted-keys #{}})))
    [(assoc st :last-commit cid :last-tx (:max-tx db))
     {:db [{:prev-commit nil :commit cid :tx (:max-tx db) :reason :boot-init}]
      ::flow/report [(str "[:transactor] ready on :main @ tx " (:max-tx db))]}]))

(defn- handle-register-schema! [st msg prev-cid]
  (let [conn (:conn st)
        {:keys [sample entity dh-decls retract-ops]} (schema/validate-schema-registration! @conn msg)
        r1 (when (seq dh-decls) (d/transact conn {:tx-data dh-decls}))
        r (d/transact conn {:tx-data (vec (concat retract-ops [entity]))})
        db (:db-after r) cid (d/commit-id db) k (:seon.schema/key entity)]
    (tmerge/reply! msg {:ok? true :key k :sample (pr-str sample) :derived-attributes (mapv :db/ident dh-decls) :tx (:max-tx db)})
    (commit-res st prev-cid (assoc r :tx-data (concat (:tx-data r1) (:tx-data r))) [:register-schema k]
                (merge {::flow/report [(str "[:transactor] registered Malli schema " k
                                            (when (seq dh-decls) (str " + Datahike attr " (mapv :db/ident dh-decls)))
                                            " (seed=42 sample: " (pr-str sample) ")")]}
                       (git-wb st cid "resources/seon/schemas.edn" (str "Register schema " k))))))

(defn- tx! [& args] (apply spawn/tx! args))

(defn- handle-fault-op! [st msg prev-cid]
  (let [conn (:conn st)]
    (case (:op msg)
      :record-fault
      (let [fid (or (:id msg) (random-uuid))
            data (cond-> (if (map? (:data msg)) (:data msg) {})
                   (:subject msg) (assoc :subject (str (:subject msg)))
                   (:ns msg) (assoc :ns (:ns msg)) (:proc msg) (assoc :proc (:proc msg)))
            ent (serr/error-tx (cond-> {:id fid :kind (or (:kind msg) (:class msg) :proc-fault)
                                        :severity (or (:severity msg) :error) :source (or (:source msg) (:proc msg) :system)
                                        :status :open :message (str (:message msg (:error msg "")))}
                                 (seq data) (assoc :data data) (:branch msg) (assoc :branch (:branch msg))
                                 (:agent msg) (assoc :agent (:agent msg))))
            r (tx! conn [ent])]
        (tmerge/reply! msg {:ok? true :fault-id fid})
        (commit-res st prev-cid r [:fault fid]))
      :retract-fault
      (let [fid (:id msg)]
        (if-not (and fid (contains? (:schema @conn) :seon.error/id) (first (d/datoms @conn :avet :seon.error/id fid)))
          (do (tmerge/reply! msg {:ok? true :retracted? false :fault-id fid}) [st nil])
          (let [r (d/transact conn {:tx-data [[:db/retractEntity [:seon.error/id fid]]]})]
            (tmerge/reply! msg {:ok? true :retracted? true :fault-id fid})
            (commit-res st prev-cid r [:retract-fault fid]))))
      :resolve-fault
      (let [r (d/transact conn {:tx-data [(serr/resolve-error-tx (:id msg))]})]
        (tmerge/reply! msg {:ok? true :fault-id (:id msg)})
        (commit-res st prev-cid r [:resolve-fault (:id msg)])))))

(defn- handle-test-results! [st msg prev-cid]
  (let [conn (:conn st)
        {:keys [results branch commit agent-id tested-tx ran cached reply-ch reply-val]} msg
        all-pass? (every? :seon.test/pass? results)
        failed-dgs (into #{} (keep #(when-not (:seon.test/pass? %) (:seon.test/digest %))) results)
        prev-failed (when branch (set (:seon.test.run/failed-digests
                                       (d/pull @conn '[:seon.test.run/failed-digests] [:seon.test.run/branch branch]))))
        retract-old (when (and branch (seq prev-failed))
                      (mapv #(vector :db/retract [:seon.test.run/branch branch] :seon.test.run/failed-digests %)
                            (remove failed-dgs prev-failed)))
        run-ent (when branch
                  (cond-> {:seon.test.run/branch branch :seon.test.run/pass? (boolean all-pass?)
                           :seon.test.run/ran (long (or ran (count results))) :seon.test.run/cached (long (or cached 0))}
                    commit (assoc :seon.test.run/commit commit) agent-id (assoc :seon.test.run/agent agent-id)
                    (seq failed-dgs) (assoc :seon.test.run/failed-digests failed-dgs)))
        cur-db @conn
        has-vr? (and (contains? (:schema cur-db) :seon.test/var-ref) (contains? (:schema cur-db) :seon.code.var/name))
        enriched (mapv (fn [rv]
                         (if-let [ve (when (and has-vr? (nil? (:seon.test/var-ref rv)) (:seon.test/var rv))
                                       (:e (first (d/datoms cur-db :avet :seon.code.var/name (:seon.test/var rv)))))]
                           (assoc rv :seon.test/var-ref ve) rv))
                       results)
        tx-data (cond-> (vec (concat retract-old enriched (when run-ent [run-ent])))
                  (and agent-id tested-tx all-pass?) (conj {:seon.agent/id agent-id :seon.agent/tested-tx (long tested-tx)}))
        r (if (seq tx-data) (tx! conn tx-data) {:db-after @conn :tx-data []})]
    (when reply-ch (tmerge/reply! {:reply-ch reply-ch} reply-val))
    (commit-res st prev-cid r [:test-results (or branch agent-id)])))

(defn- handle-spawn-on-entity! [& args] (apply spawn/handle-spawn-on-entity! args))

(defn- handle-spawn-agent! [& args] (apply spawn/handle-spawn-agent! args))

(defn- handle-update-task! [& args] (apply spawn/handle-update-task! args))

(defn- handle-create-task! [& args] (apply spawn/handle-create-task! args))

(defn transactor-proc
  {:malli/schema [:function [:=> [:cat] :any] [:=> [:cat :any] :any] [:=> [:cat :any :any] :any] [:=> [:cat :any :any :any] :any]]}
  ([] {:params {:conn "Datahike :main connection" :cfg "Pure store cfg" :repo-dir "Git checkout path"
                :commit-git? "Whether merges write back & commit to local git" :fork-out "Channel to 1-thread System Fork Parent"}
       :ins {:tx "Write operations for :main"}
       :outs {:db "Linear stream of :main commit notices {:prev-commit :commit :tx :reason}"
              :git "Write-back & branch-sync commands for :git proc"}
       :workload :mixed
       :doc "Sole writer of :main; enforces Malli rails, automatic Datahike schema sync, and 3-way merges."
       :ping-map-fn (fn [st] {:main-tx (:last-tx st) :commit (str (:last-commit st)) :commits-written (:writes st) :msg-seq (:msg-seq st)})})
  ([{:keys [conn cfg repo-dir commit-git? fork-out]}]
   (let [boot-ch (a/chan 1)]
     (a/put! boot-ch {:op :boot-init})
     {:conn conn :cfg cfg :repo-dir repo-dir :commit-git? (boolean commit-git?) :fork-out fork-out
      :writes 0 :msg-seq (count (d/datoms @conn :aevt :seon.agent.message/id))
      :last-commit (d/commit-id @conn) :last-tx (:max-tx @conn) ::flow/in-ports {:boot boot-ch}}))
  ([st transition]
   (when (= transition ::flow/stop) (sync-store-best-effort! (:cfg st)))
   st)
  ([st _ msg]
   (if (nil? msg)
     [st nil]
     (let [{:keys [conn fork-out]} st prev-cid (d/commit-id @conn)]
       (try
         (case (:op msg)
           :boot-init (handle-boot-init! st)
           :register-schema (handle-register-schema! st msg prev-cid)
           :create-task (handle-create-task! st msg prev-cid)
           :update-task (handle-update-task! st msg prev-cid)
           :spawn-agent (handle-spawn-agent! st msg prev-cid)
           :spawn-on-entity (handle-spawn-on-entity! st msg prev-cid)
           :record-test-results (handle-test-results! st msg prev-cid)
           (:record-fault :retract-fault :resolve-fault) (handle-fault-op! st msg prev-cid)
           :merge (tmerge/handle-merge-op! st msg prev-cid)
           :resume-reviewed-target (tmerge/handle-resume-reviewed-target! st msg prev-cid)
           (:create-call :await-calls :answer-call :fail-call :cancel-call :settle-calls)
           (tcalls/handle-call-op! st msg prev-cid {:reply! tmerge/reply! :db-notice tmerge/db-notice :focus-tx focus-tx})
           :transact
           (let [r (tx! conn (:tx-data msg)) db (:db-after r) cid (d/commit-id db)]
             (tmerge/sync-runtime-and-fork-parent! conn fork-out prev-cid cid)
             (tmerge/reply! msg {:ok? true :commit cid :tx (:max-tx db)})
             (commit-res st prev-cid r (:reason msg :transact)))
           :transact-branch
           (let [tb (:branch msg)
                 bconn (if (or (nil? tb) (= tb :main)) conn (store/connect (:config @conn) tb))]
             (try
               (let [r (tx! bconn (:tx-data msg)) db (:db-after r) cid (d/commit-id db)]
                 (tmerge/reply! msg {:ok? true :branch (or tb :main) :commit cid :tx (:max-tx db)})
                 (if (or (nil? tb) (= tb :main))
                   (commit-res st prev-cid r (:reason msg :transact-branch))
                   [st nil]))
               (finally
                 (when-not (identical? bconn conn) (d/release bconn)))))
           :set-config
           (let [{:keys [agent-id key value]} msg
                 r (tx! conn [(if agent-id {:seon.agent/id agent-id key value} {:seon.config/key :seon.config/root key value})])
                 db (:db-after r) cid (d/commit-id db)]
             (tmerge/reply! msg {:ok? true :target (or agent-id :seon.config/root) :key key :value value :tx (:max-tx db)})
             (commit-res st prev-cid r [:set-config (or agent-id :seon.config/root) key]
                         (when (nil? agent-id) (git-wb st cid "resources/seon/config/default.edn" (str "Update config " key)))))
           :send-message
           (let [mseq (inc (:msg-seq st)) mid (random-uuid)
                 r (tx! conn [(msg-ent mid mseq (:from msg) (:to msg) (:type msg) (:body msg))])]
             (tmerge/reply! msg {:ok? true :message-id mid :seq mseq :tx (:max-tx (:db-after r))})
             (commit-res st prev-cid r [:message (:from msg) (:to msg)]
                         {::flow/report [(str "[:transactor] message " (:from msg) " -> " (:to msg) " [" (name (:type msg :chat)) "]")]}
                         {:msg-seq mseq}))
           :show-user
           (let [{:keys [uid tx-data]} (show-user-tx @conn msg)
                 r (tx! conn tx-data)]
             (tmerge/reply! msg {:ok? true :user-id uid :tx (:max-tx (:db-after r))})
             (commit-res st prev-cid r [:show-user uid]))
           :record-summary
           (let [rec-fn (requiring-resolve 'seon.render.chronicle/record-summary!)
                 spec (assoc (or (:spec msg) {}) :agent (or (:agent (:spec msg)) (:agent-id msg) :agent/root))
                 res (rec-fn conn spec) db @conn cid (d/commit-id db)]
             (tmerge/reply! msg (dissoc res :tx-data))
             [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
              (when (:ok? res) {:db [(tmerge/db-notice prev-cid db (:tx-data res) [:record-summary (:id res)])]})])
           :set-git-commit
           (let [r (d/transact conn {:tx-data [{:seon.git/repo :main :seon.git/commit (:sha msg)}]}) db (:db-after r)]
             [(assoc st :last-commit (d/commit-id db) :last-tx (:max-tx db)) nil])
           :gc-store
           (let [res (store/gc-store! conn msg)
               pruned (:pruned-branches res)]
           (tmerge/reply! msg res)
           [st (cond-> {::flow/report [(str "[:transactor] gc-store pruned " (get-in res [:pruned :deleted]) " branches, swept " (:swept res) " keys")]}
                 (seq pruned)
                 (assoc :git [{:op :delete-branch-ref :branches (vec pruned)}]))])
           :toggle-pause-agent
           (let [id (:agent-id msg)
                 cur (boolean (:seon.agent/paused? (d/pull @conn '[:seon.agent/paused?] [:seon.agent/id id])))
                 r (tx! conn [{:seon.agent/id id :seon.agent/paused? (not cur)}])]
             (tmerge/reply! msg {:ok? true :agent id :paused? (not cur)})
             (commit-res st prev-cid r [:pause id]))
           (do (tmerge/reply! msg {:ok? false :error (str "Unknown transactor op: " (:op msg))}) [st nil]))
         (catch Throwable ex
         (tmerge/handle-transactor-error! st msg prev-cid ex)))))))