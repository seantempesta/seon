(ns ^{:seon/babashka true} seon.operator
  "Source-independent CLI client: argv data, one request, exact process identity."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.dev.dependency-digest :as dependency-digest]
            [seon.error.refusal :as refusal]
            [seon.fs])
  (:import [java.net Socket ServerSocket InetSocketAddress SocketTimeoutException]
           [java.io PushbackReader]
           [java.util.concurrent CompletableFuture TimeUnit]
           [java.util.function Supplier Function]))

(defn program-root
  "The directory this operator's code loaded from: a committed program
  (`bin/_operator-program`, a `data/source/<sha>` archive) or, run by hand, the
  checkout. Its committed `config/` is this program's shipped configuration."
  {:malli/schema [:=> [:cat] :string]}
  []
  (-> (io/resource "seon/operator.clj") io/file .getCanonicalFile
      .getParentFile .getParentFile .getParentFile .getPath))

(defn repository-root
  "The Git checkout this operator builds committed archives from and whose
  `target` holds the shared class caches: `SEON_CHECKOUT` as `bin/seon` names
  it (the operator runs a committed program, not the checkout) and a JVM it
  launched inherits, else `program-root` (the operator run from the checkout
  itself). An archive program without it has no checkout, and
  `committed-repository` refuses it."
  {:malli/schema [:=> [:cat] :string]}
  []
  (or (System/getenv "SEON_CHECKOUT") (program-root)))

(def shipped-default-decisions
  (delay (edn/read-string (slurp (io/file (program-root) "config/default.edn")))))

(defn operator-silence-backstop-ms
  {:malli/schema [:=> [:cat [:maybe :seon.config/manifest]] [:int {:min 1}]]}
  [manifest]
  (let [value (get manifest :seon.config.operator/event-silence-backstop-ms
                   (:seon.config.operator/event-silence-backstop-ms @shipped-default-decisions))]
    (when-not (pos-int? value) (throw (ex-info "Missing operator silence bound." manifest)))
    value))

(defn operator-boot-bound-ms
  {:malli/schema [:=> [:cat [:maybe :seon.config/manifest]] [:int {:min 1}]]}
  [manifest]
  (let [value (get manifest :seon.config.operator/boot-bound-ms
                   (:seon.config.operator/boot-bound-ms @shipped-default-decisions))]
    (when-not (pos-int? value) (throw (ex-info "Missing operator boot bound." manifest)))
    value))

(defn operator-check-bound-ms
  "The ceiling on one check's test run."
  {:malli/schema [:=> [:cat [:maybe :seon.config/manifest]] [:int {:min 1}]]}
  [manifest]
  (let [value (get manifest :seon.config.operator/check-bound-ms
                   (:seon.config.operator/check-bound-ms @shipped-default-decisions))]
    (when-not (pos-int? value) (throw (ex-info "Missing operator check bound." manifest)))
    value))

(defn operator-heap-max-bytes
  "The configured `-Xmx` of every JVM the operator launches, in bytes."
  {:malli/schema [:=> [:cat [:maybe :seon.config/manifest]] [:int {:min 1}]]}
  [manifest]
  (let [value (get manifest :seon.config.operator/heap-max-bytes
                   (:seon.config.operator/heap-max-bytes @shipped-default-decisions))]
    (when-not (pos-int? value) (throw (ex-info "Missing operator heap bound." manifest)))
    value))

