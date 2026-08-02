(ns seon.dev.mcp
  "Serve the source-independent development REPL MCP boundary."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io BufferedReader PushbackReader]
           [java.net InetSocketAddress Socket SocketTimeoutException]
           [java.nio.file FileVisitOption Files LinkOption]))

;; This process is deliberately below the application. It asks the fresh
;; operator for its root-scoped observations and speaks io-prepl; it never
;; loads src/ or src-old/ into the bridge process. Discovery happens on every
;; call so an already-running MCP process observes cluster starts, stops, and
;; replacements without a restart.

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
(def first-party-source-directories ["src" "test"])
(def server-info {:name "seon" :version "0.4.0"})
(def minimum-identity-prefix-chars (quot max-form-preview-chars 2))

;; The output-token estimate cannot be smaller than the bridge's required
;; terminal identity plus explicit truncation evidence. Derive that structural
;; floor from the encoded shape instead of tuning a second character constant.
(def minimum-structured-transport-chars
  (count
   (json/generate-string
    {:seon.dev.mcp/runtime "clj"
     :seon.dev.mcp/cluster
     (apply str (repeat minimum-identity-prefix-chars "c"))
     :seon.dev.mcp/session-id
     (apply str (repeat minimum-identity-prefix-chars "s"))
     :seon.dev.mcp/events
     [{:tag :ret
       :val ""
       :ns (apply str (repeat minimum-identity-prefix-chars "n"))
       :ms Long/MAX_VALUE
       :form (apply str (repeat minimum-identity-prefix-chars "f"))
       :exception true
       :seon.dev.mcp/truncated? true
       :seon.dev.mcp/retained-chars 0
       :seon.dev.mcp/total-chars (* 4 max-output-tokens)}]})))

;; [root cluster session-id] -> one stateful io-prepl socket and its endpoint.
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
  (max minimum-structured-transport-chars
       (* 4 (output-token-limit *requested-output-tokens*))))

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

(defn- string-leaves
  ([value]
   (string-leaves [] value))
  ([path value]
   (cond
     (string? value)
     [{:seon.dev.mcp/path path
       :seon.dev.mcp/raw-chars (count value)
       :seon.dev.mcp/encoded-chars
       (count (json/generate-string value))}]

     (map? value)
     (into []
           (mapcat (fn [[field-key child]]
                     (string-leaves (conj path field-key) child)))
           value)

     (vector? value)
     (into []
           (mapcat (fn [[index child]]
                     (string-leaves (conj path index) child)))
           (map-indexed vector value))

     :else
     [])))

(defn- largest-string-value
  [value]
  (->> (string-leaves value)
       (filter (comp pos? :seon.dev.mcp/raw-chars))
       (sort-by (juxt (comp - :seon.dev.mcp/encoded-chars)
                      (comp pr-str :seon.dev.mcp/path)))
       first))

(defn- event-value-path?
  [path]
  (and (= 3 (count path))
       (= :seon.dev.mcp/events (first path))
       (integer? (second path))
       (= :val (peek path))))

(defn- trim-string-value
  [value path retained]
  (if (event-value-path? path)
    (trim-event-value value (second path) retained)
    (let [parent-path (pop path)
          field (peek path)
          original (get-in value path)
          previous (get-in value
                           (conj parent-path
                                 :seon.dev.mcp/truncated-fields field))
          total (or (second previous) (count original))
          kept (safe-prefix original retained)
          trimmed (assoc-in value path kept)]
      (if (map? (get-in value parent-path))
        (assoc-in trimmed
                  (conj parent-path :seon.dev.mcp/truncated-fields field)
                  [(count kept) total])
        trimmed))))

(defn- droppable-event-index
  [value]
  (first
   (keep-indexed (fn [index event]
                   (when (not= :ret (:tag event)) index))
                 (:seon.dev.mcp/events value))))

