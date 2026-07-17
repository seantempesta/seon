(ns seon.agent.message-test
  "Focused authority-backed message acquisition and pure classification tests."
  (:require
   [cljs.test :refer [async deftest is]]
   [malli.core :as m]
   [seon.agent.message :as message]
   [seon.agent.message.internal :as internal]
   [seon.db :as db]
   [seon.db.id :as db.id]
   [seon.db.protocol :as protocol]
   [seon.runtime.admission :as admission]
   [seon.warn :as warn]))

(def database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id
   #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(defn- finish!
  [promise done]
  (-> promise
      (.then (fn [_] (done)))
      (.catch (fn [error]
                (is false (str "threw — " error))
                (done)))))

(deftest message-classification-is-pure-over-ordinary-data
  (let [self 10]
    (is (true? (message/waking-inbound?
                {:seon.agent.message/from {:db/id 11}
                 :seon.agent.message/origin :human}
                self)))
    (is (false? (message/waking-inbound?
                 {:seon.agent.message/from {:db/id self}
                  :seon.agent.message/origin :agent}
                 self)))
    (is (false? (message/waking-inbound?
                 {:seon.agent.message/from {:db/id 11}
                  :seon.agent.message/origin :core}
                 self)))
    (is (true? (internal/user-entity? {:seon.user/id "user"})))
    (is (false? (internal/user-entity? {:seon.agent/id "agent"})))
    (is (= 3 (internal/outbound-hops 3)))
    (is (= 0 (internal/outbound-hops nil)))
    (is (message/hop-live? {:seon.agent.message/hops (dec warn/hop-cap)}))
    (is (not (message/hop-live? {:seon.agent.message/hops warn/hop-cap})))))

(deftest clip-title-remains-pure-and-bounded
  (is (= "hello world" (internal/clip-title " hello\n world ")))
  (let [title (internal/clip-title (apply str (repeat 200 "x")))]
    (is (= 81 (count title)))
    (is (re-find #"…$" title))))

(deftest one-pure-builder-owns-message-and-human-plan-transaction-data
  (let [build @#'message/message-transaction
        transaction
        (build
         {:seon.agent.message/content "do this"
          :seon.agent.message/from message/user-ref
          :seon.agent.message/to [[:seon.agent/id "peer"]]
          :seon.agent.message/at (js/Date. 1000)
          :seon.agent.message/send-data
          {:seon.agent.message/from-user? true
           :seon.agent.message/agent-tos [[:seon.agent/id "peer"]]
           :seon.agent.message/hops 0}})
        built
        ((:seon.agent.message/transaction-builder transaction)
         {:seon.agent.message/id "message-a"
          :seon.agent.message/plan-id-0 "plan-a"})
        [message-row plan-row] (:seon.db/tx-data built)]
    (is (= 2 (count (:seon.agent.message/allocations transaction))))
    (is (= 0 (:seon.agent.message/hops transaction)))
    (is (= "message-a" (:seon.agent.message/id message-row)))
    (is (= :human (:seon.agent.message/origin message-row)))
    (is (= "plan-a" (:my.plan/id plan-row)))
    (is (= [:seon.agent.message/id "message-a"]
           (:my.plan/message plan-row)))))

(deftest message-request-admits-every-supported-ref-shape
  (let [request (fn [from]
                  {:seon.agent.message/from from
                   :seon.agent.message/to [:seon.agent/id "peer"]
                   :seon.agent.message/content "hello"})]
    (is (m/validate :seon.agent.message/message-request
                    (request [:seon.agent/id "sender"])))
    (is (m/validate :seon.agent.message/message-request (request 42)))
    (is (m/validate :seon.agent.message/message-request
                    (dissoc (request nil) :seon.agent.message/from)))))

(deftest send-data-is-acquired-on-one-database-value
  (async done
    (let [execute-many db/execute-many
          query db/query
          requests (atom [])]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               {::db/results
                [(protocol/success
                  {::protocol/result
                   [{:db/id 10 :seon.agent/id "sender"}
                    {:db/id 11 :seon.agent/id "peer"}]})
                 (protocol/success
                  {:datahike.query/result (js/Date. 1000)})]})))
      (set! db/query
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve 3))
              ([_query-form & _inputs]
               (js/Promise.reject
                (js/Error. "unexpected positional query")))))
      (finish!
       (-> (internal/acquire-send-data
            database [:seon.agent/id "sender"] [[:seon.agent/id "peer"]])
           (.then
            (fn [result]
              (is (false? (:seon.agent.message/from-user? result)))
              (is (= 3 (:seon.agent.message/hops result)))
              (is (= database (::db/db result)))
              (is (= 2 (count (::db/members (first @requests))))
                  "sender pulls and the human barrier share one request")
              (is (every? #(= database (::db/db %))
                          (::db/members (first @requests))))
              (is (= database (::db/db (second @requests)))
                  "the dependent hop query stays on the acquired value")
              (is (= 1 (::db/max-results (second @requests))))))
           (.finally
            (fn []
              (set! db/execute-many execute-many)
              (set! db/query query))))
       done))))

