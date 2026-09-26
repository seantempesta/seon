(ns seon.dev.mcp
  "Serve the source-independent development REPL MCP boundary."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader PushbackReader]
           [java.net InetSocketAddress Socket SocketTimeoutException]))

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
(def server-info {:name "seon" :version "0.4.0"})

;; [root cluster session-id] -> one stateful io-prepl socket and its endpoint.
(defonce clj-sessions (atom {}))
(def stdout-lock (Object.))
(def ^:dynamic *debug* (= "1" (System/getenv "DEBUG")))
(defonce reload-state (atom {}))
;; [root cluster branch] -> the basis of the last evaluation's error window there
;; (branch nil = the shared cluster context, which starts at 0). A lane branch's
;; entry starts at the fork basis its `branch` create returned; a lane this bridge
;; did not create (or one from before a bridge restart) reports its start unknown.
(defonce window-bases (atom {}))
(def max-window-errors 20)

(defn- content-text
  {:seon.fn/projection-boundary :none}
  [value]
  (if (string? value)
    value
    (json/generate-string value)))

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

(defn- discovery-rows
  [root]
  (require 'seon.operator)
  (let [root (canonical-root root)
        advertisements ((ns-resolve 'seon.operator 'advertisements) root)]
    (mapv
     (fn [advertisement]
       (let [cluster (:seon.boot/cluster-name advertisement)
             ;; Discovery needs only health; the whole observation stays in the JVM.
             health
             (try
               ((ns-resolve 'seon.operator 'prepl-value!) advertisement
                (pr-str `(do
                           (~'seon.cluster/project-next-prepl-value!
                            {:seon.dev.mcp/read-only? true :seon.dev.mcp/project? false})
                           (:seon.dev.mcp/health
                            (~'seon.cluster/mcp-runtime-observation ~cluster)))))
               (catch Exception _ nil))]
         {:seon.dev.mcp/root root :seon.dev.mcp/cluster cluster
          :seon.dev.mcp/path (str (io/file root "data/clusters" cluster "prepl.edn"))
          :seon.dev.mcp/state (if health
                               (if (#{:observed :faulted} health) :alive :degraded)
                               :unknown)
          :seon.dev.mcp/advertisement advertisement :seon.dev.mcp/source :advertisement}))
     advertisements)))

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

(defn- source!
  ;; The one reader owns forms and their refusals; the bridge carries text.
  [code]
  (when-not (string? code)
    (throw (ex-info "CLJ evaluation requires a string form."
                    {:seon.dev.mcp/failure :invalid-form})))
  {:seon.dev.mcp/source code})

(defn- collect-prepl-response!
  [{:keys [socket reader]} timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [events []]
      (let [remaining-ms (- deadline (System/currentTimeMillis))]
        (when-not (pos? remaining-ms)
          (throw (SocketTimeoutException. "io-prepl deadline exceeded")))
        (.setSoTimeout ^Socket socket (int remaining-ms))
        (let [event (edn/read {:eof ::eof} reader)]
          (cond
            (= ::eof event)
            (throw (ex-info "Cluster closed the io-prepl session."
                            {:seon.dev.mcp/failure :transport-closed}))

            (not (map? event))
            (throw (ex-info "Cluster emitted malformed io-prepl data."
                            {:seon.dev.mcp/failure :malformed-prepl
                             :seon.dev.mcp/value (pr-str event)}))

            (= :ret (:tag event))
            (conj events event)

            :else
            (recur (conj events event))))))))

(defn- decoded-projection-event
  [event]
  (if (and (= :ret (:tag event)) (string? (:val event)))
    ;; An unreadable :val keeps its raw text beside the reader's failure.
    (let [[parsed cause] (try [(edn/read-string (:val event))]
                              (catch Throwable cause [nil cause]))]
      (cond
        cause (assoc event :seon.dev.mcp/reader-error
                     (str (.getName (class cause)) ": " (ex-message cause)))
        (and (map? parsed) (contains? parsed :seon.dev.mcp/value)) (assoc event :val parsed)
        :else event))
    event))

(defn- shown-event
  ;; The agent path already printed its value: an SCI evaluation's shown text
  ;; (`:seon.dev.mcp/text`) becomes the event's value beside its evaluation
  ;; keys, and every other `:val` keeps the printed bytes the JVM sent. The
  ;; bridge reprints nothing.
  [event]
  (let [value (:seon.dev.mcp/value (:val (decoded-projection-event event)))]
    (if (string? (:seon.dev.mcp/text value))
      (merge event (dissoc value :seon.dev.mcp/text) {:val (:seon.dev.mcp/text value)})
      event)))

(defn- one-shot-events!
  [root cluster remote-form timeout-ms]
  (let [session-id (str "value-" (random-uuid))
        key [root cluster session-id]
        {:keys [writer] :as session}
        (current-clj-session! root cluster session-id)]
    (try
      (.write ^java.io.Writer writer (str remote-form "\n"))
      (.flush ^java.io.Writer writer)
      (mapv decoded-projection-event
            (collect-prepl-response! session timeout-ms))
      (finally
        (close-clj-session! key)))))

(defn- terminal-projection
  [events]
  (some #(when (= :ret (:tag %)) (:val %)) events))

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
    (when-not (contains? #{"jvm" "sci"} mode)
      (throw
       (ex-info "Evaluation mode must be 'jvm' or 'sci'."
                {:seon.dev.mcp/failure :invalid-mode
                 :seon.dev.mcp/mode mode})))
    mode))

(defn- jvm-evaluation-form
  ;; The caller's SOURCE crosses and the one reader (`seon.sci.reader/read`)
  ;; reads it in the target namespace with that namespace's aliases, Vars,
  ;; classes and data readers; a reader refusal is thrown as the reader's own.
  [source namespace-symbol read-only?]
  (pr-str
   `(do
      ((requiring-resolve 'seon.cluster/project-next-prepl-value!) {:seon.dev.mcp/read-only? ~read-only?})
      (in-ns '~namespace-symbol)
      (clojure.core/refer 'clojure.core)
      (let [n# *ns*
            events# ((requiring-resolve 'seon.sci.reader/read)
                     {:seon.sci.reader/text ~source
                      :seon.sci.reader/ns '~namespace-symbol
                      :seon.sci.reader/aliases (into {} (map (fn [[a# t#]] [a# (ns-name t#)])) (ns-aliases n#))
                      :seon.sci.reader/refers (into {} (keep (fn [[s# v#]] (cond (var? v#) [s# (symbol v#)]
                                                                                 (class? v#) [s# (symbol (.getName ^Class v#))])))
                                                    (ns-map n#))
                      :seon.sci.reader/tags *data-readers*
                      :seon.config.eval.result/max-source ~(count source)})
            failure# (if (map? events#) events# (some :seon.sci.reader/error events#))
            second# (second events#)]
        (cond
          failure# (throw (ex-info (:seon.error/message failure#) failure#))
          (= 1 (count events#)) (eval (:seon.sci.reader/form (first events#)))
          :else (throw (ex-info (if second#
                                  (str "Evaluation accepts exactly one form; the second form starts at line "
                                       (:seon.sci.reader/line second#) ", column " (:seon.sci.reader/column second#)
                                       ". Wrap sequences in `(do ...)`.")
                                  "Your reply had no form; only comments/prose. Send a form.")
                                (select-keys second# [:seon.sci.reader/line :seon.sci.reader/column
                                                      :seon.sci.reader/source]))))))))

(defn- sci-evaluation-form
  {:malli/schema [:function
                  [:=> [:cat :string :string :symbol :boolean] :string]
                  [:=> [:cat :string :string :symbol :boolean [:maybe :string]] :string]]}
  ([source cluster namespace-symbol read-only?]
   (sci-evaluation-form source cluster namespace-symbol read-only? nil))
  ([source cluster namespace-symbol read-only? branch]
  (pr-str
   `(do
      ((requiring-resolve 'seon.cluster/project-next-prepl-value!)
       {:seon.dev.mcp/evaluation? true :seon.dev.mcp/read-only? ~read-only?})
      (let [instances# @@(requiring-resolve 'seon.operator.runtime/running-instances)
          instance# (get instances# ~cluster)
          cluster# (:seon.turn.loop/cluster instance#)]
      (if-not (and instance#
                   (:seon.sci.eval/ctx instance#)
                   cluster#)
        {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.dev.mcp/evaluation
         :seon.error/operation 'seon.sci.eval/evaluate
         :seon.dev.mcp/cluster-degraded ~cluster
         :seon.error/message
         ~(str "Cluster '" cluster
               "' has a live JVM REPL, but its cluster layer is degraded; "
               "SCI evaluation is unavailable.")
         :seon.dev.mcp/cluster ~cluster}
        ;; A named branch's handle is RETAINED in the cluster's context state,
        ;; as an agent's is (`acquire-context!` reuses [branch nil]), so a def
        ;; is callable in the next evaluation there; the `branch` tool's
        ;; retire releases it.
        (let [handle# ((requiring-resolve 'seon.cluster.agent/acquire-context!)
                       (assoc cluster# :seon.store/store (:seon.store/store instance#))
                       nil {:seon.agent/branch (keyword ~branch)})]
          ((requiring-resolve 'seon.sci.eval/evaluate)
           ;; THE KEYS `evaluate` DECLARES.
           {:seon.cluster.eval/source ~source
            :seon.cluster.eval/ns [:seon.ns/name '~namespace-symbol]
            :seon.sci.eval/ctx (:seon.sci.eval/ctx handle#)
            :seon.sci.admit/caps (:seon.sci.admit/caps cluster#)
            :seon.sci.eval/time-limit-ms
            (:seon.config.eval/time-limit-ms cluster#)
            :seon.config/on-core-error
            (:seon.config/on-core-error cluster#)}))))))))

(defn- root-run-form
  "The prepl form that evaluates `source` as one ordinary run of root on the
  cluster's own branch (`seon.cluster.agent/evaluate-source!`), segmented as
  code, and answers the settled run with its receipts in order. The run starts
  in root's assigned namespace; the run writer refuses an explicit other one by
  name, so code moves with `in-ns`, as at a REPL."
  {:malli/schema [:=> [:cat :string :string [:maybe :symbol] :boolean] :string]}
  [source cluster namespace-symbol read-only?]
  (pr-str
   `(do
      ((requiring-resolve 'seon.cluster/project-next-prepl-value!)
       {:seon.dev.mcp/project? false :seon.dev.mcp/read-only? ~read-only?})
      (let [handle# (:seon.turn.loop/cluster
                     (get @@(requiring-resolve 'seon.operator.runtime/running-instances) ~cluster))]
        (if-not (:seon.agent/routing handle#)
          {:seon.error/at (java.util.Date.)
           :seon.error/layer :seon.dev.mcp/evaluation
           :seon.error/operation 'seon.cluster.agent/evaluate-source!
           :seon.error/message ~(str "Cluster '" cluster "' has a live JVM REPL, but its agents are not armed; "
                                     "SCI evaluation is unavailable.")
           :seon.dev.mcp/cluster ~cluster}
          (let [result# ((requiring-resolve 'seon.cluster.agent/evaluate-source!)
                         (cond-> {:seon.turn.loop/cluster handle# :seon.agent/routing (:seon.agent/routing handle#)
                                  :seon.agent/id "root" :seon.agent/segmenter 'seon.cluster.reply/code-sources
                                  :seon.cluster.reply/text ~source}
                           '~namespace-symbol (assoc :seon.turn/starting-ns [:seon.ns/name '~namespace-symbol])))]
            (if (or (:seon.error/at result#) (not (:seon.turn/id result#)))
              result#
              (assoc result# :seon.dev.mcp/evaluations
                     ((requiring-resolve 'seon.eval.drive/run-receipts)
                      @(:seon.db/connection handle#) [(:seon.turn/id result#)])))))))))

(defn- run-events
  "The prepl events of a root run: one per settled evaluation, its stored shown
  text as the value, and an error flagged as the event's exception."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [events]
  (let [terminal (some #(when (= :ret (:tag %)) %) events)
        value (try (edn/read-string (:val terminal)) (catch Throwable _ nil))]
    (if-let [evaluations (:seon.dev.mcp/evaluations value)]
      (into (filterv #(not= :ret (:tag %)) events)
            (map (fn [{shown :seon.eval/shown error :seon.cluster.eval/error ordinal :seon.cluster.eval/ordinal}]
                   (cond-> {:tag :ret :val (or shown error) :seon.cluster.eval/ordinal ordinal
                            :seon.turn/id (:seon.turn/id value)}
                     error (assoc :exception true))))
            evaluations)
      (cond->> events
        (:seon.error/at value) (mapv #(if (identical? % terminal) (assoc % :exception true) %))))))

(defn- remote-evaluation-form
  [{:seon.dev.mcp/keys [source read-only? branch namespace?]} mode cluster namespace-symbol]
  (case mode
    "jvm" (jvm-evaluation-form source namespace-symbol (true? read-only?))
    "sci" (if branch
            (sci-evaluation-form source cluster namespace-symbol (true? read-only?) branch)
            (root-run-form source cluster (when namespace? namespace-symbol) (true? read-only?)))))

(defn- branch-name!
  "A lane names its branch; blank, non-string or colon-led names refuse."
  [branch]
  (when-not (and (string? branch) (not (str/blank? branch))
                 (not (str/starts-with? branch ":")))
    (throw (ex-info "A branch name is required, without a leading colon."
                    {:seon.dev.mcp/failure :invalid-branch
                     :seon.dev.mcp/branch branch})))
  branch)

(defn- branch-form
  "Create (off the cluster head's commit), list or retire one named branch
  through `seon.cluster.registry`; the cluster's own branch never qualifies."
  [cluster action branch]
  (pr-str
   `(let [instance# (get @@(requiring-resolve 'seon.operator.runtime/running-instances) ~cluster)
          handle# (:seon.turn.loop/cluster instance#)
          store# {:seon.store/store (:seon.store/store instance#)}
          connection# (:seon.db/connection handle#)
          own# (get-in @connection# [:config :branch])
          branch# ~(some-> branch keyword)
          held# (filterv (fn [[[held-branch# _#] _#]] (= branch# held-branch#))
                         (some-> (:seon.agent/context-state handle#) deref))
          refuse# (fn [message# data#]
                    (merge {:seon.error/at (java.util.Date.) :seon.error/layer :seon.dev.mcp/branch
                            :seon.error/operation '~(symbol "seon.dev.mcp" (str "branch-" action))
                            :seon.error/message message# :seon.dev.mcp/branch branch#} data#))
          call# (fn [sym# & args#] (apply (requiring-resolve sym#) args#))]
      (cond
        (nil? handle#) (refuse# "The cluster layer is not running." {:seon.dev.mcp/cluster ~cluster})
        (= "list" ~action) {:seon.dev.mcp/cluster-branch own#
                            :seon.dev.mcp/branches (vec (sort (call# 'seon.cluster.registry/roster (:seon.store/store store#))))}
        (= own# branch#) (refuse# "The cluster's own branch is shared; name a lane branch." {})
        (= "create" ~action)
        (let [source# @connection#
              from# (call# 'seon.db/commit-id source#)
              result# (call# 'seon.cluster.registry/branch!
                             (assoc store# :seon.store/branch branch# :seon.cluster.registry/from from#))]
          (if (:seon.cluster/created? result#)
            ;; the fork basis: the branch inherits every datom up to it
            (assoc result# :seon.cluster.registry/from from#
                   :seon.db/basis-t (call# 'seon.db/basis-t source#))
            (refuse# "The branch already exists; retire it or name another." {})))
        (some (comp :seon.agent/id second) held#)
        (refuse# "Agents hold this branch; disarm them first." {:seon.agent/id (vec (keep (comp :seon.agent/id second) held#))})
        :else
        (do (doseq [[_# execution#] (sort-by (comp :seon.agent/owns-connection? second) held#)]
              (call# 'seon.cluster.agent/release-context! execution#))
            (call# 'seon.cluster.registry/retire-branch! (assoc store# :seon.store/branch branch#))
            {:seon.store/branch branch# :seon.dev.mcp/retired? true})))))

(defn- execute-branch
  [{:keys [root cluster action timeout_ms] branch :name}]
  (let [root (canonical-root root)
        cluster (or cluster own-cluster)
        request {:seon.dev.mcp/root root :seon.dev.mcp/cluster cluster :seon.dev.mcp/action action}]
    (try
      (when-not (contains? #{"create" "list" "retire"} action)
        (throw (ex-info "Action must be create, list or retire." {:seon.dev.mcp/failure :invalid-action})))
      (let [events (one-shot-events! root cluster
                                     (branch-form cluster action (when (not= "list" action) (branch-name! branch)))
                                     (min 120000 (max 1 (or timeout_ms default-timeout-ms))))
            terminal (some #(when (= :ret (:tag %)) %) events)
            value (try (edn/read-string (:val terminal)) (catch Throwable _ nil))
            response (assoc request :seon.dev.mcp/value (:val terminal))]
        (if (or (:exception terminal) (not (map? value)) (:seon.error/at value))
          (mcp-error (cond-> (assoc response :seon.dev.mcp/failure :branch)
                       (:exception terminal) (assoc :seon.dev.mcp/events (mapv #(dissoc % :form) events))))
          (do (case action
                ;; a new branch's first window opens at its fork basis, not at 0
                "create" (swap! window-bases assoc [root cluster branch] (:seon.db/basis-t value))
                "retire" (swap! window-bases dissoc [root cluster branch])
                nil)
              (mcp-success response))))
      (catch Throwable throwable
        (mcp-error (merge request {:seon.dev.mcp/failure :branch :seon.dev.mcp/error (ex-message throwable)
                                  :seon.dev.mcp/class (.getName (class throwable))}
                          (ex-data throwable)))))))

(defn- window-form
  "Read the evaluated branch's errors in (since, its current basis] through
  `seon.error/occurred-since`; a nil `since` reads only the current basis."
  [cluster branch since]
  (pr-str
   `(do
      ((requiring-resolve 'seon.cluster/project-next-prepl-value!) {:seon.dev.mcp/read-only? true})
      (let [instance# (get @@(requiring-resolve 'seon.operator.runtime/running-instances) ~cluster)
            connection# (if ~branch
                          ((requiring-resolve 'seon.cluster.registry/active-branch-connection)
                           {:seon.store/store (:seon.store/store instance#) :seon.store/branch (keyword ~branch)})
                          (:seon.db/connection (:seon.turn.loop/cluster instance#)))
            since# (resolve 'seon.error/occurred-since)]
        (cond
          (nil? connection#) {:seon.dev.mcp/window :unknown :seon.error/message "No live connection holds this branch."}
          (nil? since#) {:seon.dev.mcp/window :unknown :seon.error/message "This JVM's seon.error has no occurred-since (RESET NEEDED)."}
          :else (let [database# ((requiring-resolve 'seon.db/db) connection#)
                      errors# (if ~since (since# database# ~since) [])]
                  (if (map? errors#)
                    {:seon.dev.mcp/window :unknown :seon.error/error errors#}
                    {:seon.db/basis-t ((requiring-resolve 'seon.db/basis-t) database#)
                     :seon.dev.mcp/count (count errors#)
                     :seon.dev.mcp/errors (vec (take ~max-window-errors errors#))})))))))

(defn- branch-errors!
  "The errors written on the evaluated branch since this bridge's previous
  evaluation there, or nil when none; advances that branch's window basis.
  Runs on the evaluation's own session, so it pays no second discovery."
  [session timeout-ms [_ cluster branch :as key]]
  ;; a lane branch's window opens at the fork basis its `branch` create
  ;; recorded; one this bridge did not create has no known start
  (let [since (get @window-bases key (when-not branch 0))
        value (try (.write ^java.io.Writer (:writer session) (str (window-form cluster branch since) "\n"))
                   (.flush ^java.io.Writer (:writer session))
                   (terminal-projection (mapv decoded-projection-event
                                              (collect-prepl-response! session timeout-ms)))
                   (catch Throwable throwable
                     ;; a half-read reply would desynchronize the next evaluation
                     (close-clj-session! ((juxt :root :cluster :session-id) session))
                     {:seon.dev.mcp/window :unknown :seon.error/message (ex-message throwable)}))
        value (or (:seon.dev.mcp/value value) value)
        value (if (and (nil? since) (:seon.db/basis-t value))
                (assoc value :seon.dev.mcp/window :unknown
                       :seon.error/message (str "This bridge recorded no fork basis for the branch; the window opens at "
                                                (:seon.db/basis-t value) ". Requery (seon.error/occurred-since database <fork basis>)."))
                value)]
    (when-let [basis (:seon.db/basis-t value)]
      (swap! window-bases assoc key basis))
    (when (or (:seon.dev.mcp/window value) (pos? (:seon.dev.mcp/count value 0)))
      (cond-> (assoc value :seon.dev.mcp/branch (or branch :cluster) :seon.dev.mcp/since since)
        (< max-window-errors (:seon.dev.mcp/count value 0))
        (assoc :seon.dev.mcp/requery (str "(seon.error/occurred-since database " since ")"))))))

(defn- execute-clj-eval
  [{:keys [code root cluster mode session_id timeout_ms] :as request}]
  (let [root (canonical-root root)
        cluster (or cluster own-cluster)
        session-id (or session_id "default")
        timeout-ms (min 120000 (max 1 (or timeout_ms default-timeout-ms)))
        key [root cluster session-id]
        validation (try
                     {:seon.dev.mcp/evaluation
                      (source! code)
                      :seon.dev.mcp/namespace-symbol
                      (namespace-symbol! (:namespace request))
                      :seon.dev.mcp/mode
                      (let [selected (evaluation-mode! mode)
                            branch (:branch request)]
                        (when branch
                          (branch-name! branch)
                          (when (not= selected "sci")
                            (throw (ex-info "Branch selection requires SCI mode."
                                            {:seon.dev.mcp/failure :invalid-branch}))))
                        selected)}
                     (catch Throwable throwable
                       {:seon.dev.mcp/error throwable}))]
    (if-let [throwable (:seon.dev.mcp/error validation)]
      (mcp-error
       (merge {:seon.dev.mcp/failure :invalid-form
               :seon.dev.mcp/runtime "clj"
               :seon.dev.mcp/root root
               :seon.dev.mcp/cluster cluster
               :seon.dev.mcp/session-id session-id
               :seon.dev.mcp/form code
               :seon.dev.mcp/error (ex-message throwable)}
              (ex-data throwable)))
      (let [mode (:seon.dev.mcp/mode validation)
            namespace-symbol (:seon.dev.mcp/namespace-symbol validation)]
        (try
          (let [remote-form
              (remote-evaluation-form
               (assoc (:seon.dev.mcp/evaluation validation)
                      :seon.dev.mcp/read-only? (true? (:read_only request))
                      :seon.dev.mcp/branch (:branch request)
                      :seon.dev.mcp/namespace? (some? (:namespace request)))
               mode cluster namespace-symbol)
              {:keys [writer endpoint] :as session}
              (current-clj-session! root cluster session-id)]
          (.write ^java.io.Writer writer (str remote-form "\n"))
          (.flush ^java.io.Writer writer)
          (let [collected (collect-prepl-response! session timeout-ms)
                root-run? (and (= "sci" mode) (nil? (:branch request)))
                events (mapv (fn [event]
                               (-> event
                                   (cond-> (not root-run?) shown-event)
                                   ;; SCI's namespace is the response's; the
                                   ;; host prepl *ns* would mislabel it.
                                   (cond-> (= "sci" mode) (dissoc :ns))
                                   (dissoc :form)))
                             (if root-run? (run-events collected) collected))
                terminal (some #(when (= :ret (:tag %)) %) (reverse events))
                response {:seon.dev.mcp/runtime "clj"
                          :seon.dev.mcp/root root
                          :seon.dev.mcp/cluster cluster
                          :seon.dev.mcp/cluster-state
                          (:seon.dev.mcp/cluster-state endpoint)
                          :seon.dev.mcp/mode mode
                          :seon.dev.mcp/namespace (str namespace-symbol)
                          :seon.dev.mcp/session-id session-id
                          :seon.dev.mcp/form code
                          :seon.dev.mcp/events events}
                window (branch-errors! session timeout-ms
                                       [root cluster (when (= "sci" mode) (:branch request))])]
            (cond-> (if (or (:exception terminal) (some :exception events))
                      (mcp-error (assoc response :seon.dev.mcp/failure :evaluation))
                      (mcp-success response))
              window (update :content conj {:type "text"
                                            :text (content-text {:seon.dev.mcp/branch-errors window})}))))
        (catch SocketTimeoutException _
          (close-clj-session! key)
          (mcp-error {:seon.dev.mcp/failure :timeout
                      :seon.dev.mcp/runtime "clj"
                      :seon.dev.mcp/root root
                      :seon.dev.mcp/cluster cluster
                      :seon.dev.mcp/mode mode
                      :seon.dev.mcp/namespace (str namespace-symbol)
                      :seon.dev.mcp/session-id session-id
                      :seon.dev.mcp/form code
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
                   :seon.dev.mcp/form code
                   :seon.dev.mcp/error (ex-message throwable)}
                  (ex-data throwable)))))))))

(defn- session-rows
  [root cluster]
  (into
   []
   (comp
    (filter (fn [[session-root session-cluster _]]
              (and (= root session-root)
                   (= cluster session-cluster))))
    (map (fn [[session-root cluster session-id]]
           {:seon.dev.mcp/root session-root
            :seon.dev.mcp/cluster cluster
            :seon.dev.mcp/session-id session-id})))
   (sort (keys @clj-sessions))))

(defn- runtime-observation
  [root cluster]
  (try
    (let [projected
          (terminal-projection
           (one-shot-events!
            root cluster
            (pr-str
             `(do
                ((requiring-resolve
                  'seon.cluster/project-next-prepl-value!)
                 {:seon.dev.mcp/read-only? true})
                ;; The observation's declared AI face, the one `bin/seon status` prints.
                ((requiring-resolve 'seon.cluster/runtime-observation-ai)
                 {:seon.render/value
                  ((requiring-resolve 'seon.cluster/mcp-runtime-observation) ~cluster)})))
            default-timeout-ms))]
      (or (:seon.dev.mcp/value projected) projected))
    (catch Throwable throwable
      {:seon.dev.mcp/cluster cluster
       :seon.dev.mcp/health :unknown
       :seon.dev.mcp/flow :unknown
       :seon.dev.mcp/error (ex-message throwable)})))

(defn- execute-runtime-status
  [{:keys [root cluster]}]
  (let [root (canonical-root root)
        rows (discovery-rows root)
        selected (or cluster own-cluster)
        selected-rows (filterv #(= selected (:seon.dev.mcp/cluster %)) rows)
        selected-row
        (when-let [row (first selected-rows)]
          (cond-> row
            (< 1 (count selected-rows))
            (assoc :seon.dev.mcp/observation-count
                   (count selected-rows))))]
    (mcp-success
     {:seon.dev.mcp/root root
      :seon.dev.mcp/view :cluster-health-flow
      :seon.dev.mcp/clusters
      (if selected-row
        [(if (contains? #{:alive :degraded :unknown}
                        (:seon.dev.mcp/state selected-row))
           (assoc selected-row :seon.dev.mcp/runtime
                  (runtime-observation root selected))
           (assoc selected-row :seon.dev.mcp/runtime
                  {:seon.dev.mcp/health :unknown
                   :seon.dev.mcp/flow :unknown}))]
        [])
      :seon.dev.mcp/selected-cluster selected
      :seon.dev.mcp/sessions (session-rows root selected)})))

(defn- hex-digit?
  [character]
  (or (<= (int \0) (int character) (int \9))
      (<= (int \a) (int character) (int \f))
      (<= (int \A) (int character) (int \F))))

(defn- digest!
  [content-digest]
  (when-not (and (string? content-digest)
                 (= 64 (count content-digest))
                 (every? hex-digit? content-digest))
    (throw (ex-info "Digest must be 64 hexadecimal characters."
                    {:seon.dev.mcp/failure :invalid-digest})))
  content-digest)

(defn- path!
  [path]
  (let [parsed (try
                 (edn/read-string (or path "[]"))
                 (catch Throwable _ ::unreadable))]
    (when-not (vector? parsed)
      (throw (ex-info "Path must be one EDN vector."
                      {:seon.dev.mcp/failure :invalid-path})))
    parsed))

(defn- execute-get-value
  [{:keys [root cluster digest path offset timeout_ms]}]
  (let [root (canonical-root root)
        cluster (or cluster own-cluster)
        content-digest (digest! digest)
        path (path! path)
        offset (max 0 (long (or offset 0)))
        timeout-ms (min 120000 (max 1 (or timeout_ms default-timeout-ms)))
        events
        (one-shot-events!
         root cluster
         (pr-str
          `(do
             ((requiring-resolve 'seon.cluster/project-next-prepl-value!)
              {:seon.dev.mcp/read-only? true})
             ((requiring-resolve 'seon.cluster/mcp-get-value)
              ~cluster ~content-digest ~path ~offset)))
         timeout-ms)
        terminal (some #(when (= :ret (:tag %)) %) events)
        response {:seon.dev.mcp/runtime "clj"
                  :seon.dev.mcp/root root
                  :seon.dev.mcp/cluster cluster
                  :seon.dev.mcp/source-digest content-digest
                  :seon.render.data/path path
                  :seon.render.data/offset offset
                  :seon.dev.mcp/events events}]
    (if (:exception terminal)
      (mcp-error (assoc response :seon.dev.mcp/failure :evaluation))
      (mcp-success response))))

(def tools
  [{:name "eval_clj"
    :description "Evaluate exactly one Clojure form in a selected operator root, cluster, namespace, and mode; the returned MCP content renders directly into the calling agent/orchestrator context. JVM mode uses the live io-prepl; the session keeps no result (*1/*2/*3/*e stay nil), so bind what you need to a name. It binds no cluster custody; at a development REPL, (seon.cluster.boot/connection \"default\") supplies the explicit connection to pass to seon.db. SCI mode (`sci`) without a branch is ONE ORDINARY RUN OF ROOT on the cluster's own branch (seon.cluster.agent/evaluate-source!): the code is segmented as code (every form, several allowed), each form read and evaluated in root's context under the entry gate, settled as root's recorded evaluations, and answered as one event per evaluation with its saved shown text. A defn becomes a program row on the cluster. The run starts in root's own namespace (the run writer refuses another), so move with (in-ns ...) in the code. With a branch (made by the `branch` tool), SCI evaluates in that branch's retained context through the agent acquisition entrance: private SCI state, never program rows, no run or receipts, until `branch` retire releases it (a hosted branch agent replaces this). Oversized values settle into the selected cluster's blob tier and return a retrievable digest. Discovery derives from current advertisements and exact operating-system process identities on every call; the default session reconnects after JVM replacement."
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "Clojure code. JVM mode and SCI with a branch evaluate exactly one form (wrap a sequence in (do ...)); SCI without a branch evaluates every form in order as one run."}
                               :root {:type "string" :description "Operator root path. Defaults to the repository root used by bin/seon."}
                               :cluster {:type "string" :description "Cluster name within root. Defaults to this MCP server's own cluster; ambiguous live matches fail with their candidate list."}
                               :namespace {:type "string" :description "Clojure namespace for either mode. Defaults to user; a missing JVM namespace is created and refers clojure.core."}
                               :read_only {:type "boolean" :description "Declare that this evaluation changes no runtime code or mutable state; preserves retained pages. Omitted or false conservatively invalidates them."}
                               :mode {:type "string" :enum ["jvm" "sci"] :description "jvm evaluates in the host io-prepl; sci without a branch is an ordinary run of root; with a branch, the branch's retained context. Defaults to jvm."}
                               :branch {:type "string" :description "SCI only: a branch the `branch` tool created, without a leading colon. Its retained context persists across calls; omission makes the evaluation a run of root on the cluster."}
                               :session_id {:type "string" :description "Stateful io-prepl session id. Defaults to 'default'."}
                               :timeout_ms {:type "integer" :minimum 1 :maximum 120000}}
                  :required ["code"]}}

   {:name "runtime_status"
    :description "Report one selected cluster's deduplicated health/readiness, bounded problem counts, Flow proc observations, and active stateful io-prepl sessions. A proc that does not answer within the configured ping window is unknown, never healthy."
    :inputSchema {:type "object"
                  :properties {:root {:type "string" :description "Operator root path. Defaults to the repository root used by bin/seon."}
                               :cluster {:type "string" :description "Selected cluster. Defaults to this MCP server's cluster."}}
                  :required []}}

   {:name "branch"
    :description "Own a lane branch of a cluster: create one off the cluster head's commit (seon.cluster.registry/branch!), list the store's roster, or retire one (release its retained eval_clj context, then seon.cluster.registry/retire-branch!). A name is required for create and retire; the cluster's own branch, an existing name on create, and a branch agents hold on retire refuse as declared errors."
    :inputSchema {:type "object"
                  :properties {:action {:type "string" :enum ["create" "list" "retire"]}
                               :name {:type "string" :description "Branch name without a leading colon; required for create and retire."}
                               :root {:type "string" :description "Operator root path. Defaults to the repository root."}
                               :cluster {:type "string" :description "Cluster whose head and store the branch uses. Defaults to this MCP server's cluster."}
                               :timeout_ms {:type "integer" :minimum 1 :maximum 120000}}
                  :required ["action"]}}

   {:name "get_value"
    :description "Drill an oversized eval result previously stored in the selected cluster's blob tier. The path is a get-in path and offset pages the selected collection."
    :inputSchema {:type "object"
                  :properties {:digest {:type "string" :description "The SHA-256 digest returned by eval_clj."}
                               :path {:type "string" :description "An EDN get-in path into the stored value. Defaults to []."}
                               :offset {:type "integer" :minimum 0}
                               :root {:type "string" :description "Operator root path. Defaults to the repository root."}
                               :cluster {:type "string" :description "Cluster holding the blob. Defaults to this MCP server's cluster."}
                               :timeout_ms {:type "integer" :minimum 1 :maximum 120000}}
                  :required ["digest"]}}])

(defn- execute-tool
  [name arguments]
  (let [arguments (reduce-kv (fn [result key value]
                               (assoc result (keyword key) value))
                             {}
                             (or arguments {}))]
    (case name
      "eval_clj" (execute-clj-eval arguments)
      "runtime_status" (execute-runtime-status arguments)
      "get_value" (execute-get-value arguments)
      "branch" (execute-branch arguments)
      (throw (ex-info (str "Unknown tool: " name)
                      {:seon.dev.mcp/tool name})))))

;;; ---------------------------------------------------------------------------
;;; JSON-RPC
;;; ---------------------------------------------------------------------------

(defn- send-response
  {:seon.fn/external-sink :ai-visible-text
   :seon.fn/projection-boundary :none}
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
                       :capabilities {:tools {:listChanged true}}
                       :serverInfo server-info})

      "notifications/initialized"
      nil

      "tools/list"
      (send-result id {:tools tools})

      "tools/call"
      (let [{:keys [name arguments]} params]
        (let [result (execute-tool name arguments)
              error (:error @reload-state)]
          (send-result id (cond-> result
                            error (update :content conj
                                          {:type "text" :text (content-text error)})))
          (when error (swap! reload-state dissoc :error))))

      (when id
        (send-error id -32601 (str "Method not found: " method))))
    (catch Throwable throwable
      (log-error "request failed:" (ex-message throwable))
      (when id
        (let [reload-error (:error @reload-state)]
          (send-result id
                       (mcp-error
                        (merge {:seon.dev.mcp/failure :request
                                :seon.dev.mcp/error (ex-message throwable)}
                               (ex-data throwable) reload-error)))
          (when reload-error (swap! reload-state dissoc :error)))))))

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
  (let [reader (BufferedReader. *in*)
        source (io/file project-root "script/seon/dev/mcp.clj")
        state reload-state handler (atom @#'handle-request)]
    (reset! state {:checked 0 :modified (.lastModified source) :tools tools})
    (loop []
      (when-let [line (.readLine reader)]
        (when-not (str/blank? line)
          (try
            (let [now (System/currentTimeMillis)]
              (when (>= (- now (:checked @state)) 1000)
                (let [modified (.lastModified source)]
                  (swap! state assoc :checked now)
                  (when (not= modified (:modified @state))
                    (let [roots (when-let [n (find-ns 'seon.dev.mcp)] (into {} (map (fn [[_ v]] [v @v]) (ns-interns n))))]
                     (try
                      (require 'seon.dev.mcp :reload)
                      (doseq [[sym value] {'reload-state state 'clj-sessions clj-sessions}] (alter-var-root (ns-resolve 'seon.dev.mcp sym) (constantly value)))
                      (let [new-tools @(ns-resolve 'seon.dev.mcp 'tools)]
                       (when (not= (:tools @state) new-tools) (send-response {:jsonrpc "2.0" :method "notifications/tools/list_changed"}))
                       (swap! state assoc :modified modified :tools new-tools :error nil))
                      (reset! handler @(ns-resolve 'seon.dev.mcp 'handle-request))
                      (log-info "reloaded" (.getPath source))
                      (catch Throwable throwable
                        (doseq [[v root] roots] (alter-var-root v (constantly root)))
                        (log-error "reload failed:" (.getPath source) (ex-message throwable))
                        (swap! state assoc :error {:seon.dev.mcp/reload-error (ex-message throwable)
                                                   :seon.dev.mcp/file (.getPath source)})))))))
              (@handler (json/parse-string line true)))
            (catch Throwable throwable
              (log-error "parse error:" (ex-message throwable))
              (send-error nil -32700 "Parse error"))))
        (recur)))))