(defn operation-bound-ms
  {:malli/schema [:=> [:cat [:map
                            [:seon.operator/command {:optional true} :keyword]
                            [:seon.config/manifest {:optional true} :seon.config/manifest]]]
                  [:int {:min 1}]]}
  [options]
  (let [manifest (:seon.config/manifest options)]
    (case (:seon.operator/command options)
      (:init :reset :check) (operator-boot-bound-ms manifest)
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

(defn- reap-child!
  "Force `child`'s exit within `bound` ms; a survivor refuses, named by its pid."
  [^Process child bound evidence]
  (.destroyForcibly child)
  (when-not (.waitFor child bound TimeUnit/MILLISECONDS)
    (fail! "Child survived forced termination." (assoc evidence :seon.boot/pid (.pid child)))))

(defn- joined
  "`task`'s outcome once the `resource` it blocks on is closed: its value or
  its failure, awaited within `bound` ms; a task outliving that close refuses."
  [^java.io.Closeable resource ^CompletableFuture task bound]
  (.close resource)
  (try {:seon.operator/task-value (.get task bound TimeUnit/MILLISECONDS)}
       (catch java.util.concurrent.ExecutionException failure {:seon.operator/task-failure (.getCause failure)})
       (catch java.util.concurrent.TimeoutException _
         (fail! "A task outlived its closed resource." {:seon.operator/timeout-ms bound}))))

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

(defn- read-prepl-val
  "Read a trusted prepl's printed `:val` with Clojure's reader, never EDN's:
  `#'v` reads as `(var v)`, `#inst`/`#uuid` stay native, and any other tag
  (`#object`, `#error`) is a tagged literal. `*read-eval*` stays false."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [text]
  (binding [*read-eval* false *default-data-reader-fn* tagged-literal]
    (read-string text)))

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
               ;; An unreadable :val keeps its raw text (the event) beside the
               ;; reader's failure; it never replaces the text with the failure.
               (let [[value cause] (try [(read-prepl-val (:val event))]
                                        (catch Exception cause [nil cause]))]
                 (cond
                   (:exception event)
                   (fail! (or (when (map? value)
                               ;; A Seon error map, else Throwable->map's ex-data then :cause.
                               (some #(when (string? %) %)
                                     [(:seon.error/message value)
                                      (get-in value [:data :seon.error/message])
                                      (:cause value)]))
                             ;; io-prepl answers an exception as its printed
                             ;; `ex-str`: that text is the cause.
                             (when (string? value) value)
                             "PREPL evaluation failed.")
                          (cond-> event
                            (nil? cause) (assoc :val value)
                            cause (assoc :seon.operator/reader-error (ex-message cause))))
                   cause
                   (throw (ex-info "Malformed PREPL result."
                                   (diagnostic "Malformed PREPL result." event :refused cause)
                                   cause))
                   :else value))
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
                 (catch Exception cause cause))]
    ;; A failed reply completes the stop only with the observed exit, and is kept.
    (if (instance? Exception result)
      (do (try (.get (.onExit handle) (operator-silence-backstop-ms {}) TimeUnit/MILLISECONDS)
               (catch java.util.concurrent.TimeoutException never (.addSuppressed ^Exception result never)
                 (throw result)))
          {:seon.boot/cluster-name (:seon.boot/cluster-name endpoint)
           :seon.operator/stopped? true
           :seon.operator/reply-failure (diagnostic (ex-message result) {} :client-failed result)})
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
  its boot writes anything: `{branch commit-id}` from the store's branches
  (`seon.cluster.registry/branches`, `branch-commit-id`), or the full cause. On
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
                ((resolve '~'seon.cluster.registry/branches) store#))
          :seon.operator/store-read-ms (quot (- (System/nanoTime) began#) 1000000)}
         (catch Throwable cause#
           ((resolve '~'seon.cluster/release-root-store!) ~store-dir)
           (throw cause#))))
     (catch Throwable cause#
       {:seon.error/message (ex-message cause#) :seon.error/cause (Throwable->map cause#)})))

(defn launcher-pid
  "The pid whose exit must end the JVM `request` launches, or nil when it outlives
  its launcher. A JVM under an isolated operator root (a test or check drill's
  `bin/seon --root`) is bound to the process that ran this operator; a throwaway
  check JVM is bound to this operator process itself, which lives while it checks."
  {:malli/schema [:=> [:cat :map :boolean] [:maybe :int]]}
  [request throwaway?]
  (let [current (java.lang.ProcessHandle/current)]
    (cond
      throwaway? (.pid current)
      (not= (str (fs/real-path (:seon.operator/managed-root request)))
            (str (fs/real-path (repository-root))))
      (some-> (.parent current) (.orElse nil) .pid))))

(defn exit-with-launcher-form
  "The form that binds the evaluating JVM's lifetime to `launcher`'s pid: the
  launcher's exit ends it, and a launcher already gone ends it at once. Nil
  `launcher` binds nothing."
  {:malli/schema [:=> [:cat [:maybe :int]] :any]}
  [launcher]
  (when launcher
    `(let [parent# (.orElse (java.lang.ProcessHandle/of ~launcher) nil)]
       (if parent#
         (.thenRun (.onExit parent#) (reify Runnable (run [_] (System/exit 0))))
         (System/exit 0)))))

(defn- launch-form [request port launcher]
  ;; Only Clojure core loads before opening and reporting the diagnostic REPL.
  ;; The second callback value is terminal boot evidence, never a file poll;
  ;; a move reads the branch heads first, as the value between them.
  ;; Our program loads from source: a namespace loaded from a compiled class
  ;; is not reloadable (`clojure.lang.RT/load` prefers a newer `__init` class,
  ;; whose static initializer has already run), and adoption reloads.
  (pr-str
   `(do
      (require '~'clojure.core.server '~'clojure.java.io)
      ~(exit-with-launcher-form launcher)
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
;;; reads that cache for every launched source (a committed archive, a nuke
;;; archive, a scratch snapshot). A start fills a miss in `prepared-source!`
;;; before any JVM stops (root ruling 2026-09-26, option A); a nuke reads no
;;; derived state and starts from source.

(def ^:private dependency-cache-root "target/dev-dependency-classes")
(def ^:private dependency-cache-selection "target/dev-dependency-cache-current.edn")
(def ^:private dependency-cache-manifest "META-INF/seon-dev-cache.edn")
(def ^:private dependency-cache-version 4)
(def ^:private runtime-probe-bound-ms
  "Measured 2026-09-23: `java -XshowSettings:properties -version` 28 ms."
  10000)
(def ^:private dependency-fill-bound-ms
  "The bound on one cold `ensure-cache` fill: measured 111 s (the 7a89… cache's
  manifest `:duration-ms` 111270, 2026-09-25), once per dependency set, while
  the old JVM still serves; five minutes leaves room for a loaded machine."
  300000)

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
  {:malli/schema [:=> [:cat [:fn seon.fs/file?]] [:maybe :map]]}
  [^java.io.File file]
  (when (.isFile file) (edn/read-string (slurp file))))

(defn- cache-candidates
  "Immutable cache directories under `repository`, the selected one first."
  {:malli/schema [:=> [:cat :string] [:vector [:fn seon.fs/file?]]]}
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
  {:malli/schema [:=> [:cat [:fn seon.fs/file?] :symbol]
                  [:fn seon.fs/file?]]}
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

(defn- holds-directory
  "Where a process registers the directories it will use before any JVM names
  them: `<name of path>.<pid>` files, each alive as long as its pid."
  [repository]
  (io/file repository "target/operator-holds"))

(defn- hold-name
  [path]
  (apply str (map #(if (Character/isLetterOrDigit (char %)) % \_) path)))

(defn- hold!
  "Register this process as a user of the directory `path`, so no prune deletes
  it before a JVM names it as its program (a check's archive it has not yet
  launched from, `prune-archives!`). Ends with the process: a hold whose pid
  is gone is swept by `held?`. A process holding a path twice holds it once."
  {:malli/schema [:=> [:cat :string :string] :nil]}
  [repository path]
  (let [directory (doto (holds-directory repository) .mkdirs)
        hold (io/file directory (str (hold-name path) "." (.pid (java.lang.ProcessHandle/current))))]
    (when-not (.exists hold) (fs/create-file hold))
    nil))

(defn- held?
  "Whether a live process holds `path` (`hold!`); holds of dead pids are deleted."
  {:malli/schema [:=> [:cat :string :string] :boolean]}
  [repository path]
  (let [prefix (str (hold-name path) ".")]
    (boolean
     (some (fn [^java.io.File file]
             (let [pid (parse-long (subs (.getName file) (count prefix)))]
               (if (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) .isAlive)
                 true
                 (do (fs/delete-if-exists file) false))))
           (filter #(str/starts-with? (.getName ^java.io.File %) prefix)
                   (or (.listFiles (holds-directory repository)) []))))))

(defn- start-classpath
  "The `-Scp` argument prepending a dependency hit's classes to `source`'s own
  classpath, or nothing."
  {:malli/schema [:=> [:cat :string :map] [:maybe :string]]}
  [source classes]
  (when (= :hit (:seon.dev-cache/status classes))
    (str/join java.io.File/pathSeparator
              [(:seon.dev-cache/path classes)
               (str/trim (command! ["clojure" "-Spath" "-M:dev:test"] source
                                   runtime-probe-bound-ms))])))

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

(defn- heap-dumps
  "A crashed JVM's forensics under the managed root's `data/heap`, newest
  first: the heap dump `java_pid<pid>.hprof` the JVM writes inside the
  existing directory `-XX:HeapDumpPath` names (a missing one is taken as the
  file name), the JFR emergency dump `hs_err_pid<pid>.jfr` and the crash log
  `hs_err_pid<pid>.log` written beside it."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [root]
  (into [] (comp (filter #(let [file-name (.getName ^java.io.File %)]
                            (or (str/ends-with? file-name ".hprof")
                                (str/ends-with? file-name ".jfr")
                                (str/starts-with? file-name "hs_err"))))
                 (map #(.getCanonicalPath ^java.io.File %)))
        (sort-by #(- (.lastModified ^java.io.File %)) (.listFiles (io/file root "data/heap")))))

(defn launch-argv
  "The `clojure` argv that starts one JVM over `source`: its heap capped at the
  configured `:seon.config.operator/heap-max-bytes`, its heap dump, JFR
  emergency dump and crash log written to `heap-dump-path`, evaluating `form`."
  {:malli/schema [:=> [:cat :map :string [:maybe :string] :string :string] [:vector :string]]}
  [request source classpath heap-dump-path form]
  (cond-> ["clojure"]
    classpath (into ["-Scp" classpath])
    true (into [(str "-J-Xmx" (operator-heap-max-bytes (:seon.config/manifest request)))
                (str "-J-Dseon.operator.root=" (:seon.operator/managed-root request))
                (str "-J-Dseon.repository.root=" source)
                (str "-J-XX:HeapDumpPath=" heap-dump-path)
                ;; the crash on OOM's JFR emergency dump and error log, beside the heap dump
                (str "-J-XX:FlightRecorderOptions=dumppath=" heap-dump-path)
                (str "-J-XX:ErrorFile=" heap-dump-path "/hs_err_pid%p.log")])
    ;; A live-shape check's clone presents its origin's store id
    ;; (`seon.cluster.store/identity-path`).
    (:seon.operator/store-origin request)
    (conj (str "-J-Dseon.store.origin=" (:seon.operator/store-origin request)))
    true (into ["-M:dev:test" "-e" form])))

(defn boot-request
  "`request` with the agents' filesystem working root (`:seon.config.fs/working-root`)
  bound to `checkout` in its configuration environment, unless its manifest or
  environment already decides that dial. A throwaway JVM's view binds a port the
  operating system chooses (`:seon.config.web/port` 0): its cluster is named like
  the root's, so the derived port is the root's bookmark. It never collects its
  store (`:seon.config.maintenance/collect?` false): the store is deleted at exit."
  {:malli/schema [:=> [:cat :seon.operator/request :string] :seon.operator/request]}
  [request checkout]
  (let [throwaway? (:seon.operator/throwaway? request)
        request (dissoc request :seon.operator/throwaway?)
        decided? (fn [dial] (or (find (:seon.config/manifest request) dial)
                                (find (:seon.config/environment request) dial)))]
    (cond-> request
      (not (decided? :seon.config.fs/working-root))
      (assoc-in [:seon.config/environment :seon.config.fs/working-root] checkout)
      (and throwaway? (not (decided? :seon.config.web/port)))
      (assoc-in [:seon.config/environment :seon.config.web/port] 0)
      ;; A check store is deleted at exit; the template's due compact task
      ;; would otherwise collect it at boot, under the tests.
      (and throwaway? (not (decided? :seon.config.maintenance/collect?)))
      (assoc-in [:seon.config/environment :seon.config.maintenance/collect?] false))))

(defn- launch-child!
  "Launch one JVM whose program is the directory `source` and return its
  terminal boot value with the coordinates it advertised. A nuke passes
  `reuse-classes?` false: it rebuilds reading no prior derived state. Its
  heap dump lands in the managed root's `data/heap`, never in the program
  directory, which the next move prunes (`prune-archives!`)."
  [request source reuse-classes?]
  (let [throwaway? (boolean (:seon.operator/throwaway? request))
        request (boot-request request (repository-root))
        root (:seon.operator/managed-root request)
        name (:seon.boot/cluster-name request)
        log (io/file root "data/clusters" name "logs/seon.log")
        heap (doto (io/file root "data/heap") .mkdirs)
        bound (operator-silence-backstop-ms (:seon.config/manifest request))]
    (io/make-parents log)
    (with-open [callback (ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))]
      (let [environment (child-environment root)
            classes (if reuse-classes?
                      (dependency-classes (repository-root) source environment)
                      {:seon.dev-cache/status :skipped
                       :seon.dev-cache/reason :nuke-reads-no-derived-state})
            argv (launch-argv request source (start-classpath source classes) (.getCanonicalPath heap)
                              (launch-form (dissoc request :seon.operator/store-origin) (.getLocalPort callback)
                                           (launcher-pid request throwaway?)))
            builder (doto (ProcessBuilder. ^java.util.List argv)
                      (.directory (io/file source))
                      (.redirectErrorStream true)
                      (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log)))
            _ (.putAll (.environment builder) environment)
            child (.start builder)
            ;; `clojure` execs java, so the child's pid names its dump.
            dump (io/file heap (str "java_pid" (.pid child) ".hprof"))
            exited (.thenApply (.onExit child) (reify Function (apply [_ _] ::exited)))
            accepted (CompletableFuture/supplyAsync (reify Supplier (get [_] (.accept callback))))
            event (try (.get (CompletableFuture/anyOf (into-array CompletableFuture [accepted exited]))
                             bound TimeUnit/MILLISECONDS)
                       (catch java.util.concurrent.TimeoutException _ ::timeout))]
        ;; The child is not ours to kill here; the losing accept is joined and its socket closed.
        (when-not (instance? Socket event)
          (let [accept (joined callback accepted bound)]
            (some-> ^Socket (:seon.operator/task-value accept) .close)
            (fail! "Child exited or opened no REPL within its bound."
                   (cond-> {:seon.operator/log (str log) :seon.operator/event event :seon.operator/accept accept}
                     (.isFile dump) (assoc :seon.operator/heap-dump (str dump))))))
        (with-open [socket event reader (java.io.BufferedReader. (io/reader socket))]
          (.setSoTimeout socket bound)
          (let [coordinates (edn/read-string (.readLine reader))
                _ (record-cache-reference! (repository-root) classes coordinates)
                ;; Cold indexing has the publication's declared bound, independently of socket silence.
                boot-bound (operator-boot-bound-ms {})
                _ (.setSoTimeout socket boot-bound)
                ;; A move's child states the branch heads before its boot writes.
                capture-began (System/nanoTime)
                heads (when (:seon.operator/capture-heads request)
                        (assoc (if-let [line (.readLine reader)]
                                 (read-prepl-val line)
                                 {:seon.error/message "Child closed its boot channel before stating the branch heads."})
                               ;; Includes requiring seon.cluster, which boot needs next anyway.
                               :seon.operator/capture-ms (quot (- (System/nanoTime) capture-began) 1000000)))
                ;; The child prints its boot result, or a failed boot's
                ;; `Throwable->map`, whose refusal data carries Vars (`#'v`):
                ;; the same trusted reader as a prepl value, never EDN's.
                terminal (CompletableFuture/supplyAsync
                          (reify Supplier (get [_] (if-let [line (.readLine reader)]
                                                    (read-prepl-val line) ::eof))))
                value (try (.get (CompletableFuture/anyOf (into-array CompletableFuture [terminal exited]))
                                 boot-bound TimeUnit/MILLISECONDS)
                           (catch java.util.concurrent.TimeoutException _ ::timeout))]
            (when-not (map? value)
              (fail! "Child exited or closed its boot channel before readiness."
                     (cond-> {:seon.operator/event value :seon.operator/log (str log)
                              :seon.operator/read (joined socket terminal bound)
                              :seon.boot/advertisement coordinates}
                       heads (assoc :seon.operator/captured-heads heads)
                       (.isFile dump) (assoc :seon.operator/heap-dump (str dump)))))
            (when (some #(and (map? %)
                              (= :seon.cluster.store/held-elsewhere (:seon.cluster.store/rule %)))
                        (tree-seq coll? seq value))
              (.get (.onExit child) bound TimeUnit/MILLISECONDS))
            ;; What a launch observed is data in its value, never lines printed
            ;; ahead of the outcome (`-main` prints the outcome's headline first).
            (let [dumps (heap-dumps root)]
              (cond-> {:seon.operator/value (cond-> (assoc value :seon.dev-cache/dependency-classes classes
                                                           :seon.operator/log (str log))
                                              (seq dumps) (assoc :seon.operator/heap-dumps dumps))
                       :seon.boot/advertisement coordinates}
                heads (assoc :seon.operator/captured-heads heads)))))))))

;;; Nuclear rebuild (plan §7 "Schema change and reset", owner 2026-09-23):
;;; delete the store and rebuild from COMMITTED inputs only, so no in-flight
;;; working-tree hunk can break it. The program is a `git archive` of HEAD,
;;; content-keyed by commit under <root>/data/source/<sha>; each gitlink is an
;;; archive of its pin, never the moving checkout. The replacement JVM is launched with that directory as
;;; its classpath source. A failed boot's JVM is terminated by exact identity,
;;; so no JVM survives with a deleted store; the failure returns its complete
;;; cause.

(defn- command!
  "Run one argv to exit within `bound` ms; a non-zero exit or expiry refuses.
  `environment` adds variables to the inherited ones."
  ([argv directory bound] (command! argv directory bound {}))
  ([argv directory bound environment]
  (let [out (java.io.File/createTempFile "seon-operator" ".out")
        builder (doto (ProcessBuilder. ^java.util.List argv)
                  (.directory (io/file directory))
                  (.redirectErrorStream true)
                  (.redirectOutput out))
        _ (doseq [[variable value] environment] (.put (.environment builder) variable value))
        child (.start builder)]
    (try
      (when-not (.waitFor child bound TimeUnit/MILLISECONDS)
        (reap-child! child bound {:seon.operator/argv argv})
        (fail! "Command did not exit within its bound." {:seon.operator/argv argv :seon.operator/timeout-ms bound}))
      (let [text (slurp out)]
        (when-not (zero? (.exitValue child))
          (fail! "Command failed." {:seon.operator/argv argv :seon.operator/exit (.exitValue child)
                                    :seon.operator/output text}))
        text)
      ;; A survivor may still write its output file; it is kept for inspection.
      (finally (when-not (.isAlive child) (.delete out)))))))

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

(defn- seal-archive!
  "Clear the write bits of each regular file named by `paths-text` (an archive's
  NUL-separated committed paths) under `directory`, links and directories
  untouched: a JVM running the archive cannot overwrite its own program (an
  agent's relative `spit` once rewrote `src/` of the running archive, to be
  loaded as platform code by the next reload). A deleter needs no file write
  bit on POSIX (`fs/delete-tree`); directories stay writable for the caches a
  launch places beside the program (`.cpcache`, `target`)."
  {:malli/schema [:=> [:cat :string :string] :nil]}
  [directory paths-text]
  (doseq [relative (str/split paths-text #"\u0000")
          :let [file (io/file directory relative)]
          :when (and (seq relative)
                     (java.nio.file.Files/isRegularFile (.toPath file)
                                                        (into-array java.nio.file.LinkOption
                                                                    [java.nio.file.LinkOption/NOFOLLOW_LINKS])))]
    (.setWritable file false false)))

(defn- committed-repository
  "The operator's Git checkout (`repository-root`: `SEON_CHECKOUT`, else this
  script's location, never the cwd), required to be a Git top level: a nuke
  builds from committed inputs. A JVM running a `data/source/<sha>` archive
  (whose tests start operators) reaches its checkout through the inherited
  `SEON_CHECKOUT`."
  {:malli/schema [:=> [:cat] :string]}
  []
  (let [repository (repository-root)
        top (try (str/trim (command! ["git" "rev-parse" "--show-toplevel"] repository 30000))
                 (catch clojure.lang.ExceptionInfo cause
                   (throw (ex-info "The operator's checkout is not inside a Git repository; a nuke builds from committed inputs and has none."
                                   (diagnostic "The operator's checkout is not inside a Git repository; a nuke builds from committed inputs and has none."
                                               {:seon.operator/repository repository} :refused cause)
                                   cause))))]
    (when-not (= (.getCanonicalPath (io/file top)) (.getCanonicalPath (io/file repository)))
      (fail! "The operator's checkout is not a Git top level (an archive program run without SEON_CHECKOUT naming its checkout?); a nuke builds from committed inputs and this checkout has none."
             {:seon.operator/repository repository :seon.operator/enclosing-repository top}))
    repository))

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
  build products), shared by every commit archive naming that pin."
  "data/source/pins")

(defn- extract-pinned!
  "Extract `checkout` at `pin` into `destination`, then each nested gitlink of
  that pin whose checkout is initialized, at its nested pin (e.g.
  `reference-code/babashka/fs`, a `:local/root` of `deps.edn`); `git archive`
  leaves a gitlink an empty directory."
  {:malli/schema [:=> [:cat :string :string :string] :nil]}
  [checkout pin destination]
  (extract-archive! checkout pin destination)
  (doseq [{nested :path nested-pin :pin} (gitlinks checkout pin)
          :when (.exists (io/file checkout nested ".git"))]
    (extract-pinned! (str (io/file checkout nested)) nested-pin (str (io/file destination nested)))))

(defn- promoted!
  "Rename the complete directory `staging` to `target`: true, or false when a
  concurrent build of the same content renamed first (its bytes are this
  staging's, the directory being a function of its key), whose target then
  stands and the staging is deleted. `fs/move` would move the staging INTO an
  existing target."
  {:malli/schema [:=> [:cat :any :any] :boolean]}
  [^java.io.File staging ^java.io.File target]
  (try (java.nio.file.Files/move (.toPath staging) (.toPath target)
                                 (into-array java.nio.file.CopyOption
                                             [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
       true
       (catch java.io.IOException lost
         (when-not (.isDirectory target) (throw lost))
         (fs/delete-tree staging)
         false)))

(defn- pinned-checkout!
  "The directory holding `path` at `pin`, archived from its checkout once:
  staged, then atomically moved (its existence is completion). Never the
  checkout itself: a checkout moves, and an archive linking it would run
  another commit's bytes beside classes keyed on this commit's pins."
  {:malli/schema [:=> [:cat :string :string :string :string] :string]}
  [root repository path pin]
  (let [target (io/file root pinned-directory (str/replace path "/" "-") pin)]
    (when-not (.isDirectory target)
      (let [staging (io/file (.getParentFile target)
                             (str pin ".staging-" (.pid (java.lang.ProcessHandle/current))))]
        (fs/delete-tree staging)
        (extract-pinned! (str (io/file repository path)) pin (str staging))
        (promoted! staging target)))
    (.getCanonicalPath target)))

(defn- seal-pinned!
  "Seal the files git tracks at `pin` of `checkout` under `directory` (and, as
  `extract-pinned!` places them, each initialized nested gitlink at its nested
  pin) with `seal-archive!`: a dependency's source is the running JVM's code,
  and an agent's relative `spit` into a pin rewrote it. Untracked build
  products (tools.deps preparation output) and directories stay writable, so
  a rerun of preparation and `fs/delete-tree` (pruning, nuke) still work."
  {:malli/schema [:=> [:cat :string :string :string] :nil]}
  [checkout pin directory]
  (seal-archive! directory (command! ["git" "ls-tree" "-r" "-z" "--name-only" pin] checkout 30000))
  (doseq [{nested :path nested-pin :pin} (gitlinks checkout pin)
          :when (.exists (io/file checkout nested ".git"))]
    (seal-pinned! (str (io/file checkout nested)) nested-pin (str (io/file directory nested)))))

(defn- pins-text
  "`git ls-files --stage -- reference-code` output for `gitlinks`, the bytes
  `seon.dev.dependency-digest/dependency-pins` keys a snapshot on."
  {:malli/schema [:=> [:cat [:vector [:map [:path :string] [:pin :string]]]] :string]}
  [gitlink-pins]
  (apply str (map (fn [{:keys [path pin]}] (str "160000 " pin " 0\t" path "\n"))
                  (sort-by :path gitlink-pins))))

(defn committed-source!
  "A commit's program (HEAD by default) as a directory of committed bytes,
  built once per commit. Each gitlink links its pin archived once per root
  (`pinned-checkout!`), so the classpath is a function of the commit (a
  checkout-linked archive resumed after the checkout moved ran new datahike
  source beside dependency classes keyed on and compiled from the old pin:
  two `datahike.datom.Datom` classes); tools.deps prepares a pin only while
  its declared `:deps/prep-lib` output is absent."
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
            _ (extract-archive! repository sha staging)
            submodules
            (mapv (fn [{:keys [path pin]}]
                    (let [placed (io/file staging path)
                          pinned (pinned-checkout! root repository path pin)]
                      (fs/delete-tree placed)
                      (io/make-parents placed)
                      (fs/create-sym-link placed pinned)
                      {:path path :pin pin :pinned pinned
                       :prepared? (unprepared-build-products? pinned)}))
                  (gitlinks repository sha))
            ;; An archived pin that declares preparation lacks its build
            ;; products until tools.deps prepares them (e.g. http-kit's and
            ;; datahike's compiled Java), once per pin.
            _ (when (some :prepared? submodules)
                (command! ["clojure" "-X:deps" "prep" ":aliases" "[:dev :test]"] staging 300000))
            ;; Sealed only after preparation, which writes into the pins.
            _ (doseq [{:keys [path pin pinned]} submodules]
                (seal-pinned! (str (io/file repository path)) pin pinned))
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
            committed-paths (command! ["git" "ls-tree" "-r" "-z" "--name-only" sha] repository 30000)
            _ (spit (io/file staging "test-input-paths.txt") committed-paths)
            _ (seal-archive! (str staging) committed-paths)
            built? (promoted! staging target)]
        {:seon.source/git-sha sha :seon.operator/source-root (.getCanonicalPath target)
         :seon.operator/source-built? built? :seon.operator/submodules submodules})))))

(defn- elapsed-ms [began] (quot (- (System/nanoTime) began) 1000000))

(defn- nuke-attempt!
  "One destroy-and-rebuild from `source`; any failure leaves no JVM of this root."
  [request source]
  (let [root (:seon.operator/managed-root request)
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
        phases {:seon.operator/launch-ms (elapsed-ms launched)
                :seon.boot/ready-ms (get-in value [:seon.boot/readiness :seon.boot/ready-ms])}]
    (if ready?
      (assoc value :seon.operator/phases phases)
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
    `cache-directory`, `seon.fn` analysis cache-root, `bin/seon-hook`
    `shared-kondo-cache-v1-directory`).
  - .cpcache: tools.deps' computed classpaths.
  - data/embeddings: the durable embedding store (`seon.embed/directory`)."
  ["data/source"
   "target/dev-dependency-classes" "target/dev-dependency-classes.next"
   "target/dev-dependency-cache-result.edn" "target/dev-dependency-cache-current.edn"
   "target/dev-dependency-cache-processes" "target/dev-dependency-cache.lock"
   "target/dev-dependency-cache-references.lock"
   "target/test-published-bases" "target/test-classpaths"
   ".clj-kondo/.cache" ".cpcache" "data/embeddings"])

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
  (let [{:keys [deleted kept-shared]}
        (reduce (fn [outcome relative]
                  (let [file (io/file root relative)
                        linked (linked-component root relative)]
                    (cond
                      linked (update outcome :kept-shared conj linked)
                      (fs/exists? file {:nofollow-links true})
                      (do (fs/delete-tree file) (update outcome :deleted conj relative))
                      :else outcome)))
                {:deleted [] :kept-shared #{}}
                nuke-derived-paths)
        advertisements
        (into []
              (keep (fn [directory]
                      (let [file (io/file directory "prepl.edn")]
                        (when (.isFile file)
                          (fs/delete file)
                          (str "data/clusters/" (.getName directory) "/prepl.edn")))))
              (or (.listFiles (io/file root "data/clusters")) []))]
    {:seon.operator/root root
     :seon.operator/repository-root? (= (.getCanonicalPath (io/file root))
                                        (.getCanonicalPath (io/file (repository-root))))
     :seon.operator/deleted (into deleted advertisements)
     :seon.operator/kept-shared (vec (sort kept-shared))}))

(defn nuke!
  "Delete the store and every derived cache, then rebuild `default` from
  committed HEAD and report. A HEAD that does not reach readiness leaves no
  JVM of this root and answers its whole failure."
  [request]
  (let [began (System/nanoTime)
        root (:seon.operator/managed-root request)
        ;; Committed input is resolved before anything is stopped or deleted.
        sha (str/trim (command! ["git" "rev-parse" "--verify" "HEAD^{commit}"] (committed-repository) 30000))
        stopped (down! request (selected-processes root))
        down-ms (elapsed-ms began)
        wiped (wipe-derived-state! root)
        wipe-ms (- (elapsed-ms began) down-ms)
        started (System/nanoTime)
        source (committed-source! root sha)
        result (assoc (nuke-attempt! request (:seon.operator/source-root source))
                      :seon.operator/source source :seon.operator/source-ms (elapsed-ms started))
        report {:seon.operator/stopped-processes (:seon.operator/stopped-processes stopped)
                :seon.operator/wiped wiped
                :seon.operator/phases {:seon.operator/down-ms down-ms :seon.operator/wipe-ms wipe-ms}
                :seon.operator/total-ms (elapsed-ms began)}]
    (if-let [failed (:seon.operator/failed-attempt result)]
      (assoc (diagnostic (str "Nuclear rebuild failed on HEAD " sha "; no JVM of this root remains.")
                         (merge report failed {:seon.operator/source source}) :client-failed)
             :seon.operator/process-exit? true)
      (merge (dissoc result :seon.operator/phases) report
             {:seon.operator/attempt-phases (:seon.operator/phases result)}))))

;;; Move to HEAD (owner 2026-09-23: "isn't a nuke more disruptive?" / "Then
;;; load head. Don't keep a broken system running."). The nuke's committed
;;; archive is the program; the store and every cache stay. The replacement
;;; JVM resumes: boot compares the files with the published program and
;;; publishes only the paths that differ (`seon.cluster.boot`
;;; `changed-source-paths`), then `default` adopts that publication. Every
;;; JVM's program is an archive (plain `start` launches HEAD's too, `launch!`),
;;; so a half-edited working-tree file never reaches a boot and hook
;;; publication is off: committed changes arrive by `start --head`.

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

(declare held-sources)

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
                    ;; A real directory is a JVM's private cache: replaced only
                    ;; when no live process names this source.
                    (and (fs/exists? link {:nofollow-links true}) (not (fs/sym-link? link))
                         (contains? (held-sources) (str (fs/real-path source))))
                    {:path relative :placed :kept-held}
                    :else
                    ;; Written beside and renamed over, so a concurrent reader of
                    ;; the link sees the old or the new link, never none.
                    (let [replaced? (fs/exists? link {:nofollow-links true})
                          temporary (fs/path (fs/parent link) (str (fs/file-name link) ".link-" (random-uuid)))]
                      (when (and replaced? (not (fs/sym-link? link))) (fs/delete-tree link))
                      (fs/create-dirs (fs/parent link))
                      (fs/create-sym-link temporary (fs/real-path shared))
                      (fs/move temporary link {:replace-existing true :atomic-move true})
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
  "The program directory a live JVM runs (every launch runs a committed
  archive, so no working-tree edit reaches it; `start --head` moves it)."
  {:malli/schema [:=> [:cat :map] :map]}
  [identity]
  {:seon.operator/source-root
   (when-let [handle (matching-handle identity true)]
     (let [args (.arguments (.info handle))]
       (when (.isPresent args) (named-source (vec (.get args))))))})

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
                        (not (contains? held (.getCanonicalPath ^java.io.File %)))
                        (not (held? (repository-root) (.getCanonicalPath ^java.io.File %))))
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
             now# (into {} (map (juxt identity head#)) (seon.cluster.registry/branches store#))
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
                                           (seon.cluster.registry/branches store#))})
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
      (catch Exception cause
        (assoc (diagnostic "Restoring the branch heads failed; the store may be ahead of older programs."
                           {:seon.operator/captured-heads captured} :client-failed cause)
               :seon.operator/rollback :failed)))))

(defn- prepared-source!
  "`revision`'s committed archive, ready to launch: built once per commit
  (`committed-source!`), linked to this root's shared caches (the analyzer
  cache seeded from `previous-source`, the program that ran before) and its
  classpath computed by tools.deps into the archive's own `.cpcache`. Runs
  while any old JVM still serves."
  {:malli/schema [:=> [:cat :string :string [:maybe :string]] :map]}
  [root revision previous-source]
  (let [began (System/nanoTime)
        repository (committed-repository)
        source (committed-source! root revision)
        archive-ms (elapsed-ms began)
        source-root (:seon.operator/source-root source)
        analysis (root-analysis-cache! root previous-source)
        shared (assoc (share-caches! {"target" (str (io/file repository "target"))
                                      ".clj-kondo/.cache" (:seon.operator/analysis-cache analysis)}
                                     source-root (gitlinks repository (:seon.source/git-sha source)))
                      :seon.operator/analysis analysis)
        share-ms (- (elapsed-ms began) archive-ms)
        _ (command! ["clojure" "-Spath" "-M:dev:test"] source-root runtime-probe-bound-ms)
        source-ms (elapsed-ms began)]
    (assoc source
           :seon.operator/shared-caches shared
           :seon.operator/phases {:seon.operator/source-ms source-ms :seon.operator/archive-ms archive-ms
                                  :seon.operator/share-ms share-ms
                                  :seon.operator/classpath-ms (- source-ms archive-ms share-ms)})))

(defn- filled-classes!
  "`source` with its dependency-class cache filled when it misses (root ruling
  2026-09-26, option A): run before any JVM stops, so the old program keeps
  serving and the replacement boots on a hit. The fill runs in the archive,
  whose `target` links the checkout's, so it lands in the one shared cache;
  the re-selection after it is the observed outcome the launch reuses."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [root source]
  (let [began (System/nanoTime)
        source-root (:seon.operator/source-root source)
        classes (dependency-classes (repository-root) source-root (child-environment root))]
    (if (= :no-matching-cache (:seon.dev-cache/reason classes))
      (do (command! ["clojure" "-T:dev-cache" "ensure-cache"] source-root dependency-fill-bound-ms)
          (-> source
              (assoc :seon.operator/class-fill
                     {:seon.operator/before classes
                      :seon.operator/after (dependency-classes (repository-root) source-root
                                                               (child-environment root))})
              (assoc-in [:seon.operator/phases :seon.operator/class-fill-ms] (elapsed-ms began))))
      source)))

(defn launch!
  "Start the root's JVM on committed HEAD (owner 2026-09-26: boot reads the
  committed archive, never the working tree), keeping the store and caches."
  {:malli/schema [:=> [:cat :map] :map]}
  [request]
  (let [root (:seon.operator/managed-root request)
        source (filled-classes! root (prepared-source! root "HEAD" nil))
        outcome (launch-child! request (:seon.operator/source-root source) true)]
    (merge (:seon.operator/value outcome)
           {:seon.operator/source (dissoc source :seon.operator/phases)
            :seon.operator/phases (:seon.operator/phases source)}
           (program-source (:seon.boot/advertisement outcome)))))

(defn move-to-head!
  "Replace the root's JVM with one whose program is committed HEAD, keeping the
  store and every cache.

  The archive is built and its caches linked before anything stops. Then the
  root's JVMs stop within their bound, the replacement resumes on the same
  store (publishing only the files that differ from the published program)
  and its boot adopts that publication onto `default` before any layer reads
  it. A replacement that does not reach readiness is terminated by exact
  identity and the full cause returned: a broken program never keeps running."
  {:malli/schema [:=> [:cat :map] :map]}
  [request]
  (let [began (System/nanoTime)
        root (:seon.operator/managed-root request)
        ;; The replaced JVM's program directory seeds this root's analyzer cache.
        previous-source (some-> (advertisement root nil) program-source :seon.operator/source-root)
        {source-root :seon.operator/source-root :as source}
        (filled-classes! root (prepared-source! root "HEAD" previous-source))
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
                  (catch Exception cause
                    (let [evidence (:seon.error/offending (ex-data cause))]
                      {:seon.operator/value
                       (diagnostic (ex-message cause) (or (ex-data cause) {}) :client-failed cause)
                       :seon.boot/advertisement (:seon.boot/advertisement evidence)
                       :seon.operator/captured-heads (:seon.operator/captured-heads evidence)})))
        launch-ms (elapsed-ms began-launch)
        value (:seon.operator/value outcome)
        missing (get-in value [:seon.boot/readiness :seon.boot/missing-layers])
        ready? (and (not (:seon.error/message value)) (vector? missing) (empty? missing))
        pruned (when ready? (prune-archives! root source-root))
        phases (merge (:seon.operator/phases source)
                      {:seon.operator/down-ms down-ms
                       :seon.operator/launch-ms launch-ms
                       :seon.boot/ready-ms (get-in value [:seon.boot/readiness :seon.boot/ready-ms])
                       :seon.operator/total-ms (elapsed-ms began)})
        report {:seon.operator/source (dissoc source :seon.operator/phases)
                :seon.operator/stopped-processes (:seon.operator/stopped-processes stopped)
                :seon.operator/phases phases}]
    (if ready?
      (merge value report
             {:seon.operator/archives pruned
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
                                      (catch Exception cause
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
                                " did not reach readiness"
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
                                          :seon.boot/advertisement (:seon.boot/advertisement outcome)
                                          :seon.operator/terminated terminated
                                          :seon.operator/resumed resumed})
                           :client-failed)
               :seon.operator/process-exit? (not (:seon.operator/ready? resumed)))))))

(declare failure-cause first-line)

;;; Will this tree boot? (owner 2026-09-28: HEAD refused to boot twice in one
;;; hour; only a boot saw it.) The candidate is a commit object whose tree is
;;; HEAD's with the named working-tree files, archived as `start --head`
;;; archives HEAD; the running JVM publishes and adopts it on disposable
;;; branches of the live store (`seon.cluster/rehearse-source!`). No JVM boots.

(defn- candidate-revision!
  "HEAD's commit, or with `paths` a commit object whose tree is HEAD's with
  those working-tree files (written through a temporary index; HEAD, the
  shared index and the working tree are untouched)."
  {:malli/schema [:=> [:cat :string [:vector :string]] :string]}
  [repository paths]
  (let [sha (str/trim (command! ["git" "rev-parse" "--verify" "HEAD^{commit}"] repository 30000))]
    (if (empty? paths)
      sha
      (let [index (str (fs/create-temp-file {:prefix "seon-check-index"}))
            environment {"GIT_INDEX_FILE" index}]
        (try
          (command! ["git" "read-tree" sha] repository 30000 environment)
          (command! (into ["git" "add" "--"] paths) repository 60000 environment)
          (let [tree (str/trim (command! ["git" "write-tree"] repository 30000 environment))]
            (if (= tree (str/trim (command! ["git" "rev-parse" (str sha "^{tree}")] repository 30000)))
              sha
              (str/trim (command! ["git" "commit-tree" tree "-p" sha "-m"
                                   (str "check: HEAD + " (str/join " " paths))]
                                  repository 30000))))
          (finally (fs/delete-if-exists index)))))))

;;; Will the candidate's tests pass? (owner 2026-09-28: a lane proves its change
;;; before committing.) The rehearsal above adopts the candidate in the running
;;; JVM, which cannot run its compiled code; `--test` then boots a THROWAWAY JVM
;;; from the candidate's archive under a disposable root `data/check/<sha>-<pid>`
;;; (its own empty store, lock and ports: a fresh install is adoption onto an
;;; empty store, `launch!`'s path), runs the named tests there through the
;;; candidate's own `bin/test-check` (the one runner path `bin/test` uses), then
;;; terminates that JVM by exact identity and deletes the root. The root links
;;; this root's archived pins and the checkout's `target` (class caches); its
;;; analysis cache is a private copy, since the candidate's bytes are not the
;;; published program this root's cache follows.

(defn operator-check-slots
  "The configured number of throwaway check JVMs one root runs at once."
  {:malli/schema [:=> [:cat [:maybe :seon.config/manifest]] [:int {:min 1}]]}
  [manifest]
  (let [value (get manifest :seon.config.operator/check-slots
                   (:seon.config.operator/check-slots @shipped-default-decisions))]
    (when-not (pos-int? value) (throw (ex-info "Missing operator check slot count." manifest)))
    value))

(defn- live-tickets
  "The queue directory's tickets, oldest first, as `[file pid]`. A ticket whose
  process is gone is deleted and left out, so a dead check never blocks the queue."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [queue]
  (vec (for [file (sort-by #(.getName ^java.io.File %) (.listFiles ^java.io.File queue))
             :let [pid (parse-long (second (str/split (.getName ^java.io.File file) #"-")))
                   alive? (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) .isAlive)]
             :when (if alive? true (do (fs/delete-if-exists file) false))]
         [file pid])))

(defn- free-lock
  "The exclusive lock of `channel`'s file, or nil when another process or this
  JVM holds it."
  {:malli/schema [:=> [:cat :any] :any]}
  [channel]
  (try (.tryLock ^java.nio.channels.FileChannel channel)
       (catch Exception overlap ; this JVM holds it (bb lacks the class)
         (when-not (= "java.nio.channels.OverlappingFileLockException" (.getName (class overlap)))
           (throw overlap))
         nil)))

(defn- try-slot!
  "The held lock of slot `n` under `directory` with this process's pid written
  in it, or nil when another process or a waiter of this JVM holds it."
  {:malli/schema [:=> [:cat :any :int :int] [:maybe :map]]}
  [directory n pid]
  (let [channel (.getChannel (java.io.RandomAccessFile.
                              (io/file directory (str "slot-" n ".lock")) "rw"))
        lock (free-lock channel)]
    (if lock
      (do (.truncate channel 0)
          (.write channel (java.nio.ByteBuffer/wrap (.getBytes (str pid))))
          {:seon.operator/slot n :seon.operator/channel channel})
      (do (.close channel) nil))))

(defn- slot-holders
  "The pids written in the slot files under `directory`."
  {:malli/schema [:=> [:cat :any :int] [:vector :string]]}
  [directory slots]
  (vec (for [n (range slots)
             :let [file (io/file directory (str "slot-" n ".lock"))]
             :when (.isFile file)
             :let [text (str/trim (slurp file))]
             :when (seq text)]
         text)))

(defn- check-slot!
  "A held slot lock under `root`'s `data/check` (`{:seon.operator/slot n}` plus
  its channel, whose close releases it), awaited within `bound` ms among
  `slots` slots. First come, first served: a waiter files a ticket
  `queue/<epoch-ms>-<pid>` and only the oldest live ticket takes a free slot.
  The lock is the OS's, so a check that dies releases its slot, and its ticket
  is skipped by the pid check."
  {:malli/schema [:=> [:cat :string [:int {:min 1}] [:int {:min 1}] :int] :map]}
  [root bound slots pid]
  (let [directory (doto (io/file root "data/check") .mkdirs)
        queue (doto (io/file directory "queue") .mkdirs)
        ticket (io/file queue (format "%015d-%d" (System/currentTimeMillis) pid))
        _ (fs/create-file ticket)
        began (System/nanoTime)]
    (try
      (loop [announced? false]
        (let [tickets (live-tickets queue)
              position (first (keep-indexed (fn [i [file _]] (when (= file ticket) i)) tickets))
              slot (when (zero? (or position 0)) (some #(try-slot! directory % pid) (range slots)))]
          (or slot
              (do (when (< bound (elapsed-ms began))
                    (fail! "No throwaway JVM slot freed within its bound."
                           {:seon.operator/slots slots :seon.operator/timeout-ms bound}))
                  (when-not announced?
                    (binding [*out* *err*]
                      (println (str "check: queue position " (inc (or position 0)) " of " (count tickets)
                                    "; " slots " throwaway JVMs run, held by pids "
                                    (str/join " " (slot-holders directory slots))))
                      (flush)))
                  (Thread/sleep 200)
                  (recur true)))))
      (finally (fs/delete-if-exists ticket)))))

(defn reaped-orphan-slots!
  "Stop each check JVM under a slot of `root`'s `data/check` whose lock no
  process holds: its check ended (killed, suspended and killed, crashed) and
  left it running. Each free slot is taken for the sweep only, its JVMs
  terminated by exact identity and its root deleted; the reaped identities."
  {:malli/schema [:=> [:cat :string] [:vector :map]]}
  [root]
  (let [directory (io/file root "data/check")
        slots (keep #(some->> (re-matches #"slot-(\d+)" (.getName ^java.io.File %)) second parse-long)
                    (.listFiles directory))]
    (into []
          (mapcat (fn [n]
                    (when-let [slot (try-slot! directory n (.pid (java.lang.ProcessHandle/current)))]
                      (try
                        (let [disposable (io/file directory (str "slot-" n))
                              orphans (selected-processes (.getCanonicalPath disposable))]
                          (doseq [process orphans] (terminate! process (operator-silence-backstop-ms {})))
                          (when (seq orphans) (fs/delete-tree disposable))
                          orphans)
                        (finally (.close ^java.nio.channels.FileChannel (:seon.operator/channel slot)))))))
          (sort (distinct slots)))))

(defn- test-check-arguments
  "`bin/test-check` arguments for the check's `--test` values: `NS/NAME` names
  one test, `NS` a namespace; `include-long?` runs declared-long tests too."
  {:malli/schema [:=> [:cat [:vector :string] :boolean] [:vector :string]]}
  [tests include-long?]
  (cond-> (into [] (mapcat #(if (str/includes? % "/") ["--test" %] ["--ns" %])) tests)
    include-long? (conj "--include-long")))

(defn- log-tail
  "The last `n` lines of the file at `path`, or nothing."
  {:malli/schema [:=> [:cat :string [:int {:min 1}]] [:maybe [:vector :string]]]}
  [path n]
  (when (.isFile (io/file path))
    (vec (take-last n (str/split-lines (slurp path))))))

(defn- run-candidate-tests!
  "Run `tests` on the ready throwaway JVM of `disposable` through the
  candidate's `bin/test-check`: its summary line and whether it passed."
  {:malli/schema [:=> [:cat :string :string [:vector :string] :boolean] :map]}
  [source-root disposable tests include-long?]
  (let [classpath (str/join ":" (map #(str source-root "/" %) ["script" "src" "resources"]))
        argv (into ["bb" "--config" "/dev/null" "--classpath" classpath
                    (str source-root "/bin/test-check") "--root" disposable "default"]
                   (test-check-arguments tests include-long?))
        bound (+ (operator-check-bound-ms {}) (operator-silence-backstop-ms {}))]
    (try
      {:seon.operator/test-summary (str/trim (command! argv source-root bound))
       :seon.operator/tests-passed? true}
      (catch clojure.lang.ExceptionInfo refusal
        (let [evidence (:seon.error/offending (ex-data refusal))]
          (if-let [output (:seon.operator/output evidence)]
            {:seon.operator/test-summary (str/trim output) :seon.operator/tests-passed? false}
            (throw refusal)))))))

(defn operator-check-templates
  "The configured number of check template stores (one per published commit)
  one root keeps; the least recently cloned beyond it are deleted."
  {:malli/schema [:=> [:cat [:maybe :seon.config/manifest]] [:int {:min 1}]]}
  [manifest]
  (let [value (get manifest :seon.config.operator/check-templates
                   (:seon.config.operator/check-templates @shipped-default-decisions))]
    (when-not (pos-int? value) (throw (ex-info "Missing operator check template count." manifest)))
    value))

(defn- process-alive?
  "Whether any process has the pid `pid`."
  {:malli/schema [:=> [:cat :int] :boolean]}
  [pid]
  (boolean (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) .isAlive)))

(defn- swept-check-roots!
  "Delete each disposable root under `root`'s `data/check` (`<sha>-<pid>`) and
  `data/check/template` (`<name>.store-<pid>`) whose owning check process is
  gone, first stopping by exact identity each JVM still running there (an
  orphan: its check was killed or suspended); the deleted paths. A killed check
  never reaches its own `finally`. `delete-tree` never follows a link."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [root]
  (let [directory (io/file root "data/check")]
    (vec (for [child (concat (.listFiles directory) (.listFiles (io/file directory "template")))
               :when (and (.isDirectory ^java.io.File child) (not (fs/sym-link? child)))
               :let [file-name (.getName ^java.io.File child)
                     at (str/last-index-of file-name "-")
                     pid (when at (parse-long (subs file-name (inc at))))]
               ;; A slot root belongs to its slot's lock holder (`candidate-tests!`).
               :when (and pid (not (str/starts-with? file-name "slot-")) (not (process-alive? pid)))]
           (do (doseq [process (selected-processes (str child))]
                 (terminate! process (operator-silence-backstop-ms {})))
               (fs/delete-tree child)
               (str child))))))

(defn- linked-pins!
  "Link the scratch root `disposable`'s pin directory to `root`'s, so its archive
  preparation extracts each gitlink pin once for every root; before anything
  prepares a source under `disposable`, which would make the directory itself."
  {:malli/schema [:=> [:cat :string :string] :any]}
  [root disposable]
  (fs/create-dirs (io/file root pinned-directory))
  (fs/create-dirs (io/file disposable "data/source"))
  (fs/create-sym-link (io/file disposable pinned-directory)
                      (fs/real-path (io/file root pinned-directory))))

(defn- disposable-root-boot!
  "Boot a JVM of the archive `source-root` under the scratch root `disposable`
  (its pins linked, `linked-pins!`) and return its boot outcome with
  `:seon.operator/ready?`; the caller stops it. `shape` is merged into the
  launch request (`live-shape`)."
  {:malli/schema [:=> [:cat :string :string :string :map] :map]}
  [root disposable source-root shape]
  ;; A candidate's agents work in its own archive, never the checkout.
  (let [outcome (try (launch-child! (merge-with merge
                                                {:seon.operator/managed-root disposable
                                                 :seon.operator/throwaway? true
                                                 :seon.operator/command :start
                                                 :seon.boot/cluster-name "default"
                                                 :seon.config/environment
                                                 {:seon.config.fs/working-root source-root}}
                                                shape)
                                    source-root true)
                     (catch Exception cause
                       {:seon.operator/value
                        (diagnostic (ex-message cause) (or (ex-data cause) {}) :client-failed cause)}))
        missing (get-in outcome [:seon.operator/value :seon.boot/readiness :seon.boot/missing-layers])]
    (assoc outcome :seon.operator/ready?
           (and (not (:seon.error/message (:seon.operator/value outcome))) (vector? missing) (empty? missing)))))

(defn- launched-throwaway!
  "Boot a JVM of commit `sha` under the disposable root `disposable` on
  whatever store `disposable`'s `data/store` holds: whether it reached
  readiness, its boot value and the phases. The caller stops it. `shape` is
  merged into the launch request (`live-shape`)."
  {:malli/schema [:function
                  [:=> [:cat :string :string :string] :map]
                  [:=> [:cat :string :string :string :map] :map]]}
  ([root disposable sha] (launched-throwaway! root disposable sha {}))
  ([root disposable sha shape]
  (let [began (System/nanoTime)
        _ (linked-pins! root disposable)
        previous (some-> (advertisement root nil) program-source :seon.operator/source-root)
        source (filled-classes! disposable (prepared-source! disposable sha previous))
        source-root (:seon.operator/source-root source)
        prepared-ms (elapsed-ms began)
        outcome (disposable-root-boot! root disposable source-root shape)
        value (:seon.operator/value outcome)]
    {:seon.operator/ready? (:seon.operator/ready? outcome)
     :seon.operator/value value
     :seon.operator/source-root source-root
     :seon.operator/phases (merge (:seon.operator/phases source)
                                  {:seon.operator/prepare-ms prepared-ms
                                   :seon.operator/boot-ms (- (elapsed-ms began) prepared-ms)
                                   :seon.boot/ready-ms (get-in value [:seon.boot/readiness :seon.boot/ready-ms])})})))

;;; A template store per published commit (card PLATFORM-STEP slice 2). A
;;; throwaway JVM booted onto an empty store spends most of its boot publishing
;;; the whole program, the same facts for every check of the same base. The
;;; base is the commit the root's live JVM runs; its template is the store a
;;; JVM of exactly that commit's archive published at its first boot onto an
;;; empty store, then stopped through its own `down`. A store's identity is a
;;; function of its canonical path (`seon.cluster.store/datahike-configuration`;
;;; Datahike refuses a store id that differs from the stored one), so a check
;;; JVM's root is its slot's fixed directory `data/check/slot-<n>` and each slot
;;; keeps its own template, `data/check/template/<sha>_slot<n>`, built at that
;;; path. The commit id names every program input (the schema resources,
;;; configuration and pins are committed bytes), so with the slot it is the
;;; whole key. Each check clones it (`cloned!`, an APFS clone) back to its
;;; slot's store and its boot publishes only the candidate's files whose
;;; digests differ, the resume path `start --head` takes. One exclusive file
;;; lock per template orders clone and deletion across processes.

(def ^:private clonefile-script
  "Python's call of clonefile(2) on `sys.argv[1]` to `sys.argv[2]`; a refusal
  exits non-zero naming its errno."
  "import ctypes, os, sys
libc = ctypes.CDLL(None, use_errno=True)
if libc.clonefile(os.fsencode(sys.argv[1]), os.fsencode(sys.argv[2]), 0) != 0:
    sys.exit('clonefile: ' + os.strerror(ctypes.get_errno()))")

(defn- cloned!
  "Clone the directory `source` to the absent path `target` in one
  clonefile(2) call, within the operator silence bound; the argv it ran. The
  kernel clones the tree in one pass, where `cp -c -R` makes a dozen syscalls
  per file, so a konserve store's clone is not paced by its file count. As
  with `cp`, the clone is no snapshot: a concurrent writer is not blocked, and
  a file it writes during the call may or may not be in the clone."
  {:malli/schema [:=> [:cat :string :string :string] [:vector :string]]}
  [root source target]
  (let [argv ["python3" "-I" "-c" clonefile-script source target]]
    (command! argv root (operator-silence-backstop-ms {}))
    argv))

(defn- with-template-lock
  "`f`'s value, called holding the exclusive lock of the template `template-name` under
  `directory`, awaited within `bound` ms. The lock is the OS's: a check that
  dies releases it."
  {:malli/schema [:=> [:cat :any :string [:int {:min 1}] fn?] :any]}
  [directory template-name bound f]
  (let [channel (.getChannel (java.io.RandomAccessFile. (io/file directory (str template-name ".lock")) "rw"))
        began (System/nanoTime)]
    (try
      (loop []
        ;; Closing the channel releases the lock (bb allows no `.release`).
        (if (free-lock channel)
          (f)
          (do (when (< bound (elapsed-ms began))
                (fail! "The check template's lock was not freed within its bound."
                       {:seon.operator/template template-name :seon.operator/timeout-ms bound}))
              (Thread/sleep 200)
              (recur))))
      (finally (.close channel)))))

(defn- built-template!
  "Build the template store `name` of commit `base` under `directory`: a JVM
  of its archive boots onto an empty store at the slot root `disposable`,
  stops through its own `down`, and its store is renamed into place. The
  build's phases, or its failure; the slot root is emptied either way."
  {:malli/schema [:=> [:cat :string :any :string :string :string] :map]}
  [root directory base template-name disposable]
  (let [began (System/nanoTime)
        staged (io/file directory (str template-name ".store-" (.pid (java.lang.ProcessHandle/current))))]
    (try
      (let [boot (launched-throwaway! root disposable base)
            stopped (down! {:seon.operator/managed-root disposable :seon.boot/cluster-name "default"}
                           (selected-processes disposable))
            result {:seon.operator/phases (:seon.operator/phases boot)
                    :seon.operator/stopped stopped}]
        (if (and (:seon.operator/ready? boot) (empty? (:seon.operator/graceful-failures stopped)))
          (do (fs/move (io/file disposable "data/store") staged)
              (fs/move staged (io/file directory template-name) {:atomic-move true})
              (assoc result :seon.operator/build-ms (elapsed-ms began)))
          (diagnostic (str "The template JVM of " base " did not boot and stop cleanly; the check boots onto an empty store.")
                      (assoc result :seon.operator/value (:seon.operator/value boot)
                             :seon.operator/log-tail (log-tail (str (io/file disposable "data/clusters/default/logs/seon.log")) 40))
                      :client-failed)))
      (finally
        (doseq [process (selected-processes disposable)]
          (terminate! process (operator-silence-backstop-ms {})))
        (fs/delete-tree disposable)
        (fs/create-dirs disposable)
        (fs/delete-tree staged)))))

(defn- pruned-templates!
  "Delete the template stores under `directory` beyond the `kept` most
  recently cloned, each only while its own lock is free; the deleted paths."
  {:malli/schema [:=> [:cat :any [:int {:min 1}]] [:vector :string]]}
  [directory kept]
  (vec (for [template (->> (.listFiles ^java.io.File directory)
                           (filter #(and (.isDirectory ^java.io.File %)
                                         (str/includes? (.getName ^java.io.File %) "_slot")
                                         (not (str/includes? (.getName ^java.io.File %) "."))))
                           (sort-by #(.lastModified ^java.io.File %) >)
                           (drop kept))
             :let [channel (.getChannel (java.io.RandomAccessFile.
                                         (io/file directory (str (.getName ^java.io.File template) ".lock")) "rw"))
                   deleted (try (when (free-lock channel)
                                  (fs/delete-tree template)
                                  (str template))
                                (finally (.close channel)))]
             :when deleted]
         deleted)))

(defn- running-commit
  "The commit whose archive `root`'s live JVM runs, or nil when it runs no
  commit of `repository`."
  {:malli/schema [:=> [:cat :string :string] [:maybe :string]]}
  [root repository]
  (when-let [program (some-> (advertisement root nil) program-source :seon.operator/source-root io/file .getName)]
    (let [sha (try (str/trim (command! ["git" "rev-parse" "--verify" (str program "^{commit}")]
                                       repository 30000))
                   (catch clojure.lang.ExceptionInfo _ nil))]
      (when (= sha program) sha))))

(defn- changed-dependencies
  "The dependency inputs (`deps.edn` and gitlink pins) that differ between
  commits `from` and `to`, sorted. A running JVM's classpath is fixed at launch,
  so it cannot adopt such a change (`seon.cluster.source/classify-paths`)."
  {:malli/schema [:=> [:cat :string :string :string] [:vector :string]]}
  [repository from to]
  (->> (str/split-lines (command! ["git" "diff" "--raw" "--no-abbrev" "--no-renames" from to] repository 30000))
       (keep (fn [line]
               (let [[meta path] (str/split line #"\t" 2)]
                 (when (and path (or (= "deps.edn" path) (str/includes? meta " 160000 ") (str/starts-with? meta ":160000 ")))
                   path))))
       sort vec))

(defn- seeded-store!
  "Place a clone of slot `slot`'s template store of the commit `root`'s live
  JVM runs at the slot root `disposable`'s `data/store`, building the template
  first when it is missing. The outcome: `:template` with the base commit, or
  `:empty` (the JVM publishes the whole program) with the reason."
  {:malli/schema [:=> [:cat :string :string :int] :map]}
  [root disposable slot]
  (let [began (System/nanoTime)
        repository (committed-repository)
        base (running-commit root repository)]
    (if-not base
      {:seon.operator/seeded :empty :seon.operator/reason :no-running-published-commit}
      (let [directory (doto (io/file root "data/check/template") .mkdirs)
            template-name (str base "_slot" slot)
            template (io/file directory template-name)]
        (with-template-lock
          directory template-name (operator-boot-bound-ms {})
          (fn []
            (let [built (when-not (.isDirectory template)
                          (built-template! root directory base template-name disposable))]
              (if (.isDirectory template)
                (do (fs/create-dirs (io/file disposable "data"))
                    (cloned! root (str template) (str (io/file disposable "data/store")))
                    (.setLastModified template (System/currentTimeMillis))
                    (cond-> {:seon.operator/seeded :template :seon.source/git-sha base
                             :seon.operator/seed-ms (elapsed-ms began)}
                      built (assoc :seon.operator/built built
                                   :seon.operator/pruned (pruned-templates! directory (operator-check-templates {})))))
                {:seon.operator/seeded :empty :seon.operator/reason :template-build-failed
                 :seon.operator/built built}))))))))

;;; A live-shape check (`check --live-shape`: restart batches and boot-critical
;;; commits; owner 2026-10-19, after a commit refused to boot on the live store
;;; while every template booted it) boots the candidate on a clone of this
;;; root's live store instead of a template. Datahike writes every value before
;;; the pointer that names it (`datahike.gc-guard`), so pointers copied BEFORE
;;; the bulk clone name only values the clone holds: the live JVM copies
;;; `:branches`, then every branch head it lists (it knows their konserve file
;;; names, `konserve.impl.defaults/key->store-key`); the directory is cloned
;;; (`cloned!`, APFS) and those pointers laid over it, so a writer active
;;; during the clone tears no branch. The clone presents the live store's id
;;; (`-Dseon.store.origin`, `seon.cluster.store/identity-path`), and the boot
;;; resumes on it like `start --head`. Its agents start nothing
;;; (`:seon.config.agent/max-running` 0).

(defn- pointer-copy-form
  "The live JVM's copy of its store's pointers into the directory `pointers`:
  `:branches` first, then the head of every branch listed after it, so each
  listed branch has its head (a branch created between them leaves an unlisted
  head, which nothing reads)."
  [pointers]
  `(let [conn# (seon.cluster.boot/connection "default")
         dir# (get-in @conn# [:config :store :path])
         copy# (fn [key#]
                 (let [file# (konserve.impl.defaults/key->store-key key#)]
                   (java.nio.file.Files/copy (.toPath (java.io.File. dir# file#))
                                             (.toPath (java.io.File. ~pointers file#))
                                             (into-array java.nio.file.CopyOption
                                                         [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
                   file#))
         listing# (copy# :branches)]
     {:seon.operator/store-dir dir#
      :seon.operator/pointers (into [listing#] (map copy#) (sort (datahike.api/branches conn#)))}))

(defn- live-store!
  "Place a clone of this root's live store at the slot root `disposable`'s
  `data/store`, its pointers copied by the live JVM first (`pointer-copy-form`);
  with no live JVM nothing writes the store and the clone is whole as taken.
  `store` names a copy of this root's store nobody writes (a preserved
  reproduction), cloned instead. The seed, its origin and timings."
  {:malli/schema [:=> [:cat :string :string [:maybe :string]] :map]}
  [root disposable store]
  (let [began (System/nanoTime)
        origin (.getCanonicalPath (io/file root "data/store"))
        source (if store (.getCanonicalPath (io/file store)) origin)
        target (io/file disposable "data/store")
        pointers (doto (io/file disposable "data/store.pointers") .mkdirs)
        live (when-not store (advertisement root "default"))
        copied (when live
                 (prepl-value! live (pr-str (pointer-copy-form (.getCanonicalPath pointers)))
                               (operator-silence-backstop-ms {})))
        pointer-ms (elapsed-ms began)]
    (cloned! root source (str target))
    (let [clone-ms (- (elapsed-ms began) pointer-ms)]
      (doseq [^java.io.File file (.listFiles pointers)]
        (fs/copy file (io/file target (.getName file)) {:replace-existing true}))
      (fs/delete-tree pointers)
      {:seon.operator/seeded :live :seon.operator/origin origin :seon.operator/source source
       :seon.operator/pointers (count (:seon.operator/pointers copied))
       :seon.operator/pointer-ms pointer-ms :seon.operator/clone-ms clone-ms
       :seon.operator/seed-ms (elapsed-ms began)})))

(def ^:private live-shape-launch
  "What a live-shape check's boot adds to its launch request (`live-store!`)."
  {:seon.config/environment {:seon.config.agent/max-running 0}})

(defn- candidate-tests!
  "Boot a throwaway JVM of `root` from commit `sha` on a clone of the published
  commit's template store, or with `:seon.operator/live-shape?` on a clone of
  the live store (`live-store!`, `:seon.operator/live-store` naming a copy of
  it), run `tests` there and answer the summary with the phases; a boot that
  does not reach readiness answers its failure with the log's tail. The JVM
  runs under its slot's root `data/check/slot-<n>`, is terminated by exact
  identity and the root deleted on every exit path; dead checks' roots are
  swept first."
  {:malli/schema [:=> [:cat :string :string [:vector :string] :boolean :map] :map]}
  [root sha tests include-long? shape]
  (let [began (System/nanoTime)
        swept (into (swept-check-roots! root) (map str) (reaped-orphan-slots! root))
        slot (check-slot! root (operator-boot-bound-ms {}) (operator-check-slots {})
                           (.pid (java.lang.ProcessHandle/current)))
        waited-ms (elapsed-ms began)
        ;; The slot's lock is held, so its root is this check's alone: a dead
        ;; holder's orphaned JVM is stopped by exact identity and its leftovers deleted.
        disposable (.getCanonicalPath (doto (io/file root "data/check" (str "slot-" (:seon.operator/slot slot))) .mkdirs))
        _ (doseq [process (selected-processes disposable)]
            (terminate! process (operator-silence-backstop-ms {})))
        _ (fs/delete-tree disposable)
        _ (fs/create-dirs disposable)]
    (try
      (let [live? (:seon.operator/live-shape? shape)
            seed (if live?
                   (live-store! root disposable (:seon.operator/live-store shape))
                   (try (seeded-store! root disposable (:seon.operator/slot slot))
                        (catch clojure.lang.ExceptionInfo refusal
                          (fs/delete-tree (io/file disposable "data/store"))
                          {:seon.operator/seeded :empty :seon.operator/reason (ex-data refusal)})))
            seeded-ms (elapsed-ms began)
            boot (launched-throwaway! root disposable sha
                                      (if live?
                                        (assoc live-shape-launch :seon.operator/store-origin
                                               (:seon.operator/origin seed))
                                        {}))
            booted-ms (elapsed-ms began)
            ;; The template's `default` ran the base's program: its boot adopts
            ;; the candidate's publication before readiness, so a test the
            ;; candidate adds is one of its tests.
            ready? (:seon.operator/ready? boot)
            run (when ready?
                  (run-candidate-tests! (:seon.operator/source-root boot) disposable tests include-long?))
            phases (merge (:seon.operator/phases boot)
                          {:seon.operator/slot-wait-ms waited-ms
                           :seon.operator/seed-ms (- seeded-ms waited-ms)
                           :seon.operator/tests-ms (- (elapsed-ms began) booted-ms)})
            evidence {:seon.operator/test-phases phases :seon.operator/seed seed
                      :seon.operator/swept swept}]
        (if ready?
          (merge run evidence)
          (diagnostic (str "The throwaway JVM from " sha " did not reach readiness; its tests did not run.")
                      (assoc evidence :seon.operator/value (:seon.operator/value boot)
                             :seon.operator/log-tail (log-tail (str (io/file disposable "data/clusters/default/logs/seon.log")) 40))
                      :client-failed)))
      (finally
        (try
          (doseq [process (selected-processes disposable)]
            (terminate! process (operator-silence-backstop-ms {})))
          ;; delete-tree never follows a link: the pins, `target` and gitlink
          ;; links are removed, their shared targets kept.
          (fs/delete-tree disposable)
          (finally (.close ^java.nio.channels.FileChannel (:seon.operator/channel slot))))))))

(defn- awaited!
  "The value of `pending`, a future, once it exits; its own exception rethrown
  unwrapped, so its `ex-data` reaches the caller."
  {:malli/schema [:=> [:cat :any] :any]}
  [pending]
  (try @pending
       (catch java.util.concurrent.ExecutionException wrapped
         (throw (or (.getCause wrapped) wrapped)))))

(defn check!
  "Answer whether HEAD plus `:seon.operator/paths` boots on this root's store,
  in its running JVM: the candidate's archive is published and adopted there
  on disposable branches. With `:seon.operator/tests`, the candidate boots in
  a throwaway JVM alongside the rehearsal and runs those tests there
  (`candidate-tests!`); a refused rehearsal or a red run refuses. A candidate that is not HEAD
  has its archive deleted after; HEAD's is kept for the `start --head` that
  follows."
  {:malli/schema [:=> [:cat :map] :map]}
  [request]
  (let [began (System/nanoTime)
        root (:seon.operator/managed-root request)
        repository (committed-repository)
        head (str/trim (command! ["git" "rev-parse" "--verify" "HEAD^{commit}"] repository 30000))
        at (:seon.operator/at request)
        sha (if at
              (str/trim (command! ["git" "rev-parse" "--verify" (str at "^{commit}")] repository 30000))
              (candidate-revision! repository (get request :seon.operator/paths [])))
        {source-root :seon.operator/source-root :as source} (committed-source! root sha)
        _ (hold! (repository-root) source-root)
        archive-ms (elapsed-ms began)]
    (try
      (let [tests (get request :seon.operator/tests [])
            ;; The throwaway JVM boots from its own archive while the running
            ;; JVM rehearses adoption; both are awaited, and either refuses.
            run (when (seq tests)
                  (future (candidate-tests! root sha tests (true? (:seon.operator/include-long? request))
                                            (select-keys request [:seon.operator/live-shape?
                                                                  :seon.operator/live-store]))))
            ;; `--at REV` only runs tests on REV's archive: nothing to adopt.
            ;; A candidate that changes the running JVM's dependencies cannot be
            ;; adopted there; the throwaway JVM booted from its archive is its proof.
            dependencies (when-not (or at (empty? tests))
                           (when-let [running (running-commit root repository)]
                             (seq (changed-dependencies repository running sha))))
            rehearsal (try (when-not (or at dependencies)
                             (connected! (assoc request :seon.source/directory source-root
                                                :seon.boot/cluster-name (get request :seon.boot/cluster-name "default"))))
                           (catch Exception cause (some-> run awaited!) (throw cause)))
            rehearsal-ms (elapsed-ms began)
            run (some-> run awaited!)
            phases {:seon.operator/archive-ms archive-ms
                    :seon.operator/rehearsal-ms (- rehearsal-ms archive-ms)
                    :seon.operator/total-ms (elapsed-ms began)}]
        (cond
          (:seon.error/message rehearsal)
          (assoc rehearsal :seon.source/git-sha sha :seon.operator/phases phases
                 :seon.operator/test-run run)
          (or (nil? run) (:seon.error/message run))
          (assoc (or run rehearsal) :seon.source/git-sha sha :seon.operator/phases phases)
          (:seon.operator/tests-passed? run)
          (cond-> (merge rehearsal run {:seon.source/git-sha sha :seon.operator/phases phases})
            dependencies (assoc :seon.operator/dependencies-changed (vec dependencies)))
          :else
          (diagnostic (str "The candidate " sha " is red: " (first-line (:seon.operator/test-summary run)))
                      (merge run {:seon.source/git-sha sha :seon.operator/phases phases})
                      :client-failed)))
      (finally
        (when (and (:seon.operator/source-built? source) (not= sha head)
                   (not (contains? (held-sources) source-root)))
          (fs/delete-tree source-root))))))

;;; The embed server (EmbeddingGemma 2, `script/embed/server.py`) runs beside the cluster: `start` launches it when
;;; its health endpoint does not answer, `stop`/`down` stop the recorded process, `status` reports it, never
;;; restarting it, and `nuke` deletes its durable store `data/embeddings`. Its interpreter and weights are named by
;;; environment variables; its port is the one `:seon.config.embed/endpoint` names in the shipped config.

(def ^:private embed-record "data/embed-server.edn")

(defn- embed-port
  "The embed server's port: the one the shipped config's endpoint names."
  []
  (let [endpoint (:seon.config.embed/endpoint (edn/read-string (slurp (io/file (program-root) "config/default.edn"))))]
    (.getPort (java.net.URI/create endpoint))))

(defn- embed-health
  "The embed server's /healthz answer within a second, parsed: `ok` is false once its GPU worker has stopped
  (HTTP 503); nil when nothing answers."
  [port]
  (try
    (let [client (-> (java.net.http.HttpClient/newBuilder) (.connectTimeout (java.time.Duration/ofSeconds 1)) .build)
          request (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str "http://127.0.0.1:" port "/healthz")))
                      (.timeout (java.time.Duration/ofSeconds 1)) .build)
          reply (.send client request (java.net.http.HttpResponse$BodyHandlers/ofString))]
      (json/parse-string (.body reply) true))
    (catch java.io.IOException _ nil)))

(defn- embed-healthy?
  "Whether the embed server's /healthz answers ok within a second."
  [port]
  (true? (:ok (embed-health port))))

(defn- recorded-embed-server
  "The recorded embed server's identity under `root`, or nothing."
  [root]
  (let [file (io/file root embed-record)]
    (when (.isFile file) (edn/read-string (slurp file)))))

(defn embed-server-status
  "The embed server as observed, never a restart: :healthy; :worker-stopped when it answers but its GPU worker
  has stopped; :down when its recorded process has exited; else :unreachable. Its MLX memory figures when it
  answers."
  [root]
  (let [port (embed-port)
        health (embed-health port)
        recorded (recorded-embed-server root)]
    (cond-> {:seon.operator.embed/state (cond (true? (:ok health)) :healthy
                                              health :worker-stopped
                                              (and recorded (nil? (matching-handle recorded true))) :down
                                              :else :unreachable)
             :seon.operator.embed/port port}
      (:memory health) (assoc :seon.operator.embed/memory (:memory health))
      (:last_error health) (assoc :seon.operator.embed/last-error (:last_error health))
      recorded (assoc :seon.operator.embed/process recorded))))

(defn embed-server-start!
  "Launch the embed server under `root` when its health endpoint does not answer and no recorded one is still
  loading, recording its exact identity; its log is data/clusters/default/logs/embed-server.log."
  [root]
  (let [port (embed-port)]
    (if (or (embed-healthy? port) (some-> (recorded-embed-server root) (matching-handle true)))
      (embed-server-status root)
      (let [home (System/getProperty "user.home")
            python (or (System/getenv "SEON_EMBED_PYTHON") (str home "/src/embed-models/embeddinggemma-2/.venv/bin/python"))
            weights (or (System/getenv "SEON_EMBED_WEIGHTS") (str home "/src/embed-models/embeddinggemma-2/weights/eg2-text-bf16"))
            log (doto (io/file root "data/clusters/default/logs/embed-server.log") io/make-parents)
            process (.start (doto (ProcessBuilder. [python "-I" (str (io/file (program-root) "script/embed/server.py")) weights
                                                    "--port" (str port)])
                              (.redirectErrorStream true)
                              (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log))))
            identity (identity-of (.toHandle process))]
        (spit (io/file root embed-record) (pr-str identity))
        (assoc (embed-server-status root) :seon.operator.embed/started identity)))))

(defn embed-server-stop!
  "Stop the recorded embed server by its exact identity and forget it."
  [root]
  (when-let [identity (recorded-embed-server root)]
    (when (matching-handle identity true) (terminate! identity (operator-silence-backstop-ms {})))
    (fs/delete-if-exists (io/file root embed-record))
    identity))

(defn request! [request]
  (try
    (case (:seon.operator/command request)
      :start (assoc (cond
                      (:seon.operator/head? request) (move-to-head! request)
                      (seq (selected-processes (:seon.operator/managed-root request))) (connected! request)
                      :else (launch! request))
                    :seon.operator/embed-server (embed-server-start! (:seon.operator/managed-root request)))
      :status (let [reaped (reaped-orphan-slots! (:seon.operator/managed-root request))
                    result (cond-> (connected! request)
                             (seq reaped) (assoc :seon.operator/reaped-check-jvms reaped))
                    failure (:seon.boot/failure result)]
                (cond-> (assoc result :seon.operator/embed-server
                               (embed-server-status (:seon.operator/managed-root request)))
                  (:seon.boot/pid result) (merge (program-source result))
                  failure (assoc :seon.error/message
                                 (str "The running JVM's boot failed: " (:seon.error/message failure)))))
      :stop (assoc (if (:seon.operator/force? request) (force-stop! request) (connected! request))
                   :seon.operator/embed-server-stopped (embed-server-stop! (:seon.operator/managed-root request)))
      :down (assoc (down! request (selected-processes (:seon.operator/managed-root request)))
                   :seon.operator/embed-server-stopped (embed-server-stop! (:seon.operator/managed-root request)))
      ;; reset = the running JVM unlinks the branch and forks a fresh one,
      ;; keeping every cache; nuke = delete the store and every cache.
      :reset (do (when-not (:seon.operator/force? request)
                   (fail! "Reset requires --force." request))
                 (connected! request))
      :nuke (do (when-not (:seon.operator/force? request)
                  (fail! "Nuclear rebuild requires --force." request))
                (nuke! request))
      :check (check! request)
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

(defn stray-positional
  "The first of `positionals` that is no path: it exists in neither the working
  tree nor `repository`'s index (a deleted path is still tracked, which is how a
  deletion is committed), so it is a test name left over by `--test A B`."
  {:malli/schema [:=> [:cat :string [:sequential :string]] [:maybe :string]]}
  [repository positionals]
  (first (remove #(or (.exists (io/file %))
                      (not (str/blank? (command! ["git" "ls-files" "--" %] repository 30000))))
                 positionals)))

(def ^:private command-usage
  "The command usage shared by general and command-specific help."
  [[:start "start [NAME] [--config PATH] | start --head"]
   [:init "init [NAME --force | --dev NAME] [--changed PATH...]"]
   [:status "status [--verbose]"]
   [:open "open [NAME]"]
   [:stop "stop [NAME]"]
   [:down "down [--force]"]
   [:reset "reset [NAME] --force"]
   [:nuke "nuke --force"]
   [:logs "logs [NAME]"]
   [:config-apply "config apply [NAME] PATH [--branch B | --chains]"]
   [:check "check [PATH... | --at REV] [--test NS | --test NS/NAME]... [--include-long] [--live-shape [--store PATH]] (--live-shape boots the candidate on a clone of this root's live store, or of the copy PATH, instead of the published commit's template; check --at REV runs the named tests on a throwaway JVM booted from git archive REV; each test needs its own --test)"]])

(defn parse-argv
  "The operator request, or usage data when the caller asks for help."
  {:malli/schema [:=> [:cat [:sequential :string]]
                  [:map [:seon.operator/managed-root :seon.operator/managed-root]
                   [:seon.operator/command [:or :seon.operator/command [:enum :help :nuke]]]
                   [:seon.operator/help {:optional true} :string]]]}
  [arguments]
  (let [[root args] (if (= "--seon-root" (first arguments))
                      [(canonical-root (second arguments)) (drop 2 arguments)]
                      [(canonical-root (repository-root)) arguments])
        [command & args] args
        command (case command "config" :config-apply (if (#{"--help" "-h"} command) :help (keyword (or command "help"))))
        help? (or (= :help command) (some #{"--help" "-h"} args))
        usage (when help?
                (if (= :help command)
                  (str/join " | " (map second command-usage))
                  (or (some (fn [[entry-command text]] (when (= command entry-command) text)) command-usage)
                      (fail! "Unknown command." {:seon.operator/command command}))))
        args (cond help? nil
                   (= command :config-apply)
                   (if (= "apply" (first args)) (rest args) (fail! "Use config apply." {}))
                   :else args)
        command (if help? :help command)
        base (cond-> {:seon.operator/managed-root root :seon.operator/command command}
               help? (assoc :seon.operator/help (str "seon [--root PATH] " usage)))]
    (loop [args (seq args) request base positionals []]
      (if-let [arg (first args)]
        (case arg
          "--force" (recur (next args) (assoc request :seon.operator/force? true) positionals)
          "--verbose" (recur (next args) (assoc request :seon.operator/verbose? true) positionals)
          "--head" (if (= :start command)
                     (recur (next args) (assoc request :seon.operator/head? true) positionals)
                     (fail! "--head is supported only by start." request))
          "--include-long" (if (= :check command)
                             (recur (next args) (assoc request :seon.operator/include-long? true) positionals)
                             (fail! "--include-long is supported only by check." request))
          "--live-shape" (if (= :check command)
                           (recur (next args) (assoc request :seon.operator/live-shape? true) positionals)
                           (fail! "--live-shape is supported only by check." request))
          "--store" (if (and (= :check command) (seq (next args)))
                      (recur (nnext args) (assoc request :seon.operator/live-store
                                                 (.getCanonicalPath (io/file (second args))))
                             positionals)
                      (fail! "--store PATH is supported only by check." request))
          "--at" (if (and (= :check command) (seq (next args)))
                   (recur (nnext args) (assoc request :seon.operator/at (second args)) positionals)
                   (fail! "--at REV is supported only by check." request))
          "--test" (if (and (= :check command) (seq (next args)))
                     (recur (nnext args) (update request :seon.operator/tests (fnil conj []) (second args))
                            positionals)
                     (fail! "--test NS or --test NS/NAME is supported only by check." request))
          "--branch" (if (and (= :config-apply command) (seq (next args)))
                       (recur (nnext args) (assoc request :seon.operator/branch (keyword (valid-name (second args)))) positionals)
                       (fail! "--branch B is supported only by config apply." request))
          "--chains" (if (= :config-apply command)
                       (recur (next args) (assoc request :seon.operator/chains? true) positionals)
                       (fail! "--chains is supported only by config apply." request))
          "--dev" (recur (nnext args) (assoc request :seon.operator/development-cluster (valid-name (second args))) positionals)
          "--config" (let [_ (when-not (= :start command)
                                        (fail! "--config is supported only by start." request))
                           manifest (edn/read-string (slurp (second args)))]
                         (when-not (map? manifest) (fail! "Manifest must be a map." {}))
                         (recur (nnext args) (assoc request :seon.config/manifest manifest) positionals))
          "--changed" (if (seq (next args))
                         ;; Canonical, as bin/test-check sends them: a relative path would
                         ;; resolve in the JVM's source directory, not the caller's.
                         (recur nil (assoc request :seon.source/changed-paths
                                           (mapv #(.getCanonicalPath (io/file %)) (next args)))
                                positionals)
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
              (when (and (:seon.operator/branch request) (:seon.operator/chains? request))
                (fail! "config apply takes --branch B or --chains, not both." request))
              ;; The JVM reads the document itself (`seon.config/apply!`'s path
              ;; arity): the shipped document applies as itself, its smart and
              ;; initialization decisions included, which no manifest admits.
              (assoc request :seon.boot/cluster-name (valid-name name)
                     :seon.config/path (.getCanonicalPath (io/file path))))
            :check (let [stray (when (seq (:seon.operator/tests request))
                                 (stray-positional (repository-root) positionals))]
                     (when stray
                       (fail! (str "'" stray "' is not a path: each test needs its own --test (--test A --test B), not --test A B.")
                              {:seon.operator/argument stray}))
                     (when (and (:seon.operator/live-store request) (not (:seon.operator/live-shape? request)))
                       (fail! "--store PATH names the live-shape check's store: it needs --live-shape." request))
                     (when (and (:seon.operator/live-shape? request) (empty? (:seon.operator/tests request)))
                       (fail! "--live-shape boots the candidate to run its tests: name each with --test." request))
                     (when (and (:seon.operator/at request) (seq positionals))
                       (fail! "--at REV checks REV's own archive: it takes no PATH." request))
                     (assoc request :seon.operator/paths
                            (mapv #(.getCanonicalPath (io/file %)) positionals)))
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

;;; The outcome's first line (issue 2f6f10f75: a failed move's headline sat at
;;; byte 1195 of one 410 KB line and read like success). Every command's
;;; stderr begins with one short line naming the outcome; a failure's whole
;;; diagnostic is written to a file that line names.

(defn- first-line
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [text]
  (some-> text str/split-lines first))

(defn failure-cause
  "The first message beneath a failed result's own: the failed child's root
  cause (`Throwable->map`'s `:cause`), its message, then the last link of the result's own cause chain."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [result]
  (let [evidence (:seon.error/offending result)
        child (:seon.operator/value evidence)]
    (some #(when (and (string? %) (not= % (:seon.error/message result))) %)
          [(get-in child [:seon.error/cause :cause])
           (:seon.error/message child)
           ;; the primary chain's root: suppressed links (flat, naming their
           ;; relation) follow it
           (:seon.error/message (last (remove #(or (:seon.error/suppressed-by %) (:seon.error/cause-of %))
                                              (:seon.error/chain result))))])))

(defn exit-code
  "0 success; 4 failed and the previous program runs again; 5 failed and no JVM of this root runs
  (observed: `live?`); 1 any other failure."
  {:malli/schema [:=> [:cat :map :boolean] [:enum 0 1 4 5]]}
  [result live?]
  (cond
    (:seon.error/message result)
    (cond (true? (get-in result [:seon.error/offending :seon.operator/resumed :seon.operator/ready?])) 4
          (not live?) 5
          :else 1)
    :else 0))

(defn headline
  "One short line naming `request`'s outcome: FAILED with its cause, the
  missing layers and the diagnostic file, or its readiness."
  {:malli/schema [:=> [:cat :map :map [:maybe :string] [:int {:min 0}]] :string]}
  [request result diagnostic-path code]
  (let [command (str (name (:seon.operator/command request))
                     (when (:seon.operator/head? request) " --head"))
        child (get-in result [:seon.error/offending :seon.operator/value])
        missing (not-empty (get-in (or child result) [:seon.boot/readiness :seon.boot/missing-layers]))
        classes (:seon.dev-cache/dependency-classes (or child result))]
    (str "SEON " command " "
         (if (:seon.error/message result)
           (str "FAILED (exit " code
                (case code 4 ", previous program runs" 5 ", no JVM runs" "") "): "
                (first-line (:seon.error/message result))
                (when-let [cause (failure-cause result)] (str " CAUSE: " (first-line cause)))
                (when missing (str " MISSING: " (pr-str missing)))
                (when diagnostic-path (str " DIAGNOSTIC: " diagnostic-path)))
           (str "OK"
                (some->> (get-in result [:seon.operator/source :seon.source/git-sha]) (str " "))
                (some->> (get-in result [:seon.operator/phases :seon.operator/total-ms]) (str " total-ms "))
                (some->> (get-in result [:seon.operator/phases :seon.operator/class-fill-ms]) (str " class-fill-ms "))
                (some->> (get-in result [:seon.boot/readiness :seon.boot/ready-ms]) (str " ready-ms "))
                (when classes (str " classes " (name (:seon.dev-cache/status classes))
                                   (some->> (:seon.dev-cache/reason classes) name (str " ")))))))))

(defn- write-diagnostic!
  "Write a result whole (a failed one, or a started root's detail) to a new file in its cluster's log directory,
  or to the file `SEON_DIAGNOSTIC_FILE` names (a caller that reads the diagnostic back as data); its path."
  {:malli/schema [:=> [:cat :map :map] :string]}
  [request result]
  (let [file (if-let [named (not-empty (System/getenv "SEON_DIAGNOSTIC_FILE"))]
               (io/file named)
               (io/file (:seon.operator/managed-root request) "data/clusters"
                      (or (:seon.boot/cluster-name request) "default") "logs"
                      (str "operator-" (name (:seon.operator/command request)) "-"
                           (.format (java.text.SimpleDateFormat. "yyyyMMdd'T'HHmmss.SSS") (java.util.Date.))
                           ".edn")))]
    (io/make-parents file)
    (spit file (str (pr-str result) "\n"))
    (.getCanonicalPath file)))

(defn- start-summary
  "What root acts on after a successful `start`: sha, pid, timings, the stopped
  processes, counts of captured heads and pruned
  archives, and the file holding the whole result."
  {:malli/schema [:=> [:cat :map :string] :map]}
  [result detail-path]
  (let [phases (:seon.operator/phases result)]
    {:seon.source/git-sha (get-in result [:seon.operator/source :seon.source/git-sha])
     :seon.boot/pid (get-in result [:seon.boot/readiness :seon.boot/pid])
     :seon.boot/ready-ms (get-in result [:seon.boot/readiness :seon.boot/ready-ms])
     :seon.operator/total-ms (:seon.operator/total-ms phases)
     :seon.operator/stopped-processes (:seon.operator/stopped-processes result)
     :seon.operator/captured-heads-count (count (get-in result [:seon.operator/captured-heads :seon.operator/heads]))
     :seon.operator/pruned-archives-count (count (get-in result [:seon.operator/archives :seon.operator/pruned]))
     :seon.operator/detail detail-path}))

(defn -main
  {:malli/schema [:=> [:cat [:* :string]] :nil]}
  [& args]
  (try
    (let [request (parse-argv args)
          result (if (= :help (:seon.operator/command request))
                   (select-keys request [:seon.operator/help])
                   (request! request))
          failed? (:seon.error/message result)
          ;; "No JVM runs" is observed, never inferred from the message.
          code (exit-code result (or (not failed?)
                                     (boolean (seq (selected-processes (:seon.operator/managed-root request))))))]
      (when-not (:seon.operator/help result)
        (binding [*out* *err*]
          (println (headline request result (when failed? (write-diagnostic! request result)) code))
          (flush)))
      ;; A check's test run prints bin/test's own summary and red lines.
      (when-let [summary (or (:seon.operator/test-summary result)
                             (get-in result [:seon.error/offending :seon.operator/test-summary]))]
        (println summary))
      (cond
        (:seon.operator/help result) (println (:seon.operator/help result))
        failed? nil
        (= :logs (:seon.operator/command request)) (print (slurp (:seon.operator.log/path result)))
        (= :open (:seon.operator/command request))
        (let [child (.start (ProcessBuilder. ^java.util.List ["open" (:seon.render.web/url result)]))]
          (when-not (.waitFor child (operator-silence-backstop-ms {}) TimeUnit/MILLISECONDS)
            (reap-child! child (operator-silence-backstop-ms {}) result) (fail! "OS opener did not exit." result))
          (when-not (zero? (.exitValue child)) (fail! "OS opener failed." result)))
        (= :start (:seon.operator/command request)) (prn (start-summary result (write-diagnostic! request result)))
        :else (prn result))
      (shutdown-agents)
      (System/exit code))
    (catch Exception cause
      (binding [*out* *err*] (prn (diagnostic (ex-message cause) (or (ex-data cause) {}) :argv-failed cause)))
      (System/exit 1))))
