(ns seon.bench.cluster
  "Parallel isolated `target/seon` multi-instance orchestrator for benchmarks and A/B trials.
   Each instance gets its own temp directory, LMDB store (optionally cloned via `--seed-store`),
   ephemeral OS-assigned HTTP port (`--port 0`), `--headless`, and `--auto-switch-over false`,
   optionally sharing a single local `seon.bench.proxy` for concurrency and token-budget rails."
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.bench.proxy :as proxy]
            [seon.model :as model]
            [seon.store :refer [rm-rf!]]
            [seon.bench.helpers :as bh]))

(defn parse-listening-port [& args] (apply bh/parse-listening-port args))

(defn build-instance-cmd [& args] (apply bh/build-instance-cmd args))

(defn agent-settled? [& args] (apply bh/agent-settled? args))

(defn- configure-jolt-env! [& args] (apply bh/configure-jolt-env! args))

(defn ^:seon.flow/edge instance-op!
  "Sends an EDN op map to a running isolated Seon instance via `POST /op` and returns its EDN response."
  {:malli/schema [:=> [:cat [:map [:base-url :string]] :map] :map]}
  [inst op-map]
  (let [url (str (:base-url inst))]
  (if-not (or
            (str/starts-with? url "http://")
            (str/starts-with? url "https://"))
    {:ok? false, :error :invalid-url, :url url}
    (try
      (let [resp (model/http-post!
                   (str url "/op")
                   [["Content-Type" "application/edn"]]
                   (pr-str op-map))]
        (if (= 200 (:status resp))
          (try
            (edn/read-string (:body resp))
            (catch
              Throwable
              ex
              {:ok? false,
               :error :bad-edn,
               :message (or (.getMessage ex) (str ex)),
               :body (:body resp)}))
          {:ok? false,
           :error :http-error,
           :status (:status resp),
           :body (:body resp)}))
      (catch
        Throwable
        ex
        {:ok? false,
         :error :transport,
         :message (or (.getMessage ex) (str ex))})))))

(defn ^:seon.flow/edge stop-instance!
  "Gracefully shuts down an isolated Seon instance (preferring `/op {:op :hand-over}` so its
   ForkParent exits with zero orphans, falling back to Process destroy) and removes its temp dir."
  {:malli/schema [:function
                  [:=> [:cat [:map [:stop! :seon.host/fn]]] :map]
                  [:=> [:cat [:map [:stop! :seon.host/fn]] :map] :map]]}
  ([inst] (stop-instance! inst {}))
  ([inst {:keys [keep-dir?]}]
   (let [handover (try (instance-op! inst {:op :hand-over}) (catch Throwable _ nil))]
     (try ((:stop! inst)) (catch Throwable _))
     (when (and (not keep-dir?) (:work-dir inst))
       (try (rm-rf! (io/file (:work-dir inst))) (catch Throwable _)))
     {:ok? true :id (:id inst) :handover-released? (boolean (:released handover))})))

