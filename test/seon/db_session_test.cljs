(ns seon.db-session-test
  "Focused lifecycle tests for the remote `seon.db` session owner."
  (:require
   [cljs.test :refer [async deftest is]]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.db.transport.uds :as uds]))

(def ^:private database-name "session-test")
(def ^:private socket-path "tmp/session-test.sock")

(def ^:private database-0
  {:db-name database-name
   :store-id [#uuid "20000000-0000-0000-0000-000000000000" :db]
   :t 536870912
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "20000000-0000-0000-0000-000000000001"})

(def ^:private database-1
  (assoc database-0
         :t 536870913
         :datahike/commit-id
         #uuid "20000000-0000-0000-0000-000000000002"))

(def ^:private initialization-0
  {:seon.execution/artifact-digest
   "0000000000000000000000000000000000000000000000000000000000000000"
   :seon.db/attributes []
   :seon.db/program []
   :seon.db/initial-data []})

(def ^:private initialization-1
  (assoc initialization-0
         :seon.execution/artifact-digest
         "1111111111111111111111111111111111111111111111111111111111111111"))

(defn- success [request body]
  (protocol/success
   (assoc body ::protocol/request-id (::protocol/request-id request))))

(defn- requested-database [request]
  (if (= initialization-1 (::db/initialization request))
    database-1
    database-0))

(defn- response-for [control request]
  (case (::protocol/operation request)
    :seon.db.protocol.operation/capabilities
    (success request
             {::protocol/capabilities {:seon.db.capability/query true}})

    :seon.db.protocol.operation/ensure-database
    (if (true? (::fail-next-ensure? @control))
      (do
        (swap! control assoc ::fail-next-ensure? false)
        (protocol/failure
         {::protocol/error-kind protocol/database-error
          ::protocol/error "initialization rejected"
          ::protocol/body
          {::protocol/request-id (::protocol/request-id request)}}))
      (let [database (requested-database request)]
        (swap! control assoc ::database database)
        (success request
                 {::protocol/database-name database-name
                  ::db/db database
                  ::protocol/backend :memory})))

    :seon.db.protocol.operation/acquire-database
    (success request
             {::protocol/database-name database-name
              ::db/db (::database @control)
              ::protocol/acquired? true})

    :seon.db.protocol.operation/listen
    (success request {:db-after (::db/db request)
                      ::protocol/listening? true})

    :seon.db.protocol.operation/unlisten
    (success request
             {::protocol/target-request-id
              (::protocol/target-request-id request)
              ::protocol/listening? false})))

(defn- authority-request! [control request]
  (swap! control update ::requests conj request)
  (let [response (response-for control request)]
    (if (and (= protocol/ensure-database-operation
                (::protocol/operation request))
             (true? (::defer-next-ensure? @control)))
      (do
        (swap! control assoc ::defer-next-ensure? false)
        (js/Promise.
         (fn [resolve _]
           (swap! control assoc ::release-deferred! #(resolve response)))))
      (js/Promise.resolve response))))

(defn- with-authority [body]
  (let [original-connect! uds/connect!
        original-request! uds/request!
        original-connected? uds/connected?
        original-close! uds/close!
        session {::session true}
        control
        (atom {::requests []
               ::connect-count 0
               ::connected? true
               ::database database-0
               ::defer-next-ensure? false
               ::fail-next-ensure? false})]
    (reset! @#'db/!session nil)
    (set! uds/connect!
          (fn [_]
            (swap! control update ::connect-count inc)
            (swap! control assoc ::connected? true)
            (js/Promise.resolve session)))
    (set! uds/request!
          (fn [{::uds/keys [message]}]
            (authority-request! control message)))
    (set! uds/connected? (fn [_] (::connected? @control)))
    (set! uds/close!
          (fn [_]
            (let [connected? (::connected? @control)]
              (swap! control assoc ::connected? false)
              connected?)))
    (-> (js/Promise.resolve (body control))
        (.finally
         (fn []
           (db/close-session!)
           (reset! @#'db/!session nil)
           (set! uds/connect! original-connect!)
           (set! uds/request! original-request!)
           (set! uds/connected? original-connected?)
           (set! uds/close! original-close!))))))

(defn- open!
  ([] (open! nil))
  ([initialization]
   (db/open-session!
    (cond-> {::db/socket-path socket-path
             ::db/database-name database-name
             ::db/backend :memory}
      initialization (assoc ::db/initialization initialization)))))

(defn- operation-requests [control operation]
  (filterv #(= operation (::protocol/operation %)) (::requests @control)))

(defn- clear-requests! [control]
  (swap! control assoc ::requests []))

(defn- defer-next-ensure! [control]
  (swap! control assoc
         ::defer-next-ensure? true
         ::release-deferred! nil))

(defn- release-deferred! [control]
  (js/Promise.
   (fn [resolve reject]
     (js/setTimeout
      (fn []
        (if-let [release (::release-deferred! @control)]
          (do (release) (resolve true))
          (reject (js/Error. "the expected ensure request did not start"))))
      0))))

(deftest open-negotiates-and-acquires-one-ordinary-database-value
  (async done
    (-> (with-authority
          (fn [control]
            (-> (open!)
                (.then
                 (fn [opened]
                   (is (= database-name (::db/database-name opened)))
                   (is (= database-0 (::db/db opened)))
                   (is (= 1 (::connect-count @control)))
                   (is (= [protocol/capabilities-operation
                           protocol/ensure-database-operation
                           protocol/acquire-database-operation]
                          (mapv ::protocol/operation
                                (::requests @control)))))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "session open rejected: " error "\n" (.-stack error)))
           (done))))))

(deftest open-can-decline-database-advanced-events
  (async done
    (-> (with-authority
          (fn [control]
            (-> (db/open-session!
                 {::db/socket-path socket-path
                  ::db/database-name database-name
                  ::db/backend :memory
                  ::db/database-advanced? false})
                (.then
                 (fn [_]
                   (let [request (first
                                  (operation-requests
                                   control
                                   protocol/acquire-database-operation))]
                     (is (false?
                          (::protocol/database-advanced? request)))))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "session opt-out rejected: " error))
           (done))))))

(deftest changed-initialization-reuses-session-and-listener-owner
  (async done
    (-> (with-authority
          (fn [control]
            (-> (open! initialization-0)
                (.then
                 (fn [_]
                   (db/listen! {::db/key :program ::db/handler identity})))
                (.then
                 (fn [listener-key]
                   (is (= :program listener-key))
                   (let [entry-before
                         (get-in @@#'db/!session
                                 [::db/interest-handlers ":program"])]
                     (clear-requests! control)
                     (-> (open! initialization-1)
                         (.then
                          (fn [opened]
                            (let [entry-after
                                  (get-in @@#'db/!session
                                          [::db/interest-handlers ":program"])]
                              (is (= 1 (::connect-count @control)))
                              (is (= [protocol/ensure-database-operation
                                      protocol/acquire-database-operation]
                                     (mapv ::protocol/operation
                                           (::requests @control))))
                              (is (= database-1 (::db/db opened)))
                              (is (identical? (::db/owner entry-before)
                                              (::db/owner entry-after)))))))))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "changed initialization rejected: " error
                          "\n" (.-stack error)))
           (done))))))

(deftest concurrent-identical-cold-initialization-coalesces
  (async done
    (-> (with-authority
          (fn [control]
            (defer-next-ensure! control)
            (let [first-open (open! initialization-1)
                  second-open (open! initialization-1)]
              (-> (release-deferred! control)
                  (.then
                   (fn [_]
                     (js/Promise.all #js [first-open second-open])))
                  (.then
                   (fn [opened]
                     (is (= (aget opened 0) (aget opened 1)))
                     (is (= 1 (::connect-count @control)))
                     (is (= 1
                            (count
                             (operation-requests
                              control
                              protocol/ensure-database-operation))))
                     (is (= 1
                            (count
                             (operation-requests
                              control
                              protocol/acquire-database-operation))))))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "identical initialization did not coalesce: " error
                          "\n" (.-stack error)))
           (done))))))

(deftest changed-initialization-serializes-after-in-flight-initialization
  (async done
    (-> (with-authority
          (fn [control]
            (-> (open!)
                (.then
                 (fn [_]
                   (clear-requests! control)
                   (defer-next-ensure! control)
                   (let [first-open (open! initialization-0)
                         changed-open (open! initialization-1)]
                     (-> (release-deferred! control)
                         (.then
                          (fn [_]
                            (js/Promise.all #js [first-open changed-open])))
                         (.then
                          (fn [opened]
                            (is (= [protocol/ensure-database-operation
                                    protocol/acquire-database-operation
                                    protocol/ensure-database-operation
                                    protocol/acquire-database-operation]
                                   (mapv ::protocol/operation
                                         (::requests @control))))
                            (is (= [initialization-0 initialization-1]
                                   (mapv ::db/initialization
                                         (operation-requests
                                          control
                                          protocol/ensure-database-operation))))
                            (is (= database-1 (::db/db (aget opened 1)))))))))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "changed initialization did not serialize: " error
                          "\n" (.-stack error)))
           (done))))))

(deftest failed-initialization-preserves-cached-database-and-listener-owner
  (async done
    (-> (with-authority
          (fn [control]
            (-> (open!)
                (.then
                 (fn [_]
                   (db/listen! {::db/key :program ::db/handler identity})))
                (.then
                 (fn [_]
                   (let [entry-before
                         (get-in @@#'db/!session
                                 [::db/interest-handlers ":program"])]
                     (clear-requests! control)
                     (swap! control assoc ::fail-next-ensure? true)
                     (-> (open! initialization-1)
                         (.then
                          (fn [_]
                            (is false "failed ensure unexpectedly opened")))
                         (.catch
                          (fn [_]
                            (let [state @@#'db/!session
                                  entry-after
                                  (get-in state
                                          [::db/interest-handlers ":program"])]
                              (is (true? (db/attached?)))
                              (is (= database-0
                                     (get-in state
                                             [::db/databases database-name])))
                              (is (identical? (::db/owner entry-before)
                                              (::db/owner entry-after)))
                              (is (= [protocol/ensure-database-operation]
                                     (mapv ::protocol/operation
                                           (::requests @control))))))))))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "failed ensure damaged the session: " error
                          "\n" (.-stack error)))
           (done))))))
