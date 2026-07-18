(ns seon.web.reactive.call-test
  "The agent-call security boundary and agent-routed invocation.

   (b) The capability gate performs one query at one immutable database value.
       An agent-authored function resolves + is allowed regardless of its
       application namespace or original author; `fs`/core symbols are refused
       because they have no agent-authored source transaction.

   (c) A granted call captures one immutable database value and sends one ordinary
       positional invocation through the supervised execution child."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [async deftest is]]
    [seon.db :as db]
    [seon.execution :as execution]
    [seon.execution.host :as execution.host]
    [seon.runtime.admission :as admission]
    [seon.web.reactive.call :as call]
    [seon.web.reactive.transform :as transform]))

;; A valid 14-char id (`:seon.db/id` is [:string {:min 14 :max 14}]).
(def ^:private agent-id "tst-2606260000")
(def ^:private granted-sym 'my.orders/set-purpose!)

(def ^:private database
  {:db-name "test"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

;; ---------------------------------------------------------------------------
;; (b) Capability gate — pure, no bootstrap.
;; ---------------------------------------------------------------------------

(deftest capability-gate-allows-granted-refuses-everything-else
  (async done
    (let [original-query db/query
          requests (atom [])
          ghost 'my.orders/not-a-real-fn]
      (set! db/query
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve
                (when (= (str granted-sym)
                         (second (:seon.db/args request)))
                  101)))
              ([query-form & inputs]
               (js/Promise.reject
                (ex-info "unexpected positional query"
                         {:seon.db/query query-form
                          :seon.db/args inputs})))))
      (-> (js/Promise.all
           (clj->js
            [(call/capability-check database agent-id granted-sym)
             (call/capability-check database agent-id 'fs/readFileSync)
             (call/capability-check database agent-id 'seon.client/start-agent!)
             (call/capability-check database agent-id ghost)
             (call/capability-check database "someone-else9" granted-sym)]))
          (.then
           (fn [results]
             (let [[granted fs core missing shared]
                   (vec (array-seq results))
                   query (:seon.db/query (first @requests))]
               (is (= agent-id (::call/agent-id granted)))
               (is (= database (:seon.db/db (first @requests))))
               (is (= [agent-id (str granted-sym)]
                      (:seon.db/args (first @requests))))
               (is (some #{'[?function :seon.fn/source _ ?source-tx]} query))
               (is (some #{'[(get-else $ ?function
                               :seon.fn/private? false) ?private]} query))
               (is (some #{'[(= false ?private)]} query))
               (is (some #{'[?source-tx :seon.db/user ?author]} query))
               (is (some #{'[?author :seon.agent/id _]} query))
               (is (some #{'[?process :seon.db.process/id
                             :seon.db.process/repl]} query))
               (is (some #{'(not [?agent :seon.agent/terminated-at _])}
                         query))
               (is (some? (::call/refused fs)))
               (is (some? (::call/refused core)))
               (is (some? (::call/refused missing)))
               (is (= "someone-else9" (::call/agent-id shared))
                   "a different live route agent can use shared authored code")
               (is (= 5 (count @requests))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/query original-query)
             (done)))))))

;; ---------------------------------------------------------------------------
;; (c) Granted invoke crosses one database-value-pinned child boundary.
;; ---------------------------------------------------------------------------

(deftest call-invokes-through-one-database-value-pinned-child-plan
  (async done
    (let [original-prepare execution/prepare-invocations!
          original-invoke execution.host/invoke!
          prepared (atom nil)]
      (set! execution/prepare-invocations!
            (fn [request]
              (reset! prepared request)
              (js/Promise.resolve
               [{::execution/message execution/invoke-message
                 ::execution/protocol-version execution/protocol-version
                 ::execution/agent-id agent-id
                 ::execution/invocation-id "call-test"
                 :seon.db/db database
                 ::execution/function-identity
                 {::execution/function-symbol granted-sym
                  ::execution/source-digest (apply str (repeat 64 "a"))}
                 ::execution/arguments ["hello from call"]
                 ::execution/deadline-ms 9999999999999
                 ::execution/result-limit-bytes 4096}])))
      (set! execution.host/invoke!
            (fn [_]
              (js/Promise.resolve
               {::execution/message execution/result-message
                ::execution/protocol-version execution/protocol-version
                ::execution/invocation-id "call-test"
                :seon.db/db database
                ::execution/result {:my.result/accepted? true}
                ::execution/result-bytes 32})))
      (-> (call/invoke! database agent-id granted-sym ["hello from call"])
          (.then
           (fn [result]
             (is (true? (::call/ok? result)) (pr-str result))
             (is (identical? database (:seon.db/db @prepared)))
             (is (= ["hello from call"]
                    (get-in @prepared
                            [::execution/invocation-plans 0
                             ::execution/arguments])))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! execution/prepare-invocations! original-prepare)
             (set! execution.host/invoke! original-invoke)
             (done)))))))

;; ---------------------------------------------------------------------------
;; (d) HTTP boundary — agent-call injection is refused and malformed args end.
;; The PoC sends a transit value that decodes to a list.
;; the old synthesize-and-eval invoke!, the list spliced in as code and ran
;; (`(js/require "child_process")` etc.). Now the ?args= decode is DATA-ONLY
;; and invoke! is resolve-and-apply — the injected expression can never run.
;; ---------------------------------------------------------------------------

(defn- call-req
  "A Ring request carrying a WHATWG Request with fn and optional args."
  ([fn-sym args-str]
   (call-req fn-sym args-str nil))
  ([fn-sym args-str options]
   (let [base (str "/agent/" agent-id "/call?fn="
                   (js/encodeURIComponent (str fn-sym)))]
     {:seon.http/request
      (js/Request.
       (str "http://seon.test"
            (if args-str
              (str base "&args=" (js/encodeURIComponent args-str))
              base))
       (or options #js {:method "POST"}))
      :path-params {:id agent-id}})))

(deftest datastar-success-is-an-empty-acknowledgement
  (let [req (js/Request. "http://seon.test/agent/id/call"
                         #js {:headers #js {"datastar-request" "true"}})
        response (@#'call/success-response req)]
    (is (= 204 (.-status response)))
    (is (nil? (.-body response))
        "the live feed, not a duplicate body, updates UI")))

(deftest unavailable-runtime-refuses-before-capability-or-body-work
  (async done
    (reset! @#'admission/!state
            {::admission/status :unavailable
             ::admission/reason "test publication failure"})
    (-> (call/handle! (call-req granted-sym nil))
        (.then
         (fn [response]
           (is (= 503 (.-status response)))
           (.text response)))
        (.then
         (fn [body]
           (is (str/includes? body "Runtime program generation"))))
        (.catch (fn [error] (is false (str error))))
        (.finally
         (fn []
           (reset! @#'admission/!state
                   {::admission/status :available
                    ::admission/generation 0})
           (done))))))

(deftest database-failure-is-unavailable-not-a-capability-refusal
  (async done
    (let [original-db db/db
          original-capability call/capability-check
          checks (atom 0)]
      (set! db/db
            (fn
              ([] (js/Promise.resolve
                   {:seon.error/message "authority unavailable"}))
              ([_] (js/Promise.resolve
                    {:seon.error/message "authority unavailable"}))))
      (set! call/capability-check
            (fn [& _] (swap! checks inc)))
      (-> (call/handle! (call-req granted-sym
                                  (transform/encode-args [])))
          (.then
           (fn [response]
             (is (= 503 (.-status response)))
             (is (zero? @checks))
             (.text response)))
          (.then
           (fn [body]
             (is (str/includes? body "authority unavailable"))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! call/capability-check original-capability)
             (done)))))))

(deftest successful-http-call-reuses-the-acquired-database-value
  (async done
    (let [original-db db/db
          original-capability call/capability-check
          original-invoke call/invoke!
          observed (atom [])]
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! call/capability-check
            (fn [database-value route-agent-id _]
              (is (= agent-id route-agent-id))
              (swap! observed conj database-value)
              (js/Promise.resolve {::call/agent-id agent-id})))
      (set! call/invoke!
            (fn [database-value _ _ _]
              (swap! observed conj database-value)
              (js/Promise.resolve {::call/ok? true})))
      (-> (call/handle! (call-req granted-sym
                                  (transform/encode-args ["value"])))
          (.then
           (fn [response]
             (is (= 200 (.-status response)))
             (is (= 2 (count @observed)))
             (is (every? #(identical? database %) @observed))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! call/capability-check original-capability)
             (set! call/invoke! original-invoke)
             (done)))))))

(deftest call-refuses-injected-list-arg-and-never-invokes
  ;; A granted fn (capability passes) called with the list-shaped ?args=. The
  ;; data-only whitelist refuses it with a 422 "bad args" BEFORE invoke!, so the
  ;; injected expression never executes and the granted fn never runs.
  (async done
    (let [payload "[[\"~#list\",[\"~$js/require\",\"child_process\"]]]"
          invocations (atom 0)
          original-db db/db
          original-capability call/capability-check
          original-invoke call/invoke!]
      (set! db/db (fn
                    ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! call/capability-check
            (fn [_ _ _] (js/Promise.resolve {::call/agent-id agent-id})))
      (set! call/invoke! (fn [& _] (swap! invocations inc)))
      (-> (call/handle! (call-req granted-sym payload))
          (.then
           (fn [response]
             (is (= 422 (.-status response)))
             (is (zero? @invocations))
             (.text response)))
          (.then (fn [body] (is (str/includes? body "bad args"))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! call/capability-check original-capability)
             (set! call/invoke! original-invoke)
             (done)))))))

(deftest call-malformed-args-writes-response-not-hang
  ;; Garbage ?args= (not valid transit) → a written 422, not an uncaught
  ;; rejection / hung request. handle! resolves with the response.
  (async done
    (let [original-db db/db
          original-capability call/capability-check]
      (set! db/db (fn
                    ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! call/capability-check
            (fn [_ _ _] (js/Promise.resolve {::call/agent-id agent-id})))
      (-> (call/handle! (call-req granted-sym "not-valid-transit-%%%"))
          (.then
           (fn [response]
             (is (= 422 (.-status response)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! call/capability-check original-capability)
             (done)))))))