(defn ^:seon.flow/edge start-instance!
  "Spawns an isolated headless `target/seon` instance on an ephemeral OS port (`--port 0`) with its
   own temp LMDB store (optionally cloned from `:seed-store`), waits for `SEON LISTENING`, and
   returns an instance handle map."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map]]}
  ([] (start-instance! {}))
  ([{:keys [id repo-dir bin-path work-dir store-path seed-store headless? commit-git?
            auto-switch-over? model-base-url model model-stub? max-os-procs max-test-workers
            extra-env timeout-ms]
     :or {headless? true commit-git? false auto-switch-over? false timeout-ms 20000}
     :as opts}]
   (let [repo (str (or repo-dir (System/getProperty "user.dir")))
         bin (str (or bin-path (str repo "/target/seon")))
         inst-id (or id (keyword "bench" (str "inst-" (System/nanoTime))))
         safe-id (str/replace (name inst-id) #"[^A-Za-z0-9._-]" "-")
         wdir (str (or work-dir (str "/tmp/seon-bench-" safe-id "-" (System/nanoTime))))
         spath (str (or store-path (str wdir "/store")))]
     (when-not (.canExecute (io/file bin))
       (throw (ex-info (str "Seon binary missing or not executable: " bin) {:bin-path bin})))
     (.mkdirs (io/file wdir))
     (let [cmd (build-instance-cmd (assoc opts
                                          :bin-path bin
                                          :port 0
                                          :store-path spath
                                          :repo-dir repo
                                          :headless? headless?
                                          :commit-git? commit-git?
                                          :auto-switch-over? auto-switch-over?))
           pb (doto (ProcessBuilder. ^java.util.List cmd)
                (.directory (io/file repo))
                (.redirectErrorStream true))
           _ (configure-jolt-env! pb repo extra-env)
           t0 (System/nanoTime)
           proc (.start pb)
           stdout-r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream proc) "UTF-8"))
           log-atom (atom [])
           ready-ch (a/chan 1)
           delivered? (volatile! false)
           stop-fn (fn []
                     (when (.isAlive proc)
                       (try (.destroy proc) (catch Throwable _))
                       (loop [i 0]
                         (when (and (.isAlive proc) (< i 30))
                           (a/<!! (a/timeout 50))
                           (recur (inc i))))
                       (when (.isAlive proc)
                         (try (.destroyForcibly proc) (catch Throwable _))))
                     (try (.close stdout-r) (catch Throwable _)))]
       (a/thread
         (try
           (loop []
             (when-let [line (.readLine stdout-r)]
               (swap! log-atom (fn [xs] (let [n (conj xs line)] (if (> (count n) 200) (subvec n 1) n))))
               (when (not @delivered?)
                 (when-let [p (parse-listening-port line)]
                   (vreset! delivered? true)
                   (a/put! ready-ch {:port p})))
               (recur)))
           (catch Throwable _ nil)
           (finally
             (when-not @delivered?
               (vreset! delivered? true)
               (a/put! ready-ch {:exited true})))))
       (let [[v ch] (a/alts!! [ready-ch (a/timeout (long timeout-ms))])]
         (if-let [port (:port v)]
           {:id inst-id
            :port port
            :base-url (str "http://127.0.0.1:" port)
            :store-path spath
            :work-dir wdir
            :boot-ms (/ (double (- (System/nanoTime) t0)) 1e6)
            :log-atom log-atom
            :alive? (fn [] (.isAlive proc))
            :stop! stop-fn}
           (let [logs @log-atom]
             (stop-fn)
             (rm-rf! (io/file wdir))
             (throw (ex-info (str "Isolated Seon instance " inst-id " failed to listen within " timeout-ms " ms")
                             {:id inst-id :reason (if (= ch ready-ch) :exited-early :timeout) :logs (take-last 30 logs)})))))))))

(defn ^:seon.flow/edge wait-for-agent!
"Polls `inst` via `/op {:op :status}` until `agent-id` satisfies `agent-settled?` or `timeout-ms` elapses."
{:malli/schema [:function
                [:=> [:cat [:map [:base-url :string] [:stop! :seon.host/fn]] :keyword :int] :map]
                [:=> [:cat [:map [:base-url :string] [:stop! :seon.host/fn]] :keyword :int [:maybe :seon.host/fn]] :map]]}
([inst agent-id timeout-ms] (wait-for-agent! inst agent-id timeout-ms nil))
([inst agent-id timeout-ms on-poll]
 (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
   (loop [polls 0]
     (let [st (instance-op! inst {:op :status})]
       (when (and on-poll (:ok? st))
         (try (on-poll st polls) (catch Throwable _ nil)))
       (cond
         (and (:ok? st) (agent-settled? st agent-id))
         {:ok? true :settled? true :polls polls :status st}

         (>= (System/currentTimeMillis) deadline)
         {:ok? false :settled? false :polls polls :status st :error :timeout}

         :else
         (do (a/<!! (a/timeout 500))
             (recur (inc polls)))))))))