(deftest recent-uses-two-bounded-index-members-and-one-pull-many
  (async done
    (let [execute-many db/execute-many
          pull-many db/pull-many
          requests (atom [])
          earlier (js/Date. 1000)
          later (js/Date. 2000)]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               {::db/results
                [(protocol/success
                  {:datahike.index-page/datoms
                   [[22 :seon.agent.message/from 10 42 true]
                    [20 :seon.agent.message/from 10 40 true]]})
                 (protocol/success
                  {:datahike.index-page/datoms
                   [[21 :seon.agent.message/to 10 41 true]
                    [22 :seon.agent.message/to 10 42 true]]})]})))
      (set! db/pull-many
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve
                [{:seon.agent.message/id "later"
                  :seon.agent.message/at later}
                 {:seon.agent.message/id "earlier"
                  :seon.agent.message/at earlier}]))
              ([_database _selector _refs]
               (js/Promise.reject (js/Error. "unexpected positional pull")))))
      (finish!
       (-> (message/recent
            {:seon.agent/id "sender"
             ::db/db database
             :seon.agent.message/recent-limit 2})
           (.then
            (fn [messages]
              (is (= ["earlier" "later"]
                     (mapv :seon.agent.message/id messages)))
              (is (= 2 (count @requests)))
              (let [index-request (first @requests)
                    pull-request (second @requests)]
                (is (= 2 (count (::db/members index-request))))
                (is (every? #(= :reverse (::protocol/direction %))
                            (::db/members index-request)))
                (is (every? #(= database (::db/db %))
                            (::db/members index-request)))
                (is (every? #(= [:seon.agent/id "sender"]
                                (second (::protocol/prefix %)))
                            (::db/members index-request)))
                (is (= [22 21] (::db/eids pull-request)))
                (is (= database (::db/db pull-request))))))
           (.finally
            (fn []
              (set! db/execute-many execute-many)
              (set! db/pull-many pull-many))))
       done))))

(deftest recent-all-pages-once-then-pulls-in-one-batch
  (async done
    (let [index-page db/index-page
          pull-many db/pull-many
          requests (atom [])]
      (set! db/index-page
            (fn
              ([_request]
               (js/Promise.reject (js/Error. "unexpected ambient index read")))
              ([db-value options]
               (swap! requests conj [db-value options])
               (js/Promise.resolve
                {:datahike.index-page/datoms
                 [[4 :seon.agent.message/at (js/Date. 2000) 42 true]
                  [3 :seon.agent.message/at (js/Date. 1000) 41 true]]}))))
      (set! db/pull-many
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve
                [{:seon.agent.message/id "new"
                  :seon.agent.message/at (js/Date. 2000)}
                 {:seon.agent.message/id "old"
                  :seon.agent.message/at (js/Date. 1000)}]))
              ([_database _selector _refs]
               (js/Promise.reject (js/Error. "unexpected positional pull")))))
      (finish!
       (-> (message/recent-all
            {:seon.agent.message/recent-limit 2
             ::db/db database})
           (.then
            (fn [messages]
              (is (= ["old" "new"]
                     (mapv :seon.agent.message/id messages)))
              (let [[db-value options] (first @requests)]
                (is (= database db-value))
                (is (= :reverse (::db/direction options))))
              (is (= [4 3] (::db/eids (second @requests))))))
           (.finally
            (fn []
              (set! db/index-page index-page)
              (set! db/pull-many pull-many))))
       done))))

