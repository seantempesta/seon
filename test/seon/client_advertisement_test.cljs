(ns seon.client-advertisement-test
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.client :as client]
   [seon.db :as db]
   [seon.derive :as derive]))

(defn- deferred
  []
  (let [resolve! (atom nil)
        reject! (atom nil)
        promise (js/Promise.
                 (fn [resolve-promise reject-promise]
                   (reset! resolve! resolve-promise)
                   (reset! reject! reject-promise)))]
    {:promise promise :resolve! @resolve! :reject! @reject!}))

(defn- listen-stub
  [listen]
  (fn
    ([request] (listen request))
    ([_key _handler]
     (js/Promise.reject (js/Error. "unexpected two-argument listen!")))
    ([_database _key _handler]
     (js/Promise.reject (js/Error. "unexpected three-argument listen!")))))

(defn- db-stub
  [read-latest]
  (fn
    ([] (read-latest))
    ([_request]
     (js/Promise.reject (js/Error. "unexpected database selection")))))

(defn- attach-advertisement!
  []
  ((deref #'client/attach-runtime-advertisement!)))

(defn- detach-advertisement!
  []
  ((deref #'client/detach-runtime-advertisement!)))

(deftest advertisement-attaches-once-and-follows-native-database-events
  (async done
    (let [original-state @client/!state
          original-listen db/listen!
          original-unlisten db/unlisten!
          original-db db/db
          original-resumable derive/resumable-agent-ids
          requests (atom [])
          removed (atom [])
          database-0 {:t 0}
          database-1 {:t 1}
          database-2 {:t 2}]
      (reset! client/!state {})
      (set! db/listen!
            (listen-stub
             (fn [request]
               (swap! requests conj request)
               (js/Promise.resolve :runtime-advertisement))))
      (set! db/unlisten!
            (fn [interest-key]
              (swap! removed conj interest-key)
              (js/Promise.resolve true)))
      (set! db/db (db-stub #(js/Promise.resolve database-0)))
      (set! derive/resumable-agent-ids
            (fn [database]
              (js/Promise.resolve
               (case (:t database)
                 0 ["root"]
                 1 ["root" "task-a"]
                 2 ["task-b"]))))
      (let [first-attach (attach-advertisement!)
            second-attach (attach-advertisement!)]
        (is (identical? first-attach second-attach)
            "concurrent same-owner attach reuses the one pending interest")
        (-> first-attach
            (.then
             (fn [interest-key]
               (is (= :runtime-advertisement interest-key))
               (is (= ["root"] (::client/resumable-agent-ids @client/!state)))
               (is (= 1 (count @requests)))
               (is (= #{{:seon.db/a :seon.agent/id}
                        {:seon.db/a :seon.eval/home-requires}
                        {:seon.db/a :seon.agent/terminated-at}}
                      (set (::db/datom-patterns (first @requests)))))
               ((::db/handler (first @requests))
                {:db-before database-0
                 :db-after database-1
                 :tx-data []})))
            (.then
             (fn [_]
               (testing "a native transaction report refreshes the cached ids"
                 (is (= ["root" "task-a"]
                        (::client/resumable-agent-ids @client/!state))))
               ((::db/handler (first @requests))
                {:seon.db.protocol/event
                 :seon.db.protocol/resynchronization
                 :db-after database-2})))
            (.then
             (fn [_]
               (testing "a native resynchronization event uses the same db-after seam"
                 (is (= ["task-b"]
                        (::client/resumable-agent-ids @client/!state))))
               (attach-advertisement!)))
            (.then
             (fn [interest-key]
               (is (= :runtime-advertisement interest-key))
               (is (= 1 (count @requests))
                   "reattach reuses the existing scalar listener key")
               (detach-advertisement!)))
            (.then
             (fn [removed?]
               (is (true? removed?))
               (is (= [:runtime-advertisement] @removed))
               (is (nil? (::client/advertisement-owner @client/!state)))
               (is (nil? (::client/resumable-agent-ids @client/!state)))))
            (.catch
             (fn [error]
               (is false (str "advertisement lifecycle rejected: " error
                              "\n" (.-stack error)))))
            (.finally
             (fn []
               (reset! client/!state original-state)
               (set! db/listen! original-listen)
               (set! db/unlisten! original-unlisten)
               (set! db/db original-db)
               (set! derive/resumable-agent-ids original-resumable)
               (done))))))))

(deftest older-derivation-cannot-overwrite-a-newer-database-value
  (async done
    (let [original-state @client/!state
          original-listen db/listen!
          original-unlisten db/unlisten!
          original-db db/db
          original-resumable derive/resumable-agent-ids
          handler (atom nil)
          database-0 {:t 0}
          database-1 {:t 1}
          database-2 {:t 2}
          older (deferred)
          newer (deferred)]
      (reset! client/!state {})
      (set! db/listen!
            (listen-stub
             (fn [request]
               (reset! handler (::db/handler request))
               (js/Promise.resolve :runtime-advertisement))))
      (set! db/unlisten! (fn [_] (js/Promise.resolve true)))
      (set! db/db (db-stub #(js/Promise.resolve database-0)))
      (set! derive/resumable-agent-ids
            (fn [database]
              (case (:t database)
                0 (js/Promise.resolve ["initial"])
                1 (:promise older)
                2 (:promise newer))))
      (-> (attach-advertisement!)
          (.then
           (fn [_]
             (let [older-refresh (@handler {:db-after database-1})
                   newer-refresh (@handler {:db-after database-2})]
               ((:resolve! newer) ["newer"])
               (-> newer-refresh
                   (.then
                    (fn [_]
                      ((:resolve! older) ["older"])
                      older-refresh))))))
          (.then
           (fn [_]
             (is (= ["newer"]
                    (::client/resumable-agent-ids @client/!state)))
             (detach-advertisement!)))
          (.catch
           (fn [error]
             (is false (str "ordered advertisement refresh rejected: " error
                            "\n" (.-stack error)))))
          (.finally
           (fn []
             (reset! client/!state original-state)
             (set! db/listen! original-listen)
             (set! db/unlisten! original-unlisten)
             (set! db/db original-db)
             (set! derive/resumable-agent-ids original-resumable)
             (done)))))))

(deftest initial-read-cannot-overwrite-an-event-that-arrives-after-listen
  (async done
    (let [original-state @client/!state
          original-listen db/listen!
          original-unlisten db/unlisten!
          original-db db/db
          original-resumable derive/resumable-agent-ids
          handler (atom nil)
          initial-read (deferred)
          db-read-started (deferred)
          database-0 {:t 0}
          database-1 {:t 1}]
      (reset! client/!state {})
      (set! db/listen!
            (listen-stub
             (fn [request]
               (reset! handler (::db/handler request))
               (js/Promise.resolve :runtime-advertisement))))
      (set! db/unlisten! (fn [_] (js/Promise.resolve true)))
      (set! db/db
            (db-stub
             (fn []
               ((:resolve! db-read-started) true)
               (:promise initial-read))))
      (set! derive/resumable-agent-ids
            (fn [database]
              (js/Promise.resolve
               (if (identical? database database-0)
                 ["initial"]
                 ["event"]))))
      (let [attached (attach-advertisement!)]
        (-> (:promise db-read-started)
            (.then
             (fn [_]
               ;; listen! has installed its handler while the latest database
               ;; read is pending.
               (@handler {:db-after database-1})))
            (.then
             (fn [_]
               ((:resolve! initial-read) database-0)
               attached))
            (.then
             (fn [_]
               (is (= ["event"]
                      (::client/resumable-agent-ids @client/!state)))
               (is (identical? database-1
                               (::client/advertisement-db @client/!state)))
               (detach-advertisement!)))
            (.catch
             (fn [error]
               (is false (str "race-safe advertisement attach rejected: " error
                              "\n" (.-stack error)))))
            (.finally
             (fn []
               (reset! client/!state original-state)
               (set! db/listen! original-listen)
               (set! db/unlisten! original-unlisten)
               (set! db/db original-db)
               (set! derive/resumable-agent-ids original-resumable)
               (done))))))))

(deftest advertisement-errors-release-the-single-owner
  (async done
    (let [original-state @client/!state
          original-listen db/listen!
          original-unlisten db/unlisten!
          original-db db/db
          original-resumable derive/resumable-agent-ids
          removed (atom [])]
      (reset! client/!state {})
      (set! db/listen!
            (listen-stub
             (fn [_]
               (js/Promise.resolve
                {:seon.error/message "interest refused"}))))
      (set! db/unlisten!
            (fn [interest-key]
              (swap! removed conj interest-key)
              (js/Promise.resolve true)))
      (-> (attach-advertisement!)
          (.then (fn [_] (is false "a listener error must reject attach")))
          (.catch
           (fn [_]
             (is (nil? (::client/advertisement-owner @client/!state)))
             (is (empty? @removed))
             (set! db/listen!
                   (listen-stub
                    (fn [_] (js/Promise.resolve :runtime-advertisement))))
             (set! db/db (db-stub #(js/Promise.resolve {:t 0})))
             (set! derive/resumable-agent-ids
                   (fn [_]
                     (js/Promise.resolve
                      {:seon.error/message "projection refused"})))
             (attach-advertisement!)))
          (.then (fn [_] (is false "a projection error must reject attach")))
          (.catch
           (fn [_]
             (is (= [:runtime-advertisement] @removed))
             (is (nil? (::client/advertisement-owner @client/!state)))
             (is (nil? (::client/advertisement-interest-key @client/!state)))))
          (.finally
           (fn []
             (reset! client/!state original-state)
             (set! db/listen! original-listen)
             (set! db/unlisten! original-unlisten)
             (set! db/db original-db)
             (set! derive/resumable-agent-ids original-resumable)
             (done)))))))
