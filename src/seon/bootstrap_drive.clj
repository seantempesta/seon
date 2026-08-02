(ns seon.bootstrap-drive
  "Run and grade bounded bootstrap-vector drives in fact-space.

  The maintained entry point is deliberately one-drive shaped. It boots one
  repository-local scratch root, creates fresh agents through the production
  creation seam, waits for their seeded bootstrap runs, sends one outside
  objective message, forks the ending commit for grading, and writes one EDN
  report per attempt under `tmp/bootstrap-drives/`.

  Invoke it with one optional EDN request map:

    clojure -M:dev -m seon.bootstrap-drive
    clojure -M:dev -m seon.bootstrap-drive REQUEST_EDN"
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.message :as message]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.print :as print]
            [seon.render.transcript :as transcript]
            [seon.sci.eval :as sci.eval])
  (:import [java.util Date UUID]))

(def ^:private default-run-cap 6)

(def ^:private o1-rows
  (mapv (fn [ordinal]
          {:label (if (= ordinal 1) "user" "agent")
           :amount ordinal})
        (range 13)))

(def ^:private o1-held-out-rows
  [{:label "east" :amount 8}
   {:label "west" :amount 3}
   {:label "east" :amount -2}])

(def ^:private o1-answer {"user" 1 "agent" 77})
(def ^:private o1-held-out-answer {"east" 6 "west" 3})

(def objectives
  "The five ruled generation-zero objectives and their agent counts."
  {:o1
   {:seon.bootstrap-drive/agents 1
    :seon.bootstrap-drive/objective
    (str
     "Your bootstrap run's ordered forms are rows already committed as facts. "
     "Query their :seon.cluster.run.form/ordinal and namespace; map the one "
     "user-namespace form to label \"user\" and the agent-namespace forms to "
     "label \"agent\", with each ordinal as amount. The resulting rows are "
     (pr-str o1-rows) ". Define a permanent contracted function in your "
     "namespace that accepts rows shaped exactly like these and returns a "
     "map from each label string to its total amount. Run it on these rows, "
     "then complete with exactly this EDN answer and no prose: "
     (pr-str o1-answer) ".")}

   :o2
   {:seon.bootstrap-drive/agents 1
    :seon.bootstrap-drive/objective
    (str
     "Find, by querying the program graph rather than relying on its name, "
     "the function in this cluster that accepts :my.run/result. Use that "
     "function to end this run with the exact text discovered-by-contract.")}

   :o3
   {:seon.bootstrap-drive/agents 1
    :seon.bootstrap-drive/objective
    (str
     "How many public functions does my.message have in the current program "
     "graph? Derive the answer from facts, then complete with only the decimal "
     "integer and no prose.")}

   :o4
   {:seon.bootstrap-drive/agents 2
    :seon.bootstrap-drive/objective
    (str
     "Ask agent %PEER% to author a permanent contracted function that doubles "
     "an integer and tell you its qualified symbol. Wait if needed. After the "
     "reply, call that function on 21 and complete with only 42.")}

   :o5
   {:seon.bootstrap-drive/agents 1
    :seon.bootstrap-drive/objective
    (str
     "Define a permanent contracted function named summarize-row that accepts "
     "one map with :label string and :amount integer, but on your first attempt "
     "write the obvious open input-map contract. Read the refusal, repair the "
     "same function with the required closed input map, run it, and complete "
     "with only repaired.")}})

(defn- uuid-text [] (str (UUID/randomUUID)))

(defn- agent-namespace [agent-id]
  (sci.eval/agent-namespace agent-id))

(defn- creation-request [cluster-name agent-id]
  {:seon.cluster.agent/id agent-id
   :seon.cluster/name cluster-name
   :seon.ns/name (agent-namespace agent-id)})

(defn- objective-data [objective peer-id]
  (let [definition
        (or (get objectives objective)
            (throw (ex-info "Unknown bootstrap objective."
                            {:seon.bootstrap-drive/objective objective
                             :seon.bootstrap-drive/known
                             (set (keys objectives))})))]
    (update definition :seon.bootstrap-drive/objective
            str/replace "%PEER%" (or peer-id ""))))

