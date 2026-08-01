(ns seon.dev.mcp
  "Serve the source-independent development REPL MCP boundary."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io BufferedReader PushbackReader]
           [java.net InetSocketAddress Socket SocketTimeoutException]
           [java.time Instant]))

;; This process is deliberately below the application. It reads endpoint
;; advertisements and speaks io-prepl; it never loads src/ or src-old/.
;; Discovery happens on every call so an already-running MCP process observes
;; cluster starts, stops, and replacements without a restart.

(def project-root
  (or (System/getenv "SEON_PROJECT_ROOT")
      (System/getProperty "user.dir")))

(def own-cluster
  (or (some-> (System/getenv "SEON_CLUSTER_DIR") io/file .getName)
      "default"))

(def default-timeout-ms 30000)
(def connect-timeout-ms 5000)
(def default-output-tokens 4000)
(def max-output-tokens 16000)
(def min-output-tokens 64)
(def max-transport-events 256)
(def max-exception-frames 3)
(def max-form-preview-chars 60)
(def server-info {:name "seon" :version "0.3.0"})

;; [cluster session-id] -> one stateful io-prepl socket and its endpoint.
(def clj-sessions (atom {}))
(def stdout-lock (Object.))
(def ^:dynamic *requested-output-tokens* default-output-tokens)
(def ^:dynamic *debug* (= "1" (System/getenv "DEBUG")))

(defn- output-token-limit
  [requested]
  (min max-output-tokens
       (max min-output-tokens (or requested default-output-tokens))))

(defn- transport-char-limit
  []
  (* 4 (output-token-limit *requested-output-tokens*)))

(defn- event-char-limit
  []
  (max 1 (quot (transport-char-limit) 2)))

(defn- bounded-text
  [value]
  (let [value (str value)
        limit (transport-char-limit)]
    (if (<= (count value) limit)
      value
      (str (subs value 0 limit) "\n… output truncated by MCP bridge"))))

(defn- safe-prefix
  [value retained]
  (let [retained (min (count value) (max 0 retained))
        retained (if (and (pos? retained)
                          (Character/isHighSurrogate
                           (.charAt ^String value (dec retained))))
                   (dec retained)
                   retained)]
    (subs value 0 retained)))

(defn- trim-event-value
  [value event-index retained]
  (let [path [:seon.dev.mcp/events event-index]
        event (get-in value path)
        original (:val event)
        total (or (:seon.dev.mcp/total-chars event) (count original))
        kept (safe-prefix original retained)]
    (-> value
        (assoc-in (conj path :val) kept)
        (assoc-in (conj path :seon.dev.mcp/truncated?) true)
        (assoc-in (conj path :seon.dev.mcp/retained-chars) (count kept))
        (assoc-in (conj path :seon.dev.mcp/total-chars) total))))

(defn- largest-event-value
  [value]
  (->> (:seon.dev.mcp/events value)
       (keep-indexed
        (fn [index event]
          (when (and (string? (:val event)) (seq (:val event)))
            {:seon.dev.mcp/index index
             :seon.dev.mcp/raw-chars (count (:val event))
             :seon.dev.mcp/encoded-chars
             (count (json/generate-string (:val event)))})))
       (sort-by (juxt (comp - :seon.dev.mcp/encoded-chars)
                      :seon.dev.mcp/index))
       first))

(defn- encode-structured-content
  [value]
  (let [limit (transport-char-limit)]
    (loop [value value]
      (let [encoded (json/generate-string value)
            overflow (- (count encoded) limit)]
        (if-not (pos? overflow)
          encoded
          (if-let [{index :seon.dev.mcp/index
                    raw-chars :seon.dev.mcp/raw-chars}
                   (largest-event-value value)]
            (recur (trim-event-value value index
                                     (- raw-chars (max 1 overflow))))
            ;; At the minimum request, the envelope plus truncation metadata
            ;; can exceed the estimate even with empty values. Structure is
            ;; more important than pretending the payload fit.
            encoded))))))

