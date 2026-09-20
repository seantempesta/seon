(ns seon.schedule-test
  (:require [clojure.repl :as repl]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [seon.cluster.agent :as agent]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.id :as id]
            [seon.render :as render]
            [seon.render.walk :as walk]
            [seon.schedule :as schedule]
            [seon.schema :as schema]
            [seon.cluster.wake :as wake]
            [seon.turn :as turn]
            [seon.test-support :as test-support])
  (:import [java.time Instant]
           [java.util Date]))

(deftest firing-requires-the-callers-cluster-execution-context
  (test-support/with-database
   (fn [connection]
     (let [basis (:max-tx @connection)
           refusal (test-support/refusal-data
                    #(apply schedule/fire-due! [connection "root" (Date.)]))]
       (is (= (quote seon.schedule/fire-due!) (:seon.instrument/contract-violated refusal)))
       (is (= basis (:max-tx @connection)))))))

(deftest root-maintenance-seed-is-complete-and-has-no-minute-task
  (test-support/with-database
   (fn [connection]
     (let [declarations @#'schedule/root-maintenance-portfolio
           result (db/transact! connection
                                [{:seon.agent/id "root"}
                                 [:db.fn/call #'schedule/root-maintenance-seed-call]])
           database (db/db connection)
           rows (db/q database
                      '[:find ?task-id ?function ?expression ?zone
                        :where
                        [?owner :seon.agent/id "root"]
                        [?task :seon.schedule.task/owner ?owner]
                        [?task :seon.schedule.task/id ?task-id]
                        [?task :seon.schedule.task/function ?f]
                        [?f :seon.fn/sym ?function]
                        [?task :seon.schedule.task/schedule ?s]
                        [?s :seon.schedule/expression ?expression]
                        [?s :seon.schedule/zone-id ?zone]])
           expected (into #{} (map (juxt :seon.schedule.task/id :seon.fn/sym
                                          :seon.schedule/expression :seon.schedule/zone-id))
                          declarations)]
       (is (seq (:tx-data result)) (pr-str result))
       (is (seq expected))
       (is (= expected (set rows)))
       (is (every? #(not= "* * * * *" (nth % 2)) rows))
       (is (some #(= (quote seon.operator/collect!) (second %)) rows))
       (is (empty? (schedule/root-maintenance-seed-call database)))))))

(defn- instant
  [text]
  (Date/from (Instant/parse text)))

(defn- observed-after-seed
  "An observation two whole minutes ahead of now.

  The seeded every-minute expression then has a latest nominal that is
  a minute boundary strictly after the seeding transaction, so dueness
  measured from task creation fires exactly once."
  []
  (Date. (+ (System/currentTimeMillis) 120000)))

(def ^:private handler-calls (atom []))

(defn successful-handler
  [request]
  (swap! handler-calls conj request)
  {:seon.operator.footprint/root (:seon.operator/managed-root request)
   :seon.operator.footprint/file-bytes 4096
   :seon.operator.footprint/usable-bytes 8192
   :seon.operator.footprint/total-bytes 12288
   :seon.operator.footprint/usable-ratio 0.5
   :seon.operator.footprint/observed-at
   (:seon.schedule.fire/observed-at request)
   :seon.operator/low-space? false})

(defn flat-error-handler
  [request]
  (swap! handler-calls conj request)
  {:seon.error/at (Date.)
   :seon.error/layer :seon.schedule-test/handler
   :seon.error/operation 'seon.schedule-test/flat-error-handler
   :seon.error/message "The scheduled test handler returned an error."})

(defn throwing-handler
  [request]
  (swap! handler-calls conj request)
  (throw (ex-info "The scheduled test handler threw."
                  {:seon.error/at (Date.)
                   :seon.error/layer :seon.schedule-test/handler
                   :seon.error/operation 'seon.schedule-test/throwing-handler
                   :seon.error/message "The scheduled test handler threw."})))

(def ^:private result-caps
  (assoc (config/result-caps (test-support/effective-config))
         :seon.config.eval.result/max-depth 16
         :seon.config.eval.result/max-collection 100
         :seon.config.eval.result/max-string 4096
         :seon.config.eval.result/max-nodes 1000))

(defn- execution-context
  []
  {:seon.boot/cluster-name "default"
   :seon.operator/repository-root "/repo"
   :seon.operator/managed-root "/repo/operator"
   :seon.boot/log-dir "/repo/operator/data/clusters/default/logs"
   :seon.config.maintenance/min-usable-bytes 1
   :seon.config.maintenance/min-usable-ratio 0.01
   :seon.config.maintenance/log-max-bytes 1024
   :seon.config.maintenance/log-retained-files 2
   :seon.turn.loop/cluster
   (test-support/cluster-handle
    {:seon.cluster/name "default"
     :seon.db.process/id "schedule-test-process"
     :seon.sci.admit/caps result-caps
     :seon.config.error/recurrence-limit 3
     :seon.config.error/escalate-to "root"})})

(defn- seed-task!
  [connection task-id handler]
  (let [handler-ns (symbol (namespace (symbol handler)))
        result (db/transact!
   connection
   [{:seon.agent/id "root"}
    {:seon.ns/name handler-ns}
    (test-support/program-fn-row @connection handler (repl/source-fn handler))
    {:seon.schedule/id (str task-id "/schedule")
     :seon.schedule/expression "* * * * *"
     :seon.schedule/zone-id "UTC"}
    {:seon.schedule.task/id task-id
     :seon.schedule.task/owner [:seon.agent/id "root"]
     :seon.schedule.task/function [:seon.fn/sym handler]
     :seon.schedule.task/schedule
     [:seon.schedule/id (str task-id "/schedule")]}])]
    (is (seq (:tx-data result)) (pr-str result))
    result))

(defn- count-with
  [database attribute]
  (or (db/q '[:find (count ?entity) .
              :in $ ?attribute
              :where [?entity ?attribute _]]
            database attribute)
      0))

(deftest scheduled-operational-events-do-not-expand-or-invalidate-the-agent-page
  (test-support/with-database
   (fn [connection]
     (seed-task! connection "schedule-test/page"
                 (quote seon.schedule-test/successful-handler))
     (let [ctx (test-support/fork-cluster-ctx connection)
           request {:seon.db/db @connection
                    :seon.sci.eval/ctx ctx
                    :seon.render.walk/lookup [:seon.agent/id "root"]
                    :seon.render/distance 2
                    :seon.sci.admit/caps result-caps}
           before (walk/root-acquisition request)
           cache (render/shared-cache ctx)
           pull-keys (set (keys @cache))
           observed-at (observed-after-seed)]
       (is (= [[:seon.agent/id "root"]] (:seon.render.walk/order before)))
       (is (= 1 (count pull-keys)) "the real root was pulled")
       (doseq [at [observed-at (Date. (+ (.getTime observed-at) 60000))]]
         (is (= 1 (schedule/fire-due! connection "root" at (execution-context))))
         (is (true? (db/read-evidence-current?
                     @connection (:seon.render.call/read-evidence before)))
             "successful operational firings are outside the page's read evidence")
         (let [after (walk/root-acquisition (assoc request :seon.db/db @connection))]
           (is (= (select-keys before [:seon.render.walk/root :seon.render.walk/members
                                      :seon.render.walk/order])
                  (select-keys after [:seon.render.walk/root :seon.render.walk/members
                                     :seon.render.walk/order])))
           (is (= pull-keys (set (keys @cache))))))
       (is (= 2 (count-with @connection :seon.schedule.fire/id)))
       (is (= 2 (count-with @connection :seon.maintenance.request/id)))
       (is (= 2 (count-with @connection :seon.maintenance.receipt/completed-at)))
       (is (seq (:tx-data
                 (db/transact! connection
                               [{:seon.message/id "schedule-test/inbox"
                                 :seon.message/content "A declared concern changed."
                                 :seon.message/to [:seon.agent/id "root"]}]))))
       (is (false? (db/read-evidence-current?
                    @connection (:seon.render.call/read-evidence before)))
           "an initially empty declared reverse concern still invalidates on insertion")
       (is (some #{[:seon.message/id "schedule-test/inbox"]}
                 (:seon.render.walk/order
                  (walk/root-acquisition (assoc request :seon.db/db @connection)))))))))

(deftest every-public-schedule-contract-compiles
  (test-support/with-database
    (fn [connection]
      (let [projection (schema/projection-from-database @connection)
            options (:seon.schema.projection/compile-options projection)
            contracts (keep (fn [[function-name function-var]]
                              (when-let [contract (:malli/schema (meta function-var))]
                                [function-name contract]))
                            (ns-publics 'seon.schedule))]
        (is (seq contracts) "the contract census must not pass an absent subject")
        (doseq [[function-name contract] contracts]
          (is (some? (m/function-schema contract options))
              (str "invalid contract on seon.schedule/" function-name)))))))

(deftest nominal-instants-obey-gap-and-overlap-rules
  (testing "a nonexistent spring-forward minute is skipped"
    (is (= (instant "2025-03-10T06:30:00Z")
           (schedule/next-nominal-after
            {:seon.schedule/expression "30 2 * * *"
             :seon.schedule/zone-id "America/New_York"
             :seon.schedule/reference-at (instant "2025-03-09T05:00:00Z")}))))
  (testing "a repeated fall-back minute has two distinct nominal instants"
    (let [first-at (instant "2025-11-02T05:30:00Z")]
      (is (= (instant "2025-11-02T06:30:00Z")
             (schedule/next-nominal-after
              {:seon.schedule/expression "30 1 * * *"
               :seon.schedule/zone-id "America/New_York"
               :seon.schedule/reference-at first-at}))))))

(deftest one-nominal-fire-calls-the-handler-once-without-a-turn
  (test-support/with-database
    (fn [connection]
      (reset! handler-calls [])
      (seed-task! connection "schedule-test/success"
                  (quote seon.schedule-test/successful-handler))
      (let [observed-at (observed-after-seed)]
        (is (= 1 (schedule/fire-due! connection "root" observed-at
                                     (execution-context))))
        (is (= 0 (schedule/fire-due! connection "root" observed-at
                                     (execution-context))))
        (is (= 1 (count @handler-calls)))
        (is (= 1 (count-with @connection :seon.schedule.fire/id)))
        (is (= 1 (count-with @connection :seon.maintenance.receipt/id)))
        (is (= 1 (count-with @connection
                             :seon.maintenance.receipt/completed-at)))
        (is (= 1 (count-with @connection :seon.maintenance.receipt/result)))
        (is (= 0 (count-with @connection :seon.turn/id)))
        (is (= 0 (count-with @connection :seon.message/id)))
        (is (identical? connection (:seon.db/connection (first @handler-calls))))
        (is (= (dissoc (first @handler-calls)
                       :seon.db/connection
                       :seon.schedule.task/id
                       :seon.schedule.fire/id
                       :seon.agent/id
                       :seon.fn/sym
                       :seon.schedule.fire/nominal-at
                       :seon.schedule.fire/observed-at)
               (dissoc (execution-context) :seon.turn.loop/cluster)))))))

(deftest each-firing-is-one-wake-that-opens-no-turn
  ;; THE CLASS: a recurrence definition is not an event. Claiming the
  ;; SCHEDULE once would consume every later firing, so the wake rides
  ;; the firing entity — one immutable entity per nominal instant, one
  ;; `:seon.schedule.fire/agent` datom with its own transaction `:t`,
  ;; asserted once and never re-asserted.
  ;;
  ;; And it is declared `:seon.wake/opens-turn? false`: the maintenance
  ;; portfolio ticks on a cron, and a paid model call per tick is not a
  ;; feature.
  (test-support/with-database
    (fn [connection]
      (reset! handler-calls [])
      (seed-task! connection "schedule-test/firing-wake"
                  (quote seon.schedule-test/successful-handler))
      (let [database (db/db connection)]
        (is (contains? (wake/wake-attributes database)
                       :seon.schedule.fire/agent)
            "the firing attribute is listened")
        (is (not (contains? (wake/turn-opening-attributes database)
                            :seon.schedule.fire/agent))
            "and it never opens a turn")
        (is (zero? (count-with database :seon.schedule.fire/agent))
            "the recurrence definition asserted no wake datom"))
      (let [observed-at (observed-after-seed)]
        (is (= 1 (schedule/fire-due! connection "root" observed-at
                                     (execution-context))))
        (let [database (db/db connection)]
          (is (= 1 (count-with database :seon.schedule.fire/agent))
              "one firing, one wake datom")
          (is (= [(db/q '[:find ?agent . :where
                          [?agent :seon.agent/id "root"]]
                        database)]
                 (db/q '[:find [?agent ...] :where
                         [_ :seon.schedule.fire/agent ?agent]]
                       database))
              "pointing at the task's owner, so the router needs no query")
          (is (empty? (turn/unanswered-wakes database "root" {}))
              "and it derives no turn-opening work"))
        (is (= 0 (schedule/fire-due! connection "root" observed-at
                                     (execution-context))))
        (is (= 1 (count-with (db/db connection) :seon.schedule.fire/agent))
            "re-firing the same nominal instant asserts nothing: a wake
             datom is written once and never re-asserted")))))

(deftest a-nominal-predating-the-task-never-fires
  ;; The class this kills: a fresh task has no fire history, and reading
  ;; that absence as "overdue since the epoch" fired every seeded
  ;; portfolio task at first boot — including the weekly store
  ;; collection, whose reachability sweep refused a concurrent cluster
  ;; start on the same store (the cohost regression's red).
  (test-support/with-database
    (fn [connection]
      (reset! handler-calls [])
      (seed-task! connection "schedule-test/preexisting-nominal"
                  (quote seon.schedule-test/successful-handler))
      (is (= 0 (schedule/fire-due! connection "root"
                                   (instant "2025-04-05T12:34:45Z")
                                   (execution-context)))
          "an observation before the task's creation finds nothing due")
      (is (empty? @handler-calls))
      (is (= 0 (count-with @connection :seon.schedule.fire/id))))))

(deftest restart-marks-a-claimed-receipt-interrupted-without-reexecution
  (test-support/with-database
    (fn [connection]
      (reset! handler-calls [])
      (let [task-id "schedule-test/interrupted"
            observed-at (instant "2025-04-05T12:34:45Z")
            nominal-at (instant "2025-04-05T12:34:00Z")
            fire-id (id/digest 12 [:seon.schedule.fire/id task-id nominal-at])
            request
            (merge (dissoc (execution-context) :seon.turn.loop/cluster)
                   {:seon.schedule.task/id task-id
                    :seon.schedule.fire/id fire-id
                    :seon.agent/id "root"
                    :seon.fn/sym (quote seon.schedule-test/successful-handler)
                    :seon.schedule.fire/nominal-at nominal-at
                    :seon.schedule.fire/observed-at observed-at})]
        (seed-task! connection task-id
                    (quote seon.schedule-test/successful-handler))
        (test-support/transacted! connection
                                  {:tx-data [[:db.fn/call #'schedule/fire-call request]]})
        (#'cluster/recover-runs! connection)
        (is (= 0 (schedule/fire-due! connection "root" observed-at
                                     (execution-context))))
        (is (empty? @handler-calls))
        (is (= 1 (count-with @connection
                             :seon.maintenance.receipt/interrupted-at)))
        (is (= 0 (count-with @connection
                             :seon.maintenance.receipt/completed-at)))))))

(deftest returned-and-thrown-handler-errors-use-the-existing-root-wake
  (doseq [[task-id handler expected-operation]
          [["schedule-test/returned" (quote seon.schedule-test/flat-error-handler)
            'seon.schedule-test/flat-error-handler]
           ["schedule-test/thrown" (quote seon.schedule-test/throwing-handler)
            'seon.schedule-test/throwing-handler]]]
    (testing handler
      (test-support/with-database
        (fn [connection]
          (reset! handler-calls [])
          (seed-task! connection task-id handler)
          (is (= 1 (schedule/fire-due!
                    connection "root" (observed-after-seed)
                    (execution-context))))
          (is (= 1 (count @handler-calls)))
          (is (= 1 (count-with @connection :seon.maintenance.receipt/id)))
          (is (= 1 (count-with @connection :seon.maintenance.receipt/error)))
          (is (= 1 (count-with @connection :seon.error/id)))
          (is (= 1 (count-with @connection :seon.message/id)))
          (let [[_evaluation-id error-id task nominal]
                (first (db/q '[:find ?evaluation-id ?error-id ?task ?nominal
                        :where
                        [?evaluation :seon.maintenance.receipt/id ?evaluation-id]
                        [?evaluation :seon.maintenance.receipt/error ?error]
                        [?error :seon.error/id ?error-id]
                        [?evaluation :seon.maintenance.receipt/fire ?fire]
                        [?fire :seon.schedule.fire/task ?task-row]
                        [?task-row :seon.schedule.task/id ?task]
                        [?fire :seon.schedule.fire/nominal-at ?nominal]]
                             @connection))]
            (is (id/valid? 64 error-id))
            (is (= 1 (db/q '[:find (sum ?count) . :where
                              [?error :seon.error/id ?id]
                              [?error :seon.error/occurrences ?occurrence]
                              [?occurrence :seon.error.occurrence/count ?count]] (db/db connection))))
            (is (= task-id task))
            (is (inst? nominal)))
          (is (= expected-operation
                 (db/q '[:find ?operation .
                         :where [_ :seon.error/operation ?operation]]
                       @connection))))))))

(deftest schedule-remains-the-third-proc-in-the-agent-graph
  ;; THE BLUEPRINT'S REQUEST IS THE HANDLE A RUNNING CLUSTER OWNS, so the
  ;; census hands every declared member the way `arm!` does rather than the
  ;; two entries this assertion happens to read.
  (test-support/with-database
   (fn [connection]
     (let [environment (test-support/environment "seon.schedule-test"
                                                 connection)
           handle (env/carry
                   (test-support/cluster-handle
                    {:seon.db/connection connection
                     :seon.cluster/name "seon.schedule-test"
                     :seon.db.process/id "schedule-test-process"
                     :seon.sci.eval/ctx
                     (test-support/fork-cluster-ctx connection)
                     :seon.schedule/channel ::channel})
                   environment)
           definition
           (agent/graph-definition
            {:seon.turn.loop/cluster handle
             :seon.agent/id "root"})]
       (is (= #{:seon.agent/mailbox :seon.agent/turn :seon.agent/schedule}
              (set (keys (:procs definition)))))))))