(defn- bootstrap-complete? [db agent-id]
  (let [run-id (bootstrap/run-id agent-id)]
    (when-let [closed-at
               (d/q '[:find ?closed .
                      :in $ ?run-id
                      :where
                      [?run :seon.cluster.run/id ?run-id]
                      [?run :seon.cluster.run/closed-at ?closed]]
                    db run-id)]
      (let [receipt-count
            (or (d/q '[:find (count ?receipt) .
                       :in $ ?run-id
                       :where
                       [?run :seon.cluster.run/id ?run-id]
                       [?receipt :seon.cluster.eval/run ?run]]
                     db run-id)
                0)]
        (when (= (count (bootstrap/sources (agent-namespace agent-id)))
                 receipt-count)
          {:seon.cluster.run/id run-id
           :seon.cluster.run/closed-at closed-at
           :seon.bootstrap-drive/receipt-count receipt-count})))))

(defn- await-fact!
  "Wait for a database fact. The timeout guards only the remote-provider run."
  [connection timeout-ms label probe]
  (let [events (async/promise-chan)
        listener-key (keyword "seon.bootstrap-drive" (uuid-text))]
    (d/listen connection listener-key
              (fn [report]
                (when-let [value (probe (:db-after report))]
                  (async/offer! events value))))
    (try
      (when-let [value (probe @connection)]
        (async/offer! events value))
      (let [[value selected]
            (async/alts!! [events (async/timeout timeout-ms)] :priority true)]
        (when-not (= selected events)
          (throw (ex-info (str "Timed out awaiting " label ".")
                          {:seon.bootstrap-drive/label label
                           :seon.bootstrap-drive/timeout-ms timeout-ms})))
        value)
      (finally
        (d/unlisten connection listener-key)))))

(defn- inbound!
  [connection cluster-name process agent-id content]
  (let [caps (config/result-caps
              (config/effective @connection cluster-name))
        request {:seon.cluster.agent/id agent-id
                 :seon.cluster.message/inbound-content content
                 :seon.cluster.message/at (Date.)
                 :seon.config.eval.result/max-string
                 (:seon.config.eval.result/max-string caps)}
        before (message/inbound-tx @connection request)]
    (when-not (vector? before)
      (throw (ex-info "The objective message was refused."
                      {:seon.bootstrap-drive/refusal before})))
    (d/transact connection
                {:tx-data [[:db.fn/call #'message/inbound-tx request]]
                 :tx-meta {:seon.db/process
                           [:seon.db.process/id process]}})
    (or (d/q '[:find ?id .
               :in $ ?agent-id ?content
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?message :seon.cluster.message/to ?agent]
               [?message :seon.cluster.message/content ?content]
               [?message :seon.cluster.message/id ?id]]
             @connection agent-id content)
        (throw (ex-info "The committed objective message has no identity."
                        {:seon.cluster.agent/id agent-id})))))

(defn- objective-run-ids [db message-id]
  (->> (d/q '[:find ?run-id ?opened-tx
              :in $ ?message-id
              :where
              [?message :seon.cluster.message/id ?message-id]
              [?opened-tx :seon.db/trigger ?message]
              [?run :seon.cluster.run/id ?run-id ?opened-tx]]
            db message-id)
       (sort-by second)
       (mapv first)))

(defn- read-result [serialized]
  (when (string? serialized)
    (try
      (let [parsed (edn/read-string {:default (fn [_ value] value)}
                                    serialized)]
        (if (:seon.print/face parsed)
          (edn/read-string {:default (fn [_ value] value)}
                           (print/emit-text parsed {}))
          parsed))
      (catch Throwable _ ::unreadable))))

