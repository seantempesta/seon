(ns context-walk.s0-s1
  "S0 prompt capture and S1 shadow rendering on one scratch cluster.

  Run from the repository root:

    clojure -M:dev -e
      '(do (load-file \"tmp/context-walk/context_walk_s0_s1.clj\")
           (shutdown-agents)
           (System/exit 0))'

  The production block and walk implementations are read-only inputs. This
  script writes only its scratch cluster and committed research artifacts."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.ai.tokens :as tokens]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.message :as message]
            [seon.config :as config]
            [seon.context :as context]
            [seon.error :as error]
            [seon.sci.admit :as admit])
  (:import [java.io PushbackReader StringReader]
           [java.nio.charset StandardCharsets]
           [java.util Date]))

(def ^:private cluster-name "context-walk-s0-s1")
(def ^:private scratch-root "tmp/context-walk/runtime/clusters")
(def ^:private s0-root
  "docs/prds/sci-execution-runtime/research/context-walk/s0-baseline")
(def ^:private s1-root
  "docs/prds/sci-execution-runtime/research/context-walk/s1-shadow")

(def ^:private cases
  [{::case :helper-chat ::agent-id "helper" ::trigger-shape :chat}
   {::case :root-chat ::agent-id "root" ::trigger-shape :chat}
   {::case :helper-routed-problem
    ::agent-id "helper"
    ::trigger-shape :routed-problem}
   {::case :root-routed-problem
    ::agent-id "root"
    ::trigger-shape :routed-problem}
   {::case :helper-error-wake
    ::agent-id "helper"
    ::trigger-shape :error-wake}
   {::case :root-error-wake
    ::agent-id "root"
    ::trigger-shape :error-wake}])

(defn- stamp
  [& values]
  (println (str "[" (java.time.LocalTime/now) "] " (apply str values)))
  (flush))

(defn- delete-scratch-root!
  []
  (let [repository (.getCanonicalFile (io/file "."))
        expected (.getCanonicalFile (io/file scratch-root))
        tmp-root (.getCanonicalFile (io/file repository "tmp/context-walk"))]
    (when-not (.startsWith (.toPath expected) (.toPath tmp-root))
      (throw (ex-info "Refusing to delete outside tmp/context-walk."
                      {::path (.getPath expected)})))
    (when (.exists expected)
      (doseq [entry (reverse (file-seq expected))]
        (when-not (.delete ^java.io.File entry)
          (throw (ex-info "Could not remove the prior scratch entry."
                          {::path (.getPath ^java.io.File entry)})))))))

(defn- ensure-output-directories!
  []
  (doseq [path [s0-root s1-root]]
    (when-not (.mkdirs (io/file path))
      (when-not (.isDirectory (io/file path))
        (throw (ex-info "Could not create an experiment output directory."
                        {::path path}))))))

(defn- utf8-bytes
  [text]
  (alength (.getBytes ^String text StandardCharsets/UTF_8)))

(defn- capture-for-trigger
  [db trigger-id]
  (some
   (fn [[capture-id prompt basis-t run-id agent-id]]
     (when (= trigger-id (message/trigger db run-id))
       {::capture-id capture-id
        ::prompt prompt
        ::basis-t basis-t
        ::run-id run-id
        ::agent-id agent-id}))
   (d/q '[:find ?capture-id ?prompt ?basis-t ?run-id ?agent-id
          :in $ ?trigger-id
          :where
          [?capture :seon.context.capture/id ?capture-id]
          [?capture :seon.context.capture/prompt ?prompt]
          [?capture :seon.context.capture/basis-t ?basis-t]
          [?capture :seon.context.capture/run ?run]
          [?run :seon.cluster.run/id ?run-id]
          [?run :seon.cluster.run/agent ?agent]
          [?agent :seon.cluster.agent/id ?agent-id]]
        db trigger-id)))

