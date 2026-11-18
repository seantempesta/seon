(ns seon.main
  "System assembly, `--self-test` cold-boot verifier, and `--take-over` zero-downtime binary swap-over."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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

(defn make-fork-router [fork-parent]
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
  [router {:keys [store-path repo-dir commit-git? port listener model-provider max-os-procs max-test-workers boot-seq on-power-loss on-hand-over]
           :or {port 0 boot-seq 1}}]
  (let [{:keys [cfg conn created?]} (store/open! store-path)
        _ (when repo-dir
            (let [catchup (git/catch-up-tx @conn repo-dir)]
              (when (seq catchup)
                (datahike.api/transact conn {:tx-data catchup})
                (store/sync! cfg))))
        db0 @conn
        auto-git? (if (some? commit-git?)
                    commit-git?
                    (and (some? repo-dir) (boolean (store/config-val db0 :seon.config/auto-sync-git?))))
        os-procs (or max-os-procs (store/config-val db0 :seon.config/max-os-procs) 6)
        test-workers (or max-test-workers (store/config-val db0 :seon.config/max-test-workers) 4)
        model-slots (or (store/config-val db0 :seon.config/max-model-slots) 4)
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
                          :args {:in-ch server-in-ch
                                 :conn conn
                                 :cfg cfg
                                 :repo-dir repo-dir
                                 :on-hand-over on-hand-over}}
             :transactor {:proc (flow/process #'transactor/transactor-proc)
                          :args {:conn conn
                                 :cfg cfg
                                 :repo-dir repo-dir
                                 :commit-git? auto-git?
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
                                 :max-os-procs os-procs}
                          :chan-opts {:run {:buf-or-n 32}
                                      :eval {:buf-or-n 32}
                                      :response {:buf-or-n 32}}}
             :model      {:proc (flow/process #'model/model-proc)
                          :args {:provider model-provider
                                 :ctrl-ch model-ctrl-ch
                                 :max-slots model-slots
                                 :latency-ms 80}
                          :chan-opts {:request {:buf-or-n 16}}}
             :test       {:proc (flow/process #'test-runner/test-proc)
                          :args {:conn conn
                                 :cfg cfg
                                 :fork-out (:fork-out router)
                                 :fork-test-in fork-test-in
                                 :max-workers test-workers}
                          :chan-opts {:db {:buf-or-n 32} :batch {:buf-or-n 32}}}
             :git        {:proc (flow/process #'git-proc/git-proc)
                          :args {:conn conn :cfg cfg :repo-dir repo-dir}
                          :chan-opts {:in {:buf-or-n 32}}}
             :web        {:proc (flow/process #'web/web-proc)
                          :args {:port port
                                 :listener listener
                                 :conn conn
                                 :cfg cfg
                                 :repo-dir repo-dir
                                 :store-path store-path
                                 :fork-parent-pid (get-in router [:fork-parent :pid])
                                 :fork-parent-ceiling (get-in router [:fork-parent :ceiling])
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
             [[:server :git]         [:git :in]]
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
             [[:agents :git]         [:git :in]]
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
  ([sys req] (call! sys req 30000))
  ([sys req timeout-ms]
   (let [reply-ch (a/chan 1)]
     (a/>!! (:server-in-ch sys) {:req req :reply-ch reply-ch})
     (let [[ans _] (a/alts!! [reply-ch (a/timeout timeout-ms)])]
       (or ans {:ok? false :error :timeout :req req})))))

(defn stop!
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

(defn- parse-cli-arg [args flag]
  (some->> args (partition 2 1) (filter #(= flag (first %))) last second))

(defn- request-hand-over! [port]
  (let [resp (model/http-post! (str "http://127.0.0.1:" port "/op")
                               [["Content-Type" "application/edn"]]
                               "{:op :hand-over}")]
    (if (= 200 (:status resp))
      (edn/read-string (:body resp))
      {:ok? false :status (:status resp) :body (:body resp)})))

(defn- rm-rf! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf! c)))
    (.delete f)))

(defn- validate-cli-args! [args]
  (loop [[a & more] args]
    (when a
      (cond
        (= a "--self-test") (recur more)
        (contains? #{"--port" "--store" "--repo" "--take-over"} a)
        (if (seq more)
          (recur (rest more))
          (do (println "Missing value for flag:" a) (flush) (System/exit 2)))
        :else
        (do (println "Unknown CLI argument:" a) (flush) (System/exit 2))))))

(defn -main [& args]
  (validate-cli-args! args)
  (jolt.host/main-carrier-run!
   (fn []
     (let [self-test? (some #(= "--self-test" %) args)
           take-over-port (some-> (parse-cli-arg args "--take-over") parse-long)
           fp (fork/start-fork-parent!)
           router (make-fork-router fp)]
       (if self-test?
         (let [tmp-dir (str "/tmp/seon-selftest-" (System/nanoTime))
               sys (start-flow-instance! router {:store-path tmp-dir :commit-git? false :port 0})]
           (try
             (let [st (call! sys {:op :status})
                   ev (call! sys {:op :eval :branch :branch/smoke :code "(+ 20 22)"})]
               (if (and (:ok? st) (:ok? ev) (= "42" (:value ev)))
                 (do (println "SEON SELF-TEST PASS" (pr-str {:fork-parent-pid (:pid fp) :eval (:value ev)}))
                     (flush)
                     (stop! sys {:close-fork-parent? true})
                     (rm-rf! (io/file tmp-dir))
                     (System/exit 0))
                 (do (println "SEON SELF-TEST FAIL" (pr-str {:status st :eval ev}))
                     (flush)
                     (stop! sys {:close-fork-parent? true})
                     (rm-rf! (io/file tmp-dir))
                     (System/exit 1))))
             (catch Throwable ex
               (println "SEON SELF-TEST ERROR" (.getMessage ex))
               (flush)
               (System/exit 1))))

         (let [repo-dir (or (parse-cli-arg args "--repo") (System/getenv "SEON_REPO") (System/getProperty "user.dir"))
               store-path (or (parse-cli-arg args "--store") (System/getenv "SEON_STORE") (str repo-dir "/runs/store"))
               port (or take-over-port
                        (some-> (parse-cli-arg args "--port") parse-long)
                        (some-> (System/getenv "SEON_PORT") parse-long)
                        9998)
               commit-git-env (System/getenv "SEON_COMMIT_GIT")
               commit-git? (when commit-git-env (= "1" commit-git-env))
               _ (when take-over-port
                   (let [ans (request-hand-over! take-over-port)]
                     (when-not (:released ans)
                       (println "SEON TAKE-OVER REFUSED:" (pr-str ans))
                       (flush)
                       (System/exit 1))
                     (Thread/sleep 50)))
               listener (when (pos? port) (web/start-http-listener! port))
               active-sys (volatile! nil)
               rebooting? (volatile! false)
               exit-ch (a/chan 1)
               hand-over! (fn [reply-ch]
                            (when-let [cur @active-sys]
                              (vreset! active-sys nil)
                              (stop! cur {:close-fork-parent? true})
                              (let [bound-port (:port listener port)]
                                (a/>!! reply-ch {:ok? true :released true :port bound-port})
                                (a/close! reply-ch)
                                (Thread/sleep 20)
                                (when listener ((:stop! listener)))
                                (println (str "✓ [HAND-OVER] Released store & port " bound-port " to successor binary."))
                                (flush)
                                (a/>!! exit-ch :handed-over))))
               reboot! (fn reboot! []
                         (when (and (not @rebooting?) @active-sys)
                           (vreset! rebooting? true)
                           (try
                             (let [old @active-sys
                                   next-seq (inc (:boot-seq old 1))]
                               (println (str "⚡ [POWER LOSS] SIGKILLing children & cold-rebooting Boot #" next-seq " on " store-path "..."))
                               (flush)
                               (stop! old {:close-fork-parent? false})
                               (let [fresh (start-flow-instance! router
                                                                 {:store-path store-path
                                                                  :repo-dir repo-dir
                                                                  :commit-git? commit-git?
                                                                  :listener listener
                                                                  :boot-seq next-seq
                                                                  :on-power-loss reboot!
                                                                  :on-hand-over hand-over!})]
                                 (vreset! active-sys (assoc fresh :boot-seq next-seq))
                                 (println (str "✓ [BOOT #" next-seq "] Cold reboot complete & serving on port " (:port listener)))
                                 (flush)))
                             (finally
                               (vreset! rebooting? false)))))
               sys (start-flow-instance! router
                                         {:store-path store-path
                                          :repo-dir repo-dir
                                          :commit-git? commit-git?
                                          :listener listener
                                          :boot-seq 1
                                          :on-power-loss reboot!
                                          :on-hand-over hand-over!})]
           (vreset! active-sys (assoc sys :boot-seq 1))
           (.addShutdownHook (Runtime/getRuntime)
                             (Thread. ^Runnable
                                      (fn []
                                        (when-let [cur @active-sys]
                                          (stop! cur {:close-fork-parent? true}))
                                        (when listener
                                          ((:stop! listener))))))
           (println (str "SEON LISTENING http://127.0.0.1:" (:port listener port)
                         " | ForkParent PID " (:pid fp)
                         " (GC ceiling " (:ceiling fp) ")"
                         " | Store " store-path))
           (flush)
           (a/<!! exit-ch)
           (System/exit 0)))))))