(defn- run-receipts [db run-ids]
  (if (seq run-ids)
    (->> (d/q '[:find ?run-id ?ordinal ?source ?result ?error ?error-kind ?at
                :in $ [?run-id ...]
                :where
                [?run :seon.cluster.run/id ?run-id]
                [?form :seon.cluster.run.form/run ?run]
                [?form :seon.cluster.run.form/ordinal ?ordinal]
                [?form :seon.cluster.run.form/source ?source]
                [?receipt :seon.cluster.eval/run ?run]
                [?receipt :seon.cluster.eval/ordinal ?ordinal]
                [?receipt :seon.cluster.eval/at ?at]
                [(get-else $ ?receipt :seon.cluster.eval/result-edn "") ?result]
                [(get-else $ ?receipt :seon.cluster.eval/error "") ?error]
                [(get-else $ ?receipt :seon.error/kind :seon.bootstrap-drive/absent)
                 ?error-kind]]
              db run-ids)
         (sort-by (juxt #(inst-ms (nth % 6)) second))
         (mapv (fn [[run-id ordinal source result error error-kind at]]
                 {:seon.cluster.run/id run-id
                  :seon.cluster.eval/ordinal ordinal
                  :seon.cluster.run.form/source source
                  :seon.cluster.eval/result-edn result
                  :seon.cluster.eval/value (read-result result)
                  :seon.cluster.eval/error error
                  :seon.error/kind error-kind
                  :seon.cluster.eval/at at})))
    []))

(defn- completion-values [receipts]
  (into []
        (comp (map :seon.cluster.eval/value)
              (filter #(= :completed (:my.run/disposition %))))
        receipts))

(defn- terminal-state [db agent-id process message-id run-cap]
  (let [run-ids (objective-run-ids db message-id)
        receipts (run-receipts db run-ids)
        completions (completion-values receipts)
        closed-count
        (if (seq run-ids)
          (count
           (d/q '[:find [?run ...]
                  :in $ [?run-id ...]
                  :where
                  [?run :seon.cluster.run/id ?run-id]
                  [?run :seon.cluster.run/closed-at _]]
                db run-ids))
          0)
        idle? (and (seq run-ids)
                   (nil? (work/next-agent-work
                          db
                          {:seon.cluster.agent/id agent-id
                           :seon.cluster.run/process process})))]
    (cond
      (seq completions)
      {:seon.bootstrap-drive/outcome :completed
       :seon.bootstrap-drive/run-ids run-ids}

      (and idle? (>= closed-count run-cap))
      {:seon.bootstrap-drive/outcome :capped
       :seon.bootstrap-drive/run-ids run-ids}

      (and idle? (= closed-count (count run-ids)))
      {:seon.bootstrap-drive/outcome :stopped
       :seon.bootstrap-drive/run-ids run-ids}

      :else nil)))

(defn- candidate-functions [db run-ids namespace-name]
  (if (seq run-ids)
    (->> (d/q '[:find ?sym ?spec ?run-id ?ordinal
                :in $ [?run-id ...] ?namespace-name
                :where
                [?namespace :seon.ns/name ?namespace-name]
                [?function :seon.fn/ns ?namespace]
                [?function :seon.fn/sym ?sym ?tx]
                [?function :seon.fn/spec ?spec]
                [?run :seon.cluster.run/id ?run-id]
                [?receipt :seon.cluster.eval/run ?run]
                [?receipt :seon.cluster.eval/ordinal ?ordinal]
                [?receipt :seon.cluster.eval/result-edn _ ?tx]]
              db run-ids namespace-name)
         (sort-by (juxt #(nth % 2) #(nth % 3) first))
         (mapv (fn [[sym spec run-id ordinal]]
                 {:seon.fn/sym sym
                  :seon.fn/spec spec
                  :seon.cluster.run/id run-id
                  :seon.cluster.eval/ordinal ordinal})))
    []))

(defn- evaluate-function
  [connection cluster-name agent-id function-symbol argument]
  (let [db @connection
        caps (config/result-caps (config/effective db cluster-name))
        evaluation
        (sci.eval/evaluate
         {:seon.sci.eval/ctx (sci.eval/cluster-ctx db connection)
          :seon.cluster.run.form/source
          (str "(" function-symbol " " (pr-str argument) ")")
          :seon.cluster.run.form/ns
          [:seon.ns/name (agent-namespace agent-id)]
          :seon.cluster.agent/id agent-id
          :seon.sci.admit/caps caps
          :seon.sci.eval/time-limit-ms 30000
          :seon.config/on-core-error :panic})]
    {:seon.bootstrap-drive/value (:seon.sci.admit/value evaluation)
     :seon.cluster.eval/error (:seon.cluster.eval/error evaluation)}))

(defn- completed-result [receipts]
  (:my.run/result (last (completion-values receipts))))

(defn- grade-o1 [connection cluster-name agent-id run-ids]
  (let [db @connection
        receipts (run-receipts db run-ids)
        candidates (candidate-functions db run-ids (agent-namespace agent-id))
        executions
        (mapv (fn [{function-symbol :seon.fn/sym :as candidate}]
                (assoc candidate
                       :seon.bootstrap-drive/execution
                       (evaluate-function connection cluster-name agent-id
                                          function-symbol o1-held-out-rows)))
              candidates)
        working
        (some #(when (= o1-held-out-answer
                         (get-in % [:seon.bootstrap-drive/execution
                                    :seon.bootstrap-drive/value]))
                 %)
              executions)]
    {:p1a (boolean (seq candidates))
     :p1b (boolean working)
     :p1c (= (pr-str o1-answer) (completed-result receipts))
     :seon.bootstrap-drive/candidates executions
     :seon.bootstrap-drive/completed-result (completed-result receipts)}))

(defn- grade-o2 [db run-ids]
  (let [receipts (run-receipts db run-ids)]
    {:p2a
     (boolean
      (some #(re-find #":seon\.fn(?:\.arity/input-refs|/spec)"
                      (:seon.cluster.run.form/source %))
            receipts))
     :p2b (= "discovered-by-contract" (completed-result receipts))}))

