(ns seon.operator
  "Source-independent CLI client: argv data, one request, exact process identity."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
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
      :init (operator-boot-bound-ms manifest)
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
    :seon.error/offending evidence
    :seon.error/data (merge evidence {:seon.error/layer :seon.operator/client})})
  ([message evidence cause throwable]
   (refusal/diagnostic
    (assoc (diagnostic message evidence cause) :seon.error/throwable throwable))))

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

(defn- launch-form [request port]
  ;; Only Clojure core loads before opening and reporting the diagnostic REPL.
  ;; The second callback value is terminal boot evidence, never a file poll.
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
          (let [result# (try
                          (require '~'seon.cluster.boot)
                          ((resolve '~'seon.cluster.boot/request!)
                           (assoc '~request :seon.boot/prepl-server listener#))
                          (catch Throwable cause#
                            {:seon.error/message (ex-message cause#)
                             :seon.error/cause (Throwable->map cause#)}))]
            (.write writer# (str (pr-str result#) "\n")) (.flush writer#)
            (when (and (:seon.error/message result#) (.isClosed listener#))
              (System/exit 1))))
        @(promise)))))

(defn- launch-child!
  "Launch one JVM whose program is the checkout at `source` and return its
  terminal boot value with the coordinates it advertised."
  [request source]
  (let [root (:seon.operator/managed-root request)
        name (:seon.boot/cluster-name request)
        log (io/file root "data/clusters" name "logs/seon.log")
        bound (operator-silence-backstop-ms (:seon.config/manifest request))]
    (io/make-parents log)
    (with-open [callback (ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))]
      (let [argv ["clojure" (str "-J-Dseon.operator.root=" root)
                  (str "-J-Dseon.repository.root=" source)
                  "-M:dev:test" "-e" (launch-form request (.getLocalPort callback))]
            builder (doto (ProcessBuilder. ^java.util.List argv)
                      (.directory (io/file source))
                      (.redirectErrorStream true)
                      (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log)))
            _ (.putAll (.environment builder) (child-environment root))
            child (.start builder)
            exited (.thenApply (.onExit child) (reify Function (apply [_ _] ::exited)))
            accepted (CompletableFuture/supplyAsync (reify Supplier (get [_] (.accept callback))))
            event (.get (CompletableFuture/anyOf (into-array CompletableFuture [accepted exited]))
                        bound TimeUnit/MILLISECONDS)]
        (when (= ::exited event) (fail! "Child exited before opening its REPL." {:seon.operator/log (str log)}))
        (with-open [socket event reader (java.io.BufferedReader. (io/reader socket))]
          (.setSoTimeout socket bound)
          (let [coordinates (edn/read-string (.readLine reader))
                _ (binding [*out* *err*] (println "REPL" (pr-str coordinates) "log" (str log)))
                ;; Cold indexing has the publication's declared bound, independently of socket silence.
                boot-bound (operator-boot-bound-ms {})
                _ (.setSoTimeout socket boot-bound)
                terminal (CompletableFuture/supplyAsync
                          (reify Supplier (get [_] (if-let [line (.readLine reader)]
                                                    (edn/read-string {:default tagged-literal} line) ::eof))))
                value (.get (CompletableFuture/anyOf (into-array CompletableFuture [terminal exited]))
                            boot-bound TimeUnit/MILLISECONDS)]
            (when-not (map? value)
              (fail! "Child exited or closed its boot channel before readiness."
                     {:seon.operator/event value :seon.operator/log (str log)
                      :seon.boot/advertisement coordinates}))
            (when (some #(and (map? %)
                              (= :seon.cluster.store/held-elsewhere (:seon.cluster.store/rule %)))
                        (tree-seq coll? seq value))
              (.get (.onExit child) bound TimeUnit/MILLISECONDS))
            {:seon.operator/value value :seon.boot/advertisement coordinates}))))))

(defn launch! [request]
  (:seon.operator/value (launch-child! request (repository-root))))

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

(defn committed-source!
  "The HEAD program as a directory of committed bytes, built once per commit."
  [root]
  (let [repository (repository-root)
        sha (str/trim (command! ["git" "rev-parse" "HEAD"] repository 30000))
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
                    (let [checkout (io/file repository path)
                          head (when (.isDirectory checkout)
                                 (str/trim (command! ["git" "rev-parse" "HEAD"] checkout 30000)))
                          clean? (and (= head pin)
                                      (str/blank? (command! ["git" "status" "--porcelain"] checkout 30000)))
                          placed (io/file staging path)]
                      (fs/delete-tree placed)
                      (if clean?
                        (do (io/make-parents placed)
                            (fs/create-sym-link placed (.getCanonicalFile checkout))
                            {:path path :pin pin :placed :linked})
                        (do (extract-archive! checkout pin placed)
                            {:path path :pin pin :placed :archived :checkout-head head}))))
                  (gitlinks repository sha))]
        (fs/move staging target {:atomic-move true})
        {:seon.source/git-sha sha :seon.operator/source-root (.getCanonicalPath target)
         :seon.operator/source-built? true :seon.operator/submodules submodules}))))

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
                                 source)
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