(defn- drop-event
  [value index]
  (-> value
      (update :seon.dev.mcp/events
              #(into (subvec % 0 index) (subvec % (inc index))))
      (update :seon.dev.mcp/events-omitted (fnil inc 0))))

(defn- encode-structured-content
  [value]
  (let [limit (transport-char-limit)]
    (loop [value value]
      (let [encoded (json/generate-string value)
            overflow (- (count encoded) limit)]
        (if-not (pos? overflow)
          encoded
          (if-let [{path :seon.dev.mcp/path
                    raw-chars :seon.dev.mcp/raw-chars}
                   (largest-string-value value)]
            (recur (trim-string-value value path
                                      (- raw-chars (max 1 overflow))))
            (if-let [event-index (droppable-event-index value)]
              (recur (drop-event value event-index))
              ;; The bridge's fixed terminal envelope fits the advertised
              ;; minimum. Reaching this arm means a future non-string shape
              ;; grew without acquiring its own projection contract.
              encoded)))))))

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
;;; Operator-derived discovery
;;; ---------------------------------------------------------------------------

(def ^:private cluster-name-pattern
  #"\A[A-Za-z0-9](?:[A-Za-z0-9._-]{0,62})\z")

(defn- valid-cluster?
  [cluster]
  (boolean (and (string? cluster)
                (re-matches cluster-name-pattern cluster))))

(defn- canonical-root
  [root]
  (.getCanonicalPath (io/file (or root project-root))))

(defn- operator-private
  [var-symbol & arguments]
  ;; The fresh operator owns advertisements, process records, process identity,
  ;; and degraded JVM observation. Resolve its existing derivation lazily so
  ;; loading the MCP bridge still needs only the tooling classpath.
  (require 'seon.fresh-operator)
  (let [function (ns-resolve 'seon.fresh-operator var-symbol)]
    (when-not function
      (throw (ex-info "The fresh operator observation function is unavailable."
                      {:seon.dev.mcp/failure :operator-unavailable
                       :seon.dev.mcp/var var-symbol})))
    (apply function arguments)))

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

(defn- cluster-layer-form
  []
  (pr-str
   '(into
     {}
     (map
      (fn [[cluster-name instance]]
        [cluster-name
         (boolean
          (and (map? instance)
               (:seon.sci.eval/ctx instance)
               (:seon.cluster.loop/cluster instance)))])
      @@(ns-resolve 'seon.cluster (symbol "running-instances"))))))

(defn- cluster-layer-states
  [observations]
  (into
   {}
   (mapcat
    (fn [jvm]
      (when-let [advertisement
                 (and (:seon.fresh-operator/reachable? jvm)
                      (:seon.fresh-operator/probe-advertisement jvm))]
        (try
          (operator-private 'prepl-value! advertisement
                            (cluster-layer-form))
          (catch Throwable _
            nil))))
   (:seon.fresh-operator/jvms observations))))

(defn- advertisement-row
  [layer-states observation]
  (let [cluster (:seon.fresh-operator/name observation)
        advertisement (:seon.fresh-operator/advertisement observation)]
    (cond
      (not (valid-advertisement? cluster advertisement))
      {:seon.dev.mcp/root (:seon.fresh-operator/root observation)
       :seon.dev.mcp/cluster cluster
       :seon.dev.mcp/path (:seon.fresh-operator/path observation)
       :seon.dev.mcp/state :invalid
       :seon.dev.mcp/error
       "Advertisement lacks a valid cluster, pid, start instant, or prepl endpoint."}

      (not (:seon.fresh-operator/process-alive? observation))
      {:seon.dev.mcp/root (:seon.fresh-operator/root observation)
       :seon.dev.mcp/cluster cluster
       :seon.dev.mcp/path (:seon.fresh-operator/path observation)
       :seon.dev.mcp/state :stale
       :seon.dev.mcp/advertisement advertisement}

      :else
      {:seon.dev.mcp/root (:seon.fresh-operator/root observation)
       :seon.dev.mcp/cluster cluster
       :seon.dev.mcp/path (:seon.fresh-operator/path observation)
       :seon.dev.mcp/state
       (case (get layer-states cluster)
         true :alive
         false :degraded
         :unknown)
       :seon.dev.mcp/advertisement advertisement
       :seon.dev.mcp/source :advertisement})))

(defn- registered-rows
  [root observations]
  (into
   []
   (comp
    (filter :seon.fresh-operator/reachable?)
    (mapcat :seon.fresh-operator/registrations)
    (filter #(= root (:seon.fresh-operator/root %)))
    (keep
     (fn [registration]
       (let [cluster (:seon.fresh-operator/name registration)
             advertisement (:seon.fresh-operator/advertisement registration)]
         (when (valid-advertisement? cluster advertisement)
           {:seon.dev.mcp/root root
            :seon.dev.mcp/cluster cluster
            :seon.dev.mcp/state :degraded
            :seon.dev.mcp/advertisement advertisement
            :seon.dev.mcp/source :operator-process-record})))))
   (:seon.fresh-operator/jvms observations)))

(defn- discovery-rows
  [root]
  (let [root (canonical-root root)
        ;; `source-observations` is the status owner's one census:
        ;; advertisements first, then reconciled process records and live JVM
        ;; registrations reached through any surviving bootstrap prepl bind.
        observations (operator-private
                      'source-observations root
                      {:seon.fresh-operator/probe-jvms? true})
        layer-states (cluster-layer-states observations)
        advertisements (mapv (partial advertisement-row layer-states)
                             (:seon.fresh-operator/advertisements observations))
        advertised-identities
        (into #{}
              (keep (fn [row]
                      (when-let [advertisement (:seon.dev.mcp/advertisement row)]
                        [(:seon.dev.mcp/cluster row)
                         (:seon.boot/pid advertisement)
                         (:seon.boot/start-instant advertisement)])))
              advertisements)
        registrations
        (remove
         (fn [row]
           (let [advertisement (:seon.dev.mcp/advertisement row)]
             (contains? advertised-identities
                        [(:seon.dev.mcp/cluster row)
                         (:seon.boot/pid advertisement)
                         (:seon.boot/start-instant advertisement)])))
         (registered-rows root observations))]
    (->> (concat advertisements registrations)
         (sort-by (juxt #(not (contains? #{:alive :degraded :unknown}
                                         (:seon.dev.mcp/state %)))
                        :seon.dev.mcp/cluster
                        #(get-in % [:seon.dev.mcp/advertisement
                                    :seon.boot/pid])))
         vec)))

(defn- start-remedy
  [root cluster]
  (str "Start the cluster with: bin/seon --root " root " start " cluster "."))

(defn- endpoint-error
  [row]
  (let [root (:seon.dev.mcp/root row)
        cluster (:seon.dev.mcp/cluster row)
        state (:seon.dev.mcp/state row)]
    (ex-info
     (str "No live CLJ REPL is available for cluster '" cluster
          "'; its advertisement is " (name state) ". "
          (start-remedy root cluster))
     (cond-> {:seon.dev.mcp/failure :repl-unavailable
              :seon.dev.mcp/root root
              :seon.dev.mcp/cluster cluster
              :seon.dev.mcp/advertisement-state state
              :seon.dev.mcp/advertisement-file (:seon.dev.mcp/path row)
              :seon.dev.mcp/remedy (start-remedy root cluster)}
       (:seon.dev.mcp/error row)
       (assoc :seon.dev.mcp/advertisement-error
              (:seon.dev.mcp/error row))))))

(defn- read-clj-endpoint
  [root cluster]
  (when-not (valid-cluster? cluster)
    (throw (ex-info "Invalid cluster name."
                    {:seon.dev.mcp/failure :invalid-cluster
                     :seon.dev.mcp/cluster cluster})))
  (let [root (canonical-root root)
        rows (discovery-rows root)
        candidates (filterv #(and (= cluster (:seon.dev.mcp/cluster %))
                                  (contains? #{:alive :degraded :unknown}
                                             (:seon.dev.mcp/state %)))
                            rows)]
    (case (count candidates)
      1 (let [{state :seon.dev.mcp/state
               source :seon.dev.mcp/source
               advertisement :seon.dev.mcp/advertisement}
              (first candidates)]
          {:host (:seon.boot/prepl-host advertisement)
           :port (:seon.boot/prepl-port advertisement)
           :pid (:seon.boot/pid advertisement)
           :start-instant (:seon.boot/start-instant advertisement)
           :seon.dev.mcp/root root
           :seon.dev.mcp/cluster-state state
           :seon.dev.mcp/source source})
      0 (throw
         (endpoint-error
          (or (some #(when (= cluster (:seon.dev.mcp/cluster %)) %)
                    rows)
              {:seon.dev.mcp/root root
               :seon.dev.mcp/cluster cluster
               :seon.dev.mcp/path
               (.getPath (io/file root "data" "clusters" cluster "prepl.edn"))
               :seon.dev.mcp/state :missing})))
      (throw
       (ex-info
        (str "Cluster '" cluster "' is ambiguous in operator root " root ".")
        {:seon.dev.mcp/failure :ambiguous-cluster
         :seon.dev.mcp/root root
         :seon.dev.mcp/cluster cluster
         :seon.dev.mcp/candidates
         (mapv #(select-keys % [:seon.dev.mcp/root
                                :seon.dev.mcp/cluster
                                :seon.dev.mcp/state
                                :seon.dev.mcp/advertisement])
               candidates)})))))

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
  [root cluster session-id endpoint]
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
                     :root root
                     :cluster cluster
                     :session-id session-id}]
        (swap! clj-sessions assoc [root cluster session-id] session)
        session)
      (catch Throwable throwable
        (try (.close socket) (catch Throwable _ nil))
        (throw throwable)))))