(defn- public-my-message-count [db]
  (or (d/q '[:find (count ?function) .
             :where
             [?namespace :seon.ns/name my.message]
             [?function :seon.fn/ns ?namespace]
             [?function :seon.fn/private? false]]
           db)
      0))

(defn- decimal [text]
  (when (and (string? text) (re-matches #"[0-9]+" text))
    (parse-long text)))

(defn- grade-o3 [db run-ids]
  (let [expected (public-my-message-count db)
        actual (decimal (completed-result (run-receipts db run-ids)))]
    {:p3 (= expected actual)
     :seon.bootstrap-drive/expected expected
     :seon.bootstrap-drive/actual actual}))

(defn- messages-between? [db from-id to-id]
  (boolean
   (d/q '[:find ?message .
          :in $ ?from-id ?to-id
          :where
          [?from :seon.cluster.agent/id ?from-id]
          [?to :seon.cluster.agent/id ?to-id]
          [?message :seon.cluster.message/from ?from]
          [?message :seon.cluster.message/to ?to]]
        db from-id to-id)))

(defn- grade-o4 [db agent-id peer-id run-ids]
  (let [receipts (run-receipts db run-ids)
        peer-message-ids
        (d/q '[:find [?message-id ...]
               :in $ ?from-id ?to-id
               :where
               [?from :seon.cluster.agent/id ?from-id]
               [?to :seon.cluster.agent/id ?to-id]
               [?message :seon.cluster.message/from ?from]
               [?message :seon.cluster.message/to ?to]
               [?message :seon.cluster.message/id ?message-id]]
             db agent-id peer-id)
        peer-run-ids (into [] (mapcat #(objective-run-ids db %))
                           peer-message-ids)
        peer-functions
        (mapv :seon.fn/sym
              (candidate-functions db peer-run-ids
                                   (agent-namespace peer-id)))
        called
        (some (fn [function-symbol]
                (some #(when (and
                              (str/includes?
                               (:seon.cluster.run.form/source %)
                               function-symbol)
                              (str/blank? (:seon.cluster.eval/error %)))
                         function-symbol)
                      receipts))
              peer-functions)]
    {:p4a (and (messages-between? db agent-id peer-id)
               (messages-between? db peer-id agent-id))
     :p4b (boolean (seq peer-functions))
     :p4c (boolean called)
     :p4d (= "42" (completed-result receipts))
     :seon.bootstrap-drive/peer-functions (vec (sort peer-functions))
     :seon.bootstrap-drive/called called}))

