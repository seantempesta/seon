(ns acme.context-test
  (:require
    [acme.context :as context]
    [cljs.test :refer [async deftest is]]
    [seon.agent.ctx :as ctx]
    [seon.db :as db]))

(def ^:private database
  {:db-name "acme"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id
   #uuid "00000000-0000-0000-0000-000000000042"})

(defn- as-database-fn [f]
  (fn
    ([] (f))
    ([request] (f request))))

(defn- as-query-fn [f]
  (fn
    ([request] (f request))
    ([query-form & inputs] (apply f query-form inputs))))

(defn- as-transact-fn [f]
  (fn [& call-args]
    (case (count call-args)
      1 (f (first call-args))
      2 (apply f call-args)
      (throw (ex-info "unexpected transact! arity"
                      {:acme.test/argument-count (count call-args)})))))

(defn- with-fakes
  [{database-fn ::database-fn
    query-fn ::query-fn
    with-agent-fn ::with-agent-fn
    transact-fn ::transact-fn
    remove-fn ::remove-fn
    install-fn ::install-fn
    install-into-fn ::install-into-fn}
   body]
  (let [saved {::database-fn db/db
               ::query-fn db/query
               ::with-agent-fn db/with-agent
               ::transact-fn db/transact!
               ::remove-fn ctx/remove!
               ::install-fn ctx/install!
               ::install-into-fn context/install-into!}]
    (when database-fn (set! db/db (as-database-fn database-fn)))
    (when query-fn (set! db/query (as-query-fn query-fn)))
    (when with-agent-fn (set! db/with-agent with-agent-fn))
    (when transact-fn (set! db/transact! (as-transact-fn transact-fn)))
    (when remove-fn (set! ctx/remove! remove-fn))
    (when install-fn (set! ctx/install! install-fn))
    (when install-into-fn (set! context/install-into! install-into-fn))
    (-> (js/Promise.resolve (body))
        (.finally
          (fn []
            (set! db/db (::database-fn saved))
            (set! db/query (::query-fn saved))
            (set! db/with-agent (::with-agent-fn saved))
            (set! db/transact! (::transact-fn saved))
            (set! ctx/remove! (::remove-fn saved))
            (set! ctx/install! (::install-fn saved))
            (set! context/install-into! (::install-into-fn saved)))))))

(defn- finish [promise done]
  (-> promise
      (.then (fn [_] (done)))
      (.catch (fn [error]
                (is false (str "threw — " error))
                (done)))))

(deftest install-all-reads-one-database-value-and-installs-sequentially
  (async done
    (let [database-calls (atom 0)
          requests (atom [])
          installed (atom [])
          active (atom 0)
          max-active (atom 0)]
      (finish
        (with-fakes
          {::database-fn
           (fn []
             (swap! database-calls inc)
             (js/Promise.resolve database))
           ::query-fn
           (fn [request]
             (swap! requests conj request)
             (js/Promise.resolve [["root"] ["task-1"]]))
           ::install-into-fn
           (fn [id]
             (swap! installed conj id)
             (swap! active inc)
             (swap! max-active max @active)
             (-> (js/Promise.resolve {::ctx/ok? true})
                 (.then (fn [result]
                          (swap! active dec)
                          result))))}
          (fn []
            (-> (context/install-all!)
                (.then
                  (fn [result]
                    (is (= ["root" "task-1"] result))
                    (is (= 1 @database-calls))
                    (is (= database (:seon.db/db (first @requests))))
                    (is (= ["root" "task-1"] @installed))
                    (is (= 1 @max-active)))))))
        done))))

(deftest install-all-surfaces-database-errors-without-querying
  (async done
    (let [database-error {:seon.error/message "database unavailable"}
          query-calls (atom 0)
          install-calls (atom 0)]
      (finish
        (with-fakes
          {::database-fn (fn [] (js/Promise.resolve database-error))
           ::query-fn
           (fn [_]
             (swap! query-calls inc)
             (js/Promise.resolve []))
           ::install-into-fn
           (fn [_]
             (swap! install-calls inc)
             (js/Promise.resolve {::ctx/ok? true}))}
          (fn []
            (-> (context/install-all!)
                (.then
                  (fn [result]
                    (is (= database-error result))
                    (is (zero? @query-calls))
                    (is (zero? @install-calls)))))))
        done))))

(deftest install-all-surfaces-query-errors-without-installing
  (async done
    (let [query-error {:seon.error/message "query failed"}
          install-calls (atom 0)]
      (finish
        (with-fakes
          {::database-fn (fn [] (js/Promise.resolve database))
           ::query-fn (fn [_] (js/Promise.resolve query-error))
           ::install-into-fn
           (fn [_]
             (swap! install-calls inc)
             (js/Promise.resolve {::ctx/ok? true}))}
          (fn []
            (-> (context/install-all!)
                (.then
                  (fn [result]
                    (is (= query-error result))
                    (is (zero? @install-calls)))))))
        done))))

(deftest install-all-stops-at-the-first-installation-error
  (async done
    (let [install-error {:seon.error/message "write failed"}
          installed (atom [])]
      (finish
        (with-fakes
          {::database-fn (fn [] (js/Promise.resolve database))
           ::query-fn
           (fn [_] (js/Promise.resolve [["root"] ["task-1"]]))
           ::install-into-fn
           (fn [id]
             (swap! installed conj id)
             (js/Promise.resolve install-error))}
          (fn []
            (-> (context/install-all!)
                (.then
                  (fn [result]
                    (is (= install-error result))
                    (is (= ["root"] @installed)))))))
        done))))

(deftest install-into-stops-when-removal-fails
  (async done
    (let [remove-error {::ctx/ok? false ::ctx/error "remove failed"}
          transact-calls (atom 0)
          install-calls (atom 0)]
      (finish
        (with-fakes
          {::with-agent-fn (fn [_ thunk] (thunk))
           ::remove-fn (fn [_] (js/Promise.resolve remove-error))
           ::transact-fn
           (fn [_]
             (swap! transact-calls inc)
             (js/Promise.resolve {}))
           ::install-fn
           (fn [_]
             (swap! install-calls inc)
             (js/Promise.resolve {::ctx/ok? true}))}
          (fn []
            (-> (context/install-into! "root")
                (.then
                  (fn [result]
                    (is (= remove-error result))
                    (is (zero? @transact-calls))
                    (is (zero? @install-calls)))))))
        done))))

(deftest install-into-stops-when-the-canvas-transaction-fails
  (async done
    (let [transaction-error {:seon.error/message "canvas failed"}
          install-calls (atom 0)]
      (finish
        (with-fakes
          {::with-agent-fn (fn [_ thunk] (thunk))
           ::remove-fn (fn [_] (js/Promise.resolve {::ctx/ok? true}))
           ::transact-fn (fn [_] (js/Promise.resolve transaction-error))
           ::install-fn
           (fn [_]
             (swap! install-calls inc)
             (js/Promise.resolve {::ctx/ok? true}))}
          (fn []
            (-> (context/install-into! "root")
                (.then
                  (fn [result]
                    (is (= transaction-error result))
                    (is (zero? @install-calls)))))))
        done))))

(deftest install-into-surfaces-the-context-installation-error
  (async done
    (let [install-error {::ctx/ok? false ::ctx/error "install failed"}]
      (finish
        (with-fakes
          {::with-agent-fn (fn [_ thunk] (thunk))
           ::remove-fn (fn [_] (js/Promise.resolve {::ctx/ok? true}))
           ::transact-fn (fn [_] (js/Promise.resolve {}))
           ::install-fn (fn [_] (js/Promise.resolve install-error))}
          (fn []
            (-> (context/install-into! "root")
                (.then (fn [result] (is (= install-error result)))))))
        done))))
