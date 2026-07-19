(ns seon.agent-loop-test
  "Focused contracts for the authority-backed agent loop."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [my.plan.internal :as plan-internal]
    [seon.agent.loop :as loop]
    [seon.agent.message :as message]
    [seon.agent.run :as run]
    [seon.agent.runtime :as runtime]
    [seon.agent.turn :as turn]
    [seon.db :as db]
    [seon.db.protocol :as db.protocol]
    [seon.execution :as execution]
    [seon.error :as error]
    [seon.runtime.admission :as admission]
    [seon.runtime.recovery :as recovery]))

(defn- member [result]
  {::db.protocol/success? true
   ::db.protocol/result result})

(defn- query-member [result]
  {::db.protocol/success? true
   :datahike.query/result result})

(defn- loop-read [run current-run-id mode turn-count form-count]
  {::db/results [(member run)
                 (query-member current-run-id)
                 (query-member mode)
                 (query-member turn-count)
                 (query-member form-count)]})

(deftest transition-is-the-whole-fsm
  (testing "known events move through the declared table"
    (is (= :running (loop/transition :idle :trigger)))
    (is (= :paused (loop/transition :running :pause)))
    (is (= :running (loop/transition :paused :resume)))
    (is (= :terminated (loop/transition :running :terminate))))
  (testing "unknown transitions preserve state"
    (is (= :idle (loop/transition :idle :deadline)))
    (is (= :terminated (loop/transition :terminated :resume)))))

