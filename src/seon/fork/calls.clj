(ns seon.fork.calls
  "Agent REPL `user/*` delegation (ask!/await!/call!/answer!/fail!/cancel!/calls), context-tuning,
   memory, compaction, monitoring, and lifecycle helpers."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.store :as store]
            [seon.render.data :as rdata]
            [seon.health.coverage]
            [seon.render.sanitize :as sanitize]
            [seon.render.fit :as fit]
            [seon.task.refine :as refine]
            [seon.schema.index :as six]))

(def ^:dynamic *agent-ctx* nil)

(defn tx-main-sync!
  "Sends a request to `:transactor` on `:main` via `:agents` without blocking any Flow proc,
   returning the exact transaction reply map (e.g. `:call-id`, cycle rejection, or schema error)."
  {:malli/schema [:=> [:cat :map] :any]}
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

(defn coerce-call-id
  {:malli/schema [:=> [:cat :any] [:maybe :uuid]]}
  [x]
  (cond
    (uuid? x) x
    (and (map? x) (uuid? (:call-id x))) (:call-id x)
    (and (map? x) (uuid? (:seon.agent.call/id x))) (:seon.agent.call/id x)
    (string? x) (try (java.util.UUID/fromString (str/trim x)) (catch Throwable _ nil))
    :else nil))

(defn active-incoming-call-id
  {:malli/schema [:=> [:cat :seon.host/conn :keyword] [:maybe :uuid]]}
  [conn agent-id]
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
  (let [main-db (or (store/head conn :main) @conn)
        aid (or target-id (entry-agent-id main-db))
        ag (d/pull main-db '[:seon.agent/id :seon.agent/branch :seon.agent/router? :seon.agent/label
                           :seon.agent/parent {:seon.agent/tasks [:seon.task/id]}]
                   [:seon.agent/id aid])
        br (or (:seon.agent/branch ag) :main)
        bdb (or (store/head conn br) main-db)
        ev-eids (mapv :e (d/datoms bdb :avet :seon.agent.eval/agent aid))
        evals (->> ev-eids
                   (map #(d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/call-index :seon.agent.eval/intent
                                     :seon.agent.eval/code :seon.agent.eval/ms :seon.agent.eval/error :seon.agent.eval/thought] %))
                   (sort-by (juxt #(long (or (:seon.agent.eval/turn %) 0))
                                  #(long (or (:seon.agent.eval/call-index %) 0))))
                   vec)
        recent (->> (take-last 6 evals)
                    (mapv (fn [e]
                            (cond-> {:index (:seon.agent.eval/call-index e)
                                     :turn (:seon.agent.eval/turn e)
                                     :intent (:seon.agent.eval/intent e)
                                     :ms (:seon.agent.eval/ms e)}
                              (:seon.agent.eval/error e) (assoc :error (:seon.agent.eval/error e))))))
        latest-reply (some #(when (and (str/blank? (str (:seon.agent.eval/code %))) (not (str/blank? (str (:seon.agent.eval/thought %))))) (:seon.agent.eval/thought %)) (rseq evals))
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
     :router? (boolean (:seon.agent/router? ag))
     :unread-count unread-count
     :parent-request (when parent-req
                       {:parent (:parent-id parent-req)
                        :task-id (get-in parent-req [:task :seon.task/id])
                        :incoming-count (count (:incoming parent-req))})
     :self-progress self-prog
     :children (mapv summarize-node (or tree []))
     :recent-evals recent
     :latest-reply latest-reply}))

(defn ^:seon.flow/edge send-message!
  "Sends a typed message (`:chat`, `:delegate`, etc.) to agent `to`."
  {:seon.repl/export true :malli/schema [:=> [:cat :keyword :keyword :any] [:maybe :map]]}
  [to msg-type body]
  (when-let [{:keys [out agent-id]} *agent-ctx*]
    (a/>!! out {:ev :tx-main :op :send-message :from agent-id :to to :type msg-type :body (str body)})
    {:sent-to to :type msg-type}))

(defn ^:seon.flow/edge spawn-agent!
  "Spawns an agent on a branch via unified `:op :spawn-agent`. Supports `:id`, `:branch`, `:scope`, `:goal`, `:tasks`, `:fork-from` (inherit branch + open session prefix), `:branch-from` (inherit code branch only), `:merge-target` (`:main`, `:none`, branch kw, or `[:seon.agent/id parent]`), `:auto-merge?`, `:pause-parent?`, `:target`."
  {:seon.repl/export true :malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [id branch] :as spec}]
  (when-let [{:keys [agent-id]} *agent-ctx*]
    (let [b (or branch (keyword "branch" (str (or (namespace id) "agent") "." (name id))))]
      (tx-main-sync! (merge {:op :spawn-agent :parent agent-id :branch b} spec)))))

