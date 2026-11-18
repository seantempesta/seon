(ns seon.server
  "The `:server` Flow proc (`:io` workload):
   Single external boundary for `bin/seon`, MCP tools, and UI actions.
   Routes validated EDN operations directly to `:transactor`, `:agents`, or `:test` via Flow `:conns`."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.git :as git]
            [seon.merge :as merge]
            [seon.reconciler :as reconciler]
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
        git-ent (d/pull db '[:git/commit :git/branch] [:git/repo :main])]
    {:ok? true
     :main-tx (:max-tx db)
     :commit (str (d/commit-id db))
     :git (:git/commit git-ent)
     :repo-dir repo-dir
     :store-path (get-in cfg [:store :path])
     :forms forms-count
     :cached-green-tests green-tests
     :branches branches
     :tasks tasks
     :agents agents}))

(defn server-proc
  ([] {:params {:in-ch "External request channel `{:req map :reply-ch chan}`"
                :conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :repo-dir "Git repository working tree"}
       :ins {}
       :outs {:tx "Write operations routed to `:transactor`"
              :eval "REPL evaluation requests routed to `:agents`"
              :batch "Test run requests routed to `:test`"}
       :workload :io
       :doc "External CLI / MCP / HTTP command boundary."
       :ping-map-fn (fn [st] {:requests-served (:served st)})})
  ([{:keys [in-ch conn cfg repo-dir]}]
   {:conn conn
    :cfg cfg
    :repo-dir repo-dir
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

           :eval
           [st' {:eval [(assoc req :reply-ch reply-ch)]}]

           :test
           [st' {:batch [(assoc req :reply-ch reply-ch)]}]

           (:create-task :spawn-agent :send-message :merge :transact :toggle-pause-agent)
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
                   [st' nil]))))

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
                               :conflicts (:conflicts plan)
                               :git-ref (:ref exported)
                               :diff git-diff})
             [st' nil])

           (do (reply! reply-ch {:ok? false :error (str "Unknown server op: " op)})
               [st' nil]))
         (catch Throwable ex
           (reply! reply-ch {:ok? false :error (.getMessage ex)})
           [st' {::flow/error [{:proc :server :op op :error (.getMessage ex)}]}]))))))