(defn- current-clj-session!
  [root cluster session-id]
  (let [key [root cluster session-id]
        endpoint (read-clj-endpoint root cluster)
        cached (get @clj-sessions key)]
    (cond
      (and cached (= endpoint (:endpoint cached)))
      cached

      cached
      (do
        (close-clj-session! key)
        (if (= "default" session-id)
          (open-clj-session! root cluster session-id endpoint)
          (throw
           (ex-info "Named CLJ session ended with the cluster restart."
                    {:seon.dev.mcp/failure :session-lost
                     :seon.dev.mcp/cluster cluster
                     :seon.dev.mcp/session-id session-id
                     :seon.dev.mcp/retry-with-new-session true}))))

      :else
      (open-clj-session! root cluster session-id endpoint))))

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
          {:seon.dev.mcp/source code
           :seon.dev.mcp/form first-form})))
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

(defn- source-files
  [directory-name]
  (let [directory (io/file project-root directory-name)]
    (if-not (.isDirectory directory)
      []
      (with-open [paths (Files/walk (.toPath directory)
                                    (make-array FileVisitOption 0))]
        (->> (iterator-seq (.iterator paths))
             (filter #(Files/isRegularFile
                       % (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
             (filter #(let [path (str %)]
                        (or (str/ends-with? path ".clj")
                            (str/ends-with? path ".cljc"))))
             (mapv #(.toFile %)))))))

(defn- source-namespace
  [file]
  (try
    (with-open [reader (PushbackReader. (io/reader file))]
      (binding [*read-eval* false]
        (let [eof (Object.)]
          (loop []
            (let [form (read {:eof eof :read-cond :allow} reader)]
              (cond
                (identical? eof form) nil
                (and (seq? form)
                     (= 'ns (first form))
                     (symbol? (second form)))
                (second form)
                :else (recur)))))))
    (catch Throwable _
      nil)))

(defn- first-party-class-prefixes
  []
  (into #{}
        (comp (mapcat source-files)
              (keep source-namespace)
              (map #(munge (str %))))
        first-party-source-directories))

(defn- class-owned-by-prefix?
  [prefixes class-name]
  (some #(or (= % class-name)
             (str/starts-with? class-name (str % "$")))
        prefixes))

(defn- first-party-frame?
  [source-prefixes event-namespace frame]
  (let [class-name (str (first frame))
        event-prefix (when (and (string? event-namespace)
                                (not (str/blank? event-namespace)))
                       (munge event-namespace))]
    (or (class-owned-by-prefix? source-prefixes class-name)
        (and event-prefix
             (class-owned-by-prefix? [event-prefix] class-name)))))

(defn- project-exception-value
  [value event-namespace]
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
            source-prefixes (first-party-class-prefixes)
            frames (into []
                         (comp (filter #(first-party-frame?
                                        source-prefixes event-namespace %))
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
    (update event :val project-exception-value (:ns event))
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

(defn- namespace-symbol!
  [namespace-name]
  (let [namespace-name (or namespace-name "user")
        value (try
                (edn/read-string namespace-name)
                (catch Throwable _ nil))]
    (when-not (and (symbol? value)
                   (nil? (namespace value))
                   (= namespace-name (str value)))
      (throw
       (ex-info "Namespace must be one unqualified Clojure namespace symbol."
                {:seon.dev.mcp/failure :invalid-namespace
                 :seon.dev.mcp/namespace namespace-name})))
    value))

(defn- evaluation-mode!
  [mode]
  (let [mode (or mode "jvm")]
    (when-not (contains? #{"jvm" "door"} mode)
      (throw
       (ex-info "Evaluation mode must be 'jvm' or 'door'."
                {:seon.dev.mcp/failure :invalid-mode
                 :seon.dev.mcp/mode mode})))
    mode))

(defn- jvm-evaluation-form
  [form namespace-symbol]
  (pr-str
   (list 'do
         (list 'in-ns (list 'quote namespace-symbol))
         (list 'clojure.core/refer (list 'quote 'clojure.core))
         (list 'clojure.core/eval (list 'quote form)))))

(defn- door-evaluation-form
  [source cluster namespace-symbol]
  (pr-str
   `(let [instances# @@(ns-resolve 'seon.cluster
                                   (symbol "running-instances"))
          instance# (get instances# ~cluster)
          cluster# (:seon.cluster.loop/cluster instance#)]
      (if-not (and instance#
                   (:seon.sci.eval/ctx instance#)
                   cluster#)
        {:seon.error/kind :seon.dev.mcp/cluster-degraded
         :seon.error/message
         ~(str "Cluster '" cluster
               "' has a live JVM REPL, but its cluster layer is degraded; "
               "door evaluation is unavailable.")
         :seon.dev.mcp/cluster ~cluster}
        ((requiring-resolve 'seon.sci.eval/evaluate)
         {:seon.cluster.run.form/source ~source
          :seon.cluster.run.form/ns [:seon.ns/name '~namespace-symbol]
          :seon.sci.eval/ctx (:seon.sci.eval/ctx instance#)
          :seon.sci.admit/caps (:seon.sci.admit/caps cluster#)
          :seon.sci.eval/time-limit-ms
          (:seon.config.eval/time-limit-ms cluster#)
          :seon.config/on-core-error
          (:seon.config/on-core-error cluster#)})))))

(defn- remote-evaluation-form
  [{:seon.dev.mcp/keys [form source]} mode cluster namespace-symbol]
  (case mode
    "jvm" (jvm-evaluation-form form namespace-symbol)
    "door" (door-evaluation-form source cluster namespace-symbol)))

(defn- execute-clj-eval
  [{:keys [code root cluster mode session_id timeout_ms] :as request}]
  (let [root (canonical-root root)
        cluster (or cluster own-cluster)
        session-id (or session_id "default")
        timeout-ms (min 120000 (max 1 (or timeout_ms default-timeout-ms)))
        key [root cluster session-id]
        validation (try
                     {:seon.dev.mcp/evaluation
                      (require-single-clj-form! code)
                      :seon.dev.mcp/namespace-symbol
                      (namespace-symbol! (:namespace request))
                      :seon.dev.mcp/mode
                      (evaluation-mode! mode)}
                     (catch Throwable throwable
                       {:seon.dev.mcp/error throwable}))]
    (if-let [throwable (:seon.dev.mcp/error validation)]
      (mcp-error
       (merge {:seon.dev.mcp/failure :invalid-form
               :seon.dev.mcp/runtime "clj"
               :seon.dev.mcp/root root
               :seon.dev.mcp/cluster cluster
               :seon.dev.mcp/session-id session-id
               :seon.dev.mcp/error (ex-message throwable)}
              (ex-data throwable)))
      (let [mode (:seon.dev.mcp/mode validation)
            namespace-symbol (:seon.dev.mcp/namespace-symbol validation)]
        (try
          (let [remote-form
              (remote-evaluation-form
               (:seon.dev.mcp/evaluation validation)
               mode cluster namespace-symbol)
              {:keys [writer endpoint] :as session}
              (current-clj-session! root cluster session-id)]
          (.write ^java.io.Writer writer (str remote-form "\n"))
          (.flush ^java.io.Writer writer)
          (let [events (collect-prepl-response! session timeout-ms)
                terminal (some #(when (= :ret (:tag %)) %) events)
                response {:seon.dev.mcp/runtime "clj"
                          :seon.dev.mcp/root root
                          :seon.dev.mcp/cluster cluster
                          :seon.dev.mcp/cluster-state
                          (:seon.dev.mcp/cluster-state endpoint)
                          :seon.dev.mcp/mode mode
                          :seon.dev.mcp/namespace (str namespace-symbol)
                          :seon.dev.mcp/session-id session-id
                          :seon.dev.mcp/events events}]
            (if (:exception terminal)
              (mcp-error (assoc response :seon.dev.mcp/failure :evaluation))
              (mcp-success response))))
        (catch SocketTimeoutException _
          (close-clj-session! key)
          (mcp-error {:seon.dev.mcp/failure :timeout
                      :seon.dev.mcp/runtime "clj"
                      :seon.dev.mcp/root root
                      :seon.dev.mcp/cluster cluster
                      :seon.dev.mcp/mode mode
                      :seon.dev.mcp/namespace (str namespace-symbol)
                      :seon.dev.mcp/session-id session-id
                      :seon.dev.mcp/timeout-ms timeout-ms}))
        (catch Throwable throwable
          (close-clj-session! key)
          (mcp-error
           (merge {:seon.dev.mcp/failure
                   (or (:seon.dev.mcp/failure (ex-data throwable))
                       :transport)
                   :seon.dev.mcp/runtime "clj"
                   :seon.dev.mcp/root root
                   :seon.dev.mcp/cluster cluster
                   :seon.dev.mcp/mode mode
                   :seon.dev.mcp/namespace (str namespace-symbol)
                   :seon.dev.mcp/session-id session-id
                   :seon.dev.mcp/error (ex-message throwable)}
                  (ex-data throwable)))))))))

(defn- session-rows
  [root]
  (into
   []
   (comp
    (filter (fn [[session-root _ _]] (= root session-root)))
    (map (fn [[session-root cluster session-id]]
           {:seon.dev.mcp/root session-root
            :seon.dev.mcp/cluster cluster
            :seon.dev.mcp/session-id session-id})))
   (sort (keys @clj-sessions))))

(defn- execute-runtime-status
  [{:keys [root]}]
  (let [root (canonical-root root)
        rows (discovery-rows root)]
    (mcp-success
     {:seon.dev.mcp/root root
      :seon.dev.mcp/view :inventory
      :seon.dev.mcp/clusters rows
      :seon.dev.mcp/sessions (session-rows root)})))

(def tools
  [{:name "eval_clj"
    :description "Evaluate exactly one Clojure form in a selected operator root, cluster, namespace, and mode; the returned MCP content renders directly into the calling agent/orchestrator context. JVM mode uses the live io-prepl and retains *1/*2. Door mode evaluates through seon.sci.eval/evaluate with the cluster's live shared SCI ctx, admission caps, contracts, print grammar, and time limit: it MUTATES that shared per-cluster ctx, so a debug def enters the agents' world, and it creates NO run or receipts because the run loop owns those facts. Discovery derives from the fresh operator's advertisements and degraded process-record census on every call; the default session reconnects after JVM replacement. Narrow oversized results or raise max_output_tokens up to 16000."
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "Exactly one Clojure form; wrap an intentional sequence in (do ...)."}
                               :root {:type "string" :description "Operator root path. Defaults to the repository root used by bin/seon."}
                               :cluster {:type "string" :description "Cluster name within root. Defaults to this MCP server's own cluster; ambiguous live matches fail with their candidate list."}
                               :namespace {:type "string" :description "Clojure namespace for either mode. Defaults to user; a missing JVM namespace is created and refers clojure.core."}
                               :mode {:type "string" :enum ["jvm" "door"] :description "jvm evaluates in the host io-prepl; door evaluates through the cluster's shared SCI ctx. Defaults to jvm."}
                               :session_id {:type "string" :description "Stateful io-prepl session id. Defaults to 'default'."}
                               :timeout_ms {:type "integer" :minimum 1 :maximum 120000}
                               :max_output_tokens {:type "integer" :minimum 64 :maximum 16000}}
                  :required ["code"]}}

   {:name "runtime_status"
    :description "Report root-scoped JVM clusters and the active stateful io-prepl sessions under that root."
    :inputSchema {:type "object"
                  :properties {:root {:type "string" :description "Operator root path. Defaults to the repository root used by bin/seon."}}
                  :required []}}])

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

(def parent-handle
  (let [parent (.parent (java.lang.ProcessHandle/current))]
    (when (.isPresent parent)
      (.get parent))))

(defn- start-parent-watchdog!
  []
  (when parent-handle
    (try
      (let [exit
            (.thenRun
             (.onExit ^java.lang.ProcessHandle parent-handle)
             (reify Runnable
               (run [_] (System/exit 0))))]
        (.exceptionally
         exit
         (reify java.util.function.Function
           (apply [_ throwable]
             (log-error "watchdog failed:" (ex-message throwable))
             nil))))
      (catch Throwable throwable
        (log-error "watchdog failed:" (ex-message throwable))))))

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
