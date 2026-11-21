(ns seon.server
  "The `:server` Flow proc (`:io` workload):
   Single boundary for `bin/seon`, MCP tools, live `--take-over` swap-overs, and callable UI forms."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.git :as git]
            [seon.health :as health]
            [seon.merge :as merge]
            [seon.reconciler :as reconciler]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.health.metrics :as hmetrics]
            [seon.render.wire :as rwire]
            [seon.transactor.calls :as tcalls]))

(defn- reply! [ch val]
  (when ch
    (a/put! ch val)
    (a/close! ch)))

(defn system-status-snapshot
"Computes a live system snapshot (active non-archived agents & branches, counts, tasks, config)."
{:malli/schema [:=> [:cat :seon.host/conn :map [:maybe :string]] :map]}
[conn cfg repo-dir]
(let [db @conn
      store (:store db)
      all-branches (vec (sort (d/branches conn)))
      tasks (->> (d/datoms db :aevt :seon.task/id)
                 (map #(d/pull db '[:seon.task/id :seon.task/scope :seon.task/title
                                    :seon.task/done? :seon.task/tests] (:e %)))
                 (sort-by #(str (:seon.task/id %)))
                 vec)
      all-ag-dts (vec (d/datoms db :aevt :seon.agent/id))
      archived-brs (if (contains? (:schema db) :seon.agent/archived?)
                     (let [arch-eids (into #{} (keep #(when (:v %) (:e %))) (d/datoms db :aevt :seon.agent/archived?))]
                       (into #{} (keep (fn [e] (:seon.agent/branch (d/pull db '[:seon.agent/branch] e)))) arch-eids))
                     #{})
      agents (->> all-ag-dts
                  (keep #(reconciler/derive-agent-status db store {} #{} #{} (:v %)))
                  (sort-by #(str (:id %)))
                  vec)
      active-agent-brs (into #{:db :main} (keep :branch) agents)
      branches (filterv #(or (contains? active-agent-brs %) (not (contains? archived-brs %))) all-branches)
      archived-agents (- (count all-ag-dts) (count agents))
      archived-branches (- (count all-branches) (count branches))
      green-tests (count (filter #(true? (:v %)) (d/datoms db :aevt :seon.test/pass?)))
      forms-count (count (d/datoms db :aevt :seon.code.form/key))
      vars-count (count (d/datoms db :aevt :seon.code.var/name))
      schemas-count (count (d/datoms db :aevt :seon.schema/key))
      docs-count (count (d/datoms db :aevt :seon.code.doc/id))
      git-ent (d/pull db '[:seon.git/commit :seon.git/branch] [:seon.git/repo :main])]
  {:ok? true
   :main-tx (:max-tx db)
   :commit (str (d/commit-id db))
   :git (:seon.git/commit git-ent)
   :repo-dir repo-dir
   :store-path (get-in cfg [:store :path])
   :forms forms-count
   :vars vars-count
   :schemas schemas-count
   :docs docs-count
   :derived-dh-attributes (count (:schema db))
   :cached-green-tests green-tests
   :config (:effective (store/config-map db))
   :branches branches
   :archived-branches archived-branches
   :tasks tasks
   :agents agents
   :archived-agents archived-agents
   :learning-metrics (dissoc (hmetrics/agent-learning-metrics conn db {:window 10}) :recent-agents)}))

(defn explore-var [db var-sym]
  (when-let [dt (first (d/datoms db :avet :seon.code.var/name var-sym))]
    (let [v (dissoc (d/pull db '[*] (:e dt)) :db/id)
          f (when-let [fk (:seon.code.var/form v)]
              (d/pull db '[:seon.code.form/src :seon.code.form/hash] [:seon.code.form/key fk]))
          callers (->> (d/datoms db :avet :seon.code.var/refs var-sym)
                       (keep #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                       sort
                       vec)]
      (assoc v
             :src (:seon.code.form/src f)
             :hash (:seon.code.form/hash f)
             :callers callers))))

(defn explore-schema [db schema-key]
  (when-let [dt (first (d/datoms db :avet :seon.schema/key schema-key))]
    (let [s (dissoc (d/pull db '[*] (:e dt)) :db/id)
          users (->> (d/datoms db :avet :seon.code.var/schema-refs schema-key)
                     (keep #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                     sort
                     vec)
          dh-attr (get (:schema db) schema-key)
          samples (schema/sample-schema db schema-key 5)]
      (cond-> (assoc s :used-by users :samples (mapv pr-str samples))
        dh-attr (assoc :datahike-attribute dh-attr)))))

(defn server-proc
  {:malli/schema [:function [:=> [:cat] :any] [:=> [:cat :any] :any] [:=> [:cat :any :any] :any] [:=> [:cat :any :any :any] :any]]}
  ([] {:params {:in-ch "External request channel `{:req map :reply-ch chan}`"
                :conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :repo-dir "Git repository working tree"
                :on-hand-over "Callback for `--take-over`"
                :on-switch-over "Callback for atomic build + --self-test + --take-over"}
       :ins {}
       :outs {:tx "Write operations routed to `:transactor`"
              :eval "REPL evaluation requests routed to `:agents`"
              :batch "Test run requests routed to `:test`"
              :git "Branch git-ref syncs routed to `:git`"}
       :workload :io
       :doc "External CLI / MCP / HTTP / Take-Over command boundary."
       :ping-map-fn (fn [st] {:requests-served (:served st)})})
  ([{:keys [in-ch conn cfg repo-dir on-hand-over on-switch-over]}]
   {:in-ch in-ch
    :conn conn
    :cfg cfg
    :repo-dir repo-dir
    :on-hand-over on-hand-over
    :on-switch-over on-switch-over
    :served 0
    ::flow/in-ports {:ext in-ch}})
  ([st _] st)
  ([st _ envelope]
   (if (nil? envelope)
     [st nil]
     (let [{:keys [req reply-ch]} envelope
           op (:op req)
           st' (update st :served inc)]
       (try
         (case op
           :status
           (do (reply! reply-ch (system-status-snapshot (:conn st) (:cfg st) (:repo-dir st)))
               [st' nil])

           :agent-context
           (let [main-db @(:conn st)
                 b (or (:branch req) :branch/mcp)
                 aid (or (:agent-id req) :agent/mcp)
                 branch-db (or (store/head (:conn st) b) main-db)
                 cur-ag (or (when (first (d/datoms branch-db :avet :seon.agent/id aid))
                              (d/pull branch-db '[:seon.agent/task :seon.agent/scope] [:seon.agent/id aid]))
                            (when (first (d/datoms main-db :avet :seon.agent/id aid))
                              (d/pull main-db '[:seon.agent/task :seon.agent/scope] [:seon.agent/id aid])))
                 req-task (:task req)
                 req-scope (:scope req)
                 eff-task (or req-task
                              (:seon.agent/task cur-ag)
                              (tcalls/best-task-for-scope main-db (or req-scope (:seon.agent/scope cur-ag)))
                              (when (first (d/datoms main-db :avet :seon.task/id :template/discover-and-plan))
                                :template/discover-and-plan))
                 eff-scope (or req-scope
                               (:seon.agent/scope cur-ag)
                               (when (and eff-task (first (d/datoms main-db :avet :seon.task/id eff-task)))
                                 (:seon.task/scope (d/pull main-db '[:seon.task/scope] [:seon.task/id eff-task]))))
                 opts (cond-> {}
                        eff-task (assoc :task eff-task)
                        eff-scope (assoc :scope eff-scope)
                        (seq (:extra-namespaces req)) (assoc :extra-namespaces (:extra-namespaces req))
                        (contains? req :include-tail?) (assoc :include-tail? (boolean (:include-tail? req))))
                 seen (store/agent-seen-message-ids branch-db aid)
                 unread (->> (d/datoms main-db :avet :seon.agent.message/to aid)
                             (map #(d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq
                                                     :seon.agent.message/from :seon.agent.message/type
                                                     :seon.agent.message/body] (:e %)))
                             (remove #(contains? seen (:seon.agent.message/id %)))
                             (sort-by :seon.agent.message/seq)
                             vec)
                 include-ctx? (if (contains? req :include-context?) (boolean (:include-context? req)) true)
                 ctx-text (when include-ctx?
                            (rwire/render-agent-context main-db branch-db aid opts))]
             (reply! reply-ch {:ok? true
                               :branch b
                               :agent-id aid
                               :task eff-task
                               :scope eff-scope
                               :context-text ctx-text
                               :unread unread})
             [st' nil])

           :explore-var
           (let [v (explore-var @(:conn st) (:var req))]
             (reply! reply-ch (if v {:ok? true :var v} {:ok? false :error (str "Var not found: " (:var req))}))
             [st' nil])

           :explore-schema
           (let [s (explore-schema @(:conn st) (:key req))]
             (reply! reply-ch (if s {:ok? true :schema s} {:ok? false :error (str "Schema not found: " (:key req))}))
             [st' nil])

           :health
           (do (reply! reply-ch (health/report @(:conn st)))
               [st' nil])

           :eval
           (let [aid (:agent-id req)
                 raw-b (or (:branch req)
                           (when aid
                             (:seon.agent/branch (d/pull @(:conn st) '[:seon.agent/branch] [:seon.agent/id aid])))
                           :branch/repl)
                 b (if (= raw-b :main) :branch/repl raw-b)]
             [st' {:eval [(assoc req :branch b :reply-ch reply-ch)]}])

           :test
           [st' {:batch [(assoc req :reply-ch reply-ch)]}]

           (:create-task :spawn-agent :send-message :show-user :merge :transact :toggle-pause-agent
            :register-schema :set-config :record-fault :retract-fault :resolve-fault
            :create-call :await-calls :answer-call :fail-call :cancel-call :settle-calls)
           [st' {:tx [(assoc req :reply-ch reply-ch)]}]

           :import-file
           (let [{:keys [path branch text]} req
                 b (or branch :main)
                 repo (:repo-dir st)
                 f (when (and (nil? text) repo) (io/file repo path))
                 content (or text (when (and f (.isFile f)) (slurp f)))
                 exec? (boolean (and f (.canExecute f)))]
             (if (nil? content)
               (do (reply! reply-ch {:ok? false :error (str "File not found: " path)})
                   [st' nil])
               (if (= b :main)
                 (let [tx (code/file-import-tx @(:conn st) path content exec?)]
                   [st' {:tx [{:op :transact :tx-data tx :reason [:import-file path] :reply-ch reply-ch}]}])
                 (do
                   (store/branch! (:conn st) :main b)
                   (let [bconn (store/connect (:cfg st) b)]
                     (try
                       (let [tx (code/file-import-tx @bconn path content exec?)
                             r (d/transact bconn {:tx-data tx})]
                         (reply! reply-ch {:ok? true :branch b :path path :tx (:max-tx (:db-after r))}))
                       (finally
                         (d/release bconn))))
                   [st' (if (:repo-dir st) {:git [{:op :sync-branch :branch b}]} nil)]))))

           :diff
           (let [b (:branch req)
                 plan (merge/three-way (:conn st) (:cfg st) b)
                 exported (when (:repo-dir st)
                            (git/export-branch-ref! (:conn st) (:cfg st) (:repo-dir st) b))
                 git-diff (when exported
                            (git/git! (:repo-dir st) "diff" "HEAD" (:ref exported)))]
             (reply! reply-ch {:ok? (:ok? plan)
                               :branch b
                               :touched-paths (:touched-paths plan)
                               :entity-diffs (:entity-diffs plan)
                               :conflicts (:conflicts plan)
                               :git-ref (:ref exported)
                               :diff git-diff})
             [st' nil])

           :start-bench-run
           (let [opts (merge {:repo-dir (:repo-dir st)
                              :seed-store (get-in st [:cfg :store :path])}
                             (or (:opts req) {}))
                 start-fn (requiring-resolve 'seon.bench.cluster/start-bench-run-async!)
                 inst-op (requiring-resolve 'seon.bench.cluster/instance-op!)
                 in-ch (:in-ch st)
                 coord-url (str "http://127.0.0.1:" (or (:coordinator-port req) (System/getenv "SEON_PORT") 9998))
                 publish! (fn [ent]
                            (let [tx-req {:op :transact :tx-data [ent] :reason [:bench-run (:seon.bench.run/id ent)]}]
                              (if in-ch
                                (a/put! in-ch {:req tx-req})
                                (try (inst-op {:base-url coord-url} tx-req) (catch Throwable _ nil)))))
                 ans (start-fn opts publish!)]
             (reply! reply-ch ans)
             [st' {::flow/report [(str "[:server] started live benchmark run " (:run-id ans) " (" (:total ans) " tasks)")]}])

           :hand-over
           (if-let [ho (:on-hand-over st)]
             (do (a/thread (ho reply-ch))
                 [st' {::flow/report ["[:server] initiating live `--take-over` hand-over to successor binary..."]}])
             (do (reply! reply-ch {:ok? false :error "Hand-over not configured on this instance"})
                 [st' nil]))

           :switch-over
           (if-let [sw (:on-switch-over st)]
             (do (a/thread
                   (let [ans (try (sw) (catch Throwable ex {:ok? false :error (.getMessage ex)}))]
                     (reply! reply-ch ans)))
                 [st' {::flow/report ["[:server] initiating atomic build + --self-test + --take-over switch-over..."]}])
             (do (reply! reply-ch {:ok? false :error "Switch-over not configured on this instance"})
                 [st' nil]))

           (do (reply! reply-ch {:ok? false :error (str "Unknown server op: " op)})
               [st' nil]))
         (catch Throwable ex
           (reply! reply-ch {:ok? false :error (.getMessage ex)})
           [st' {::flow/error [{:proc :server :op op :error (.getMessage ex)}]}]))))))