(defn ^:seon.flow/edge unpin!
  "Removes `item` from `:seon.agent/pinned` on `@ctx`."
  {:seon.repl/export true :malli/schema [:=> [:cat :any] [:maybe :map]]}
  [item]
  (when-let [{:keys [conn]} *agent-ctx*]
    (let [cur (vec (or (:seon.agent/pinned @ctx/ctx) []))
          nxt (vec (remove #(= % item) cur))]
      (swap! ctx/ctx assoc :seon.agent/pinned nxt)
      {:unpinned item :count (count nxt)})))

(defn ^:seon.flow/edge ask!
  "Sends a structured Flow call to `callee` with a prompt string or task spec map."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat :keyword :any] [:maybe :map]] [:=> [:cat :keyword :any [:maybe :map]] [:maybe :map]]]}
  ([callee prompt-or-spec] (ask! callee prompt-or-spec {}))
  ([callee prompt-or-spec opts]
   (when-let [{:keys [conn agent-id]} *agent-ctx*]
     (let [spec (if (map? prompt-or-spec) (merge prompt-or-spec opts) (assoc opts :prompt (str prompt-or-spec)))
           pstr (str (or (:prompt spec) (:title spec) (:plan spec) ""))
           parent-cid (or (:parent-call spec) (active-incoming-call-id conn agent-id))]
       (tx-main-sync! (cond-> (merge {:op :create-call :caller agent-id :callee callee :prompt pstr} spec)
                        parent-cid (assoc :parent-call parent-cid)))))))

(defn ^:seon.flow/edge update-task!
"Updates `:plan`, `:summary`, `:tests`, `:proofs`, `:test-vars`, `:needs`, or `:done?` on your active `:seon.task/*` (or `:id` in `spec`)."
{:seon.repl/export true :malli/schema [:=> [:cat :map] [:maybe :map]]}
[spec]
(when *agent-ctx*
  (refine/apply-update-task! *agent-ctx* spec ctx/refresh-active! tx-main-sync!)))

