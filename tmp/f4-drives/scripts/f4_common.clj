(ns f4-drives.common
  "Shared live-drive helpers for the F4 agents-as-flows proof."
  (:require [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.core :as datahike]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.oversight :as oversight]
            [seon.render :as render]
            [seon.render.agent :as render.agent]
            [seon.render.hiccup :as hiccup]))

(def runtime-root
  "The dedicated F4 process-root store and cluster directory."
  "tmp/f4-drives/runtime")

(def evidence-root
  "Raw, reproducible evidence emitted by the F4 drives."
  "tmp/f4-drives/evidence")

(def local-endpoint
  "The direct local MLX OpenAI-compatible endpoint."
  "http://127.0.0.1:8090/v1/chat/completions")

(def local-model
  "The immutable model snapshot served by MLX."
  (str (System/getProperty "user.home")
       "/.cache/huggingface/hub/"
       "models--mlx-community--Qwen3.6-35B-A3B-4bit-DWQ/"
       "snapshots/73c707af4243243b18193444467872d20cff9399"))

(defn stamp
  "Print one timestamped drive line."
  [& parts]
  (println
   (str "[" (.toString (java.time.OffsetDateTime/now)) "] "
        (apply str parts)))
  (flush))

(defn ensure-directory!
  "Ensure `path` exists and return it."
  [path]
  (.mkdirs (io/file path))
  path)

(defn write-edn!
  "Write one readable EDN evidence value."
  [path value]
  (ensure-directory! (.getParent (io/file path)))
  (spit path (str (with-out-str (pprint/pprint value))))
  path)

(defn write-text!
  "Write one text evidence artifact."
  [path value]
  (ensure-directory! (.getParent (io/file path)))
  (spit path (str value))
  path)

(defn git-head
  "The committed source revision loaded by a drive JVM."
  []
  (let [process
        (.start
         (ProcessBuilder.
          ^java.util.List ["git" "rev-parse" "HEAD"]))
        value (str/trim (slurp (.getInputStream process)))]
    (when-not (zero? (.waitFor process))
      (throw (ex-info "Could not read the F4 source revision." {})))
    value))

