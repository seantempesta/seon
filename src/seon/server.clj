(ns seon.server
  "The `:server` Flow proc (`:io` workload):
   Single boundary for `bin/seon`, MCP tools, live `--take-over` swap-overs, and callable UI forms."
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.browser :as browser]
            [seon.code :as code]
            [seon.git :as git]
            [seon.health :as health]
            [seon.merge :as merge]
            [seon.reconciler :as reconciler]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.health.metrics :as hmetrics]
            [seon.render :as render]
            [seon.render.wire :as rwire]
            [seon.transactor.calls :as tcalls]
            [seon.vector :as vec]
            [seon.vector.flow :as vflow]
            [seon.git.forks :as forks]))

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

(defn explore-var
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:maybe :map]]}
  [db var-sym]
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

(defn explore-schema
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword] [:maybe :map]]}
  [db schema-key]
  (when-let [dt (first (d/datoms db :avet :seon.schema/key schema-key))]
    (let [s (dissoc (d/pull db '[*] (:e dt)) :db/id)
          se (:e dt)
          var-eids (into #{} (map :e)
                         (concat (when (contains? (:schema db) :seon.code.var/input)
                                   (d/datoms db :avet :seon.code.var/input se))
                                 (when (contains? (:schema db) :seon.code.var/optional-input)
                                   (d/datoms db :avet :seon.code.var/optional-input se))
                                 (when (contains? (:schema db) :seon.code.var/output)
                                   (d/datoms db :avet :seon.code.var/output se))))
          users (->> var-eids
                     (keep #(:seon.code.var/name (d/pull db '[:seon.code.var/name] %)))
                     sort
                     vec)
          dh-attr (get (:schema db) schema-key)
          samples (schema/sample-schema db schema-key 5)]
      (cond-> (assoc s :used-by users :samples (mapv pr-str samples))
        dh-attr (assoc :datahike-attribute dh-attr)))))

(defn- vectors-fallback-reply [db op req]
  (if (= op :vector-status)
    {:ok? true :enabled? false :indexed-count (vflow/indexed-count db)
     :pending-count 0 :in-flight? false :worker-alive? false
     :batches-done 0 :errors 0 :backpressure-active? false}
    (let [q (:query req)]
      (if (and (= op :embed-search)
               (or (and (vector? q) (seq q) (number? (first q)))
                   (int? q)
                   (and (vector? q) (= 2 (count q)) (keyword? (first q)))))
        (let [hits (vec/near db q (long (or (:k req) 10)) (or (:opts req) {}))]
          {:ok? true :query q :hits hits :results (vflow/describe-hits db hits)})
        {:ok? false :enabled? false :error :vectors-unavailable :results []}))))

(defn- browser-target-url [st req]
  (or (when (and (string? (:url req)) (not (str/blank? (:url req)))) (:url req))
      (str "http://127.0.0.1:" (or (:port req) (:port st) (System/getenv "SEON_PORT") 9998) "/")))

(defn- ^:seon.flow/edge handle-browser-op! [st req reply-ch]
  (let [url (browser-target-url st req)
        op (:op req)]
    (case op
      :render-hiccup-frames
      (let [html (cond
                   (string? (:html req)) (:html req)
                   (contains? req :hiccup) (render/hiccup->html (:hiccup req))
                   :else "")
            opts (select-keys req [:widths :theme :height :quality :max-width :label-prefix
                                   :timeout-ms :settle-ms :binary-path])]
        (a/thread
          (let [ans (try (browser/render-hiccup-frames! url html opts)
                         (catch Throwable ex
                           {:ok? false :error (or (.getMessage ex) (str ex)) :frames []}))]
            (reply! reply-ch ans))))

      :measure-dom
      (let [opts (select-keys req [:width :height :selector :max-elements :snippets
                                   :timeout-ms :settle-ms :binary-path])]
        (a/thread
          (let [ans (try (browser/measure-live-url! url opts)
                         (catch Throwable ex
                           {:ok? false :error (or (.getMessage ex) (str ex)) :url url}))]
            (reply! reply-ch ans)))))))

(defn- ^:seon.flow/edge handle-fork-op!
  "`:push` runs `forks/push-all!` (never-force; `:dry-run?` reads only) off the proc thread; `:index-forks`
   imports `:forks` origins (default all `forks/fork-repos`) onto a non-`:main` `:branch` via `forks/index-fork-tx`."
  [st req reply-ch]
  (let [repo (:repo-dir st)
        b (:branch req)]
    (cond
      (nil? repo)
      (do (reply! reply-ch {:ok? false :error "No repo-dir configured"}) nil)

      (= :push (:op req))
      (do (a/thread
            (reply! reply-ch (try (forks/push-all! repo {:dry-run? (boolean (:dry-run? req))})
                                  (catch Throwable ex {:ok? false :error (.getMessage ex)}))))
          nil)

      (or (nil? b) (= b :main))
      (do (reply! reply-ch {:ok? false :error ":index-forks requires a non-:main :branch"}) nil)

      :else
      (do (store/branch! (:conn st) :main b)
          (let [bconn (store/connect (:cfg st) b)]
            (try
              (let [counts (into {}
                                 (map (fn [o]
                                        (let [tx (forks/index-fork-tx @bconn repo o)]
                                          (d/transact bconn {:tx-data tx})
                                          [o (count tx)])))
                                 (or (seq (:forks req)) (sort (keys forks/fork-repos))))]
                (reply! reply-ch {:ok? true :branch b :forks counts :tx (:max-tx @bconn)}))
              (finally
                (d/release bconn))))
          {:git [{:op :sync-branch :branch b}]}))))

(defn- ^:seon.flow/edge agent-context-reply!
  "Replies to an `:agent-context` request with the rendered agent context, task/scope and unread inbox."
  [st req reply-ch]
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
    nil))

(defn- ^:seon.flow/edge eval-route
  "Resolves the target branch for an `:eval` request and returns the `:eval` out-map."
  [st req reply-ch]
  (let [aid (:agent-id req)
        raw-b (or (:branch req)
                  (when aid
                    (:seon.agent/branch (d/pull @(:conn st) '[:seon.agent/branch] [:seon.agent/id aid])))
                  :branch/repl)
        b (if (= raw-b :main) :branch/repl raw-b)]
    {:eval [(assoc req :branch b :reply-ch reply-ch)]}))

(defn- ^:seon.flow/edge import-file-op
  "Handles an `:import-file` request: imports `path`/`text` into `b` and returns the routing out-map."
  [st req reply-ch]
  (let [{:keys [path branch text]} req
        b (or branch :main)
        repo (:repo-dir st)
        f (when (and (nil? text) repo) (io/file repo path))
        content (or text (when (and f (.isFile f)) (slurp f)))
        exec? (boolean (and f (.canExecute f)))]
    (if (nil? content)
      (do (reply! reply-ch {:ok? false :error (str "File not found: " path)})
          nil)
      (if (= b :main)
        {:tx [{:op :transact :tx-data (code/file-import-tx @(:conn st) path content exec?)
              :reason [:import-file path] :reply-ch reply-ch}]}
        (do
          (store/branch! (:conn st) :main b)
          (let [bconn (store/connect (:cfg st) b)]
            (try
              (let [tx (code/file-import-tx @bconn path content exec?)
                    r (d/transact bconn {:tx-data tx})]
                (reply! reply-ch {:ok? true :branch b :path path :tx (:max-tx (:db-after r))}))
              (finally
                (d/release bconn))))
          (when (:repo-dir st) {:git [{:op :sync-branch :branch b}]}))))))

(defn- ^:seon.flow/edge diff-op
  "Replies to a `:diff` request with the three-way merge plan and exported git diff."
  [st req reply-ch]
  (let [b (:branch req)
        plan (merge/three-way (:conn st) (:cfg st) b)
        exported (when (:repo-dir st)
                   (git/export-branch-ref! (:conn st) (:cfg st) (:repo-dir st) b plan))
        git-diff (when exported
                   (git/git! (:repo-dir st) "diff" "HEAD" (:ref exported)))]
    (reply! reply-ch {:ok? (:ok? plan)
                      :branch b
                      :touched-paths (:touched-paths plan)
                      :entity-diffs (:entity-diffs plan)
                      :conflicts (:conflicts plan)
                      :git-ref (:ref exported)
                      :diff git-diff})
    nil))

(defn- ^:seon.flow/edge start-bench-run-op
  "Starts a live benchmark run and replies with its summary, returning a `::flow/report` out-map."
  [st req reply-ch]
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
                       (try (inst-op {:base-url coord-url} tx-req) (catch Throwable _ignore nil)))))
        ans (start-fn opts publish!)]
    (reply! reply-ch ans)
    {:clojure.core.async.flow/report [(str "[:server] started live benchmark run " (:run-id ans) " (" (:total ans) " tasks)")]}))

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
              :git "Branch git-ref syncs routed to `:git`"
              :vectors "Vector search & status requests routed to `:vectors`"}
       :workload :io
       :doc "External CLI / MCP / HTTP / Take-Over command boundary."
       :ping-map-fn (fn [st] {:requests-served (:served st)})})
  ([{:keys [in-ch conn cfg repo-dir on-hand-over on-switch-over vectors-connected?]}]
   {:in-ch in-ch
    :conn conn
    :cfg cfg
    :repo-dir repo-dir
    :on-hand-over on-hand-over
    :on-switch-over on-switch-over
    :vectors-connected? (not (false? vectors-connected?))
    :served 0
    :clojure.core.async.flow/in-ports {:ext in-ch}})
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
           (do (agent-context-reply! st req reply-ch)
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
           [st' (eval-route st req reply-ch)]

           :test
           [st' {:batch [(assoc req :reply-ch reply-ch)]}]

           (:embed-search :embed-query :vector-status :backfill-now)
           (if (false? (:vectors-connected? st))
             (do (reply! reply-ch (vectors-fallback-reply @(:conn st) op req))
                 [st' nil])
             [st' {:vectors [(assoc req :reply-ch reply-ch)]}])

           (:create-task :spawn-agent :spawn-on-entity :send-message :show-user :merge :transact :toggle-pause-agent
            :register-schema :set-config :record-fault :retract-fault :resolve-fault
            :create-call :await-calls :answer-call :fail-call :cancel-call :settle-calls)
           [st' {:tx [(assoc req :reply-ch reply-ch)]}]

           :import-file
           [st' (import-file-op st req reply-ch)]

           :diff
           (do (diff-op st req reply-ch)
               [st' nil])

           :start-bench-run
           [st' (start-bench-run-op st req reply-ch)]

           (:push :index-forks)
           [st' (handle-fork-op! st req reply-ch)]

           (:render-hiccup-frames :measure-dom)
           (do (handle-browser-op! st req reply-ch)
               [st' nil])

           :hand-over
           (if-let [ho (:on-hand-over st)]
             (do (a/thread (ho reply-ch))
                 [st' {:clojure.core.async.flow/report ["[:server] initiating live `--take-over` hand-over to successor binary..."]}])
             (do (reply! reply-ch {:ok? false :error "Hand-over not configured on this instance"})
                 [st' nil]))

           :switch-over
           (if-let [sw (:on-switch-over st)]
             (do (a/thread
                   (let [ans (try (sw) (catch Throwable ex {:ok? false :error (.getMessage ex)}))]
                     (reply! reply-ch ans)))
                 [st' {:clojure.core.async.flow/report ["[:server] initiating atomic build + --self-test + --take-over switch-over..."]}])
             (do (reply! reply-ch {:ok? false :error "Switch-over not configured on this instance"})
                 [st' nil]))

           (do (reply! reply-ch {:ok? false :error (str "Unknown server op: " op)})
               [st' nil]))
         (catch Throwable ex
           (reply! reply-ch {:ok? false :error (.getMessage ex)})
           [st' {:clojure.core.async.flow/error [{:proc :server :op op :error (.getMessage ex)}]}]))))))