(defn- content-text
  [value]
  (if (string? value)
    (bounded-text value)
    (encode-structured-content value)))

(defn- log-info
  [& arguments]
  (binding [*out* *err*]
    (apply println "[mcp INFO]" arguments)
    (flush)))

(defn- log-debug
  [& arguments]
  (when *debug*
    (binding [*out* *err*]
      (apply println "[mcp DEBUG]" arguments)
      (flush))))

(defn- log-error
  [& arguments]
  (binding [*out* *err*]
    (apply println "[mcp ERR ]" arguments)
    (flush)))

(defn- mcp-success
  [value]
  {:content [{:type "text"
              :text (content-text value)}]})

(defn- mcp-error
  [error]
  (let [data (if (map? error)
               error
               {:seon.dev.mcp/error (str error)})]
    {:content [{:type "text"
                :text (content-text data)}]
     :isError true}))

;;; ---------------------------------------------------------------------------
;;; Advertisement discovery
;;; ---------------------------------------------------------------------------

(def ^:private cluster-name-pattern
  #"\A[A-Za-z0-9](?:[A-Za-z0-9._-]{0,62})\z")

(defn- valid-cluster?
  [cluster]
  (boolean (and (string? cluster)
                (re-matches cluster-name-pattern cluster))))

(defn- cluster-root
  []
  (io/file project-root "data" "clusters"))

(defn- advertisement-file
  [cluster]
  (io/file (cluster-root) cluster "prepl.edn"))

(defn- process-root-store-directory?
  [directory]
  ;; `seon.cluster.store/lock-file` derives the process-root flock as the
  ;; canonical store path plus ".lock". Cluster directories have no such
  ;; sibling; this identifies the role without reserving a directory name.
  (try
    (.isFile (io/file (str (.getCanonicalPath ^java.io.File directory)
                           ".lock")))
    (catch Throwable _
      false)))

(defn- process-start-instant
  [pid]
  (try
    (let [optional (java.lang.ProcessHandle/of (long pid))]
      (when (.isPresent optional)
        (let [handle (.get optional)
              start (.startInstant (.info handle))]
          (when (and (.isAlive handle) (.isPresent start))
            (.get start)))))
    (catch Throwable _
      nil)))

(defn- matching-live-process?
  [advertisement]
  (let [recorded (:seon.boot/start-instant advertisement)
        current (process-start-instant (:seon.boot/pid advertisement))]
    (and (inst? recorded)
         current
         (= (inst-ms recorded)
            (.toEpochMilli ^Instant current)))))

(defn- valid-advertisement?
  [cluster advertisement]
  (and (map? advertisement)
       (= cluster (:seon.boot/cluster-name advertisement))
       (string? (:seon.boot/prepl-host advertisement))
       (not (str/blank? (:seon.boot/prepl-host advertisement)))
       (integer? (:seon.boot/prepl-port advertisement))
       (<= 1 (:seon.boot/prepl-port advertisement) 65535)
       (integer? (:seon.boot/pid advertisement))
       (pos? (:seon.boot/pid advertisement))
       (inst? (:seon.boot/start-instant advertisement))))

(defn- read-advertisement
  [cluster]
  (let [file (advertisement-file cluster)
        path (.getPath file)]
    (if-not (.isFile file)
      {:seon.dev.mcp/cluster cluster
       :seon.dev.mcp/path path
       :seon.dev.mcp/state :missing}
      (try
        (let [advertisement (edn/read-string (slurp file))]
          (cond
            (not (valid-advertisement? cluster advertisement))
            {:seon.dev.mcp/cluster cluster
             :seon.dev.mcp/path path
             :seon.dev.mcp/state :invalid
             :seon.dev.mcp/error
             "Advertisement lacks a valid cluster, pid, start instant, or prepl endpoint."}

            (not (matching-live-process? advertisement))
            {:seon.dev.mcp/cluster cluster
             :seon.dev.mcp/path path
             :seon.dev.mcp/state :stale
             :seon.dev.mcp/advertisement advertisement}

            :else
            {:seon.dev.mcp/cluster cluster
             :seon.dev.mcp/path path
             :seon.dev.mcp/state :alive
             :seon.dev.mcp/advertisement advertisement}))
        (catch Throwable throwable
          {:seon.dev.mcp/cluster cluster
           :seon.dev.mcp/path path
           :seon.dev.mcp/state :unreadable
           :seon.dev.mcp/error (ex-message throwable)})))))

(defn- advertisement-rows
  []
  (try
    (let [root (cluster-root)
          directories (when (.isDirectory root) (.listFiles root))]
      (->> directories
           (filter #(.isDirectory ^java.io.File %))
           (remove process-root-store-directory?)
           (map #(.getName ^java.io.File %))
           (filter valid-cluster?)
           (mapv read-advertisement)
           (sort-by (juxt #(not= :alive (:seon.dev.mcp/state %))
                          :seon.dev.mcp/cluster))
           vec))
    (catch Throwable throwable
      [{:seon.dev.mcp/cluster "<discovery>"
        :seon.dev.mcp/path (.getPath (cluster-root))
        :seon.dev.mcp/state :unreadable
        :seon.dev.mcp/error (ex-message throwable)}])))

(defn- old-writer-port-file
  [cluster]
  (let [override (when (= cluster own-cluster)
                   (System/getenv "SEON_WRITER_REPL_PORT_FILE"))
        file (io/file
              (or override
                  (str "tmp/seon-writer-repl-port-" cluster)))]
    (if (.isAbsolute file)
      file
      (io/file project-root (.getPath file)))))

(defn- old-writer-endpoint
  [cluster]
  (let [file (old-writer-port-file cluster)]
    (try
      (when (.isFile file)
        (let [port (some-> (slurp file) str/trim parse-long)]
          (when (and port (<= 1 port 65535))
            {:host "127.0.0.1"
             :port port
             :seon.dev.mcp/source :old-writer-port-file
             :seon.dev.mcp/path (.getPath file)})))
      (catch Throwable _
        nil))))

(defn- start-remedy
  [cluster]
  (str "Start the cluster with: bin/seon start " cluster "."))

(defn- endpoint-error
  [row]
  (let [cluster (:seon.dev.mcp/cluster row)
        state (:seon.dev.mcp/state row)]
    (ex-info
     (str "No live CLJ REPL is available for cluster '" cluster
          "'; its advertisement is " (name state) ". "
          (start-remedy cluster))
     (cond-> {:seon.dev.mcp/failure :repl-unavailable
              :seon.dev.mcp/cluster cluster
              :seon.dev.mcp/advertisement-state state
              :seon.dev.mcp/advertisement-file (:seon.dev.mcp/path row)
              :seon.dev.mcp/remedy (start-remedy cluster)}
       (:seon.dev.mcp/error row)
       (assoc :seon.dev.mcp/advertisement-error
              (:seon.dev.mcp/error row))))))

(defn- read-clj-endpoint
  [cluster]
  (when-not (valid-cluster? cluster)
    (throw (ex-info "Invalid cluster name."
                    {:seon.dev.mcp/failure :invalid-cluster
                     :seon.dev.mcp/cluster cluster})))
  (let [{state :seon.dev.mcp/state
         advertisement :seon.dev.mcp/advertisement
         :as row}
        (read-advertisement cluster)]
    (case state
      :alive
      {:host (:seon.boot/prepl-host advertisement)
       :port (:seon.boot/prepl-port advertisement)
       :pid (:seon.boot/pid advertisement)
       :start-instant (:seon.boot/start-instant advertisement)
       :seon.dev.mcp/source :fresh-advertisement}

      :missing
      (or (old-writer-endpoint cluster)
          (throw (endpoint-error row)))

      (throw (endpoint-error row)))))

;;; ---------------------------------------------------------------------------
;;; io-prepl transport
;;; ---------------------------------------------------------------------------

(defn- close-clj-session!
  [key]
  (when-let [{:keys [socket]} (get @clj-sessions key)]
    (swap! clj-sessions dissoc key)
    (try
      (.close ^Socket socket)
      (catch Throwable _
        nil))))

(defn- open-clj-session!
  [cluster session-id endpoint]
  (let [socket (Socket.)]
    (try
      (.connect socket
                (InetSocketAddress. ^String (:host endpoint)
                                    (int (:port endpoint)))
                connect-timeout-ms)
      (let [session {:socket socket
                     :reader (PushbackReader. (io/reader socket))
                     :writer (io/writer socket)
                     :endpoint endpoint
                     :cluster cluster
                     :session-id session-id}]
        (swap! clj-sessions assoc [cluster session-id] session)
        session)
      (catch Throwable throwable
        (try (.close socket) (catch Throwable _ nil))
        (throw throwable)))))

(defn- current-clj-session!
  [cluster session-id]
  (let [key [cluster session-id]
        endpoint (read-clj-endpoint cluster)
        cached (get @clj-sessions key)]
    (cond
      (and cached (= endpoint (:endpoint cached)))
      cached

      cached
      (do
        (close-clj-session! key)
        (if (= "default" session-id)
          (open-clj-session! cluster session-id endpoint)
          (throw
           (ex-info "Named CLJ session ended with the cluster restart."
                    {:seon.dev.mcp/failure :session-lost
                     :seon.dev.mcp/cluster cluster
                     :seon.dev.mcp/session-id session-id
                     :seon.dev.mcp/retry-with-new-session true}))))

      :else
      (open-clj-session! cluster session-id endpoint))))

(defn- reader-whitespace?
  [character]
  (or (= (int \,) character)
      (Character/isWhitespace (char character))))

(defn- skip-comment!
  [^LineNumberingPushbackReader reader]
  (loop []
    (let [character (.read reader)]
      (when-not (or (= -1 character) (= (int \newline) character))
        (recur)))))

(defn- next-form-position!
  [^LineNumberingPushbackReader reader]
  (loop []
    (let [character (.read reader)]
      (cond
        (= -1 character)
        nil

        (reader-whitespace? character)
        (recur)

        (= (int \;) character)
        (do (skip-comment! reader) (recur))

        :else
        (do
          (.unread reader character)
          {:seon.dev.mcp/line (.getLineNumber reader)
           :seon.dev.mcp/column (.getColumnNumber reader)})))))

(defn- form-preview
  [source]
  (let [normalized (-> source str/trim (str/replace #"\s+" " "))]
    (if (<= (count normalized) max-form-preview-chars)
      normalized
      (str (subs normalized 0 (dec max-form-preview-chars)) "…"))))

(defn- require-single-clj-form!
  [code]
  (when-not (string? code)
    (throw (ex-info "CLJ evaluation requires a string form."
                    {:seon.dev.mcp/failure :invalid-form})))
  (try
    (let [eof (Object.)
          reader (LineNumberingPushbackReader.
                  (java.io.StringReader. code))]
      (binding [*read-eval* false]
        (let [first-form (read {:eof eof :read-cond :allow} reader)]
          (when (identical? eof first-form)
            (throw (ex-info "CLJ evaluation requires one form."
                            {:seon.dev.mcp/failure :empty-form})))
          (when-let [{line :seon.dev.mcp/line
                      column :seon.dev.mcp/column
                      :as position}
                     (next-form-position! reader)]
            (.captureString reader)
            (let [second-form (read {:eof eof :read-cond :allow} reader)
                  preview (form-preview (.getString reader))]
              (when-not (identical? eof second-form)
                (throw
                 (ex-info
                  (str "CLJ evaluation accepts exactly one form; the second "
                       "form starts at line " line ", column " column ": "
                       preview ". Wrap sequences in `(do ...)`.")
                  (assoc position
                         :seon.dev.mcp/failure :multiple-forms
                         :seon.dev.mcp/preview preview))))))
          code)))
    (catch clojure.lang.ExceptionInfo exception
      (if (:seon.dev.mcp/failure (ex-data exception))
        (throw exception)
        (throw (ex-info "CLJ evaluation form is unreadable."
                        {:seon.dev.mcp/failure :invalid-form
                         :seon.dev.mcp/reader-error (ex-message exception)}
                        exception))))
    (catch Throwable throwable
      (throw (ex-info "CLJ evaluation form is unreadable."
                      {:seon.dev.mcp/failure :invalid-form
                       :seon.dev.mcp/reader-error (ex-message throwable)}
                      throwable)))))

(defn- bounded-event
  [event limit]
  (if-let [value (when (string? (:val event)) (:val event))]
    (let [kept (safe-prefix value (min limit (count value)))]
      [(cond-> (assoc event :val kept)
         (< (count kept) (count value))
         (assoc :seon.dev.mcp/truncated? true
                :seon.dev.mcp/retained-chars (count kept)
                :seon.dev.mcp/total-chars (count value)))
       (count kept)])
    [event 0]))

(defn- first-party-frame?
  [frame]
  (let [class-name (str (first frame))]
    (or (str/starts-with? class-name "seon.")
        (str/starts-with? class-name "user$eval")
        (str/starts-with? class-name "repl_context$eval")
        (str/starts-with? class-name "repl-context$eval"))))

(defn- project-exception-value
  [value]
  (let [throwable-map
        (try
          (if (string? value)
            (edn/read-string {:default (fn [tag tagged-value]
                                         [tag tagged-value])}
                             value)
            value)
          (catch Throwable _ nil))]
    (if-not (map? throwable-map)
      value
      (let [trace (vec (:trace throwable-map))
            frames (into []
                         (comp (filter first-party-frame?)
                               (take max-exception-frames))
                         trace)]
        (pr-str
         (array-map
          :cause (:cause throwable-map)
          :phase (:phase throwable-map)
          :via (mapv #(select-keys % [:type :message])
                     (:via throwable-map))
          :trace frames
          :seon.dev.mcp/frames-omitted (- (count trace)
                                          (count frames))))))))

(defn- project-exception-event
  [event]
  (if (:exception event)
    (update event :val project-exception-value)
    event))

(defn- collect-prepl-response!
  [{:keys [socket reader]} timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [events []
           dropped 0]
      (let [remaining-ms (- deadline (System/currentTimeMillis))]
        (when-not (pos? remaining-ms)
          (throw (SocketTimeoutException. "io-prepl deadline exceeded")))
        (.setSoTimeout ^Socket socket (int remaining-ms))
        (let [event (edn/read {:eof ::eof} reader)
              event (if (map? event)
                      (project-exception-event event)
                      event)]
          (cond
            (= ::eof event)
            (throw (ex-info "Cluster closed the io-prepl session."
                            {:seon.dev.mcp/failure :transport-closed}))

            (not (map? event))
            (throw (ex-info "Cluster emitted malformed io-prepl data."
                            {:seon.dev.mcp/failure :malformed-prepl
                             :seon.dev.mcp/value (pr-str event)}))

            :else
            (let [[event _] (bounded-event event (event-char-limit))
                  terminal? (= :ret (:tag event))
                  retain? (or terminal?
                              (< (count events) (dec max-transport-events)))
                  events (cond-> events retain? (conj event))
                  dropped (if retain? dropped (inc dropped))]
              (if terminal?
                (cond-> events
                  (pos? dropped)
                  (conj {:tag :seon.dev.mcp/dropped-events
                         :count dropped}))
                (recur events dropped)))))))))

(defn- execute-clj-eval
  [{:keys [code cluster session_id timeout_ms]}]
  (let [cluster (or cluster own-cluster)
        session-id (or session_id "default")
        timeout-ms (min 120000 (max 1 (or timeout_ms default-timeout-ms)))
        key [cluster session-id]
        validation (try
                     {:seon.dev.mcp/code
                      (require-single-clj-form! code)}
                     (catch Throwable throwable
                       {:seon.dev.mcp/error throwable}))]
    (if-let [throwable (:seon.dev.mcp/error validation)]
      (mcp-error
       (merge {:seon.dev.mcp/failure :invalid-form
               :seon.dev.mcp/runtime "clj"
               :seon.dev.mcp/cluster cluster
               :seon.dev.mcp/session-id session-id
               :seon.dev.mcp/error (ex-message throwable)}
              (ex-data throwable)))
      (try
        (let [{:keys [writer] :as session}
              (current-clj-session! cluster session-id)]
          (.write ^java.io.Writer
                  writer
                  (str (:seon.dev.mcp/code validation) "\n"))
          (.flush ^java.io.Writer writer)
          (let [events (collect-prepl-response! session timeout-ms)
                terminal (some #(when (= :ret (:tag %)) %) events)
                response {:seon.dev.mcp/runtime "clj"
                          :seon.dev.mcp/cluster cluster
                          :seon.dev.mcp/session-id session-id
                          :seon.dev.mcp/events events}]
            (if (:exception terminal)
              (mcp-error (assoc response :seon.dev.mcp/failure :evaluation))
              (mcp-success response))))
        (catch SocketTimeoutException _
          (close-clj-session! key)
          (mcp-error {:seon.dev.mcp/failure :timeout
                      :seon.dev.mcp/runtime "clj"
                      :seon.dev.mcp/cluster cluster
                      :seon.dev.mcp/session-id session-id
                      :seon.dev.mcp/timeout-ms timeout-ms}))
        (catch Throwable throwable
          (close-clj-session! key)
          (mcp-error
           (merge {:seon.dev.mcp/failure
                   (or (:seon.dev.mcp/failure (ex-data throwable))
                       :transport)
                   :seon.dev.mcp/runtime "clj"
                   :seon.dev.mcp/cluster cluster
                   :seon.dev.mcp/session-id session-id
                   :seon.dev.mcp/error (ex-message throwable)}
                  (ex-data throwable))))))))

(defn- execute-list-sessions
  [_]
  (mcp-success
   (str "CLJ io-prepl sessions: "
        (if (seq @clj-sessions)
          (str/join ", "
                    (sort (map (fn [[cluster session-id]]
                                 (str cluster "/" session-id))
                               (keys @clj-sessions))))
          "(none)"))))

(defn- row-status-line
  [{cluster :seon.dev.mcp/cluster
    state :seon.dev.mcp/state
    advertisement :seon.dev.mcp/advertisement
    path :seon.dev.mcp/path
    error :seon.dev.mcp/error}]
  (str "  " cluster
       " state=" (name state)
       (when-let [pid (:seon.boot/pid advertisement)]
         (str " pid=" pid))
       (when-let [port (:seon.boot/prepl-port advertisement)]
         (str " prepl=" (:seon.boot/prepl-host advertisement) ":" port))
       (when-let [url (:seon.render.web/url advertisement)]
         (str " url=" url))
       " advertisement=" path
       (when error (str " error=" error))))

(defn- execute-runtime-status
  [_]
  (let [rows (advertisement-rows)
        missing (filterv #(= :missing (:seon.dev.mcp/state %)) rows)
        visible (remove #(= :missing (:seon.dev.mcp/state %)) rows)
        status-lines
        (cond-> (mapv row-status-line visible)
          (seq missing)
          (conj (str "  " (count missing)
                     " clusters with no advertisement: "
                     (str/join ", "
                               (map :seon.dev.mcp/cluster missing)))))]
    (mcp-success
     (str "fresh JVM clusters:\n"
          (if (seq status-lines)
            (str/join "\n" status-lines)
            "  (none)")
          "\nCLJ io-prepl sessions: " (count @clj-sessions)))))

(def tools
  [{:name "eval_clj"
    :description "Evaluate exactly one Clojure form through the selected cluster's advertised io-prepl. The session retains *1/*2; narrow oversized results or raise max_output_tokens up to 16000. Discovery runs on every call; the default session reconnects after cluster restart."
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "Exactly one Clojure form; wrap an intentional sequence in (do ...)."}
                               :cluster {:type "string" :description "Cluster name. Fresh discovery reads data/clusters/<name>/prepl.edn; defaults to this MCP server's own cluster."}
                               :session_id {:type "string" :description "Stateful io-prepl session id. Defaults to 'default'."}
                               :timeout_ms {:type "integer" :minimum 1 :maximum 120000}
                               :max_output_tokens {:type "integer" :minimum 64 :maximum 16000}}
                  :required ["code"]}}

   {:name "list_sessions"
    :description "List the bridge's active CLJ io-prepl sessions."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name "runtime_status"
    :description "Report fresh JVM clusters from advertisement files and the current bridge session count."
    :inputSchema {:type "object" :properties {} :required []}}])

(defn- execute-tool
  [name arguments]
  (let [arguments (reduce-kv (fn [result key value]
                               (assoc result (keyword key) value))
                             {}
                             (or arguments {}))]
    (binding [*requested-output-tokens*
              (output-token-limit (:max_output_tokens arguments))]
      (case name
        "eval_clj" (execute-clj-eval arguments)
        "runtime_status" (execute-runtime-status arguments)
        "list_sessions" (execute-list-sessions arguments)
        (throw (ex-info (str "Unknown tool: " name)
                        {:seon.dev.mcp/tool name}))))))

;;; ---------------------------------------------------------------------------
;;; JSON-RPC
;;; ---------------------------------------------------------------------------

(defn- send-response
  [response]
  (let [encoded (json/generate-string response)]
    (log-debug "<<" encoded)
    (locking stdout-lock
      (println encoded)
      (flush))))

(defn- send-result
  [id result]
  (send-response {:jsonrpc "2.0" :id id :result result}))

(defn- send-error
  [id code message]
  (send-response {:jsonrpc "2.0"
                  :id id
                  :error {:code code :message message}}))

(defn- handle-request
  [{:keys [method params id] :as request}]
  (log-debug ">>" (pr-str request))
  (try
    (case method
      "initialize"
      (send-result id {:protocolVersion "2024-11-05"
                       :capabilities {:tools {}}
                       :serverInfo server-info})

      "notifications/initialized"
      nil

      "tools/list"
      (send-result id {:tools tools})

      "tools/call"
      (let [{:keys [name arguments]} params]
        (send-result id (execute-tool name arguments)))

      (when id
        (send-error id -32601 (str "Method not found: " method))))
    (catch Throwable throwable
      (log-error "request failed:" (ex-message throwable))
      (when id
        (send-result id
                     (mcp-error
                      (merge {:seon.dev.mcp/failure :request
                              :seon.dev.mcp/error (ex-message throwable)}
                             (ex-data throwable))))))))

(def parent-pid
  (let [parent (.parent (java.lang.ProcessHandle/current))]
    (when (.isPresent parent)
      (.pid (.get parent)))))

(defn- start-parent-watchdog!
  []
  (when parent-pid
    (future
      (try
        (loop []
          (Thread/sleep 5000)
          (let [handle (java.lang.ProcessHandle/of parent-pid)]
            (if (and (.isPresent handle)
                     (.isAlive (.get handle)))
              (recur)
              (System/exit 0))))
        (catch Throwable throwable
          (log-error "watchdog failed:" (ex-message throwable)))))))

(defn -main
  "Serve newline-delimited MCP JSON-RPC on standard I/O."
  [& _]
  (log-info "Seon MCP server starting; project=" project-root)
  (start-parent-watchdog!)
  (let [reader (BufferedReader. *in*)]
    (loop []
      (when-let [line (.readLine reader)]
        (when-not (str/blank? line)
          (try
            (handle-request (json/parse-string line true))
            (catch Throwable throwable
              (log-error "parse error:" (ex-message throwable))
              (send-error nil -32700 "Parse error"))))
        (recur)))))