(defn source-digest
  "SHA-256 of every regular file under the live `src` classpath."
  []
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        root (.getCanonicalFile (io/file "src"))
        files (->> (file-seq root)
                   (filter #(.isFile ^java.io.File %))
                   (sort-by #(.getPath ^java.io.File %)))]
    (doseq [file files]
      (.update digest
               (.getBytes
                (.substring (.getPath ^java.io.File file)
                            (inc (count (.getPath root))))
                java.nio.charset.StandardCharsets/UTF_8))
      (.update digest (byte 0))
      (.update digest (java.nio.file.Files/readAllBytes (.toPath file))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn delete-tree!
  "Delete only the exact repository-local tree named by `path`."
  [path]
  (let [root (.getCanonicalFile (io/file path))
        allowed (.getCanonicalFile (io/file "tmp/f4-drives"))]
    (when-not (str/starts-with? (.getPath root)
                                (str (.getPath allowed)
                                     java.io.File/separator))
      (throw
       (ex-info "F4 cleanup refused a path outside tmp/f4-drives."
                {:f4-drives/path (.getPath root)})))
    (when (.exists root)
      (doseq [file (reverse (file-seq root))]
        (when-not (.delete ^java.io.File file)
          (throw
           (ex-info "F4 cleanup could not delete a path."
                    {:f4-drives/path (.getPath file)}))))))
  nil)

(defn local-manifest
  "The ordinary persisted dials for one local-model drive."
  ([]
   (local-manifest {}))
  ([overrides]
   (merge
    (config/defaults)
    {:seon.config.ai/endpoint local-endpoint
     :seon.config.ai/model local-model
     ;; The current config schema still requires this descriptor field.
     ;; `local-targets` below selects the landed explicit no-auth arm, so
     ;; the named environment variable is never read and no auth header
     ;; is emitted.
     :seon.config.ai/api-key-variable "F4_UNUSED_NO_AUTH"
     :seon.config.ai/timeout-ms 120000
     :seon.config.web/port 0
     :seon.config/on-core-error :record
     :seon.config.ai.retry/maximum-retries 0
     :seon.config.ai.retry/maximum-total-delay-ms 0}
    overrides)))

(defn local-targets
  "Select the landed explicit no-auth target from effective dials."
  [dials]
  {:seon.ai/primary
   {:seon.ai/endpoint (:seon.config.ai/endpoint dials)
    :seon.ai/model (:seon.config.ai/model dials)
    :seon.config.ai/no-auth true
    :seon.ai/timeout-ms (:seon.config.ai/timeout-ms dials)}})

(defn start-local!
  "Start one scratch cluster with a persisted local descriptor."
  ([cluster-name]
   (start-local! cluster-name {}))
  ([cluster-name manifest-overrides]
   (let [manifest (local-manifest manifest-overrides)]
     (with-redefs [config/defaults (constantly manifest)
                   ai/targets local-targets]
       (cluster/start!
        {:seon.boot/cluster-name cluster-name
         :seon.boot/root runtime-root})))))

(defn apply-local!
  "Apply new local dials to a running scratch cluster."
  [instance overrides]
  (let [cluster-name
        (get-in instance [:seon.boot/config :seon.boot/cluster-name])
        manifest (local-manifest overrides)]
    (with-redefs [config/defaults (constantly manifest)]
      (config/apply!
       {:seon.config/connection
        (:seon.boot/cluster-connection instance)
        :seon.config/manifest manifest
        :seon.boot/cluster-name cluster-name}))))

(defn connection
  "The branch connection of a live cluster instance."
  [instance]
  (:seon.boot/cluster-connection instance))

(defn await-db
  "Wait event-first until `probe` returns a value from a database value."
  [label connection probe timeout-ms]
  (let [answer (promise)
        key (keyword "f4-drives.await" (str (random-uuid)))
        observe! (fn [db]
                   (when-let [value (probe db)]
                     (deliver answer value)))
        listener (fn [report] (observe! (:db-after report)))]
    (datahike/listen! connection key listener)
    (try
      ;; Register first, derive second: no commit can hide in the gap.
      (observe! @connection)
      (let [value (deref answer timeout-ms ::timeout)]
        (when (= ::timeout value)
          (throw
           (ex-info (str "F4 timed out waiting for " label ".")
                    {:f4-drives/label label
                     :f4-drives/timeout-ms timeout-ms})))
        value)
      (finally
        (datahike/unlisten! connection key)))))

(defn await-value
  "Wait for a process-local value; the timeout is a loud backstop."
  [label probe timeout-ms]
  (let [started (System/nanoTime)]
    (loop []
      (if-let [value (probe)]
        value
        (if (>= (/ (- (System/nanoTime) started) 1000000.0)
                timeout-ms)
          (throw
           (ex-info (str "F4 timed out waiting for " label ".")
                    {:f4-drives/label label
                     :f4-drives/timeout-ms timeout-ms}))
          (do
            (Thread/sleep 20)
            (recur)))))))

(defn message
  "Build one outside trigger row."
  [message-id agent-id content at]
  {:seon.cluster.message/id message-id
   :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
   :seon.cluster.message/content content
   :seon.cluster.message/at at})

(defn create-agents!
  "Create ordinary agents through the formal namespace+prompt path."
  [connection agent-ids]
  (d/transact
   connection
   (into
    []
    (mapcat
     (fn [agent-id]
       (cluster.agent/creation-tx
        {:seon.cluster.agent/id agent-id
         :seon.ns/name (symbol (str "my.agents." agent-id))})))
    agent-ids))
  ;; Each seed is derived from the database value after creation and gets
  ;; its own transaction, matching the boot owner and avoiding shared
  ;; temporary ids across independent block sets.
  (doseq [agent-id agent-ids]
    (let [seed (render.agent/seed-tx @connection agent-id)]
      (when (seq seed)
        (d/transact connection seed))))
  agent-ids)

(declare trigger-existing!)

(defn install-agents-and-triggers!
  "Create ordinary agents, then commit all outside triggers simultaneously."
  [connection prompts]
  (create-agents! connection (keys prompts))
  (trigger-existing! connection prompts))

(defn trigger-existing!
  "Commit simultaneous outside triggers for existing agents."
  [connection prompts]
  (let [at (java.util.Date.)
        rows
        (mapv
         (fn [[agent-id content]]
           (message (str "f4-" agent-id "-" (random-uuid))
                    agent-id content at))
         prompts)]
    (d/transact connection rows)
    {:f4-drives/triggered-at at
     :f4-drives/messages
     (into {}
           (map (fn [row]
                  [(second (:seon.cluster.message/to row))
                   (:seon.cluster.message/id row)]))
           rows)}))

(defn run-for-trigger
  "The run row answering `message-id`, or nil."
  [db message-id]
  (d/q
   '[:find (pull ?run
                 [:seon.cluster.run/id
                  :seon.cluster.run/opened-at
                  :seon.cluster.run/closed-at
                  :seon.cluster.run/plan-digest
                  :seon.cluster.run/error
                  {:seon.cluster.run/agent
                   [:seon.cluster.agent/id]}]) .
     :in $ ?message-id
     :where
     [?message :seon.cluster.message/id ?message-id]
     [?tx :seon.db/trigger ?message]
     [?run :seon.cluster.run/id _ ?tx]]
   db message-id))

(defn closed-runs
  "Closed runs for every agent→message id, or nil until complete."
  [db messages]
  (let [runs
        (into {}
              (map (fn [[agent-id message-id]]
                     [agent-id (run-for-trigger db message-id)]))
              messages)]
    (when (and (every? some? (vals runs))
               (every? :seon.cluster.run/closed-at (vals runs)))
      runs)))

(defn run-census
  "A durable census of one run and every direct child/evidence row."
  [db run-id]
  {:seon.cluster.run/id run-id
   :f4-drives/agent
   (d/q '[:find ?agent-id .
          :in $ ?run-id
          :where
          [?run :seon.cluster.run/id ?run-id]
          [?run :seon.cluster.run/agent ?agent]
          [?agent :seon.cluster.agent/id ?agent-id]]
        db run-id)
   :f4-drives/trigger
   (d/q '[:find ?message-id .
          :in $ ?run-id
          :where
          [?run :seon.cluster.run/id ?run-id ?tx]
          [?tx :seon.db/trigger ?message]
          [?message :seon.cluster.message/id ?message-id]]
        db run-id)
   :f4-drives/attempts
   (->> (d/q
         '[:find ?ordinal ?at ?committed ?endpoint ?model
           :in $ ?run-id
           :where
           [?run :seon.cluster.run/id ?run-id]
           [?attempt :seon.ai.attempt/run ?run]
           [?attempt :seon.ai.attempt/ordinal ?ordinal]
           [?attempt :seon.ai.attempt/at ?at ?tx]
           [?tx :db/txInstant ?committed]
           [?attempt :seon.ai/endpoint ?endpoint]
           [?attempt :seon.ai/model ?model]]
         db run-id)
        (sort-by first)
        vec)
   :f4-drives/forms
   (->> (d/q
         '[:find ?ordinal ?source
           :in $ ?run-id
           :where
           [?run :seon.cluster.run/id ?run-id]
           [?form :seon.cluster.run.form/run ?run]
           [?form :seon.cluster.run.form/ordinal ?ordinal]
           [?form :seon.cluster.run.form/source ?source]]
         db run-id)
        (sort-by first)
        vec)
   :f4-drives/receipts
   (->> (d/q
         '[:find (pull ?receipt
                       [:seon.cluster.eval/ordinal
                        :seon.cluster.eval/result-edn
                        :seon.cluster.eval/error
                        :seon.cluster.eval/interrupted-at])
           :in $ ?run-id
           :where
           [?run :seon.cluster.run/id ?run-id]
           [?receipt :seon.cluster.eval/run ?run]]
         db run-id)
        (map first)
        (sort-by :seon.cluster.eval/ordinal)
        vec)})