(defn ^:seon.flow/edge probe-trial-trace
  "Queries the isolated trial instance for `aid`'s latest 2 evals (snippet + last error) so
   live monitoring and failure post-mortems never lose what a worker did before teardown."
  {:malli/schema [:=> [:cat :map :keyword :keyword] :map]}
  [inst aid branch]
  (try
    (let [code (str "(let [bdb (seon.store/head (user/conn) " (pr-str branch) ") "
                    "evs (->> (datahike.api/datoms bdb :avet :seon.agent.eval/agent " (pr-str aid) ") "
                    "(map #(datahike.api/pull bdb '[:seon.agent.eval/handle :seon.agent.eval/turn :seon.agent.eval/status :seon.agent.eval/error :seon.agent.eval/code] (:e %))) "
                    "(sort-by #(or (:seon.agent.eval/turn %) 0)) vec) "
                    "last-ev (last evs) "
                    "last-err (last (filter #(or (:seon.agent.eval/error %) (= :error (:seon.agent.eval/status %))) evs)) "
                    "clip (fn [s n] (let [t (clojure.string/replace (str (or s \"\")) #\"\\s+\" \" \") ] (if (> (count t) n) (str (subs t 0 n) \"…\") t)))] "
                    "{:count (count evs) "
                    ":last-snippet (when last-ev (str (:seon.agent.eval/handle last-ev) \": \" (clip (:seon.agent.eval/code last-ev) 100))) "
                    ":last-error (when last-err (str (:seon.agent.eval/handle last-err) \" ERR: \" (clip (:seon.agent.eval/error last-err) 140)))})")
          res (instance-op! inst {:op :eval :agent :repl/bench-probe :branch :branch/bench-probe :code code})
          parsed (when (and (:ok? res) (string? (:value res)))
                   (try (edn/read-string (:value res)) (catch Throwable _ nil)))]
      (if (map? parsed) parsed {}))
    (catch Throwable _ {})))

(defn trial-ids
  "Derives `{:id :branch :agent-id}` keywords for `task` at 0-based `idx`."
  {:malli/schema [:=> [:cat :int :map] [:map [:id :keyword] [:branch :keyword] [:agent-id :keyword]]], :cost/ticks 600}
  [idx task]
  (let [trial-id (or (:id task) (keyword "trial" (str "t" (inc (long idx)))))]
    {:id trial-id
     :branch (or (:branch task) (keyword "branch" (str "trial-" (name trial-id))))
     :agent-id (or (:agent-id task) (keyword "bench" (name trial-id)))}))

(defn trial-instance-opts
  "Merges a trial's per-task overrides over run-level `opts` and boot defaults."
  {:malli/schema [:=> [:cat :keyword :map :map] :map]
   :cost/ticks 400}
  [trial-id task opts]
  {:id trial-id
   :repo-dir (or (:repo-dir task) (:repo-dir opts))
   :bin-path (or (:bin-path task) (:bin-path opts))
   :seed-store (or (:seed-store task) (:seed-store opts))
   :model-base-url (or (:model-base-url task) (:eff-base-url opts))
   :model (or (:model task) (:model opts))
   :model-stub? (if (contains? task :model-stub?) (:model-stub? task) (:model-stub? opts))
   :max-os-procs (or (:max-os-procs task) (:max-os-procs opts) 2)
   :max-test-workers (or (:max-test-workers task) (:max-test-workers opts) 2)
   :timeout-ms (or (:boot-timeout-ms opts) 25000)})

(defn trial-task-plan
  "Resolves a trial's inline task map, task-entity id, scope, goal, and body text."
  {:malli/schema [:=> [:cat :map] [:map [:task-map [:maybe :map]] [:task-id [:maybe :keyword]]
                                       [:scope [:maybe :any]] [:goal :string] [:body [:maybe :string]]]]
   :cost/ticks 300}
  [task]
  (let [task-map (when (map? (:task task)) (:task task))
        task-id (or (:id task-map) (when (keyword? (:task task)) (:task task)))]
    {:task-map task-map
     :task-id task-id
     :scope (or (:scope task) (:scope task-map))
     :goal (str (or (:goal task) (:title task-map) "Complete assigned task."))
     :body (or (:body task)
               (when task-id "Work your assigned task, prove it green, and call (user/ready!)."))}))