(deftest ticker-records-an-unexpected-rejection-under-its-configuration
  (async done
    (let [configuration {:seon.config/on-core-error :log}
          observed (atom [])
          original-available? admission/available?
          original-close-overdue run/close-overdue-runs!
          original-with-configuration error/with-configuration
          original-record error/record!]
      (set! admission/available? (constantly true))
      (set! run/close-overdue-runs!
            (fn [_]
              (js/Promise.reject (js/Error. "watchdog failed"))))
      (set! error/with-configuration
            (fn [actual thunk]
              (swap! observed conj [:configuration actual])
              (thunk)))
      (set! error/record!
            (fn [request]
              (swap! observed conj [:record request])
              request))
      (-> (js/Promise.resolve
            ((deref #'loop/run-tick!) configuration (js/Date.)))
          (.then
            (fn [_]
              (is (= configuration (second (first @observed))))
              (let [[_ request] (second @observed)]
                (is (= :core (::error/fault request)))
                (is (= "watchdog failed"
                       (.-message (::error/raw request)))))))
          (.catch
            (fn [e]
              (is false (str "ticker fault recording rejected: " e))))
          (.finally
            (fn []
              (set! admission/available? original-available?)
              (set! run/close-overdue-runs! original-close-overdue)
              (set! error/with-configuration original-with-configuration)
              (set! error/record! original-record)
              (done)))))))

(deftest next-event-is-pure-over-one-database-projection
  (let [next-event @#'loop/next-event
        future (js/Date. (+ (.now js/Date) 60000))
        base {::loop/run {:seon.agent.run/id "run-a"
                          :seon.agent.run/status :open
                          :seon.agent.run/turn-limit 4
                          :seon.agent.run/deadline future}
              ::loop/current-run-id "run-a"
              ::loop/repl-mode :batch
              ::loop/turn-count 0
              ::loop/form-count 0}]
    (is (= :turn-ok (next-event base 0)))
    (is (= :superseded
           (next-event (assoc base ::loop/current-run-id "run-b") 0)))
    (is (= :pause
           (next-event (assoc-in base [::loop/run :seon.agent.run/paused-at]
                                 (js/Date.))
                       0)))
    (is (= :turn-limit (next-event (assoc base ::loop/turn-count 4) 0)))
    (is (= :turn-limit
           (next-event (assoc base ::loop/repl-mode :stream
                                   ::loop/form-count 4)
                       0)))
    (is (= :no-forms
           (next-event base loop/no-forms-streak-limit)))
    (is (= :complete
           (next-event
             (assoc base ::loop/run
                    {:seon.agent.run/id "run-a"
                     :seon.agent.run/status :closed
                     :seon.agent.run/closed-reason :completed})
             0)))))

(deftest run-loop-acquires-one-batched-database-value
  (async done
    (let [admission-state admission/state
          db-fn db/db
          execute-many db/execute-many
          !requests (atom [])
          database {:db-name "default" :t 1 :as-of nil :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "10000000-0000-0000-0000-000000000001"}
          run {:seon.agent.run/id "run-a"
               :seon.agent.run/status :closed
               :seon.agent.run/closed-reason :completed}
          _ (set! admission/state
                  (fn [] {::admission/status :available}))
          _ (set! db/db
                  (fn
                    ([] (js/Promise.resolve database))
                    ([_request] (js/Promise.resolve database))))
          _ (set! db/execute-many
                  (fn [request]
                    (swap! !requests conj request)
                    (js/Promise.resolve (loop-read run nil :batch 0 0))))
          result (loop/run-loop! {:seon.agent/id "agent-a"} "run-a")]
      (-> result
          (.then
            (fn [result]
              (is (= :idle result))
              (is (= 1 (count @!requests)))
              (let [members (::db/members (first @!requests))]
                (is (= 5 (count members)))
                (is (every? #(= database (::db/db %)) members))
                (is (= ["agent-a"]
                       (::db.protocol/arguments (second members))))
                (is (= ["run-a"]
                       (::db.protocol/arguments (nth members 3)))))))
          (.catch (fn [error]
                    (is false (str "run-loop! rejected: " error))))
          (.finally
           (fn []
             (set! db/db db-fn)
             (set! db/execute-many execute-many)
             (set! admission/state admission-state)
             (done)))))))

(deftest run-loop-passes-its-database-value-into-the-turn
  (async done
    (let [admission-state admission/state
          db-fn db/db
          execute-many db/execute-many
          beat run/beat!
          run-turn turn/run-turn!
          consult plan-internal/maybe-consult!
          database {:db-name "default" :t 7 :as-of nil :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "10000000-0000-0000-0000-000000000007"}
          open-run {:seon.agent.run/id "run-a"
                    :seon.agent.run/status :open
                    :seon.agent.run/turn-limit 4
                    :seon.agent.run/deadline
                    (js/Date. (+ (.now js/Date) 60000))}
          closed-run {:seon.agent.run/id "run-a"
                      :seon.agent.run/status :closed
                      :seon.agent.run/closed-reason :completed}
          !reads (atom 0)
          !beat-database (atom nil)
          !turn-input (atom nil)
          _ (set! admission/state
                  (fn [] {::admission/status :available}))
          _ (set! db/db
                  (fn
                    ([] (js/Promise.resolve database))
                    ([_request] (js/Promise.resolve database))))
          _ (set! db/execute-many
                  (fn [_request]
                    (let [n (swap! !reads inc)]
                      (js/Promise.resolve
                       (if (= 1 n)
                         (loop-read open-run "run-a" :batch 0 0)
                         (loop-read closed-run nil :batch 1 1))))))
          _ (set! run/beat!
                  (fn [_request]
                    (reset! !beat-database
                            (:seon.db/db (db/current-tx-context)))
                    (js/Promise.resolve
                     {:db-before database :db-after database
                      :tx-data [] :tempids {} :tx-meta {}})))
          _ (set! turn/run-turn!
                  (fn [input]
                    (reset! !turn-input input)
                    (js/Promise.resolve
                     {:seon.agent.turn/id "turn-a"
                      :seon.agent/eval-count 1})))
          _ (set! plan-internal/maybe-consult!
                  (fn [_request] (js/Promise.resolve nil)))]
      (-> (loop/run-loop! {:seon.agent/id "agent-a"} "run-a")
          (.then
           (fn [result]
             (is (= :idle result))
             (is (= database @!beat-database))
             (is (= database (:seon.db/db @!turn-input)))
             (is (= "run-a" (:seon.agent.run/id @!turn-input)))
             (is (= 2 @!reads))))
          (.catch (fn [error]
                    (is false (str "run-loop! rejected: " error))))
          (.finally
           (fn []
             (set! db/db db-fn)
             (set! db/execute-many execute-many)
             (set! run/beat! beat)
             (set! turn/run-turn! run-turn)
             (set! plan-internal/maybe-consult! consult)
             (set! admission/state admission-state)
             (done)))))))

(deftest retired-execution-child-recovers-the-exact-run
  (async done
    (let [admission-state admission/state
          db-fn db/db
          execute-many db/execute-many
          beat run/beat!
          run-turn turn/run-turn!
          recover recovery/recover!
          open-run-fn run/open-run!
          drive-run loop/drive-run!
          close-run run/close-run!
          database {:db-name "default" :t 9 :as-of nil :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "10000000-0000-0000-0000-000000000009"}
          open-run {:seon.agent.run/id "run-a"
                    :seon.agent.run/status :open
                    :seon.agent.run/turn-limit 4
                    :seon.agent.run/deadline
                    (js/Date. (+ (.now js/Date) 60000))}
          !recovery-request (atom nil)
          !ordinary-close-count (atom 0)
          !recovery-run-request (atom nil)
          !drive-count (atom 0)
          _ (set! admission/state
                  (fn [] {::admission/status :available}))
          _ (set! db/db
                  (fn
                    ([] (js/Promise.resolve database))
                    ([_request] (js/Promise.resolve database))))
          _ (set! db/execute-many
                  (fn [_request]
                    (js/Promise.resolve
                     (loop-read open-run "run-a" :batch 0 0))))
          _ (set! run/beat!
                  (fn [_request]
                    (js/Promise.resolve
                     {:db-before database :db-after database
                      :tx-data [] :tempids {} :tx-meta {}})))
          _ (set! turn/run-turn!
                  (fn [_input]
                    (js/Promise.resolve
                    {:seon.agent.turn/id "turn-a"
                      :seon.agent.turn/status :error
                      :seon.error/data
                      {:seon.execution.host/pid 444
                       ::execution/child-retired? true}
                      ::execution/child-retired? true})))
          _ (set! recovery/recover!
                  (fn [request]
                    (reset! !recovery-request request)
                    (js/Promise.resolve
                     {::recovery/repaired? true
                      ::recovery/automatic-run? true
                      ::recovery/agent-ids ["agent-a"]
                      ::recovery/run-ids ["run-a"]
                      ::recovery/turn-ids ["turn-a"]
                      ::recovery/eval-ids []})))
          _ (set! run/open-run!
                  (fn [request]
                    (reset! !recovery-run-request request)
                    (js/Promise.resolve
                     {:seon.agent.run/id "run-recovery"})))
          _ (set! loop/drive-run!
                  (fn [_request] (swap! !drive-count inc)))
          _ (set! run/close-run!
                  (fn [_request]
                    (swap! !ordinary-close-count inc)
                    (js/Promise.resolve nil)))]
      (-> (loop/run-loop! {:seon.agent/id "agent-a"} "run-a")
          (.then
           (fn [result]
             (is (= :idle result))
             (is (= {:seon.agent/id "agent-a"
                     :seon.agent.run/id "run-a"
                     :seon.runtime.recovery/detail
                     "execution child retired during active work"
                     :seon.runtime.recovery/evidence
                     {:seon.execution.host/pid 444
                      ::execution/child-retired? true}}
                    @!recovery-request))
             (is (zero? @!ordinary-close-count))
             (js/Promise.
              (fn [resolve _] (js/setTimeout resolve 10)))))
          (.then
           (fn [_]
             (is (= {:seon.agent/id "agent-a"
                     :seon.agent.run/trigger :recovery}
                    @!recovery-run-request))
             (is (= 1 @!drive-count))))
          (.catch (fn [error]
                    (is false (str "run-loop! rejected: " error))))
          (.finally
           (fn []
             (set! db/db db-fn)
             (set! db/execute-many execute-many)
             (set! run/beat! beat)
             (set! turn/run-turn! run-turn)
             (set! recovery/recover! recover)
             (set! run/open-run! open-run-fn)
             (set! loop/drive-run! drive-run)
             (set! run/close-run! close-run)
             (set! admission/state admission-state)
             (done)))))))

(deftest repeated-child-failure-messages-root-with-durable-evidence
  (async done
    (let [notify! (deref #'loop/schedule-recovery-notice!)
          original-message message/message!
          !request (atom nil)
          hash (apply str (repeat 64 "b"))]
      (set! message/message!
            (fn [request]
              (reset! !request request)
              (js/Promise.resolve {:seon.agent.message/id "message-1"
                                   :seon.agent.message/hops 0})))
      (notify! "agent-a"
               {:seon.runtime.recovery/id "recovery-2"
                ::recovery/diagnostic-blob [:my.blob/hash hash]})
      (-> (js/Promise. (fn [resolve _] (js/setTimeout resolve 10)))
          (.then
           (fn [_]
             (is (= [:seon.agent/id "agent-a"]
                    (:seon.agent.message/from @!request)))
             (is (= [[:seon.agent/id "root"]]
                    (:seon.agent.message/to @!request)))
             (is (re-find #"recovery-2"
                          (:seon.agent.message/content @!request)))
             (is (re-find (re-pattern hash)
                          (:seon.agent.message/content @!request)))))
          (.catch (fn [error]
                    (is false (str "recovery notice rejected: " error))))
          (.finally (fn []
                      (set! message/message! original-message)
                      (done)))))))

(deftest run-loop-closes-a-bound-from-the-deciding-database-value
  (async done
    (let [admission-state admission/state
          db-fn db/db
          execute-many db/execute-many
          close-run run/close-run!
          database {:db-name "default" :t 8 :as-of nil :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "10000000-0000-0000-0000-000000000008"}
          bounded-run {:seon.agent.run/id "run-a"
                       :seon.agent.run/status :open
                       :seon.agent.run/turn-limit 4
                       :seon.agent.run/deadline
                       (js/Date. (+ (.now js/Date) 60000))}
          !close-request (atom nil)
          _ (set! admission/state
                  (fn [] {::admission/status :available}))
          _ (set! db/db
                  (fn
                    ([] (js/Promise.resolve database))
                    ([_request] (js/Promise.resolve database))))
          _ (set! db/execute-many
                  (fn [_request]
                    (js/Promise.resolve
                     (loop-read bounded-run "run-a" :batch 4 0))))
          _ (set! run/close-run!
                  (fn [request]
                    (reset! !close-request request)
                    (js/Promise.resolve
                     {:db-before database :db-after database
                      :tx-data [] :tempids {} :tx-meta {}})))]
      (-> (loop/run-loop! {:seon.agent/id "agent-a"} "run-a")
          (.then
           (fn [result]
             (is (= :idle result))
             (is (= database (:seon.db/db @!close-request)))
             (is (= :turn-limit
                    (:seon.agent.run/closed-reason @!close-request)))))
          (.catch (fn [error]
                    (is false (str "run-loop! rejected: " error))))
          (.finally
           (fn []
             (set! db/db db-fn)
             (set! db/execute-many execute-many)
             (set! run/close-run! close-run)
             (set! admission/state admission-state)
             (done)))))))

(deftest wake-handler-reads-one-committed-database-value-once
  (async done
    (let [available? admission/available?
          pull-many db/pull-many
          database {:db-name "default" :t 2 :as-of nil :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "10000000-0000-0000-0000-000000000002"}
          !requests (atom [])
          handler (loop/wake-handler {:seon.agent/id "agent-a"})
          _ (set! admission/available? (constantly true))
          _ (set! db/pull-many
                  (fn
                    ([_request]
                     (js/Promise.reject (js/Error. "unexpected map pull")))
                    ([db-value selector refs]
                     (swap! !requests conj [db-value selector refs])
                     (js/Promise.resolve
                      [{:db/id 7 :seon.agent/terminated-at (js/Date.)}
                       {:db/id 11
                        :seon.agent.message/id "message-a"
                        :seon.agent.message/hops 0
                        :seon.agent.message/origin :human
                        :seon.agent.message/from {:db/id 9}}]))))
          result
          (handler
           {:db-after database
            :tx-data [[11 :seon.agent.message/to 7 536870914 true]
                      [11 :seon.agent.message/content "hello"
                       536870914 true]]})]
      (-> result
          (.then
           (fn [_]
             (is (= 1 (count @!requests)))
             (let [[db-value selector refs] (first @!requests)]
               (is (= database db-value))
               (is (= [[:seon.agent/id "agent-a"] 11] refs))
               (is (some #(and (map? %)
                               (contains? % :seon.agent.message/from))
                         selector)))))
          (.catch (fn [error]
                    (is false (str "wake handler rejected: " error))))
          (.finally
           (fn []
             (set! db/pull-many pull-many)
             (set! admission/available? available?)
             (done)))))))

(deftest human-message-supersedes-an-open-run
  (async done
    (let [available? admission/available?
          pull-many db/pull-many
          close-run! run/close-run!
          open-run! run/open-run!
          run-loop! loop/run-loop!
          database {:db-name "default" :t 3 :as-of nil :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "10000000-0000-0000-0000-000000000003"}
          !closed (atom nil)
          !opened (atom nil)
          !driven (atom nil)
          handler (loop/wake-handler {:seon.agent/id "agent-a"})]
      (set! admission/available? (constantly true))
      (set! db/pull-many
            (fn
              ([_request]
               (js/Promise.reject (js/Error. "unexpected map pull")))
              ([_database _selector _refs]
               (js/Promise.resolve
                [{:db/id 7
                  :seon.agent/run {:seon.agent.run/id "run-old"
                                   :seon.agent.run/status :open}}
                 {:db/id 11
                  :seon.agent.message/id "message-human"
                  :seon.agent.message/hops 0
                  :seon.agent.message/origin :human
                  :seon.agent.message/from {:db/id 9}}]))))
      (set! run/close-run!
            (fn [request]
              (reset! !closed request)
              (js/Promise.resolve {:db-after database})))
      (set! run/open-run!
            (fn [request]
              (reset! !opened request)
              (js/Promise.resolve {:seon.agent.run/id "run-human"})))
      (set! loop/run-loop!
            (fn [_input run-id]
              (reset! !driven run-id)
              (js/Promise.resolve :idle)))
      (-> (handler
           {:db-after database
            :tx-data [[11 :seon.agent.message/to 7 536870915 true]]})
          (.then (fn [_]
                   (js/Promise.
                    (fn [resolve _] (js/setTimeout resolve 20)))))
          (.then
           (fn [_]
             (is (= {:seon.agent.run/id "run-old"
                     :seon.agent.run/closed-reason :superseded}
                    @!closed))
             (is (= {:seon.agent/id "agent-a"
                     :seon.agent.run/trigger :message
                     :seon.agent.run/cause 11}
                    @!opened))
             (is (= "run-human" @!driven))))
          (.catch (fn [error]
                    (is false (str "human wake rejected: " error))))
          (.finally
           (fn []
             (set! db/pull-many pull-many)
             (set! run/close-run! close-run!)
             (set! run/open-run! open-run!)
             (set! loop/run-loop! run-loop!)
             (set! admission/available? available?)
             (done)))))))

(deftest activity-log-is-one-authority-query
  (async done
    (let [query db/query
          !requests (atom [])
          started (js/Date. 1000)
          _ (set! db/query
                  (fn
                    ([request]
                     (swap! !requests conj request)
                     (js/Promise.resolve
                      [[{:seon.agent.run/status :closed
                         :seon.agent.run/closed-reason :completed
                         :seon.agent.run/cause
                         {:seon.agent.message/content "done"}}
                        started]]))
                    ([_query & _inputs]
                     (js/Promise.reject
                      (js/Error. "unexpected positional query")))))
          result (loop/activity-log {:seon.agent/id "agent-a"})]
      (-> result
          (.then
            (fn [result]
              (is (= 1 (count @!requests)))
              (is (= [{:seon.agent.loop/at started
                       :seon.agent/state :idle
                       :seon.agent.loop/stop-reason :completed
                       :seon.agent.loop/cause "done"}]
                     (:seon.agent.loop/entries result)))))
          (.catch (fn [error]
                    (is false (str "activity-log rejected: " error))))
          (.finally
           (fn []
             (set! db/query query)
             (done)))))))

(deftest committed-work-query-is-one-ordered-exact-wake-rule
  (let [query @#'loop/pending-inbound-query
        where (:where query)
        coverage (some #(when (and (seq? %) (= 'not-join (first %)))
                          (rest %))
                       where)]
    (is (= '[?message-tx :asc ?message :asc] (:order-by query)))
    (is (= 1 (:limit query)))
    (is (some #{'[(not= ?sender ?agent)]} where))
    (is (some #{'[(get-else $ ?message :seon.agent.message/origin :agent)
                  ?origin]} where))
    (is (some #{'[(not= ?origin :core)]} where))
    (is (some #{'[(get-else $ ?message :seon.agent.message/hops 0) ?hops]}
              where))
    (is (some #{'[(< ?hops ?hop-cap)]} where))
    (is (some #{'[?run :seon.agent.run/closed-reason ?close-reason]} coverage))
    (is (some #{'[(not= ?close-reason :quiesced)]} coverage)
        "planned quiescence cannot claim that inbound work completed")
    (is (some #(and (seq? %) (= 'not-join (first %))) where)
        "an ordinary run close at or after the message covers that message")))

(deftest wake-and-replay-share-one-run-loop
  (async done
    (let [run-loop! loop/run-loop!
          calls (atom 0)
          finish (atom nil)]
      (set! loop/run-loop!
            (fn [_input _run-id]
              (swap! calls inc)
              (js/Promise. (fn [resolve _reject] (reset! finish resolve)))))
      (let [drive @#'loop/drive-run-loop!
            input {:seon.agent/id "agent-a"}
            first-drive (drive input "run-a")
            second-drive (drive input "run-a")]
        (is (identical? first-drive second-drive))
        (is (= 1 @calls))
        (@finish :idle)
        (-> (js/Promise.all #js [first-drive second-drive])
            (.then (fn [] (is (= 1 @calls))))
            (.catch (fn [error]
                      (is false (str "shared run loop rejected: " error))))
            (.finally
             (fn []
               (set! loop/run-loop! run-loop!)
               (done))))))))

(deftest drive-run-opens-committed-message-before-host-and-drives-it
  (async done
    (let [id "committed-child"
          database {:db-name "default" :t 42 :as-of nil :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "20000000-0000-0000-0000-000000000002"}
          db! db/db
          execute-many db/execute-many
          open-run! run/open-run!
          run-loop! loop/run-loop!
          available? admission/available?
          requests (atom [])
          opened (atom nil)
          driven (atom nil)]
      (swap! @#'loop/!loop-input assoc id
             {:seon.agent/id id :seon.agent/llm-fn identity})
      (set! admission/available? (constantly true))
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               {::db/results
                [(member {:db/id 7 :seon.agent/id id})
                 (query-member [[11 536870914]])]})))
      (set! run/open-run!
            (fn [request]
              (reset! opened request)
              (js/Promise.resolve
               {:seon.agent.run/id "run-a"
                :seon.agent.run/status :open})))
      (set! loop/run-loop!
            (fn [input run-id]
              (reset! driven [input run-id])
              (js/Promise.resolve :idle)))
      (loop/drive-run! {:seon.agent/id id})
      (-> (js/Promise.
           (fn [resolve _] (js/setTimeout resolve 20)))
          (.then
           (fn []
             (is (= 1 (count @requests)))
             (let [query-request (second (::db/members (first @requests)))]
               (is (= 1 (get-in query-request
                                [::db.protocol/query-form :limit])))
               (is (= 65536 (:datahike.resource/max-results query-request))
                   "the semantic limit stays one while Datahike may retain query nodes")
               (is (= id (first (::db.protocol/arguments query-request)))))
             (is (= {:seon.agent/id id
                     :seon.agent.run/trigger :message
                     :seon.agent.run/cause 11}
                    @opened))
             (is (= [id "run-a"]
                    [(get-in @driven [0 :seon.agent/id]) (second @driven)]))))
          (.catch (fn [error]
                    (is false (str "committed drive rejected: " error))))
          (.finally
           (fn []
             (swap! @#'loop/!loop-input dissoc id)
             (set! db/db db!)
             (set! db/execute-many execute-many)
             (set! run/open-run! open-run!)
             (set! loop/run-loop! run-loop!)
             (set! admission/available? available?)
             (done)))))))

(deftest drive-run-redrives-open-run-and-ignores-covered-messages
  (async done
    (let [id "hosted-agent"
          database {:db-name "default" :t 50 :as-of nil :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "30000000-0000-0000-0000-000000000003"}
          db! db/db
          execute-many db/execute-many
          open-run! run/open-run!
          run-loop! loop/run-loop!
          available? admission/available?
          opens (atom 0)
          driven (atom [])]
      (swap! @#'loop/!loop-input assoc id
             {:seon.agent/id id :seon.agent/llm-fn identity})
      (set! admission/available? (constantly true))
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               {::db/results
                [(member {:db/id 7 :seon.agent/id id
                          :seon.agent/run
                          {:seon.agent.run/id "run-open"
                           :seon.agent.run/status :open}})
                 ;; The ordered query returns nothing when the prior close
                 ;; transaction covers all inbound messages.
                 (query-member [])]})))
      (set! run/open-run!
            (fn [_]
              (swap! opens inc)
              (js/Promise.resolve {:seon.agent.run/id "forbidden"})))
      (set! loop/run-loop!
            (fn [_ run-id]
              (swap! driven conj run-id)
              (js/Promise.resolve :idle)))
      (loop/drive-run! {:seon.agent/id id})
      (-> (js/Promise. (fn [resolve _] (js/setTimeout resolve 20)))
          (.then (fn []
                   (is (zero? @opens))
                   (is (= ["run-open"] @driven))))
          (.catch (fn [error]
                    (is false (str "open re-drive rejected: " error))))
          (.finally
           (fn []
             (swap! @#'loop/!loop-input dissoc id)
             (set! db/db db!)
             (set! db/execute-many execute-many)
             (set! run/open-run! open-run!)
             (set! loop/run-loop! run-loop!)
             (set! admission/available? available?)
             (done)))))))

(deftest message-open-cas-loss-renews-the-winner
  (async done
    (let [id "agent-a"
          db! db/db
          execute-many db/execute-many
          pull db/pull
          open-run! run/open-run!
          renew! run/renew!
          run-loop! loop/run-loop!
          available? admission/available?
          renewed (atom nil)]
      (swap! @#'loop/!loop-input assoc id
             {:seon.agent/id id :seon.agent/llm-fn identity})
      (set! admission/available? (constantly true))
      (set! run/open-run!
            (fn [_]
              (js/Promise.resolve {:seon.error/message "CAS failed"})))
      (set! db/db
            (fn ([] (js/Promise.resolve ::database))
              ([_] (js/Promise.resolve ::database))))
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               {::db/results
                [(member {:db/id 7 :seon.agent/id id})
                 (query-member [[11 536870914]])]})))
      (set! db/pull
            (fn
              ([_]
               (js/Promise.resolve
                {:db/id 7
                 :seon.agent/run {:seon.agent.run/id "winner"
                                  :seon.agent.run/status :open}}))
              ([_ _]
               (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _]
               (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (set! run/renew!
            (fn [request]
              (reset! renewed request)
              (js/Promise.resolve {:seon.agent.run/id "winner"})))
      (set! loop/run-loop!
            (fn [& _]
              (js/Promise.reject
               (js/Error. "the CAS loser must not start another driver"))))
      (loop/drive-run! {:seon.agent/id id})
      (-> (js/Promise. (fn [resolve _] (js/setTimeout resolve 20)))
          (.then (fn []
             (is (= {:seon.agent/id "agent-a"
                     :seon.agent.run/id "winner"}
                    @renewed))))
          (.catch (fn [error]
                    (is false (str "CAS adoption rejected: " error))))
          (.finally
           (fn []
             (swap! @#'loop/!loop-input dissoc id)
             (set! db/db db!)
             (set! db/execute-many execute-many)
             (set! db/pull pull)
             (set! run/open-run! open-run!)
             (set! run/renew! renew!)
             (set! loop/run-loop! run-loop!)
             (set! admission/available? available?)
             (done)))))))

(deftest runtime-installs-listener-before-driving-committed-work
  (async done
    (let [pull db/pull
          available? admission/available?
          install! loop/install-wake-trigger!
          drive! loop/drive-run!
          effects (atom [])]
      (set! admission/available? (constantly true))
      (set! db/pull
            (fn
              ([_]
               (js/Promise.resolve
                {:seon.agent/id "agent-a"
                 :seon.agent/namespace {:seon.ns/name 'my.tax}}))
              ([_ _]
               (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _]
               (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (set! loop/install-wake-trigger!
            (fn [_]
              (swap! effects conj :install-start)
              (js/Promise.
               (fn [resolve _]
                 (js/setTimeout
                  (fn []
                    (swap! effects conj :install-finished)
                    (resolve true))
                  5)))))
      (set! loop/drive-run!
            (fn [_]
              (swap! effects conj :drive)
              nil))
      (-> (runtime/resume!
           {:seon.agent/id "agent-a"
            :seon.agent.runtime/llm-fn identity})
          (.then
           (fn [result]
             (is (true? (:seon.agent.runtime/resumed? result)))
             (is (= 'my.tax (:seon.agent/ns result)))
             (is (= [:install-start :install-finished :drive] @effects))))
          (.catch (fn [error]
                    (is false (str "runtime resume rejected: " error))))
          (.finally
           (fn []
             (set! db/pull pull)
             (set! admission/available? available?)
             (set! loop/install-wake-trigger! install!)
             (set! loop/drive-run! drive!)
             (done)))))))

(deftest restored-wake-listener-drives-committed-work
  (async done
    (let [query db/query
          listen! db/listen!
          drive! loop/drive-run!
          request (atom nil)
          driven (atom [])
          listen-stub
          (fn [value]
            (reset! request value)
            (js/Promise.resolve (:seon.db/key value)))]
      (set! (.-cljs$core$IFn$_invoke$arity$1 listen-stub) listen-stub)
      (set! db/query (fn [_] (js/Promise.resolve 42)))
      (set! db/listen! listen-stub)
      (set! loop/drive-run! #(swap! driven conj %))
      (-> (loop/install-wake-trigger!
           {:seon.agent/id "agent-a" :seon.agent/llm-fn identity})
          (.then
           (fn [_]
             ((:seon.db/handler @request)
              {::db.protocol/event db.protocol/resynchronization-event})
             (is (= [{:seon.db/a :seon.agent.message/to
                      :seon.db/v 42
                      :seon.db/added? true}]
                    (:seon.db/datom-patterns @request)))
             (is (= [{:seon.agent/id "agent-a"
                      :seon.agent/llm-fn identity}]
                    @driven))))
          (.catch
           (fn [error]
             (is false (str "wake resynchronization rejected: " error))))
          (.finally
           (fn []
             (swap! @#'loop/!loop-input dissoc "agent-a")
             (set! db/query query)
             (set! db/listen! listen!)
             (set! loop/drive-run! drive!)
             (done)))))))