(deftest message-write-uses-one-acquired-database-value
  (async done
    (let [available? admission/available?
          db! db/db
          acquire internal/acquire-send-data
          allocate! db.id/allocate!
          observed (atom nil)
          db-calls (atom 0)]
      (set! admission/available? (constantly true))
      (set! db/db
            (fn
              ([]
               (swap! db-calls inc)
               (js/Promise.resolve database))
              ([_request]
               (js/Promise.reject
                (js/Error. "unexpected named database read")))))
      (set! internal/acquire-send-data
            (fn [db-value _from _to]
              (js/Promise.resolve
               {::db/db db-value
                :seon.agent.message/from-user? false
                :seon.agent.message/hops 2})))
      (set! db.id/allocate!
            (fn [request]
              (reset! observed request)
              (let [built ((::db.id/transaction-builder request)
                           {:seon.agent.message/id "message-id"})]
                (is (= database (::db/db request)))
                (is (= 3 (get-in built [:seon.db/tx-data 0
                                        :seon.agent.message/hops])))
                (js/Promise.resolve
                 {:db-before database
                  :db-after (assoc database :t 43)
                  :tx-data (:seon.db/tx-data built)
                  :tempids {}
                  ::db.id/ids {:seon.agent.message/id "message-id"}}))))
      (finish!
       (-> (message/message!
            {:seon.agent.message/from [:seon.agent/id "sender"]
             :seon.agent.message/to [:seon.agent/id "peer"]
             :seon.agent.message/content "hello"})
           (.then
            (fn [result]
              (is (= {:seon.agent.message/id "message-id"
                      :seon.agent.message/hops 3}
                     result))
              (is (= 1 @db-calls))
              (is (= 1 (count (::db.id/allocations @observed))))))
           (.finally
            (fn []
              (set! admission/available? available?)
              (set! db/db db!)
              (set! internal/acquire-send-data acquire)
              (set! db.id/allocate! allocate!))))
       done))))

(deftest message-write-preserves-direct-allocation-error
  (async done
    (let [available? admission/available?
          acquire internal/acquire-send-data
          allocate! db.id/allocate!
          failure {:seon.error/message "writer refused"
                   :seon.error/kind :user-input}]
      (set! admission/available? (constantly true))
      (set! internal/acquire-send-data
            (fn [db-value _from _to]
              (js/Promise.resolve
               {::db/db db-value
                :seon.agent.message/from-user? false
                :seon.agent.message/hops 0})))
      (set! db.id/allocate! (fn [_request] (js/Promise.resolve failure)))
      (finish!
       (-> (message/message!
            {::db/db database
             :seon.agent.message/from [:seon.agent/id "sender"]
             :seon.agent.message/to [:seon.agent/id "peer"]
             :seon.agent.message/content "hello"})
           (.then (fn [result] (is (= failure result))))
           (.finally
            (fn []
              (set! admission/available? available?)
              (set! internal/acquire-send-data acquire)
              (set! db.id/allocate! allocate!))))
       done))))