(defn trial-spawn-op
  "Builds the `/op {:op :spawn-agent}` payload for a trial worker."
  {:malli/schema [:=> [:cat :keyword :keyword [:map [:goal :string]]] :map]
   :cost/ticks 200}
  [agent-id branch {:keys [goal scope task-id]}]
  (cond-> {:op :spawn-agent :id agent-id :branch branch :role :worker :goal goal}
    scope (assoc :scope scope)
    task-id (assoc :task task-id)))

(defn trial-message-op
  "Builds the `/op {:op :send-message}` payload carrying the trial body, or nil."
  {:malli/schema [:=> [:cat :keyword [:maybe :string]] [:maybe :map]]
   :cost/ticks 150}
  [agent-id body]
  (when body
    {:op :send-message :from :user :to agent-id :type :chat :body (str body)}))

(defn trial-progress
  "Builds the live progress snapshot for one trial from a `/op {:op :status}` poll."
  {:malli/schema [:=> [:cat :keyword [:map [:agents [:maybe [:sequential :map]]]] [:maybe :map]]
                        [:map [:status :keyword] [:evals :int] [:alive? :boolean]]]
   :cost/ticks 300}
  [agent-id status-msg trace]
  (let [ag (some #(when (= agent-id (:id %)) %) (:agents status-msg))]
    (cond-> {:status (or (:status ag) :running)
             :evals (long (or (:evals ag) 0))
             :alive? true}
      (:last-snippet trace) (assoc :last-snippet (:last-snippet trace))
      (:last-error trace) (assoc :last-error (:last-error trace)))))

(defn trial-outcome
  "Computes a trial's merge/ok flags from its per-op responses and task options."
  {:malli/schema [:=> [:cat [:map [:task :map] [:ag-snap [:maybe :map]] [:eval-res [:maybe :map]]
                                [:agent-res [:maybe :map]] [:test-res [:maybe :map]] [:merge-res [:maybe :map]]]]
                        [:map [:already-merged? :boolean] [:merged? :boolean] [:require-merge? :boolean]
                              [:halted-not-merged? :boolean] [:ok? :boolean]]]
   :cost/ticks 400}
  [{:keys [task ag-snap eval-res agent-res test-res merge-res]}]
  (let [already-merged? (= :merged (:status ag-snap))
        merged? (boolean (or already-merged? (:ok? merge-res)))
        require-merge? (if (contains? task :require-merge?)
                         (:require-merge? task)
                         (boolean (or (:task task) (:merge? task))))
        halted-not-merged? (and require-merge? (not merged?))]
    {:already-merged? already-merged?
     :merged? merged?
     :require-merge? require-merge?
     :halted-not-merged? halted-not-merged?
     :ok? (boolean (and (if eval-res (:ok? eval-res) true)
                        (if agent-res (get-in agent-res [:wait :ok?]) true)
                        (if test-res (:ok? test-res) true)
                        (if merge-res (:ok? merge-res) true)
                        (not halted-not-merged?)))}))

(defn trial-failure
  "Renders the first human-readable failure reason for a trial, or nil when green."
  {:malli/schema [:=> [:cat [:map [:timeout-ms :int]
                                [:agent-res [:maybe :map]]
                                [:test-res [:maybe :map]]
                                [:merge-res [:maybe :map]]
                                [:ag-snap [:maybe :map]]
                                [:final-trace [:maybe :map]]
                                [:health-res [:maybe :map]]
                                [:halted-not-merged? :boolean]]]
                        [:maybe :string]]
   :cost/ticks 500}
  [{:keys [timeout-ms agent-res test-res merge-res ag-snap final-trace health-res halted-not-merged?]}]
  (or (when (= :timeout (get-in agent-res [:wait :error]))
        (str "Timed out after " (long timeout-ms) " ms"))
      (when (and test-res (not (:ok? test-res)))
        (let [f1 (first (remove :seon.test/pass? (:results test-res)))
              c1 (first (:conflicts test-res))]
          (str "test-failed: "
               (or (:seon.test/var f1)
                   (when c1 (str (:reason c1) " " (or (:message c1) (:entity c1))))
                   (:error test-res)
                   "red"))))
      (when (and merge-res (not (:ok? merge-res)))
        (str "merge-failed: " (or (:error merge-res) (:message merge-res) "blocked")))
      (when halted-not-merged?
        (or (:last-error final-trace)
            (str "not-merged (status " (:status ag-snap) ", last " (:last-snippet final-trace) ")")))
      (when (not (:ok? health-res))
        (str "health-err: "
             (or (:error health-res)
                 (pr-str (take 2 (filter #(= :error (:severity %)) (:problems health-res)))))))
      (:last-error final-trace)))

(defn trial-result
  "Assembles one trial's public result map from its op responses."
  {:malli/schema [:=> [:cat :map] :map]
   :cost/ticks 400}
  [{:keys [id port boot-ms elapsed-ms outcome ag-snap eval-res agent-res test-res merge-res
           health-res final-trace failure]}]
  {:id id
   :port port
   :boot-ms boot-ms
   :elapsed-ms elapsed-ms
   :ok? (boolean (:ok? outcome))
   :merged? (boolean (:merged? outcome))
   :agent-status (:status ag-snap)
   :evals (long (or (:evals ag-snap) 0))
   :eval eval-res
   :agent agent-res
   :test test-res
   :merge merge-res
   :health-ok? (boolean (:ok? health-res))
   :last-snippet (:last-snippet final-trace)
   :last-error failure})

(defn run-one-trial!
  "Boots one isolated instance, drives a full trial against it, tears it down, returns its result map."
  {:malli/schema [:=> [:cat :int :map :map] :map], :seon.eval/budget-override {:ticks 2000000000, :bytes 1073741824}, :seon.flow/edge true}
  [idx task opts]
  (let [{:keys [on-trial-update keep-dirs? task-timeout-ms]} opts
        p (:proxy-instance opts)
        {:keys [id branch agent-id]} (trial-ids idx task)
        t-start (System/nanoTime)
        inst (atom nil)
        elapsed-ms! (fn [] (/ (double (- (System/nanoTime) t-start)) 1e6))
        emit! (fn [m]
                (when on-trial-update
                  (try
                    (on-trial-update (assoc m :id id :port (:port @inst) :elapsed-ms (long (elapsed-ms!)))
                                     (when p (proxy/stats p)))
                    (catch Throwable _ nil))))
        timeout-ms (long (or (:timeout-ms task) task-timeout-ms 60000))]
    (try
      (let [i (start-instance! (trial-instance-opts id task opts))
            _ (reset! inst i)
            _ (emit! {:status :booted :evals 0 :alive? true :boot-ms (long (:boot-ms i))})
            plan (trial-task-plan task)
            eval-res (when-let [code (:eval-code task)]
                       (instance-op! i {:op :eval :agent agent-id :branch branch :code code}))
            agent-res (when (or (:task task) (:goal task) (:body task))
                        (let [tmap (:task-map plan)
                              _ (when tmap (instance-op! i (assoc tmap :op :create-task)))
                              spawn (instance-op! i (trial-spawn-op agent-id branch plan))
                              msg (when-let [op (trial-message-op agent-id (:body plan))]
                                    (instance-op! i op))
                              wait (wait-for-agent!
                                    i agent-id timeout-ms
                                    (when on-trial-update
                                      (fn [st polls]
                                        (when (zero? (mod polls 4))
                                          (let [ag (some #(when (= agent-id (:id %)) %) (:agents st))
                                                tr (when (pos? (long (or (:evals ag) 0)))
                                                     (probe-trial-trace i agent-id branch))]
                                            (emit! (trial-progress agent-id st tr)))))))]
                          {:spawn spawn :message msg :wait wait}))
            ag-snap (some #(when (= agent-id (:id %)) %) (get-in agent-res [:wait :status :agents]))
            final-trace (when (and agent-res
                                   (or (not (get-in agent-res [:wait :ok?]))
                                       (not= :merged (:status ag-snap))))
                          (probe-trial-trace i agent-id branch))
            already-merged? (= :merged (:status ag-snap))
            test-res (when (and (:run-tests? task)
                                (or (not already-merged?) (:retest-main? task)))
                       (instance-op! i {:op :test :branch (if already-merged? :main branch)}))
            merge-res (when (:merge? task)
                        (if already-merged?
                          {:ok? true :already-merged? true :branch branch}
                          (instance-op! i {:op :merge :branch branch
                                           :message (str "Bench trial " (name id))})))
            health-res (instance-op! i {:op :health})
            outcome (trial-outcome {:task task :ag-snap ag-snap :eval-res eval-res
                                    :agent-res agent-res :test-res test-res :merge-res merge-res})
            failure (trial-failure {:timeout-ms timeout-ms
                                    :agent-res agent-res
                                    :test-res test-res
                                    :merge-res merge-res
                                    :ag-snap ag-snap
                                    :final-trace final-trace
                                    :health-res health-res
                                    :halted-not-merged? (:halted-not-merged? outcome)})]
        (trial-result {:id id
                       :port (:port i)
                       :boot-ms (:boot-ms i)
                       :elapsed-ms (elapsed-ms!)
                       :outcome outcome
                       :ag-snap ag-snap
                       :eval-res eval-res
                       :agent-res agent-res
                       :test-res test-res
                       :merge-res merge-res
                       :health-res health-res
                       :final-trace final-trace
                       :failure failure}))
      (catch Throwable ex
        {:id id
         :ok? false
         :elapsed-ms (elapsed-ms!)
         :error (or (.getMessage ex) (str ex))
         :data (ex-data ex)})
      (finally
        (when-let [i @inst]
          (stop-instance! i {:keep-dir? keep-dirs?}))))))

(defn ^:seon.flow/edge run-parallel-trial!
"Runs `tasks` concurrently across N isolated `target/seon` instances (optionally routing all model
 calls through one shared `seon.bench.proxy`), collecting per-instance metrics and cleaning up
 every instance and temp directory before returning."
{:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map]]}
([] (run-parallel-trial! {:tasks []}))
([{:keys [tasks proxy? proxy-opts model-base-url boot-timeout-ms task-timeout-ms keep-dirs?]
   :as opts
   :or {proxy? false boot-timeout-ms 25000 task-timeout-ms 60000 keep-dirs? false}}]
 (let [t0 (System/nanoTime)
       p (when proxy? (proxy/start! (or proxy-opts {})))
       run-opts (assoc opts
                       :eff-base-url (or (when p (:base-url p)) model-base-url)
                       :proxy-instance p
                       :boot-timeout-ms boot-timeout-ms
                       :task-timeout-ms task-timeout-ms
                       :keep-dirs? keep-dirs?)]
   (try
     (let [results (->> (map-indexed vector tasks)
                        (mapv (fn [[idx task]] (a/thread (run-one-trial! idx task run-opts))))
                        (mapv a/<!!))]
       {:ok? (every? :ok? results)
        :count (count results)
        :elapsed-ms (/ (double (- (System/nanoTime) t0)) 1e6)
        :results results
        :proxy-stats (when p (proxy/stats p))})
     (finally
       (when p (proxy/stop! p)))))))

(defn ^:seon.flow/edge start-bench-run-async!
  "Launches `run-parallel-trial!` asynchronously on a background thread and streams throttled
   `:seon.bench.run/entity` snapshots via `(publish-fn entity-map)` so the Web UI monitors live
   progress, proxy tokens/KV cache, and failure traces over a single coordinator port."
  {:malli/schema [:=> [:cat :map :seon.host/fn] :map]}
  [opts publish-fn]
  (let [run-id (random-uuid)
        tasks (vec (or (:tasks opts) []))
        started-ms (System/currentTimeMillis)
        title (or (not-empty (str (:title opts ""))) (str "Parallel Trial (" (count tasks) " tasks)"))
        init-trials (mapv (fn [idx t]
                            {:id (or (:id t) (keyword "trial" (str "t" (inc idx))))
                             :status :booting :evals 0 :elapsed-ms 0 :alive? false})
                          (range) tasks)
        state-atom (atom {:trials (into {} (map (juxt :id identity) init-trials))
                          :order (mapv :id init-trials)
                          :proxy-stats nil
                          :last-pub-ms 0})
        build-entity (fn [status finished-ms]
                       (let [{:keys [trials order proxy-stats]} @state-atom
                             tvec (mapv #(get trials %) order)
                             passed (count (filter #(#{:merged :passed} (:status %)) tvec))
                             failed (count (filter #(#{:failed :timeout :error} (:status %)) tvec))
                             now-ms (System/currentTimeMillis)]
                         (cond-> {:seon.bench.run/id run-id
                                  :seon.bench.run/title title
                                  :seon.bench.run/status status
                                  :seon.bench.run/started-ms started-ms
                                  :seon.bench.run/updated-ms now-ms
                                  :seon.bench.run/total (count tvec)
                                  :seon.bench.run/passed passed
                                  :seon.bench.run/failed failed
                                  :seon.bench.run/trials tvec}
                           finished-ms (assoc :seon.bench.run/finished-ms finished-ms)
                           proxy-stats (assoc :seon.bench.run/proxy-stats proxy-stats))))
        pub! (fn [status finished-ms force?]
               (let [now (System/currentTimeMillis) last-p (:last-pub-ms @state-atom)]
               (when (or force? (>= (- now last-p) 1800))
                 (swap! state-atom assoc :last-pub-ms now)
                 (try
                   (publish-fn (build-entity status finished-ms))
                   (catch Throwable _ nil)))))]
    (do)
    (a/thread
      (try
        (let [res (do
                  (pub! :running nil true)
                  (run-parallel-trial!
                    (assoc
                      opts
                      :on-trial-update
                      (fn [tm pstats]
                        (let [prev-st (get-in @state-atom [:trials (:id tm) :status])]
                          (swap!
                            state-atom
                            (fn [s]
                              (cond->
                                (update-in s [:trials (:id tm)] merge tm)
                                pstats
                                (assoc :proxy-stats pstats))))
                          (pub! :running nil (not= prev-st (:status tm))))))))]
          (swap! state-atom
                 (fn [s]
                   (let [tmap (reduce (fn [acc r]
                                        (let [st (cond (:ok? r) (if (:merged? r) :merged :passed)
                                                       (and (:last-error r) (str/includes? (str (:last-error r)) "Timed out")) :timeout
                                                       :else :failed)]
                                          (assoc acc (:id r)
                                                 (cond-> {:id (:id r)
                                                          :status st
                                                          :port (:port r)
                                                          :evals (long (or (:evals r) 0))
                                                          :elapsed-ms (long (or (:elapsed-ms r) 0))
                                                          :boot-ms (long (or (:boot-ms r) 0))
                                                          :merged? (boolean (:merged? r))
                                                          :health-ok? (boolean (:health-ok? r))
                                                          :alive? false}
                                                   (:last-snippet r) (assoc :last-snippet (:last-snippet r))
                                                   (or (:last-error r) (:error r)) (assoc :last-error (str (or (:last-error r) (:error r))))))))
                                      (:trials s)
                                      (:results res))]
                     (cond-> (assoc s :trials tmap)
                       (:proxy-stats res) (assoc :proxy-stats (:proxy-stats res))))))
          (let [n-pass (count (filter :ok? (:results res)))
                n-tot (count (:results res))
                fin-st (cond (= n-pass n-tot) :passed
                             (pos? n-pass) :partial
                             :else :failed)]
            (pub! fin-st (System/currentTimeMillis) true)))
        (catch Throwable ex
          (swap! state-atom assoc-in [:proxy-stats :error] (or (.getMessage ex) (str ex)))
          (pub! :failed (System/currentTimeMillis) true))))
    {:ok? true :run-id run-id :status :running :title title :total (count tasks)}))