(defn- capture-waiter
  "Register interest before the trigger commit, then derive current state."
  [connection trigger-id]
  (let [result (promise)
        listener-key (keyword "context-walk.capture" trigger-id)
        observe! (fn [db]
                   (when-let [capture (capture-for-trigger db trigger-id)]
                     (deliver result capture)))]
    (d/listen connection listener-key
              (fn [transaction-report]
                (observe! (:db-after transaction-report))))
    (observe! @connection)
    (fn []
      (try
        (let [capture (deref result 30000 ::timed-out)]
          (when (= ::timed-out capture)
            (throw (ex-info "Capture did not commit before the backstop."
                            {::trigger-id trigger-id})))
          capture)
        (finally
          (d/unlisten connection listener-key))))))

(defn- captured-before-provider?
  [db prompt]
  (boolean
   (d/q '[:find ?capture .
          :in $ ?prompt
          :where [?capture :seon.context.capture/prompt ?prompt]]
        db prompt)))

(defn- mock-completer
  [connection calls]
  (fn [request]
    (swap! calls conj
           {::prompt (:seon.ai/prompt request)
            ::capture-present-before-call?
            (captured-before-provider? @connection
                                       (:seon.ai/prompt request))})
    {:seon.ai/text "(my.run/wait \"context-walk capture complete\")"}))

(defn- transact-trigger!
  [connection trigger-id transaction-data]
  (let [await! (capture-waiter connection trigger-id)]
    (d/transact connection transaction-data)
    (await!)))

(defn- chat-trigger!
  [connection agent-id]
  (let [trigger-id (str "context-walk-chat-" agent-id)]
    (transact-trigger!
     connection trigger-id
     [{:seon.cluster.message/id trigger-id
       :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
       :seon.cluster.message/content
       (str "Chat baseline for " agent-id
            ": report the namespace and the current request.")
       :seon.cluster.message/at (Date.)}])))

(defn- routed-problem-trigger!
  [connection agent-id]
  (let [sender (if (= agent-id "root") "helper" "root")
        problem-id (str "context-walk-problem-" agent-id)
        trigger-id (str "context-walk-routed-" agent-id)]
    (d/transact connection [{:seon.problems/id problem-id}])
    (transact-trigger!
     connection trigger-id
     [{:seon.cluster.message/id trigger-id
       :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
       :seon.cluster.message/from [:seon.cluster.agent/id sender]
       :seon.cluster.message/about [:seon.problems/id problem-id]
       :seon.cluster.message/content
       (str "Investigate " problem-id
            " and report whether this namespace can repair it.")
       :seon.cluster.message/at (Date.)}])))

(defn- error-wake-trigger!
  [instance connection agent-id]
  (let [error-id (str "context-walk-error-" agent-id)
        trigger-id (str error-id "-"
                        (if (= agent-id "root")
                          "no-attributable-agent"
                          "your-run"))
        advertisement (:seon.boot/advertisement instance)
        process (cluster/process-identity advertisement)
        effective (config/effective @connection cluster-name)
        source (ex-info (str "Injected context-walk fault for " agent-id)
                        {:context-walk/agent-id agent-id})
        request
        (cond-> {:seon.error/source source
                 :seon.error/id error-id
                 :seon.error/at (Date.)
                 :seon.error/process process
                 :seon.sci.admit/caps (config/result-caps effective)
                 :seon.error/basis-t (:max-tx @connection)
                 :seon.config.error/recurrence-limit
                 (:seon.config.error/recurrence-limit effective)
                 :seon.config.error/escalate-to "root"}
          (not= agent-id "root")
          (assoc :seon.cluster.agent/id agent-id))
        await! (capture-waiter connection trigger-id)]
    (d/transact connection (error/commit-tx @connection request))
    (await!)))

(defn- run-s0!
  [instance connection]
  (let [calls (atom [])]
    (with-redefs [ai/complete (mock-completer connection calls)]
      (let [captures
            [(assoc (chat-trigger! connection "helper")
                    ::case :helper-chat ::trigger-shape :chat)
             (assoc (chat-trigger! connection "root")
                    ::case :root-chat ::trigger-shape :chat)
             (assoc (routed-problem-trigger! connection "helper")
                    ::case :helper-routed-problem
                    ::trigger-shape :routed-problem)
             (assoc (routed-problem-trigger! connection "root")
                    ::case :root-routed-problem
                    ::trigger-shape :routed-problem)
             (assoc (error-wake-trigger! instance connection "helper")
                    ::case :helper-error-wake ::trigger-shape :error-wake)
             (assoc (error-wake-trigger! instance connection "root")
                    ::case :root-error-wake ::trigger-shape :error-wake)]]
        (when-not (every? ::capture-present-before-call? @calls)
          (throw (ex-info "A provider call ran without a durable capture."
                          {::calls @calls})))
        (when-not (= (mapv ::case cases) (mapv ::case captures))
          (throw (ex-info "The captured corpus does not match the case plan."
                          {::captures captures})))
        captures))))

