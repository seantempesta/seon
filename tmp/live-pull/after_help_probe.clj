(ns after-help-probe
  "Decompose the generated opening one settled entry at a time.

  Unlike `tmp/live-pull/probe.clj`, this probe seeds the run through the real
  `seon.bootstrap/seed-tx` path and then advances the opening exactly as
  `seon.cluster.loop/generate-turn` does: derive one entry, append its form,
  transact its receipt, derive the next. Each derivation is measured with
  bounded call counters and is executed on its own platform thread under a
  declared deadline, so a non-return is reported as a stack dump rather than
  as an unbounded wait.

  Run from the repository root with:

    clojure -J-Xmx8g -M:dev:test -i tmp/live-pull/after_help_probe.clj \\
      -m after-help-probe tmp/live-pull/after-help-NAME [ENTRIES] [DEADLINE-S]"
  (:require [clojure.java.io :as io]
            [datahike.pull-api :as pull-api]
            [my.plan :as plan]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support])
  (:import [java.lang.management ManagementFactory]
           [java.nio.file Files LinkOption Path]
           [java.util Date]))

(defn- private-var
  [namespace-name symbol-name]
  (or (ns-resolve namespace-name symbol-name)
      (throw (ex-info "The profiling boundary does not exist."
                      {:after-help.probe/namespace namespace-name
                       :after-help.probe/symbol symbol-name}))))

(defn- counted
  [counters label function]
  (fn [& arguments]
    (let [started (System/nanoTime)]
      (try
        (apply function arguments)
        (finally
          (swap! counters
                 (fn [current]
                   (-> current
                       (update-in [label :calls] (fnil inc 0))
                       (update-in [label :nanoseconds]
                                  (fnil + 0) (- (System/nanoTime) started))))))))))

(defn- printable
  [counters]
  (into (sorted-map)
        (map (fn [[label sample]]
               [label {:calls (:calls sample)
                       :milliseconds (/ (double (:nanoseconds sample))
                                        1000000.0)}]))
        counters))

