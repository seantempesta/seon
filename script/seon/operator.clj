(ns seon.operator
  "Source-independent CLI client: argv data, one request, exact process identity."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.dev.dependency-digest :as dependency-digest]
            [seon.error.refusal :as refusal])
  (:import [java.net Socket ServerSocket InetSocketAddress SocketTimeoutException]
           [java.io PushbackReader]
           [java.util.concurrent CompletableFuture TimeUnit]
           [java.util.function Supplier Function]))

(defn repository-root []
  (-> (io/resource "seon/operator.clj") io/file .getCanonicalFile
      .getParentFile .getParentFile .getParentFile .getPath))

(def shipped-default-decisions
  (delay (edn/read-string (slurp (io/file (repository-root) "config/default.edn")))))

(defn operator-silence-backstop-ms [manifest]
  (let [value (get manifest :seon.config.operator/event-silence-backstop-ms
                   (:seon.config.operator/event-silence-backstop-ms @shipped-default-decisions))]
    (when-not (pos-int? value) (throw (ex-info "Missing operator silence bound." manifest)))
    value))

(defn operator-boot-bound-ms [manifest]
  (let [value (get manifest :seon.config.operator/boot-bound-ms
                   (:seon.config.operator/boot-bound-ms @shipped-default-decisions))]
    (when-not (pos-int? value) (throw (ex-info "Missing operator boot bound." manifest)))
    value))

(defn operator-export-bound-ms
  {:malli/schema [:=> [:cat [:maybe :seon.config/manifest]] [:int {:min 1}]]}
  [manifest]
  (let [value (get manifest :seon.config.operator/export-bound-ms
                   (:seon.config.operator/export-bound-ms @shipped-default-decisions))]
    (when-not (pos-int? value) (throw (ex-info "Missing operator export bound." manifest)))
    value))

(defn operation-bound-ms
  {:malli/schema [:=> [:cat [:map
                            [:seon.operator/command {:optional true} :keyword]
                            [:seon.config/manifest {:optional true} :seon.config/manifest]]]
                  [:int {:min 1}]]}
  [options]
  (let [manifest (:seon.config/manifest options)]
    (case (:seon.operator/command options)
      :export (operator-export-bound-ms manifest)
      (:init :reset) (operator-boot-bound-ms manifest)
      (operator-silence-backstop-ms manifest))))

(defn diagnostic
  "The client's operation error. A caught Throwable is handed whole to the one
  error constructor (`seon.error.refusal/diagnostic`), never reduced to its message."
  {:malli/schema [:function
                  [:=> [:cat :string :map :seon.operator/disposition] :seon.operator/operation-error]
                  [:=> [:cat :string :map :seon.operator/disposition :seon.error/throwable]
                   :seon.operator/operation-error]]}
  ([message evidence cause]
   {:seon.error/at (java.util.Date.)
    :seon.error/layer :seon.operator/operation
    :seon.error/operation 'seon.operator/request!
    :seon.error/message message
    :seon.operator/disposition cause
    :seon.error/member :seon.operator/request
    :seon.error/expected :completed-operation
    :seon.error/offending evidence})
  ([message evidence cause throwable]
   ;; The outermost link's ex-data is usually the evidence itself; it is
   ;; carried once, as `:seon.error/offending`, never again inside the chain.
   (update (refusal/diagnostic
            (assoc (diagnostic message evidence cause) :seon.error/throwable throwable))
           :seon.error/chain
           (fn [links]
             (mapv #(if (= evidence (:seon.error/data %)) (dissoc % :seon.error/data) %)
                   links)))))

(defn- fail! [message evidence]
  (throw (ex-info message (diagnostic message evidence :refused))))

(defn canonical-root [root]
  (when-not (and (string? root) (not (str/blank? root)) (.isDirectory (io/file root)))
    (fail! "Operator root must be an existing directory." {:seon.operator/root root}))
  (.getCanonicalPath (io/file root)))

(defn identity-of [handle]
  (let [start (.startInstant (.info handle))]
    (when-not (.isPresent start)
      (fail! "OS process start instant is unavailable." {:seon.boot/pid (.pid handle)}))
    {:seon.boot/pid (.pid handle) :seon.boot/start-instant (java.util.Date/from (.get start))}))

(defn matching-handle
  ([identity] (matching-handle identity false))
  ([identity stale-is-absent?]
  (when-not (and (pos-int? (:seon.boot/pid identity)) (inst? (:seon.boot/start-instant identity)))
    (fail! "Exact process identity is required." identity))
  (let [optional (java.lang.ProcessHandle/of (:seon.boot/pid identity))]
    (when (and (.isPresent optional) (.isAlive (.get optional)))
      (let [handle (.get optional) current (identity-of handle)]
        (if (= current (select-keys identity [:seon.boot/pid :seon.boot/start-instant]))
          handle
          (when-not stale-is-absent?
            (fail! "Process identity changed; refusing to signal its replacement." identity))))))))

(defn advertisements [root]
  (let [directory (io/file root "data/clusters")]
    (into []
          (keep (fn [child]
                  (let [path (io/file child "prepl.edn")]
                    (when (.isFile path)
                      (let [value (edn/read-string (slurp path))]
                        (when (matching-handle value true) value))))))
          (or (.listFiles directory) []))))

