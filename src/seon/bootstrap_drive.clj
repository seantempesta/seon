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
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.eval.drive :as eval.drive]
            [seon.sci.eval :as sci.eval])
  (:import [java.util UUID]))

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
     "use :any for that row shape. Read the refusal, repair the same function "
     "with the honest concrete open map, run it, and complete "
     "with only repaired.")}})

(defn- uuid-text [] (str (UUID/randomUUID)))

(defn- agent-namespace [db agent-id]
  (sci.eval/agent-namespace db agent-id))

(defn- objective-data [objective peer-id]
  (let [definition
        (or (get objectives objective)
            (throw (ex-info "Unknown bootstrap objective."
                            {:seon.bootstrap-drive/objective objective
                             :seon.bootstrap-drive/known
                             (set (keys objectives))})))]
    (update definition :seon.bootstrap-drive/objective
            str/replace "%PEER%" (or peer-id ""))))

(defn- objective-run-ids [db message-id]
  (->> (db/q '[:find ?run-id ?opened-tx
              :in $ ?message-id
              :where
              [?message :seon.cluster.message/id ?message-id]
              [?run :seon.cluster.run/trigger ?message]
              [?run :seon.cluster.run/id ?run-id ?opened-tx]]
            db message-id)
       (sort-by second)
       (mapv first)))

(defn- candidate-functions [db run-ids namespace-name]
  (if (seq run-ids)
    (->> (db/q '[:find ?sym ?spec ?run-id ?ordinal
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
          [:seon.ns/name (agent-namespace db agent-id)]
          :seon.cluster.agent/id agent-id
          :seon.sci.admit/caps caps
          :seon.sci.eval/time-limit-ms 30000
          :seon.config/on-core-error :panic})]
    {:seon.bootstrap-drive/value (:seon.sci.admit/value evaluation)
     :seon.cluster.eval/error (:seon.cluster.eval/error evaluation)}))

(defn- grade-o1
  [connection cluster-name agent-id run-ids completed-result]
  (let [db @connection
        candidates (candidate-functions db run-ids (agent-namespace db agent-id))
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
     :p1c (= (pr-str o1-answer) completed-result)
     :seon.bootstrap-drive/candidates executions
     :seon.bootstrap-drive/completed-result completed-result}))

(defn- grade-o2 [receipts completed-result]
  {:p2a
   (boolean
    (some #(re-find #":seon\.fn(?:\.arity/input-refs|/spec)"
                    (:seon.cluster.run.form/source %))
          receipts))
   :p2b (= "discovered-by-contract" completed-result)})

(defn- public-my-message-count [db]
  (or (db/q '[:find (count ?function) .
             :where
             [?namespace :seon.ns/name my.message]
             [?function :seon.fn/ns ?namespace]
             [?function :seon.fn/private? false]]
           db)
      0))

(defn- decimal [text]
  (when (and (string? text) (re-matches #"[0-9]+" text))
    (parse-long text)))

(defn- grade-o3 [db completed-result]
  (let [expected (public-my-message-count db)
        actual (decimal completed-result)]
    {:p3 (= expected actual)
     :seon.bootstrap-drive/expected expected
     :seon.bootstrap-drive/actual actual}))

(defn- messages-between? [db from-id to-id]
  (boolean
   (db/q '[:find ?message .
          :in $ ?from-id ?to-id
          :where
          [?from :seon.cluster.agent/id ?from-id]
          [?to :seon.cluster.agent/id ?to-id]
          [?message :seon.cluster.message/from ?from]
          [?message :seon.cluster.message/to ?to]]
        db from-id to-id)))

(defn- grade-o4
  [db agent-id peer-id receipts completed-result]
  (let [peer-message-ids
        (db/q '[:find [?message-id ...]
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
                                   (agent-namespace db peer-id)))
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
     :p4d (= "42" completed-result)
     :seon.bootstrap-drive/peer-functions (vec (sort peer-functions))
     :seon.bootstrap-drive/called called}))

