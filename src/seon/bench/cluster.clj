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
            [jolt.process :as proc]))

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
     (let [cmd (build-instance-cmd (assoc opts :bin-path bin :port 0 :store-path spath :repo-dir repo
                                          :headless? headless? :commit-git? commit-git? :auto-switch-over? auto-switch-over?))
           started-ns (System/nanoTime)
           jproc (proc/process cmd {:dir repo :err :out :extra-env (bh/jolt-env-map repo extra-env) :shutdown proc/destroy-tree})
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

(defn trial-ids
  "Derives `{:id :branch :agent-id}` keywords for `task` at 0-based `idx`."
  {:malli/schema [:=> [:cat :int :map] [:map [:id :keyword] [:branch :keyword] [:agent-id :keyword]]], :cost/ticks 600}
  [idx task]
  (let [trial-id (or (:id task) (keyword "trial" (str "t" (inc (long idx)))))]
    {:id trial-id
     :branch (or (:branch task) (when (:chat-agent task) :branch/root) (keyword "branch" (str "trial-" (name trial-id))))
     :agent-id (or (:chat-agent task) (:agent-id task) (keyword "bench" (name trial-id)))}))

(defn trial-instance-opts
  "Merges a trial's per-task overrides over run-level `opts` and boot defaults."
  {:malli/schema [:=> [:cat :keyword :map :map] :map] :cost/ticks 400}
  [trial-id task opts]
  {:id trial-id :repo-dir (or (:repo-dir task) (:repo-dir opts))
   :bin-path (or (:bin-path task) (:bin-path opts)) :seed-store (or (:seed-store task) (:seed-store opts))
   :model-base-url (or (:model-base-url task) (:eff-base-url opts)) :model (or (:model task) (:model opts))
   :model-stub? (if (contains? task :model-stub?) (:model-stub? task) (:model-stub? opts))
   :max-os-procs (or (:max-os-procs task) (:max-os-procs opts) 2)
   :max-test-workers (or (:max-test-workers task) (:max-test-workers opts) 2)
   :timeout-ms (or (:boot-timeout-ms opts) 25000)})

(defn trial-task-plan
  "Resolves a trial's inline task map, task-entity id, scope, goal, and body text."
  {:malli/schema [:=> [:cat :map] [:map [:task-map [:maybe :map]] [:task-id [:maybe :keyword]]
                                   [:scope [:maybe :any]] [:goal :string] [:body [:maybe :string]]]] :cost/ticks 300}
  [task]
  (let [task-map (when (map? (:task task)) (:task task))
        task-id (or (:id task-map) (when (keyword? (:task task)) (:task task)))]
    {:task-map task-map :task-id task-id :scope (or (:scope task) (:scope task-map))
     :goal (str (or (:goal task) (:title task-map) "Complete assigned task."))
     :body (or (:body task) (when task-id "Work your assigned task, prove it green, and call (user/ready!)."))}))

(defn trial-spawn-op
  "Builds the `/op {:op :spawn-agent}` payload for a trial worker."
  {:malli/schema [:=> [:cat :keyword :keyword [:map [:goal :string]]] :map]
   :cost/ticks 200}
  [agent-id branch {:keys [goal scope task-id]}]
  (cond-> {:op :spawn-agent :id agent-id :branch branch :goal goal}
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
                  [:map [:status :keyword] [:evals :int] [:alive? :boolean]]] :cost/ticks 300}
  [agent-id status-msg trace]
  (let [agent-row (some #(when (= agent-id (:id %)) %) (:agents status-msg))]
    (cond-> {:status (or (:status agent-row) :running) :evals (long (or (:evals agent-row) 0)) :alive? true}
      (:last-snippet trace) (assoc :last-snippet (:last-snippet trace))
      (:last-error trace) (assoc :last-error (:last-error trace)))))

(defn callable-specialist-id?
  "Returns true when `agent-id` identifies a callable subsystem specialist (`:ns/*` or `:specialist/*`)."
  {:malli/schema [:=> [:cat :any] :boolean] :cost/ticks 200}
  [agent-id]
  (boolean (and (keyword? agent-id)
                (contains? #{"ns" "specialist"} (namespace agent-id)))))

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

(defn- settled-call-count [final-trace]
  (if (and (map? final-trace) (contains? final-trace :settled-delegated-calls))
    (long (or (:settled-delegated-calls final-trace) 0))
    (long (or (:delegated-calls final-trace) 0))))

(defn trial-outcome
  "Computes a trial's merge/ok flags from its per-op responses, task options, and optional trace."
  {:malli/schema [:=> [:cat [:map [:task :map] [:ag-snap [:maybe :map]] [:eval-res [:maybe :map]]
                             [:agent-res [:maybe :map]] [:test-res [:maybe :map]] [:merge-res [:maybe :map]]
                             [:final-trace {:optional true} [:maybe :map]]]]
                  [:map [:already-merged? :boolean] [:merged? :boolean] [:require-merge? :boolean]
                   [:halted-not-merged? :boolean] [:ok? :boolean]]] :cost/ticks 600}
  [{:keys [task ag-snap eval-res agent-res test-res merge-res final-trace]}]
  (let [already-merged? (= :merged (:status ag-snap))
        merged? (boolean (or already-merged? (:ok? merge-res)))
        require-merge? (if (contains? task :require-merge?) (:require-merge? task) (boolean (or (:task task) (:merge? task))))
        halted-not-merged? (and require-merge? (not merged?))
        min-delegated (long (or (:min-delegated-calls task) 0))
        delegated-ok? (or (not (pos? min-delegated)) (>= (settled-call-count final-trace) min-delegated))
        reply-ok? (or (not (:chat-agent task)) (not (str/blank? (str (or (:last-reply final-trace) "")))))]
    {:already-merged? already-merged? :merged? merged? :require-merge? require-merge?
     :halted-not-merged? halted-not-merged?
     :ok? (boolean (and (if eval-res (:ok? eval-res) true)
                        (if agent-res (get-in agent-res [:wait :ok?]) true)
                        (if test-res (:ok? test-res) true)
                        (if merge-res (:ok? merge-res) true)
                        (not halted-not-merged?) delegated-ok? reply-ok?))}))

(defn trial-failure
  "Renders the first human-readable failure reason for a trial, or nil when green."
  {:malli/schema [:=> [:cat [:map [:timeout-ms :int] [:agent-res [:maybe :map]] [:test-res [:maybe :map]]
                             [:merge-res [:maybe :map]] [:ag-snap [:maybe :map]] [:final-trace [:maybe :map]]
                             [:health-res [:maybe :map]] [:halted-not-merged? :boolean]
                             [:task {:optional true} [:maybe :map]]]] [:maybe :string]] :cost/ticks 15000000}
  [{:keys [timeout-ms agent-res test-res merge-res ag-snap final-trace health-res halted-not-merged? task]}]
  (let [min-delegated (long (or (:min-delegated-calls task) 0))
        settled-calls (settled-call-count final-trace)]
    (or (when (= :timeout (get-in agent-res [:wait :error]))
          (str "Timed out after " (long timeout-ms) " ms"))
        (when (and test-res (not (:ok? test-res)))
          (let [first-fail (first (remove :seon.test/pass? (:results test-res)))
                first-conflict (first (:conflicts test-res))]
            (str "test-failed: "
                 (or (:seon.test/var first-fail)
                     (when first-conflict (str (:reason first-conflict) " " (or (:message first-conflict) (:entity first-conflict))))
                     (:error test-res) "red"))))
        (when (and merge-res (not (:ok? merge-res)))
          (str "merge-failed: " (or (:error merge-res) (:message merge-res) "blocked")))
        (when halted-not-merged?
          (or (:last-error final-trace)
              (str "not-merged (status " (:status ag-snap) ", last " (:last-snippet final-trace) ")")))
        (when (and (pos? min-delegated) (< settled-calls min-delegated))
          (str "delegated-calls-short: " settled-calls "/" min-delegated " settled"))
        (when (and (:chat-agent task) (str/blank? (str (or (:last-reply final-trace) ""))))
          "chat-agent produced empty reply")
        (when (not (:ok? health-res))
          (str "health-err: " (or (:error health-res) (pr-str (take 2 (filter #(= :error (:severity %)) (:problems health-res)))))))
        (:last-error final-trace))))

(defn- ^:seon.flow/edge eval-probe-edn [inst code]
  (try
    (let [res (instance-op! inst {:op :eval :agent :repl/bench-probe :branch :branch/bench-probe :code code})
          parsed (when (and (:ok? res) (string? (:value res)))
                   (try (edn/read-string (:value res)) (catch Throwable _ nil)))]
      (if (map? parsed) parsed {}))
    (catch Throwable _ {})))

(defn ^:seon.flow/edge probe-trial-trace
  "Queries the isolated trial instance via `seon.health.metrics/agent-friction-summary` before teardown."
  {:malli/schema [:=> [:cat :map :keyword :keyword [:? [:maybe :map]]] :map]}
  ([inst agent-id branch] (probe-trial-trace inst agent-id branch nil))
  ([inst agent-id branch probe-opts]
   (let [after-tx (long (or (:after-tx probe-opts) 0))
         after-main-tx (long (or (:after-main-tx probe-opts) 0))
         opts-map (cond-> {:branch branch}
                    (pos? after-tx) (assoc :after-tx after-tx)
                    (pos? after-main-tx) (assoc :after-main-tx after-main-tx))]
     (eval-probe-edn inst
       (str "(if-let [f (requiring-resolve (quote seon.health.metrics/agent-friction-summary))] "
            "(f (user/conn) (user/main-db) " (pr-str agent-id) " " (pr-str opts-map) ") {})")))))

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

(def ^:private telemetry-numeric-pairs
  [[:turns :turns] [:cache-hit-tokens :kv-hit-tokens] [:cache-miss-tokens :kv-miss-tokens]
   [:prompt-tokens :prompt-tokens] [:completion-tokens :completion-tokens]
   [:prefix-hash-count :prefix-hashes] [:prefix-busts :prefix-busts] [:ooms :ooms]
   [:eval-errors :eval-errors] [:upstream-errors :upstream-errors]
   [:rogue-fs-count :rogue-fs-count] [:no-match-count :no-match-count]
   [:delegated-calls :delegated-calls] [:settled-delegated-calls :settled-delegated-calls]])

(defn trial-telemetry
  "Projects optional per-trial friction telemetry from `trace` and `category`."
  {:malli/schema [:=> [:cat [:maybe :keyword] [:maybe :map]] :map] :cost/ticks 400}
  [category trace]
  (let [trace-map (if (map? trace) trace {})]
    (reduce (fn [acc [src-key dst-key]]
              (let [val (get trace-map src-key)]
                (if (number? val) (assoc acc dst-key (long val)) acc)))
            (cond-> {}
              category (assoc :category category)
              (boolean? (:turn-overrun? trace-map)) (assoc :turn-overrun? (:turn-overrun? trace-map)))
            telemetry-numeric-pairs)))

(defn trial-result
  "Assembles one trial result map from its op responses and friction trace."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 400}
  [{:keys [id category port boot-ms elapsed-ms outcome ag-snap eval-res agent-res test-res merge-res
           health-res final-trace failure]}]
  (let [timed-out? (boolean (= :timeout (get-in agent-res [:wait :error])))
        status (cond (:ok? outcome) (if (:merged? outcome) :merged :passed) timed-out? :timeout :else :failed)]
    (merge {:id id :status status :timed-out? timed-out? :port port :boot-ms boot-ms :elapsed-ms elapsed-ms
            :ok? (boolean (:ok? outcome)) :merged? (boolean (:merged? outcome)) :agent-status (:status ag-snap)
            :evals (long (or (:evals final-trace) (:evals ag-snap) 0)) :eval eval-res :agent agent-res
            :test test-res :merge merge-res :health-ok? (boolean (:ok? health-res))
            :last-snippet (:last-snippet final-trace) :last-error failure}
           (trial-telemetry category final-trace))))

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
            baseline (when chat-aid (probe-agent-baseline inst-handle agent-id branch))
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
            final-trace (when (or agent-res eval-res) (probe-trial-trace inst-handle agent-id branch baseline))
            timed-out? (= :timeout (get-in agent-res [:wait :error]))
            already-merged? (= :merged (:status ag-snap))
            test-res (when (and (not timed-out?) (:run-tests? task) (or (not already-merged?) (:retest-main? task)))
                       (instance-op! inst-handle {:op :test :branch (if already-merged? :main branch)}))
            merge-res (when (and (not timed-out?) (:merge? task))
                        (if already-merged?
                          {:ok? true :already-merged? true :branch branch}
                          (instance-op! inst-handle {:op :merge :branch branch :message (str "Bench trial " (name id))})))
            health-res (instance-op! inst-handle {:op :health})
            outcome (trial-outcome {:task task :ag-snap ag-snap :eval-res eval-res :agent-res agent-res
                                    :test-res test-res :merge-res merge-res :final-trace final-trace})
            failure (trial-failure {:timeout-ms timeout-ms :agent-res agent-res :test-res test-res :merge-res merge-res
                                    :ag-snap ag-snap :task task :final-trace (when-not (:ok? outcome) final-trace)
                                    :health-res health-res :halted-not-merged? (:halted-not-merged? outcome)})]
        (trial-result {:id id :category (:category task) :port (:port inst-handle) :boot-ms (:boot-ms inst-handle)
                       :elapsed-ms (elapsed-ms!) :outcome outcome :ag-snap ag-snap :eval-res eval-res
                       :agent-res agent-res :test-res test-res :merge-res merge-res
                       :health-res health-res :final-trace final-trace :failure failure}))
      (catch Throwable ex
        {:id id :status :error :timed-out? false :ok? false :elapsed-ms (elapsed-ms!)
         :error (or (.getMessage ex) (str ex)) :data (ex-data ex)})
      (finally
        (when-let [inst-handle @inst-ref] (stop-instance! inst-handle {:keep-dir? keep-dirs?}))))))

(defn ^:seon.flow/edge run-parallel-trial!
  "Runs `tasks` concurrently across N isolated `target/seon` instances and cleans up every instance."
  {:malli/schema [:=> [:cat [:? :map]] :map]}
  ([] (run-parallel-trial! {:tasks []}))
  ([{:keys [tasks proxy? proxy-opts model-base-url boot-timeout-ms task-timeout-ms keep-dirs?]
     :as opts :or {proxy? false boot-timeout-ms 25000 task-timeout-ms 60000 keep-dirs? false}}]
   (let [started-ns (System/nanoTime)
         proxy-inst (when proxy? (proxy/start! (or proxy-opts {})))
         run-opts (assoc opts :eff-base-url (or (when proxy-inst (:base-url proxy-inst)) model-base-url)
                         :proxy-instance proxy-inst :boot-timeout-ms boot-timeout-ms
                         :task-timeout-ms task-timeout-ms :keep-dirs? keep-dirs?)]
     (try
       (let [results (->> (map-indexed vector tasks)
                          (mapv (fn [[idx task]] (a/thread (run-one-trial! idx task run-opts))))
                          (mapv a/<!!))]
         {:ok? (every? :ok? results) :count (count results)
          :elapsed-ms (/ (double (- (System/nanoTime) started-ns)) 1e6)
          :results results :proxy-stats (when proxy-inst (proxy/stats proxy-inst))})
       (finally (when proxy-inst (proxy/stop! proxy-inst)))))))

(defn- result->stored-trial [result-row]
  (let [row-status (cond (:ok? result-row) (if (:merged? result-row) :merged :passed)
                         (or (:timed-out? result-row) (= :timeout (:status result-row))) :timeout
                         :else :failed)
        err-text (or (:last-error result-row) (:error result-row))
        telemetry-keys (into [:category :turn-overrun?] (map second telemetry-numeric-pairs))]
    (merge
      (cond-> {:id (:id result-row) :status row-status :port (:port result-row)
               :evals (long (or (:evals result-row) 0)) :elapsed-ms (long (or (:elapsed-ms result-row) 0))
               :boot-ms (long (or (:boot-ms result-row) 0)) :merged? (boolean (:merged? result-row))
               :health-ok? (boolean (:health-ok? result-row)) :alive? false}
        (:last-snippet result-row) (assoc :last-snippet (:last-snippet result-row))
        err-text (assoc :last-error (str err-text)))
      (select-keys result-row telemetry-keys))))

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
                              (:category task) (assoc :category (:category task))))
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