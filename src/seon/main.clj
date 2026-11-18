(ns seon.main
  "System assembly & entry point for Seon:
   1. Spawns the single-threaded `seon.fork` parent first (while this process is 1 OS thread).
   2. Opens the durable LMDB Datahike store (`seon.store/open!`).
   3. Starts the static 8-proc `clojure.core.async.flow` graph:
      `:server`, `:transactor`, `:reconciler`, `:agents`, `:model`, `:test`, `:git`, `:web`."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [seon.agents :as agents]
            [seon.fork :as fork]
            [seon.git :as git]
            [seon.git-proc :as git-proc]
            [seon.model :as model]
            [seon.reconciler :as reconciler]
            [seon.server :as server]
            [seon.store :as store]
            [seon.test-runner :as test-runner]
            [seon.transactor :as transactor]
            [seon.web :as web]))

(defn make-fork-router
  "Demultiplexes events from the 1-thread System Fork Parent to the active Flow's `:agents` and `:test` channels."
  [fork-parent]
  (let [targets (volatile! {:agent-ch nil :test-ch nil :kill-ack nil})]
    (a/go-loop []
      (when-let [ev (a/<! (:in (:end fork-parent)))]
        (let [{:keys [agent-ch test-ch kill-ack]} @targets]
          (cond
            (= (:ev ev) :killed-all-9) (when kill-ack (deliver kill-ack (:pids ev)))
            (= (:type ev) :agent)      (when agent-ch (a/>! agent-ch ev))
            (= (:type ev) :test)       (when test-ch (a/>! test-ch ev))))
        (recur)))
    {:fork-parent fork-parent
     :fork-out (:out (:end fork-parent))
     :set-targets! (fn [m] (vreset! targets m))}))