(defn- defined-name [source]
  (some->> source (re-find #"\(defn\s+([^\s\[\](){}]+)") second))

(defn- grade-o5 [db agent-id run-ids receipts]
  (let [refused-names
        (into #{}
              (comp
               (filter
                #(= :seon.schema/undefined-contract
                    (get-in % [:seon.eval.drive/value
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
              (candidate-functions db run-ids (agent-namespace db agent-id)))]
    {:p5 (boolean (seq (set/intersection refused-names repaired)))
     :seon.bootstrap-drive/refused-names refused-names
     :seon.bootstrap-drive/repaired-names repaired}))

(defn- grade!
  [connection cluster-name objective agent-id peer-id episode]
  (let [db @connection
        run-ids (:seon.eval.drive/run-ids episode)
        receipts (:seon.eval.drive/receipts episode)
        completed-result (:seon.eval.drive/completed-result episode)]
    (case objective
      :o1 (grade-o1 connection cluster-name agent-id run-ids
                    completed-result)
      :o2 (grade-o2 receipts completed-result)
      :o3 (grade-o3 db completed-result)
      :o4 (grade-o4 db agent-id peer-id receipts completed-result)
      :o5 (grade-o5 db agent-id run-ids receipts))))

(defn- write-report! [report]
  (let [directory (io/file "tmp" "bootstrap-drives")
        file (io/file directory
                      (str (:seon.bootstrap-drive/id report) ".edn"))]
    (.mkdirs directory)
    (spit file (str (pr-str report) "\n"))
    (.getPath file)))

(defn- payment-required?
  [report]
  (boolean
   (some #(= 402 (:seon.ai/http-status %))
         (:seon.bootstrap-drive/model-attempts report))))

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
         {:seon.error/throwable-class (.getName (class failure))
          :seon.error/message (or (ex-message failure)
                                  (.getName (class failure)))}}
        path (write-report! report)]
    (assoc report :seon.bootstrap-drive/report-path path)))

(defn- one-drive!
  [instance objective attempt run-cap remote-timeout-ms]
  (let [cluster-name (get-in instance [:seon.boot/config
                                       :seon.boot/cluster-name])
        drive-id (str (name objective) "-" attempt "-"
                      (subs (uuid-text) 0 8))
        agent-id (str "bootstrap-" drive-id)
        peer-id (when (= :o4 objective) (str agent-id "-peer"))
        definition (objective-data objective peer-id)
        agent-ids (cond-> [agent-id] peer-id (conj peer-id))
        episode
        (eval.drive/run-episode!
         instance
         {:seon.eval.drive/id drive-id
          :seon.eval.drive/objective
          (:seon.bootstrap-drive/objective definition)
          :seon.eval.drive/agent-ids agent-ids
          :seon.eval.drive/run-cap run-cap
          :seon.eval.drive/remote-timeout-ms remote-timeout-ms})
        store (:seon.store/store instance)
        grade-branch (:seon.eval.drive/grading-branch episode)
        grade-connection (store/open-branch! store grade-branch)]
    (try
        (let [grade (grade! grade-connection cluster-name objective agent-id
                            peer-id episode)
              episode-terminal (:seon.eval.drive/terminal episode)
              report
              {:seon.bootstrap-drive/id drive-id
               :seon.bootstrap-drive/objective objective
               :seon.bootstrap-drive/attempt attempt
               :seon.bootstrap-drive/agent-id agent-id
               :seon.bootstrap-drive/peer-id peer-id
               :seon.bootstrap-drive/model
               (:seon.eval.drive/model episode)
               :seon.bootstrap-drive/thinking
               (:seon.eval.drive/thinking episode)
               :seon.bootstrap-drive/model-attempts
               (:seon.eval.drive/model-attempts episode)
               :seon.bootstrap-drive/message-id
               (:seon.eval.drive/message-id episode)
               :seon.bootstrap-drive/ending-commit
               (:seon.eval.drive/ending-commit episode)
               :seon.bootstrap-drive/terminal
               {:seon.bootstrap-drive/outcome
                (:seon.eval.drive/outcome episode-terminal)
                :seon.bootstrap-drive/run-ids
                (:seon.eval.drive/run-ids episode-terminal)}
               :seon.bootstrap-drive/grade grade
               :seon.bootstrap-drive/transcript
               (:seon.eval.drive/transcript episode)}
              path (write-report! report)]
          (assoc report :seon.bootstrap-drive/report-path path))
      (finally
        (d/release grade-connection)
        (registry/retire-branch!
         {:seon.store/store store :seon.store/branch grade-branch})))))

(defn- run-drives!
  "Run `:runs` bounded attempts and return their report maps.

  No matrix is implicit: objective defaults to O1 and runs defaults to one."
  [request]
  (let [{objective :seon.bootstrap-drive/objective
         runs :seon.bootstrap-drive/runs
         run-cap :seon.bootstrap-drive/run-cap
         remote-timeout-ms :seon.bootstrap-drive/remote-timeout-ms
         :or {objective :o1
              runs 1
              run-cap default-run-cap}}
        request
        invocation-id (subs (uuid-text) 0 8)
        cluster-name (str "bootstrap-drive-" invocation-id)
        process-root (str "tmp/bootstrap-drives/" invocation-id "/clusters")
        timeout-ms (or remote-timeout-ms (* run-cap 240000))]
    (when-not (and (pos-int? runs) (pos-int? run-cap))
      (throw (ex-info "Drive runs and run cap must be positive integers."
                      {:seon.bootstrap-drive/runs runs
                       :seon.bootstrap-drive/run-cap run-cap})))
    (cluster/refresh-source! process-root)
    (let [instance
          (cluster/start!
           {:seon.boot/cluster-name cluster-name
            :seon.boot/root process-root
            :seon.config/manifest
            {:seon.config.run/max-episode-runs run-cap}})]
      (try
        (reduce
         (fn [reports attempt]
           (let [report
                 (try
                   (one-drive! instance objective attempt run-cap timeout-ms)
                   (catch Throwable failure
                     (failed-report objective attempt failure)))
                 reports (conj reports report)]
             (if (payment-required? report)
               (reduced reports)
               reports)))
         []
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