(defn advertisement [root name]
  (let [values (advertisements root)]
    (if name (first (filter #(= name (:seon.boot/cluster-name %)) values))
        (first values))))

(defn selected-processes [root]
  ;; Exact JVM argument equality, never a command substring or a claim file.
  (let [argument (str "-Dseon.operator.root=" (canonical-root root))]
    (with-open [stream (java.lang.ProcessHandle/allProcesses)]
      (into (set (map #(select-keys % [:seon.boot/pid :seon.boot/start-instant])
                      (advertisements root)))
            (keep (fn [handle]
                    (let [args (.arguments (.info handle))]
                      (when (and (.isPresent args) (some #{argument} (.get args)))
                        (identity-of handle)))))
            (iterator-seq (.iterator stream))))))

(defn prepl-value!
  "Read one form's arbitrary EDN terminal value. The optional total bound limits
  the hook's wait even while output arrives; expiry leaves execution unknown."
  {:malli/schema
   [:function
    [:=> [:cat :map :string] :seon.schema/value]
    [:=> [:cat :map :string [:int {:min 1}]] :seon.schema/value]
    [:=> [:cat :map :string [:int {:min 1}] [:maybe [:=> [:cat :string] :nil]]]
     :seon.schema/value]
    [:=> [:cat :map :string [:int {:min 1}] [:maybe [:=> [:cat :string] :nil]]
          [:maybe [:int {:min 1}]]]
     :seon.schema/value]]}
  ([advertisement form] (prepl-value! advertisement form (operator-silence-backstop-ms {})))
  ([advertisement form timeout-ms] (prepl-value! advertisement form timeout-ms nil))
  ([advertisement form timeout-ms observe!]
   (prepl-value! advertisement form timeout-ms observe! nil))
  ([advertisement form timeout-ms observe! total-bound-ms]
   (let [deadline (when total-bound-ms (+ (System/nanoTime) (* 1000000 total-bound-ms)))]
     (with-open [socket (Socket.)]
       (.connect socket (InetSocketAddress. (:seon.boot/prepl-host advertisement)
                                           (int (:seon.boot/prepl-port advertisement))) (int timeout-ms))
       (.setSoTimeout socket (int timeout-ms))
       (with-open [writer (io/writer socket) reader (PushbackReader. (io/reader socket))]
         (.write writer (str form "\n")) (.flush writer)
         (loop []
           (when deadline
             (let [remaining (long (Math/ceil (/ (- deadline (System/nanoTime)) 1000000.0)))]
               (when-not (pos? remaining)
                 (fail! "PREPL terminal result did not arrive within its declared bound; outcome unknown."
                        (assoc advertisement :seon.operator/timeout-ms total-bound-ms)))
               (.setSoTimeout socket (int (min timeout-ms remaining)))))
           (let [event (try
                         (edn/read {:eof ::eof} reader)
                         (catch SocketTimeoutException _
                           (fail! "PREPL emitted no next output or terminal result within its declared bound; outcome unknown."
                                  (assoc advertisement
                                         :seon.operator/event (if deadline :prepl-terminal-result :prepl-output-or-result)
                                         :seon.operator/timeout-ms (or total-bound-ms timeout-ms)))))]
             (when (and observe! (= :out (:tag event))) (observe! (:val event)))
             (when (= ::eof event) (fail! "PREPL closed without a terminal result; outcome unknown." advertisement))
             (if (= :ret (:tag event))
               (if (:exception event)
                 (fail! "PREPL evaluation failed." event)
                 (try (edn/read-string (:val event))
                      (catch Exception cause
                        (throw (ex-info "Malformed PREPL result."
                                        (diagnostic "Malformed PREPL result." event :refused cause)
                                        cause)))))
               (recur)))))))))

(defn- operator-reply!
  [value]
  (when-not (map? value)
    (fail! "Operator reply must be a readable EDN map."
           {:seon.operator/reply value}))
  value)

(defn live-root-value!
  ([root form] (live-root-value! root form {}))
  ([root form options]
   (if-let [endpoint (advertisement root nil)]
     {:seon.operator/live-process? true
      :seon.operator/value
      (prepl-value! endpoint form (operation-bound-ms options)
                    (:seon.operator/observe-output! options))}
     (let [identities (selected-processes root)]
       (if (seq identities)
         (fail! "An exact-root JVM is alive but its endpoint is unavailable."
                {:seon.operator/root root :seon.operator/processes (vec identities)})
         {:seon.operator/live-process? false})))))

(defn- request-form [request]
  (pr-str `(do
             (require '~'seon.cluster.boot)
             (when-let [mark# (ns-resolve '~'seon.cluster '~'project-next-prepl-value!)]
               (mark# {:seon.dev.mcp/read-only? true :seon.dev.mcp/project? false}))
             (~'seon.cluster.boot/request! '~request))))

(defn connected! [request]
  (let [root (:seon.operator/managed-root request)
        endpoint (advertisement root nil)]
    (when-not endpoint
      (let [identities (selected-processes root)]
        (fail! (if (seq identities)
                 "An exact-root JVM is alive but its endpoint is unavailable."
                 "No live exact-root JVM; start the selected root first.")
               (assoc request :seon.operator/processes (vec identities)))))
    (let [identity (select-keys endpoint [:seon.boot/pid :seon.boot/start-instant])
          handle (matching-handle identity)
          result (operator-reply!
                  (prepl-value! endpoint
                                (request-form (merge request identity))
                                (operation-bound-ms request)
                                (fn [text] (print text) (flush))))]
      (when (:seon.operator/process-exit? result)
        (.get (.onExit handle) (operation-bound-ms request) TimeUnit/MILLISECONDS))
      result)))

(defn terminate! [identity bound]
  (when-let [handle (matching-handle identity)]
    (.destroy handle)
    (try (.get (.onExit handle) bound TimeUnit/MILLISECONDS)
         (catch java.util.concurrent.TimeoutException _
           (when-let [same (matching-handle identity)]
             (.destroyForcibly same)
             (.get (.onExit same) bound TimeUnit/MILLISECONDS)))))
  identity)

(defn down! [request identities]
  ;; Bind graceful requests to captured identities. A fresh endpoint may belong
  ;; to a replacement and must never become a newly selected signal target.
  ;; A graceful request that fails still ends in exact-identity termination;
  ;; its failure is reported with the result, never discarded.
  (let [graceful
        (when-not (:seon.operator/force? request)
          (into []
                (keep (fn [endpoint]
                        (let [identity (select-keys endpoint [:seon.boot/pid :seon.boot/start-instant])]
                          (when (contains? (set identities) identity)
                            (try (let [reply (prepl-value! endpoint
                                                           (request-form (merge request identity {:seon.operator/command :down})))]
                                   (when (:seon.error/message reply) reply))
                                 (catch Exception cause
                                   (diagnostic (ex-message cause) (or (ex-data cause) identity)
                                               :client-failed cause)))))))
                (advertisements (:seon.operator/managed-root request))))]
    (doseq [identity identities] (terminate! identity (operator-silence-backstop-ms {})))
    (cond-> {:seon.operator/stopped-processes (vec identities)
             :seon.operator/process-exit? (boolean (seq identities))}
      (seq graceful) (assoc :seon.operator/graceful-failures graceful))))

(defn force-stop! [request]
  (let [endpoint (advertisement (:seon.operator/managed-root request)
                                (:seon.boot/cluster-name request))
        _ (when-not endpoint (fail! "Force-stop requires a live sibling observation." request))
        identity (select-keys endpoint [:seon.boot/pid :seon.boot/start-instant])
        handle (or (matching-handle identity) (fail! "Selected process already exited." identity))
        result (try
                 (prepl-value! endpoint (request-form (merge request identity)))
                 (catch Exception _ ::disconnected))]
    (if (= ::disconnected result)
      (do (.get (.onExit handle) (operator-silence-backstop-ms {}) TimeUnit/MILLISECONDS)
          {:seon.boot/cluster-name (:seon.boot/cluster-name endpoint)
           :seon.operator/stopped? true})
      result)))

(defn- child-environment [root]
  (let [file (io/file root ".env")]
    (merge
     (when (.isFile file)
       (into {} (keep (fn [line]
                        (let [line (str/trim line)
                              line (if (str/starts-with? line "export ") (subs line 7) line)
                              at (.indexOf line "=")]
                          (when (and (pos? at) (not (str/starts-with? line "#")))
                            (let [value (str/trim (subs line (inc at)))
                                  value (if (and (< 1 (count value))
                                                 (#{\' \"} (first value)) (= (first value) (last value)))
                                          (subs value 1 (dec (count value))) value)]
                              [(str/trim (subs line 0 at)) value])))))
             (str/split-lines (slurp file))))
     (into {} (System/getenv)))))

(defn- head-capture-form
  "The child's read of every branch head of the store at `store-dir`, before
  its boot writes anything: `{branch commit-id}` from the roster
  (`seon.cluster.registry/roster`, `branch-commit-id`), or the full cause. On
  success the store stays held (`seon.cluster/acquire-root-store!` counts
  holders), so the boot's own acquisition reuses it instead of opening it a
  second time; the launch form releases this holder once boot answers."
  [store-dir]
  `(try
     (require '~'seon.cluster '~'seon.cluster.registry)
     (let [began# (System/nanoTime)
           store# ((resolve '~'seon.cluster/acquire-root-store!) ~store-dir)]
       (try
         {:seon.operator/heads
          (into {} (map (fn [branch#]
                          [branch# ((resolve '~'seon.cluster.registry/branch-commit-id)
                                    {:seon.store/store store# :seon.store/branch branch#})]))
                ((resolve '~'seon.cluster.registry/roster) store#))
          :seon.operator/store-read-ms (quot (- (System/nanoTime) began#) 1000000)}
         (catch Throwable cause#
           ((resolve '~'seon.cluster/release-root-store!) ~store-dir)
           (throw cause#))))
     (catch Throwable cause#
       {:seon.error/message (ex-message cause#) :seon.error/cause (Throwable->map cause#)})))

(defn- launch-form [request port]
  ;; Only Clojure core loads before opening and reporting the diagnostic REPL.
  ;; The second callback value is terminal boot evidence, never a file poll;
  ;; a move reads the branch heads first, as the value between them.
  (pr-str
   `(do
      (require '~'clojure.core.server '~'clojure.java.io)
      (let [listener# (~'clojure.core.server/start-server
                       {:name ~(str "seon.cluster/" (:seon.boot/cluster-name request))
                        :accept '~'seon.operator.prepl/io-prepl :address "127.0.0.1" :port 0
                        :args [:cluster-name ~(:seon.boot/cluster-name request)]})
            start# (.startInstant (.info (java.lang.ProcessHandle/current)))
            identity# {:seon.boot/pid (.pid (java.lang.ProcessHandle/current))
                       :seon.boot/start-instant (java.util.Date/from (.get start#))}
            coordinates# (merge identity# {:seon.boot/cluster-name ~(:seon.boot/cluster-name request)
                                           :seon.boot/prepl-host "127.0.0.1"
                                           :seon.boot/prepl-port (.getLocalPort listener#)})]
        (with-open [callback# (java.net.Socket. "127.0.0.1" ~port)
                    writer# (~'clojure.java.io/writer callback#)]
          (.write writer# (str (pr-str coordinates#) "\n")) (.flush writer#)
          (let [heads# ~(some-> (:seon.operator/capture-heads request) head-capture-form)
                _# (when heads# (.write writer# (str (pr-str heads#) "\n")) (.flush writer#))
                result# (try
                          (require '~'seon.cluster.boot)
                          ((resolve '~'seon.cluster.boot/request!)
                           (assoc '~(dissoc request :seon.operator/capture-heads)
                                  :seon.boot/prepl-server listener#))
                          (catch Throwable cause#
                            {:seon.error/message (ex-message cause#)
                             :seon.error/cause (Throwable->map cause#)}))]
            ;; The capture's holder of the store ends once boot holds its own.
            (when (:seon.operator/heads heads#)
              ((resolve '~'seon.cluster/release-root-store!) ~(:seon.operator/capture-heads request)))
            (.write writer# (str (pr-str result#) "\n")) (.flush writer#)
            (when (and (:seon.error/message result#) (.isClosed listener#))
              (System/exit 1))))
        @(promise)))))

(declare command!)

;;; The dependency class cache on the start classpath (owner 2026-09-23: "link
;;; the caches"). `dev_cache.clj` builds immutable, content-keyed directories
;;; under the MAIN checkout's `target/dev-dependency-classes/<digest>`; this
;;; reads that cache for every launched source (the checkout itself, a nuke
;;; archive, a scratch snapshot) and never builds one: a miss starts from
;;; source and says so, with the command that fills the cache.

(def ^:private dependency-cache-root "target/dev-dependency-classes")
(def ^:private dependency-cache-selection "target/dev-dependency-cache-current.edn")
(def ^:private dependency-cache-manifest "META-INF/seon-dev-cache.edn")
(def ^:private dependency-cache-version 4)
(def ^:private runtime-probe-bound-ms
  "Measured 2026-09-23: `java -XshowSettings:properties -version` 28 ms."
  10000)

(defn- child-java
  "The `java` the `clojure` launcher will run under `environment`."
  {:malli/schema [:=> [:cat [:map-of :string :string]] :string]}
  [environment]
  (or (get environment "JAVA_CMD")
      (some-> (fs/which "java") str)
      (some-> (get environment "JAVA_HOME") (io/file "bin" "java") str)
      (fail! "No java executable for the child JVM." {:seon.operator/environment-keys
                                                      (vec (sort (keys environment)))})))

(defn- child-runtime-properties
  "The child JVM's own values of the runtime properties its compiled classes key on."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:map-of :string :string]]}
  [environment]
  (let [java (child-java environment)
        text (command! [java "-XshowSettings:properties" "-version"] "." runtime-probe-bound-ms)
        wanted (set dependency-digest/runtime-properties)
        properties (into {}
                         (keep (fn [line]
                                 (let [line (str/trim line)
                                       at (str/index-of line " = ")]
                                   (when at
                                     (let [key (subs line 0 at)]
                                       (when (wanted key) [key (subs line (+ at 3))]))))))
                         (str/split-lines text))]
    (when-not (= wanted (set (keys properties)))
      (fail! "The child JVM did not state its runtime identity."
             {:seon.operator/java java :seon.operator/expected (vec (sort wanted))
              :seon.operator/stated properties}))
    properties))

(defn- read-edn-file
  "The EDN value of `file`, or nothing when it does not exist."
  {:malli/schema [:=> [:cat [:fn #(instance? java.io.File %)]] [:maybe :map]]}
  [^java.io.File file]
  (when (.isFile file) (edn/read-string (slurp file))))

(defn- cache-candidates
  "Immutable cache directories under `repository`, the selected one first."
  {:malli/schema [:=> [:cat :string] [:vector [:fn #(instance? java.io.File %)]]]}
  [repository]
  (let [root (io/file repository dependency-cache-root)
        selected (some-> (read-edn-file (io/file repository dependency-cache-selection))
                         :seon.dev-cache/path io/file)]
    (into [] (distinct)
          (concat (when selected [selected])
                  (sort-by #(.getName ^java.io.File %)
                           (filter #(.isDirectory ^java.io.File %)
                                   (or (.listFiles root) [])))))))

(defn- loader-class
  {:malli/schema [:=> [:cat [:fn #(instance? java.io.File %)] :symbol]
                  [:fn #(instance? java.io.File %)]]}
  [directory namespace-name]
  (io/file directory (str (str/replace (munge (str namespace-name)) "." "/") "__init.class")))

(defn dependency-classes
  "The dependency class directory `source`'s start classpath reuses, or its named miss.

  A directory is reused when its manifest names this cache version, its own
  digest, the configuration digest of `source` (its `deps.edn`, its pins and
  the child JVM's runtime identity) and every loader class it lists exists."
  {:malli/schema [:=> [:cat :string :string [:map-of :string :string]] :map]}
  [repository source environment]
  (let [started (System/nanoTime)
        properties (child-runtime-properties environment)
        expected (try (dependency-digest/configuration-digest source properties)
                      (catch clojure.lang.ExceptionInfo refusal
                        (if (= 'seon.dev.dependency-digest/dependency-pins
                               (:seon.error/operation (ex-data refusal)))
                          ::pins-unavailable
                          (throw refusal))))
        hit (when (string? expected)
              (some (fn [directory]
                      (let [manifest (read-edn-file (io/file directory dependency-cache-manifest))]
                        (when (and (= dependency-cache-version (:seon.dev-cache/version manifest))
                                   (= (.getName ^java.io.File directory) (:seon.dev-cache/cache-digest manifest))
                                   (= expected (:seon.dev-cache/input-digest manifest))
                                   (every? #(.isFile ^java.io.File (loader-class directory %))
                                           (:seon.dev-cache/namespaces manifest)))
                          {:seon.dev-cache/status :hit
                           :seon.dev-cache/path (.getCanonicalPath ^java.io.File directory)
                           :seon.dev-cache/digest (:seon.dev-cache/cache-digest manifest)
                           :seon.dev-cache/namespaces (count (:seon.dev-cache/namespaces manifest))})))
                    (cache-candidates repository)))]
    (assoc (or hit
               {:seon.dev-cache/status :miss
                :seon.dev-cache/reason (if (string? expected) :no-matching-cache :pins-unavailable)
                :seon.dev-cache/input-digest expected
                ;; Filled from `source` itself: an archive's `target` links the
                ;; checkout's (`share-caches!`), so the fill lands in the one cache.
                :seon.dev-cache/fill (str "cd " source " && clojure -T:dev-cache ensure-cache")})
           :seon.dev-cache/selection-ms (quot (- (System/nanoTime) started) 1000000))))

(defn- start-classpath
  "The `-Scp` argument prepending a hit's classes to `source`'s own classpath, or nothing."
  {:malli/schema [:=> [:cat :string :map] [:maybe :string]]}
  [source classes]
  (when (= :hit (:seon.dev-cache/status classes))
    (str (:seon.dev-cache/path classes) java.io.File/pathSeparator
         (str/trim (command! ["clojure" "-Spath" "-M:dev:test"] source runtime-probe-bound-ms)))))

(defn- record-cache-reference!
  "Record the child JVM's use of a cache directory, so `dev-cache/reap` keeps it
  while that exact process lives. Taken under the cache's reference lock."
  {:malli/schema [:=> [:cat :string :map :map] :nil]}
  [repository classes coordinates]
  (when (= :hit (:seon.dev-cache/status classes))
    (let [pid (:seon.boot/pid coordinates)
          target (io/file repository "target/dev-dependency-cache-processes" (str pid ".edn"))
          candidate (io/file (.getParentFile target) (str pid ".edn." (random-uuid)))]
      (.mkdirs (.getParentFile target))
      (with-open [lock-file (java.io.RandomAccessFile.
                             (io/file repository "target/dev-dependency-cache-references.lock") "rw")
                  channel (.getChannel lock-file)]
        ;; Closing the channel releases the lock (babashka exposes no FileLock methods).
        (.lock channel)
        (spit candidate (str (pr-str {:seon.boot/pid pid
                                      :seon.boot/start-instant (:seon.boot/start-instant coordinates)
                                      :seon.operator.process-record/cache-path
                                      (:seon.dev-cache/path classes)})
                             "\n"))
        (fs/move candidate target {:replace-existing true :atomic-move true}))))
  nil)

(defn- launch-child!
  "Launch one JVM whose program is the checkout at `source` and return its
  terminal boot value with the coordinates it advertised. A nuke passes
  `reuse-classes?` false: it rebuilds reading no prior derived state."
  [request source reuse-classes?]
  (let [root (:seon.operator/managed-root request)
        name (:seon.boot/cluster-name request)
        log (io/file root "data/clusters" name "logs/seon.log")
        bound (operator-silence-backstop-ms (:seon.config/manifest request))]
    (io/make-parents log)
    (with-open [callback (ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))]
      (let [environment (child-environment root)
            classes (if reuse-classes?
                      (dependency-classes (repository-root) source environment)
                      {:seon.dev-cache/status :skipped
                       :seon.dev-cache/reason :nuke-reads-no-derived-state})
            classpath (start-classpath source classes)
            argv (cond-> ["clojure"]
                   classpath (into ["-Scp" classpath])
                   true (into [(str "-J-Dseon.operator.root=" root)
                               (str "-J-Dseon.repository.root=" source)
                               "-M:dev:test" "-e" (launch-form request (.getLocalPort callback))]))
            builder (doto (ProcessBuilder. ^java.util.List argv)
                      (.directory (io/file source))
                      (.redirectErrorStream true)
                      (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log)))
            _ (.putAll (.environment builder) environment)
            child (.start builder)
            exited (.thenApply (.onExit child) (reify Function (apply [_ _] ::exited)))
            accepted (CompletableFuture/supplyAsync (reify Supplier (get [_] (.accept callback))))
            event (.get (CompletableFuture/anyOf (into-array CompletableFuture [accepted exited]))
                        bound TimeUnit/MILLISECONDS)]
        (when (= ::exited event) (fail! "Child exited before opening its REPL." {:seon.operator/log (str log)}))
        (with-open [socket event reader (java.io.BufferedReader. (io/reader socket))]
          (.setSoTimeout socket bound)
          (let [coordinates (edn/read-string (.readLine reader))
                _ (record-cache-reference! (repository-root) classes coordinates)
                _ (binding [*out* *err*]
                    (println "REPL" (pr-str coordinates) "log" (str log))
                    (println "DEPENDENCY-CLASSES" (pr-str classes)))
                ;; Cold indexing has the publication's declared bound, independently of socket silence.
                boot-bound (operator-boot-bound-ms {})
                _ (.setSoTimeout socket boot-bound)
                ;; A move's child states the branch heads before its boot writes.
                capture-began (System/nanoTime)
                heads (when (:seon.operator/capture-heads request)
                        (assoc (if-let [line (.readLine reader)]
                                 (edn/read-string {:default tagged-literal} line)
                                 {:seon.error/message "Child closed its boot channel before stating the branch heads."})
                               ;; Includes requiring seon.cluster, which boot needs next anyway.
                               :seon.operator/capture-ms (quot (- (System/nanoTime) capture-began) 1000000)))
                terminal (CompletableFuture/supplyAsync
                          (reify Supplier (get [_] (if-let [line (.readLine reader)]
                                                    (edn/read-string {:default tagged-literal} line) ::eof))))
                value (.get (CompletableFuture/anyOf (into-array CompletableFuture [terminal exited]))
                            boot-bound TimeUnit/MILLISECONDS)]
            (when-not (map? value)
              (fail! "Child exited or closed its boot channel before readiness."
                     (cond-> {:seon.operator/event value :seon.operator/log (str log)
                              :seon.boot/advertisement coordinates}
                       heads (assoc :seon.operator/captured-heads heads))))
            (when (some #(and (map? %)
                              (= :seon.cluster.store/held-elsewhere (:seon.cluster.store/rule %)))
                        (tree-seq coll? seq value))
              (.get (.onExit child) bound TimeUnit/MILLISECONDS))
            (cond-> {:seon.operator/value (assoc value :seon.dev-cache/dependency-classes classes)
                     :seon.boot/advertisement coordinates}
              heads (assoc :seon.operator/captured-heads heads))))))))

(defn launch! [request]
  (:seon.operator/value (launch-child! request (repository-root) true)))

;;; Nuclear rebuild (plan §7 "Schema change and reset", owner 2026-09-23):
;;; delete the store and rebuild from COMMITTED inputs only, so no in-flight
;;; working-tree hunk can break it. The program is a `git archive` of HEAD,
;;; content-keyed by commit under <root>/data/source/<sha>; each gitlink is the
;;; checked-out submodule when that checkout is clean at its pin, else an
;;; archive of the pin. The replacement JVM is launched with that directory as
;;; its classpath source. A failed attempt's JVM is terminated by exact identity,
;;; so no JVM survives with a deleted store; one retry covers a transient loss
;;; (a racing start winning the replacement gap); a second failure returns both
;;; complete causes and leaves no JVM.

(defn- command!
  "Run one argv to exit within `bound` ms; a non-zero exit or expiry refuses."
  [argv directory bound]
  (let [out (java.io.File/createTempFile "seon-operator" ".out")
        child (.start (doto (ProcessBuilder. ^java.util.List argv)
                        (.directory (io/file directory))
                        (.redirectErrorStream true)
                        (.redirectOutput out)))]
    (try
      (when-not (.waitFor child bound TimeUnit/MILLISECONDS)
        (.destroyForcibly child)
        (fail! "Command did not exit within its bound." {:seon.operator/argv argv :seon.operator/timeout-ms bound}))
      (let [text (slurp out)]
        (when-not (zero? (.exitValue child))
          (fail! "Command failed." {:seon.operator/argv argv :seon.operator/exit (.exitValue child)
                                    :seon.operator/output text}))
        text)
      (finally (.delete out)))))

(defn- gitlinks
  "Every submodule path and pinned commit recorded in `sha`'s tree."
  [repository sha]
  (into []
        (keep (fn [line]
                (let [tab (str/index-of line "\t")]
                  (when (and tab (str/starts-with? line "160000 commit "))
                    {:path (subs line (inc tab)) :pin (subs line 14 tab)}))))
        (str/split-lines (command! ["git" "ls-tree" "-r" sha] repository 30000))))

(defn- extract-archive!
  "Extract `git archive <commit>` of `repository` into `destination`."
  [repository commit destination]
  (let [tar (java.io.File/createTempFile "seon-nuke" ".tar")]
    (try
      (command! ["git" "archive" "--format=tar" "-o" (str tar) commit] repository 120000)
      (.mkdirs (io/file destination))
      (command! ["tar" "-xf" (str tar) "-C" (str destination)] repository 120000)
      (finally (.delete tar)))))

(defn- committed-repository
  "The operator's own checkout (derived from this script's location, never the
  cwd), required to be a Git top level: a nuke builds from committed inputs."
  []
  (let [repository (repository-root)
        top (try (str/trim (command! ["git" "rev-parse" "--show-toplevel"] repository 30000))
                 (catch clojure.lang.ExceptionInfo cause
                   (throw (ex-info "The operator's checkout is not inside a Git repository; a nuke builds from committed inputs and has none."
                                   (diagnostic "The operator's checkout is not inside a Git repository; a nuke builds from committed inputs and has none."
                                               {:seon.operator/repository repository} :refused cause)
                                   cause))))]
    (when-not (= (.getCanonicalPath (io/file top)) (.getCanonicalPath (io/file repository)))
      (fail! "The operator's checkout is not a Git top level (a frozen archive inside another repository?); a nuke builds from committed inputs and this checkout has none."
             {:seon.operator/repository repository :seon.operator/enclosing-repository top}))
    repository))

(defn- leading-fields
  "The first `n` space-separated fields of `line` and the rest of it."
  {:malli/schema [:=> [:cat :string [:int {:min 1}]] [:vector :string]]}
  [line n]
  (loop [fields [] at 0]
    (let [space (str/index-of line " " at)]
      (if (and space (< (count fields) n))
        (recur (conj fields (subs line at space)) (inc space))
        (conj fields (subs line at))))))

(defn- checkout-pins
  "Each gitlink of the repository's index as {path pin}, and the paths whose
  checkout is not that pin with unmodified tracked bytes. One `git status`
  answers for every submodule (porcelain v2 `S<c><m><u>`: `c` names a checkout
  commit other than the index pin, `m` modified tracked content); untracked
  build products are ignored, as a clean checkout carries them."
  {:malli/schema [:=> [:cat :string]
                  [:map [:seon.operator/index-pins [:map-of :string :string]]
                   [:seon.operator/unclean [:set :string]]]]}
  [repository]
  (let [staged (command! ["git" "ls-files" "--stage" "--" "reference-code"] repository 30000)
        status (command! ["git" "status" "--porcelain=v2" "--untracked-files=no"
                          "--ignore-submodules=untracked" "--" "reference-code"]
                         repository 30000)]
    {:seon.operator/index-pins
     (into {} (keep (fn [line]
                      (let [tab (str/index-of line "\t")]
                        (when (and tab (str/starts-with? line "160000 "))
                          [(subs line (inc tab)) (subs line 7 47)]))))
           (str/split-lines staged))
     :seon.operator/unclean
     (into #{} (keep (fn [line]
                       (let [[kind _ submodule _ _ _ _ _ path] (leading-fields line 8)]
                         (when (and (= "1" kind) (str/starts-with? submodule "S")
                                    (or (= \C (nth submodule 1)) (= \M (nth submodule 2))))
                           path))))
           (str/split-lines status))}))

(defn- unprepared-build-products?
  "Whether the dependency at `directory` declares tools.deps preparation
  (`:deps/prep-lib`) whose `:ensure` output is not yet present."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [directory]
  (let [manifest (io/file directory "deps.edn")
        preparation (when (.isFile manifest) (:deps/prep-lib (edn/read-string (slurp manifest))))]
    (boolean (and preparation (not (.exists (io/file directory (:ensure preparation))))))))

(def ^:private pinned-directory
  "Where a root keeps each gitlink pin archived once (its bytes and prepared
  build products), for every commit archive whose checkout is not at that pin."
  "data/source/pins")

(defn- pinned-checkout!
  "The directory holding `path` at `pin`, archived from its checkout once:
  staged, then atomically moved (its existence is completion)."
  {:malli/schema [:=> [:cat :string :string :string :string] :string]}
  [root repository path pin]
  (let [target (io/file root pinned-directory (str/replace path "/" "-") pin)]
    (when-not (.isDirectory target)
      (let [staging (io/file (.getParentFile target)
                             (str pin ".staging-" (.pid (java.lang.ProcessHandle/current))))]
        (fs/delete-tree staging)
        (extract-archive! (str (io/file repository path)) pin staging)
        (fs/move staging target {:atomic-move true})))
    (.getCanonicalPath target)))

(defn- pins-text
  "`git ls-files --stage -- reference-code` output for `gitlinks`, the bytes
  `seon.dev.dependency-digest/dependency-pins` keys a snapshot on."
  {:malli/schema [:=> [:cat [:vector [:map [:path :string] [:pin :string]]]] :string]}
  [gitlink-pins]
  (apply str (map (fn [{:keys [path pin]}] (str "160000 " pin " 0\t" path "\n"))
                  (sort-by :path gitlink-pins))))

(defn committed-source!
  "A commit's program (HEAD by default) as a directory of committed bytes,
  built once per commit. Each gitlink is the checked-out submodule when it is
  at the commit's pin with unmodified tracked bytes, else a link to that pin
  archived once per root (`pinned-checkout!`); tools.deps prepares an
  archived pin only while its declared `:deps/prep-lib` output is absent."
  {:malli/schema [:function
                  [:=> [:cat :string] :map]
                  [:=> [:cat :string :string] :map]]}
  ([root] (committed-source! root "HEAD"))
  ([root revision]
  (let [repository (committed-repository)
        sha (str/trim (command! ["git" "rev-parse" "--verify" (str revision "^{commit}")] repository 30000))
        parent (io/file root "data/source")
        target (io/file parent sha)]
    (if (.isDirectory target)
      {:seon.source/git-sha sha :seon.operator/source-root (.getCanonicalPath target)
       :seon.operator/source-built? false}
      (let [staging (io/file parent (str sha ".staging-" (.pid (java.lang.ProcessHandle/current))))
            _ (fs/delete-tree staging)
            ;; The submodule census runs beside the tree's extraction.
            census (future (checkout-pins repository))
            _ (extract-archive! repository sha staging)
            {:seon.operator/keys [index-pins unclean]} @census
            submodules
            (mapv (fn [{:keys [path pin]}]
                    (let [checkout (io/file repository path)
                          clean? (and (.isDirectory checkout) (= pin (get index-pins path))
                                      (not (contains? unclean path)))
                          placed (io/file staging path)]
                      (fs/delete-tree placed)
                      (if clean?
                        (do (io/make-parents placed)
                            (fs/create-sym-link placed (.getCanonicalFile checkout))
                            {:path path :pin pin :placed :linked})
                        (let [pinned (pinned-checkout! root repository path pin)]
                          (io/make-parents placed)
                          (fs/create-sym-link placed pinned)
                          {:path path :pin pin :placed :archived
                           :index-pin (get index-pins path)
                           :prepared? (unprepared-build-products? pinned)}))))
                  (gitlinks repository sha))
            ;; An archived pin that declares preparation lacks its build
            ;; products until tools.deps prepares them (e.g. http-kit's and
            ;; datahike's compiled Java), once per pin.
            _ (when (some :prepared? submodules)
                (command! ["clojure" "-X:deps" "prep" ":aliases" "[:dev :test]"] staging 300000))
            ;; An archive is no Git work tree: without its commit's pins, a
            ;; `git ls-files` inside it answers the ENCLOSING checkout's index
            ;; (`seon.test.cache/gitlink-digests`, `dependency-digest/dependency-pins`),
            ;; so a nuke published the checkout's gitlink pins, not the commit's.
            _ (spit (io/file staging dependency-digest/dependency-pins-file)
                    (pins-text (gitlinks repository sha)))
            ;; The same holds for the input inventory: `git ls-files --cached`
            ;; inside the archive lists the enclosing checkout's index, so a
            ;; file deleted there but still staged leaked into the commit's
            ;; inputs. The archive carries its commit's exact paths, the
            ;; inventory `seon.test.cache/input-paths` reads when present.
            _ (spit (io/file staging "test-input-paths.txt")
                    (command! ["git" "ls-tree" "-r" "-z" "--name-only" sha] repository 30000))]
        (fs/move staging target {:atomic-move true})
        {:seon.source/git-sha sha :seon.operator/source-root (.getCanonicalPath target)
         :seon.operator/source-built? true :seon.operator/submodules submodules})))))

(defn- elapsed-ms [began] (quot (- (System/nanoTime) began) 1000000))

(defn- nuke-attempt!
  "One destroy-and-rebuild from `source`; any failure leaves no JVM of this root."
  [request source]
  (let [root (:seon.operator/managed-root request)
        began (System/nanoTime)
        stopped (down! request (selected-processes root))
        down-ms (elapsed-ms began)
        launched (System/nanoTime)
        outcome (try
                  (launch-child! (assoc request :seon.operator/command :start
                                        :seon.boot/cluster-name "default" :seon.store/destroy? true)
                                 source false)
                  (catch Exception cause
                    {:seon.operator/value
                     (diagnostic (ex-message cause) (or (ex-data cause) {}) :client-failed cause)}))
        value (:seon.operator/value outcome)
        missing (get-in value [:seon.boot/readiness :seon.boot/missing-layers])
        ready? (and (not (:seon.error/message value)) (vector? missing) (empty? missing))
        phases {:seon.operator/down-ms down-ms :seon.operator/launch-ms (elapsed-ms launched)
                :seon.boot/ready-ms (get-in value [:seon.boot/readiness :seon.boot/ready-ms])}]
    (if ready?
      (assoc value :seon.operator/phases phases :seon.operator/stopped-processes (:seon.operator/stopped-processes stopped))
      ;; Partial boot keeps its REPL for an ordinary start; a nuke never leaves
      ;; a JVM beside a deleted store, so every process of this root ends.
      (let [terminated (mapv #(terminate! % (operator-silence-backstop-ms {})) (selected-processes root))]
        {:seon.operator/failed-attempt
         {:seon.operator/value value :seon.operator/phases phases
          :seon.boot/advertisement (:seon.boot/advertisement outcome)
          :seon.operator/terminated terminated}}))))

(def ^:private nuke-derived-paths
  "Every root-relative derived path the code writes, deleted by a nuke so the
  rebuild reads no prior derived state (owner 2026-09-23: \"nuke nukes
  everything including all caches\"). The store itself is deleted by the
  replacement JVM under its flock (`seon.cluster.store/open-store!`
  `:seon.store/destroy?`), which retains the lock file.
  - data/source: this operator's committed archives (`committed-source!`).
  - target/dev-dependency-*: the development class cache (`dev_cache.clj:13-19`).
  - target/test-published-bases, target/test-classpaths: published test bases
    and classpaths of the retired gate launcher (no current producer).
  - .clj-kondo/.cache: the analyzer and lint cache (`seon.fn.analyzer`
    `cache-directory`, `seon.fn` analysis cache-root, `bin/seon-hook:236`,
    `script/seon/dev/clj_kondo.clj:36`).
  - .cpcache: tools.deps' computed classpaths."
  ["data/source"
   "target/dev-dependency-classes" "target/dev-dependency-classes.next"
   "target/dev-dependency-cache-result.edn" "target/dev-dependency-cache-current.edn"
   "target/dev-dependency-cache-processes" "target/dev-dependency-cache.lock"
   "target/dev-dependency-cache-references.lock"
   "target/test-published-bases" "target/test-classpaths"
   ".clj-kondo/.cache" ".cpcache"])

(defn- linked-component
  "The first component of `relative` under `root` that is a symbolic link, or nothing."
  [root relative]
  (loop [path (fs/path root) [part & more] (fs/components relative)]
    (when part
      (let [next-path (fs/path path part)]
        (if (fs/sym-link? next-path)
          (str (fs/relativize (fs/path root) next-path))
          (recur next-path more))))))

(defn- wipe-derived-state!
  "Delete every derived path owned by `root`, never following a link, and each
  cluster's advertisement file. A path reached through a linked component is a
  cache SHARED with another root: the link and its target are both kept
  (owner 2026-09-23: nuking a root wipes that root's derived state; nuking the
  repository's own root wipes the shared caches, which are its own)."
  [root]
  (let [deleted (volatile! []) unlinked (volatile! #{})]
    (doseq [relative nuke-derived-paths
            :let [file (io/file root relative)
                  linked (linked-component root relative)]]
      (cond
        linked (vswap! unlinked conj linked)
        (fs/exists? file {:nofollow-links true})
        (do (fs/delete-tree file) (vswap! deleted conj relative))))
    (doseq [directory (or (.listFiles (io/file root "data/clusters")) [])
            :let [file (io/file directory "prepl.edn")]
            :when (.isFile file)]
      (fs/delete file)
      (vswap! deleted conj (str "data/clusters/" (.getName directory) "/prepl.edn")))
    {:seon.operator/root root
     :seon.operator/repository-root? (= (.getCanonicalPath (io/file root))
                                        (.getCanonicalPath (io/file (repository-root))))
     :seon.operator/deleted @deleted
     :seon.operator/kept-shared (vec (sort @unlinked))}))

(def ^:private nuke-fallback-boots
  "Distinct older programs a nuke boots after HEAD fails twice (plan §7, B)."
  4)

(defn- same-program?
  "Whether two commits' trees agree outside documentation."
  [repository a b]
  (let [child (.start (doto (ProcessBuilder. ^java.util.List
                                             ["git" "diff" "--quiet" a b "--" "." ":(exclude)docs"])
                        (.directory (io/file repository))
                        (.redirectErrorStream true)))]
    (when-not (.waitFor child 30000 TimeUnit/MILLISECONDS)
      (.destroyForcibly child)
      (fail! "git diff did not exit within its bound." {:seon.operator/commits [a b]}))
    (case (.exitValue child)
      0 true
      1 false
      (fail! "git diff failed." {:seon.operator/commits [a b] :seon.operator/exit (.exitValue child)
                                 :seon.operator/output (slurp (.getInputStream child))}))))

(defn- program-candidates
  "Newest first-parent commits from `revision`, one per distinct program."
  [revision]
  (let [repository (committed-repository)
        shas (str/split-lines
              (command! ["git" "rev-list" "--first-parent" "-n" "64"
                         (str revision "^{commit}")] repository 30000))]
    (reduce (fn [kept sha]
              (cond
                (<= (inc nuke-fallback-boots) (count kept)) (reduced kept)
                (and (seq kept) (same-program? repository (peek kept) sha)) kept
                :else (conj kept sha)))
            [] shas)))

(defn nuke!
  "Delete the store and every derived cache, then rebuild `default` from
  committed inputs. Never leaves a JVM with a deleted store and never stops
  at stale state: HEAD twice, then the newest older distinct programs, each
  terminated when it does not reach readiness. HEAD's failure is reported first."
  [request]
  (let [began (System/nanoTime)
        root (:seon.operator/managed-root request)
        ;; Committed inputs are resolved before anything is stopped or deleted.
        ;; A drill may name another committed revision; the CLI always builds HEAD.
        candidates (program-candidates (get request :seon.source/revision "HEAD"))
        began-down (System/nanoTime)
        stopped (down! request (selected-processes root))
        down-ms (elapsed-ms began-down)
        wiped (wipe-derived-state! root)
        wipe-ms (- (elapsed-ms began-down) down-ms)
        plan (into [(first candidates) (first candidates)] (rest candidates))
        attempts (loop [[sha & more] plan attempts []]
                   (let [started (System/nanoTime)
                         source (committed-source! root sha)
                         source-ms (elapsed-ms started)
                         result (assoc (nuke-attempt! request (:seon.operator/source-root source))
                                       :seon.operator/source source
                                       :seon.operator/source-ms source-ms)
                         attempts (conj attempts result)]
                     (if (and (:seon.operator/failed-attempt result) (seq more))
                       (recur more attempts)
                       attempts)))
        final (peek attempts)
        failures (into [] (keep (fn [attempt]
                                  (when-let [failed (:seon.operator/failed-attempt attempt)]
                                    (assoc failed :seon.operator/source (:seon.operator/source attempt)))))
                       attempts)
        report {:seon.operator/stopped-processes (:seon.operator/stopped-processes stopped)
                :seon.operator/wiped wiped
                :seon.operator/phases {:seon.operator/down-ms down-ms :seon.operator/wipe-ms wipe-ms}
                :seon.operator/candidates candidates
                :seon.operator/total-ms (elapsed-ms began)}
        head (first candidates)
        ready-sha (get-in final [:seon.operator/source :seon.source/git-sha])]
    (cond
      (:seon.operator/failed-attempt final)
      (assoc (diagnostic (str "Nuclear rebuild failed on HEAD " head " and every older distinct program tried; no JVM of this root remains.")
                         (merge report {:seon.operator/failed-attempts failures}) :client-failed)
             :seon.operator/process-exit? true)
      (= head ready-sha)
      (cond-> (merge (dissoc final :seon.operator/phases) report
                     {:seon.operator/attempt-phases (:seon.operator/phases final)})
        (seq failures) (assoc :seon.operator/failed-attempts failures))
      :else
      (merge (dissoc final :seon.operator/phases) report
             {:seon.operator/head-failure (first failures)
              :seon.operator/fallback-from head
              :seon.operator/failed-attempts failures
              :seon.operator/attempt-phases (:seon.operator/phases final)}))))

;;; Move to HEAD (owner 2026-09-23: "isn't a nuke more disruptive?" / "Then
;;; load head. Don't keep a broken system running."). The nuke's committed
;;; archive is the program; the store and every cache stay. The replacement
;;; JVM resumes: boot compares the files with the published program and
;;; publishes only the paths that differ (`seon.cluster.boot`
;;; `changed-source-paths`), then `default` adopts that publication. A JVM
;;; whose program is an archive does not see the working tree, so hook
;;; publication is off until the root starts from the checkout again.

(def ^:private analysis-cache
  "The analyzer cache every archive of a root links (`seon.fn.analyzer`
  `cache-directory` is `<source>/.clj-kondo/.cache`). clj-kondo keys a
  namespace's entry by nothing but its name, so the entry is valid only for the
  bytes last linted: it follows the ROOT's published program, never the
  checkout's working tree, whose edits the hook lints into the checkout's own
  cache (observed 2026-09-23: an archive linked to the checkout's cache refused
  publication on `Unresolved var: cache/worker-checkout!`, entries of a
  working-tree `seon.test.cache` the archive does not have)."
  "data/source/analysis-cache")

(defn- root-analysis-cache!
  "This root's analyzer cache directory, seeded once from the cache of the
  program the root ran before (`previous-source`, the replaced JVM's source
  directory), whose entries describe that program's published bytes; a root
  with no prior program starts it empty and says so."
  {:malli/schema [:=> [:cat :string [:maybe :string]] :map]}
  [root previous-source]
  (let [cache (io/file root analysis-cache)
        seed (some-> previous-source (io/file ".clj-kondo" ".cache"))]
    (cond
      (.isDirectory cache)
      {:seon.operator/analysis-cache (.getCanonicalPath cache) :seon.operator/seeded :kept}
      (and seed (.isDirectory seed)
           (not= (.getCanonicalPath seed) (.getCanonicalPath cache)))
      (let [staging (io/file (.getParentFile cache)
                             (str "analysis-cache.staging-" (.pid (java.lang.ProcessHandle/current))))]
        (fs/delete-tree staging)
        (fs/copy-tree (.getCanonicalPath seed) staging)
        (fs/move staging cache {:atomic-move true})
        {:seon.operator/analysis-cache (.getCanonicalPath cache)
         :seon.operator/seeded (.getCanonicalPath seed)})
      :else
      (do (.mkdirs cache)
          {:seon.operator/analysis-cache (.getCanonicalPath cache) :seon.operator/seeded :empty}))))

(defn- share-caches!
  "Link each of `source`'s derived cache paths to its shared directory
  (`links`: relative path -> directory) and record the commit's pins
  (`dependency-pins.txt`) so the class cache key is computable in a directory
  that is not a Git work tree. A real directory in a link's place (a JVM that
  ran from this archive before) is replaced; its bytes were that JVM's
  private cache."
  {:malli/schema [:=> [:cat [:map-of :string :string] :string
                       [:vector [:map [:path :string] [:pin :string]]]] :map]}
  [links source gitlink-pins]
  (let [pins (io/file source dependency-digest/dependency-pins-file)
        linked
        (mapv (fn [[relative directory]]
                (let [link (fs/path source relative)
                      shared (fs/path directory)]
                  (fs/create-dirs shared)
                  (cond
                    (and (fs/sym-link? link)
                         (= (str (fs/real-path shared)) (str (fs/read-link link))))
                    {:path relative :placed :kept}
                    :else
                    (let [replaced? (fs/exists? link {:nofollow-links true})]
                      (when replaced? (fs/delete-tree link))
                      (fs/create-dirs (fs/parent link))
                      (fs/create-sym-link link (fs/real-path shared))
                      {:path relative :placed (if replaced? :replaced :linked)}))))
              (sort links))]
    (when-not (.isFile pins)
      (spit pins (pins-text gitlink-pins)))
    {:seon.operator/linked linked
     :seon.operator/pins (str pins)}))

(defn- named-source
  "The program directory a process's arguments name (`-Dseon.repository.root=`), or nothing."
  {:malli/schema [:=> [:cat [:sequential :string]] [:maybe :string]]}
  [arguments]
  (some (fn [arg]
          (some #(when (str/starts-with? arg %)
                   (.getCanonicalPath (io/file (subs arg (count %)))))
                ["-Dseon.repository.root=" "-J-Dseon.repository.root="]))
        arguments))

(defn- program-source
  "Where a live JVM's program comes from and whether hook publication reaches it."
  {:malli/schema [:=> [:cat :map] :map]}
  [identity]
  (let [source (when-let [handle (matching-handle identity true)]
                 (let [args (.arguments (.info handle))]
                   (when (.isPresent args) (named-source (vec (.get args))))))
        checkout (.getCanonicalPath (io/file (repository-root)))
        archive? (and source (not= checkout (.getCanonicalPath (io/file source))))]
    (cond-> {:seon.operator/source-root source
             :seon.operator/hook-publication (if archive? :off :on)}
      archive?
      (assoc :seon.operator/hook-publication-reason
             (str "This JVM's program is the committed archive " source
                  "; working-tree edits are not published into it. Start the root from the"
                  " checkout (`bin/seon down && bin/seon start`) to publish edits again.")))))

(defn- held-sources
  "Canonical program directories every live process on this machine names."
  {:malli/schema [:=> [:cat] [:set :string]]}
  []
  (with-open [stream (java.lang.ProcessHandle/allProcesses)]
    (into #{}
          (keep (fn [handle]
                  (let [args (.arguments (.info handle))]
                    (when (.isPresent args) (named-source (vec (.get args)))))))
          (iterator-seq (.iterator stream)))))

(defn- prune-archives!
  "Delete this root's committed archives other than `kept` that no live
  process names as its program, then every archived pin no remaining archive
  links; deletion never follows a link."
  {:malli/schema [:=> [:cat :string :string] :map]}
  [root kept]
  (let [held (held-sources)
        pins (io/file root pinned-directory)
        archives (filter #(and (.isDirectory ^java.io.File %)
                               (not (contains? #{(.getCanonicalPath pins)
                                                 (.getCanonicalPath (io/file root analysis-cache))}
                                               (.getCanonicalPath ^java.io.File %)))
                               (not (str/includes? (.getName ^java.io.File %) ".staging-")))
                         (or (.listFiles (io/file root "data/source")) []))
        {pruned true retained false}
        (group-by #(and (not= kept (.getCanonicalPath ^java.io.File %))
                        (not (contains? held (.getCanonicalPath ^java.io.File %))))
                  archives)
        _ (doseq [archive pruned] (fs/delete-tree archive))
        linked (into #{}
                     (comp (mapcat #(or (.listFiles (io/file ^java.io.File % "reference-code")) []))
                           (filter #(fs/sym-link? (fs/path ^java.io.File %)))
                           (map #(str (fs/read-link (fs/path ^java.io.File %)))))
                     retained)
        stale-pins (into []
                         (comp (mapcat #(or (.listFiles ^java.io.File %) []))
                               (filter #(.isDirectory ^java.io.File %))
                               (remove #(str/includes? (.getName ^java.io.File %) ".staging-"))
                               (remove #(contains? linked (.getCanonicalPath ^java.io.File %))))
                         (or (.listFiles pins) []))]
    (doseq [pin stale-pins] (fs/delete-tree pin))
    {:seon.operator/pruned (mapv #(.getName ^java.io.File %) pruned)
     :seon.operator/retained (mapv #(.getName ^java.io.File %) retained)
     :seon.operator/pruned-pins (mapv #(str (.getName (.getParentFile ^java.io.File %)) "/"
                                             (.getName ^java.io.File %))
                                       stale-pins)}))

(defn- head-restore-form
  "The form a failed replacement evaluates to put every branch back on the
  exact commit it named before its boot wrote. Datahike has no head reset that
  keeps a commit id (`force-branch!`, `reference-code/datahike/src/datahike/
  versioning.cljc:323`, writes a NEW commit, which a later adoption reads as a
  change), so every connection this JVM holds to a moved branch is released
  (`connector.cljc:468`, all references), unlinked (`versioning.cljc:279`) and branched again from
  the captured commit (`:212`, which stores that commit's own record as the
  head). A branch the attempt created is released and unlinked."
  [store-dir heads]
  `(let [store# (seon.cluster/acquire-root-store! ~store-dir)]
     (try
       (let [heads# '~heads
             connection# (:seon.store/connection-object store#)
             head# (fn [branch#] (seon.cluster.registry/branch-commit-id
                                  {:seon.store/store store# :seon.store/branch branch#}))
             store-id# (get-in @connection# [:config :store :id])
             unlink!# (fn [branch#]
                        ;; Every connection this JVM holds to the branch
                        ;; (`datahike.connections/*connections*`, `connections.cljc:3`).
                        (doseq [{held# :conn} (vals @datahike.connections/*connections*)
                                :let [config# (:config @held#)]
                                :when (and (= branch# (:branch config#))
                                           (= store-id# (get-in config# [:store :id])))]
                          (datahike.api/release held# true))
                        (datahike.api/delete-branch! connection# branch#))
             now# (into {} (map (juxt identity head#)) (seon.cluster.registry/roster store#))
             restored#
             (into []
                   (keep (fn [[branch# commit#]]
                           (let [current# (get now# branch#)]
                             (when (not= current# commit#)
                               (when current# (unlink!# branch#))
                               (datahike.api/branch! connection# commit# branch#)
                               {:seon.store/branch branch# :seon.operator/from current#
                                :seon.operator/to (head# branch#)}))))
                   heads#)
             unlinked# (into [] (remove (set (keys heads#))) (keys now#))]
         (run! unlink!# unlinked#)
         {:seon.operator/restored restored# :seon.operator/unlinked unlinked#
          :seon.operator/heads-after (into {} (map (juxt identity head#))
                                           (seon.cluster.registry/roster store#))})
       (finally (seon.cluster/release-root-store! ~store-dir)))))

(defn- restore-heads!
  "Put the root's branches back on the heads a failed replacement captured,
  through that replacement's own REPL (it holds the store's flock); a
  replacement that exited or captured nothing answers the typed unknown."
  {:malli/schema [:=> [:cat :string [:maybe :map] [:maybe :map]] :map]}
  [store-dir captured advertisement]
  (cond
    (not (:seon.operator/heads captured))
    {:seon.operator/rollback :unknown
     :seon.operator/reason "The replacement stated no branch heads before its boot."
     :seon.operator/captured-heads captured}
    (not (and advertisement (matching-handle advertisement true)))
    {:seon.operator/rollback :unknown
     :seon.operator/reason "The replacement exited; no JVM holds the store to restore its branch heads."
     :seon.operator/captured-heads captured}
    :else
    (try
      (let [result (prepl-value! advertisement
                                 (pr-str (head-restore-form store-dir (:seon.operator/heads captured)))
                                 (operator-silence-backstop-ms {}))]
        (assoc result
               ;; Restored means every branch names its captured commit again.
               :seon.operator/rollback (if (= (:seon.operator/heads captured)
                                              (:seon.operator/heads-after result))
                                         :restored :failed)
               :seon.operator/captured-heads captured))
      (catch clojure.lang.ExceptionInfo cause
        (assoc (diagnostic "Restoring the branch heads failed; the store may be ahead of older programs."
                           {:seon.operator/captured-heads captured} :client-failed cause)
               :seon.operator/rollback :failed)))))

(defn move-to-head!
  "Replace the root's JVM with one whose program is committed HEAD (a drill
  may name `:seon.source/revision`), keeping the store and every cache.

  The archive is built and its caches linked before anything stops. Then the
  root's JVMs stop within their bound, the replacement resumes on the same
  store (publishing only the files that differ from the published program)
  and `default` adopts that publication. A replacement that does not reach
  readiness, or whose adoption refuses, is terminated by exact identity and
  the full cause returned: a broken program never keeps running."
  {:malli/schema [:=> [:cat :map] :map]}
  [request]
  (let [began (System/nanoTime)
        root (:seon.operator/managed-root request)
        repository (committed-repository)
        source (committed-source! root (get request :seon.source/revision "HEAD"))
        archive-ms (elapsed-ms began)
        source-root (:seon.operator/source-root source)
        ;; The replaced JVM's program directory seeds this root's analyzer cache.
        previous-source (some-> (advertisement root nil) program-source :seon.operator/source-root)
        analysis (root-analysis-cache! root previous-source)
        shared (assoc (share-caches! {"target" (str (io/file repository "target"))
                                      ".clj-kondo/.cache" (:seon.operator/analysis-cache analysis)}
                                     source-root (gitlinks repository (:seon.source/git-sha source)))
                      :seon.operator/analysis analysis)
        share-ms (- (elapsed-ms began) archive-ms)
        ;; tools.deps computes the archive's classpath once, into its own
        ;; `.cpcache`, while the old JVM still serves.
        _ (command! ["clojure" "-Spath" "-M:dev:test"] source-root runtime-probe-bound-ms)
        source-ms (elapsed-ms began)
        began-down (System/nanoTime)
        stopped (down! request (selected-processes root))
        down-ms (elapsed-ms began-down)
        began-launch (System/nanoTime)
        store-dir (.getCanonicalPath (io/file root "data/store"))
        ;; A store the replacement will resume states its branch heads first,
        ;; so a failed attempt can put them back (a failed move must stay
        ;; revertible to every older program).
        capture? (seq (.list (io/file store-dir)))
        outcome (try
                  (launch-child! (cond-> (assoc request :seon.operator/command :start
                                                :seon.boot/cluster-name "default")
                                   capture? (assoc :seon.operator/capture-heads store-dir))
                                 source-root true)
                  (catch clojure.lang.ExceptionInfo cause
                    (let [evidence (:seon.error/offending (ex-data cause))]
                      {:seon.operator/value
                       (diagnostic (ex-message cause) (or (ex-data cause) {}) :client-failed cause)
                       :seon.boot/advertisement (:seon.boot/advertisement evidence)
                       :seon.operator/captured-heads (:seon.operator/captured-heads evidence)})))
        launch-ms (elapsed-ms began-launch)
        value (:seon.operator/value outcome)
        missing (get-in value [:seon.boot/readiness :seon.boot/missing-layers])
        ready? (and (not (:seon.error/message value)) (vector? missing) (empty? missing))
        began-adopt (System/nanoTime)
        adoption (when ready?
                   (try (connected! {:seon.operator/managed-root root
                                     :seon.operator/command :init
                                     :seon.operator/development-cluster "default"})
                        (catch Exception cause
                          (diagnostic (ex-message cause) (or (ex-data cause) {}) :client-failed cause))))
        adopt-ms (elapsed-ms began-adopt)
        adopted? (and ready? (not (:seon.error/message adoption)))
        pruned (when adopted? (prune-archives! root source-root))
        phases {:seon.operator/source-ms source-ms :seon.operator/archive-ms archive-ms
                :seon.operator/share-ms share-ms
                :seon.operator/classpath-ms (- source-ms archive-ms share-ms)
                :seon.operator/down-ms down-ms
                :seon.operator/launch-ms launch-ms
                :seon.boot/ready-ms (get-in value [:seon.boot/readiness :seon.boot/ready-ms])
                :seon.operator/adopt-ms adopt-ms
                :seon.operator/total-ms (elapsed-ms began)}
        report {:seon.operator/source (assoc source :seon.operator/shared-caches shared)
                :seon.operator/stopped-processes (:seon.operator/stopped-processes stopped)
                :seon.operator/phases phases}]
    (if adopted?
      (merge value report
             {:seon.operator/adoption adoption :seon.operator/archives pruned
              :seon.operator/captured-heads (:seon.operator/captured-heads outcome)}
             (program-source (:seon.boot/advertisement outcome)))
      (let [began-restore (System/nanoTime)
            restored (when capture?
                       (restore-heads! store-dir (:seon.operator/captured-heads outcome)
                                       (:seon.boot/advertisement outcome)))
            report (assoc-in report [:seon.operator/phases :seon.operator/restore-ms]
                             (elapsed-ms began-restore))
            terminated (mapv #(terminate! % (operator-silence-backstop-ms {})) (selected-processes root))
            ;; The replaced program runs again, once: no retry loop.
            began-resume (System/nanoTime)
            resumed (when previous-source
                      (let [back (try (:seon.operator/value
                                       (launch-child! (assoc request :seon.operator/command :start
                                                             :seon.boot/cluster-name "default")
                                                      previous-source true))
                                      (catch clojure.lang.ExceptionInfo cause
                                        (diagnostic (ex-message cause) (or (ex-data cause) {})
                                                    :client-failed cause)))
                            back-missing (get-in back [:seon.boot/readiness :seon.boot/missing-layers])
                            back-ready? (and (not (:seon.error/message back))
                                             (vector? back-missing) (empty? back-missing))]
                        {:seon.operator/source-root previous-source
                         :seon.operator/ready? back-ready?
                         :seon.operator/value back
                         :seon.operator/terminated
                         (if back-ready? []
                             (mapv #(terminate! % (operator-silence-backstop-ms {})) (selected-processes root)))}))
            report (assoc-in report [:seon.operator/phases :seon.operator/resume-previous-ms]
                             (elapsed-ms began-resume))]
        (assoc (diagnostic (str "The JVM from " (:seon.source/git-sha source)
                                (if ready? " refused to adopt its publication" " did not reach readiness")
                                (case (:seon.operator/rollback restored)
                                  :restored "; its branch heads were put back"
                                  nil "; it wrote to no existing store"
                                  "; ITS BRANCH HEADS COULD NOT BE PUT BACK (see :seon.operator/heads)")
                                "; it was terminated"
                                (cond (nil? resumed) ", and no program ran before it, so no JVM of this root runs"
                                      (:seon.operator/ready? resumed) (str "; the previous program " previous-source " runs again")
                                      :else (str "; THE PREVIOUS PROGRAM " previous-source " ALSO FAILED (see :seon.operator/resumed), so no JVM of this root runs"))
                                ". The store and caches are kept.")
                           (merge report {:seon.operator/value value
                                          :seon.operator/heads restored
                                          :seon.operator/adoption adoption
                                          :seon.boot/advertisement (:seon.boot/advertisement outcome)
                                          :seon.operator/terminated terminated
                                          :seon.operator/resumed resumed})
                           :client-failed)
               :seon.operator/process-exit? (not (:seon.operator/ready? resumed)))))))

(defn request! [request]
  (try
    (case (:seon.operator/command request)
      :start (cond
               (:seon.operator/head? request) (move-to-head! request)
               (seq (selected-processes (:seon.operator/managed-root request))) (connected! request)
               :else (launch! request))
      :status (let [result (connected! request)]
                (cond-> result
                  (:seon.boot/pid result) (merge (program-source result))))
      :stop (if (:seon.operator/force? request) (force-stop! request) (connected! request))
      :down (down! request (selected-processes (:seon.operator/managed-root request)))
      ;; reset = the running JVM unlinks the branch and forks a fresh one,
      ;; keeping every cache; nuke = delete the store and every cache.
      :reset (do (when-not (:seon.operator/force? request)
                   (fail! "Reset requires --force." request))
                 (connected! request))
      :nuke (do (when-not (:seon.operator/force? request)
                  (fail! "Nuclear rebuild requires --force." request))
                (nuke! request))
      :logs (let [path (io/file (:seon.operator/managed-root request) "data/clusters"
                                (:seon.boot/cluster-name request) "logs/seon.log")]
              (when-not (.isFile path) (fail! "Requested log is unavailable." request))
              {:seon.boot/log-dir (str (.getParentFile path)) :seon.operator.log/path (str path)})
      (connected! request))
    (catch Exception cause
      (diagnostic (ex-message cause) (or (ex-data cause) request) :client-failed cause))))

(defn- valid-name [value]
  (when-not (and (string? value) (<= 1 (count value) 63)
                 (Character/isLetterOrDigit ^char (first value))
                 (every? #(or (Character/isLetterOrDigit ^char %) (#{\. \_ \-} %)) value))
    (fail! "Cluster name requires letters, digits, dot, underscore or hyphen." {:seon.boot/name value}))
  value)

(defn parse-argv [arguments]
  (let [[root args] (if (= "--seon-root" (first arguments))
                      [(canonical-root (second arguments)) (drop 2 arguments)]
                      [(canonical-root (repository-root)) arguments])
        [command & args] args
        command (case command "config" :config-apply (if (#{"--help" "-h"} command) :help (keyword (or command "help"))))
        args (if (= command :config-apply)
               (if (= "apply" (first args)) (rest args) (fail! "Use config apply." {})) args)
        base {:seon.operator/managed-root root :seon.operator/command command}]
    (loop [args (seq args) request base positionals []]
      (if-let [arg (first args)]
        (case arg
          "--force" (recur (next args) (assoc request :seon.operator/force? true) positionals)
          "--verbose" (recur (next args) (assoc request :seon.operator/verbose? true) positionals)
          "--head" (if (= :start command)
                     (recur (next args) (assoc request :seon.operator/head? true) positionals)
                     (fail! "--head is supported only by start." request))
          "--dev" (recur (nnext args) (assoc request :seon.operator/development-cluster (valid-name (second args))) positionals)
          "--config" (let [_ (when-not (= :start command)
                                        (fail! "--config is supported only by start." request))
                           manifest (edn/read-string (slurp (second args)))]
                         (when-not (map? manifest) (fail! "Manifest must be a map." {}))
                         (recur (nnext args) (assoc request :seon.config/manifest manifest) positionals))
          "--changed" (if (seq (next args))
                         (recur nil (assoc request :seon.source/changed-paths (vec (next args))) positionals)
                         (fail! "--changed requires paths." {}))
          (if (str/starts-with? arg "--") (fail! "Unknown option." {:seon.operator/argument arg})
              (recur (next args) request (conj positionals arg))))
        (do
          (case command
            :help request
            :config-apply
            (let [[name path] (case (count positionals)
                                1 ["default" (first positionals)]
                                2 positionals
                                (fail! "Use config apply [NAME] PATH." request))
                  manifest (edn/read-string (slurp path))]
              (when-not (map? manifest) (fail! "Manifest must be an EDN map." request))
              (assoc request :seon.boot/cluster-name (valid-name name) :seon.config/manifest manifest))
            :export (if (= 1 (count positionals))
                      (assoc request :seon.operator/destination (.getCanonicalPath (io/file (first positionals))))
                      (fail! "Use export PATH." request))
            (:status :down :nuke)
            (do (when (seq positionals) (fail! "Command takes no cluster name." request))
                (when (and (#{:reset :nuke} command) (not (:seon.operator/force? request)))
                  (fail! (str (if (= :reset command) "Reset" "Nuclear rebuild") " requires --force.")
                         request)) request)
            (:start :init :open :stop :logs :reset)
            (do (when (< 1 (count positionals)) (fail! "Command takes at most one cluster name." request))
                (when (and (= :reset command) (not (:seon.operator/force? request)))
                  (fail! "Reset requires --force." request))
                ;; The replaced JVM hosts `default`; --head restarts only it.
                (when (and (:seon.operator/head? request) (seq positionals)
                           (not= "default" (first positionals)))
                  (fail! "start --head replaces the root's JVM and starts default; it takes no other cluster name."
                         request))
                (cond-> request
                  (or (seq positionals) (#{:start :logs} command))
                  (assoc :seon.boot/cluster-name (valid-name (or (first positionals) "default")))))
            (fail! "Unknown command." request)))))))

(defn -main [& args]
  (try
    (let [request (parse-argv args)
          result (if (= :help (:seon.operator/command request))
                   {:seon.operator/help "seon [--root PATH] start [NAME] [--config PATH] | start --head | init [NAME --force | --dev NAME] [--changed PATH...] | status [--verbose] | open [NAME] | stop [NAME] | down [--force] | reset [NAME] --force | nuke --force | logs [NAME] | config apply [NAME] PATH | export PATH"}
                   (request! request))]
      (cond
        (:seon.operator/help result) (println (:seon.operator/help result))
        (:seon.error/message result) (binding [*out* *err*] (prn result))
        (= :logs (:seon.operator/command request)) (print (slurp (:seon.operator.log/path result)))
        (= :open (:seon.operator/command request))
        (let [child (.start (ProcessBuilder. ^java.util.List ["open" (:seon.render.web/url result)]))]
          (when-not (.waitFor child (operator-silence-backstop-ms {}) TimeUnit/MILLISECONDS)
            (.destroyForcibly child) (fail! "OS opener did not exit." result))
          (when-not (zero? (.exitValue child)) (fail! "OS opener failed." result)))
        :else (prn result))
      (when (= :off (:seon.operator/hook-publication result))
        (binding [*out* *err*]
          (println "HOOK-PUBLICATION off:" (:seon.operator/hook-publication-reason result))))
      (shutdown-agents)
      ;; 3: stable and ready, but on an older program because HEAD failed.
      (System/exit (cond (:seon.error/message result) 1
                         (:seon.operator/fallback-from result) 3
                         :else 0)))
    (catch Exception cause
      (binding [*out* *err*] (prn (diagnostic (ex-message cause) (or (ex-data cause) {}) :argv-failed cause)))
      (System/exit 1))))
