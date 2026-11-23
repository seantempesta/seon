(ns seon.transactor
  "The `:transactor` Flow proc (`:mixed` workload): sole serialized writer of `:main` on LMDB,
   automatic Malli -> Datahike schema sync, hot-load overlay, and 3-way merges."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.error :as serr]
            [seon.fork :as fork]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.transactor.calls :as tcalls]
            [seon.transactor.merge :as tmerge]))

(defn- focus-tx [scope] (when scope (code/ns-parent-chain-tx scope)))
(defn- ^:seon.flow/edge sync-store-best-effort! [cfg] (try (store/sync! cfg) (catch Throwable _ nil)))

(defn- norm-ref [r]
  (when (and (vector? r) (= 2 (count r)) (qualified-keyword? (first r)) (some? (second r)))
    (let [v (second r)]
      [(first r) (if (and (seq? v) (= 'quote (first v))) (second v) v)])))

(defn- cap16 [xs]
  (let [v (vec (remove nil? xs))]
    (if (> (count v) 16) (subvec v (- (count v) 16)) v)))

(defn- branch-for [aid rb]
  (if (and rb (not= :main rb))
    rb
    (keyword "branch" (str (or (namespace aid) "agent") "." (name aid)))))

(defn- msg-ent [mid mseq from to mtype body]
  {:seon.agent.message/id mid :seon.agent.message/seq mseq
   :seon.agent.message/from (or from :user) :seon.agent.message/to to
   :seon.agent.message/type (or mtype :chat) :seon.agent.message/body (str (or body ""))})

(defn- commit-res [st prev-cid r reason & [extra-out extra-st]]
  (let [db (:db-after r) cid (d/commit-id db)]
    [(-> (merge st extra-st) (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
     (merge {:db [(tmerge/db-notice prev-cid db (:tx-data r) reason)]} extra-out)]))

(defn- git-wb [st cid path message]
  (when (and (:commit-git? st) (:repo-dir st))
    {:git [{:op :write-back :branch :main :commit cid :touched-paths #{path} :message message}]}))

(defn- next-inspect-state [cur msg clear-nav?]
  (let [cr (norm-ref (:seon.user/inspect-ref cur))
        ct (vec (keep norm-ref (:seon.user/inspect-trail cur)))
        clp (if (map? (:seon.user/lab-prefs cur)) (:seon.user/lab-prefs cur) {})
        cf (vec (keep norm-ref (:inspect-forward clp)))
        pr (norm-ref (:push-inspect msg)) dr (norm-ref (:inspect-ref msg))
        [nr nt nf ret?]
        (cond
          (:clear-inspect msg) [nil [] [] (some? cr)]
          pr (if (= pr cr) [cr ct cf false] [pr (if cr (cap16 (conj ct cr)) ct) [] false])
          (:pop-inspect msg) (if (seq ct) [(peek ct) (pop ct) (if cr (cap16 (conj cf cr)) cf) false] [nil [] [] (some? cr)])
          (:forward-inspect msg) (if (seq cf) [(peek cf) (if cr (cap16 (conj ct cr)) ct) (pop cf) false] [cr ct cf false])
          (integer? (:jump-trail msg))
          (let [i (long (:jump-trail msg))]
            (if (and (>= i 0) (< i (count ct)))
              [(nth ct i) (subvec ct 0 i) (cap16 (concat cf (reverse (concat (subvec ct (inc i)) (when cr [cr]))))) false]
              [cr ct cf false]))
          (contains? msg :inspect-ref) (if dr [dr ct [] false] [nil [] [] (some? cr)])
          clear-nav? [nil [] [] (some? cr)]
          :else [cr ct cf false])
        lp1 (cond
              (:reset-lab-prefs msg) {}
              (map? (:lab-prefs msg)) (into {} (remove #(nil? (val %)))
                                            (if (:replace-lab-prefs? msg) (:lab-prefs msg) (merge clp (:lab-prefs msg))))
              :else clp)
        lp2 (if-let [rid (when (qualified-keyword? (:toggle-lab-rule msg)) (:toggle-lab-rule msg))]
              (let [s (set (:disabled-rules lp1)) s' (if (s rid) (disj s rid) (conj s rid))]
                (if (seq s') (assoc lp1 :disabled-rules (vec (sort s'))) (dissoc lp1 :disabled-rules)))
              lp1)
        lp3 (if (seq nf) (assoc lp2 :inspect-forward nf) (dissoc lp2 :inspect-forward))]
    {:ref nr :trail nt :lp lp3 :ret? ret? :cur-ref (:seon.user/inspect-ref cur)
     :ref-ch? (not= nr cr) :tr-ch? (not= nt ct) :lp-ch? (not= lp3 clp)}))

(defn- show-user-tx [db msg]
  (let [{:keys [user-id viewing viewing-ref mode sidebar? toggle-sidebar? toggle-expand expand
                selected-branch as-of-tx theme toggle-theme? keep-inspect?]} msg
        uid (or user-id :user/default) dh (or (:schema db) {})
        insp? (every? dh [:seon.user/inspect-ref :seon.user/inspect-trail :seon.user/lab-prefs])
        pat (cond-> '[:seon.user/id :seon.user/view-mode :seon.user/sidebar-open? :seon.user/expanded-agents
                      :seon.user/as-of-tx :seon.user/theme {:seon.user/viewing [:seon.agent/id]}]
              insp? (into [:seon.user/inspect-ref :seon.user/inspect-trail :seon.user/lab-prefs]))
        cur (if (first (d/datoms db :avet :seon.user/id uid)) (d/pull db pat [:seon.user/id uid]) {})
        cur-v (get-in cur [:seon.user/viewing :seon.agent/id])
        vref (or viewing-ref
                 (when viewing
                   (let [aid (cond (= viewing :entry)
                                   (or (when (dh :seon.agent/entry?)
                                         (some-> (first (d/datoms db :avet :seon.agent/entry? true))
                                                 :e (#(:seon.agent/id (d/pull db '[:seon.agent/id] %)))))
                                       :agent/root)
                                   (vector? viewing) (second viewing)
                                   :else viewing)]
                     (when (first (d/datoms db :avet :seon.agent/id aid)) [:seon.agent/id aid]))))
        sw? (and vref (or viewing-ref (not= (second vref) cur-v)))
        clear-as-of? (and (:seon.user/as-of-tx cur)
                          (or (and (contains? msg :as-of-tx) (or (nil? as-of-tx) (= :live as-of-tx)))
                              (and sw? (not (contains? msg :as-of-tx)))))
        clear-nav? (and (not keep-inspect?) (or (and mode (not= mode :inspect)) sw?))
        ins (when insp? (next-inspect-state cur msg clear-nav?))
        retracts (cond-> []
                   (and toggle-expand (contains? (set (:seon.user/expanded-agents cur)) toggle-expand))
                   (conj [:db/retract [:seon.user/id uid] :seon.user/expanded-agents toggle-expand])
                   clear-as-of? (conj [:db/retract [:seon.user/id uid] :seon.user/as-of-tx (:seon.user/as-of-tx cur)])
                   (and ins (:ret? ins) (some? (:cur-ref ins)))
                   (conj [:db/retract [:seon.user/id uid] :seon.user/inspect-ref (:cur-ref ins)]))
        add-exp (cond-> #{}
                  (and toggle-expand (not (contains? (set (:seon.user/expanded-agents cur)) toggle-expand))) (conj toggle-expand)
                  (seq expand) (into expand))
        nxt-th (cond (#{:auto :dark :light} theme) theme
                     toggle-theme? (if (= :light (:seon.user/theme cur)) :dark :light) :else nil)
        ent (cond-> {:seon.user/id uid}
              vref (assoc :seon.user/viewing vref)
              (or mode (when (and (:push-inspect msg) (contains? #{:context :debug} (:seon.user/view-mode cur))) :inspect))
              (assoc :seon.user/view-mode (or mode :inspect))
              (some? sidebar?) (assoc :seon.user/sidebar-open? (boolean sidebar?))
              toggle-sidebar? (assoc :seon.user/sidebar-open? (not (boolean (:seon.user/sidebar-open? cur))))
              (seq add-exp) (assoc :seon.user/expanded-agents add-exp)
              selected-branch (assoc :seon.user/selected-branch selected-branch)
              (integer? as-of-tx) (assoc :seon.user/as-of-tx (long as-of-tx))
              nxt-th (assoc :seon.user/theme nxt-th)
              (and ins (:ref ins) (:ref-ch? ins)) (assoc :seon.user/inspect-ref (:ref ins))
              (and ins (:tr-ch? ins)) (assoc :seon.user/inspect-trail (:trail ins))
              (and ins (:lp-ch? ins)) (assoc :seon.user/lab-prefs (:lp ins)))]
    {:uid uid :tx-data (vec (concat retracts [ent]))}))

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

(defn- derive-focus-scope [[fa fv] ent]
  (let [ns-of #(when (and (symbol? %) (namespace %)) (symbol (namespace %)))]
    (or (some #(when (symbol? %) %)
              [(:seon.code.var/ns ent) (:seon.code.ns/name ent) (:seon.code.form/ns ent)
               (:seon.task/scope ent) (get-in ent [:seon.error/data :ns]) (:seon.agent/scope ent)])
        (some ns-of [(:seon.error/var ent) (:seon.test/var ent) (:seon.fix/regression-test ent)])
        (when (= fa :seon.code.var/name) (ns-of fv))
        (when (and (= fa :seon.code.ns/name) (symbol? fv)) fv))))

(defn- focus-agent-id [db [fa fv] ent]
  (let [raw (or (some #(when-let [v (get ent %)] (if (ident? v) (name v) (str v)))
                      [:seon.code.var/name :seon.code.ns/name :seon.task/id
                       :seon.rule/id :seon.schema/key :seon.error/kind])
                (when (= :seon.system/id fa) (str "system-" (if (ident? fv) (name fv) fv)))
                (if (ident? fv) (name fv) (str fv)))
        s1 (-> (str/lower-case (str raw)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))
        base (if (str/blank? s1) "focus" (subs s1 0 (min 20 (count s1))))]
    (loop [n 1]
      (let [cand (keyword "agent" (if (= n 1) base (str base "-" n)))]
        (if (first (d/datoms db :avet :seon.agent/id cand)) (recur (inc n)) cand)))))

(defn- tx! [conn tx-data] (d/transact conn {:tx-data (tcalls/with-ref-mirrors @conn tx-data)}))

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

(defn- handle-spawn-on-entity! [st msg prev-cid]
  (let [conn (:conn st) db0 @conn dh (:schema db0) fref (norm-ref (:focus-ref msg))]
    (if-not fref
      (do (tmerge/reply! msg {:ok? false :error "Invalid :focus-ref"}) [st nil])
      (let [[fa fv] fref
            feid (cond (= fa :db/id) (when (integer? fv) fv)
                       (contains? dh fa) (:e (first (d/datoms db0 :avet fa fv))) :else nil)
            sys-up (when (and (nil? feid) (= fa :seon.system/id) (contains? dh :seon.system/id))
                     {:db/id "focus/system" :seon.system/id fv
                      :seon.system/title (if (= fv :root) "Seon Runtime" (str "System " (name fv)))})]
        (if (and (nil? feid) (nil? sys-up) (not (#{:seon.signal/kind :seon.branch/name} fa)))
          (do (tmerge/reply! msg {:ok? false :error (str "Entity not found: " (pr-str fref))}) [st nil])
          (let [ent (or (when feid (d/pull db0 '[*] feid)) sys-up)
                tref (or feid (when sys-up "focus/system"))
                aid (or (:id msg) (focus-agent-id db0 fref ent))
                b (branch-for aid (:branch msg))
                scope (or (:scope msg) (derive-focus-scope fref ent))
                lbl (or (some #(when-let [v (get ent %)] (not-empty (str v)))
                              [:seon.code.var/name :seon.code.ns/name :seon.task/title
                               :seon.task/id :seon.error/kind :seon.system/title]) (str fv))
                prompt (str/trim (str (or (:prompt msg) "")))
                body (if (str/blank? prompt) (str "Focus on " (pr-str fref) " (" lbl ").") prompt)
                is-task? (or (= fa :seon.task/id) (some? (:seon.task/id ent)))
                eff-task (if is-task? (or (:seon.task/id ent) fv) (keyword "seon.focus" (name aid)))
                task-op (when-not is-task?
                          (cond-> {:seon.task/id eff-task :seon.task/title (str "Focus: " lbl)
                                   :seon.task/plan body :seon.task/done? false}
                            scope (assoc :seon.task/scope scope)
                            (contains? dh :seon.task/owner) (assoc :seon.task/owner aid :seon.task/created-by (or (:parent msg) :user))
                            (contains? dh :seon.task/curated) (assoc :seon.task/curated [fref])))
                mseq (inc (:msg-seq st)) mid (random-uuid)
                ag-ent (cond-> {:db/id "agent/new" :seon.agent/id aid :seon.agent/branch b
                                :seon.agent/role (or (:role msg) :worker)
                                :seon.agent/goal (if (str/blank? prompt) (str "Focus on " lbl) prompt)
                                :seon.agent/parent (or (:parent msg) :user)
                                :seon.agent/task eff-task :seon.agent/pinned [fref]}
                         scope (assoc :seon.agent/scope scope :seon.agent/focus (str "ns/" scope))
                         (and tref (contains? dh :seon.agent/target)) (assoc :seon.agent/target tref)
                         (some? (:auto-merge? msg)) (assoc :seon.agent/auto-merge? (boolean (:auto-merge? msg))))
                u-ops (when (contains? dh :seon.user/id)
                        (:tx-data (show-user-tx db0 {:user-id (or (:user-id msg) :user/default)
                                                     :viewing-ref "agent/new" :mode :session
                                                     :clear-inspect true :as-of-tx :live})))
                r (tx! conn (vec (concat (when scope (focus-tx scope))
                  (remove nil? [sys-up task-op ag-ent (msg-ent mid mseq (:from msg) aid :chat body)])
                  u-ops)))
                _ (store/branch! conn :main b)
                db (:db-after r)
                res-t (or feid (when (contains? dh fa) (:e (first (d/datoms db :avet fa fv)))))]
            (tmerge/reply! msg (cond-> {:ok? true :agent aid :branch b :task eff-task :focus-ref fref :message-id mid :tx (:max-tx db)}
                                 scope (assoc :scope scope) res-t (assoc :target res-t)))
            (commit-res st prev-cid r [:spawn-on-entity aid fref]
                        {::flow/report [(str "[:transactor] spawned " aid " on " b " focused on " (pr-str fref))]}
                        {:msg-seq mseq})))))))

(defn- handle-spawn-agent! [st msg prev-cid]
  (let [conn (:conn st) db0 @conn
      {:keys [id parent scope branch role goal task config auto-merge?]} msg
      b (branch-for id branch) mseq (inc (:msg-seq st))
      blank? (and (str/blank? (str goal)) (nil? task) (nil? scope))
      matched (when-not blank? (or task (tcalls/best-task-for-scope db0 scope)))
      sub (when (and (not blank?) (nil? matched) (first (d/datoms db0 :avet :seon.task/id :template/discover-and-plan)))
            (tcalls/build-subtask-tx db0 (or parent :user) id (random-uuid) scope
                                     {:title (or (not-empty (str goal)) (str "Discover & plan " (or scope id)))
                                      :plan (str (or goal "")) :from-template :template/discover-and-plan :role role}))
      eff-task (or matched (:task-id sub))
      ag0 (merge {:seon.agent/id id :seon.agent/branch b :seon.agent/role (or role :worker) :seon.agent/goal (or goal "")} config)
      ag (cond-> ag0
           (some? auto-merge?) (assoc :seon.agent/auto-merge? (boolean auto-merge?))
           blank? (assoc :seon.agent/pinned (vec (distinct (conj (vec (:seon.agent/pinned ag0)) [:seon.system/id :root]))))
           parent (assoc :seon.agent/parent parent)
           scope (assoc :seon.agent/scope scope :seon.agent/focus (str "ns/" scope))
           eff-task (assoc :seon.agent/task eff-task))
      tx-data (vec (concat (when scope (focus-tx scope)) (:tx sub)
                           (remove nil? [ag (when-not (str/blank? (str goal)) (msg-ent (random-uuid) mseq parent id :delegate goal))])))
      r (tx! conn tx-data) _ (store/branch! conn :main b)]
  (tmerge/reply! msg (cond-> {:ok? true :agent id :branch b :scope scope :tx (:max-tx (:db-after r))} eff-task (assoc :task eff-task)))
  (commit-res st prev-cid r [:spawn-agent id]
              {::flow/report [(str "[:transactor] branched " b " for agent " id (when eff-task (str " (task " eff-task ")")))]}
              {:msg-seq mseq})))

(defn- handle-update-task! [st msg prev-cid]
  (let [conn (:conn st) db0 @conn
        {:keys [id title plan scope needs tests summary done? owner]} msg]
    (if-not (and id (first (d/datoms db0 :avet :seon.task/id id)))
      (do (tmerge/reply! msg {:ok? false :error (str "Task not found: " id)}) [st nil])
      (let [ext? (contains? (:schema db0) :seon.task/owner)
            ent (cond-> {:seon.task/id id}
                  (string? title) (assoc :seon.task/title title) (string? plan) (assoc :seon.task/plan plan)
                  scope (assoc :seon.task/scope scope) (some? done?) (assoc :seon.task/done? (boolean done?))
                  (and ext? (string? summary)) (assoc :seon.task/summary summary)
                  (and ext? owner (qualified-keyword? owner)) (assoc :seon.task/owner owner))
            r (tx! conn (remove nil? [ent
              (when (seq needs) {:seon.task/id id :seon.task/needs (set needs)})
              (when (seq tests) {:seon.task/id id :seon.task/tests (set tests)})]))
            db (:db-after r)]
        (tmerge/reply! msg {:ok? true :task id :tx (:max-tx db)})
        (commit-res st prev-cid r [:update-task id]
                    (merge {::flow/report [(str "[:transactor] updated task " id)]}
                           (git-wb st (d/commit-id db) "resources/seon/tasks.edn" (str "Update task " id))))))))

(defn- handle-create-task! [st msg prev-cid]
  (let [conn (:conn st) db0 @conn
        {:keys [id scope title plan needs tests spawn-agent? agent-id role auto-merge?
                parent template? template owner created-by summary parent-agent]} msg
        aid (or agent-id (keyword (namespace id) (name id)))
        b (branch-for aid nil) mseq (inc (:msg-seq st))
        ext? (contains? (:schema db0) :seon.task/owner)
        tx-data (cond-> (vec (concat (when (and spawn-agent? scope) (focus-tx scope))
                                     [(cond-> {:seon.task/id id :seon.task/title (or title (name id))
                                               :seon.task/plan (or plan "") :seon.task/done? false}
                                        scope (assoc :seon.task/scope scope)
                                        (and ext? (some? template?)) (assoc :seon.task/template? (boolean template?))
                                        (and ext? template (qualified-keyword? template)) (assoc :seon.task/template template)
                                        (and ext? (or owner (when spawn-agent? aid))) (assoc :seon.task/owner (or owner aid))
                                        (and ext? created-by) (assoc :seon.task/created-by created-by)
                                        (and ext? (string? summary)) (assoc :seon.task/summary summary))
                                      (when (seq needs) {:seon.task/id id :seon.task/needs (set needs)})
                                      (when (seq tests) {:seon.task/id id :seon.task/tests (set tests)})
                                      (when (and parent (qualified-keyword? parent) (not= parent id)
                                                 (first (d/datoms db0 :avet :seon.task/id parent))
                                                 (not (:seon.task/template? (d/pull db0 '[:seon.task/template?] [:seon.task/id parent]))))
                                        {:seon.task/id parent :seon.task/needs #{id}})]))
                  spawn-agent?
                  (conj (cond-> {:seon.agent/id aid :seon.agent/branch b :seon.agent/task id
                                 :seon.agent/role (or role :worker) :seon.agent/goal (or title (name id))}
                          parent-agent (assoc :seon.agent/parent parent-agent)
                          (some? auto-merge?) (assoc :seon.agent/auto-merge? (boolean auto-merge?))
                          scope (assoc :seon.agent/scope scope :seon.agent/focus (str "ns/" scope)))
                        (msg-ent (random-uuid) mseq parent-agent aid :task (str (or title (name id)) "\n" (or plan "")))))
        r (tx! conn (remove nil? tx-data))
        _ (when spawn-agent? (store/branch! conn :main b))
        db (:db-after r) cid (d/commit-id db)]
    (tmerge/reply! msg {:ok? true :task id :agent (when spawn-agent? aid) :branch (when spawn-agent? b) :tx (:max-tx db)})
    (commit-res st prev-cid r [:create-task id]
                (merge {::flow/report [(str "[:transactor] created task " id (when spawn-agent? (str " + agent " aid " on " b)))]}
                       (git-wb st cid "resources/seon/tasks.edn" (str "Create task " id)))
                (when spawn-agent? {:msg-seq mseq}))))

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
           (:create-call :await-calls :answer-call :fail-call :cancel-call :settle-calls)
           (tcalls/handle-call-op! st msg prev-cid {:reply! tmerge/reply! :db-notice tmerge/db-notice :focus-tx focus-tx})
           :transact
           (let [r (tx! conn (:tx-data msg)) db (:db-after r) cid (d/commit-id db)]
             (tmerge/sync-runtime-and-fork-parent! conn fork-out prev-cid cid)
             (tmerge/reply! msg {:ok? true :commit cid :tx (:max-tx db)})
             (commit-res st prev-cid r (:reason msg :transact)))
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
           :toggle-pause-agent
           (let [id (:agent-id msg)
                 cur (boolean (:seon.agent/paused? (d/pull @conn '[:seon.agent/paused?] [:seon.agent/id id])))
                 r (tx! conn [{:seon.agent/id id :seon.agent/paused? (not cur)}])]
             (tmerge/reply! msg {:ok? true :agent id :paused? (not cur)})
             (commit-res st prev-cid r [:pause id]))
           (do (tmerge/reply! msg {:ok? false :error (str "Unknown transactor op: " (:op msg))}) [st nil]))
         (catch Throwable ex
           (tmerge/reply! msg {:ok? false :error (.getMessage ex)})
           [st {::flow/error [{:proc :transactor :op (:op msg) :error (.getMessage ex)}]}]))))))
