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

(def point
  {:seon.db.coordinate/database-id
   #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   :seon.db.coordinate/branch :db
   :seon.db.coordinate/commit-id
   #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   :seon.db.coordinate/t 42})

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
               {::db/coordinate point
                ::db/results
                [(protocol/success
                  {::protocol/result
                   [{:db/id 10 :seon.agent/id "sender"}
                    {:db/id 11 :seon.agent/id "peer"}]})
                 (protocol/success
                  {:datahike.query/result (js/Date. 1000)})]})))
      (set! db/query
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve 3)))
      (finish!
       (-> (internal/acquire-send-data
            [:seon.agent/id "sender"] [[:seon.agent/id "peer"]])
           (.then
            (fn [result]
              (is (true? (:seon.db/ok? result)))
              (is (false? (:seon.agent.message/from-user? result)))
              (is (= 3 (:seon.agent.message/hops result)))
              (is (= 2 (count (::db/members (first @requests))))
                  "sender pulls and the human barrier share one request")
              (is (= point (::db/coordinate (second @requests)))
                  "the dependent hop query stays on the acquired value")
              (is (= 1 (::db/max-results (second @requests)))))))
           (.finally
            (fn []
              (set! db/execute-many execute-many)
              (set! db/query query))))
       done)))

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
               (if (= 1 (count (::db/members request)))
                 {::db/coordinate point
                  ::db/results
                  [(protocol/success {:datahike.query/result 10})]}
                 {::db/coordinate point
                  ::db/results
                  [(protocol/success
                    {::protocol/datoms
                     [{:seon.db/e 22} {:seon.db/e 20}]})
                   (protocol/success
                    {::protocol/datoms
                     [{:seon.db/e 21} {:seon.db/e 22}]})]}))))
      (set! db/pull-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               [{:seon.agent.message/id "later"
                 :seon.agent.message/at later}
                {:seon.agent.message/id "earlier"
                 :seon.agent.message/at earlier}])))
      (finish!
       (-> (message/recent
            {:seon.agent/id "sender"
             :seon.agent.message/recent-limit 2})
           (.then
            (fn [messages]
              (is (= ["earlier" "later"]
                     (mapv :seon.agent.message/id messages)))
              (is (= 3 (count @requests)))
              (let [index-request (second @requests)
                    pull-request (nth @requests 2)]
                (is (= point (::db/coordinate index-request)))
                (is (= 2 (count (::db/members index-request))))
                (is (every? #(= :reverse (::protocol/direction %))
                            (::db/members index-request)))
                (is (= [22 21] (::db/refs pull-request)))
                (is (= point (::db/coordinate pull-request))))))
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
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               {::db/coordinate point
                ::db/datoms [{:seon.db/e 4} {:seon.db/e 3}]})))
      (set! db/pull-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               [{:seon.agent.message/id "new"
                 :seon.agent.message/at (js/Date. 2000)}
                {:seon.agent.message/id "old"
                 :seon.agent.message/at (js/Date. 1000)}])))
      (finish!
       (-> (message/recent-all
            {:seon.agent.message/recent-limit 2
             ::db/coordinate point})
           (.then
            (fn [messages]
              (is (= ["old" "new"]
                     (mapv :seon.agent.message/id messages)))
              (is (= :reverse (::db/direction (first @requests))))
              (is (= [4 3] (::db/refs (second @requests))))))
           (.finally
            (fn []
              (set! db/index-page index-page)
              (set! db/pull-many pull-many))))
       done))))

(deftest inbound-target-check-precedes-one-pinned-pull
  (async done
    (let [pull db/pull
          requests (atom [])]
      (set! db/pull
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               {:seon.agent.message/from {:db/id 11}
                :seon.agent.message/origin :human})))
      (finish!
       (-> (message/inbound-msg-datom?
            {::db/coordinate point
             :seon.db/datom
             {:seon.db/e 20 :seon.db/a :seon.agent.message/to
              :seon.db/v 10 :seon.db/tx 42 :seon.db/added? true}
             :seon.agent/eid 10})
           (.then
            (fn [inbound?]
              (is (true? inbound?))
              (is (= point (::db/coordinate (first @requests))))
              (is (= 20 (::db/ref (first @requests))))))
           (.finally (fn [] (set! db/pull pull))))
       done))))

(deftest message-write-uses-acquired-data-and-no-local-connection
  (async done
    (let [available? admission/available?
          acquire internal/acquire-send-data
          allocate! db.id/allocate!
          observed (atom nil)]
      (set! admission/available? (constantly true))
      (set! internal/acquire-send-data
            (fn [_from _to]
              (js/Promise.resolve
               {:seon.db/ok? true
                :seon.agent.message/from-user? false
                :seon.agent.message/hops 2})))
      (set! db.id/allocate!
            (fn [request]
              (reset! observed request)
              (let [built ((::db.id/transaction-builder request)
                           {:seon.agent.message/id "message-id"})]
                (is (not (contains? request :seon.db/conn)))
                (is (= 3 (get-in built [:seon.db/tx-data 0
                                        :seon.agent.message/hops])))
                (js/Promise.resolve
                 {:seon.db/ok? true
                  ::db.id/ids {:seon.agent.message/id "message-id"}}))))
      (finish!
       (-> (message/message!
            {:seon.agent.message/from [:seon.agent/id "sender"]
             :seon.agent.message/to [:seon.agent/id "peer"]
             :seon.agent.message/content "hello"})
           (.then
            (fn [result]
              (is (= {:seon.agent.message/ok? true
                      :seon.agent.message/id "message-id"
                      :seon.agent.message/hops 3}
                     result))
              (is (= 1 (count (::db.id/allocations @observed))))))
           (.finally
            (fn []
              (set! admission/available? available?)
              (set! internal/acquire-send-data acquire)
              (set! db.id/allocate! allocate!))))
       done))))

(deftest blank-content-and-self-message-remain-errors-as-values
  (async done
    (let [available? admission/available?
          current-agent-id db/current-agent-id]
      (set! admission/available? (constantly true))
      (set! db/current-agent-id (constantly "self"))
      (finish!
       (-> (message/message!
            {:seon.agent.message/from message/user-ref
             :seon.agent.message/content "   "})
           (.then
            (fn [blank]
              (is (false? (:seon.db/ok? blank)))
              (message/agent "self" "hello")))
           (.then
            (fn [self]
              (is (false? (:seon.db/ok? self)))
              (is (re-find #"YOURSELF"
                           (get-in self [:seon.db/error
                                         :seon.error/message])))))
           (.finally
            (fn []
              (set! admission/available? available?)
              (set! db/current-agent-id current-agent-id))))
       done))))
