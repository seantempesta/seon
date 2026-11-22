(ns seon.fork.calls
  "Agent REPL `user/*` delegation (ask!/await!/call!/answer!/fail!/cancel!/calls), context-tuning,
   memory, compaction, monitoring, and lifecycle helpers."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.store :as store]
            [seon.render.data :as rdata]))

(def ^:dynamic *agent-ctx* nil)

(defn tx-main-sync!
  "Sends a request to `:transactor` on `:main` via `:agents` without blocking any Flow proc,
   returning the exact transaction reply map (e.g. `:call-id`, cycle rejection, or schema error)."
  [req]
  (when-let [{:keys [in out]} *agent-ctx*]
    (let [rid (str "tx-" (random-uuid))]
      (a/>!! out (assoc req :ev :tx-main-sync :req-id rid))
      (loop []
        (if-let [m (a/<!! in)]
          (cond
            (and (= (:op m) :tx-reply) (= (:req-id m) rid)) (:reply m)
            (= (:op m) :stop) {:ok? false :error :stopped}
            :else (recur))
          {:ok? false :error :eof})))))

(defn coerce-call-id [x]
  (cond
    (uuid? x) x
    (and (map? x) (uuid? (:call-id x))) (:call-id x)
    (and (map? x) (uuid? (:seon.agent.call/id x))) (:seon.agent.call/id x)
    (string? x) (try (java.util.UUID/fromString (str/trim x)) (catch Throwable _ nil))
    :else nil))