(defn start-flow-instance!
  "Starts one static 8-proc Seon Flow instance against `store-path` using `router`."
  [router {:keys [store-path repo-dir commit-git? port model-provider max-os-procs max-test-workers boot-seq on-power-loss]
           :or {port 0 commit-git? false boot-seq 1}}]
  (let [{:keys [cfg conn created?]} (store/open! store-path)
        ;; Catch up or bootstrap git HEAD synchronously BEFORE starting Flow procs so `:test`, `:reconciler`,
        ;; and `:server` see `:main` whole from millisecond 0 (zero boot race!).
        _ (when repo-dir
            (let [catchup (git/catch-up-tx @conn repo-dir)]
              (when (seq catchup)
                (datahike.api/transact conn {:tx-data catchup})
                (store/sync! cfg))))
        server-in-ch (a/chan 64)
        model-ctrl-ch (a/chan 8)
        fork-agent-in (a/chan 128)
        fork-test-in (a/chan 128)
        report-chan (a/chan (a/sliding-buffer 128))
        error-chan (a/chan (a/sliding-buffer 64))
        flow-holder (volatile! nil)
        _ ((:set-targets! router) {:agent-ch fork-agent-in :test-ch fork-test-in :kill-ack nil})
        g (flow/create-flow
           {:procs
            {:server     {:proc (flow/process #'server/server-proc)
                          :args {:in-ch server-in-ch :conn conn :cfg cfg :repo-dir repo-dir}}
             :transactor {:proc (flow/process #'transactor/transactor-proc)
                          :args {:conn conn
                                 :cfg cfg
                                 :repo-dir repo-dir
                                 :commit-git? commit-git?
                                 :fork-out (:fork-out router)}
                          :chan-opts {:tx {:buf-or-n 64}}}
             :reconciler {:proc (flow/process #'reconciler/reconciler-proc)
                          :args {:conn conn}
                          :chan-opts {:db {:buf-or-n 32} :exits {:buf-or-n 64}}}
             :agents     {:proc (flow/process #'agents/agents-proc)
                          :args {:conn conn
                                 :cfg cfg
                                 :fork-out (:fork-out router)
                                 :fork-agent-in fork-agent-in
                                 :max-os-procs (or max-os-procs 6)}
                          :chan-opts {:run {:buf-or-n 32}
                                      :eval {:buf-or-n 32}
                                      :response {:buf-or-n 32}}}
             :model      {:proc (flow/process #'model/model-proc)
                          :args {:provider model-provider
                                 :ctrl-ch model-ctrl-ch
                                 :max-slots 4
                                 :latency-ms 80}
                          :chan-opts {:request {:buf-or-n 16}}}
             :test       {:proc (flow/process #'test-runner/test-proc)
                          :args {:conn conn
                                 :cfg cfg
                                 :fork-out (:fork-out router)
                                 :fork-test-in fork-test-in
                                 :max-workers (or max-test-workers 4)}
                          :chan-opts {:db {:buf-or-n 32} :batch {:buf-or-n 32}}}
             :git        {:proc (flow/process #'git-proc/git-proc)
                          :args {:conn conn :repo-dir repo-dir}
                          :chan-opts {:in {:buf-or-n 16}}}
             :web        {:proc (flow/process #'web/web-proc)
                          :args {:port port
                                 :conn conn
                                 :store-path store-path
                                 :fork-parent-pid (get-in router [:fork-parent :pid])
                                 :server-in-ch server-in-ch
                                 :model-ctrl-ch model-ctrl-ch
                                 :report-chan report-chan
                                 :error-chan error-chan
                                 :flow-fn #(deref flow-holder)
                                 :on-power-loss on-power-loss
                                 :boot-seq boot-seq}
                          :chan-opts {:db {:buf-or-n (a/sliding-buffer 8)}
                                      :stream {:buf-or-n (a/sliding-buffer 16)}}}}

            :conns
            [[[:server :tx]          [:transactor :tx]]
             [[:server :eval]        [:agents :eval]]
             [[:server :batch]       [:test :batch]]
             [[:transactor :db]      [:reconciler :db]]
             [[:transactor :db]      [:test :db]]
             [[:transactor :db]      [:web :db]]
             [[:transactor :git]     [:git :in]]
             [[:git :tx]             [:transactor :tx]]
             [[:reconciler :run]     [:agents :run]]
             [[:reconciler :batch]   [:test :batch]]
             [[:reconciler :tx]      [:transactor :tx]]
             [[:agents :request]     [:model :request]]
             [[:model :response]     [:agents :response]]
             [[:model :stream]       [:web :stream]]
             [[:agents :tx]          [:transactor :tx]]
             [[:agents :exits]       [:reconciler :exits]]
             [[:test :tx]            [:transactor :tx]]]

            :report-chan report-chan
            :error-chan error-chan})]
    (vreset! flow-holder g)
    (flow/start g)
    (flow/resume g)
    {:flow g
     :conn conn
     :cfg cfg
     :created? created?
     :server-in-ch server-in-ch
     :report-chan report-chan
     :error-chan error-chan
     :router router}))

(defn call!
  "Sends an EDN request map `req` into `:server` and blocks (up to `timeout-ms`) for the reply."
  ([sys req] (call! sys req 30000))
  ([sys req timeout-ms]
   (let [reply-ch (a/chan 1)]
     (a/>!! (:server-in-ch sys) {:req req :reply-ch reply-ch})
     (let [[ans _] (a/alts!! [reply-ch (a/timeout timeout-ms)])]
       (or ans {:ok? false :error :timeout :req req})))))

(defn stop!
  "Cleanly stops a Flow instance, reaps forked children, and syncs/closes its LMDB connection."
  ([sys] (stop! sys {:close-fork-parent? false}))
  ([{:keys [flow conn cfg router]} {:keys [close-fork-parent?]}]
   (let [ack (promise)]
     ((:set-targets! router) {:agent-ch nil :test-ch nil :kill-ack ack})
     (a/>!! (:fork-out router) {:op :kill-all-9!})
     (deref ack 2000 [])
     (when flow
       (try (flow/stop flow) (catch Throwable _)))
     (store/close! {:conn conn :cfg cfg})
     (when close-fork-parent?
       (try (a/close! (:fork-out router)) (catch Throwable _))))))

(defn -main [& _args]
  (jolt.host/main-carrier-run!
   (fn []
     ;; STEP 1: Fork 1-thread System Fork Parent FIRST before opening LMDB or starting Flow threads!
     (let [fp (fork/start-fork-parent!)
           router (make-fork-router fp)
           repo-dir (or (System/getenv "SEON_REPO") (System/getProperty "user.dir"))
           store-path (or (System/getenv "SEON_STORE") (str repo-dir "/runs/store"))
           port (or (some-> (System/getenv "SEON_PORT") parse-long) 9998)
           commit-git? (= "1" (System/getenv "SEON_COMMIT_GIT"))
           active-sys (volatile! nil)
           reboot! (fn reboot! []
                     (when-let [old @active-sys]
                       (let [next-seq (inc (:boot-seq old 1))]
                         (println (str "⚡ [POWER LOSS] SIGKILLing children & cold-rebooting Boot #" next-seq " on " store-path "..."))
                         (flush)
                         (stop! old {:close-fork-parent? false})
                         (let [fresh (start-flow-instance! router
                                                           {:store-path store-path
                                                            :repo-dir repo-dir
                                                            :commit-git? commit-git?
                                                            :port port
                                                            :boot-seq next-seq
                                                            :on-power-loss reboot!})]
                           (vreset! active-sys (assoc fresh :boot-seq next-seq))))))
           sys (start-flow-instance! router
                                     {:store-path store-path
                                      :repo-dir repo-dir
                                      :commit-git? commit-git?
                                      :port port
                                      :boot-seq 1
                                      :on-power-loss reboot!})]
       (vreset! active-sys (assoc sys :boot-seq 1))
       (.addShutdownHook (Runtime/getRuntime)
                         (Thread. ^Runnable
                                  (fn []
                                    (when-let [cur @active-sys]
                                      (stop! cur {:close-fork-parent? true})))))
       (println (str "SEON LISTENING http://127.0.0.1:" port
                     " | ForkParent PID " (:pid fp)
                     " | Store " store-path))
       (flush)
       (a/<!! (a/chan))))))