;;; ---------------------------------------------------------------------------
;;; S1: a shadow renderer over each capture's exact database value
;;; ---------------------------------------------------------------------------

(defn- value-renderer
  []
  (try
    (requiring-resolve 'seon.render.value/render-ai)
    (catch Throwable _ nil)))

(defn- bounded-placeholder
  [value caps]
  (let [admitted
        (admit/admit {:seon.sci.admit/value value
                      :seon.sci.admit/caps caps
                      :seon.sci.admit/interrupt-fn (fn [])
                      :seon.config/on-core-error :record})]
    (str (pr-str (:seon.sci.admit/value admitted))
         "\n; context-walk placeholder: seon.render.value was unavailable")))

(defn- render-value
  ([value effective caps]
   (render-value value effective caps nil))
  ([value effective caps options]
  (if-let [renderer (value-renderer)]
    (renderer
     (cond-> {:seon.render/value value
              :seon.config/effective effective
              :seon.sci.admit/caps caps}
       options (assoc :seon.render.value/options options)))
    (bounded-placeholder value caps))))

(defn- require-spec
  [{:seon.ns.require/keys [target alias as-alias? refers refer-all?]}]
  (vec
   (concat
    [target]
    (when alias [(if as-alias? :as-alias :as) alias])
    (when refer-all? [:refer :all])
    (when (seq refers) [:refer (vec (sort refers))]))))

(defn- namespace-row
  [db namespace-name]
  (d/pull db
          '[:db/id
            :seon.ns/name
            :seon.ns/doc
            :seon.ns/source
            {:seon.ns/require-edges
             [:seon.ns.require/target
              :seon.ns.require/alias
              :seon.ns.require/as-alias?
              :seon.ns.require/refers
              :seon.ns.require/refer-all?]}
            {:seon.fn/_ns
             [:seon.fn/sym
              :seon.fn/source
              :seon.fn/arglists
              :seon.fn/doc
              :seon.fn/private?]}
            {:seon.schema/_ns
             [:seon.schema/key
              :seon.schema/form]}]
          [:seon.ns/name namespace-name]))

