(ns seon.bench.cluster
  "Parallel isolated `target/seon` multi-instance orchestrator for benchmarks and A/B trials."
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.bench.proxy :as proxy]
            [seon.model :as model]
            [seon.store :refer [rm-rf!]]
            [seon.bench.helpers :as bh]
            [jolt.fs :as fs]
            [jolt.process :as proc]
            [clojure.core.async.flow :as flow]
            [seon.bench.trial :as trial]))

(defn parse-listening-port [& args] (apply bh/parse-listening-port args))

(defn build-instance-cmd [& args] (apply bh/build-instance-cmd args))

(defn agent-settled? [& args] (apply bh/agent-settled? args))

(defn ^:seon.flow/edge instance-op!
  "Sends an EDN op map to a running isolated Seon instance via `POST /op` and returns its EDN response."
  {:malli/schema [:=> [:cat [:map [:base-url :string]] :map] :map]}
  [inst op-map]
  (let [url (str (:base-url inst))]
    (if-not (or (str/starts-with? url "http://") (str/starts-with? url "https://"))
      {:ok? false :error :invalid-url :url url}
      (try
        (let [resp (model/http-post! (str url "/op") [["Content-Type" "application/edn"]] (pr-str op-map))]
          (if (= 200 (:status resp))
            (try (edn/read-string (:body resp))
              (catch Throwable ex {:ok? false :error :bad-edn :message (or (.getMessage ex) (str ex)) :body (:body resp)}))
            {:ok? false :error :http-error :status (:status resp) :body (:body resp)}))
        (catch Throwable ex {:ok? false :error :transport :message (or (.getMessage ex) (str ex))})))))

(defn ^:seon.flow/edge stop-instance!
  "Gracefully shuts down an isolated Seon instance and removes its temp dir."
  {:malli/schema [:=> [:cat [:map [:stop! :seon.host/fn]] [:? :map]] :map]}
  ([inst] (stop-instance! inst {}))
  ([inst {:keys [keep-dir?]}]
   (let [handover (try (instance-op! inst {:op :hand-over}) (catch Throwable _ nil))]
     (try ((:stop! inst)) (catch Throwable _))
     (when (and (not keep-dir?) (:work-dir inst))
       (try (rm-rf! (io/file (:work-dir inst))) (catch Throwable _)))
     {:ok? true :id (:id inst) :handover-released? (boolean (:released handover))})))