(defn ^:seon.flow/edge assign-task!
"Assigns `:seon.task/id` `task-id` (and its `:scope`) to the active agent, preserving any existing `:seon.agent/tasks`."
{:seon.repl/export true :malli/schema [:=> [:cat :keyword] [:maybe :map]]}
[task-id]
(when-let [{:keys [conn agent-id]} *agent-ctx*]
  (let [mdb (store/head conn :main)
        t (d/pull mdb '[:seon.task/id :seon.task/scope] [:seon.task/id task-id])]
    (if-not (:seon.task/id t)
      {:ok? false :error (str "Task not found: " task-id)}
      (let [te (:e (first (d/datoms mdb :avet :seon.task/id task-id)))
            has-tasks? (contains? (:schema @conn) :seon.agent/tasks)
            existing (when has-tasks?
                       (into #{} (keep #(if (map? %) (:db/id %) %))
                             (:seon.agent/tasks (d/pull @conn '[:seon.agent/tasks] [:seon.agent/id agent-id]))))
            ent (cond-> {:seon.agent/id agent-id}
                  (and te has-tasks?) (assoc :seon.agent/tasks (conj (or existing #{}) te))
                  (:seon.task/scope t) (assoc :seon.agent/scope (:seon.task/scope t)))
            r (d/transact conn {:tx-data [ent]})]
        (ctx/refresh-active! (:db-after r) (:tx-data r))
        (when (first (d/datoms mdb :avet :seon.agent/id agent-id))
          (tx-main-sync! {:op :transact :tx-data [ent] :reason [:assign-task agent-id task-id]}))
        {:ok? true :agent-id agent-id :task task-id :scope (:seon.task/scope t)})))))

(defn ^:seon.flow/edge await!
  "Suspends until one or more outgoing call IDs resolve."
  {:seon.repl/export true :malli/schema [:=> [:cat :any] [:maybe :map]]}
  [call-or-calls]
  (when-let [{:keys [agent-id]} *agent-ctx*]
    (let [raw (if (or (vector? call-or-calls) (set? call-or-calls) (seq? call-or-calls)) call-or-calls [call-or-calls])
          ids (vec (keep coerce-call-id raw))]
      (tx-main-sync! {:op :await-calls :caller agent-id :call-ids ids}))))

(defn ^:seon.flow/edge call!
  "Synchronous shorthand for `(ask! callee prompt (assoc opts :await? true))`."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat :keyword :any] [:maybe :map]] [:=> [:cat :keyword :any [:maybe :map]] [:maybe :map]]]}
  ([callee prompt] (call! callee prompt {}))
  ([callee prompt opts] (ask! callee prompt (assoc opts :await? true))))

(defn ^:seon.flow/edge answer!
  "Settles an incoming structured call `call-id` with `value`."
  {:seon.repl/export true :malli/schema [:=> [:cat :any :any] [:maybe :map]]}
  [call-id value]
  (when-let [{:keys [agent-id]} *agent-ctx*]
    (if-let [cid (coerce-call-id call-id)]
      (tx-main-sync! {:op :answer-call :call-id cid :callee agent-id :result value})
      {:ok? false :error {:class :invalid-call-id :value call-id}})))

(defn ^:seon.flow/edge fail!
  "Settles an incoming structured call `call-id` as failed with `error`."
  {:seon.repl/export true :malli/schema [:=> [:cat :any :any] [:maybe :map]]}
  [call-id error]
  (when-let [{:keys [agent-id]} *agent-ctx*]
    (if-let [cid (coerce-call-id call-id)]
      (tx-main-sync! {:op :fail-call :call-id cid :callee agent-id :error error})
      {:ok? false :error {:class :invalid-call-id :value call-id}})))

(defn ^:seon.flow/edge cancel!
  "Cancels an outgoing pending call `call-id` (and cascading descendants)."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat :any] [:maybe :map]] [:=> [:cat :any :any] [:maybe :map]]]}
  ([call-id] (cancel! call-id "cancelled"))
  ([call-id reason]
   (if-let [cid (coerce-call-id call-id)]
     (tx-main-sync! {:op :cancel-call :call-id cid :reason (str reason)})
     {:ok? false :error {:class :invalid-call-id :value call-id}})))

(defn ^:seon.flow/edge calls
  "Returns `{:incoming [...] :outgoing [...]}` structured calls for the active agent."
  {:seon.repl/export true :malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (when-let [{:keys [conn agent-id]} *agent-ctx*]
    (let [mdb (store/head conn :main)
          pull-all (fn [attr]
                     (if (contains? (:schema mdb) attr)
                       (->> (d/datoms mdb :avet attr agent-id)
                            (map #(dissoc (d/pull mdb '[*] (:e %)) :db/id))
                            (sort-by #(or (:seon.agent.call/created-tx %) 0)) vec)
                       []))]
      {:incoming (pull-all :seon.agent.call/callee) :outgoing (pull-all :seon.agent.call/caller)})))

(defn ^:seon.flow/edge show-user!
  "Steers UI viewport (`:viewing`, `:mode`, `:sidebar?`, `:expand`, `:as-of-tx`, `:inspect-ref`, `:nav-back`, `:nav-forward`, `:expert-mode?`, `:lab-prefs`)."
  {:seon.repl/export true :malli/schema [:=> [:cat :map] [:maybe :map]]}
  [arg]
  (when-let [{:keys [out]} *agent-ctx*]
    (let [lp (cond-> (if (map? (:lab-prefs arg)) (:lab-prefs arg) {})
               (contains? arg :expert-mode?) (assoc :expert-mode? (boolean (:expert-mode? arg))))
          req (cond-> (merge {:ev :tx-main :op :show-user :user-id (or (:user-id arg) :user/default)}
                             (select-keys arg [:viewing :mode :selected-branch :as-of-tx :inspect-ref :push-inspect]))
                (some? (:sidebar? arg)) (assoc :sidebar? (boolean (:sidebar? arg)))
                (seq (:expand arg)) (assoc :expand (set (:expand arg)))
                (:clear-inspect arg) (assoc :clear-inspect true)
                (:nav-back arg) (assoc :nav-back true :pop-inspect true)
                (:nav-forward arg) (assoc :nav-forward true :forward-inspect true)
                (seq lp) (assoc :lab-prefs lp))]
      (a/>!! out req)
      (merge {:ok? true} (select-keys arg [:viewing :mode :sidebar? :as-of-tx :inspect-ref :push-inspect :expert-mode?])))))

(defn ^:seon.flow/edge start-bench-run!
  "Starts a parallel isolated benchmark run via the coordinator's `:start-bench-run` op."
  {:seon.repl/export true :malli/schema [:=> [:cat :any] :any]}
  [opts]
  (let [inst-op (requiring-resolve 'seon.bench.cluster/instance-op!)
        port (or (:coordinator-port opts) (System/getenv "SEON_PORT") 9998)]
    (inst-op {:base-url (str "http://127.0.0.1:" port)}
             {:op :start-bench-run :opts (if (map? opts) opts {}) :coordinator-port port})))

(defn ^:seon.flow/edge record-summary!
  "Records a durable `:seon.summary/entity` Chronicle milestone card on `:main`."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :any] [:maybe :map]]]}
  ([] (record-summary! {}))
  ([spec]
   (when-let [{:keys [agent-id]} *agent-ctx*]
     (tx-main-sync! {:op :record-summary :agent-id agent-id :spec (if (map? spec) spec {})}))))

(defn ^:seon.flow/edge wait-for-mail!
  "Marks this agent waiting (`:waiting? true :ready? false`) until a new message arrives."
  {:seon.repl/export true :malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (when-let [{:keys [conn agent-id]} *agent-ctx*]
    (d/transact conn {:tx-data [{:seon.agent/id agent-id :seon.agent/waiting? true :seon.agent/ready? false}]})
    {:ok? true :status :waiting :agent-id agent-id}))

(defn ^:seon.flow/edge chat!
  "Sends a live `:chat` message to `target-agent` (defaulting to the entry coordinator)."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat :any] [:maybe :map]] [:=> [:cat [:maybe :keyword] :any] [:maybe :map]]]}
  ([msg] (chat! nil msg))
  ([target-agent msg]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [target (or target-agent (entry-agent-id (store/head conn :main)))]
       (tx-main-sync! {:op :show-user :as-of-tx :live})
       (assoc (tx-main-sync! {:op :send-message :from :user :to target :type :chat :body (str msg)}) :to target)))))

