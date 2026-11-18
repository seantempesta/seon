(ns seon.server
  "The `:server` Flow proc (`:io` workload):
   Single external & internal command boundary for `bin/seon`, MCP sessions, live `--take-over`
   binary swap-overs, and Datastar UI actions.
   Routes validated EDN operations directly to `:transactor`, `:agents`, or `:test` via Flow `:conns`."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.git :as git]
            [seon.merge :as merge]
            [seon.reconciler :as reconciler]
            [seon.schema :as schema]
            [seon.store :as store]))

(defn- reply! [ch val]
  (when ch
    (a/put! ch val)
    (a/close! ch)))

(defn system-status-snapshot [conn cfg repo-dir]
  (let [db @conn
        store (:store db)
        branches (vec (sort (d/branches conn)))
        tasks (->> (d/datoms db :aevt :task/id)
                   (map #(d/pull db '[:task/id :task/title :task/done? :task/tests] (:e %)))
                   (sort-by #(str (:task/id %)))
                   vec)
        agents (->> (d/datoms db :aevt :agent/id)
                    (keep #(reconciler/derive-agent-status db store {} #{} #{} (:v %)))
                    (sort-by #(str (:id %)))
                    vec)
        green-tests (count (filter #(true? (:v %)) (d/datoms db :aevt :test/pass?)))
        forms-count (count (d/datoms db :aevt :form/key))
        vars-count (count (d/datoms db :aevt :var/name))
        schemas-count (count (d/datoms db :aevt :schema/key))
        docs-count (count (d/datoms db :aevt :doc/id))
        git-ent (d/pull db '[:git/commit :git/branch] [:git/repo :main])]
    {:ok? true
     :main-tx (:max-tx db)
     :commit (str (d/commit-id db))
     :git (:git/commit git-ent)
     :repo-dir repo-dir
     :store-path (get-in cfg [:store :path])
     :forms forms-count
     :vars vars-count
     :schemas schemas-count
     :docs docs-count
     :cached-green-tests green-tests
     :config (:effective (store/config-map db))
     :branches branches
     :tasks tasks
     :agents agents}))

(defn explore-var
  "100% indexed Datahike lookup (0 live parsing!) of a `:var/name`: its source, schema, callers,
   callees, cost budget, and reaching `deftest`s."
  [db var-sym]
  (when-let [dt (first (d/datoms db :avet :var/name var-sym))]
    (let [v (dissoc (d/pull db '[*] (:e dt)) :db/id)
          f (when-let [fk (:var/form v)]
              (d/pull db '[:form/src :form/hash] [:form/key fk]))
          ;; 1 indexed AVET seek for all direct callers!
          callers (->> (d/datoms db :avet :var/refs var-sym)
                       (keep #(:var/name (d/pull db '[:var/name] (:e %))))
                       sort
                       vec)]
      (assoc v
             :src (:form/src f)
             :hash (:form/hash f)
             :callers callers))))

(defn explore-schema
  "100% indexed Datahike lookup of `:schema/key`: its form, `seed = 42` samples, and all functions
   referencing it via `:var/schema-refs` (1 AVET seek!)."
  [db schema-key]
  (when-let [dt (first (d/datoms db :avet :schema/key schema-key))]
    (let [s (dissoc (d/pull db '[*] (:e dt)) :db/id)
          users (->> (d/datoms db :avet :var/schema-refs schema-key)
                     (keep #(:var/name (d/pull db '[:var/name] (:e %))))
                     sort
                     vec)
          samples (schema/sample-schema db schema-key 5)]
      (assoc s :used-by users :samples (mapv pr-str samples)))))

(defn server-proc
  ([] {:params {:in-ch "External request channel `{:req map :reply-ch chan}`"
                :conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :repo-dir "Git repository working tree"
                :on-hand-over "Callback `(fn [] {:ok? true :released true :port ...})` for `--take-over`"}
       :ins {}
       :outs {:tx "Write operations routed to `:transactor`"
              :eval "REPL evaluation requests routed to `:agents`"
              :batch "Test run requests routed to `:test`"
              :git "Branch git-ref syncs routed to `:git`"}
       :workload :io
       :doc "External CLI / MCP / HTTP / Take-Over command boundary."
       :ping-map-fn (fn [st] {:requests-served (:served st)})})
  ([{:keys [in-ch conn cfg repo-dir on-hand-over]}]
   {:conn conn
    :cfg cfg
    :repo-dir repo-dir
    :on-hand-over on-hand-over
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

           :explore-var
           (let [v (explore-var @(:conn st) (:var req))]
             (reply! reply-ch (if v {:ok? true :var v} {:ok? false :error (str "Var not found: " (:var req))}))
             [st' nil])

           :explore-schema
           (let [s (explore-schema @(:conn st) (:key req))]
             (reply! reply-ch (if s {:ok? true :schema s} {:ok? false :error (str "Schema not found: " (:key req))}))
             [st' nil])

           :eval
           (let [aid (:agent-id req)
                 b (or (:branch req)
                       (when aid
                         (:agent/branch (d/pull @(:conn st) '[:agent/branch] [:agent/id aid])))
                       :main)]
             [st' {:eval [(assoc req :branch b :reply-ch reply-ch)]}])

           :test
           [st' {:batch [(assoc req :reply-ch reply-ch)]}]

           (:create-task :spawn-agent :send-message :merge :transact :toggle-pause-agent :register-schema :set-config)
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

           :hand-over
           (if-let [ho (:on-hand-over st)]
             (do (a/thread
                   ;; Run hand-over outside `:server` step so `flow/stop` can cleanly stop `:server` itself!
                   (ho reply-ch))
                 [st' {::flow/report ["[:server] initiating live `--take-over` hand-over to successor binary..."]}])
             (do (reply! reply-ch {:ok? false :error "Hand-over not configured on this instance"})
                 [st' nil]))

           (do (reply! reply-ch {:ok? false :error (str "Unknown server op: " op)})
               [st' nil]))
         (catch Throwable ex
           (reply! reply-ch {:ok? false :error (.getMessage ex)})
           [st' {::flow/error [{:proc :server :op op :error (.getMessage ex)}]}]))))))