(defn fleet-unit
  "The live fleet unit for `instance` at its current database value."
  [instance]
  (oversight/unit
   {:seon.db/db @(connection instance)
    :seon.boot/instance instance
    :seon.sci.admit/caps
    (:seon.sci.admit/caps (:seon.cluster.loop/cluster instance))}))

(defn fleet-agents
  "The process-local agent census joined to durable facts."
  [instance]
  (:seon.oversight/agents (:seon.render/value (fleet-unit instance))))

(defn fleet-html
  "The serialized fleet table from the one render route."
  [instance]
  (let [unit (fleet-unit instance)
        rendered
        (render/render {:seon.render/unit unit
                        :seon.render/kind :seon.render/html})]
    (hiccup/->string (:seon.render/output rendered))))

(defn mid-turn-count
  "How many armed agents the fleet ping currently reports mid-turn."
  [instance]
  (count
   (filter #(= :mid-turn (:seon.oversight/state %))
           (fleet-agents instance))))

(defn armed-count
  "How many per-agent graphs are live in `instance`."
  [instance]
  (count
   (:seon.cluster.agent/armed
    @(:seon.cluster.agent/routing instance))))

(defn gc-used-mb
  "Force two collections and return used heap in MiB."
  []
  (System/gc)
  (Thread/sleep 200)
  (System/gc)
  (Thread/sleep 200)
  (let [runtime (Runtime/getRuntime)]
    (/ (- (.totalMemory runtime) (.freeMemory runtime)) 1048576.0)))

(defn thread-dump-counts
  "Count platform and virtual threads with the JDK's own JSON dump."
  [label]
  (ensure-directory! evidence-root)
  (let [pid (.pid (java.lang.ProcessHandle/current))
        path (str evidence-root "/threads-" label "-" (System/nanoTime) ".json")
        process
        (.start
         (ProcessBuilder.
          ^java.util.List
          ["jcmd" (str pid) "Thread.dump_to_file" "-format=json" path]))]
    (when-not (zero? (.waitFor process))
      (throw (ex-info "jcmd thread dump failed."
                      {:f4-drives/path path})))
    (let [text (slurp path)
          total (count (re-seq #"\"tid\":" text))
          virtual (count (re-seq #"\"virtual\": ?true" text))]
      {:f4-drives/total total
       :f4-drives/virtual virtual
       :f4-drives/platform (- total virtual)
       :f4-drives/path path})))

(defn basis
  "The durable branch head facts useful for isolation checks."
  [connection]
  (let [db @connection
        identity (d/committed-value-identity db)]
    {:f4-drives/basis-t (:max-tx db)
     :f4-drives/commit-id (:datahike.value/commit-id identity)
     :f4-drives/connection-id
     (:datahike.value/connection-id identity)
     :f4-drives/generation
     (:datahike.value/generation identity)}))

(defn duration-ms
  "Milliseconds from the trigger row's time to a run's close."
  [triggered-at run]
  (- (inst-ms (:seon.cluster.run/closed-at run))
     (inst-ms triggered-at)))

(defn assert-drive!
  "Throw with evidence when a drive invariant is false."
  [truth message data]
  (when-not truth
    (throw (ex-info message data)))
  true)