(deftest human-message-normalizes-recipients-and-builds-one-plan-row
  (async done
    (let [available? admission/available?
          acquire internal/acquire-send-data
          allocate! db.id/allocate!
          built (atom nil)]
      (set! admission/available? (constantly true))
      (set! internal/acquire-send-data
            (fn [db-value _from recipients]
              (is (= [[:seon.agent/id "peer"]] recipients))
              (js/Promise.resolve
               {::db/db db-value
                :seon.agent.message/from-user? true
                :seon.agent.message/agent-tos recipients
                :seon.agent.message/hops 0})))
      (set! db.id/allocate!
            (fn [request]
              (is (= 2 (count (::db.id/allocations request))))
              (let [transaction
                    ((::db.id/transaction-builder request)
                     {:seon.agent.message/id "message-id"
                      :seon.agent.message/plan-id-0 "plan-id"})]
                (reset! built transaction)
                (js/Promise.resolve
                 {:db-before database
                  :db-after (assoc database :t 43)
                  :tx-data (:seon.db/tx-data transaction)
                  :tempids {}
                  ::db.id/ids
                  {:seon.agent.message/id "message-id"
                   :seon.agent.message/plan-id-0 "plan-id"}}))))
      (finish!
       (-> (message/message!
            {::db/db database
             :seon.agent.message/from message/user-ref
             :seon.agent.message/to
             [[:seon.agent/id "peer"] [:seon.agent/id "peer"]]
             :seon.agent.message/content "do this"})
           (.then
            (fn [result]
              (is (= {:seon.agent.message/id "message-id"
                      :seon.agent.message/hops 0}
                     result))
              (let [[message-row plan-row] (:seon.db/tx-data @built)]
                (is (= :human (:seon.agent.message/origin message-row)))
                (is (= [[:seon.agent/id "peer"]]
                       (:seon.agent.message/to message-row)))
                (is (= [:seon.agent.message/id "message-id"]
                       (:my.plan/message plan-row))))))
           (.finally
            (fn []
              (set! admission/available? available?)
              (set! internal/acquire-send-data acquire)
              (set! db.id/allocate! allocate!))))
       done))))

(deftest boundary-validation-remains-direct-errors
  (async done
    (let [available? admission/available?]
      (set! admission/available? (constantly true))
      (finish!
       (-> (message/message!
            {:seon.agent.message/from message/user-ref
             :seon.agent.message/content "   "})
           (.then
            (fn [blank]
              (is (string? (:seon.error/message blank)))
              (message/message!
               {:seon.agent.message/from message/user-ref
                :seon.agent.message/to []
                :seon.agent.message/content "hello"})))
           (.then
            (fn [empty-recipient]
              (is (re-find #"empty" (:seon.error/message empty-recipient)))
              (message/message!
               {:seon.agent.message/from message/user-ref
                :seon.agent.message/content "hello"
                :seon.agent.message/origin :human})))
           (.then
            (fn [origin]
              (is (re-find #"only :core" (:seon.error/message origin)))))
           (.finally
            (fn []
              (set! admission/available? available?))))
       done))))

(deftest send-acquisition-rejects-unresolved-and-self-participants
  (async done
    (let [execute-many db/execute-many
          responses
          (atom
           [[{:db/id 10 :seon.agent/id "sender"}]
            [{:db/id 10 :seon.agent/id "sender"}
             {:db/id 10 :seon.agent/id "sender"}]])]
      (set! db/execute-many
            (fn [_request]
              (let [entities (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve
                 {::db/results
                  [(protocol/success {::protocol/result entities})
                   (protocol/success
                    {:datahike.query/result (js/Date. 1000)})]}))))
      (finish!
       (-> (internal/acquire-send-data
            database [:seon.agent/id "sender"] [[:seon.agent/id "missing"]])
           (.then
            (fn [missing]
              (is (re-find #"does not resolve" (:seon.error/message missing)))
              (internal/acquire-send-data
               database [:seon.agent/id "sender"]
               [[:seon.agent/id "sender"]])))
           (.then
            (fn [self]
              (is (re-find #"self-recipient" (:seon.error/message self)))))
           (.finally
            (fn []
              (set! db/execute-many execute-many))))
       done))))