(defn active-incoming-call-id [conn agent-id]
  (try
    (let [mdb (store/head conn :main)]
      (when (contains? (:schema mdb) :seon.agent.call/callee)
        (->> (d/datoms mdb :avet :seon.agent.call/callee agent-id)
             (map #(d/pull mdb '[:seon.agent.call/id :seon.agent.call/status :seon.agent.call/created-tx] (:e %)))
             (filter #(= :pending (:seon.agent.call/status %)))
             (sort-by :seon.agent.call/created-tx)
             last
             :seon.agent.call/id)))
    (catch Throwable _ nil)))

(defn entry-agent-id
  "Resolves the system's entry agent (`:seon.agent/entry? true`), falling back to `:agent/root`."
  {:malli/schema [:=> [:cat :seon.host/db] :keyword]}
  [main-db]
  (or (when (contains? (:schema main-db) :seon.agent/entry?)
        (some-> (first (d/datoms main-db :avet :seon.agent/entry? true))
                :e
                (#(:seon.agent/id (d/pull main-db '[:seon.agent/id] %)))))
      :agent/root))

(defn agent-monitor-snapshot
  "Returns a live, agent-perspective monitoring snapshot (optional parent request, own branch
   merge-gate readiness, recursive children/subagents progress tree, recent eval intents/errors,
   and latest reply) using indexed Datahike progress queries."
  {:malli/schema [:=> [:cat :seon.host/conn [:maybe :keyword]] :map]}
  [conn target-id]
  (let [main-db (store/head conn :main)
        aid (or target-id (entry-agent-id main-db))
        ag (d/pull main-db '[:seon.agent/id :seon.agent/branch :seon.agent/role :seon.agent/label
                             :seon.agent/parent :seon.agent/task]
                   [:seon.agent/id aid])
        br (or (:seon.agent/branch ag) :main)
        bdb (or (store/head conn br) main-db)
        ev-eids (mapv :e (d/datoms bdb :avet :seon.agent.eval/agent aid))
        evals (->> ev-eids
                   (map #(d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/index :seon.agent.eval/intent
                                       :seon.agent.eval/ms :seon.agent.eval/error :seon.agent.eval/reply] %))
                   (sort-by (juxt #(long (or (:seon.agent.eval/turn %) 0))
                                  #(long (or (:seon.agent.eval/index %) 0))))
                   vec)
        recent (->> (take-last 6 evals)
                    (mapv (fn [e]
                            (cond-> {:index (:seon.agent.eval/index e)
                                     :turn (:seon.agent.eval/turn e)
                                     :intent (:seon.agent.eval/intent e)
                                     :ms (:seon.agent.eval/ms e)}
                              (:seon.agent.eval/error e) (assoc :error (:seon.agent.eval/error e))))))
        latest-reply (some :seon.agent.eval/reply (rseq evals))
        seen (store/agent-seen-message-ids bdb aid)
        unread-count (->> (d/datoms main-db :avet :seon.agent.message/to aid)
                          (map #(:seon.agent.message/id (d/pull main-db '[:seon.agent.message/id] (:e %))))
                          (remove #(contains? seen %))
                          count)
        self-prog (rdata/agent-branch-progress main-db conn aid)
        parent-req (rdata/parent-request-data main-db aid)
        tree (rdata/delegation-progress-tree main-db conn aid)
        summarize-node (fn summarize-node [n]
                         (let [p (:progress n)]
                           (cond-> {:agent (:seon.agent.call/callee n)
                                    :branch (:branch p)
                                    :status (:seon.agent.call/status n)
                                    :prompt (:seon.agent.call/prompt n)
                                    :stage (:stage p)
                                    :tests (:tests p)
                                    :latest-intent (:latest-intent p)}
                             (:last-error p) (assoc :last-error (:last-error p))
                             (seq (:children n)) (assoc :children (mapv summarize-node (:children n))))))]
    {:agent aid
     :branch br
     :role (:seon.agent/role ag)
     :unread-count unread-count
     :parent-request (when parent-req
                       {:parent (:parent-id parent-req)
                        :task-id (get-in parent-req [:task :seon.task/id])
                        :incoming-count (count (:incoming parent-req))})
     :self-progress self-prog
     :children (mapv summarize-node (or tree []))
     :recent-evals recent
     :latest-reply latest-reply}))

(defn install-call-and-ui-helpers!
  "Interns delegation, messaging, memory, context-tuning, compaction, and ready!/wait-for-mail! helpers into `user`."
  {:malli/schema [:=> [:cat :seon.host/ns] :any]}
  [u]
  (intern u 'send-message!
          (fn [to msg-type body]
            (when-let [{:keys [out agent-id]} *agent-ctx*]
              (a/>!! out {:ev :tx-main
                          :op :send-message
                          :from agent-id
                          :to to
                          :type msg-type
                          :body (str body)})
              {:sent-to to :type msg-type})))
  (intern u 'spawn-agent!
          (fn [{:keys [id branch scope role goal task]}]
            (when-let [{:keys [out agent-id]} *agent-ctx*]
              (let [b (or branch (keyword "branch" (str (namespace id) "." (name id))))]
                (a/>!! out {:ev :tx-main
                            :op :spawn-agent
                            :parent agent-id
                            :id id
                            :branch b
                            :scope scope
                            :role (or role :worker)
                            :goal (or goal "")
                            :task task})
                {:spawned id :branch b :scope scope}))))
  (intern u 'pin!
          (fn [item]
            (when-let [{:keys [conn]} *agent-ctx*]
              (let [cur (vec (or (:seon.agent/pinned @ctx/ctx) []))
                    ref-val (cond
                              (and (map? item) (:seon.code.var/name item)) [:seon.code.var/name (:seon.code.var/name item)]
                              (and (map? item) (:seon.code.ns/name item))  [:seon.code.ns/name (:seon.code.ns/name item)]
                              (and (map? item) (:seon.code.doc/id item))   [:seon.code.doc/id (:seon.code.doc/id item)]
                              (and (map? item) (:seon.schema/key item))    [:seon.schema/key (:seon.schema/key item)]
                              (and (map? item) (:seon.task/id item))       [:seon.task/id (:seon.task/id item)]
                              (and (map? item) (:seon.agent/id item))      [:seon.agent/id (:seon.agent/id item)]
                              :else item)
                    nxt (if (some #(= % ref-val) cur) cur (conj cur ref-val))]
                (swap! ctx/ctx assoc :seon.agent/pinned nxt)
                {:pinned ref-val :count (count nxt)}))))
  (intern u 'unpin!
          (fn [item]
            (when-let [{:keys [conn]} *agent-ctx*]
              (let [cur (vec (or (:seon.agent/pinned @ctx/ctx) []))
                    nxt (vec (remove #(= % item) cur))]
                (swap! ctx/ctx assoc :seon.agent/pinned nxt)
                {:unpinned item :count (count nxt)}))))
  (intern u 'merge-self!
          (fn []
            ((resolve 'user/ready!))))
  (intern u 'ask!
          (fn
            ([callee prompt-or-spec]
             ((resolve 'user/ask!) callee prompt-or-spec {}))
            ([callee prompt-or-spec opts]
             (when-let [{:keys [conn agent-id]} *agent-ctx*]
               (let [spec (if (map? prompt-or-spec) (merge prompt-or-spec opts) (assoc opts :prompt (str prompt-or-spec)))
                     pstr (str (or (:prompt spec) (:title spec) (:plan spec) ""))
                     parent-cid (or (:parent-call spec) (active-incoming-call-id conn agent-id))]
                 (tx-main-sync! (cond-> (merge {:op :create-call
                                                :caller agent-id
                                                :callee callee
                                                :prompt pstr}
                                               spec)
                                  parent-cid (assoc :parent-call parent-cid))))))))
  (intern u 'update-task!
          (fn [spec]
            (when-let [{:keys [conn agent-id]} *agent-ctx*]
              (let [tid (or (:id spec)
                            (:seon.agent/task (d/pull @conn '[:seon.agent/task] [:seon.agent/id agent-id]))
                            (:seon.agent/task (d/pull (store/head conn :main) '[:seon.agent/task] [:seon.agent/id agent-id])))]
                (if-not tid
                  {:ok? false :error "No active :seon.agent/task on your agent; pass {:id :ns/task ...} or create one via (user/create-task! ...)."}
                  (tx-main-sync! (assoc spec :op :update-task :id tid)))))))
  (intern u 'assign-task!
          (fn [task-id]
            (when-let [{:keys [conn agent-id]} *agent-ctx*]
              (let [mdb (store/head conn :main)
                    t (d/pull mdb '[:seon.task/id :seon.task/scope] [:seon.task/id task-id])]
                (if-not (:seon.task/id t)
                  {:ok? false :error (str "Task not found: " task-id)}
                  (let [ent (cond-> {:seon.agent/id agent-id :seon.agent/task task-id}
                              (:seon.task/scope t) (assoc :seon.agent/scope (:seon.task/scope t)))
                        r (d/transact conn {:tx-data [ent]})]
                    (ctx/refresh-active! (:db-after r) (:tx-data r))
                    (when (first (d/datoms mdb :avet :seon.agent/id agent-id))
                      (tx-main-sync! {:op :transact :tx-data [ent] :reason [:assign-task agent-id task-id]}))
                    {:ok? true :agent-id agent-id :task task-id :scope (:seon.task/scope t)}))))))
  (intern u 'await!
          (fn [call-or-calls]
            (when-let [{:keys [agent-id]} *agent-ctx*]
              (let [raw (if (or (vector? call-or-calls) (set? call-or-calls) (seq? call-or-calls))
                          call-or-calls
                          [call-or-calls])
                    ids (vec (keep coerce-call-id raw))]
                (tx-main-sync! {:op :await-calls :caller agent-id :call-ids ids})))))
  (intern u 'call!
          (fn
            ([callee prompt]
             ((resolve 'user/call!) callee prompt {}))
            ([callee prompt opts]
             ((resolve 'user/ask!) callee prompt (assoc opts :await? true)))))
  (intern u 'answer!
          (fn [call-id value]
            (when-let [{:keys [agent-id]} *agent-ctx*]
              (if-let [cid (coerce-call-id call-id)]
                (tx-main-sync! {:op :answer-call :call-id cid :callee agent-id :result value})
                {:ok? false :error {:class :invalid-call-id :value call-id}}))))
  (intern u 'fail!
          (fn [call-id error]
            (when-let [{:keys [agent-id]} *agent-ctx*]
              (if-let [cid (coerce-call-id call-id)]
                (tx-main-sync! {:op :fail-call :call-id cid :callee agent-id :error error})
                {:ok? false :error {:class :invalid-call-id :value call-id}}))))
  (intern u 'cancel!
          (fn
            ([call-id] ((resolve 'user/cancel!) call-id "cancelled"))
            ([call-id reason]
             (if-let [cid (coerce-call-id call-id)]
               (tx-main-sync! {:op :cancel-call :call-id cid :reason (str reason)})
               {:ok? false :error {:class :invalid-call-id :value call-id}}))))
  (intern u 'calls
          (fn []
            (when-let [{:keys [conn agent-id]} *agent-ctx*]
              (let [mdb (store/head conn :main)
                    pull-all (fn [attr]
                               (if (contains? (:schema mdb) attr)
                                 (->> (d/datoms mdb :avet attr agent-id)
                                      (map #(dissoc (d/pull mdb '[*] (:e %)) :db/id))
                                      (sort-by #(or (:seon.agent.call/created-tx %) 0))
                                      vec)
                                 []))]
                {:incoming (pull-all :seon.agent.call/callee)
                 :outgoing (pull-all :seon.agent.call/caller)}))))
  (intern u 'show-user!
          (fn [{:keys [user-id viewing mode sidebar? expand selected-branch as-of-tx] :as arg}]
            (when-let [{:keys [out]} *agent-ctx*]
              (a/>!! out (cond-> {:ev :tx-main
                                  :op :show-user
                                  :user-id (or user-id :user/default)}
                           viewing (assoc :viewing viewing)
                           mode (assoc :mode mode)
                           (some? sidebar?) (assoc :sidebar? (boolean sidebar?))
                           (seq expand) (assoc :expand (set expand))
                           selected-branch (assoc :selected-branch selected-branch)
                           (contains? arg :as-of-tx) (assoc :as-of-tx as-of-tx)))
              {:ok? true :viewing viewing :mode mode :sidebar? sidebar? :as-of-tx as-of-tx})))
  (intern u 'remember!
          (fn [note]
            (when *agent-ctx*
              (let [cur (vec (or (:seon.agent/memory @ctx/ctx) []))
                    nxt (if (some #(= % note) cur) cur (conj cur note))]
                (swap! ctx/ctx assoc :seon.agent/memory nxt)
                {:remembered note :count (count nxt)}))))
  (intern u 'forget!
          (fn [idx-or-note]
            (when *agent-ctx*
              (let [cur (vec (or (:seon.agent/memory @ctx/ctx) []))
                    nxt (if (and (integer? idx-or-note) (<= 0 idx-or-note (dec (count cur))))
                          (vec (concat (subvec cur 0 idx-or-note) (subvec cur (inc idx-or-note))))
                          (vec (remove #(= % idx-or-note) cur)))]
                (swap! ctx/ctx assoc :seon.agent/memory nxt)
                {:count (count nxt)}))))
  (intern u 'tune-context!
          (fn [{:keys [pin unpin pattern recent-data-limit max-vars-shown max-schemas-shown
                       extra-namespaces extra-schemas] :as opts}]
            (when *agent-ctx*
              (let [cur-prefs (or (:seon.agent/context-prefs @ctx/ctx) {})
                    pref-patch (cond-> {}
                                 (integer? recent-data-limit) (assoc :recent-data-limit (long recent-data-limit))
                                 (integer? max-vars-shown)    (assoc :max-vars-shown (long max-vars-shown))
                                 (integer? max-schemas-shown) (assoc :max-schemas-shown (long max-schemas-shown))
                                 (contains? opts :extra-namespaces)
                                 (assoc :extra-namespaces (mapv #(if (string? %) (symbol %) %) extra-namespaces))
                                 (contains? opts :extra-schemas)
                                 (assoc :extra-schemas (vec extra-schemas)))
                    next-prefs (merge cur-prefs pref-patch)]
                (when (seq pref-patch)
                  (swap! ctx/ctx assoc :seon.agent/context-prefs next-prefs))
                (when (and (vector? pattern) (seq pattern))
                  (swap! ctx/ctx assoc :seon.ctx/pattern pattern))
                (doseq [p pin] ((resolve 'user/pin!) p))
                (doseq [u-item unpin] ((resolve 'user/unpin!) u-item))
                {:ok? true :context-prefs next-prefs}))))
  (intern u 'compact!
          (fn
            ([] ((resolve 'user/compact!) {}))
            ([summary-or-opts]
             (when-let [{:keys [conn agent-id]} *agent-ctx*]
               (let [opts (if (map? summary-or-opts) summary-or-opts {:summary (str summary-or-opts)})
                     {:keys [summary memory tune through-turn keep-last]} opts
                     target-aid (or (:agent-id opts) agent-id)
                     _ (when (not (str/blank? (str summary)))
                         ((resolve 'user/remember!) (str summary)))
                     _ (doseq [m memory] ((resolve 'user/remember!) m))
                     _ (when (map? tune) ((resolve 'user/tune-context!) tune))
                     db-before @conn
                     eval-ents (->> (d/datoms db-before :avet :seon.agent.eval/agent target-aid)
                                    (map #(d/pull db-before '[:db/id :seon.agent.eval/turn] (:e %)))
                                    vec)
                     distinct-turns (vec (sort (distinct (keep :seon.agent.eval/turn eval-ents))))
                     keep-n (long (or keep-last 0))
                     cut-turn (cond
                                (integer? through-turn) (long through-turn)
                                (pos? keep-n)
                                (if (> (count distinct-turns) keep-n)
                                  (long (nth distinct-turns (- (count distinct-turns) keep-n 1)))
                                  -1)
                                :else Long/MAX_VALUE)
                     to-retract (filterv #(<= (long (or (:seon.agent.eval/turn %) 0)) cut-turn) eval-ents)
                     retract-tx (mapv #(vector :db/retractEntity (:db/id %)) to-retract)
                     r (when (seq retract-tx)
                         (d/transact conn {:tx-data retract-tx}))]
                 (when r
                   (ctx/refresh-active! (:db-after r) (:tx-data r)))
                 {:ok? true
                  :agent-id target-aid
                  :retracted-evals (count to-retract)
                  :remaining-evals (- (count eval-ents) (count to-retract))
                  :history-tx (:max-tx db-before)
                  :memory-count (count (:seon.agent/memory @ctx/ctx))})))))
  (intern u 'start-bench-run!
          (fn [opts]
            (let [inst-op (requiring-resolve 'seon.bench.cluster/instance-op!)
                  port (or (:coordinator-port opts) (System/getenv "SEON_PORT") 9998)]
              (inst-op {:base-url (str "http://127.0.0.1:" port)}
                       {:op :start-bench-run :opts (if (map? opts) opts {}) :coordinator-port port}))))
  (intern u 'record-summary!
          (fn
            ([] ((resolve 'user/record-summary!) {}))
            ([spec]
             (when-let [{:keys [agent-id]} *agent-ctx*]
               (tx-main-sync! {:op :record-summary :agent-id agent-id :spec (if (map? spec) spec {})})))))
  (intern u 'wait-for-mail!
  (fn []
    (when-let [{:keys [conn agent-id]} *agent-ctx*]
      (d/transact conn {:tx-data [{:seon.agent/id agent-id :seon.agent/waiting? true :seon.agent/ready? false}]})
      {:ok? true :status :waiting :agent-id agent-id})))
  (intern u 'chat!
          (fn
            ([msg] ((resolve 'user/chat!) nil msg))
            ([target-agent msg]
             (when-let [{:keys [conn]} *agent-ctx*]
               (let [target (or target-agent (entry-agent-id (store/head conn :main)))]
                 (tx-main-sync! {:op :show-user :as-of-tx :live})
                 (assoc (tx-main-sync! {:op :send-message
                                        :from :user
                                        :to target
                                        :type :chat
                                        :body (str msg)})
                        :to target))))))
  (intern u 'monitor
          (fn
            ([] ((resolve 'user/monitor) nil))
            ([target-agent]
             (when-let [{:keys [conn]} *agent-ctx*]
               (agent-monitor-snapshot conn target-agent)))))
  (intern u 'ready!
  (fn []
    (when-let [{:keys [conn agent-id branch]} *agent-ctx*]
      (d/transact conn {:tx-data [{:seon.agent/id agent-id :seon.agent/waiting? false :seon.agent/ready? true}]})
      {:ok? true :status :ready :agent-id agent-id :branch branch}))))