(defn- namespace-form
  [row]
  (let [name (:seon.ns/name row)
        doc (:seon.ns/doc row)
        specs (->> (:seon.ns/require-edges row)
                   (remove :seon.ns.require/as-alias?)
                   (map require-spec)
                   (sort-by (comp str first))
                   vec)]
    (apply list
           (cond-> ['ns name]
             doc (conj doc)
             (seq specs) (conj (cons :require specs))))))

(defn- local-symbol
  [function-symbol]
  (let [function-symbol (if (symbol? function-symbol)
                          function-symbol
                          (symbol function-symbol))]
    (symbol (name function-symbol))))

(defn- compact-function
  [row]
  (str
   (when-let [doc (:seon.fn/doc row)]
     (str ";; " (first (str/split-lines doc)) "\n"))
   (when-let [arglists (:seon.fn/arglists row)]
     (str ";; " (local-symbol (:seon.fn/sym row)) " " arglists "\n"))
   "(declare " (local-symbol (:seon.fn/sym row)) ")"))

(defn- full-function
  [row]
  (if-let [source (:seon.fn/source row)]
    source
    (compact-function row)))

(defn- schema-statement
  [row]
  (str "(seon.schema/register! "
       (pr-str (:seon.schema/key row))
       " "
       (:seon.schema/form row)
       ")"))

(defn- namespace-section
  [row distance]
  (let [functions (sort-by (comp str :seon.fn/sym) (:seon.fn/_ns row))
        schemas (sort-by (comp str :seon.schema/key) (:seon.schema/_ns row))
        header (str ";;; namespace " (:seon.ns/name row)
                    " — distance " distance)]
    (str/join
     "\n\n"
     (remove
      str/blank?
      (concat
       [header (pr-str (namespace-form row))]
       (cond
         (> distance 1)
         (concat (map full-function functions)
                 (map schema-statement schemas))

         (pos? distance)
         (map compact-function functions)

         :else nil))))))

(defn- render-namespace-graph
  [db namespace-name distance]
  (letfn [(visit [name remaining visited]
            (if (contains? visited name)
              [visited []]
              (let [row (or (namespace-row db name)
                            {:seon.ns/name name})
                    visited (conj visited name)
                    targets (if (pos? remaining)
                              (->> (:seon.ns/require-edges row)
                                   (remove :seon.ns.require/as-alias?)
                                   (map :seon.ns.require/target)
                                   distinct
                                   (sort-by str))
                              [])
                    [visited dependency-sections]
                    (reduce
                     (fn [[seen sections] target]
                       (let [[seen additions]
                             (visit target (dec remaining) seen)]
                         [seen (into sections additions)]))
                     [visited []]
                     targets)]
                [visited
                 (conj dependency-sections
                       (namespace-section row remaining))])))]
    (str/join "\n\n" (second (visit namespace-name distance #{})))))

(defn- read-all-forms
  [text]
  (with-open [reader (PushbackReader. (StringReader. text))]
    (loop [forms []]
      (let [form (read {:eof ::eof} reader)]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(defn- trigger-message
  [db run-id]
  (when-let [trigger-id (message/trigger db run-id)]
    (d/pull
     db
     '[:db/id
       :seon.cluster.message/id
       :seon.cluster.message/content
       :seon.cluster.message/at
       {:seon.cluster.message/to [:seon.cluster.agent/id]}
       {:seon.cluster.message/from [:seon.cluster.agent/id]}
       {:seon.cluster.message/about
        [:seon.problems/id
         :seon.error/id
         :seon.error/kind
         :seon.error/message]}]
     [:seon.cluster.message/id trigger-id])))

(defn- trigger-shape
  [trigger]
  (let [about (:seon.cluster.message/about trigger)]
    (cond
      (:seon.error/id about) :error-wake
      (and (:seon.cluster.message/from trigger)
           (:seon.problems/id about)) :routed-problem
      :else :chat)))

(defn- mode
  [shape]
  (if (= shape :chat) :chat :goal-seeking))

(defn- shaped-trigger
  [trigger]
  (let [shape (trigger-shape trigger)
        base
        {:context-walk/trigger-shape shape
         :context-walk/mode (mode shape)
         :seon.cluster.message/id (:seon.cluster.message/id trigger)
         :seon.cluster.message/to
         (get-in trigger
                 [:seon.cluster.message/to :seon.cluster.agent/id])
         :seon.cluster.message/content
         (:seon.cluster.message/content trigger)}]
    (cond-> base
      (:seon.cluster.message/from trigger)
      (assoc :seon.cluster.message/from
             (get-in trigger
                     [:seon.cluster.message/from :seon.cluster.agent/id]))

      (:seon.cluster.message/about trigger)
      (assoc :seon.cluster.message/about
             (dissoc (:seon.cluster.message/about trigger) :db/id)))))

(defn- agent-namespace
  [db agent-id]
  (d/q '[:find ?namespace-name .
         :in $ ?agent-id
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?agent :seon.cluster.agent/namespace ?namespace]
         [?namespace :seon.ns/name ?namespace-name]]
       db agent-id))

(defn- walk-context
  [db capture]
  (let [agent-id (::agent-id capture)
        run-id (::run-id capture)
        effective (config/effective db cluster-name)
        caps (config/result-caps effective)
        trigger (trigger-message db run-id)
        shaped (shaped-trigger trigger)
        namespace-name (agent-namespace db agent-id)
        code (render-namespace-graph db namespace-name 2)
        _ (read-all-forms
           (str/replace code #"(?m)^;;;.*$" ""))]
    (str
     ";;; static execution scaffold\n"
     (context/execution-ai {})
     "\n\n;;; agent entity\n"
     (render-value
      {:seon.cluster.agent/id agent-id
       :seon.cluster.agent/namespace namespace-name}
      effective caps)
     "\n\n;;; namespace walk — requires followed to distance 2\n"
     code
     "\n\n;;; trigger rendered by shape\n"
     (render-value
      shaped effective caps
      ;; Message content is the work, not preview prose. The universal
      ;; renderer remains the floor, but this shape-specific request spends
      ;; the available string budget so an instruction is not silently clipped.
      {:seon.render.value/max-depth 8
       :seon.render.value/max-collection 16
       :seon.render.value/max-map-visits 32
       :seon.render.value/max-string 4096
       :seon.render.value/shape-sample 8
       :seon.render.value/width 100}))))

(defn- file-stem
  [case-key]
  (name case-key))

(defn- write-edn!
  [path value]
  (spit path
        (with-out-str
          (binding [pprint/*print-right-margin* 100]
            (pprint/pprint value)))))

(defn- write-artifacts!
  [connection captures]
  (let [head @connection
        results
        (mapv
         (fn [capture]
           (let [case-key (::case capture)
                 stem (file-stem case-key)
                 prompt (::prompt capture)
                 basis (d/as-of head (::basis-t capture))
                 shadow (walk-context basis capture)
                 block-path (str s0-root "/" stem ".prompt.txt")
                 side-by-side-path
                 (str s1-root "/" stem ".side-by-side.txt")]
             ;; No newline is added: the S0 file is byte-for-byte the capture.
             (spit block-path prompt)
             (spit side-by-side-path
                   (str "=== BLOCK CONTEXT — exact captured bytes ===\n"
                        prompt
                        "\n\n=== WALK CONTEXT — shadow only ===\n"
                        shadow))
             {::case case-key
              ::agent-id (::agent-id capture)
              ::trigger-shape (::trigger-shape capture)
              ::run-id (::run-id capture)
              ::capture-id (::capture-id capture)
              ::basis-t (::basis-t capture)
              ::block-file block-path
              ::block-bytes (utf8-bytes prompt)
              ::block-estimated-tokens (tokens/estimate prompt)
              ::walk-file side-by-side-path
              ::walk-bytes (utf8-bytes shadow)
              ::walk-estimated-tokens (tokens/estimate shadow)
              ::value-renderer
              (if (value-renderer)
                :seon.render.value/render-ai
                :bounded-pr-str-placeholder)}))
         captures)]
    (write-edn! (str s0-root "/metrics.edn")
                (mapv #(select-keys %
                                   [::case ::agent-id ::trigger-shape
                                    ::run-id ::capture-id ::basis-t
                                    ::block-file ::block-bytes
                                    ::block-estimated-tokens])
                      results))
    (write-edn! (str s1-root "/metrics.edn") results)
    results))

(defn- execute!
  []
  (delete-scratch-root!)
  (ensure-output-directories!)
  (stamp "starting scratch cluster " cluster-name " (never default)")
  (let [instance (cluster/start!
                  {:seon.boot/cluster-name cluster-name
                   :seon.boot/root scratch-root})
        connection (:seon.boot/cluster-connection instance)]
    (try
      (d/transact connection
                  (agent/creation-tx
                   {:seon.cluster.agent/id "helper"
                    :seon.ns/name 'my.agents.helper}))
      (stamp "created helper twin; root twin came from ordinary boot")
      (let [captures (run-s0! instance connection)
            results (write-artifacts! connection captures)]
        (doseq [result results]
          (stamp (name (::case result))
                 " block=" (::block-bytes result) " bytes/"
                 (::block-estimated-tokens result) " est. tokens"
                 " walk=" (::walk-bytes result) " bytes/"
                 (::walk-estimated-tokens result) " est. tokens"
                 " floor=" (::value-renderer result)))
        results)
      (finally
        (cluster/stop! instance)
        (stamp "stopped scratch cluster " cluster-name)))))

(execute!)