(defn nuke!
  "Delete the store and rebuild `default` from committed HEAD. Never leaves a
  JVM with a deleted store: readiness, or no JVM and every attempt's cause."
  [request]
  (let [began (System/nanoTime)
        source (committed-source! (:seon.operator/managed-root request))
        source-ms (elapsed-ms began)
        attempts (loop [attempts []]
                   (let [result (nuke-attempt! request (:seon.operator/source-root source))
                         attempts (conj attempts result)]
                     (if (and (:seon.operator/failed-attempt result) (< (count attempts) 2))
                       (recur attempts)
                       attempts)))
        final (peek attempts)
        failures (mapv :seon.operator/failed-attempt (filter :seon.operator/failed-attempt attempts))
        report {:seon.operator/source source
                :seon.operator/source-ms source-ms
                :seon.operator/total-ms (elapsed-ms began)}]
    (if (:seon.operator/failed-attempt final)
      (assoc (diagnostic "Nuclear rebuild from committed HEAD failed twice; no JVM of this root remains."
                         (merge report {:seon.operator/failed-attempts failures}) :client-failed)
             :seon.operator/process-exit? true)
      (cond-> (merge final report)
        (seq failures) (assoc :seon.operator/failed-attempts failures)))))

(defn request! [request]
  (try
    (case (:seon.operator/command request)
      :start (if (seq (selected-processes (:seon.operator/managed-root request)))
               (connected! request) (launch! request))
      :stop (if (:seon.operator/force? request) (force-stop! request) (connected! request))
      :down (down! request (selected-processes (:seon.operator/managed-root request)))
      ;; `reset` stays the nuke until the fresh-branch reset (plan §7) replaces it.
      (:reset :nuke) (do (when-not (:seon.operator/force? request)
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
            (:status :down :reset :nuke)
            (do (when (seq positionals) (fail! "Command takes no cluster name." request))
                (when (and (#{:reset :nuke} command) (not (:seon.operator/force? request)))
                  (fail! "Nuclear rebuild requires --force." request)) request)
            (:start :init :open :stop :logs)
            (do (when (< 1 (count positionals)) (fail! "Command takes at most one cluster name." request))
                (cond-> request
                  (or (seq positionals) (#{:start :logs} command))
                  (assoc :seon.boot/cluster-name (valid-name (or (first positionals) "default")))))
            (fail! "Unknown command." request)))))))

(defn -main [& args]
  (try
    (let [request (parse-argv args)
          result (if (= :help (:seon.operator/command request))
                   {:seon.operator/help "seon [--root PATH] start [NAME] [--config PATH] | init [NAME --force | --dev NAME] [--changed PATH...] | status [--verbose] | open [NAME] | stop [NAME] | down [--force] | reset --force | nuke --force | logs [NAME] | config apply [NAME] PATH | export PATH"}
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
      (shutdown-agents)
      (System/exit (if (:seon.error/message result) 1 0)))
    (catch Exception cause
      (binding [*out* *err*] (prn (diagnostic (ex-message cause) (or (ex-data cause) {}) :argv-failed cause)))
      (System/exit 1))))