(defn ^:seon.flow/edge start-instance!
  "Spawns an isolated headless `target/seon` instance on an ephemeral port with its own temp LMDB store."
  {:malli/schema [:=> [:cat [:? :map]] :map]}
  ([] (start-instance! {}))
  ([{:keys [id repo-dir bin-path work-dir store-path headless? commit-git? auto-switch-over? extra-env timeout-ms]
     :or {headless? true commit-git? false auto-switch-over? false timeout-ms 20000} :as opts}]
   (let [repo (str (or repo-dir (System/getProperty "user.dir")))
         bin (str (or bin-path (str repo "/target/seon")))
         inst-id (or id (keyword "bench" (str "inst-" (System/nanoTime))))
         wdir (str (or work-dir (str "/tmp/seon-bench-" (str/replace (name inst-id) #"[^A-Za-z0-9._-]" "-") "-" (System/nanoTime))))
         spath (str (or store-path (str wdir "/store")))]
     (when-not (fs/executable? bin) (throw (ex-info (str "Seon binary missing or not executable: " bin) {:bin-path bin})))
     (fs/create-dirs wdir)
     (let [seed-s (when-let [ss (:seed-store opts)] (let [s (str ss)] (if (clojure.string/starts-with? s "/") s (str repo "/" s))))
           cmd (build-instance-cmd (assoc opts :bin-path bin :port 0 :store-path spath :repo-dir repo :seed-store seed-s
                                          :headless? headless? :commit-git? commit-git? :auto-switch-over? auto-switch-over?))
           started-ns (System/nanoTime)
           jproc (proc/process cmd {:dir wdir :err :out :extra-env (bh/jolt-env-map repo extra-env) :shutdown proc/destroy-tree})
           ^Process proc (:proc jproc)
           reader (java.io.BufferedReader. (java.io.InputStreamReader. (:out jproc) "UTF-8"))
           log-atom (atom []) ready-ch (a/chan 1) delivered? (volatile! false)
           stop-fn (fn []
                     (when (.isAlive proc)
                       (try (proc/destroy-tree jproc) (catch Throwable _))
                       (loop [step 0] (when (and (.isAlive proc) (< step 30)) (a/<!! (a/timeout 50)) (recur (inc step))))
                       (when (.isAlive proc) (try (.destroyForcibly proc) (catch Throwable _))))
                     (try (.close reader) (catch Throwable _)))]
       (a/thread
         (try
           (loop []
             (when-let [line (.readLine reader)]
               (swap! log-atom #(let [xs (conj % line)] (if (> (count xs) 200) (subvec xs 1) xs)))
               (when-not @delivered?
                 (when-let [port (parse-listening-port line)] (vreset! delivered? true) (a/put! ready-ch {:port port})))
               (recur)))
           (catch Throwable _ nil)
           (finally (when-not @delivered? (vreset! delivered? true) (a/put! ready-ch {:exited true})))))
       (let [[msg ch] (a/alts!! [ready-ch (a/timeout (long timeout-ms))])]
         (if-let [port (:port msg)]
           {:id inst-id :port port :base-url (str "http://127.0.0.1:" port) :store-path spath :work-dir wdir
            :boot-ms (/ (double (- (System/nanoTime) started-ns)) 1e6) :log-atom log-atom :alive? #(.isAlive proc) :stop! stop-fn}
           (let [logs @log-atom]
             (stop-fn) (fs/delete-tree wdir)
             (throw (ex-info (str "Isolated Seon instance " inst-id " failed to listen within " timeout-ms " ms")
                             {:id inst-id :reason (if (= ch ready-ch) :exited-early :timeout) :logs (take-last 30 logs)})))))))))

(defn ^:seon.flow/edge wait-for-agent!
  "Polls `inst` until `agent-id` settles past optional `:baseline-evals` or times out."
  {:malli/schema [:=> [:cat [:map [:base-url :string] [:stop! :seon.host/fn]] :keyword :int [:? [:maybe :seon.host/fn]] [:? [:maybe :map]]] :map]}
  ([inst agent-id timeout-ms] (wait-for-agent! inst agent-id timeout-ms nil nil))
  ([inst agent-id timeout-ms on-poll] (wait-for-agent! inst agent-id timeout-ms on-poll nil))
  ([inst agent-id timeout-ms on-poll wait-opts]
   (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))
         baseline-evals (long (or (:baseline-evals wait-opts) 0))]
     (loop [polls 0]
       (let [status-snap (instance-op! inst {:op :status})
             agent-row (when (:ok? status-snap) (some #(when (= agent-id (:id %)) %) (:agents status-snap)))
             advanced? (or (zero? baseline-evals) (> (long (or (:evals agent-row) 0)) baseline-evals))]
         (when (and on-poll (:ok? status-snap)) (try (on-poll status-snap polls) (catch Throwable _ nil)))
         (cond
           (and (:ok? status-snap) (agent-settled? status-snap agent-id) advanced?)
           {:ok? true :settled? true :polls polls :status status-snap}
           (>= (System/currentTimeMillis) deadline)
           {:ok? false :settled? false :polls polls :status status-snap :error :timeout}
           :else (do (a/<!! (a/timeout 500)) (recur (inc polls)))))))))

(defn ^{:seon.code/reexport 'seon.bench.trial/trial-ids} trial-ids [& args] (apply trial/trial-ids args))

(defn ^{:seon.code/reexport 'seon.bench.trial/trial-instance-opts} trial-instance-opts [& args] (apply trial/trial-instance-opts args))

(defn ^{:seon.code/reexport 'seon.bench.trial/trial-task-plan} trial-task-plan [& args] (apply trial/trial-task-plan args))

(defn ^{:seon.code/reexport 'seon.bench.trial/trial-spawn-op} trial-spawn-op [& args] (apply trial/trial-spawn-op args))

(defn ^{:seon.code/reexport 'seon.bench.trial/trial-message-op} trial-message-op [& args] (apply trial/trial-message-op args))

(defn ^{:seon.code/reexport 'seon.bench.trial/trial-progress} trial-progress [& args] (apply trial/trial-progress args))

(defn ^{:seon.code/reexport 'seon.bench.trial/callable-specialist-id?} callable-specialist-id? [& args] (apply trial/callable-specialist-id? args))

(defn- ^:seon.flow/edge pause-preexisting-agents!
  ([inst] (pause-preexisting-agents! inst nil))
  ([inst keep-aid]
   (let [agents (:agents (instance-op! inst {:op :status}))
         tx-data (reduce
                   (fn [acc {:keys [id status]}]
                     (cond
                       (or (nil? id) (= keep-aid id)) acc
                       (and keep-aid (callable-specialist-id? id))
                       (if (= :paused status) (conj acc {:seon.agent/id id :seon.agent/paused? false}) acc)
                       (not= :paused status) (conj acc {:seon.agent/id id :seon.agent/paused? true})
                       :else acc))
                   (if keep-aid [{:seon.agent/id keep-aid :seon.agent/paused? false}] [])
                   agents)]
     (when (seq tx-data)
       (instance-op! inst {:op :transact :tx-data tx-data :reason :bench-isolate-preexisting-agents})))))

(defn ^{:seon.code/reexport 'seon.bench.trial/trial-outcome} trial-outcome [& args] (apply trial/trial-outcome args))

(defn ^{:seon.code/reexport 'seon.bench.trial/trial-failure} trial-failure [& args] (apply trial/trial-failure args))

(defn- ^:seon.flow/edge eval-probe-edn [inst code]
  (try
    (let [res (instance-op! inst {:op :eval :agent :repl/bench-probe :branch :branch/bench-probe :code code})
          parsed (when (and (:ok? res) (string? (:value res)))
                   (try (edn/read-string (:value res)) (catch Throwable _ nil)))]
      (if (map? parsed) parsed {}))
    (catch Throwable _ {})))

(defn ^:seon.flow/edge probe-trial-trace
  "Queries the isolated trial instance via zero-fork `/op :agent-trace` (with eval fallback for older binaries)."
  {:malli/schema [:=> [:cat :map :keyword :keyword [:? [:maybe :map]]] :map]}
  ([inst agent-id branch] (probe-trial-trace inst agent-id branch nil))
  ([inst agent-id branch probe-opts]
   (let [after-tx (long (or (:after-tx probe-opts) 0))
         after-main-tx (long (or (:after-main-tx probe-opts) 0))
         opts-map (cond-> {:branch branch}
                    (pos? after-tx) (assoc :after-tx after-tx)
                    (pos? after-main-tx) (assoc :after-main-tx after-main-tx)
                    (:scope probe-opts) (assoc :scope (:scope probe-opts))
                    (:include-trace? probe-opts) (assoc :include-trace? true))
         fast (try (instance-op! inst {:op :agent-trace :agent-id agent-id :branch branch :audit? true :opts opts-map})
                   (catch Throwable _ nil))]
     (if (and (map? fast) (not (and (false? (:ok? fast)) (str/includes? (str (:error fast)) "Unknown server op"))))
       fast
       (eval-probe-edn inst
         (str "(if-let [f (or (requiring-resolve (quote seon.bench.helpers/trial-audit-summary)) "
              "(requiring-resolve (quote seon.health.metrics/agent-friction-summary)))] "
              "(f (user/conn) (user/main-db) " (pr-str agent-id) " (quote " (pr-str opts-map) ")) {})"))))))

(defn- ^:seon.flow/edge probe-agent-baseline [inst agent-id branch]
  (let [status-snap (instance-op! inst {:op :status})
        agent-row (some #(when (= agent-id (:id %)) %) (:agents status-snap))
        tx-map (eval-probe-edn
                 inst
                 (str "(let [conn (user/conn) main-db (user/main-db) "
                      "branch-db (or (when ((set (datahike.api/branches conn)) " (pr-str branch) ") "
                      "(seon.store/head conn " (pr-str branch) ")) main-db)] "
                      "{:after-tx (long (or (:max-tx branch-db) 0)) :after-main-tx (long (or (:max-tx main-db) 0))})"))]
    (merge {:baseline-evals (long (or (:evals agent-row) 0)) :after-tx 0 :after-main-tx 0} tx-map)))

(defn ^{:seon.code/reexport 'seon.bench.trial/trial-telemetry} trial-telemetry [& args] (apply trial/trial-telemetry args))

(defn ^{:seon.code/reexport 'seon.bench.trial/trial-result} trial-result [& args] (apply trial/trial-result args))

(defn run-one-trial!
  "Boots one isolated instance, drives a full trial against it, tears it down, returns its result map."
  {:malli/schema [:=> [:cat :int :map :map] :map]
   :seon.eval/budget-override {:ticks 2000000000 :bytes 1073741824}
   :seon.flow/edge true}
  [idx task opts]
  (let [{:keys [on-trial-update keep-dirs? task-timeout-ms]} opts
        proxy-inst (:proxy-instance opts)
        {:keys [id branch agent-id]} (trial-ids idx task)
        chat-aid (:chat-agent task)
        started-ns (System/nanoTime)
        inst-ref (atom nil)
        elapsed-ms! #(/ (double (- (System/nanoTime) started-ns)) 1e6)
        emit! (fn [payload]
                (when on-trial-update
                  (try
                    (on-trial-update (assoc payload :id id :port (:port @inst-ref) :elapsed-ms (long (elapsed-ms!)))
                                     (when proxy-inst (proxy/stats proxy-inst)))
                    (catch Throwable _ nil))))
        timeout-ms (long (or (:timeout-ms task) task-timeout-ms 60000))]
    (try
      (let [inst-handle (start-instance! (trial-instance-opts id task opts))
            _ (reset! inst-ref inst-handle)
            _ (pause-preexisting-agents! inst-handle chat-aid)
            _ (emit! {:status :booted :evals 0 :alive? true :boot-ms (long (:boot-ms inst-handle))})
            plan (trial-task-plan task)
            baseline (if chat-aid (probe-agent-baseline inst-handle agent-id branch) {:after-main-tx (long (or (:main-tx (instance-op! inst-handle {:op :status})) 0))})
            baseline-evals (long (or (:baseline-evals baseline) 0))
            eval-res (when-let [code (:eval-code task)]
                       (instance-op! inst-handle {:op :eval :agent agent-id :branch branch :code code}))
            on-poll (when on-trial-update
                      (fn [status-snap polls]
                        (when (zero? (mod polls 4))
                          (let [row (some #(when (= agent-id (:id %)) %) (:agents status-snap))
                                trace (when (> (long (or (:evals row) 0)) baseline-evals)
                                        (probe-trial-trace inst-handle agent-id branch baseline))]
                            (emit! (trial-progress agent-id status-snap trace))))))
            agent-res (when (or chat-aid (:task task) (:goal task) (:body task))
                        (let [spawn (when-not chat-aid
                                      (when-let [task-map (:task-map plan)]
                                        (instance-op! inst-handle (assoc task-map :op :create-task)))
                                      (instance-op! inst-handle (trial-spawn-op agent-id branch plan)))
                              msg (when-let [op (trial-message-op agent-id (:body plan))]
                                    (instance-op! inst-handle op))
                              wait (wait-for-agent! inst-handle agent-id timeout-ms on-poll baseline)]
                          {:spawn spawn :message msg :wait wait}))
            ag-snap (some #(when (= agent-id (:id %)) %) (get-in agent-res [:wait :status :agents]))
            final-trace (when (or agent-res eval-res) (probe-trial-trace inst-handle agent-id branch (assoc baseline :scope (:scope plan) :include-trace? (:include-trace? task))))
            timed-out? (= :timeout (get-in agent-res [:wait :error]))
            already-merged? (= :merged (:status ag-snap))
            test-res (when (and (not timed-out?) (:run-tests? task) (or (not already-merged?) (:retest-main? task)))
                       (instance-op! inst-handle {:op :test :branch (if already-merged? :main branch)}))
            merge-res (when (and (not timed-out?) (:merge? task))
                      (cond already-merged? {:ok? true :already-merged? true :branch branch}
                      (:auto-merge? task) {:ok? false :error "worker-did-not-auto-merge" :touched-paths #{}}
                      :else (instance-op! inst-handle {:op :merge :branch branch :message (str "Bench trial " (name id))})))
            health-res (instance-op! inst-handle {:op :health})
            verify-res (cond
                       (and timed-out? (:verify-spec task))
                       {:ok? false :reason "skipped-due-to-timeout"}
                       (:verify-spec task)
                       (let [sp (assoc (:verify-spec task) :after-main-tx (long (or (:after-main-tx baseline) 0)))
                             vr (instance-op! inst-handle {:op :verify-task :spec sp})]
                         (if (and (false? (:ok? vr)) (str/includes? (str (:error vr)) "Unknown server op"))
                           (eval-probe-edn inst-handle (str "(seon.bench.helpers/verify-task-spec (user/main-db) '" (pr-str sp) ")"))
                           vr))
                       :else nil)
            outcome (trial-outcome {:task task :ag-snap ag-snap :eval-res eval-res :agent-res agent-res
                                    :test-res test-res :merge-res merge-res :final-trace final-trace
                                    :health-res health-res :verify-res verify-res})
            failure (when-not (:ok? outcome)
                    (trial-failure {:timeout-ms timeout-ms :agent-res agent-res :test-res test-res :merge-res merge-res
                                    :ag-snap ag-snap :task task :final-trace final-trace
                                    :health-res health-res :verify-res verify-res :halted-not-merged? (:halted-not-merged? outcome)}))]
        (trial-result {:id id :category (:category task) :arm (:arm task) :pair-id (:pair-id task) :port (:port inst-handle) :boot-ms (:boot-ms inst-handle) :elapsed-ms (elapsed-ms!) :outcome outcome :ag-snap ag-snap :eval-res eval-res :agent-res agent-res :test-res test-res :merge-res merge-res :health-res health-res :final-trace final-trace :verify-res verify-res :failure failure}))
      (catch Throwable ex
        {:id id :status :error :timed-out? false :ok? false :elapsed-ms (elapsed-ms!)
         :error (or (.getMessage ex) (str ex)) :data (ex-data ex)})
      (finally
        (when-let [inst-handle @inst-ref] (stop-instance! inst-handle {:keep-dir? keep-dirs?}))))))

(defn ^:seon.flow/edge run-parallel-trial!
  "Runs `tasks` through a static `clojure.core.async.flow` bounded worker pool (`:max-parallel-instances`, default 3) with pure channel backpressure and clean `flow/stop` teardown."
  {:malli/schema [:=> [:cat [:? :map]] :map]}
  ([] (run-parallel-trial! {:tasks []}))
  ([{:keys [tasks proxy? proxy-opts model-base-url boot-timeout-ms task-timeout-ms keep-dirs? max-parallel-instances]
     :as opts :or {proxy? false boot-timeout-ms 60000 task-timeout-ms 60000 keep-dirs? false max-parallel-instances 3}}]
   (let [started-ns (System/nanoTime)
         tvec (vec tasks)
         total (count tvec)]
     (if (zero? total)
       {:ok? true :count 0 :elapsed-ms 0.0 :results [] :proxy-stats nil}
       (let [n-par (int (min total (max 1 (long max-parallel-instances))))
             proxy-inst (when proxy? (proxy/start! (or proxy-opts {})))
             run-opts (assoc opts :eff-base-url (or (when proxy-inst (:base-url proxy-inst)) model-base-url)
                             :proxy-instance proxy-inst :boot-timeout-ms boot-timeout-ms
                             :task-timeout-ms task-timeout-ms :keep-dirs? keep-dirs?)
             wkeys (mapv #(keyword (str "w" %)) (range n-par))
             kick-ch (a/chan 1)
             done-ch (a/chan 1)
             disp-fn (fn
                       ([] {:params {:kick-ch "Startup trigger" :done-ch "Result delivery"}
                            :ins {:done "Completed `[wkey idx res]`"}
                            :outs (zipmap wkeys (repeat "Task `[idx task]`"))
                            :workload :mixed})
                       ([{:keys [kick-ch done-ch]}]
                        {:pending (vec (map-indexed vector tvec)) :free (vec wkeys) :results []
                         :done-ch done-ch ::flow/in-ports {:kick kick-ch}})
                       ([st _] st)
                       ([st in-id msg]
                        (let [st1 (if (= in-id :done)
                                    (let [[wk idx res] msg]
                                      (-> st (update :free conj wk) (update :results conj [idx res])))
                                    st)
                              disp (loop [s st1 outs {}]
                                     (if (and (seq (:free s)) (seq (:pending s)))
                                       (let [wk (first (:free s)) item (first (:pending s))]
                                         (recur (-> s (update :free #(subvec % 1)) (update :pending #(subvec % 1)))
                                                (assoc outs wk [item])))
                                       [s outs]))
                              [st2 outs] disp]
                          (when (= total (count (:results st2)))
                            (a/put! (:done-ch st2) (mapv second (sort-by first (:results st2))))
                            (a/close! (:done-ch st2)))
                          [st2 (when (seq outs) outs)])))
             make-worker (fn [wk]
                           (fn
                             ([] {:ins {:in "`[idx task]`"} :outs {:out "`[wk idx res]`"} :workload :io})
                             ([_] {:wk wk})
                             ([st _] st)
                             ([st _ [idx task]]
                              [st {:out [[wk idx (run-one-trial! idx task run-opts)]]}])))
             procs (into {:dispatcher {:proc (flow/process disp-fn) :args {:kick-ch kick-ch :done-ch done-ch}
                                       :chan-opts {:done {:buf-or-n (max 4 (* 2 n-par))}}}}
                         (map (fn [wk] [wk {:proc (flow/process (make-worker wk)) :chan-opts {:in {:buf-or-n 1}}}]) wkeys))
             conns (vec (mapcat (fn [wk] [[[:dispatcher wk] [wk :in]] [[wk :out] [:dispatcher :done]]]) wkeys))
             g (flow/create-flow {:procs procs :conns conns})]
         (try
           (flow/start g)
           (flow/resume g)
           (a/>!! kick-ch :start)
           (let [results (a/<!! done-ch)]
             {:ok? (every? :ok? results) :count (count results)
              :elapsed-ms (/ (double (- (System/nanoTime) started-ns)) 1e6)
              :results results :proxy-stats (when proxy-inst (proxy/stats proxy-inst))})
           (finally
             (try (flow/stop g) (catch Throwable _ nil))
             (a/close! kick-ch)
             (when proxy-inst (proxy/stop! proxy-inst)))))))))

(defn- result->stored-trial [& args] (apply trial/result->stored-trial args))

(defn ^:seon.flow/edge start-bench-run-async!
  "Launches `run-parallel-trial!` asynchronously and streams `:seon.bench.run/entity` snapshots via `publish-fn`."
  {:malli/schema [:=> [:cat :map :seon.host/fn] :map]}
  [opts publish-fn]
  (let [run-id (random-uuid)
        tasks (vec (or (:tasks opts) []))
        started-ms (System/currentTimeMillis)
        title (or (not-empty (str (:title opts ""))) (str "Parallel Trial (" (count tasks) " tasks)"))
        init-trials (mapv (fn [idx task]
                            (cond-> {:id (or (:id task) (keyword "trial" (str "t" (inc idx))))
                                   :status :booting :evals 0 :elapsed-ms 0 :alive? false}
                            (:category task) (assoc :category (:category task))
                            (:arm task) (assoc :arm (:arm task))
                            (:pair-id task) (assoc :pair-id (:pair-id task))))
                          (range) tasks)
        state-atom (atom {:trials (into {} (map (juxt :id identity) init-trials))
                          :order (mapv :id init-trials) :proxy-stats nil :last-pub-ms 0})
        build-entity (fn [status finished-ms]
                       (let [{:keys [trials order proxy-stats]} @state-atom
                             trial-vec (mapv #(get trials %) order)]
                         (cond-> {:seon.bench.run/id run-id :seon.bench.run/title title
                                  :seon.bench.run/status status :seon.bench.run/started-ms started-ms
                                  :seon.bench.run/updated-ms (System/currentTimeMillis)
                                  :seon.bench.run/total (count trial-vec)
                                  :seon.bench.run/passed (count (filter #(#{:merged :passed} (:status %)) trial-vec))
                                  :seon.bench.run/failed (count (filter #(#{:failed :timeout :error} (:status %)) trial-vec))
                                  :seon.bench.run/trials trial-vec}
                           finished-ms (assoc :seon.bench.run/finished-ms finished-ms)
                           proxy-stats (assoc :seon.bench.run/proxy-stats proxy-stats)
                           (:suite-id opts) (assoc :seon.bench.run/suite-id (:suite-id opts))
                           (:git-commit opts) (assoc :seon.bench.run/git-commit (str (:git-commit opts)))
                           (:main-tx opts) (assoc :seon.bench.run/main-tx (long (:main-tx opts)))
                           (:model opts) (assoc :seon.bench.run/model (str (:model opts))))))
        pub! (fn [status finished-ms force?]
               (let [now (System/currentTimeMillis)]
                 (when (or force? (>= (- now (:last-pub-ms @state-atom)) 1800))
                   (swap! state-atom assoc :last-pub-ms now)
                   (try (publish-fn (build-entity status finished-ms)) (catch Throwable _ nil)))))
        on-update (fn [trial-msg pstats]
                    (let [prev-st (get-in @state-atom [:trials (:id trial-msg) :status])]
                      (swap! state-atom #(cond-> (update-in % [:trials (:id trial-msg)] merge trial-msg)
                                           pstats (assoc :proxy-stats pstats)))
                      (pub! :running nil (not= prev-st (:status trial-msg)))))]
    (a/thread
      (try
        (pub! :running nil true)
        (let [res (run-parallel-trial! (assoc opts :on-trial-update on-update))
              passed-count (count (filter :ok? (:results res)))
              total-count (count (:results res))]
          (swap! state-atom
            (fn [state]
              (let [trial-map (reduce #(assoc %1 (:id %2) (result->stored-trial %2))
                                (:trials state) (:results res))]
                (cond-> (assoc state :trials trial-map)
                  (:proxy-stats res) (assoc :proxy-stats (:proxy-stats res))))))
          (pub! (cond (= passed-count total-count) :passed (pos? passed-count) :partial :else :failed)
                (System/currentTimeMillis) true))
        (catch Throwable ex
          (swap! state-atom assoc-in [:proxy-stats :error] (or (.getMessage ex) (str ex)))
          (pub! :failed (System/currentTimeMillis) true))))
    {:ok? true :run-id run-id :status :running :title title :total (count tasks)}))