(defn ^:seon.flow/edge monitor
  "Inspects parent/self/children merge-gate and call progress snapshot."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat [:maybe :keyword]] [:maybe :map]]]}
  ([] (monitor nil))
  ([target-agent]
   (when-let [{:keys [conn]} *agent-ctx*]
     (agent-monitor-snapshot conn target-agent))))

(defn ^:seon.flow/edge ready!
  "Optional explicit completion signal (`:ready? true`); normal agents auto-test & merge on prose reply with edits."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat [:maybe [:or :string :map]]] [:maybe :map]]]}
  ([] (ready! nil))
  ([summary]
   (when-let [{:keys [conn agent-id branch]} *agent-ctx*]
     (let [raw (cond (string? summary) summary (map? summary) (or (:summary summary) (:message summary)) :else nil)
           s (when (and (string? raw) (not (str/blank? raw))) (str/trim raw))
           ent (cond-> {:seon.agent/id agent-id :seon.agent/waiting? false :seon.agent/ready? true}
                 s (assoc :seon.agent/summary s))]
       (d/transact conn {:tx-data [ent]})
       (cond-> {:ok? true :status :ready :agent-id agent-id :branch branch}
         s (assoc :summary s))))))

(defn ^:seon.flow/edge finish-review!
  "Optional explicit review completion signal (same as `ready!`; prose reply with edits also auto-merges back)."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat [:maybe [:or :string :map]]] [:maybe :map]]]}
  ([] (ready! nil))
  ([summary] (ready! summary)))

