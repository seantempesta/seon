(ns seon.fork.helpers
  "Agent REPL `user/*` helpers & dynamic `*agent-ctx*`."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :as t]
            [datahike.api :as d]
            [jolt.async.remote :as remote]
            [jolt.ffi :as ffi]
            [jolt.host]
            [jolt.io-poller :as poller]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.health :as health]
            [seon.schema :as schema]
            [seon.store :as store]))

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

(defn install-agent-helpers! []
  (let [u (create-ns 'user)]
    (binding [*ns* u]
      (refer-clojure)
      (require '[datahike.api :as d]
               '[clojure.string :as str])
      (intern u 'conn-of
              (fn [] (:conn *agent-ctx*)))
      (intern u 'conn
              (fn [] (:conn *agent-ctx*)))
      (intern u 'db
              (fn [] (when-let [c (:conn *agent-ctx*)] @c)))
      (intern u 'source
              (fn [target]
                (when-let [{:keys [conn branch]} *agent-ctx*]
                  (let [bdb @conn
                        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
                        base-db (when (not= branch :main)
                                  (try (store/merge-base-db conn :main branch) (catch Throwable _ nil)))]
                    (cond
                      (keyword? target)
                      (let [pull-s #(let [m (d/pull % '[:seon.schema/key :seon.schema/form :seon.schema/doc
                                                        :seon.schema/examples :seon.schema/render]
                                                    [:seon.schema/key target])]
                                      (when (:seon.schema/key m) m))]
                        (or (pull-s bdb) (pull-s mdb)))

                      (and (string? target) (or (str/includes? target "#") (str/ends-with? target ".md")))
                      (if (str/includes? target "#")
                        (let [pull-d #(let [m (d/pull % '[:seon.code.doc/id :seon.code.doc/path
                                                          :seon.code.doc/heading :seon.code.doc/body]
                                                      [:seon.code.doc/id target])]
                                        (when (:seon.code.doc/id m)
                                          (str (:seon.code.doc/heading m "") (:seon.code.doc/body m ""))))]
                          (or (pull-d bdb) (pull-d mdb)))
                        (or (code/render-doc bdb target) (code/render-doc mdb target)))

                      :else
                      (let [s (if (string? target) (symbol target) target)
                            src-in (fn [db]
                                     (when-let [fkey (:seon.code.var/form (d/pull db '[:seon.code.var/form] [:seon.code.var/name s]))]
                                       (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key fkey]))))]
                        (if (namespace s)
                          (let [b-src (src-in bdb)
                                base-src (when base-db (src-in base-db))
                                m-src (src-in mdb)]
                            ;; Prefer branch edit when the branch actually modified this var; else show latest `:main`!
                            (if (and b-src (not= b-src base-src)) b-src (or m-src b-src)))
                          (let [ns-forms (fn [db]
                                           (->> (d/datoms db :avet :seon.code.form/ns s)
                                                (map #(d/pull db '[:seon.code.form/key :seon.code.form/order :seon.code.form/src] (:e %)))
                                                (sort-by #(or (:seon.code.form/order %) 0))))
                                m-fs (ns-forms mdb)
                                b-by-k (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) (ns-forms bdb))
                                base-by-k (if base-db
                                            (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) (ns-forms base-db))
                                            {})
                                merged (mapv (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                                               (let [bs (get b-by-k key)]
                                                 (if (and bs (not= bs (get base-by-k key))) bs src)))
                                             (if (seq m-fs) m-fs (ns-forms bdb)))]
                            (str/join "\n\n" merged)))))))))
      (intern u 'as-of
              (fn [tx]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (d/as-of @conn (long tx)))))
      (intern u 'callers
              (fn [sym]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [db @conn
                        s (if (string? sym) (symbol sym) sym)]
                    (->> (d/datoms db :avet :seon.code.var/refs s)
                         (keep #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                         distinct sort vec)))))
      (intern u 'callees
              (fn [sym]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [db @conn
                        s (if (string? sym) (symbol sym) sym)
                        v (d/pull db '[:seon.code.var/name :seon.code.var/refs
                                       :seon.code.var/schema-refs :seon.code.var/keywords]
                                  [:seon.code.var/name s])]
                    (when (:seon.code.var/name v)
                      {:refs (vec (sort (:seon.code.var/refs v)))
                       :schema-refs (vec (sort (:seon.code.var/schema-refs v)))
                       :keywords (vec (sort (:seon.code.var/keywords v)))})))))
      (intern u 'tests-for
              (fn [sym-or-ns]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (code/reaching-tests @conn sym-or-ns))))
      (intern u 'remove-var!
              (fn
                ([sym] ((resolve 'user/remove-var!) sym {}))
                ([sym {:keys [force?]}]
                 (when-let [{:keys [conn]} *agent-ctx*]
                   (let [db @conn
                         s (if (string? sym) (symbol sym) sym)
                         k (str s)
                         callers (->> (d/datoms db :avet :seon.code.var/refs s)
                                      (keep #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                                      (remove #(= % s))
                                      distinct sort vec)]
                     (if (and (seq callers) (not force?))
                       {:ok? false
                        :error :has-callers
                        :var s
                        :callers callers
                        :hint "Update callers first or pass {:force? true}."}
                       (let [has-form? (boolean (first (d/datoms db :avet :seon.code.form/key k)))
                             has-var?  (boolean (first (d/datoms db :avet :seon.code.var/name s)))
                             tx (cond-> []
                                  has-form? (conj [:db/retractEntity [:seon.code.form/key k]])
                                  has-var?  (conj [:db/retractEntity [:seon.code.var/name s]]))
                             r (when (seq tx) (d/transact conn {:tx-data tx}))]
                         (when (namespace s)
                           (when-let [ns-obj (find-ns (symbol (namespace s)))]
                             (ns-unmap ns-obj (symbol (name s)))))
                         (when r (ctx/refresh-active! (:db-after r) (:tx-data r)))
                         {:ok? true
                          :removed s
                          :retracted-form? has-form?
                          :retracted-var? has-var?
                          :tx (when r (:max-tx (:db-after r)))})))))))
      (intern u 'diff
              (fn []
                (when-let [{:keys [conn branch]} *agent-ctx*]
                  (let [bdb @conn
                        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
                        base-cid (when (not= branch :main)
                                   (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
                        ops (if base-cid
                              (code/changed-form-ops conn base-cid (d/commit-id bdb))
                              {:upserts [] :retracted-keys #{} :schema-keys #{}})
                        errs (if (= branch :main)
                               []
                               (health/caused-by-branch mdb bdb {}))]
                    {:branch branch
                     :upserts (vec (sort (map :seon.code.form/key (:upserts ops))))
                     :retracted (vec (sort (:retracted-keys ops)))
                     :schemas (vec (sort (:schema-keys ops)))
                     :health-errors errs}))))
      (intern u 'doc!
              (fn
                ([doc-id body] ((resolve 'user/doc!) doc-id nil body))
                ([doc-id heading body]
                 (when-let [{:keys [conn]} *agent-ctx*]
                   (let [db @conn
                         did (str doc-id)
                         existing (d/pull db '[:seon.code.doc/id :seon.code.doc/path
                                               :seon.code.doc/order :seon.code.doc/heading]
                                          [:seon.code.doc/id did])
                         [path slug] (str/split did #"#" 2)
                         eff-path (or (:seon.code.doc/path existing) path)
                         eff-head (cond
                                    (some? heading) (str heading)
                                    (:seon.code.doc/id existing) (:seon.code.doc/heading existing "")
                                    :else (str "## " (or slug "Section")))
                         raw-body (str body)
                         eff-body (cond-> raw-body
                                    (and (not (str/blank? eff-head)) (not (str/starts-with? raw-body "\n")))
                                    (#(str "\n" %))
                                    (not (str/ends-with? raw-body "\n"))
                                    (str "\n"))
                         eff-order (if (:seon.code.doc/id existing)
                                     (long (:seon.code.doc/order existing 0))
                                     (inc (reduce max -1 (map :seon.code.doc/order (code/doc-sections db eff-path)))))
                         ent {:seon.code.doc/id did
                              :seon.code.doc/path eff-path
                              :seon.code.doc/order eff-order
                              :seon.code.doc/heading eff-head
                              :seon.code.doc/body eff-body}
                         r (d/transact conn {:tx-data [ent]})]
                     (ctx/refresh-active! (:db-after r) (:tx-data r))
                     {:ok? true :doc-id did :path eff-path :tx (:max-tx (:db-after r))})))))
      (intern u 'history
              (fn [target]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [db @conn
                        hdb (d/history db)
                        [id-attr id-val val-attr]
                        (cond
                          (keyword? target) [:seon.schema/key target :seon.schema/form]
                          (and (string? target) (str/includes? target "#")) [:seon.code.doc/id target :seon.code.doc/body]
                          :else [:seon.code.form/key (str target) :seon.code.form/src])
                        eids (into #{} (comp (filter :added) (map :e))
                                   (d/datoms hdb :avet id-attr id-val))]
                    (->> eids
                         (mapcat #(d/datoms hdb :eavt % val-attr))
                         (filter :added)
                         (sort-by :tx)
                         (mapv (fn [dt]
                                 {:tx (:tx dt)
                                  :value (:v dt)})))))))
      (intern u 'past-evals
              (fn
                ([] ((resolve 'user/past-evals) {}))
                ([opts]
                 (when-let [{:keys [conn agent-id]} *agent-ctx*]
                   (let [m (cond
                             (integer? opts) {:limit opts}
                             (string? opts)  {:grep opts}
                             (map? opts)     opts
                             :else           {})
                         aid (or (:agent-id m) agent-id)
                         lim (long (or (:limit m) 15))
                         retracted-only? (boolean (:retracted-only? m))
                         q (when-not (str/blank? (str (:grep m)))
                             (str/lower-case (str (:grep m))))
                         rows (store/agent-historical-evals @conn aid)
                         filtered (cond->> rows
                                    retracted-only? (filter :seon.agent.eval/retracted-tx)
                                    q (filter (fn [e]
                                                (let [s (str/lower-case
                                                         (str (:seon.agent.eval/code e) "\n"
                                                              (:seon.agent.eval/thought e) "\n"
                                                              (:seon.agent.eval/value e) "\n"
                                                              (:seon.agent.eval/error e)))]
                                                  (str/includes? s q)))))]
                     (->> filtered
                          (take-last lim)
                          (mapv (fn [e]
                                  (cond-> {:tx (:seon.agent.eval/tx e)
                                           :turn (:seon.agent.eval/turn e)
                                           :handle (:seon.agent.eval/handle e)}
                                    (:seon.agent.eval/retracted-tx e)
                                    (assoc :retracted-tx (:seon.agent.eval/retracted-tx e))
                                    (not (str/blank? (str (:seon.agent.eval/code e))))
                                    (assoc :code (let [c (str (:seon.agent.eval/code e))]
                                                   (if (> (count c) 140) (str (subs c 0 140) "…") c)))
                                    (not (str/blank? (str (:seon.agent.eval/thought e))))
                                    (assoc :thought (let [t (str (:seon.agent.eval/thought e))]
                                                      (if (> (count t) 140) (str (subs t 0 140) "…") t)))
                                    (:seon.agent.eval/error e)
                                    (assoc :error (:seon.agent.eval/error e)))))))))))
      (intern u 'transact!
              (fn [tx-data]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [r (d/transact conn {:tx-data tx-data})]
                    (ctx/refresh-active! (:db-after r) (:tx-data r))
                    {:tx (:max-tx (:db-after r))}))))
      (intern u 'task
              (fn []
                (when-let [{:keys [conn agent-id]} *agent-ctx*]
                  (let [ag (d/pull @conn '[:seon.agent/task :seon.agent/goal :seon.agent/scope]
                                   [:seon.agent/id agent-id])]
                    (if-let [tid (:seon.agent/task ag)]
                      (d/pull @conn '[*] [:seon.task/id tid])
                      ag)))))
      (intern u 'main-db
              (fn [] (when-let [{:keys [conn]} *agent-ctx*]
                       (store/head conn :main))))
      (intern u 'health
              (fn
                ([]
                 (when-let [{:keys [conn agent-id branch]} *agent-ctx*]
                   (let [role (:seon.agent/role (d/pull @conn '[:seon.agent/role] [:seon.agent/id agent-id]))
                         target-db (if (or (= branch :main) (= role :coordinator))
                                     (store/head conn :main)
                                     @conn)]
                     (health/report target-db))))
                ([which]
                 (when-let [{:keys [conn]} *agent-ctx*]
                   (health/report (if (= which :main) (store/head conn :main) @conn))))))
      (intern u 'config
              (fn
                ([] (when-let [{:keys [conn agent-id]} *agent-ctx*]
                      (:effective (store/config-map (store/head conn :main) agent-id))))
                ([k] (when-let [{:keys [conn agent-id]} *agent-ctx*]
                       (store/config-val (store/head conn :main) agent-id k)))))
      (intern u 'register-schema!
              (fn [key form & [{:keys [doc examples render]}]]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [{:keys [sample entity dh-decls]}
                        (schema/validate-schema-registration!
                         @conn {:key key :form form :doc doc :examples examples :render render})]
                    (when (seq dh-decls)
                      (d/transact conn {:tx-data dh-decls}))
                    (let [r (d/transact conn {:tx-data [entity]})]
                      (ctx/refresh-active! (:db-after r) (:tx-data r)))
                    {:registered key :sample sample :derived-attributes (mapv :db/ident dh-decls)}))))
      (intern u 'search
              (fn [substr]
                (when-let [{:keys [conn branch]} *agent-ctx*]
                  (let [bdb @conn
                        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
                        q (str/lower-case (str substr))
                        find-vals (fn [attr lim]
                                    (->> (concat (d/datoms mdb :aevt attr)
                                                 (when (not= bdb mdb) (d/datoms bdb :aevt attr)))
                                         (keep #(when (str/includes? (str/lower-case (str (:v %))) q) (:v %)))
                                         distinct sort (take lim) vec))]
                    {:vars (find-vals :seon.code.var/name 25)
                     :schemas (find-vals :seon.schema/key 20)
                     :docs (find-vals :seon.code.doc/id 20)}))))
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
      (intern u 'canvas!
              (fn
                ([m-or-hiccup]
                 (when *agent-ctx*
                   (let [m (cond
                             (map? m-or-hiccup) (select-keys m-or-hiccup [:title :summary :body])
                             (vector? m-or-hiccup) {:body m-or-hiccup}
                             (string? m-or-hiccup) {:summary m-or-hiccup}
                             :else {:body m-or-hiccup})]
                     (swap! ctx/ctx assoc :seon.agent/canvas m)
                     {:canvas-updated true :keys (vec (keys m))})))
                ([title body]
                 ((resolve 'user/canvas!) {:title (str title) :body body}))
                ([title summary body]
                 ((resolve 'user/canvas!) {:title (str title) :summary (str summary) :body body}))))
      (intern u 'present!
              (fn
                ([k hiccup]
                 (let [title (if (keyword? k) (name k) (str k))]
                   ((resolve 'user/canvas!) {:title title :body hiccup})))
                ([_k title hiccup]
                 ((resolve 'user/canvas!) {:title (str title) :body hiccup}))))
      (intern u 'unpresent!
              (fn [_k]
                (when *agent-ctx*
                  (swap! ctx/ctx assoc :seon.agent/canvas {})
                  {:cleared true})))
      (intern u 'clear-stage!
              (fn []
                (when *agent-ctx*
                  (swap! ctx/ctx assoc :seon.agent/canvas {} :seon.agent/stage [])
                  {:cleared true})))
      (intern u 'set-render!
              (fn [sym]
                (when *agent-ctx*
                  (swap! ctx/ctx assoc :seon.agent/render-fn sym)
                  {:render-fn sym})))
      (intern u 'merge-self!
              (fn []
                ((resolve 'user/ready!))))
      (intern u 'ask!
              (fn
                ([callee prompt]
                 ((resolve 'user/ask!) callee prompt {}))
                ([callee prompt opts]
                 (when-let [{:keys [conn agent-id]} *agent-ctx*]
                   (let [parent-cid (or (:parent-call opts) (active-incoming-call-id conn agent-id))]
                     (tx-main-sync! (cond-> (merge {:op :create-call
                                                    :caller agent-id
                                                    :callee callee
                                                    :prompt (str prompt)}
                                                   opts)
                                      parent-cid (assoc :parent-call parent-cid))))))))
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
      (intern u 'wait-for-mail!
              (fn []
                (when-let [{:keys [conn agent-id]} *agent-ctx*]
                  (d/transact conn {:tx-data [{:seon.agent/id agent-id :seon.agent/waiting? true :seon.agent/ready? false}]})
                  :waiting)))
      (intern u 'ready!
              (fn []
                (when-let [{:keys [conn agent-id]} *agent-ctx*]
                  (d/transact conn {:tx-data [{:seon.agent/id agent-id :seon.agent/waiting? false :seon.agent/ready? true}]})
                  :ready))))))