(defn- defined-name [source]
  (some->> source (re-find #"\(defn\s+([^\s\[\](){}]+)") second))

(defn- grade-o5 [db agent-id run-ids]
  (let [receipts (run-receipts db run-ids)
        refused-names
        (into #{}
              (comp
               (filter
                #(= :seon.schema/open-argument-map
                    (get-in % [:seon.cluster.eval/value
                               :seon.error/data
                               :seon.sci.eval/data
                               :seon.schema/error])))
               (keep #(defined-name (:seon.cluster.run.form/source %))))
              receipts)
        repaired
        (into #{}
              (comp
               (map :seon.fn/sym)
               (map #(last (str/split % #"/"))))
              (candidate-functions db run-ids (agent-namespace agent-id)))]
    {:p5 (boolean (seq (set/intersection refused-names repaired)))
     :seon.bootstrap-drive/refused-names refused-names
     :seon.bootstrap-drive/repaired-names repaired}))

(defn- grade!
  [connection cluster-name objective agent-id peer-id message-id]
  (let [db @connection
        run-ids (objective-run-ids db message-id)]
    (case objective
      :o1 (grade-o1 connection cluster-name agent-id run-ids)
      :o2 (grade-o2 db run-ids)
      :o3 (grade-o3 db run-ids)
      :o4 (grade-o4 db agent-id peer-id run-ids)
      :o5 (grade-o5 db agent-id run-ids))))

(defn- full-transcript [db agent-id cluster-name]
  (transcript/render-ai
   {:seon.db/db db
    :seon.cluster.agent/id agent-id
    :seon.sci.admit/caps
    (config/result-caps (config/effective db cluster-name))
    ::transcript/token-budget 1000000000}))

(defn- grading-branch! [store ending-commit drive-id]
  (let [branch (keyword (str "bootstrap-grade-" drive-id))]
    (registry/branch! {:seon.store/store store
                       :seon.cluster.registry/from ending-commit
                       :seon.store/branch branch})
    branch))

(defn- write-report! [report]
  (let [directory (io/file "tmp" "bootstrap-drives")
        file (io/file directory
                      (str (:seon.bootstrap-drive/id report) ".edn"))]
    (.mkdirs directory)
    (spit file (str (pr-str report) "\n"))
    (.getPath file)))

(defn- failed-report [objective attempt failure]
  (let [report
        {:seon.bootstrap-drive/id
         (str (name objective) "-" attempt "-failed-"
              (subs (uuid-text) 0 8))
         :seon.bootstrap-drive/objective objective
         :seon.bootstrap-drive/attempt attempt
         :seon.bootstrap-drive/terminal
         {:seon.bootstrap-drive/outcome :failed}
         :seon.bootstrap-drive/error
         {:seon.error/class (.getName (class failure))
          :seon.error/message (or (ex-message failure)
                                  (.getName (class failure)))}}
        path (write-report! report)]
    (assoc report :seon.bootstrap-drive/report-path path)))

(defn- one-drive!
  [instance objective attempt run-cap remote-timeout-ms]
  (let [connection (:seon.boot/cluster-connection instance)
        process (cluster/process-identity (:seon.boot/advertisement instance))
        cluster-name (get-in instance [:seon.boot/config
                                       :seon.boot/cluster-name])
        drive-id (str (name objective) "-" attempt "-"
                      (subs (uuid-text) 0 8))
        agent-id (str "bootstrap-" drive-id)
        peer-id (when (= :o4 objective) (str agent-id "-peer"))
        definition (objective-data objective peer-id)
        agent-ids (cond-> [agent-id] peer-id (conj peer-id))]
    (doseq [id agent-ids]
      (cluster/ensure-entity! connection process
                              (creation-request cluster-name id)))
    (doseq [id agent-ids]
      (await-fact! connection 120000 (str "bootstrap " id)
                   #(bootstrap-complete? % id)))
    (let [message-id
          (inbound! connection cluster-name process agent-id
                    (:seon.bootstrap-drive/objective definition))
          terminal
          (await-fact!
           connection remote-timeout-ms (str "objective " drive-id)
           #(terminal-state % agent-id process message-id run-cap))
          ending-db @connection
          ending-commit (d/commit-id ending-db)
          transcript-text (full-transcript ending-db agent-id cluster-name)
          store (:seon.store/store instance)
          grade-branch (grading-branch! store ending-commit drive-id)
          grade-connection (store/open-branch! store grade-branch)]
      (try
        (let [grade (grade! grade-connection cluster-name objective agent-id
                            peer-id message-id)
              report
              {:seon.bootstrap-drive/id drive-id
               :seon.bootstrap-drive/objective objective
               :seon.bootstrap-drive/attempt attempt
               :seon.bootstrap-drive/agent-id agent-id
               :seon.bootstrap-drive/peer-id peer-id
               :seon.bootstrap-drive/model
               (:seon.config.ai/model
                (config/effective ending-db cluster-name))
               :seon.bootstrap-drive/thinking
               (:seon.config.ai/thinking
                (config/effective ending-db cluster-name))
               :seon.bootstrap-drive/message-id message-id
               :seon.bootstrap-drive/ending-commit ending-commit
               :seon.bootstrap-drive/terminal terminal
               :seon.bootstrap-drive/grade grade
               :seon.bootstrap-drive/transcript transcript-text}
              path (write-report! report)]
          (assoc report :seon.bootstrap-drive/report-path path))
        (finally
          (d/release grade-connection)
          (registry/retire-branch!
           {:seon.store/store store :seon.store/branch grade-branch}))))))

(defn- run-drives!
  "Run `:runs` bounded attempts and return their report maps.

  No matrix is implicit: objective defaults to O1 and runs defaults to one."
  [{objective :seon.bootstrap-drive/objective
    runs :seon.bootstrap-drive/runs
    run-cap :seon.bootstrap-drive/run-cap
    remote-timeout-ms :seon.bootstrap-drive/remote-timeout-ms
    :or {objective :o1
         runs 1
         run-cap default-run-cap}}]
  (when-not (and (pos-int? runs) (pos-int? run-cap))
    (throw (ex-info "Drive runs and run cap must be positive integers."
                    {:seon.bootstrap-drive/runs runs
                     :seon.bootstrap-drive/run-cap run-cap})))
  (let [invocation-id (subs (uuid-text) 0 8)
        cluster-name (str "bootstrap-drive-" invocation-id)
        process-root (str "tmp/bootstrap-drives/" invocation-id "/clusters")
        timeout-ms (or remote-timeout-ms (* run-cap 240000))]
    (cluster/refresh-source! process-root)
    (let [instance
          (cluster/start!
           {:seon.boot/cluster-name cluster-name
            :seon.boot/root process-root
            :seon.config/manifest
            {:seon.config.run/max-episode-runs run-cap}})]
      (try
        (mapv (fn [attempt]
                (try
                  (one-drive! instance objective attempt run-cap timeout-ms)
                  (catch Throwable failure
                    (failed-report objective attempt failure))))
              (range 1 (inc runs)))
        (finally
          (cluster/stop! instance))))))

(defn -main
  "Run the requested drives and print each report path with its grade."
  {:malli/schema [:=> [:cat [:* :string]] [:vector :map]]}
  [& arguments]
  (let [request
        (case (count arguments)
          0 {}
          1 (edn/read-string (first arguments))
          (throw (ex-info "Expected zero arguments or one EDN request map."
                          {:seon.bootstrap-drive/arguments arguments})))
        reports (run-drives! request)]
    (doseq [report reports]
      (prn (select-keys report
                        [:seon.bootstrap-drive/id
                         :seon.bootstrap-drive/report-path
                         :seon.bootstrap-drive/grade])))
    reports))