(defn ^:seon.flow/edge export-ns-helpers!
  "Declaratively interns every public var in `ns-sym` carrying `:seon.repl/export` into `u`,
   preserving `:doc`, `:arglists`, `:malli/schema`, and `:seon.eval/budget-override` metadata with live var-indirect hot-reload."
  {:malli/schema [:=> [:cat :seon.host/ns :symbol] :int]}
  [u ns-sym]
  (let [n (volatile! 0)]
    (doseq [[sym v] (ns-publics ns-sym)
            :let [m (meta v)
                  ex (:seon.repl/export m)]
            :when ex]
      (let [names (cond (true? ex) [sym] (symbol? ex) [ex] (sequential? ex) ex :else [sym])
            keep-m (assoc (select-keys m [:doc :arglists :malli/schema :seon.eval/budget-override])
                          :seon.repl/source (symbol (str ns-sym) (name sym)))]
        (doseq [un names]
          (let [iv (intern u un (fn [& args] (apply v args)))]
            (alter-meta! iv merge keep-m)
            (vswap! n inc)))))
    @n))

(defn ^:seon.flow/edge install-call-and-ui-helpers!
  "Declaratively exports all `^:seon.repl/export` delegation, messaging, memory, and lifecycle helpers into `u`."
  {:malli/schema [:=> [:cat :seon.host/ns] :any]}
  [u]
  (+ (export-ns-helpers! u 'seon.fork.calls) (export-ns-helpers! u 'seon.health.coverage)))

(defn- normalize-pin-ref [item]
  (cond
    (and (map? item) (:seon.code.var/name item)) [:seon.code.var/name (:seon.code.var/name item)]
    (and (map? item) (:seon.code.ns/name item))  [:seon.code.ns/name (:seon.code.ns/name item)]
    (and (map? item) (:seon.code.doc/id item))   [:seon.code.doc/id (:seon.code.doc/id item)]
    (and (map? item) (:seon.schema/key item))    [:seon.schema/key (:seon.schema/key item)]
    (and (map? item) (:seon.task/id item))       [:seon.task/id (:seon.task/id item)]
    (and (map? item) (:seon.agent/id item))      [:seon.agent/id (:seon.agent/id item)]
    :else item))