(defn- boundaries
  [counters]
  (into {}
        (map (fn [[resolved label]]
               [resolved (counted counters label @resolved)]))
        [[#'walk/root-acquisition :root-acquisition]
         [#'walk/root-pull-plan :root-pull-plan]
         [#'walk/root-selector :root-selector]
         [#'walk/entity-lookup :entity-lookup]
         [#'walk/neighborhood :neighborhood]
         [#'walk/ordered-episode :ordered-episode]
         [#'config/effective :config-effective]
         [#'schema/projection-from-database :projection-from-database]
         [#'db/pull :db-pull]
         [#'db/q :db-query]
         [#'pull-api/pull-spec :datahike-pull-spec]
         [#'pull-api/compile-pull-plan :datahike-compile-plan]
         [#'plan/ready-subjects :plan-ready-subjects]
         [#'render/render-call :render-call]
         [#'bootstrap/pull-result :pull-result]
         [(private-var 'seon.bootstrap 'direct-candidates) :direct-candidates]
         [(private-var 'seon.bootstrap 'listing-candidates) :listing-candidates]
         [(private-var 'seon.bootstrap 'intent-acquisition) :intent-acquisition]
         [(private-var 'seon.bootstrap 'admitted-intent) :admitted-intent]
         [(private-var 'seon.bootstrap 'root-candidate) :root-candidate]
         [(private-var 'seon.render.walk 'acquisition-members)
          :membership-index]]))

(defn- stack-lines
  [^Thread thread]
  (into [] (map str) (.getStackTrace thread)))

(defn- bounded
  "Run `operation` on its own thread under a declared deadline.

  Returns the value, or a record naming the deadline and the live stack. The
  probe never waits without a bound, so a non-return is evidence."
  [label deadline-ms operation]
  (let [result (promise)
        thrown (promise)
        thread (Thread.
                (fn []
                  (try
                    (deliver result (operation))
                    (catch Throwable throwable
                      (deliver thrown throwable)
                      (deliver result ::threw))))
                (str "after-help-" (name label)))]
    (.start thread)
    (.join thread (long deadline-ms))
    (cond
      (not (realized? result))
      {:after-help.probe/deadline-exceeded true
       :after-help.probe/deadline-ms deadline-ms
       :after-help.probe/thread-state (str (.getState thread))
       :after-help.probe/stack (stack-lines thread)}

      (realized? thrown)
      {:after-help.probe/threw (str (class @thrown))
       :after-help.probe/message (ex-message @thrown)
       :after-help.probe/data (ex-data @thrown)}

      :else @result)))

(defn- summarize
  [value]
  (cond
    (and (map? value) (:after-help.probe/deadline-exceeded value)) value
    (and (map? value) (:after-help.probe/threw value)) value
    (and (map? value) (:seon.error/kind value))
    {:after-help.probe/error-kind (:seon.error/kind value)
     :after-help.probe/error-message (:seon.error/message value)}
    (and (map? value) (:seon.repl/form value))
    {:after-help.probe/entry-form (pr-str (:seon.repl/form value))
     :after-help.probe/entry-comment (:seon.repl/comment value)}
    (nil? value) {:after-help.probe/entry nil}
    :else {:after-help.probe/value-class (some-> value class str)}))

(defn- measure!
  [counters label deadline-ms operation]
  (reset! counters {})
  (let [started (System/nanoTime)
        value (bounded label deadline-ms operation)
        elapsed (/ (double (- (System/nanoTime) started)) 1000000.0)]
    (println
     (pr-str (merge {:after-help.probe/measurement label
                     :after-help.probe/wall-ms elapsed
                     :after-help.probe/counters (printable @counters)}
                    (summarize value))))
    (flush)
    value))

(defn- directory-bytes
  [root]
  (with-open [paths (Files/walk (.toPath (io/file root))
                                (make-array java.nio.file.FileVisitOption 0))]
    (reduce (fn [total ^Path path]
              (if (Files/isRegularFile path (make-array LinkOption 0))
                (+ total (Files/size path))
                total))
            0
            (iterator-seq (.iterator paths)))))

(defn- conditions
  [root database]
  {:after-help.probe/root (.getCanonicalPath (io/file root))
   :after-help.probe/store-bytes (directory-bytes root)
   :after-help.probe/eavt-datom-count (count (:eavt database))
   :after-help.probe/basis-transaction (db/basis-t database)
   :after-help.probe/java-version (System/getProperty "java.version")
   :after-help.probe/os-arch (System/getProperty "os.arch")
   :after-help.probe/available-processors
   (.getAvailableProcessors (ManagementFactory/getOperatingSystemMXBean))
   :after-help.probe/max-heap-bytes (.maxMemory (Runtime/getRuntime))})

(defn- request-for
  ([connection ctx agent-id] (request-for connection ctx agent-id nil))
  ([connection ctx agent-id profile]
   (cond-> {:seon.db/db @connection
            :seon.db/connection connection
            :seon.sci.eval/ctx ctx
            :seon.cluster.agent/id agent-id
            :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
            :seon.sci.admit/caps (config/result-caps (config/defaults))
            :seon.sci.eval/time-limit-ms 5000
            :seon.config/on-core-error :record
            :seon.render/output :seon.render/form
            :seon.render/distance 3}
     profile (assoc :seon.render/profile profile))))

(defn- admitted-print-node
  [value]
  (:seon.sci.admit/print-node
   (admit/admit-value {:seon.sci.admit/value value
                       :seon.sci.admit/interrupt-fn (fn [])
                       :seon.sci.admit/caps (config/result-caps
                                             (config/defaults))
                       :seon.config/on-core-error :record})))

(defn- settle!
  "Record one receipt for an already-appended generated form."
  [connection agent-id ordinal value]
  (let [id (bootstrap/run-id agent-id)]
    (db/transact!
     connection
     [{:seon.cluster.eval/id (pr-str [id ordinal])
       :seon.cluster.eval/run [:seon.cluster.run/id id]
       :seon.cluster.eval/ordinal ordinal
       :seon.cluster.eval/at (Date.)
       :seon.cluster.eval/result-edn (pr-str (admitted-print-node value))}])))

(defn- append!
  [connection agent-id ordinal entry process]
  (db/transact!
   connection
   (run/append-generated-tx
    {:seon.cluster.run/id (bootstrap/run-id agent-id)
     :seon.cluster.run/process process
     :seon.cluster.run.form/ordinal ordinal
     :seon.cluster.run.form/source (bootstrap/entry-source entry)
     :seon.ns/name (sci.eval/agent-namespace @connection agent-id)})))

(defn- run-probe!
  [root connection entry-count deadline-ms & [carried-profile?]]
  (support/seed-cluster! connection "after-help-probe")
  ;; The shipped `config/default.edn` value; the isolated branch carries no
  ;; applied manifest, and the generated opening refuses without it.
  (db/transact! connection
                [{:seon.config/cluster "after-help-probe"
                  :seon.config.bootstrap/beyond-closure-token-budget 1024}])
  (cluster/ensure-cluster-entity! connection "after-help-probe"
                                  cluster/boot-process-identity)
  (let [agent-id "after-help-probe-agent"
        process cluster/boot-process-identity]
    (cluster/ensure-entity!
     connection process
     {:seon.cluster.agent/id agent-id
      :seon.cluster/name "after-help-probe"
      :seon.ns/name (symbol "my.agents" agent-id)})
    ;; `cluster/ensure-entity!` already commits `bootstrap/seed-tx`, so the
    ;; run, its trigger message, and its ordinal-0 `(help)` form exist here on
    ;; the same path the live cluster uses.
    (let [ctx (sci.eval/cluster-ctx @connection connection)
          counters (atom {})
          ;; `carried-profile?` supplies the request key the render owner
          ;; otherwise re-derives per call. This is the fix candidate under
          ;; test, not a fixture convenience.
          profile (when carried-profile?
                    ((private-var 'seon.render 'agent-render-profile)
                     (config/effective @connection "after-help-probe")))]
      (println (pr-str (assoc (conditions root @connection)
                              :after-help.probe/carried-profile?
                              (boolean carried-profile?))))
      (flush)
      (with-redefs-fn (boundaries counters)
        (fn []
          (loop [index 0]
            (when (< index entry-count)
              (let [rows (long
                          (or (db/q '[:find (count ?form) .
                                      :in $ ?run-id
                                      :where
                                      [?run :seon.cluster.run/id ?run-id]
                                      [?form :seon.cluster.run.form/run ?run]]
                                    @connection (bootstrap/run-id agent-id))
                              0))
                    settled (long
                             (or (db/q '[:find (count ?receipt) .
                                         :in $ ?run-id
                                         :where
                                         [?run :seon.cluster.run/id ?run-id]
                                         [?receipt :seon.cluster.eval/run ?run]]
                                       @connection (bootstrap/run-id agent-id))
                                 0))
                    _ (println (pr-str {:after-help.probe/state index
                                        :after-help.probe/appended-forms rows
                                        :after-help.probe/settled-receipts
                                        settled
                                        :after-help.probe/ready-subject-count
                                        (count (plan/ready-subjects
                                                @connection agent-id))}))
                    request (request-for connection ctx agent-id profile)
                    entry (measure!
                           counters (keyword (str "derive-" index))
                           deadline-ms
                           #(bootstrap/next-entry
                             request (bootstrap/run-id agent-id)))]
                (cond
                  (or (nil? entry)
                      (not (map? entry))
                      (:after-help.probe/deadline-exceeded entry)
                      (:after-help.probe/threw entry)
                      (:seon.error/kind entry))
                  (println (pr-str {:after-help.probe/stopped-at index}))

                  :else
                  (do
                    ;; The seed already appended ordinal 0; later entries are
                    ;; appended exactly as `generate-turn` appends them.
                    (when (pos? index)
                      (append! connection agent-id index entry process))
                    (settle! connection agent-id index
                             (if (zero? index)
                               (bootstrap/situation @connection agent-id)
                               {:after-help.probe/settled index}))
                    (recur (inc index))))))))))))

(defn -main
  "Advance one seeded generated opening under bounded per-entry measurement."
  [& [root entry-count deadline-seconds mode]]
  (let [root-file (.getCanonicalFile (io/file (or root "")))
        entry-count (if entry-count (parse-long entry-count) 4)
        deadline-ms (* 1000 (if deadline-seconds
                              (parse-long deadline-seconds)
                              180))]
    (when-not (.startsWith (.toPath root-file)
                           (.toPath (.getCanonicalFile
                                     (io/file "tmp/live-pull"))))
      (throw (ex-info "The probe root must be beneath tmp/live-pull."
                      {:after-help.probe/root (.getPath root-file)})))
    (when (.exists root-file)
      (throw (ex-info "The probe root already exists."
                      {:after-help.probe/root (.getPath root-file)})))
    (support/with-published-file-database
      root-file :after-help-probe
      (fn [connection]
        (run-probe! root-file connection entry-count deadline-ms
                    (= "carried-profile" mode)))))
  (shutdown-agents)
  (System/exit 0))
