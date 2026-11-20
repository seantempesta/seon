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
            [seon.store :refer [rm-rf!]]))

(defn parse-listening-port
  "Extracts the bound localhost integer port from a Seon startup log line, or nil."
  {:malli/schema [:=> {:examples [[["SEON LISTENING http://127.0.0.1:54321 | Headless | Store /tmp/s"] 54321]
                                  [["Booting Seon..."] nil]
                                  [[""] nil]]}
                  [:cat :string] [:maybe :int]]
   :cost/ticks 250000
   :cost/bytes 2097152}
  [line]
  (when-let [[_ port-str] (re-find #"SEON LISTENING https?://[^:\s]+:(\d+)" line)]
    (parse-long port-str)))

(defn build-instance-cmd
  "Builds the CLI argv vector for spawning an isolated headless `target/seon` benchmark instance."
  {:malli/schema [:=> {:examples [[[{:bin-path "/app/target/seon"
                                     :store-path "/tmp/s1"
                                     :repo-dir "/app"
                                     :model-stub? true}]
                                   ["/app/target/seon"
                                    "--port" "0"
                                    "--headless"
                                    "--store" "/tmp/s1"
                                    "--repo" "/app"
                                    "--commit-git" "false"
                                    "--auto-switch-over" "false"
                                    "--model-stub"]]]}
                  [:cat :map] [:vector :string]]
   :cost/ticks 500000
   :cost/bytes 4194304}
  [{:keys [bin-path port store-path seed-store repo-dir headless? commit-git? auto-switch-over?
           model-base-url model model-stub? max-os-procs max-test-workers]
    :or {port 0 headless? true commit-git? false auto-switch-over? false}}]
  (cond-> [(str (or bin-path "target/seon"))
           "--port" (str (long (or port 0)))]
    headless? (conj "--headless")
    store-path (conj "--store" (str store-path))
    seed-store (conj "--seed-store" (str seed-store))
    repo-dir (conj "--repo" (str repo-dir))
    (some? commit-git?) (conj "--commit-git" (if commit-git? "true" "false"))
    (some? auto-switch-over?) (conj "--auto-switch-over" (if auto-switch-over? "true" "false"))
    model-base-url (conj "--model-base-url" (str model-base-url))
    model (conj "--model" (str model))
    model-stub? (conj "--model-stub")
    max-os-procs (conj "--max-os-procs" (str (long max-os-procs)))
    max-test-workers (conj "--max-test-workers" (str (long max-test-workers)))))

(defn agent-settled?
  "Returns true when `agent-id` in a `/op {:op :status}` snapshot has executed at least one eval
   (or merged) and has no pending turns, unread messages, awaits, tests, or merges."
  {:malli/schema [:=> {:examples [[[{:agents [{:id :w/a :evals 2 :busy? false :unread-count 0
                                               :awaiting-count 0 :needs-turn? false :needs-test? false
                                               :needs-merge? false :status :waiting}]}
                                   :w/a]
                                  true]
                                 [[{:agents [{:id :w/a :evals 0 :busy? true :unread-count 1
                                               :awaiting-count 0 :needs-turn? true :needs-test? false
                                               :needs-merge? false :status :waiting}]}
                                   :w/a]
                                  false]
                                 [[{:agents []} :w/missing]
                                  false]]}
                  [:cat :map :keyword] :boolean]
   :cost/ticks 500000
   :cost/bytes 4194304}
  [status-map agent-id]
  (boolean
   (when-let [ag (some #(when (= agent-id (:id %)) %) (:agents status-map))]
     (and (not (:busy? ag))
          (zero? (long (or (:unread-count ag) 0)))
          (zero? (long (or (:awaiting-count ag) 0)))
          (not (:needs-turn? ag))
          (not (:needs-test? ag))
          (not (:needs-merge? ag))
          (or (= :merged (:status ag))
              (pos? (long (or (:evals ag) 0))))))))

(defn- configure-jolt-env! [^ProcessBuilder pb repo-dir extra-env]
  (let [env (.environment pb)
        lib-dir (io/file repo-dir "target/lib")]
    (.put env "SEON_BINARY" "1")
    (doseq [[env-k base] [["JOLT_LZ4_LIB" "liblz4"]
                          ["JOLT_ZSTD_LIB" "libzstd"]
                          ["JOLT_LMDB_LIB" "liblmdb"]]]
      (when-not (.get env env-k)
        (let [dylib (io/file lib-dir (str base ".dylib"))
              so (io/file lib-dir (str base ".so"))]
          (cond
            (.isFile dylib) (.put env env-k (.getAbsolutePath dylib))
            (.isFile so) (.put env env-k (.getAbsolutePath so))))))
    (doseq [[k v] extra-env]
      (when (and k (some? v))
        (.put env (str k) (str v))))))

(defn ^:seon.flow/edge instance-op!
  "Sends an EDN op map to a running isolated Seon instance via `POST /op` and returns its EDN response."
  {:malli/schema [:=> [:cat [:map [:base-url :string] [:stop! :seon.host/fn]] :map] :map]}
  [inst op-map]
  (let [resp (model/http-post! (str (:base-url inst) "/op")
                               [["Content-Type" "application/edn"]]
                               (pr-str op-map))]
    (if (= 200 (:status resp))
      (try
        (edn/read-string (:body resp))
        (catch Throwable ex
          {:ok? false :error :bad-edn :message (or (.getMessage ex) (str ex)) :body (:body resp)}))
      {:ok? false :error :http-error :status (:status resp) :body (:body resp)})))

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
  {:malli/schema [:=> [:cat [:map [:base-url :string] [:stop! :seon.host/fn]] :keyword :int] :map]}
  [inst agent-id timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop [polls 0]
      (let [st (instance-op! inst {:op :status})]
        (cond
          (and (:ok? st) (agent-settled? st agent-id))
          {:ok? true :settled? true :polls polls :status st}

          (>= (System/currentTimeMillis) deadline)
          {:ok? false :settled? false :polls polls :status st :error :timeout}

          :else
          (do (a/<!! (a/timeout 150))
              (recur (inc polls))))))))

(defn ^:seon.flow/edge run-parallel-trial!
  "Runs `tasks` concurrently across N isolated `target/seon` instances (optionally routing all model
   calls through one shared `seon.bench.proxy`), collecting per-instance metrics and cleaning up
   every instance and temp directory before returning."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map]]}
  ([] (run-parallel-trial! {:tasks []}))
  ([{:keys [tasks repo-dir bin-path seed-store proxy? proxy-opts model-base-url model model-stub?
            max-os-procs max-test-workers boot-timeout-ms task-timeout-ms keep-dirs?]
     :or {proxy? false boot-timeout-ms 25000 task-timeout-ms 60000 keep-dirs? false}}]
   (let [t0 (System/nanoTime)
         p (when proxy? (proxy/start! (or proxy-opts {})))
         eff-base-url (or (when p (:base-url p)) model-base-url)]
     (try
       (let [chs (mapv
                  (fn [idx task]
                    (a/thread
                      (let [tid (or (:id task) (keyword "trial" (str "t" (inc idx))))
                            branch (or (:branch task) (keyword "branch" (str "trial-" (name tid))))
                            aid (or (:agent-id task) (keyword "bench" (name tid)))
                            t-start (System/nanoTime)
                            inst (atom nil)]
                        (try
                          (let [i (start-instance!
                                   {:id tid
                                    :repo-dir (or (:repo-dir task) repo-dir)
                                    :bin-path (or (:bin-path task) bin-path)
                                    :seed-store (or (:seed-store task) seed-store)
                                    :model-base-url (or (:model-base-url task) eff-base-url)
                                    :model (or (:model task) model)
                                    :model-stub? (if (contains? task :model-stub?) (:model-stub? task) model-stub?)
                                    :max-os-procs (or (:max-os-procs task) max-os-procs 2)
                                    :max-test-workers (or (:max-test-workers task) max-test-workers 2)
                                    :timeout-ms boot-timeout-ms})
                                _ (reset! inst i)
                                eval-res (when-let [code (:eval-code task)]
                                           (instance-op! i {:op :eval :agent aid :branch branch :code code}))
                                agent-res (when (or (:goal task) (:body task))
                                            (let [sp (instance-op! i (cond-> {:op :spawn-agent
                                                                              :id aid
                                                                              :branch branch
                                                                              :role :worker
                                                                              :goal (str (or (:goal task) "Complete benchmark task."))}
                                                                       (:scope task) (assoc :scope (:scope task))))
                                                  msg (when (:body task)
                                                        (instance-op! i {:op :send-message
                                                                         :from :user
                                                                         :to aid
                                                                         :type :chat
                                                                         :body (str (:body task))}))
                                                  wait (wait-for-agent! i aid (long (or (:timeout-ms task) task-timeout-ms)))]
                                              {:spawn sp :message msg :wait wait}))
                                test-res (when (:run-tests? task)
                                           (instance-op! i {:op :test :branch branch}))
                                merge-res (when (:merge? task)
                                            (instance-op! i {:op :merge
                                                             :branch branch
                                                             :message (str "Bench trial " (name tid))}))
                                health-res (instance-op! i {:op :health})
                                ok? (and (if eval-res (:ok? eval-res) true)
                                         (if agent-res (get-in agent-res [:wait :ok?]) true)
                                         (if test-res (:ok? test-res) true)
                                         (if merge-res (:ok? merge-res) true))]
                            {:id tid
                             :port (:port i)
                             :boot-ms (:boot-ms i)
                             :elapsed-ms (/ (double (- (System/nanoTime) t-start)) 1e6)
                             :ok? (boolean ok?)
                             :eval eval-res
                             :agent agent-res
                             :test test-res
                             :merge merge-res
                             :health-ok? (boolean (:ok? health-res))})
                          (catch Throwable ex
                            {:id tid
                             :ok? false
                             :elapsed-ms (/ (double (- (System/nanoTime) t-start)) 1e6)
                             :error (or (.getMessage ex) (str ex))
                             :data (ex-data ex)})
                          (finally
                            (when-let [i @inst]
                              (stop-instance! i {:keep-dir? keep-dirs?})))))))
                  (range)
                  tasks)
             results (mapv a/<!! chs)]
         {:ok? (every? :ok? results)
          :count (count results)
          :elapsed-ms (/ (double (- (System/nanoTime) t0)) 1e6)
          :results results
          :proxy-stats (when p (proxy/stats p))})
       (finally
         (when p (proxy/stop! p)))))))