(defn ^:seon.flow/edge pin!
  "Pins an entity ref or map into `:seon.agent/pinned` on `@ctx`."
  {:seon.repl/export true :malli/schema [:=> [:cat :any] [:maybe :map]]}
  [item]
  (when (:conn *agent-ctx*)
    (let [cur (vec (or (:seon.agent/pinned @ctx/ctx) []))
          ref-val (normalize-pin-ref item)
          nxt (if (some #(= % ref-val) cur) cur (conj cur ref-val))]
      (swap! ctx/ctx assoc :seon.agent/pinned nxt)
      {:pinned ref-val :count (count nxt)})))

(defn- compute-tune-patch
  "Pure shared patch builder for `:summary`, `:pinned`, `:context-prefs`, and `:seon.ctx/pattern`."
  {:malli/schema [:=> [:cat :map [:sequential :any] [:maybe :map]] :map]}
  [cur notes tune]
  (let [sum0 (str/trim (str (or (:seon.agent/summary cur) "")))
        lines0 (if (str/blank? sum0) [] (vec (remove str/blank? (str/split-lines sum0))))
        lines1 (reduce (fn [a n]
                         (let [s (str/trim (if (string? n) n (pr-str n)))]
                           (if (or (str/blank? s) (some #(= % s) a)) a (conj a s))))
                       lines0 notes)
        sum1 (str/join "\n" lines1)
        pin0 (vec (or (:seon.agent/pinned cur) []))
        pin1 (cond-> pin0
               (seq (:pin tune))
               (as-> p (reduce (fn [a x] (let [r (normalize-pin-ref x)] (if (some #(= % r) a) a (conj a r)))) p (:pin tune)))
               (seq (:unpin tune))
               (as-> p (let [rm (into #{} (map normalize-pin-ref) (:unpin tune))]
                         (vec (remove #(or (contains? rm %) (contains? rm (normalize-pin-ref %))) p)))))
        prefs0 (if (map? (:seon.agent/context-prefs cur)) (:seon.agent/context-prefs cur) {})
        pref-patch (when (map? tune)
                     (let [{:keys [recent-data-limit max-vars-shown max-schemas-shown extra-namespaces extra-schemas]} tune]
                       (cond-> {}
                         (integer? recent-data-limit) (assoc :recent-data-limit (long recent-data-limit))
                         (integer? max-vars-shown)    (assoc :max-vars-shown (long max-vars-shown))
                         (integer? max-schemas-shown) (assoc :max-schemas-shown (long max-schemas-shown))
                         (contains? tune :extra-namespaces) (assoc :extra-namespaces (mapv #(if (string? %) (symbol %) %) extra-namespaces))
                         (contains? tune :extra-schemas) (assoc :extra-schemas (vec extra-schemas)))))
        prefs1 (if (seq pref-patch) (merge prefs0 pref-patch) prefs0)
        pat (:pattern tune)
        patch (cond-> {}
                (and (not= sum1 sum0) (not (str/blank? sum1))) (assoc :seon.agent/summary sum1)
                (not= pin1 pin0) (assoc :seon.agent/pinned pin1)
                (seq pref-patch) (assoc :seon.agent/context-prefs prefs1)
                (and (vector? pat) (seq pat)) (assoc :seon.ctx/pattern pat))]
    {:patch patch :context-prefs prefs1 :summary sum1 :notes lines1 :pinned pin1}))

(defn ^:seon.flow/edge remember!
  "Appends a durable summary note onto `:seon.agent/summary` on self (`@ctx`) or on `target-agent-id`."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat :any] [:maybe :map]] [:=> [:cat :qualified-keyword :any] [:maybe :map]]]}
  ([note]
   (when *agent-ctx*
     (let [{:keys [patch notes summary]} (compute-tune-patch @ctx/ctx [note] nil)]
       (when (seq patch) (swap! ctx/ctx merge patch))
       {:remembered note :count (count notes) :summary summary})))
  ([target-aid note]
   (when-let [{:keys [conn agent-id]} *agent-ctx*]
     (if (= target-aid agent-id)
       (remember! note)
       (let [db @conn
             teid (:e (first (d/datoms db :avet :seon.agent/id target-aid)))]
         (if-not teid
           {:ok? false :error :agent-not-found :agent-id target-aid}
           (let [cur (d/pull db '[:seon.agent/summary] teid)
                 {:keys [patch notes summary]} (compute-tune-patch cur [note] nil)]
             (when (seq patch) (d/transact conn {:tx-data [(assoc patch :seon.agent/id target-aid)]}))
             {:ok? true :agent-id target-aid :remembered note :count (count notes) :summary summary})))))))

(defn ^:seon.flow/edge tune-context!
  "Updates `:seon.agent/context-prefs`, `:seon.ctx/pattern`, and `:pin`/`:unpin` on `@ctx`."
  {:seon.repl/export true :malli/schema [:=> [:cat :map] [:maybe :map]]}
  [opts]
  (when *agent-ctx*
    (let [{:keys [patch context-prefs]} (compute-tune-patch @ctx/ctx nil opts)]
      (when (seq patch) (swap! ctx/ctx merge patch))
      {:ok? true :context-prefs context-prefs})))

(defn ^:seon.flow/edge compact!
  "Compacts eval history by retracting selected `:turns`, `:through-turn`, or `:keep-last` (keeping `d/history` intact) and saving `:summary`/`:memory`/`:tune` onto `:agent-id` (default self)."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :any] [:maybe :map]]]}
  ([] (compact! {}))
  ([summary-or-opts]
   (when-let [{:keys [conn agent-id]} *agent-ctx*]
     (let [opts (if (map? summary-or-opts) summary-or-opts {:summary (str summary-or-opts)})
           {:keys [summary memory tune turns through-turn keep-last]} opts
           target-aid (or (:agent-id opts) agent-id)
           self? (= target-aid agent-id)
           notes (vec (remove #(str/blank? (str %)) (concat (when (and summary (not (str/blank? (str summary)))) [(str summary)]) memory)))
           db-before @conn
           target-eid (:e (first (d/datoms db-before :avet :seon.agent/id target-aid)))]
       (if (and (not self?) (nil? target-eid))
         {:ok? false :error :agent-not-found :agent-id target-aid}
         (let [_ (when (and self? (or (seq notes) (map? tune)))
                   (let [{:keys [patch]} (compute-tune-patch @ctx/ctx notes tune)]
                     (when (seq patch) (swap! ctx/ctx merge patch))))
               db-now @conn
               eval-ents (mapv #(d/pull db-now '[:db/id :seon.agent.eval/turn] (:e %))
                               (d/datoms db-now :avet :seon.agent.eval/agent target-aid))
               distinct-turns (vec (sort (distinct (keep :seon.agent.eval/turn eval-ents))))
               turn-set (when (coll? turns) (into #{} (map long) turns))
               keep-n (long (or keep-last 0))
               cut-turn (cond
                          (integer? through-turn) (long through-turn)
                          (pos? keep-n) (if (> (count distinct-turns) keep-n) (long (nth distinct-turns (- (count distinct-turns) keep-n 1))) -1)
                          :else Long/MAX_VALUE)
               to-retract (if turn-set
                            (filterv #(contains? turn-set (long (or (:seon.agent.eval/turn %) -1))) eval-ents)
                            (filterv #(<= (long (or (:seon.agent.eval/turn %) 0)) cut-turn) eval-ents))
               retract-tx (mapv #(vector :db/retractEntity (:db/id %)) to-retract)
               target-tx (when (and (not self?) (or (seq notes) (map? tune)))
                           (let [cur (d/pull db-now '[:seon.agent/summary :seon.agent/pinned :seon.agent/context-prefs :seon.ctx/pattern] target-eid)
                                 {:keys [patch]} (compute-tune-patch cur notes tune)]
                             (when (seq patch)
                               [(assoc patch :seon.agent/id target-aid)])))
               tx (vec (concat retract-tx target-tx))]
           (when (seq tx) (d/transact conn {:tx-data tx}))
           (let [rem (- (count eval-ents) (count to-retract))]
             {:ok? true :agent-id target-aid :retracted-evals (count to-retract)
              :remaining-evals rem :through-turn (when-not turn-set cut-turn)
              :turns (when turn-set (vec (sort turn-set))) :memories-saved (count notes)})))))))

(defn ^:seon.flow/edge set-canvas!
  "Paints live Canvas with Hiccup `[:div ...]` or `'ns/fn` (`(fn [db agent])` or 1-arg `:seon.ui/hiccup`),
   returning fast factual sanitizer/resolution feedback (zero browser calls)."
  {:seon.repl/export true
   :malli/schema [:function [:=> [:cat [:or [:vector :any] :qualified-symbol]] [:maybe :map]]
                  [:=> [:cat [:or [:vector :any] :qualified-symbol] [:maybe :map]] [:maybe :map]]]}
  ([hiccup-or-fn] (set-canvas! hiccup-or-fn nil))
  ([hiccup-or-fn _opts]
   (when-let [{:keys [conn agent-id]} *agent-ctx*]
     (let [db @conn
           aeid (:e (first (d/datoms db :avet :seon.agent/id agent-id)))
           cur (when aeid (d/pull db (quote [:seon.agent/canvas-hiccup :seon.agent/canvas-fn]) aeid))
           vec? (vector? hiccup-or-fn)
           tx (cond
                vec?
                (cond-> [{:seon.agent/id agent-id :seon.agent/canvas-hiccup hiccup-or-fn}]
                  (and aeid (some? (:seon.agent/canvas-fn cur))) (conj [:db/retract aeid :seon.agent/canvas-fn (:seon.agent/canvas-fn cur)]))
                (qualified-symbol? hiccup-or-fn)
                (cond-> [{:seon.agent/id agent-id :seon.agent/canvas-fn hiccup-or-fn}]
                  (and aeid (some? (:seon.agent/canvas-hiccup cur))) (conj [:db/retract aeid :seon.agent/canvas-hiccup (:seon.agent/canvas-hiccup cur)])))
           r (d/transact conn {:tx-data tx})
           _ (ctx/refresh-active! (:db-after r) (:tx-data r))
           dropped (when vec? (vec (remove #(= "Stripped forbidden tag :seon/fit" %) (:dropped (sanitize/sanitize-hiccup-with-report hiccup-or-fn {:allow-seon-actions? true})))))
           unresolved? (and (qualified-symbol? hiccup-or-fn) (nil? (fit/resolve-renderer-var hiccup-or-fn)))]
       (cond-> {:ok? true :agent-id agent-id :mode (if vec? :hiccup :fn) :tx (:max-tx (:db-after r))}
         (seq dropped) (assoc :dropped (vec dropped))
         unresolved? (assoc :warning "Renderer var not resolved yet"))))))

(defn ^:seon.flow/edge clear-canvas!
  "Clears custom `:seon.agent/canvas-hiccup` / `:seon.agent/canvas-fn` override."
  {:seon.repl/export true
   :malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (when-let [{:keys [conn agent-id]} *agent-ctx*]
    (let [db @conn
          aeid (:e (first (d/datoms db :avet :seon.agent/id agent-id)))
          cur (when aeid (d/pull db (quote [:seon.agent/canvas-hiccup :seon.agent/canvas-fn]) aeid))
          tx (cond-> []
               (and aeid (some? (:seon.agent/canvas-hiccup cur)))
               (conj [:db/retract aeid :seon.agent/canvas-hiccup (:seon.agent/canvas-hiccup cur)])
               (and aeid (some? (:seon.agent/canvas-fn cur)))
               (conj [:db/retract aeid :seon.agent/canvas-fn (:seon.agent/canvas-fn cur)]))]
      (when (seq tx)
        (let [r (d/transact conn {:tx-data tx})]
          (ctx/refresh-active! (:db-after r) (:tx-data r))))
      {:ok? true :agent-id agent-id :cleared (count tx)})))

(defn ^:seon.flow/edge schema
  "Inspects Malli schemas: `(user/schema)` lists all `:seon.schema/key`s; `(user/schema :ns/k)` shows form/doc/examples; `(user/schema \"term\"|#\"re\")` searches."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat] :any] [:=> [:cat :any] :any]]}
  ([] (when-let [{:keys [conn]} *agent-ctx*] (six/lookup-schemas @conn)))
  ([q] (when-let [{:keys [conn]} *agent-ctx*] (six/lookup-schemas @conn q))))